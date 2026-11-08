package signature

import (
	"context"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"sort"
	"strings"
)

func Plan(ctx context.Context, req PlanRequest) (PlanResponse, error) {
	s, err := newSnapshot(req.Request)
	if err != nil {
		return PlanResponse{}, err
	}
	defer s.close()
	a, err := s.load(ctx, nil)
	if err != nil {
		return PlanResponse{}, err
	}
	target, err := a.target(req.Target)
	if err != nil {
		return PlanResponse{}, err
	}
	response := PlanResponse{SymbolID: a.symbolID(target), ConfigurationDigest: s.digest, Preconditions: []Precondition{}, WorkspaceEdit: WorkspaceEdit{DocumentChanges: []DocumentEdit{}}, Usages: []Usage{}, Coverage: a.coverage(), Conflicts: []Conflict{}, Validation: Validation{BeforeErrors: a.errors, AfterErrors: []string{}}}
	reject := func(code, message string) {
		response.Conflicts = append(response.Conflicts, conflictAt(target.file, target.name, code, message))
	}
	if req.SymbolID != response.SymbolID {
		reject("stale-symbol", "The inspected declaration changed; reopen Change Signature")
	}
	if req.ConfigurationDigest != s.digest {
		reject("stale-configuration", "The Go toolchain or workspace configuration changed; reopen Change Signature")
	}
	if err = validRows(req.Change, target); err != nil {
		reject("signature", err.Error())
	}
	if len(response.Conflicts) > 0 {
		return finishResponse(response, s), nil
	}
	family := a.relationClosure(target)
	keys := familyKeys(family)
	if req.Change.Name != target.object.Name() {
		response.Conflicts = append(response.Conflicts, a.renameConflicts(family, keys, req.Change.Name)...)
	}
	edits := newEdits()
	imports := map[string]*importManager{}
	for path, fi := range a.files {
		imports[path] = newImports(fi)
	}
	plans := []*declarationPlan{}
	for _, d := range family {
		if err = ctx.Err(); err != nil {
			return PlanResponse{}, err
		}
		dp, conflicts := planDeclaration(a, target, d, req.Change, edits, imports[d.file.source.Path])
		response.Conflicts = append(response.Conflicts, conflicts...)
		if dp != nil {
			plans = append(plans, dp)
		}
		response.Usages = append(response.Usages, Usage{d.file.source.URI, sourceRange(d.file.source, d.file.offset(d.name.Pos()), d.file.offset(d.ft.End())), "definition", "Update this related function or interface method"})
	}
	// Rename references by their typed object identity, including implementations
	// and selectors. Never search/replace an identifier spelling globally.
	for _, fi := range a.files {
		for id, obj := range fi.pkg.TypesInfo.Uses {
			fn, ok := obj.(*types.Func)
			if !ok || !keys[objectKey(a.fset, fn)] || id.Name == req.Change.Name {
				continue
			}
			if err = edits.add(fi.source.Path, ByteEdit{fi.offset(id.Pos()), fi.offset(id.End()), req.Change.Name}); err != nil {
				response.Conflicts = append(response.Conflicts, conflictAt(fi, id, "overlap", err.Error()))
			}
		}
	}
	shapeChanged := callShapeChanged(target, req.Change)
	relations := a.methodRelations(target.object.Name())
	if shapeChanged || req.Change.Name != target.object.Name() {
		for _, d := range family {
			if contract := relations.readonly[d.key]; contract != "" {
				response.Conflicts = append(response.Conflicts, conflictAt(d.file, d.name, "readonly-interface-contract", "This method implements read-only interface "+contract+"; its unchanged dynamic callers require a deliberate compatibility migration"))
			}
			if relations.uncertain[d.key] {
				response.Conflicts = append(response.Conflicts, conflictAt(d.file, d.name, "generic-relationship", "A potential generic interface relationship has overlapping constraints that cannot yet be proven for every instantiation; choose an explicit relationship migration"))
			}
		}
		response.Conflicts = append(response.Conflicts, a.inactiveReferences(target.object.Name())...)
		if req.Change.Name != target.object.Name() {
			response.Conflicts = append(response.Conflicts, a.inactiveReferences(req.Change.Name)...)
		}
	}
	if shapeChanged {
		paths := make([]string, 0, len(a.files))
		for path := range a.files {
			paths = append(paths, path)
		}
		sort.Strings(paths)

		for _, path := range paths {
			usages, conflicts, er := planFileUsages(ctx, a, target, a.files[path], family, keys, req.Change, imports[path], edits)
			if er != nil {
				return PlanResponse{}, er
			}
			response.Usages = append(response.Usages, usages...)
			response.Conflicts = append(response.Conflicts, conflicts...)
		}
	}

	for _, dp := range plans {
		response.Conflicts = append(response.Conflicts, planReturns(a, target, dp, req.Change, edits, imports[dp.declaration.file.source.Path])...)
	}
	for _, im := range imports {
		if err = im.emit(edits); err != nil {
			response.Conflicts = append(response.Conflicts, conflictAt(im.fi, im.fi.ast.Name, "imports", err.Error()))
		}
	}
	if len(response.Conflicts) > 0 {
		return finishResponse(response, s), nil
	}
	candidates, err := edits.candidates(a)
	if err != nil {
		reject("generation", err.Error())
		return finishResponse(response, s), nil
	}
	checked, err := s.load(ctx, candidates)
	if err != nil {
		return PlanResponse{}, err
	}
	response.Validation.AfterErrors = checked.errors
	newErrors := newDiagnostics(a.errors, checked.errors)
	for _, message := range newErrors {
		reject("typecheck", message)
	}
	response.Validation.TypeChecked = len(checked.errors) == 0
	if len(response.Conflicts) > 0 {
		return finishResponse(response, s), nil
	}
	// Verify the actual requested declaration shape, not only a successful load.
	for _, old := range family {
		var actual *declaration
		for _, d := range checked.decls {
			if d.file.source.Path == old.file.source.Path && d.name.Name == req.Change.Name && d.file.offset(d.name.Pos()) >= old.file.offset(old.ft.Pos())-32 {
				actual = d
				break
			}
		}
		if actual == nil || len(actual.parameters) != len(req.Change.Parameters) || len(actual.results) != len(req.Change.Results) {
			reject("intent", "The candidate does not contain every requested related signature")
			break
		}
	}
	if len(response.Conflicts) > 0 {
		return finishResponse(response, s), nil
	}
	if err = s.unchanged(); err != nil {
		return PlanResponse{}, err
	}
	paths := make([]string, 0, len(candidates))
	for path := range candidates {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	for _, path := range paths {
		old := a.files[path].source
		var version *int
		if old.Overlay != nil && old.Overlay.LSP != nil {
			v := old.Overlay.LSP.Version
			version = &v
		}
		response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, DocumentEdit{TextDocument{old.URI, version}, []TextEdit{{Range: sourceRange(old, 0, len(old.Text)), NewText: candidates[path]}}})
	}
	return finishResponse(response, s), nil
}
func finishResponse(response PlanResponse, s *snapshot) PlanResponse {
	sort.Slice(response.Conflicts, func(i, j int) bool {
		x, y := response.Conflicts[i], response.Conflicts[j]
		if x.URI != y.URI {
			return x.URI < y.URI
		}
		if x.Range.Start.Line != y.Range.Start.Line {
			return x.Range.Start.Line < y.Range.Start.Line
		}
		if x.Range.Start.Character != y.Range.Start.Character {
			return x.Range.Start.Character < y.Range.Start.Character
		}
		return x.Code+x.Message < y.Code+y.Message
	})
	response.Preconditions = s.preconditions()
	response.Directories = s.directoryConditions()
	sort.Slice(response.Usages, func(i, j int) bool {
		x, y := response.Usages[i], response.Usages[j]
		if x.URI != y.URI {
			return x.URI < y.URI
		}
		if x.Range.Start.Line != y.Range.Start.Line {
			return x.Range.Start.Line < y.Range.Start.Line
		}
		return x.Range.Start.Character < y.Range.Start.Character
	})
	// Conflicted plans never expose a partial edit for accidental application.
	if len(response.Conflicts) > 0 {
		response.WorkspaceEdit.DocumentChanges = []DocumentEdit{}
	}
	data, _ := json.Marshal(response)
	response.PlanDigest = hash(data)
	response.PlanID = response.PlanDigest[:24]
	return response
}
func callShapeChanged(d *declaration, c Change) bool {
	if len(d.parameters) != len(c.Parameters) || len(d.results) != len(c.Results) || len(c.RemovedResultUseMigrations) > 0 {
		return true
	}
	for i, r := range c.Parameters {
		if r.OldIndex == nil || *r.OldIndex != i || r.Type != d.parameters[i].row.Type || r.Variadic != d.parameters[i].row.Variadic || r.DefaultArgument != nil {
			return true
		}
	}
	for i, r := range c.Results {
		if r.OldIndex == nil || *r.OldIndex != i || r.Type != d.results[i].row.Type {
			return true
		}
	}
	return false
}
func newDiagnostics(before, after []string) []string {
	counts := map[string]int{}
	for _, s := range before {
		counts[diagnosticText(s)]++
	}
	var out []string
	for _, s := range after {
		k := diagnosticText(s)
		if counts[k] > 0 {
			counts[k]--
		} else {
			out = append(out, s)
		}
	}
	return out
}
func diagnosticText(s string) string {
	if i := strings.Index(s, ": "); i >= 0 {
		return s[i+2:]
	}
	return s
}
func familyMayRecover(ds []*declaration) bool {
	for _, d := range ds {
		if d.body == nil {
			continue
		}
		found := false
		ast.Inspect(d.body, func(n ast.Node) bool {
			if _, nested := n.(*ast.FuncLit); nested {
				return false
			}
			if call, ok := n.(*ast.CallExpr); ok {
				if id, ok := call.Fun.(*ast.Ident); ok && id.Name == "recover" && d.file.pkg.TypesInfo.Uses[id] == types.Universe.Lookup("recover") {
					found = true
				}
			}
			return !found
		})
		if found {
			return true
		}
	}
	return false
}
func callInput(a *analysis, target *declaration, fi *fileInfo, call *ast.CallExpr, parent ast.Node, imports *importManager, edits *edits, c Change) (CallRewriteInput, error) {
	sig, _ := fi.pkg.TypesInfo.TypeOf(call.Fun).(*types.Signature)
	if sig == nil {
		return CallRewriteInput{}, fmt.Errorf("call has no resolved signature")
	}
	offset := 0
	selector := baseSelector(call.Fun)
	if selector != nil {
		if selection := fi.pkg.TypesInfo.Selections[selector]; selection != nil && selection.Kind() == types.MethodExpr {
			offset = 1
		}
	}
	substitutions := map[string]string{}
	for i, p := range target.parameters {
		if i+offset < sig.Params().Len() {
			inferSubstitutions(p.typ, sig.Params().At(i+offset).Type(), substitutions, func(t types.Type) string { text, _ := imports.render(t, call.Pos()); return text })
		}
	}
	paramTypes, err := callerTypes(a, target, fi, imports, call.Pos(), c.Parameters, target.parameters, sig.Params(), offset, substitutions)
	if err != nil {
		return CallRewriteInput{}, err
	}
	resultTypes, err := callerTypes(a, target, fi, imports, call.Pos(), c.Results, target.results, sig.Results(), 0, substitutions)
	if err != nil {
		return CallRewriteInput{}, err
	}
	removed := map[int]string{}
	for _, m := range c.RemovedResultUseMigrations {
		for i, p := range target.results {
			if p.row.RowID == m.RowID {
				removed[i] = m.Expression
			}
		}
	}
	discarded := map[int]bool{}
	if assign, ok := parent.(*ast.AssignStmt); ok && len(assign.Rhs) == 1 && assign.Rhs[0] == call {
		for i, lhs := range assign.Lhs {
			if id, ok := lhs.(*ast.Ident); ok && id.Name == "_" {
				discarded[i] = true
			}
		}
	}
	return CallRewriteInput{Context: context.Background(), Fset: a.fset, File: fi.ast, Info: fi.pkg.TypesInfo, Package: fi.pkg.Types, Call: call, Parent: parent, Source: fi.source.Text, URI: fi.source.URI, Signature: sig, OldParameters: rows(target.parameters), OldResults: rows(target.results), CalleeText: edits.text(fi, call.Fun), NodeText: func(n ast.Node) string { return edits.text(fi, n) }, TypeText: func(t types.Type) (string, error) { return imports.render(t, call.Pos()) }, NewParameterTypes: paramTypes, NewResultTypes: resultTypes, GenericSubstitutions: substitutions, RemovedResultExpressions: removed, DiscardedResults: discarded, RewriteExpression: func(text string, bindings map[string]string) (string, error) {
		return qualifiedExpression(a, target, fi, imports, call.Pos(), text, bindings, false)
	}}, nil
}
func baseSelector(expr ast.Expr) *ast.SelectorExpr {
	switch x := expr.(type) {
	case *ast.SelectorExpr:
		return x
	case *ast.IndexExpr:
		return baseSelector(x.X)
	case *ast.IndexListExpr:
		return baseSelector(x.X)
	case *ast.ParenExpr:
		return baseSelector(x.X)
	}
	return nil
}
func callerTypes(a *analysis, target *declaration, fi *fileInfo, imports *importManager, pos token.Pos, next []Row, old []parameter, tuple *types.Tuple, offset int, substitutions map[string]string) ([]string, error) {
	out := make([]string, len(next))
	for i, row := range next {
		if row.OldIndex != nil && row.Type == old[*row.OldIndex].row.Type {
			index := *row.OldIndex + offset
			if index >= tuple.Len() {
				return nil, fmt.Errorf("signature arity differs across related methods")
			}
			t := tuple.At(index).Type()
			if old[*row.OldIndex].row.Variadic {
				if slice, ok := t.(*types.Slice); ok {
					t = slice.Elem()
				}
			}
			text, err := imports.render(t, pos)
			if err != nil {
				return nil, err
			}
			out[i] = text
			continue
		}
		value, err := types.Eval(a.fset, target.file.pkg.Types, target.ft.End()-1, row.Type)
		if err != nil || !value.IsType() {
			return nil, fmt.Errorf("cannot resolve new type %s", row.Type)
		}
		text, err := imports.render(value.Type, pos)
		if err != nil {
			return nil, err
		}
		text, err = callSubstituteType(text, substitutions)
		if err != nil {
			return nil, err
		}
		out[i] = text
	}
	return out, nil
}
func inferSubstitutions(pattern, actual types.Type, out map[string]string, render func(types.Type) string) {
	switch p := pattern.(type) {
	case *types.TypeParam:
		out[p.Obj().Name()] = render(actual)
	case *types.Slice:
		if a, ok := actual.(*types.Slice); ok {
			inferSubstitutions(p.Elem(), a.Elem(), out, render)
		}
	case *types.Pointer:
		if a, ok := actual.(*types.Pointer); ok {
			inferSubstitutions(p.Elem(), a.Elem(), out, render)
		}
	case *types.Array:
		if a, ok := actual.(*types.Array); ok {
			inferSubstitutions(p.Elem(), a.Elem(), out, render)
		}
	case *types.Map:
		if a, ok := actual.(*types.Map); ok {
			inferSubstitutions(p.Key(), a.Key(), out, render)
			inferSubstitutions(p.Elem(), a.Elem(), out, render)
		}
	case *types.Chan:
		if a, ok := actual.(*types.Chan); ok {
			inferSubstitutions(p.Elem(), a.Elem(), out, render)
		}
	case *types.Named:
		if a, ok := actual.(*types.Named); ok && p.Origin().Obj().Pkg().Path() == a.Origin().Obj().Pkg().Path() && p.Origin().Obj().Name() == a.Origin().Obj().Name() {
			for i := 0; i < p.TypeArgs().Len() && i < a.TypeArgs().Len(); i++ {
				inferSubstitutions(p.TypeArgs().At(i), a.TypeArgs().At(i), out, render)
			}
		}
	}
}

