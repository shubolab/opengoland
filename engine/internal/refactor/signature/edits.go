package signature

import (
	"bytes"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"go/types"
	"golang.org/x/tools/go/ast/astutil"
	"sort"
	"strconv"
	"strings"
)

type edits struct{ files map[string][]ByteEdit }

func newEdits() *edits { return &edits{files: map[string][]ByteEdit{}} }
func (e *edits) add(path string, edit ByteEdit) error {
	if edit.Start > edit.End || edit.Start < 0 {
		return fmt.Errorf("invalid source edit")
	}
	previous := e.files[path]
	out := previous[:0]
	for _, old := range previous {
		if old.Start == edit.Start && old.End == edit.End && old.NewText == edit.NewText {
			return nil
		}
		if edit.Start <= old.Start && edit.End >= old.End {
			continue
		}
		if old.Start < edit.End && edit.Start < old.End {
			return fmt.Errorf("overlapping source edits at %s:%d", path, edit.Start)
		}
		if old.Start == edit.Start && old.End == edit.End {
			return fmt.Errorf("conflicting source edits at %s:%d", path, edit.Start)
		}
		out = append(out, old)
	}
	e.files[path] = append(out, edit)
	return nil
}
func (e *edits) text(fi *fileInfo, n ast.Node) string {
	if n == nil {
		return ""
	}
	start, end := fi.offset(n.Pos()), fi.offset(n.End())
	out := fi.source.Text[start:end]
	list := []ByteEdit{}
	for _, edit := range e.files[fi.source.Path] {
		if edit.Start >= start && edit.End <= end {
			list = append(list, edit)
		}
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Start > list[j].Start })
	for _, edit := range list {
		out = out[:edit.Start-start] + edit.NewText + out[edit.End-start:]
	}
	return out
}
func (e *edits) candidates(a *analysis) (map[string]string, error) {
	out := map[string]string{}
	for path, list := range e.files {
		fi := a.files[path]
		if fi == nil {
			return nil, fmt.Errorf("edit refers to non-workspace source %s", path)
		}
		text := fi.source.Text
		sort.Slice(list, func(i, j int) bool { return list[i].Start > list[j].Start })
		for _, edit := range list {
			if edit.End > len(text) {
				return nil, fmt.Errorf("source edit exceeds file")
			}
			text = text[:edit.Start] + edit.NewText + text[edit.End:]
		}
		formatted, err := format.Source([]byte(text))
		if err != nil {
			return nil, fmt.Errorf("generated Go is invalid at %s: %w", path, err)
		}
		formatted, err = cleanUnusedImports(fi, formatted)
		if err != nil {
			return nil, err
		}
		if string(formatted) != fi.source.Text {
			out[path] = string(formatted)
		}
	}
	return out, nil
}

type importManager struct {
	fi        *fileInfo
	aliases   map[string]string
	additions map[string]string
	names     map[string]bool
}

