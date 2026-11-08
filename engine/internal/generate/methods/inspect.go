package methods

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"go/types"
	"path/filepath"
	"sort"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	"golang.org/x/tools/go/packages"
)

var commonPackages = []string{"context", "fmt", "io", "sort"}

type receiverDecl struct {
	file  signature.SemanticFile
	spec  *ast.TypeSpec
	named *types.Named
	wire  Receiver
}
type interfaceDecl struct {
	object *types.TypeName
	typ    types.Type
	wire   Interface
}
type requiredMethod struct {
	object    *types.Func
	signature *types.Signature
	wire      Method
}
type analysis struct {
	snapshot        *signature.SemanticSnapshot
	view            signature.SemanticAnalysis
	request         Request
	target          signature.SemanticFile
	imports         []string
	receivers       map[string]*receiverDecl
	receiverAliases map[string]*receiverDecl
	receiver        *receiverDecl
	newName         string
	interfaces      map[string]interfaceDecl
	selected        *interfaceDecl
	interfaceType   *types.Interface
	methods         []requiredMethod
	inspection      InspectResponse
}

func digest(v any) string {
	b, _ := json.Marshal(v)
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
func display(t types.Type) string {
	return types.TypeString(t, func(p *types.Package) string { return p.Name() })
}
func typeParameters(t types.Type) *types.TypeParamList {
	switch t := t.(type) {
	case *types.Named:
		return t.TypeParams()
	case *types.Alias:
		return t.TypeParams()
	}
	return nil
}
func parameterRows(t types.Type) []TypeParameter {
	out := []TypeParameter{}
	for i := 0; i < typeParameters(t).Len(); i++ {
		p := typeParameters(t).At(i)
		out = append(out, TypeParameter{p.Obj().Name(), display(p.Constraint())})
	}
	return out
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
	if request.ReceiverKind == "" {
		request.ReceiverKind = "pointer"
	}
	if request.ReceiverKind != "pointer" && request.ReceiverKind != "value" {
		return nil, fmt.Errorf("choose a pointer or value receiver")
	}
	if request.ReceiverType != "" && request.NewTypeName != "" {
		return nil, fmt.Errorf("choose an existing receiver or a new type, not both")
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
	paths := append([]string{}, commonPackages...)
	paths = append(paths, request.CatalogPackages...)
	if request.InterfaceImportPath != "" {
		paths = append(paths, request.InterfaceImportPath)
	}
	if request.InterfaceID != "" {
		if i := strings.LastIndex(request.InterfaceID, "."); i > 0 {
			paths = append(paths, request.InterfaceID[:i])
		}
	}
	paths = uniquePaths(paths)
	view, e := s.LoadPackages(ctx, nil, paths)
	if e != nil {
		return nil, e
	}
	a, e := analyze(s, view, request, paths)
	if e != nil {
		return nil, e
	}
	// Resolve an explicitly qualified package which is not imported yet. Load
	// it together with the workspace so named types retain identical identities.
	if a.selected == nil && request.InterfaceExpression != "" {
		base, _, parseErr := expressionParts(request.InterfaceExpression)
		if parseErr != nil {
			return nil, parseErr
		}
		if i := strings.LastIndex(base, "."); i > 0 {
			qualifier := base[:i]
			if !a.knownQualifier(qualifier) && !contains(paths, qualifier) {
				paths = uniquePaths(append(paths, qualifier))
				view, e = s.LoadPackages(ctx, nil, paths)
				if e != nil {
					return nil, e
				}
				a, e = analyze(s, view, request, paths)
				if e != nil {
					return nil, e
				}
			}
		}
	}
	if e = a.selectInterface(); e != nil {
		return nil, e
	}
	a.finishInspection()
	fail = false
	return a, nil
}
func uniquePaths(input []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, p := range input {
		if p != "" && !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	sort.Strings(out)
	return out
}
func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}

func analyze(s *signature.SemanticSnapshot, view signature.SemanticAnalysis, request Request, imports []string) (*analysis, error) {
	path, e := signature.LocalPath(request.Target.URI)
	if e != nil {
		return nil, e
	}
	file, ok := view.Files[path]
	if !ok {
		return nil, fmt.Errorf("select an editable Go type or interface in the active build context")
	}
	a := &analysis{snapshot: s, view: view, request: request, target: file, imports: imports, receivers: map[string]*receiverDecl{}, receiverAliases: map[string]*receiverDecl{}, interfaces: map[string]interfaceDecl{}}
	paths := []string{}
	for p := range view.Files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, p := range paths {
		f := view.Files[p]
		if filepath.Dir(p) != filepath.Dir(path) || f.Package.Types.Path() != file.Package.Types.Path() {
			continue
		}
		for _, decl := range f.AST.Decls {
			gen, ok := decl.(*ast.GenDecl)
			if !ok {
				continue
			}
			for _, node := range gen.Specs {
				spec, ok := node.(*ast.TypeSpec)
				if !ok || spec.Assign.IsValid() {
					continue
				}
				obj, ok := file.Package.Types.Scope().Lookup(spec.Name.Name).(*types.TypeName)
				if !ok {
					continue
				}
				named, ok := obj.Type().(*types.Named)
				if !ok {
					continue
				}
				switch named.Underlying().(type) {
				case *types.Interface, *types.Pointer:
					continue
				}
				start, end := view.Fset.PositionFor(spec.Pos(), false).Offset, view.Fset.PositionFor(spec.End(), false).Offset
				wire := Receiver{ReceiverID: digest([]any{f.URI, start, spec.Name.Name, f.Text}), Name: spec.Name.Name, URI: f.URI, Range: signature.UTF16Range(f.Text, start, end), TypeParameters: parameterRows(named)}
				a.receivers[wire.Name] = &receiverDecl{f, spec, named, wire}
			}
		}
	}
	// Nongeneric aliases to local, non-instantiated defined types can denote
	// receivers (including one pointer indirection). Keep their canonical base
	// identity and declaration file, instead of inventing an alias-owned method.
	for _, name := range file.Package.Types.Scope().Names() {
		obj, ok := file.Package.Types.Scope().Lookup(name).(*types.TypeName)
		if !ok || !obj.IsAlias() || typeParameters(obj.Type()).Len() > 0 {
			continue
		}
		base := types.Unalias(obj.Type())
		if pointer, ok := base.(*types.Pointer); ok {
			base = types.Unalias(pointer.Elem())
		}
		named, ok := base.(*types.Named)
		if !ok || named.Obj().Pkg() == nil || named.Obj().Pkg().Path() != file.Package.Types.Path() || named.TypeParams().Len() > 0 || named.TypeArgs().Len() > 0 {
			continue
		}
		if receiver := a.receivers[named.Obj().Name()]; receiver != nil {
			a.receiverAliases[name] = receiver
		}
	}
	entry, obj := a.caretType()
	a.inspection.EntryKind = entry
	if request.NewTypeName != "" {
		if !token.IsIdentifier(request.NewTypeName) || request.NewTypeName == "_" {
			return nil, fmt.Errorf("enter a valid new Go type name")
		}
		if file.Package.Types.Scope().Lookup(request.NewTypeName) != nil {
			return nil, fmt.Errorf("%s already exists in this package", request.NewTypeName)
		}
		a.newName = request.NewTypeName
	} else if request.ReceiverType != "" {
		a.receiver = a.receivers[request.ReceiverType]
		if a.receiver == nil {
			a.receiver = a.receiverAliases[request.ReceiverType]
		}
		if a.receiver == nil {
			return nil, fmt.Errorf("%s does not resolve to a valid local defined receiver; foreign bases, generic/instantiated aliases, interfaces and defined pointer types cannot own these methods", request.ReceiverType)
		}
	} else if entry == "receiver" && obj != nil {
		a.receiver = a.receivers[obj.Name()]
		if a.receiver == nil && obj.Pkg() != nil && obj.Pkg().Path() == file.Package.Types.Path() {
			a.receiver = a.receiverAliases[obj.Name()]
		}
		if a.receiver == nil {
			return nil, fmt.Errorf("the selected alias or type cannot own methods in this package")
		}
	}
	// Prefer current-package objects from the receiver's actual test variant.
	a.addInterfaces(file.Package.Types)
	packages.Visit(view.Packages, func(p *packages.Package) bool {
		if p.Types != nil {
			a.addInterfaces(p.Types)
		}
		return true
	}, nil)
	errObject := types.Universe.Lookup("error").(*types.TypeName)
	a.interfaces["error"] = interfaceDecl{object: errObject, typ: errObject.Type(), wire: Interface{InterfaceID: "error", Name: "error", Display: "error", TypeParameters: []TypeParameter{}, MethodCount: 1}}
	if request.InterfaceID != "" {
		if selected, ok := a.interfaces[request.InterfaceID]; ok {
			copy := selected
			a.selected = &copy
		}
	}
	if request.InterfaceID == "" && request.InterfaceExpression == "" && entry == "interface" && obj != nil {
		key := obj.Name()
		if obj.Pkg() != nil {
			key = obj.Pkg().Path() + "." + key
		}
		if candidate, ok := a.interfaces[key]; ok {
			copy := candidate
			a.selected = &copy
		}
	}
	return a, nil
}
func (a *analysis) caretType() (string, *types.TypeName) {
	offset, e := signature.UTF16Offset(a.target.Text, a.request.Target.Position)
	if e != nil {
		return "receiver", nil
	}
	var selected *types.TypeName
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		if node == nil {
			return false
		}
		start, end := a.view.Fset.PositionFor(node.Pos(), false).Offset, a.view.Fset.PositionFor(node.End(), false).Offset
		if offset < start || offset > end {
			return false
		}
		if id, ok := node.(*ast.Ident); ok {
			if obj, ok := a.target.Package.TypesInfo.ObjectOf(id).(*types.TypeName); ok {
				selected = obj
			}
		}
		return true
	})
	if selected == nil {
		ast.Inspect(a.target.AST, func(node ast.Node) bool {
			spec, ok := node.(*ast.TypeSpec)
			if !ok {
				return true
			}
			start, end := a.view.Fset.PositionFor(spec.Pos(), false).Offset, a.view.Fset.PositionFor(spec.End(), false).Offset
			if offset >= start && offset <= end {
				selected, _ = a.target.Package.TypesInfo.Defs[spec.Name].(*types.TypeName)
				return false
			}
			return true
		})
	}
	if selected != nil {
		if _, ok := selected.Type().Underlying().(*types.Interface); ok {
			return "interface", selected
		}
	}
	return "receiver", selected
}
func (a *analysis) addInterfaces(pkg *types.Package) {
	for _, name := range pkg.Scope().Names() {
		obj, ok := pkg.Scope().Lookup(name).(*types.TypeName)
		if !ok || (!obj.Exported() && pkg.Path() != a.target.Package.Types.Path()) {
			continue
		}
		under, ok := obj.Type().Underlying().(*types.Interface)
		if !ok || !under.Complete().IsMethodSet() {
			continue
		}
		id := pkg.Path() + "." + name
		if _, exists := a.interfaces[id]; exists {
			continue
		}
		wire := Interface{InterfaceID: id, Name: name, PackagePath: pkg.Path(), PackageName: pkg.Name(), Display: pkg.Name() + "." + name, TypeParameters: parameterRows(obj.Type()), MethodCount: under.NumMethods(), NeedsTypeArguments: typeParameters(obj.Type()).Len() > 0}
		if pkg.Path() == a.target.Package.Types.Path() {
			wire.Display = name
		}
		a.interfaces[id] = interfaceDecl{obj, obj.Type(), wire}
	}
}

