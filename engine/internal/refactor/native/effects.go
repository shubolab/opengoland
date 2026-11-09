package native

import (
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"sort"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	"golang.org/x/tools/go/types/typeutil"
)

func (a *analysis) collectInlineTargets(caret int) {
	info := a.target.Package.TypesInfo
	var object types.Object
	var selected *ast.Ident
	declaration := false
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		id, ok := node.(*ast.Ident)
		if !ok {
			return true
		}
		start, end := a.view.Fset.PositionFor(id.Pos(), false).Offset, a.view.Fset.PositionFor(id.End(), false).Offset
		if caret >= start && caret <= end {
			if obj := info.ObjectOf(id); obj != nil {
				object, selected = obj, id
				declaration = info.Defs[id] != nil
			}
		}
		return true
	})
	if object == nil {
		var call *ast.CallExpr
		ast.Inspect(a.target.AST, func(node ast.Node) bool {
			current, ok := node.(*ast.CallExpr)
			if !ok {
				return true
			}
			start, end := a.view.Fset.PositionFor(current.Pos(), false).Offset, a.view.Fset.PositionFor(current.End(), false).Offset
			if caret >= start && caret <= end && (call == nil || current.End()-current.Pos() < call.End()-call.Pos()) {
				call = current
			}
			return true
		})
		if call != nil {
			object = typeutil.StaticCallee(info, call)
			selected = callableName(call.Fun)
		}
	}
	kind := ""
	switch obj := object.(type) {
	case *types.Func:
		kind = "inline.call"
	case *types.Var:
		if obj.Parent() != nil && obj.Parent() != a.target.Package.Types.Scope() {
			kind = "inline.variable"
		}
	}
	if kind == "" || a.request.Kind != "inline" && a.request.Kind != kind {
		return
	}
	key := a.objectKey(object)
	seen := map[string]bool{}
	for _, file := range a.view.Files {
		if !a.snapshot.Editable(file.Path) {
			continue
		}
		for id, obj := range file.Package.TypesInfo.Uses {
			if a.objectKey(obj) != key {
				continue
			}
			position := a.view.Fset.PositionFor(id.Pos(), false)
			if position.Filename != file.Path {
				continue
			}
			if !declaration && (file.Path != a.target.Path || id != selected) {
				continue
			}
			start, end := position.Offset, a.view.Fset.PositionFor(id.End(), false).Offset
			if kind == "inline.call" {
				isCall := false
				ast.Inspect(file.AST, func(node ast.Node) bool {
					if call, ok := node.(*ast.CallExpr); ok && callableName(call.Fun) == id && a.objectKey(typeutil.StaticCallee(file.Package.TypesInfo, call)) == key {
						isCall = true
					}
					return true
				})
				if !isCall {
					continue
				}
			}
			rangeValue := signature.UTF16Range(file.Text, start, end)
			identity := digest([]any{file.URI, rangeValue})
			if seen[identity] {
				continue
			}
			seen[identity] = true
			lineStart := strings.LastIndex(file.Text[:start], "\n") + 1
			lineEnd := strings.Index(file.Text[end:], "\n")
			if lineEnd < 0 {
				lineEnd = len(file.Text)
			} else {
				lineEnd += end
			}
			text := strings.TrimSpace(file.Text[lineStart:lineEnd])
			if len([]rune(text)) > 180 {
				text = string([]rune(text)[:180]) + "…"
			}
			a.inspection.Targets = append(a.inspection.Targets, TargetChoice{TargetID: identity[:16], URI: file.URI, Range: rangeValue, Text: text, Kind: kind})
		}
	}
	sort.SliceStable(a.inspection.Targets, func(i, j int) bool {
		left, right := a.inspection.Targets[i], a.inspection.Targets[j]
		if left.URI != right.URI {
			return left.URI < right.URI
		}
		if left.Range.Start.Line != right.Range.Start.Line {
			return left.Range.Start.Line < right.Range.Start.Line
		}
		return left.Range.Start.Character < right.Range.Start.Character
	})
	if len(a.inspection.Targets) > 0 {
		position := a.view.Fset.PositionFor(object.Pos(), false)
		if file, exists := a.view.Files[position.Filename]; exists && a.snapshot.Editable(file.Path) {
			rangeValue := signature.UTF16Range(file.Text, position.Offset, position.Offset+len(object.Name()))
			all := TargetChoice{TargetID: digest([]any{file.URI, rangeValue, "all"})[:16], URI: file.URI, Range: rangeValue,
				Text: "All workspace references to " + object.Name() + " — choose declaration disposition", Kind: kind + "-all"}
			a.inspection.Targets = append([]TargetChoice{all}, a.inspection.Targets...)
		}
	}
}