func newImports(fi *fileInfo) *importManager {
	m := &importManager{fi: fi, aliases: map[string]string{}, additions: map[string]string{}, names: map[string]bool{}}
	ast.Inspect(fi.ast, func(n ast.Node) bool {
		if id, ok := n.(*ast.Ident); ok {
			m.names[id.Name] = true
		}
		return true
	})
	for _, spec := range fi.ast.Imports {
		path, err := strconv.Unquote(spec.Path.Value)
		if err != nil {
			continue
		}
		var obj *types.PkgName
		if spec.Name != nil {
			obj, _ = fi.pkg.TypesInfo.Defs[spec.Name].(*types.PkgName)
		} else {
			obj, _ = fi.pkg.TypesInfo.Implicits[spec].(*types.PkgName)
		}
		if obj != nil {
			m.aliases[path] = obj.Name()
		}
	}
	return m
}
func (m *importManager) qualifier(pkg *types.Package, pos token.Pos) (string, error) {
	if pkg == nil || pkg.Path() == m.fi.pkg.Types.Path() {
		return "", nil
	}
	if alias, ok := m.aliases[pkg.Path()]; ok && alias != "_" {
		if alias == "." {
			return "", nil
		}
		if scope := m.fi.pkg.Types.Scope().Innermost(pos); scope != nil {
			_, obj := scope.LookupParent(alias, pos)
			if obj != nil {
				pn, ok := obj.(*types.PkgName)
				if !ok || pn.Imported().Path() != pkg.Path() {
					return "", fmt.Errorf("import %s is shadowed at this use; choose a non-shadowed import alias", alias)
				}
			}
		}
		return alias, nil
	}
	if alias := m.additions[pkg.Path()]; alias != "" {
		return alias, nil
	}
	if m.aliases[pkg.Path()] == "_" {
		return "", fmt.Errorf("package %s has a side-effect-only import; change its alias before using it in the signature", pkg.Path())
	}
	alias := pkg.Name()
	if m.names[alias] {
		alias = "openGoland" + strings.ToUpper(alias[:1]) + alias[1:]
		for i := 2; m.names[alias]; i++ {
			alias = pkg.Name() + fmt.Sprint(i)
		}
	}
	m.names[alias] = true
	m.additions[pkg.Path()] = alias
	return alias, nil
}
func (m *importManager) render(t types.Type, pos token.Pos) (string, error) {
	var failure error
	s := types.TypeString(t, func(p *types.Package) string {
		q, e := m.qualifier(p, pos)
		if e != nil {
			failure = e
		}
		return q
	})
	return s, failure
}
func (m *importManager) emit(e *edits) error {
	if len(m.additions) == 0 {
		return nil
	}
	composed := m.fi.source.Text
	pending := append([]ByteEdit{}, e.files[m.fi.source.Path]...)
	sort.Slice(pending, func(i, j int) bool { return pending[i].Start > pending[j].Start })
	for _, edit := range pending {
		composed = composed[:edit.Start] + edit.NewText + composed[edit.End:]
	}
	syntax, parseErr := parser.ParseFile(token.NewFileSet(), m.fi.source.Path, composed, parser.ParseComments)
	if parseErr != nil {
		return parseErr
	}
	selectors := selectorNames(syntax)
	paths := make([]string, 0, len(m.additions))
	for p, alias := range m.additions {
		if selectors[alias] {
			paths = append(paths, p)
		}
	}
	sort.Strings(paths)
	if len(paths) == 0 {
		return nil
	}
	var lines strings.Builder
	lines.WriteString("\n")
	for _, p := range paths {
		fmt.Fprintf(&lines, "import %s %s\n", m.additions[p], strconv.Quote(p))
	}
	at := m.fi.offset(m.fi.ast.Name.End())
	for _, d := range m.fi.ast.Decls {
		g, ok := d.(*ast.GenDecl)
		if ok && g.Tok == token.IMPORT {
			at = m.fi.offset(g.End())
		}
	}
	return e.add(m.fi.source.Path, ByteEdit{at, at, lines.String()})
}
func qualifiedExpression(a *analysis, target *declaration, to *fileInfo, imports *importManager, pos token.Pos, text string, bindings map[string]string, preferBindings bool) (string, error) {
	for _, binding := range bindings {
		if token.IsIdentifier(binding) {
			imports.names[binding] = true
		}
	}
	expr, err := parser.ParseExprFrom(a.fset, "signature-expression.go", text, parser.AllErrors)
	if err != nil {
		return "", err
	}
	info := &types.Info{Uses: map[*ast.Ident]types.Object{}, Defs: map[*ast.Ident]types.Object{}, Types: map[ast.Expr]types.TypeAndValue{}}
	scopePos := target.ft.End() - 1
	if target.body != nil {
		scopePos = target.body.Lbrace + 1
	}
	// CheckExpr resolves identifiers against the original declaration scope. New
	// row bindings are checked again in the complete candidate package.
	_ = types.CheckExpr(a.fset, target.file.pkg.Types, scopePos, expr, info)
	parents := map[ast.Node]ast.Node{}
	var stack []ast.Node
	ast.Inspect(expr, func(n ast.Node) bool {
		if n == nil {
			stack = stack[:len(stack)-1]
			return false
		}
		if len(stack) > 0 {
			parents[n] = stack[len(stack)-1]
		}
		stack = append(stack, n)
		return true
	})
	expressionObjects := map[types.Object]bool{}
	for _, object := range info.Defs {
		if object != nil {
			expressionObjects[object] = true
		}
	}
	originalBindings := map[types.Object]string{}
	for _, parameter := range append(append([]parameter{}, target.parameters...), target.results...) {
		if binding, ok := bindings[parameter.row.Name]; ok && parameter.object != nil {
			originalBindings[parameter.object] = binding
		}
	}
	var replacements []ByteEdit
	var fail error
	ast.Inspect(expr, func(n ast.Node) bool {
		id, ok := n.(*ast.Ident)
		if !ok {
			return true
		}
		if _, defined := info.Defs[id]; defined {
			return true
		}
		if sel, ok := parents[id].(*ast.SelectorExpr); ok && sel.Sel == id {
			return true
		}
		if kv, ok := parents[id].(*ast.KeyValueExpr); ok && kv.Key == id {
			if obj, ok := info.Uses[id].(*types.Var); ok && obj.IsField() {
				return true
			}
			if info.Uses[id] == nil {
				fail = fmt.Errorf("default expression has an unresolved literal key %s", id.Name)
				return false
			}
		}
		replacement := ""
		obj := info.Uses[id]
		if preferBindings && !expressionObjects[obj] {
			if bound, ok := bindings[id.Name]; ok {
				replacement = bound
			}
		}
		if bound, ok := originalBindings[obj]; ok && replacement == "" {
			replacement = bound
		} else if obj == nil {
			if bound, ok := bindings[id.Name]; ok {
				replacement = bound
			}
		}

		if replacement == "" {
			if v, ok := obj.(*types.Var); ok && !v.IsField() && v.Pkg() != nil && v.Parent() != v.Pkg().Scope() && !expressionObjects[obj] {
				fail = fmt.Errorf("declaration-local variable %s has no binding at this call site", id.Name)
				return false
			}
			if obj == nil && types.Universe.Lookup(id.Name) == nil {
				fail = fmt.Errorf("identifier %s cannot be resolved in the declaration context", id.Name)
				return false
			}
		}
		if replacement == "" {
			if pn, ok := obj.(*types.PkgName); ok {
				q, e := imports.qualifier(pn.Imported(), pos)
				if e != nil {
					fail = e
					return false
				}
				replacement = q
			} else if obj != nil && obj.Pkg() != nil && obj.Parent() == obj.Pkg().Scope() && obj.Pkg().Path() != to.pkg.Types.Path() {
				if !obj.Exported() {
					fail = fmt.Errorf("default expression refers to unexported %s.%s outside its package", obj.Pkg().Path(), obj.Name())
					return false
				}
				q, e := imports.qualifier(obj.Pkg(), pos)
				if e != nil {
					fail = e
					return false
				}
				if q != "" {
					replacement = q + "." + obj.Name()
				}
			}
		}
		if replacement != "" && replacement != id.Name {
			file := a.fset.File(id.Pos())
			replacements = append(replacements, ByteEdit{file.Offset(id.Pos()), file.Offset(id.End()), replacement})
		}
		return true
	})
	if fail != nil {
		return "", fail
	}
	sort.Slice(replacements, func(i, j int) bool { return replacements[i].Start > replacements[j].Start })
	for _, edit := range replacements {
		text = text[:edit.Start] + edit.NewText + text[edit.End:]
	}
	return text, nil
}