func expressionParts(expression string) (string, []string, error) {
	base := strings.TrimSpace(expression)
	arguments := []string{}
	if i := strings.Index(base, "["); i >= 0 {
		suffix := base[i:]
		base = base[:i]
		node, e := parser.ParseExpr("X" + suffix)
		if e != nil {
			return "", nil, e
		}
		var nodes []ast.Expr
		switch v := node.(type) {
		case *ast.IndexExpr:
			nodes = []ast.Expr{v.Index}
		case *ast.IndexListExpr:
			nodes = v.Indices
		default:
			return "", nil, fmt.Errorf("invalid generic interface expression")
		}
		for _, n := range nodes {
			var b bytes.Buffer
			_ = format.Node(&b, token.NewFileSet(), n)
			arguments = append(arguments, b.String())
		}
	}
	return base, arguments, nil
}
func (a *analysis) knownQualifier(q string) bool {
	if q == a.target.Package.Name {
		return true
	}
	for _, spec := range a.target.AST.Imports {
		var obj *types.PkgName
		if spec.Name != nil {
			obj, _ = a.target.Package.TypesInfo.Defs[spec.Name].(*types.PkgName)
		} else {
			obj, _ = a.target.Package.TypesInfo.Implicits[spec].(*types.PkgName)
		}
		if obj != nil && obj.Name() == q {
			return true
		}
	}
	return false
}
func (a *analysis) selectInterface() error {
	arguments := append([]string{}, a.request.InterfaceTypeArguments...)
	if a.request.InterfaceExpression != "" {
		base, inline, e := expressionParts(a.request.InterfaceExpression)
		if e != nil {
			return e
		}
		if len(inline) > 0 {
			if len(arguments) > 0 {
				return fmt.Errorf("put generic arguments in the expression or the type-argument fields, not both")
			}
			arguments = inline
		}
		name, pkgPath := base, a.request.InterfaceImportPath
		if i := strings.LastIndex(base, "."); i > 0 {
			q := base[:i]
			name = base[i+1:]
			if pkgPath == "" {
				if q == a.target.Package.Name {
					pkgPath = a.target.Package.Types.Path()
				} else {
					for _, decl := range a.interfaces {
						if decl.wire.PackagePath == q {
							pkgPath = q
							break
						}
					}
					if pkgPath == "" {
						for _, spec := range a.target.AST.Imports {
							var obj *types.PkgName
							if spec.Name != nil {
								obj, _ = a.target.Package.TypesInfo.Defs[spec.Name].(*types.PkgName)
							} else {
								obj, _ = a.target.Package.TypesInfo.Implicits[spec].(*types.PkgName)
							}
							if obj != nil && obj.Name() == q {
								pkgPath = obj.Imported().Path()
								break
							}
						}
					}
				}
			}
		}
		if pkgPath == "" {
			pkgPath = a.target.Package.Types.Path()
		}
		key := pkgPath + "." + name
		if base == "error" {
			key = "error"
		}
		selected, ok := a.interfaces[key]
		if !ok {
			return fmt.Errorf("%s is not a named method-set interface in the selected Go context", a.request.InterfaceExpression)
		}
		a.selected = &selected
	}
	if a.selected == nil {
		if a.request.InterfaceID != "" {
			return fmt.Errorf("the selected interface is no longer available")
		}
		return nil
	}
	needed := typeParameters(a.selected.typ).Len()
	if len(arguments) == 0 && needed > 0 {
		return nil
	}
	if len(arguments) != needed {
		return fmt.Errorf("%s requires %d explicit interface type arguments", a.selected.wire.Display, needed)
	}
	typ := a.selected.typ
	if needed > 0 {
		values := []types.Type{}
		position := a.target.AST.Pos()
		if a.receiver != nil {
			position = a.receiver.spec.Type.Pos()
		}
		for _, argument := range arguments {
			tv, e := types.Eval(a.view.Fset, a.target.Package.Types, position, argument)
			if e != nil {
				return fmt.Errorf("invalid interface type argument %s: %w", argument, e)
			}
			if !tv.IsType() {
				return fmt.Errorf("%s must be a Go type", argument)
			}
			values = append(values, tv.Type)
		}
		instantiated, e := types.Instantiate(nil, typ, values, true)
		if e != nil {
			return e
		}
		typ = instantiated
	}
	iface, ok := typ.Underlying().(*types.Interface)
	if !ok || !iface.Complete().IsMethodSet() {
		return fmt.Errorf("interfaces with type terms need a separate underlying-type design")
	}
	a.interfaceType = iface
	for i := 0; i < iface.NumMethods(); i++ {
		fn := iface.Method(i)
		sig := fn.Type().(*types.Signature)
		wire := Method{MethodID: digest([]any{a.selected.wire.InterfaceID, fn.Id(), types.TypeString(sig, func(p *types.Package) string { return p.Path() })}), Name: fn.Name(), Signature: display(sig), Status: "missing"}
		a.classifyMethod(fn, sig, &wire)
		a.methods = append(a.methods, requiredMethod{fn, sig, wire})
	}
	return nil
}

