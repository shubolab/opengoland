package native

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"sort"
	"strconv"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	"golang.org/x/tools/go/ast/astutil"
	"golang.org/x/tools/go/types/typeutil"
)

type allInline struct {
	object      types.Object
	declaration *ast.FuncDecl
	file        signature.SemanticFile
	uses        []inlineUse
	references  int
}
type inlineUse struct {
	file signature.SemanticFile
	id   *ast.Ident
	call *ast.CallExpr
}

func (a *analysis) inspectAllInline() {
	var object types.Object
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		if id, ok := node.(*ast.Ident); ok {
			start, end := a.view.Fset.PositionFor(id.Pos(), false).Offset, a.view.Fset.PositionFor(id.End(), false).Offset
			if a.start >= start && a.end <= end {
				object = a.target.Package.TypesInfo.ObjectOf(id)
			}
		}
		return true
	})
	if object == nil {
		a.reject("inline-all-target", "Choose the name of a local variable or a workspace function/method declaration")
		return
	}
	all := &allInline{object: object, references: a.referenceCount(object)}
	a.all = all
	key := a.objectKey(object)
	for _, file := range a.view.Files {
		if !a.snapshot.Editable(file.Path) {
			continue
		}
		calls := map[*ast.Ident]*ast.CallExpr{}
		ast.Inspect(file.AST, func(node ast.Node) bool {
			if call, ok := node.(*ast.CallExpr); ok {
				if function := typeutil.StaticCallee(file.Package.TypesInfo, call); function != nil && a.objectKey(function) == key {
					calls[callableName(call.Fun)] = call
				}
			}
			return true
		})
		ast.Inspect(file.AST, func(node ast.Node) bool {
			if id, ok := node.(*ast.Ident); ok && a.objectKey(file.Package.TypesInfo.Uses[id]) == key {
				use := inlineUse{file: file, id: id, call: calls[id]}
				all.uses = append(all.uses, use)
			}
			return true
		})
	}
	sort.Slice(all.uses, func(i, j int) bool {
		x, y := all.uses[i], all.uses[j]
		if x.file.Path != y.file.Path {
			return x.file.Path < y.file.Path
		}
		return x.id.Pos() < y.id.Pos()
	})
	// The typed workspace may contain test variants of the same syntax file.
	seen := map[string]bool{}
	unique := all.uses[:0]
	for _, use := range all.uses {
		k := fmt.Sprintf("%s:%d", use.file.Path, a.view.Fset.PositionFor(use.id.Pos(), false).Offset)
		if !seen[k] {
			unique = append(unique, use)
			seen[k] = true
		}
	}
	all.uses = unique
	if len(all.uses) == 0 {
		a.reject("inline-all-uses", "No editable workspace references remain")
		return
	}
	if a.request.Kind == "inline.variable-all" {
		variable, ok := object.(*types.Var)
		if !ok {
			a.reject("inline-all-target", "Choose an initialized local variable")
			return
		}
		if variable.Parent() == nil || variable.Parent() == a.target.Package.Types.Scope() {
			a.reject("inline-all-target", "Package variables need a separate initialization and alias analysis")
			return
		}
		originalStart, originalEnd := a.start, a.end
		use := all.uses[0]
		a.start, a.end = a.view.Fset.PositionFor(use.id.Pos(), false).Offset, a.view.Fset.PositionFor(use.id.End(), false).Offset
		originalKind := a.request.Kind
		a.request.Kind = "inline.variable"
		a.inspectInline()
		a.request.Kind = originalKind
		a.start, a.end = originalStart, originalEnd
		if a.inspection.Inline != nil {
			a.inspection.Inline.All = true
			a.inspection.Inline.CanKeep = false
			a.inspection.Inline.CanRemove = a.inline != nil && a.inline.removable
			a.inspection.Inline.RemovalReason = "All local uses are replaced; remove the declaration to avoid an unused local"
		}
		a.inspection.Occurrences = len(all.uses)
		return
	}
	function, ok := object.(*types.Func)
	if !ok {
		a.reject("inline-all-target", "Choose a statically resolved function or method declaration")
		return
	}
	position := a.view.Fset.PositionFor(function.Pos(), false)
	file, exists := a.view.Files[position.Filename]
	if !exists || !a.snapshot.Editable(file.Path) {
		a.reject("inline-all-source", "The declaration is outside the editable workspace")
		return
	}
	all.file = file
	for _, decl := range file.AST.Decls {
		if fn, ok := decl.(*ast.FuncDecl); ok && a.objectKey(file.Package.TypesInfo.Defs[fn.Name]) == key {
			all.declaration = fn
		}
	}
	if all.declaration == nil || all.declaration.Body == nil {
		a.reject("inline-all-source", "The function needs a Go body in the active workspace")
		return
	}
	sig := function.Type().(*types.Signature)
	if sig.TypeParams().Len() > 0 || sig.RecvTypeParams().Len() > 0 {
		a.reject("inline-all-generic", "Generic inline-all needs concrete type substitution at every call; this workspace plan leaves it unchanged")
		return
	}
	calls := 0
	for _, use := range all.uses {
		if use.call == nil {
			continue
		}
		calls++
		if use.file.Package.Types.Path() != file.Package.Types.Path() {
			a.reject("inline-all-package", "Cross-package inline-all needs an accessibility and import rewrite; use the exact-call action instead")
			return
		}
		if use.file.Path == file.Path && use.call.Pos() >= all.declaration.Pos() && use.call.End() <= all.declaration.End() {
			a.reject("inline-all-recursion", "Recursive calls cannot all be expanded into finite copied bodies; use exact-call inline with the declaration kept")
			return
		}
	}
	if calls == 0 {
		a.reject("inline-all-calls", "The references contain no statically resolved calls")
		return
	}
	ordered := append([]inlineUse{}, all.uses...)
	sort.Slice(ordered, func(i, j int) bool {
		x, y := ordered[i], ordered[j]
		if x.file.Path != y.file.Path {
			return x.file.Path < y.file.Path
		}
		if x.call == nil {
			return false
		}
		if y.call == nil {
			return true
		}
		return x.call.Pos() < y.call.Pos()
	})
	previousPath := ""
	var previousEnd token.Pos
	for _, use := range ordered {
		if use.call == nil {
			continue
		}
		if use.file.Path == previousPath && use.call.Pos() < previousEnd {
			a.reject("inline-all-overlap", "Nested calls to the same declaration need a separate evaluation-order expansion; source is unchanged")
			return
		}
		previousPath, previousEnd = use.file.Path, use.call.End()
	}
	canRemove := calls == all.references
	reason := ""
	if !canRemove {
		reason = "Function-value or other references remain; the declaration must be kept"
	}
	a.inspection.Inline = &InlineDetails{Name: object.Name(), References: all.references, CanKeep: true, CanRemove: canRemove, RemovalReason: reason, All: true}
	a.inspection.Occurrences = calls
	a.inspection.Signature = fmt.Sprintf("Inline %d workspace call(s) to %s using immediately invoked functions; argument evaluation and the call frame are retained", calls, function.FullName())
	// Validate each call's lexical bindings before presenting a removal choice.
	if _, err := a.allInlineCandidates("keep"); err != nil {
		a.reject("inline-all-bindings", err.Error())
	}
}