func (a *analysis) objectKey(object types.Object) string {
	if object == nil {
		return ""
	}
	position := a.view.Fset.PositionFor(object.Pos(), false)
	pkg := ""
	if object.Pkg() != nil {
		pkg = object.Pkg().Path()
	}
	return fmt.Sprintf("%s|%s|%d|%s", pkg, position.Filename, position.Offset, object.Name())
}

type inlineBinding struct {
	object      types.Object
	use         *ast.Ident
	initializer ast.Expr
	declaration ast.Node
	removable   bool
}

func (a *analysis) inspectInline() {
	info := a.target.Package.TypesInfo
	if a.request.Kind == "inline.call" {
		var selected *ast.CallExpr
		ast.Inspect(a.target.AST, func(node ast.Node) bool {
			call, ok := node.(*ast.CallExpr)
			if !ok {
				return true
			}
			start, end := a.view.Fset.PositionFor(call.Pos(), false).Offset, a.view.Fset.PositionFor(call.End(), false).Offset
			if a.start >= start && a.end <= end && (selected == nil || call.End()-call.Pos() < selected.End()-selected.Pos()) {
				selected = call
			}
			return true
		})
		if selected == nil {
			a.reject("inline-target", "Choose a statically resolved Go function or method call")
			return
		}
		obj := typeutil.StaticCallee(info, selected)
		if obj == nil {
			a.reject("inline-target", "Function values and dynamic interface dispatch require a separate inline analysis")
			return
		}
		a.inline = &inlineBinding{object: obj}
		a.inspection.Inline = &InlineDetails{Name: obj.Name(), References: a.referenceCount(obj), CanKeep: true, CanRemove: false, RemovalReason: "This occurrence mode keeps the declaration; inline-all and definition removal need a separate workspace plan"}
		a.inspection.Signature = "Inline call to " + obj.FullName() + "; keep the declaration"
		return
	}
	var use *ast.Ident
	var object *types.Var
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		id, ok := node.(*ast.Ident)
		if !ok {
			return true
		}
		start, end := a.view.Fset.PositionFor(id.Pos(), false).Offset, a.view.Fset.PositionFor(id.End(), false).Offset
		if a.start >= start && a.end <= end {
			if obj, yes := info.Uses[id].(*types.Var); yes {
				use, object = id, obj
			}
		}
		return true
	})
	if object == nil || object.Parent() == nil || object.Parent() == a.target.Package.Types.Scope() {
		a.reject("inline-target", "Choose a use of an initialized local variable")
		return
	}
	inline := &inlineBinding{object: object, use: use}
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		switch declaration := node.(type) {
		case *ast.AssignStmt:
			if declaration.Tok != token.DEFINE || len(declaration.Lhs) != len(declaration.Rhs) {
				return true
			}
			for i, left := range declaration.Lhs {
				if id, ok := left.(*ast.Ident); ok && info.Defs[id] == object {
					inline.initializer = declaration.Rhs[i]
					inline.declaration = declaration
					inline.removable = len(declaration.Lhs) == 1
				}
			}
		case *ast.GenDecl:
			if declaration.Tok != token.VAR {
				return true
			}
			for _, spec := range declaration.Specs {
				value, ok := spec.(*ast.ValueSpec)
				if !ok || len(value.Names) != len(value.Values) {
					continue
				}
				for i, id := range value.Names {
					if info.Defs[id] == object {
						inline.initializer = value.Values[i]
						inline.declaration = value
						inline.removable = len(value.Names) == 1
						if len(declaration.Specs) == 1 {
							inline.declaration = declaration
						}
					}
				}
			}
		}
		return true
	})
	if inline.initializer == nil {
		a.reject("inline-initializer", "The local variable needs one independent initializer expression")
		return
	}
	a.inline = inline
	if !a.immutable(object) {
		a.reject("inline-effects", "The variable is assigned again or its address escapes; replacing it with its initializer would change behavior")
	}
	if _, ok := object.Type().Underlying().(*types.Basic); !ok {
		a.reject("inline-type", "This inline-variable planner currently requires a scalar value; aliasing, allocations and identity-bearing values need a separate effect analysis")
	}
	if !a.pureScalar(inline.initializer) {
		a.reject("inline-effects", "The initializer can have side effects, mutable dependencies or panic timing; it cannot safely be re-evaluated at this use")
	}
	count := a.referenceCount(object)
	reason := ""
	if !inline.removable {
		reason = "The declaration shares other bindings; this mode does not partially remove them"
	}
	a.inspection.Inline = &InlineDetails{Name: object.Name(), References: count, CanKeep: count > 1, CanRemove: count == 1 && inline.removable, RemovalReason: reason}
	if count > 1 {
		a.inspection.Inline.RemovalReason = "Other references remain; inline all of them before removing this declaration"
	}
	a.inspection.Signature = fmt.Sprintf("Inline local %s (%s), %d reference(s)", object.Name(), object.Type(), count)
}

