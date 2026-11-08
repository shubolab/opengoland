package structs

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"sort"
	"unicode"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

type field struct {
	wire        Field
	variable    *types.Var
	typeSource  string
	position    signature.Range
	declaration *ast.Field
}
type analysis struct {
	snapshot      *signature.SemanticSnapshot
	view          signature.SemanticAnalysis
	target, owner signature.SemanticFile
	spec          *ast.TypeSpec
	named         *types.Named
	structure     *types.Struct
	fields        []field
	inspection    InspectResponse
}

func digest(value any) string {
	data, _ := json.Marshal(value)
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}
func upperFirst(value string) string {
	r := []rune(value)
	if len(r) > 0 {
		r[0] = unicode.ToUpper(r[0])
	}
	return string(r)
}
func parameterName(value string) string {
	r := []rune(value)
	end := 0
	for end < len(r) && unicode.IsUpper(r[end]) {
		end++
	}
	if end > 1 && end < len(r) && unicode.IsLower(r[end]) {
		end--
	}
	if end == 0 && len(r) > 0 {
		end = 1
	}
	for i := 0; i < end; i++ {
		r[i] = unicode.ToLower(r[i])
	}
	name := string(r)
	if !token.IsIdentifier(name) || name == "_" {
		name += "Value"
	}
	return name
}
func fresh(base string, used map[string]bool) string {
	name := base
	for i := 2; used[name]; i++ {
		name = fmt.Sprintf("%s%d", base, i)
	}
	used[name] = true
	return name
}
func Inspect(ctx context.Context, request Request) (InspectResponse, error) {
	a, e := open(ctx, request)
	if e != nil {
		return InspectResponse{}, e
	}
	defer a.snapshot.Close()
	if e = a.snapshot.Unchanged(); e != nil {
		return InspectResponse{}, e
	}
	return a.inspection, nil
}

