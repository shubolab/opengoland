package native

import (
	"context"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"go/types"
	"sort"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func Plan(ctx context.Context, request PlanRequest) (PlanResponse, error) {
	a, err := open(ctx, request.Request)
	if err != nil {
		return PlanResponse{}, err
	}
	defer a.snapshot.Close()
	response := PlanResponse{PlanResponse: signature.PlanResponse{SymbolID: a.inspection.InspectionID, ConfigurationDigest: a.snapshot.Digest(), Preconditions: []signature.Precondition{}, Directories: []signature.DirectoryCondition{}, WorkspaceEdit: signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{}}, Usages: []signature.Usage{}, Coverage: a.view.Coverage, Conflicts: append([]signature.Conflict{}, a.inspection.Conflicts...), Validation: signature.Validation{BeforeErrors: append([]string{}, a.view.Errors...), AfterErrors: []string{}}}, InspectionID: a.inspection.InspectionID, Kind: a.request.Kind}
	reject := func(code, message string) { response.Conflicts = append(response.Conflicts, a.conflict(code, message)) }
	finish := func() (PlanResponse, error) {
		if err := a.snapshot.Unchanged(); err != nil {
			return PlanResponse{}, err
		}
		response.Preconditions = a.snapshot.Preconditions()
		response.Directories = a.snapshot.Directories()
		if len(response.Conflicts) > 0 {
			response.WorkspaceEdit.DocumentChanges = []signature.DocumentEdit{}
			response.Focus = nil
		}
		response.PlanDigest = digest(response)
		response.PlanID = response.PlanDigest[:24]
		return response, nil
	}
	if request.InspectionID != a.inspection.InspectionID {
		reject("stale-inspection", "The selected source or refactoring candidate changed; inspect again")
	}
	if request.ConfigurationDigest != a.snapshot.Digest() {
		reject("stale-configuration", "The Go SDK or workspace configuration changed; inspect again")
	}
	if a.all != nil {
		if len(response.Conflicts) > 0 {
			return finish()
		}
		candidates, err := a.allInlineCandidates(request.InlineDeclaration)
		if err != nil {
			reject("inline-all-choice", err.Error())
			return finish()
		}
		if err := a.validateCandidates(ctx, candidates, &response, reject); err != nil {
			return PlanResponse{}, err
		}
		return finish()
	}
	if len(request.CandidateEdit.DocumentChanges) == 0 || a.candidate == "" {
		reject("candidate", "Choose a resolved Go refactoring candidate before planning")
	}
	if a.request.Kind == "inline" {
		reject("inline-target", "Choose one exact inline call or variable target")
	}
	if len(response.Conflicts) > 0 {
		return finish()
	}
	if strings.HasPrefix(a.request.Kind, "extract.") && len(a.bindings) == 0 {
		reject("candidate-bindings", "The candidate introduced no independently identified Go binding")
		return finish()
	}
	beforeEffects := len(a.inspection.Conflicts)
	a.validateExtractionEffects()
	response.Conflicts = append(response.Conflicts, a.inspection.Conflicts[beforeEffects:]...)
	if len(response.Conflicts) > 0 {
		return finish()
	}
	selected := map[string]string{}
	known := map[string]bool{}
	for _, b := range a.bindings {
		known[b.wire.NameID] = true
	}
	for _, name := range request.Names {
		if !known[name.NameID] || selected[name.NameID] != "" {
			reject("name-selection", "A selected generated binding is unknown or duplicated")
			continue
		}
		if reason := a.nameConflict(name.Name); reason != "" {
			reject("name-conflict", name.Name+": "+reason)
		}
		selected[name.NameID] = name.Name
	}
	if len(selected) != len(a.bindings) {
		reject("name-selection", "Choose a name for every generated binding")
	}
	used := map[string]bool{}
	for _, name := range selected {
		if used[name] {
			reject("name-conflict", "Generated bindings must use distinct names")
		}
		used[name] = true
	}
	if len(response.Conflicts) > 0 {
		return finish()
	}
	var edits []signature.ByteEdit
	positions := map[int]bool{}
	for _, b := range a.bindings {
		for _, id := range b.identifiers {
			start, end := a.fset.PositionFor(id.Pos(), false).Offset, a.fset.PositionFor(id.End(), false).Offset
			if start < 0 || end > len(a.candidate) || a.candidate[start:end] != b.wire.GeneratedName {
				reject("binding-position", "A generated binding no longer matches its candidate source")
				continue
			}
			if !positions[start] {
				edits = append(edits, signature.ByteEdit{Start: start, End: end, NewText: selected[b.wire.NameID]})
				positions[start] = true
			}
		}
	}
	// gopls offers only a constant action for constant-valued expressions. A
	// standard Extract Variable request deliberately changes that new declaration
	// to var before final checking, rather than silently performing another action.
	if strings.HasPrefix(a.request.Kind, "extract.variable") {
		ast.Inspect(a.parsed, func(node ast.Node) bool {
			decl, ok := node.(*ast.GenDecl)
			if !ok || decl.Tok != token.CONST {
				return true
			}
			fresh := false
			for _, spec := range decl.Specs {
				if v, ok := spec.(*ast.ValueSpec); ok {
					for _, id := range v.Names {
						if positions[a.fset.PositionFor(id.Pos(), false).Offset] {
							fresh = true
						}
					}
				}
			}
			if fresh {
				start := a.fset.PositionFor(decl.TokPos, false).Offset
				edits = append(edits, signature.ByteEdit{Start: start, End: start + len("const"), NewText: "var"})
			}
			return true
		})
	}
	if len(response.Conflicts) > 0 {
		return finish()
	}
	candidate := applyBytes(a.candidate, edits)
	if strings.HasPrefix(a.request.Kind, "inline.") {
		candidate, err = a.inlineCandidate(request.InlineDeclaration)
		if err != nil {
			reject("inline-choice", err.Error())
			return finish()
		}
	}
	formatted, err := format.Source([]byte(candidate))
	if err != nil {
		reject("candidate-syntax", "The named refactoring is not valid Go: "+err.Error())
		return finish()
	}
	candidate = string(formatted)
	view, err := a.snapshot.Load(ctx, map[string]string{a.target.Path: candidate})
	if err != nil {
		return PlanResponse{}, err
	}
	response.Validation.AfterErrors = append([]string{}, view.Errors...)
	if len(view.Errors) > 0 {
		reject("candidate-typecheck", "The named refactoring does not type-check:\n"+strings.Join(view.Errors, "\n"))
		return finish()
	}
	response.Validation.TypeChecked = true
	var version *int
	if a.target.Overlay != nil && a.target.Overlay.LSP != nil {
		value := a.target.Overlay.LSP.Version
		version = &value
	}
	response.WorkspaceEdit.DocumentChanges = []signature.DocumentEdit{{TextDocument: signature.TextDocument{URI: a.target.URI, Version: version}, Edits: []signature.TextEdit{{Range: signature.UTF16Range(a.target.Text, 0, len(a.target.Text)), NewText: candidate}}}}
	response.Usages = []signature.Usage{{URI: a.target.URI, Range: a.request.Selection, Kind: a.request.Kind, Message: "Validated Go refactoring; one atomic editor transaction"}}
	if len(a.bindings) > 0 {
		response.Focus = a.focus(candidate, selected[a.bindings[0].wire.NameID])
	}
	return finish()
}