func (a *analysis) referenceCount(object types.Object) int {
	seen := map[string]bool{}
	key := a.objectKey(object)
	for _, file := range a.view.Files {
		if !a.snapshot.Editable(file.Path) {
			continue
		}
		for id, obj := range file.Package.TypesInfo.Uses {
			position := a.view.Fset.PositionFor(id.Pos(), false)
			if position.Filename == file.Path && a.objectKey(obj) == key {
				seen[fmt.Sprintf("%s:%d", file.Path, position.Offset)] = true
			}
		}
	}
	return len(seen)
}

func (a *analysis) immutable(object types.Object) bool {
	variable, ok := object.(*types.Var)
	if !ok {
		return true
	}
	if variable.IsField() || variable.Parent() == nil || variable.Parent() == a.target.Package.Types.Scope() {
		return false
	}
	valid := true
	info := a.target.Package.TypesInfo
	contains := func(node ast.Node) bool {
		found := false
		ast.Inspect(node, func(n ast.Node) bool {
			if id, ok := n.(*ast.Ident); ok && info.Uses[id] == object {
				found = true
			}
			return true
		})
		return found
	}
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		switch n := node.(type) {
		case *ast.AssignStmt:
			for _, left := range n.Lhs {
				if contains(left) {
					valid = false
				}
			}
		case *ast.IncDecStmt:
			if contains(n.X) {
				valid = false
			}
		case *ast.RangeStmt:
			if n.Key != nil && contains(n.Key) || n.Value != nil && contains(n.Value) {
				valid = false
			}
		case *ast.UnaryExpr:
			if n.Op == token.AND && contains(n.X) {
				valid = false
			}
		case *ast.SelectorExpr:
			if selection := info.Selections[n]; selection != nil && selection.Kind() == types.MethodVal && contains(n.X) {
				if signature, ok := selection.Obj().Type().(*types.Signature); ok && signature.Recv() != nil {
					if _, pointer := signature.Recv().Type().(*types.Pointer); pointer {
						valid = false
					}
				}
			}
		}
		return valid
	})
	return valid
}

// This deliberately excludes calls, selectors, indexing, indirection and
// potentially panicking variable divisors. Type-check success alone is not an
// effect proof. Unsupported expressions remain untouched with a clear conflict.
func (a *analysis) pureScalar(expr ast.Expr) bool {
	info := a.target.Package.TypesInfo
	if value, ok := info.Types[expr]; ok && value.Value != nil {
		return true
	}
	switch e := expr.(type) {
	case *ast.BasicLit:
		return true
	case *ast.ParenExpr:
		return a.pureScalar(e.X)
	case *ast.Ident:
		obj := info.Uses[e]
		if obj == nil {
			return false
		}
		if _, ok := obj.(*types.Const); ok {
			return true
		}
		_, scalar := obj.Type().Underlying().(*types.Basic)
		return scalar && a.immutable(obj)
	case *ast.UnaryExpr:
		return (e.Op == token.ADD || e.Op == token.SUB || e.Op == token.XOR || e.Op == token.NOT) && a.pureScalar(e.X)
	case *ast.BinaryExpr:
		if e.Op == token.QUO || e.Op == token.REM || e.Op == token.SHL || e.Op == token.SHR {
			return false
		}
		return a.pureScalar(e.X) && a.pureScalar(e.Y)
	case *ast.CallExpr:
		if len(e.Args) != 1 || !info.Types[e.Fun].IsType() {
			return false
		}
		from, to := info.TypeOf(e.Args[0]), info.TypeOf(e)
		if from == nil || to == nil {
			return false
		}
		_, fromScalar := from.Underlying().(*types.Basic)
		_, toScalar := to.Underlying().(*types.Basic)
		return fromScalar && toScalar && a.pureScalar(e.Args[0])
	}
	return false
}