func (a *analysis) allInlineCandidates(disposition string) (map[string]string, error) {
	if a.all == nil || a.inspection.Inline == nil {
		return nil, fmt.Errorf("the typed inline-all target is unavailable")
	}
	if disposition == "" {
		disposition = "keep"
	}
	if disposition != "keep" && disposition != "remove" {
		return nil, fmt.Errorf("choose whether to keep or remove the declaration")
	}
	if disposition == "keep" && !a.inspection.Inline.CanKeep {
		return nil, fmt.Errorf("replacing every local use requires explicit declaration removal")
	}
	if disposition == "remove" && !a.inspection.Inline.CanRemove {
		return nil, fmt.Errorf("cannot remove the declaration: %s", a.inspection.Inline.RemovalReason)
	}
	if a.request.Kind == "inline.variable-all" {
		if a.inline == nil {
			return nil, fmt.Errorf("the initialized local variable is unavailable")
		}
		edits := []signature.ByteEdit{}
		for _, use := range a.all.uses {
			a.inline.use = use.id
			variable := a.inline.object.(*types.Var)
			replacement, err := a.scalarReplacement(variable)
			if err != nil {
				return nil, err
			}
			edits = append(edits, signature.ByteEdit{Start: a.view.Fset.PositionFor(use.id.Pos(), false).Offset, End: a.view.Fset.PositionFor(use.id.End(), false).Offset, NewText: replacement})
		}
		start, end := a.view.Fset.PositionFor(a.inline.declaration.Pos(), false).Offset, a.view.Fset.PositionFor(a.inline.declaration.End(), false).Offset
		edits = append(edits, signature.ByteEdit{Start: start, End: end})
		return map[string]string{a.target.Path: applyBytes(a.target.Text, edits)}, nil
	}
	edits := map[string][]signature.ByteEdit{}
	files := map[string]signature.SemanticFile{}
	// Each file owns a fresh AST. The immutable snapshot ASTs are never mutated.
	parses := map[string]*ast.File{}
	fsets := map[string]*token.FileSet{}
	for _, use := range a.all.uses {
		if use.call == nil {
			continue
		}
		file := use.file
		files[file.Path] = file
		if parses[file.Path] == nil {
			fs := token.NewFileSet()
			parsed, err := parser.ParseFile(fs, file.Path, file.Text, parser.ParseComments)
			if err != nil {
				return nil, err
			}
			parses[file.Path] = parsed
			fsets[file.Path] = fs
		}
		literal, err := a.inlineLiteral(use, parses[file.Path], fsets[file.Path])
		if err != nil {
			return nil, err
		}
		edits[file.Path] = append(edits[file.Path], signature.ByteEdit{Start: a.view.Fset.PositionFor(use.call.Pos(), false).Offset, End: a.view.Fset.PositionFor(use.call.End(), false).Offset, NewText: literal})
	}
	if disposition == "remove" {
		file := a.all.file
		files[file.Path] = file
		start := a.all.declaration.Pos()
		if a.all.declaration.Doc != nil {
			start = a.all.declaration.Doc.Pos()
		}
		edits[file.Path] = append(edits[file.Path], signature.ByteEdit{Start: a.view.Fset.PositionFor(start, false).Offset, End: a.view.Fset.PositionFor(a.all.declaration.End(), false).Offset})
	}
	result := map[string]string{}
	for path, changes := range edits {
		source := applyBytes(files[path].Text, changes)
		// Add only imports actually needed by copied code. Reparse after text edits
		// so positions in the working AST cannot accidentally refer to old source.
		fs := token.NewFileSet()
		parsed, err := parser.ParseFile(fs, path, source, parser.ParseComments)
		if err != nil {
			return nil, err
		}
		if imports := parses[path]; imports != nil {
			for _, spec := range imports.Imports {
				alias := ""
				if spec.Name != nil {
					alias = spec.Name.Name
				}
				importPath, _ := strconv.Unquote(spec.Path.Value)
				astutil.AddNamedImport(fs, parsed, alias, importPath)
			}
		}
		removeUnusedNamedImports(fs, parsed, files[path])
		result[path] = rendered(parsed)
	}
	return result, nil
}