func applyBytes(source string, edits []signature.ByteEdit) string {
	sort.SliceStable(edits, func(i, j int) bool { return edits[i].Start > edits[j].Start })
	for _, edit := range edits {
		source = source[:edit.Start] + edit.NewText + source[edit.End:]
	}
	return source
}

func (a *analysis) focus(source, name string) *Focus {
	fs := token.NewFileSet()
	file, err := parser.ParseFile(fs, a.target.Path, source, 0)
	if err != nil {
		return nil
	}
	var result *ast.Ident
	ast.Inspect(file, func(node ast.Node) bool {
		if result != nil {
			return false
		}
		switch n := node.(type) {
		case *ast.FuncDecl:
			if n.Name.Name == name && a.helper != nil && rendered(n.Recv) == rendered(a.helper.Recv) {
				result = n.Name
			}
		case *ast.ValueSpec:
			if a.helper == nil {
				for _, id := range n.Names {
					if id.Name == name {
						result = id
						break
					}
				}
			}
		case *ast.AssignStmt:
			if a.helper == nil && n.Tok == token.DEFINE {
				for _, left := range n.Lhs {
					if id, ok := left.(*ast.Ident); ok && id.Name == name {
						result = id
						break
					}
				}
			}
		}
		return true
	})
	if result == nil {
		return nil
	}
	return &Focus{URI: a.target.URI, Range: signature.UTF16Range(source, fs.PositionFor(result.Pos(), false).Offset, fs.PositionFor(result.End(), false).Offset)}
}