func (a *analysis) validateExtractionEffects() {
	if a.request.Kind == "extract.function" || a.request.Kind == "extract.method" {
		a.validateMovedFrameEffects()
		return
	}
	if !strings.HasPrefix(a.request.Kind, "extract.variable") && !strings.HasPrefix(a.request.Kind, "extract.constant") {
		return
	}
	var selected ast.Expr
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		expr, ok := node.(ast.Expr)
		if !ok {
			return true
		}
		start, end := a.view.Fset.PositionFor(expr.Pos(), false).Offset, a.view.Fset.PositionFor(expr.End(), false).Offset
		if start >= a.start && end <= a.end && (selected == nil || expr.End()-expr.Pos() > selected.End()-selected.Pos()) {
			selected = expr
		}
		return true
	})
	if selected == nil {
		a.reject("expression", "Select a complete value expression")
		return
	}
	if strings.HasSuffix(a.request.Kind, "-all") && !a.pureScalar(selected) {
		a.reject("occurrence-effects", "Replacing every occurrence requires a scalar expression with immutable dependencies and no effects")
	}
	// A single extraction may move evaluation before an enclosing lazy branch,
	// loop, defer or go statement. The safe scalar subset has no observable work
	// to move; effectful expressions need a dedicated evaluation-order planner.
	if !a.pureScalar(selected) {
		unsafe := false
		independent := false
		ast.Inspect(a.target.AST, func(node ast.Node) bool {
			if node == nil {
				return true
			}
			if node.Pos() > selected.Pos() || node.End() < selected.End() {
				return false
			}
			switch n := node.(type) {
			case *ast.AssignStmt:
				if len(n.Rhs) == 1 && n.Rhs[0] == selected {
					independent = true
					for _, left := range n.Lhs {
						if _, ok := left.(*ast.Ident); !ok {
							unsafe = true
						}
					}
				}
			case *ast.ValueSpec:
				if len(n.Values) == 1 && n.Values[0] == selected {
					independent = true
				}
			case *ast.ReturnStmt:
				if len(n.Results) == 1 && n.Results[0] == selected {
					independent = true
				}
			case *ast.ExprStmt:
				if n.X == selected {
					independent = true
				}
			case *ast.ForStmt, *ast.RangeStmt, *ast.GoStmt, *ast.DeferStmt:
				unsafe = true
			case *ast.BinaryExpr:
				if (n.Op == token.LAND || n.Op == token.LOR) && selected.Pos() >= n.Y.Pos() {
					unsafe = true
				}
			}
			return true
		})
		if unsafe || !independent {
			a.reject("evaluation-order", "This extraction can change lazy, loop, deferred or goroutine evaluation timing; the expression is left unchanged")
		}
	}
}

// Moving a statement into a helper adds a call frame. A defer belongs to the
// function executing that statement, and the built-in recover only works when
// called directly by the deferred function. Neither property is checked by a
// successful candidate type-check. An entire nested literal keeps its own frame
// when moved, so its defer/recover statements are deliberately not rejected.
func (a *analysis) validateMovedFrameEffects() {
	deferred, recovered := false, false
	if a.helper != nil {
		ast.Inspect(a.helper.Body, func(node ast.Node) bool {
			if _, literal := node.(*ast.FuncLit); literal {
				return false
			}
			if _, moved := node.(*ast.DeferStmt); moved {
				deferred = true
			}
			return true
		})
	}
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		if node == nil {
			return true
		}
		start := a.view.Fset.PositionFor(node.Pos(), false).Offset
		end := a.view.Fset.PositionFor(node.End(), false).Offset
		if end <= a.start || start >= a.end {
			return false
		}
		if _, literal := node.(*ast.FuncLit); literal && start >= a.start && end <= a.end {
			return false
		}
		switch n := node.(type) {
		case *ast.CallExpr:
			if id, ok := n.Fun.(*ast.Ident); ok {
				if object, ok := a.target.Package.TypesInfo.Uses[id].(*types.Builtin); ok && object.Name() == "recover" {
					recovered = true
				}
			}
		}
		return true
	})
	if deferred {
		a.reject("extraction-defer-frame", "The selection executes defer in the current function. Moving it into a helper would run the deferred work when the helper returns; source is unchanged")
	}
	if recovered {
		a.reject("extraction-recover-frame", "The selection calls the built-in recover directly. A new helper frame can prevent recovery; source is unchanged")
	}
}