func open(ctx context.Context, request Request) (*analysis, error) {
	if e := ctx.Err(); e != nil {
		return nil, e
	}
	s, e := signature.OpenSemanticSnapshot(request.Request)
	if e != nil {
		return nil, e
	}
	fail := true
	defer func() {
		if fail {
			s.Close()
		}
	}()
	view, e := s.Load(ctx, nil)
	if e != nil {
		return nil, e
	}
	path, e := signature.LocalPath(request.Target.URI)
	if e != nil {
		return nil, e
	}
	target, ok := view.Files[path]
	if !ok {
		return nil, fmt.Errorf("select a local package-level named struct or one of its fields")
	}
	offset, e := signature.UTF16Offset(target.Text, request.Target.Position)
	if e != nil {
		return nil, e
	}
	var object *types.TypeName
	smallest := len(target.Text) + 1
	ast.Inspect(target.AST, func(node ast.Node) bool {
		spec, ok := node.(*ast.TypeSpec)
		if !ok {
			return true
		}
		start, end := view.Fset.PositionFor(spec.Name.Pos(), false).Offset, view.Fset.PositionFor(spec.End(), false).Offset
		if offset >= start && offset <= end && end-start < smallest {
			candidate, _ := target.Package.TypesInfo.Defs[spec.Name].(*types.TypeName)
			if candidate != nil {
				object = candidate
				smallest = end - start
			}
		}
		return true
	})
	if object == nil {
		ast.Inspect(target.AST, func(node ast.Node) bool {
			id, ok := node.(*ast.Ident)
			if !ok {
				return true
			}
			start, end := view.Fset.PositionFor(id.Pos(), false).Offset, view.Fset.PositionFor(id.End(), false).Offset
			if offset >= start && offset <= end {
				object, _ = target.Package.TypesInfo.ObjectOf(id).(*types.TypeName)
			}
			return true
		})
	}
	if object == nil {
		return nil, fmt.Errorf("place the caret on a named struct or one of its fields")
	}
	base := object.Type()
	if object.IsAlias() {
		if alias, ok := base.(*types.Alias); ok && alias.TypeParams().Len() > 0 {
			return nil, fmt.Errorf("generic aliases need an explicit canonical struct selection")
		}
		base = types.Unalias(base)
		if pointer, ok := base.(*types.Pointer); ok {
			base = types.Unalias(pointer.Elem())
		}
	}
	named, ok := base.(*types.Named)
	if !ok || named.Obj().Pkg() == nil || named.Obj().Pkg().Path() != target.Package.Types.Path() || named.Obj().Parent() != named.Obj().Pkg().Scope() {
		return nil, fmt.Errorf("helpers require a package-level struct defined in this Go package")
	}
	if object.IsAlias() && (named.TypeParams().Len() > 0 || named.TypeArgs().Len() > 0) {
		return nil, fmt.Errorf("select the canonical generic struct instead of an alias to its instantiation")
	}
	structure, ok := named.Underlying().(*types.Struct)
	if !ok {
		return nil, fmt.Errorf("the selected type is not a defined struct")
	}
	a := &analysis{snapshot: s, view: view, target: target, named: named, structure: structure, fields: []field{}}
	paths := []string{}
	for path := range view.Files {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	for _, path := range paths {
		file := view.Files[path]
		if file.Package.Types.Path() != named.Obj().Pkg().Path() {
			continue
		}
		for _, decl := range file.AST.Decls {
			gen, ok := decl.(*ast.GenDecl)
			if !ok {
				continue
			}
			for _, node := range gen.Specs {
				spec, ok := node.(*ast.TypeSpec)
				if ok && !spec.Assign.IsValid() && spec.Name.Name == named.Obj().Name() {
					a.owner = file
					a.spec = spec
				}
			}
		}
	}
	if a.spec == nil {
		return nil, fmt.Errorf("the canonical struct definition is not editable in this active context")
	}
	canonical, ok := a.owner.Package.Types.Scope().Lookup(named.Obj().Name()).(*types.TypeName)
	if !ok {
		return nil, fmt.Errorf("the selected struct has no canonical type identity")
	}
	a.named = canonical.Type().(*types.Named)
	a.structure = a.named.Underlying().(*types.Struct)
	start, end := view.Fset.PositionFor(a.spec.Name.Pos(), false).Offset, view.Fset.PositionFor(a.spec.End(), false).Offset
	response := InspectResponse{ConfigurationDigest: s.Digest(), StructID: digest([]any{a.owner.URI, a.named.Obj().Name(), start, a.owner.Text}), Name: a.named.Obj().Name(), URI: a.owner.URI, Range: signature.UTF16Range(a.owner.Text, start, end), TypeParameters: []TypeParameter{}, ConstructorName: "New" + upperFirst(a.named.Obj().Name()), Fields: []Field{}, Coverage: view.Coverage, Conflicts: []signature.Conflict{}}
	if ast.IsGenerated(a.owner.AST) {
		response.Conflicts = append(response.Conflicts, a.conflict("generated-file", "Choose a struct in a source file which is not generated"))
	}
	for i := 0; i < a.named.TypeParams().Len(); i++ {
		p := a.named.TypeParams().At(i)
		response.TypeParameters = append(response.TypeParameters, TypeParameter{Name: p.Obj().Name(), Constraint: types.TypeString(p.Constraint(), func(pkg *types.Package) string { return pkg.Name() })})
	}
	if a.owner.Package.Types.Scope().Lookup(response.ConstructorName) != nil {
		response.ConstructorConflict = "The suggested constructor name already exists in this package"
	}
	declarations := make([]*ast.Field, a.structure.NumFields())
	rawTypes := make([]string, a.structure.NumFields())
	ranges := make([]signature.Range, a.structure.NumFields())
	index := 0
	if literal, ok := a.spec.Type.(*ast.StructType); ok {
		for _, decl := range literal.Fields.List {
			count := len(decl.Names)
			if count == 0 {
				count = 1
			}
			typeStart, typeEnd := view.Fset.PositionFor(decl.Type.Pos(), false).Offset, view.Fset.PositionFor(decl.Type.End(), false).Offset
			for j := 0; j < count && index < len(declarations); j++ {
				declarations[index] = decl
				rawTypes[index] = a.owner.Text[typeStart:typeEnd]
				fieldStart, fieldEnd := view.Fset.PositionFor(decl.Pos(), false).Offset, view.Fset.PositionFor(decl.End(), false).Offset
				if len(decl.Names) > 0 {
					fieldStart = view.Fset.PositionFor(decl.Names[j].Pos(), false).Offset
					fieldEnd = view.Fset.PositionFor(decl.Names[j].End(), false).Offset
				}
				ranges[index] = signature.UTF16Range(a.owner.Text, fieldStart, fieldEnd)
				if target.Path == a.owner.Path {
					declStart, declEnd := view.Fset.PositionFor(decl.Pos(), false).Offset, view.Fset.PositionFor(decl.End(), false).Offset
					if offset >= declStart && offset <= declEnd && (response.CaretFieldID == "" || len(decl.Names) > 0 && offset >= fieldStart && offset <= fieldEnd) {
						response.CaretFieldID = digest([]any{response.StructID, index, a.structure.Field(index).Name()})
					}
				}
				index++
			}
		}
	}
	im := newImports(a)
	used := map[string]bool{"r": true, a.named.Obj().Name(): true}
	for alias := range im.used {
		used[alias] = true
	}
	for _, p := range response.TypeParameters {
		used[p.Name] = true
	}
	for i := 0; i < a.structure.NumFields(); i++ {
		variable := a.structure.Field(i)
		name := variable.Name()
		wire := Field{FieldID: digest([]any{response.StructID, i, name}), Name: name, Names: []string{name}, Type: types.TypeString(variable.Type(), func(pkg *types.Package) string { return pkg.Name() }), Exported: variable.Exported(), Embedded: variable.Embedded(), Editable: name != "_", ParameterName: fresh(parameterName(name), used), GetterName: "Get" + upperFirst(name), SetterName: "Set" + upperFirst(name)}
		if name == "_" {
			wire.Reason = "Blank fields cannot be selected or initialized by name"
		} else if !variable.Exported() && variable.Pkg() != nil && variable.Pkg().Path() != a.owner.Package.Types.Path() {
			wire.Editable = false
			wire.Reason = "This field is private to another package"
		} else if rawTypes[i] == "" {
			if _, e := newImports(a).render(variable.Type()); e != nil {
				wire.Editable = false
				wire.Reason = e.Error()
			}
		}
		wire.GetterConflict = a.methodConflict(wire.GetterName)
		wire.SetterConflict = a.methodConflict(wire.SetterName)
		if declarations[i] == nil {
			ranges[i] = response.Range
		}
		a.fields = append(a.fields, field{wire: wire, variable: variable, typeSource: rawTypes[i], position: ranges[i], declaration: declarations[i]})
		response.Fields = append(response.Fields, wire)
	}
	response.InspectionID = digest([]any{response.ConfigurationDigest, response.StructID, response.Fields, target.URI, target.Text, response.CaretFieldID})
	a.inspection = response
	fail = false
	return a, nil
}

func (a *analysis) methodConflict(name string) string {
	for i := 0; i < a.structure.NumFields(); i++ {
		field := a.structure.Field(i)
		if field.Name() == name && (field.Exported() || field.Pkg() == nil || field.Pkg().Path() == a.owner.Package.Types.Path()) {
			return "A direct field already has this method name"
		}
	}
	set := types.NewMethodSet(types.NewPointer(a.named))
	for i := 0; i < set.Len(); i++ {
		method := set.At(i).Obj()
		if method.Name() == name && (method.Exported() || method.Pkg() == nil || method.Pkg().Path() == a.owner.Package.Types.Path()) {
			return "An existing or promoted method already has this name"
		}
	}
	return ""
}
func (a *analysis) conflict(code, message string) signature.Conflict {
	uri := a.owner.URI
	if uri == "" {
		uri = a.target.URI
	}
	return signature.Conflict{URI: uri, Range: signature.Range{}, Code: code, Message: message}
}
