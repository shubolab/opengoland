package signature

import (
	"context"
	"errors"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"sort"
	"strings"
)

type parameter struct {
	row    Row
	field  *ast.Field
	ident  *ast.Ident
	object types.Object
	typ    types.Type
}
type declaration struct {
	file                *fileInfo
	object              *types.Func
	ft                  *ast.FuncType
	name                *ast.Ident
	body                *ast.BlockStmt
	node                ast.Node
	key                 string
	parameters, results []parameter
}

func objectKey(fs *token.FileSet, obj *types.Func) string {
	obj = obj.Origin()
	p := fs.PositionFor(obj.Pos(), false)
	pkg := ""
	if obj.Pkg() != nil {
		pkg = obj.Pkg().Path()
	}
	return fmt.Sprintf("%s:%s:%d:%s", pkg, p.Filename, p.Offset, obj.Name())
}
func (a *analysis) collectDeclarations() {
	paths := make([]string, 0, len(a.files))
	for p := range a.files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, path := range paths {
		fi := a.files[path]
		ast.Inspect(fi.ast, func(n ast.Node) bool {
			var ft *ast.FuncType
			var name *ast.Ident
			var body *ast.BlockStmt
			switch x := n.(type) {
			case *ast.FuncDecl:
				ft, name, body = x.Type, x.Name, x.Body
			case *ast.Field:
				if len(x.Names) == 1 {
					ft, _ = x.Type.(*ast.FuncType)
					if ft != nil {
						name = x.Names[0]
					}
				}
			}
			if ft == nil || name == nil {
				return true
			}
			obj, _ := fi.pkg.TypesInfo.Defs[name].(*types.Func)
			if obj == nil {
				return true
			}
			d := &declaration{file: fi, object: obj, ft: ft, name: name, body: body, node: n, key: objectKey(a.fset, obj)}
			sig, _ := obj.Type().(*types.Signature)
			if sig == nil {
				return true
			}
			d.parameters = flatten(fi, ft.Params, sig.Params(), sig.Variadic(), d.key, "p")
			d.results = flatten(fi, ft.Results, sig.Results(), false, d.key, "r")
			a.decls = append(a.decls, d)
			return true
		})
	}
}
func flatten(fi *fileInfo, list *ast.FieldList, tuple *types.Tuple, variadic bool, key, kind string) []parameter {
	out := []parameter{}
	if list == nil {
		return out
	}
	for _, field := range list.List {
		names := field.Names
		if len(names) == 0 {
			names = []*ast.Ident{nil}
		}
		for _, id := range names {
			i := len(out)
			if i >= tuple.Len() {
				break
			}
			v := tuple.At(i)
			name := ""
			if id != nil {
				name = id.Name
			}
			isVar := variadic && i == tuple.Len()-1
			text := fi.text(field.Type)
			if isVar {
				text = strings.TrimPrefix(text, "...")
			}
			old := i
			row := Row{RowID: hash([]byte(key + ":" + kind + fmt.Sprint(i)))[:20], OldIndex: &old, Name: name, Type: text, Variadic: isVar}
			var obj types.Object
			if id != nil {
				obj = fi.pkg.TypesInfo.Defs[id]
			}
			out = append(out, parameter{row, field, id, obj, v.Type()})
		}
	}
	return out
}
func rows(params []parameter) []Row {
	result := make([]Row, len(params))
	for i, p := range params {
		result[i] = p.row
	}
	return result
}
func (a *analysis) target(target Target) (*declaration, error) {
	path, err := uriPath(target.URI)
	if err != nil {
		return nil, err
	}
	fi := a.files[path]
	if fi == nil {
		return nil, errors.New("target source is excluded from the selected Go build context or outside the workspace")
	}
	offset, err := byteOffset(fi.source.Text, target.Position)
	if err != nil {
		return nil, err
	}
	for _, d := range a.decls {
		if d.file == fi && offset >= fi.offset(d.ft.Pos()) && offset <= fi.offset(d.ft.End()) {
			return d, nil
		}
	}
	var obj *types.Func
	best := len(fi.source.Text) + 1
	ast.Inspect(fi.ast, func(n ast.Node) bool {
		if n == nil {
			return false
		}
		start, end := fi.offset(n.Pos()), fi.offset(n.End())
		if offset < start || offset > end {
			return false
		}
		if id, ok := n.(*ast.Ident); ok {
			if f, ok := fi.pkg.TypesInfo.ObjectOf(id).(*types.Func); ok && end-start < best {
				obj = f
				best = end - start
			}
		}
		return true
	})
	if obj != nil {
		key := objectKey(a.fset, obj)
		for _, d := range a.decls {
			if d.key == key {
				return d, nil
			}
		}
	}
	return nil, errors.New("place the caret on a workspace function/method declaration or its direct call")
}
func (a *analysis) symbolID(d *declaration) string {
	return hash([]byte(d.key + ":" + hash([]byte(d.file.source.Text)) + ":" + d.file.text(d.ft)))
}
func (a *analysis) relationClosure(target *declaration) []*declaration {
	relations := a.methodRelations(target.object.Name())
	selected := map[string]*declaration{target.key: target}
	changed := true
	for changed {
		changed = false
		for _, candidate := range a.decls {
			if selected[candidate.key] != nil || candidate.object.Name() != target.object.Name() {
				continue
			}
			for _, d := range selected {
				if relatedMethods(d.object, candidate.object) || relations.edges[d.key][candidate.key] {
					selected[candidate.key] = candidate
					changed = true
					break
				}
			}
		}
	}
	out := make([]*declaration, 0, len(selected))
	for _, d := range a.decls {
		if selected[d.key] != nil {
			out = append(out, d)
		}
	}
	return out
}
func relatedMethods(a, b *types.Func) bool {
	as, _ := a.Type().(*types.Signature)
	bs, _ := b.Type().(*types.Signature)
	if as == nil || bs == nil || as.Recv() == nil || bs.Recv() == nil {
		return false
	}
	ai, aiface := as.Recv().Type().Underlying().(*types.Interface)
	bi, biface := bs.Recv().Type().Underlying().(*types.Interface)
	if !aiface && !biface {
		return false
	}
	if aiface {
		ai.Complete()
		if implementsAcrossVariants(bs.Recv().Type(), ai) || implementsAcrossVariants(types.NewPointer(bs.Recv().Type()), ai) {
			return sameMethodShape(as, bs)
		}
	}
	if biface {
		bi.Complete()
		if implementsAcrossVariants(as.Recv().Type(), bi) || implementsAcrossVariants(types.NewPointer(as.Recv().Type()), bi) {
			return sameMethodShape(as, bs)
		}
	}
	return false
}
func sameMethodShape(a, b *types.Signature) bool {
	return a.Variadic() == b.Variadic() && tupleShape(a.Params()) == tupleShape(b.Params()) && tupleShape(a.Results()) == tupleShape(b.Results())
}
func familyKeys(decls []*declaration) map[string]bool {
	m := map[string]bool{}
	for _, d := range decls {
		m[d.key] = true
	}
	return m
}
func calledObject(info *types.Info, expr ast.Expr) *types.Func {
	switch x := expr.(type) {
	case *ast.ParenExpr:
		return calledObject(info, x.X)
	case *ast.IndexExpr:
		return calledObject(info, x.X)
	case *ast.IndexListExpr:
		return calledObject(info, x.X)
	case *ast.Ident:
		f, _ := info.ObjectOf(x).(*types.Func)
		return f
	case *ast.SelectorExpr:
		f, _ := info.ObjectOf(x.Sel).(*types.Func)
		return f
	}
	return nil
}
func (a *analysis) valueCount(keys map[string]bool) int {
	count := 0
	for _, fi := range a.files {
		ast.Inspect(fi.ast, func(n ast.Node) bool {
			id, ok := n.(*ast.Ident)
			if !ok {
				return true
			}
			obj, ok := fi.pkg.TypesInfo.Uses[id].(*types.Func)
			if !ok || !keys[objectKey(a.fset, obj)] {
				return true
			}
			expr := ast.Node(id)
			if sel, ok := fi.parents[id].(*ast.SelectorExpr); ok && sel.Sel == id {
				expr = sel
			}
			for {
				switch p := fi.parents[expr].(type) {
				case *ast.IndexExpr:
					expr = p
				case *ast.IndexListExpr:
					expr = p
				case *ast.ParenExpr:
					expr = p
				default:
					goto done
				}
			}
		done:
			if c, ok := fi.parents[expr].(*ast.CallExpr); !ok || c.Fun != expr {
				count++
			}
			return true
		})
	}
	return count
}
func Inspect(ctx context.Context, req Request) (InspectResponse, error) {
	s, err := newSnapshot(req)
	if err != nil {
		return InspectResponse{}, err
	}
	defer s.close()
	a, err := s.load(ctx, nil)
	if err != nil {
		return InspectResponse{}, err
	}
	d, err := a.target(req.Target)
	if err != nil {
		return InspectResponse{}, err
	}
	related := a.relationClosure(d)
	kind := "function"
	receiver := ""
	sig := d.object.Type().(*types.Signature)
	if sig.Recv() != nil {
		kind = "method"
		receiver = types.TypeString(sig.Recv().Type(), func(p *types.Package) string { return p.Name() })
		if _, ok := sig.Recv().Type().Underlying().(*types.Interface); ok {
			kind = "interfaceMethod"
		}
	}
	out := InspectResponse{SymbolID: a.symbolID(d), ConfigurationDigest: s.digest, Name: d.name.Name, Kind: kind, URI: d.file.source.URI, Range: sourceRange(d.file.source, d.file.offset(d.name.Pos()), d.file.offset(d.ft.End())), Receiver: receiver, Parameters: rows(d.parameters), Results: rows(d.results), RelatedDeclarations: len(related), FunctionValues: a.valueCount(familyKeys(related)), Coverage: a.coverage(), Conflicts: []Conflict{}}
	if err = s.unchanged(); err != nil {
		return InspectResponse{}, err
	}
	return out, nil
}

// Test variants deliberately contain distinct go/types package objects for the
// same physical declaration. Canonical named-type paths bridge only that
// duplication; ordinary Go interface assignability remains the first test.
func implementsAcrossVariants(receiver types.Type, iface *types.Interface) bool {
	if types.Implements(receiver, iface) {
		return true
	}
	if !iface.IsMethodSet() {
		return false
	}
	methods := types.NewMethodSet(receiver)
	for i := 0; i < iface.NumMethods(); i++ {
		wanted := iface.Method(i)
		selection := methods.Lookup(wanted.Pkg(), wanted.Name())
		if selection == nil {
			return false
		}
		got, _ := selection.Obj().(*types.Func)
		if got == nil {
			return false
		}
		a, _ := wanted.Type().(*types.Signature)
		b, _ := got.Type().(*types.Signature)
		if a == nil || b == nil || !sameMethodShape(a, b) {
			return false
		}
	}
	return true
}
func tupleShape(tuple *types.Tuple) string {
	var parts []string
	for i := 0; i < tuple.Len(); i++ {
		parts = append(parts, types.TypeString(tuple.At(i).Type(), func(p *types.Package) string { return p.Path() }))
	}
	return strings.Join(parts, ";")
}