func (a *analysis) inlineLiteral(use inlineUse, working *ast.File, fs *token.FileSet) (string, error) {
	callee := a.all.declaration
	file := a.all.file
	edits := []signature.ByteEdit{}
	end := a.view.Fset.PositionFor(callee.Body.End(), false).Offset
	// FuncType.Pos starts at func, excluding the declaration name and receiver.
	typeStart := a.view.Fset.PositionFor(callee.Type.Params.Opening, false).Offset
	text := "func" + file.Text[typeStart:end]
	// Rewrite import qualifiers in the original signature/body to aliases safe at
	// the call site. Other free lexical names must retain their typed identities.
	info := file.Package.TypesInfo
	skipped := map[*ast.Ident]bool{}
	ast.Inspect(callee, func(node ast.Node) bool {
		if sel, ok := node.(*ast.SelectorExpr); ok {
			skipped[sel.Sel] = true
		}
		return true
	})
	imports := map[string]string{}
	used := map[string]bool{}
	ast.Inspect(use.file.AST, func(node ast.Node) bool {
		if id, ok := node.(*ast.Ident); ok {
			used[id.Name] = true
		}
		return true
	})
	ast.Inspect(callee, func(node ast.Node) bool {
		if id, ok := node.(*ast.Ident); ok {
			used[id.Name] = true
		}
		return true
	})
	var bindingError error
	ast.Inspect(callee, func(node ast.Node) bool {
		id, ok := node.(*ast.Ident)
		if !ok || skipped[id] || bindingError != nil {
			return true
		}
		object := info.Uses[id]
		if object == nil {
			return true
		}
		pos := a.view.Fset.PositionFor(id.Pos(), false).Offset
		if pos < typeStart || pos >= end {
			return true
		}
		if imported, ok := object.(*types.PkgName); ok {
			path := imported.Imported().Path()
			alias := imports[path]
			if alias == "" {
				for _, spec := range working.Imports {
					p, _ := strconv.Unquote(spec.Path.Value)
					if p == path {
						if spec.Name != nil {
							alias = spec.Name.Name
						} else {
							alias = imported.Imported().Name()
						}
						break
					}
				}
				if alias == "." || alias == "_" {
					bindingError = fmt.Errorf("dot/blank import %s needs a separate binding rewrite", path)
					return false
				}
				if alias == "" {
					base := imported.Imported().Name()
					alias = base
					for i := 2; used[alias]; i++ {
						alias = fmt.Sprintf("%s%d", base, i)
					}
					used[alias] = true
					astutil.AddNamedImport(fs, working, alias, path)
				}
				imports[path] = alias
			}
			if inside := lookupAt(file, id.Pos(), alias); inside != nil && inside.Pos() >= callee.Pos() && inside.Pos() <= callee.End() {
				bindingError = fmt.Errorf("import alias %s would be captured inside the copied function body", alias)
				return false
			}
			if captured := lookupAt(use.file, use.call.Pos(), alias); captured != nil {
				pkg, ok := captured.(*types.PkgName)
				if !ok || pkg.Imported().Path() != path {
					bindingError = fmt.Errorf("%s captures imported qualifier %s at a call site", use.file.Path, alias)
					return false
				}
			}
			edits = append(edits, signature.ByteEdit{Start: pos - typeStart + len("func"), End: pos - typeStart + len("func") + len(id.Name), NewText: alias})
			return true
		}
		if object.Pos() >= callee.Pos() && object.Pos() <= callee.End() {
			return true
		}
		if field, ok := object.(*types.Var); ok && field.IsField() {
			return true
		}
		if _, label := object.(*types.Label); label {
			return true
		}
		other := lookupAt(use.file, use.call.Pos(), object.Name())
		if other == nil || a.objectKey(other) != a.objectKey(object) {
			bindingError = fmt.Errorf("%s would capture the free name %s at an inline call; source is unchanged", use.file.Path, object.Name())
			return false
		}
		return true
	})
	if bindingError != nil {
		return "", bindingError
	}
	text = applyBytes(text, edits)
	arguments := []string{}
	if callee.Recv != nil {
		receiver := callee.Recv.List[0]
		name := "_"
		if len(receiver.Names) > 0 {
			name = receiver.Names[0].Name
		}
		sel, ok := use.call.Fun.(*ast.SelectorExpr)
		if !ok {
			return "", fmt.Errorf("the method call needs an explicit receiver")
		}
		selection := use.file.Package.TypesInfo.Selections[sel]
		if selection == nil {
			return "", fmt.Errorf("the method receiver cannot be resolved")
		}
		if len(selection.Index()) != 1 {
			return "", fmt.Errorf("promoted methods need a separate receiver-path rewrite")
		}
		want := a.all.object.Type().(*types.Signature).Recv().Type()
		receiverType, err := a.receiverTypeAt(use, receiver.Type, want)
		if err != nil {
			return "", err
		}
		got := use.file.Package.TypesInfo.TypeOf(sel.X)
		if selection.Kind() == types.MethodExpr && len(use.call.Args) > 0 {
			got = use.file.Package.TypesInfo.TypeOf(use.call.Args[0])
		}
		_, wantPointer := types.Unalias(want).(*types.Pointer)
		_, gotPointer := types.Unalias(got).(*types.Pointer)
		receiverText := name + " " + receiverType
		if !wantPointer && gotPointer {
			// Go evaluates all arguments before the implicit value-receiver
			// dereference. Doing *(receiver) in the first argument would panic
			// too early. Retain the pointer until entry, before the old body.
			deferred := false
			ast.Inspect(use.file.AST, func(node ast.Node) bool {
				switch n := node.(type) {
				case *ast.DeferStmt:
					deferred = deferred || n.Call == use.call
				case *ast.GoStmt:
					deferred = deferred || n.Call == use.call
				}
				return true
			})
			if deferred {
				return "", fmt.Errorf("a deferred/goroutine value method through a pointer needs receiver dereference before scheduling; this all-call expansion leaves it unchanged")
			}
			temporary := "inlineReceiver"
			for i := 2; used[temporary]; i++ {
				temporary = fmt.Sprintf("inlineReceiver%d", i)
			}
			receiverText = temporary + " *" + receiverType
			prelude := name + " := *" + temporary + ";"
			receiverUsed := false
			if len(receiver.Names) > 0 {
				object := file.Package.TypesInfo.Defs[receiver.Names[0]]
				ast.Inspect(callee.Body, func(node ast.Node) bool {
					if id, ok := node.(*ast.Ident); ok && file.Package.TypesInfo.Uses[id] == object {
						receiverUsed = true
					}
					return true
				})
			}
			if name == "_" || !receiverUsed {
				prelude = "_ = *" + temporary + ";"
			}
			literalFS := token.NewFileSet()
			expression, err := parser.ParseExprFrom(literalFS, "inline.go", text, 0)
			if err != nil {
				return "", err
			}
			bodyStart := literalFS.Position(expression.(*ast.FuncLit).Body.Lbrace).Offset + 1
			text = text[:bodyStart] + prelude + text[bodyStart:]
		}
		// Receiver type qualifiers are not expected for legal local Go methods.
		text = "func(" + receiverText + func() string {
			if len(callee.Type.Params.List) > 0 {
				return ", "
			}
			return ""
		}() + strings.TrimPrefix(text, "func(")
		if selection.Kind() == types.MethodVal {
			receiverExpr := rendered(sel.X)
			if wantPointer && !gotPointer {
				receiverExpr = "&(" + receiverExpr + ")"
			}
			arguments = append(arguments, receiverExpr)
		}
	}
	for _, arg := range use.call.Args {
		arguments = append(arguments, rendered(arg))
	}
	if use.call.Ellipsis.IsValid() && len(arguments) > 0 {
		arguments[len(arguments)-1] += "..."
	}
	return "(" + text + ")(" + strings.Join(arguments, ", ") + ")", nil
}