func selectorNames(file *ast.File) map[string]bool {
	names := map[string]bool{}
	ast.Inspect(file, func(n ast.Node) bool {
		if sel, ok := n.(*ast.SelectorExpr); ok {
			if id, ok := sel.X.(*ast.Ident); ok {
				names[id.Name] = true
			}
		}
		return true
	})
	return names
}
func cleanUnusedImports(fi *fileInfo, source []byte) ([]byte, error) {
	fs := token.NewFileSet()
	file, err := parser.ParseFile(fs, fi.source.Path, source, parser.ParseComments)
	if err != nil {
		return nil, err
	}
	names := selectorNames(file)
	original := newImports(fi)
	changed := false
	for _, spec := range append([]*ast.ImportSpec{}, file.Imports...) {
		path, e := strconv.Unquote(spec.Path.Value)
		if e != nil {
			return nil, e
		}
		alias := original.aliases[path]
		spelling := ""
		if spec.Name != nil {
			alias = spec.Name.Name
			spelling = alias
		}
		if path == "C" || alias == "_" || alias == "." || alias == "" || names[alias] {
			continue
		}
		if astutil.DeleteNamedImport(fs, file, spelling, path) {
			changed = true
		}
	}
	if !changed {
		return source, nil
	}
	var output bytes.Buffer
	if err = format.Node(&output, fs, file); err != nil {
		return nil, err
	}
	return output.Bytes(), nil
}