func (a *analysis) classifyMethod(fn *types.Func, sig *types.Signature, wire *Method) {
	if a.receiver != nil {
		typ := receiverType(a.receiver.named, a.request.ReceiverKind)
		if method := types.NewMethodSet(typ).Lookup(fn.Pkg(), fn.Name()); method != nil {
			if types.Identical(method.Type(), sig) {
				wire.Status = "existing"
				return
			}
			wire.Status = "conflict"
			wire.Message = "An existing method with this name has a different signature"
			return
		}
		for i := 0; i < a.receiver.named.NumMethods(); i++ {
			m := a.receiver.named.Method(i)
			if m.Name() == fn.Name() {
				wire.Status = "conflict"
				wire.Message = "This name already belongs to a method with another receiver/signature"
				return
			}
		}
		if str, ok := a.receiver.named.Underlying().(*types.Struct); ok {
			for i := 0; i < str.NumFields(); i++ {
				if str.Field(i).Name() == fn.Name() {
					wire.Status = "conflict"
					wire.Message = "A direct field already has this method name"
					return
				}
			}
		}
	}
	if !fn.Exported() && fn.Pkg() != nil && fn.Pkg().Path() != a.target.Package.Types.Path() {
		wire.Status = "conflict"
		wire.Message = "A package-private method from another package cannot be declared here"
	}
	if wire.Status == "missing" {
		if e := accessible(sig, a.target.Package.Types.Path(), map[types.Type]bool{}); e != nil {
			wire.Status = "conflict"
			wire.Message = e.Error()
		}
	}
}