func (a *analysis) inlineCandidate(disposition string) (string, error) {
	if a.inline == nil || a.inspection.Inline == nil {
		return "", fmt.Errorf("the inline target is unavailable")
	}
	if disposition == "" {
		disposition = "keep"
	}
	if disposition != "keep" && disposition != "remove" {
		return "", fmt.Errorf("choose whether to keep or remove the original declaration")
	}
	if disposition == "keep" && !a.inspection.Inline.CanKeep {
		return "", fmt.Errorf("keeping the last-use local declaration would leave it unused; explicitly choose safe removal")
	}
	if disposition == "remove" && !a.inspection.Inline.CanRemove {
		return "", fmt.Errorf("cannot remove this declaration: %s", a.inspection.Inline.RemovalReason)
	}
	if a.request.Kind == "inline.call" {
		return a.candidate, nil
	}
	variable, ok := a.inline.object.(*types.Var)
	if !ok {
		return "", fmt.Errorf("no local inline variable")
	}
	replacement, err := a.scalarReplacement(variable)
	if err != nil {
		return "", err
	}
	useStart, useEnd := a.view.Fset.PositionFor(a.inline.use.Pos(), false).Offset, a.view.Fset.PositionFor(a.inline.use.End(), false).Offset
	edits := []signature.ByteEdit{{Start: useStart, End: useEnd, NewText: replacement}}
	if disposition == "remove" {
		start, end := a.view.Fset.PositionFor(a.inline.declaration.Pos(), false).Offset, a.view.Fset.PositionFor(a.inline.declaration.End(), false).Offset
		if end > useStart {
			return "", fmt.Errorf("the declaration/use order is not supported by this removal mode")
		}
		edits = append(edits, signature.ByteEdit{Start: start, End: end, NewText: ""})
	}
	return applyBytes(a.target.Text, edits), nil
}

func (a *analysis) scalarReplacement(variable *types.Var) (string, error) {
	var capture string
	skipped := map[*ast.Ident]bool{}
	ast.Inspect(a.inline.initializer, func(node ast.Node) bool {
		if selector, ok := node.(*ast.SelectorExpr); ok {
			skipped[selector.Sel] = true
		}
		return true
	})
	ast.Inspect(a.inline.initializer, func(node ast.Node) bool {
		id, ok := node.(*ast.Ident)
		if !ok || skipped[id] {
			return true
		}
		object := a.target.Package.TypesInfo.Uses[id]
		if object != nil && a.objectKey(lookupAt(a.target, a.inline.use.Pos(), id.Name)) != a.objectKey(object) {
			capture = id.Name
		}
		return true
	})
	if capture != "" {
		return "", fmt.Errorf("the name %s resolves to a different binding at an inline use; source is unchanged", capture)
	}
	typeText, err := a.safeTypeTextAt(a.target, a.inline.use.Pos(), variable.Type())
	if err != nil {
		return "", err
	}
	rhsStart, rhsEnd := a.view.Fset.PositionFor(a.inline.initializer.Pos(), false).Offset, a.view.Fset.PositionFor(a.inline.initializer.End(), false).Offset
	// Preserve precedence and the variable's concrete type, including named or
	// explicitly narrowed scalar types passed through an interface.
	return "(" + typeText + ")(" + a.target.Text[rhsStart:rhsEnd] + ")", nil
}

func (a *analysis) validateCandidates(ctx context.Context, candidates map[string]string, response *PlanResponse, reject func(string, string)) error {
	for path, source := range candidates {
		formatted, err := format.Source([]byte(source))
		if err != nil {
			reject("candidate-syntax", "The workspace inline candidate is not valid Go: "+err.Error())
			return nil
		}
		candidates[path] = string(formatted)
	}
	view, err := a.snapshot.Load(ctx, candidates)
	if err != nil {
		return err
	}
	response.Validation.AfterErrors = append([]string{}, view.Errors...)
	if len(view.Errors) > 0 {
		reject("candidate-typecheck", "The workspace inline candidate does not type-check:\n"+strings.Join(view.Errors, "\n"))
		return nil
	}
	response.Validation.TypeChecked = true
	paths := make([]string, 0, len(candidates))
	for path := range candidates {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	for _, path := range paths {
		file := a.view.Files[path]
		var version *int
		if file.Overlay != nil && file.Overlay.LSP != nil {
			value := file.Overlay.LSP.Version
			version = &value
		}
		response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, signature.DocumentEdit{
			TextDocument: signature.TextDocument{URI: file.URI, Version: version},
			Edits:        []signature.TextEdit{{Range: signature.UTF16Range(file.Text, 0, len(file.Text)), NewText: candidates[path]}},
		})
	}
	for _, use := range a.all.uses {
		start, end := a.view.Fset.PositionFor(use.id.Pos(), false).Offset, a.view.Fset.PositionFor(use.id.End(), false).Offset
		response.Usages = append(response.Usages, signature.Usage{URI: use.file.URI, Range: signature.UTF16Range(use.file.Text, start, end), Kind: a.request.Kind, Message: "Workspace inline; one atomic editor transaction"})
	}
	return nil
}