func (a *analysis) receiverTypeAt(use inlineUse, expression ast.Expr, want types.Type) (string, error) {
	valid := true
	ast.Inspect(expression, func(node ast.Node) bool {
		if id, ok := node.(*ast.Ident); ok {
			object := a.all.file.Package.TypesInfo.Uses[id]
			if object != nil && a.objectKey(lookupAt(use.file, use.call.Pos(), id.Name)) != a.objectKey(object) {
				valid = false
			}
		}
		return true
	})
	if valid {
		return rendered(expression), nil
	}
	return a.safeTypeTextAt(use.file, use.call.Pos(), want)
}

func lookupAt(file signature.SemanticFile, pos token.Pos, name string) types.Object {
	var smallest *types.Scope
	for _, scope := range file.Package.TypesInfo.Scopes {
		if scope.Contains(pos) && (smallest == nil || scope.End()-scope.Pos() < smallest.End()-smallest.Pos()) {
			smallest = scope
		}
	}
	if smallest == nil {
		smallest = file.Package.Types.Scope()
	}
	_, object := smallest.LookupParent(name, pos)
	return object
}
func removeUnusedNamedImports(fs *token.FileSet, file *ast.File, original signature.SemanticFile) {
	names := map[string]bool{}
	specIDs := map[*ast.Ident]bool{}
	for _, spec := range file.Imports {
		if spec.Name != nil {
			specIDs[spec.Name] = true
		}
	}
	ast.Inspect(file, func(node ast.Node) bool {
		if id, ok := node.(*ast.Ident); ok && !specIDs[id] {
			names[id.Name] = true
		}
		return true
	})
	for _, spec := range append([]*ast.ImportSpec{}, file.Imports...) {
		alias := ""
		if spec.Name != nil {
			alias = spec.Name.Name
		}
		path, _ := strconv.Unquote(spec.Path.Value)
		name := alias
		if name == "" {
			for _, old := range original.AST.Imports {
				p, _ := strconv.Unquote(old.Path.Value)
				if p == path {
					if imported, ok := original.Package.TypesInfo.Implicits[old].(*types.PkgName); ok {
						name = imported.Name()
					}
					break
				}
			}
		}
		if name == "" {
			continue
		}
		if name != "_" && name != "." && !names[name] {
			astutil.DeleteNamedImport(fs, file, alias, path)
		}
	}
}