// A generic origin's declared method has its own receiver type parameters.
// Instantiate with the origin's symbolic parameters to substitute those method
// binders into the same universe as Interface[T], rather than comparing two
// different T objects merely because their spelling happens to match.
func receiverType(named *types.Named, kind string) types.Type {
	typ := types.Type(named)
	if named.TypeParams().Len() > 0 && named.TypeArgs().Len() == 0 {
		args := []types.Type{}
		for i := 0; i < named.TypeParams().Len(); i++ {
			args = append(args, named.TypeParams().At(i))
		}
		if instantiated, e := types.Instantiate(nil, named, args, false); e == nil {
			typ = instantiated
		}
	}
	if kind == "pointer" {
		typ = types.NewPointer(typ)
	}
	return typ
}
func (a *analysis) finishInspection() {
	response := InspectResponse{ConfigurationDigest: a.snapshot.Digest(), EntryKind: a.inspection.EntryKind, ReceiverKind: a.request.ReceiverKind, ReceiverCandidates: []Receiver{}, Interfaces: []Interface{}, Methods: []Method{}, Coverage: a.view.Coverage, Conflicts: []signature.Conflict{}}
	for _, r := range a.receivers {
		response.ReceiverCandidates = append(response.ReceiverCandidates, r.wire)
	}
	sort.Slice(response.ReceiverCandidates, func(i, j int) bool { return response.ReceiverCandidates[i].Name < response.ReceiverCandidates[j].Name })
	if a.receiver != nil {
		copy := a.receiver.wire
		response.Receiver = &copy
	} else if a.newName != "" {
		response.Receiver = &Receiver{ReceiverID: digest([]any{"new", a.target.URI, a.newName, a.target.Text}), Name: a.newName, URI: a.target.URI, Range: signature.UTF16Range(a.target.Text, len(a.target.Text), len(a.target.Text)), TypeParameters: []TypeParameter{}, New: true}
	}
	for _, decl := range a.interfaces {
		response.Interfaces = append(response.Interfaces, decl.wire)
	}
	sort.Slice(response.Interfaces, func(i, j int) bool { return response.Interfaces[i].InterfaceID < response.Interfaces[j].InterfaceID })
	if a.selected != nil {
		copy := a.selected.wire
		copy.NeedsTypeArguments = a.interfaceType == nil && len(copy.TypeParameters) > 0
		response.SelectedInterface = &copy
	}
	response.Implemented = a.interfaceType != nil && response.Receiver != nil
	for _, method := range a.methods {
		response.Methods = append(response.Methods, method.wire)
		if method.wire.Status != "existing" {
			response.Implemented = false
		}
	}
	response.InspectionID = digest([]any{response.ConfigurationDigest, response.EntryKind, response.ReceiverKind, response.Receiver, response.SelectedInterface, response.Methods, a.request.InterfaceExpression, a.request.InterfaceTypeArguments, a.target.Text})
	a.inspection = response
}
