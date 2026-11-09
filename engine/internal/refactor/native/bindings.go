package native

import (
	"context"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"strconv"
	"strings"
)

func functionHeader(f *ast.FuncDecl) string {
	return rendered(&ast.FuncDecl{Recv: f.Recv, Name: f.Name, Type: f.Type})
}
func callableName(expr ast.Expr) *ast.Ident {
	switch value := expr.(type) {
	case *ast.Ident:
		return value
	case *ast.SelectorExpr:
		return value.Sel
	case *ast.IndexExpr:
		return callableName(value.X)
	case *ast.IndexListExpr:
		return callableName(value.X)
	case *ast.ParenExpr:
		return callableName(value.X)
	}
	return nil
}
func generatedName(name, prefix string) bool {
	if !strings.HasPrefix(name, prefix) {
		return false
	}
	for _, r := range name[len(prefix):] {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func (a *analysis) findFunctionBinding() {
	index := -1
	var original *ast.FuncDecl
	for i, decl := range a.target.AST.Decls {
		f, ok := decl.(*ast.FuncDecl)
		if !ok || f.Body == nil {
			continue
		}
		start, end := a.view.Fset.PositionFor(f.Body.Pos(), false).Offset, a.view.Fset.PositionFor(f.Body.End(), false).Offset
		if a.start > start && a.end <= end {
			index = i
			original = f
			break
		}
	}
	if original == nil || a.start == a.end {
		a.reject("function-selection", "Select complete statements inside a Go function or method")
		return
	}
	if len(a.parsed.Decls) != len(a.target.AST.Decls)+1 || index+1 >= len(a.parsed.Decls) {
		a.reject("candidate-shape", "The function extraction did not introduce exactly one adjacent declaration")
		return
	}
	parent, parentOK := a.parsed.Decls[index].(*ast.FuncDecl)
	helper, helperOK := a.parsed.Decls[index+1].(*ast.FuncDecl)
	if !parentOK || !helperOK || functionHeader(parent) != functionHeader(original) {
		a.reject("candidate-shape", "The extraction changed the enclosing function signature")
		return
	}
	for i, decl := range a.target.AST.Decls {
		if i == index {
			continue
		}
		j := i
		if i > index {
			j++
		}
		if rendered(decl) != rendered(a.parsed.Decls[j]) {
			a.reject("candidate-shape", "The extraction unexpectedly changed an unrelated declaration")
			return
		}
	}
	isMethod := a.request.Kind == "extract.method"
	if isMethod {
		if original.Recv == nil || helper.Recv == nil || rendered(original.Recv) != rendered(helper.Recv) || helper.Name.Name != "newMethod" {
			a.reject("candidate-method", "The candidate does not preserve the selected method receiver")
			return
		}
	} else if helper.Recv != nil || !generatedName(helper.Name.Name, "newFunction") {
		a.reject("candidate-function", "The candidate does not contain the expected extracted Go function")
		return
	}
	oldStart, oldEnd := a.view.Fset.PositionFor(original.Pos(), false).Offset, a.view.Fset.PositionFor(original.End(), false).Offset
	newStart, newEnd := a.fset.PositionFor(parent.Pos(), false).Offset, a.fset.PositionFor(parent.End(), false).Offset
	oldText, newText := a.target.Text[oldStart:oldEnd], a.candidate[newStart:newEnd]
	prefix := 0
	for prefix < len(oldText) && prefix < len(newText) && oldText[prefix] == newText[prefix] {
		prefix++
	}
	suffix := 0
	for suffix < len(oldText)-prefix && suffix < len(newText)-prefix && oldText[len(oldText)-1-suffix] == newText[len(newText)-1-suffix] {
		suffix++
	}
	// The original selected statement may already call an existing method with
	// the generated name. A common textual prefix can then include the entire
	// newly generated call. Keep the selection start as a lower bound; never
	// search the copied helper body or the unchanged suffix.
	changedStart := newStart + prefix
	selectionStart := newStart + a.start - oldStart
	if selectionStart >= newStart && selectionStart < changedStart {
		changedStart = selectionStart
	}
	changed := span{changedStart, newEnd - suffix}
	var calls []*ast.Ident
	ast.Inspect(parent.Body, func(node ast.Node) bool {
		if call, ok := node.(*ast.CallExpr); ok {
			id := callableName(call.Fun)
			if id != nil && id.Name == helper.Name.Name {
				offset := a.fset.PositionFor(id.Pos(), false).Offset
				if offset >= changed.start && offset < changed.end {
					calls = append(calls, id)
				}
			}
		}
		return true
	})
	if len(calls) != 1 {
		a.reject("candidate-call", "The extracted helper call cannot be identified unambiguously without rewriting existing calls")
		return
	}
	a.parent, a.helper = parent, helper
	suggestion := "extracted"
	for n := 2; a.nameConflict(suggestion) != ""; n++ {
		suggestion = "extracted" + strconv.Itoa(n)
	}
	a.bindings = []binding{{wire: Name{NameID: digest([]any{a.request.Kind, a.fset.PositionFor(helper.Name.Pos(), false).Offset, helper.Name.Name})[:16], GeneratedName: helper.Name.Name, SuggestedName: suggestion, Type: rendered(helper.Type)}, identifiers: []*ast.Ident{helper.Name, calls[0]}}}
	a.inspection.Signature = functionHeader(helper)
	a.inspection.Occurrences = 1
}

func (a *analysis) findLocalBindings(ctx context.Context) error {
	view, err := a.snapshot.Load(ctx, map[string]string{a.target.Path: a.candidate})
	if err != nil {
		return err
	}
	if len(view.Errors) > 0 {
		a.reject("candidate-typecheck", "The extraction candidate does not type-check:\n"+strings.Join(view.Errors, "\n"))
		return nil
	}
	file, ok := view.Files[a.target.Path]
	if !ok {
		return fmt.Errorf("the candidate Go package disappeared")
	}
	a.fset, a.parsed = view.Fset, file.AST
	var objects []types.Object
	for id, obj := range file.Package.TypesInfo.Defs {
		if obj == nil {
			continue
		}
		_, variable := obj.(*types.Var)
		_, constant := obj.(*types.Const)
		if !variable && !constant {
			continue
		}
		if !generatedName(id.Name, "newVar") && !generatedName(id.Name, "newConst") {
			continue
		}
		position := view.Fset.PositionFor(id.Pos(), false)
		if position.Filename != a.target.Path {
			continue
		}
		for _, insert := range a.inserted {
			if position.Offset >= insert.start && position.Offset < insert.end {
				objects = append(objects, obj)
				break
			}
		}
	}
	sortObjects(objects)
	if len(objects) == 0 {
		a.reject("candidate-bindings", "The extraction must introduce a fresh local variable or constant at an insertion point")
		return nil
	}
	for i, obj := range objects {
		var identifiers []*ast.Ident
		for id, def := range file.Package.TypesInfo.Defs {
			if def == obj {
				identifiers = append(identifiers, id)
			}
		}
		for id, use := range file.Package.TypesInfo.Uses {
			if use == obj {
				identifiers = append(identifiers, id)
			}
		}
		for _, id := range identifiers {
			if view.Fset.PositionFor(id.Pos(), false).Filename != a.target.Path {
				a.reject("candidate-bindings", "The new local binding unexpectedly spans multiple source files")
				return nil
			}
		}
		suggestion := "value"
		if i > 0 {
			suggestion += strconv.Itoa(i + 1)
		}
		alreadySuggested := func(name string) bool {
			for _, b := range a.bindings {
				if b.wire.SuggestedName == name {
					return true
				}
			}
			return false
		}
		for n := 2; a.nameConflict(suggestion) != "" || alreadySuggested(suggestion); n++ {
			suggestion = "value" + strconv.Itoa(n)
		}
		a.bindings = append(a.bindings, binding{wire: Name{NameID: digest([]any{a.request.Kind, view.Fset.PositionFor(obj.Pos(), false).Offset, obj.Name()})[:16], GeneratedName: obj.Name(), SuggestedName: suggestion, Type: types.TypeString(obj.Type(), func(p *types.Package) string { return p.Name() })}, identifiers: identifiers, object: obj})
		a.inspection.Occurrences += len(identifiers) - 1
	}
	a.inspection.Signature = rendered(file.AST.Name) + ": " + a.request.Kind
	return nil
}

func sortObjects(objects []types.Object) {
	for i := 1; i < len(objects); i++ {
		for j := i; j > 0 && objects[j].Pos() < objects[j-1].Pos(); j-- {
			objects[j], objects[j-1] = objects[j-1], objects[j]
		}
	}
}

// Name selection must not silently capture an existing binding. Local names are
// deliberately conservative across the enclosing function's nested scopes.
func (a *analysis) nameConflict(name string) string {
	if !token.IsIdentifier(name) || name == "_" {
		return "Choose a valid non-blank Go identifier"
	}
	if a.request.Kind == "extract.function" {
		if name == "init" {
			return "An extracted function cannot be an init function"
		}
		if types.Universe.Lookup(name) != nil {
			return "The function name would shadow a Go predeclared identifier"
		}
		if a.target.Package.Types.Scope().Lookup(name) != nil {
			return "The function name already exists in this Go package"
		}
		if file := a.view.Fset.File(a.target.AST.Pos()); file != nil {
			if scope := a.target.Package.TypesInfo.Scopes[a.target.AST]; scope != nil {
				position := file.Pos(a.start)
				if inner := scope.Innermost(position); inner != nil {
					if _, object := inner.LookupParent(name, position); object != nil {
						return "The function name would be captured by an existing local or imported binding at the new call"
					}
				}
			}
		}
		return ""
	}
	if a.request.Kind == "extract.method" {
		for _, decl := range a.target.AST.Decls {
			f, ok := decl.(*ast.FuncDecl)
			if !ok || f.Recv == nil {
				continue
			}
			start, end := a.view.Fset.PositionFor(f.Pos(), false).Offset, a.view.Fset.PositionFor(f.End(), false).Offset
			if a.start < start || a.end > end {
				continue
			}
			obj, _ := a.target.Package.TypesInfo.Defs[f.Name].(*types.Func)
			if obj != nil {
				if sig, ok := obj.Type().(*types.Signature); ok && sig.Recv() != nil {
					if found, _, _ := types.LookupFieldOrMethod(sig.Recv().Type(), true, a.target.Package.Types, name); found != nil {
						return "The name conflicts with an existing or promoted receiver method/field"
					}
				}
			}
		}
		return ""
	}
	for id, obj := range a.target.Package.TypesInfo.Defs {
		if obj == nil || id.Name != name || a.view.Fset.PositionFor(id.Pos(), false).Filename != a.target.Path {
			continue
		}
		return "The local name already occurs in this source file; choose an unambiguous name"
	}
	for id, obj := range a.target.Package.TypesInfo.Uses {
		if obj != nil && id.Name == name && a.view.Fset.PositionFor(id.Pos(), false).Filename == a.target.Path {
			return "The local name would capture an existing source binding"
		}
	}
	return ""
}