func planFileUsages(ctx context.Context, a *analysis, target *declaration, fi *fileInfo, family []*declaration, keys map[string]bool, change Change, imports *importManager, e *edits) ([]Usage, []Conflict, error) {
	type item struct {
		node ast.Expr
		call *ast.CallExpr
	}
	var work []item
	ast.Inspect(fi.ast, func(n ast.Node) bool {
		if call, ok := n.(*ast.CallExpr); ok {
			if fn := calledObject(fi.pkg.TypesInfo, call.Fun); fn != nil && keys[objectKey(a.fset, fn)] {
				work = append(work, item{call, call})
			}
		}
		id, ok := n.(*ast.Ident)
		if !ok {
			return true
		}
		fn, ok := fi.pkg.TypesInfo.Uses[id].(*types.Func)
		if !ok || !keys[objectKey(a.fset, fn)] {
			return true
		}
		expr := ast.Expr(id)
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
			work = append(work, item{node: expr})
		}
		return true
	})
	// One postorder covers calls nested in method-value receivers AND values
	// nested in call arguments. Each parent incorporates already rewritten children.
	sort.Slice(work, func(i, j int) bool {
		a, b := work[i].node, work[j].node
		if a.End()-a.Pos() != b.End()-b.Pos() {
			return a.End()-a.Pos() < b.End()-b.Pos()
		}
		return a.Pos() > b.Pos()
	})
	var usages []Usage
	var conflicts []Conflict
	for _, entry := range work {
		if err := ctx.Err(); err != nil {
			return nil, nil, err
		}
		call := entry.call
		if call == nil {
			call = &ast.CallExpr{Fun: entry.node, Lparen: entry.node.End(), Rparen: entry.node.End()}
		}
		input, err := callInput(a, target, fi, call, fi.parents[entry.node], imports, e, change)
		if err != nil {
			conflicts = append(conflicts, conflictAt(fi, entry.node, "usage-types", err.Error()))
			continue
		}
		input.Context = ctx
		var generated []ByteEdit
		var rejected []Conflict
		kind := "call"
		if entry.call == nil {
			kind = "functionValue"
			generated, rejected = RewriteValue(ValueRewriteInput{CallRewriteInput: input, Expr: entry.node, TargetMayRecover: familyMayRecover(family)}, change)
		} else {
			generated, rejected = RewriteCall(input, change)
		}
		conflicts = append(conflicts, rejected...)
		for _, edit := range generated {
			if err = e.add(fi.source.Path, edit); err != nil {
				conflicts = append(conflicts, conflictAt(fi, entry.node, "overlap", err.Error()))
			}
		}
		usages = append(usages, Usage{fi.source.URI, sourceRange(fi.source, fi.offset(entry.node.Pos()), fi.offset(entry.node.End())), kind, "Migrate this typed usage while preserving evaluation order"})
	}
	return usages, conflicts, nil
}
