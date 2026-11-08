package signature

import (
	"context"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
)

// CallRewriteInput belongs to one immutable, already typed source snapshot.
// TypeText may add imports through the caller's per-file import planner. The
// optional new type strings are instantiated and qualified in this call's scope.
// NodeText composes already planned edits in nested calls without overlapping
// edits; offsets always continue to refer to Source.
type CallRewriteInput struct {
	Context                           context.Context
	Fset                              *token.FileSet
	File                              *ast.File
	Info                              *types.Info
	Package                           *types.Package
	Call                              *ast.CallExpr
	Parent                            ast.Node
	Source, URI                       string
	Signature                         *types.Signature
	OldParameters, OldResults         []Row
	CalleeText                        string
	NodeText                          func(ast.Node) string
	TypeText                          func(types.Type) (string, error)
	NewParameterTypes, NewResultTypes []string
	GenericSubstitutions              map[string]string
	RemovedResultExpressions          map[int]string
	DiscardedResults                  map[int]bool
	// RewriteExpression binds a declaration-context migration/default expression
	// before moving it to this caller. The fallback binds free parameter/result
	// identifiers and rejects ambiguous named-literal field keys.
	RewriteExpression func(string, map[string]string) (string, error)
}

// ValueRewriteInput captures a bound receiver when the function value is
// created, rather than reevaluating it whenever the adapter is invoked.
type ValueRewriteInput struct {
	CallRewriteInput
	Expr             ast.Expr
	TargetMayRecover bool
}

// RewriteCall preserves the original argument evaluation and the old result
// shape. A defer/go statement is lowered separately so its eventual direct
// callee remains the target, which is essential for recover.
func RewriteCall(in CallRewriteInput, change Change) ([]ByteEdit, []Conflict) {
	if in.Call == nil || in.Fset == nil || in.File == nil || in.Info == nil {
		var node ast.Node
		if in.Call != nil {
			node = in.Call
		}
		return nil, []Conflict{callConflict(in, node, "call-context", "The typed call snapshot is incomplete")}
	}
	if in.Context != nil && in.Context.Err() != nil {
		return nil, []Conflict{callConflict(in, in.Call, "cancelled", in.Context.Err().Error())}
	}
	m, err := prepareCallModel(in, change)
	if err != nil {
		return nil, []Conflict{callConflict(in, in.Call, "call-migration", err.Error())}
	}
	if text, proven := m.directIfProven(); proven {
		return []ByteEdit{{Start: callOffset(in, in.Call.Pos()), End: callOffset(in, in.Call.End()), NewText: text}}, nil
	}
	var replacement string
	start, end := in.Call.Pos(), in.Call.End()
	switch parent := in.Parent.(type) {
	case *ast.DeferStmt:
		if parent.Call == in.Call {
			replacement, err = m.directStatement("defer", parent)
			start, end = parent.Pos(), parent.End()
		}
	case *ast.GoStmt:
		if parent.Call == in.Call {
			replacement, err = m.directStatement("go", parent)
			start, end = parent.Pos(), parent.End()
		}
	}
	if replacement == "" && err == nil {
		replacement, err = m.expression()
	}
	if err != nil {
		return nil, []Conflict{callConflict(in, in.Call, "call-migration", err.Error())}
	}
	return []ByteEdit{{Start: callOffset(in, start), End: callOffset(in, end), NewText: replacement}}, nil
}

// RewriteValue retains the old function type for callbacks, assignments and
// returned values. A function containing recover cannot be put behind an
// adapter when a future deferred use is unknown; its typed flow must instead be
// propagated by the owning planner.
func RewriteValue(in ValueRewriteInput, change Change) ([]ByteEdit, []Conflict) {
	if in.Expr == nil || in.Fset == nil || in.File == nil || in.Info == nil {
		return nil, []Conflict{callConflict(in.CallRewriteInput, nil, "value-context", "The typed function value snapshot is incomplete")}
	}
	if in.Context != nil && in.Context.Err() != nil {
		return nil, []Conflict{callConflict(in.CallRewriteInput, in.Expr, "cancelled", in.Context.Err().Error())}
	}
	if in.TargetMayRecover {
		return nil, []Conflict{callConflict(in.CallRewriteInput, in.Expr, "deferred-function-value", "This function calls recover. Its function-value uses must propagate the new type so a future defer still invokes it directly")}
	}
	call := *in.ExprCall()
	base := in.CallRewriteInput
	base.Call = &call
	base.Parent = nil
	m, err := prepareCallModel(base, change)
	if err != nil {
		return nil, []Conflict{callConflict(base, in.Expr, "value-migration", err.Error())}
	}
	body, err := m.invocationBody(false)
	if err != nil {
		return nil, []Conflict{callConflict(base, in.Expr, "value-migration", err.Error())}
	}
	oldFunction := "func(" + strings.Join(m.oldDeclarations, ", ") + ")" + resultTypeList(m.oldResultTypes)
	text := "func(" + m.callee + " " + m.newFunctionType + ") " + oldFunction + " { return " + oldFunction + " {\n" + body + "\n} }(" + m.calleeText + ")"
	if _, err = parser.ParseExpr(text); err != nil {
		return nil, []Conflict{callConflict(base, in.Expr, "value-generation", "Cannot represent this function value: "+err.Error())}
	}
	return []ByteEdit{{Start: callOffset(base, in.Expr.Pos()), End: callOffset(base, in.Expr.End()), NewText: text}}, nil
}

func (in ValueRewriteInput) ExprCall() *ast.CallExpr {
	return &ast.CallExpr{Fun: in.Expr, Lparen: in.Expr.End(), Rparen: in.Expr.End()}
}

type callModel struct {
	in                                                  CallRewriteInput
	change                                              Change
	sig                                                 *types.Signature
	prefix, callee, calleeText, newFunctionType         string
	oldNames, oldTypes, oldDeclarations, oldResultTypes []string
	newTypes, newResultTypes                            []string
	bindings                                            map[string]string
	receiverPrefix                                      int
	ignoreResults                                       bool
}

func prepareCallModel(in CallRewriteInput, change Change) (*callModel, error) {
	in.Parent = callTransparentParent(in)
	clonedSubstitutions := make(map[string]string, len(in.GenericSubstitutions))
	for name, replacement := range in.GenericSubstitutions {
		clonedSubstitutions[name] = replacement
	}
	in.GenericSubstitutions = clonedSubstitutions
	m := &callModel{in: in, change: change, bindings: map[string]string{}}
	m.sig = in.Signature
	if m.sig == nil {
		m.sig, _ = in.Info.TypeOf(in.Call.Fun).(*types.Signature)
	}
	if m.sig == nil {
		return nil, fmt.Errorf("The selected call has no resolved function signature")
	}
	// Inferred generic functions need explicit arguments once passed as values.
	if ident := callBaseIdent(in.Call.Fun); ident != nil {
		if inst, ok := in.Info.Instances[ident]; ok {
			if sig, ok := inst.Type.(*types.Signature); ok {
				m.sig = sig
			}
			if in.GenericSubstitutions == nil {
				in.GenericSubstitutions = map[string]string{}
			}
			if origin, ok := in.Info.Uses[ident].(*types.Func); ok {
				if sig, ok := origin.Type().(*types.Signature); ok && sig.TypeParams() != nil {
					for n := 0; n < sig.TypeParams().Len() && n < inst.TypeArgs.Len(); n++ {
						t, err := callRenderType(in, inst.TypeArgs.At(n))
						if err != nil {
							return nil, err
						}
						in.GenericSubstitutions[sig.TypeParams().At(n).Obj().Name()] = t
					}
				}
			}
		}
	}
	m.in = in
	if selector := callSelector(in.Call.Fun); selector != nil {
		if selection := in.Info.Selections[selector]; selection != nil && selection.Kind() == types.MethodExpr {
			m.receiverPrefix = 1
		}
	}
	m.prefix = callFreshPrefix(in, change, callOffset(in, in.Call.Pos()))
	m.callee = m.prefix + "callee"
	for n := 0; n < m.sig.Params().Len(); n++ {
		t, err := callRenderType(in, m.sig.Params().At(n).Type())
		if err != nil {
			return nil, err
		}
		name := m.prefix + "arg" + strconv.Itoa(n)
		m.oldNames = append(m.oldNames, name)
		m.oldTypes = append(m.oldTypes, t)
		declarationType := t
		if m.sig.Variadic() && n == m.sig.Params().Len()-1 {
			slice, ok := m.sig.Params().At(n).Type().Underlying().(*types.Slice)
			if !ok {
				return nil, fmt.Errorf("The variadic parameter is not a slice")
			}
			element, err := callRenderType(in, slice.Elem())
			if err != nil {
				return nil, err
			}
			declarationType = "..." + element
		}
		m.oldDeclarations = append(m.oldDeclarations, name+" "+declarationType)
	}
	for n := 0; n < m.sig.Results().Len(); n++ {
		t, err := callRenderType(in, m.sig.Results().At(n).Type())
		if err != nil {
			return nil, err
		}
		m.oldResultTypes = append(m.oldResultTypes, t)
	}
	oldCount := m.sig.Params().Len() - m.receiverPrefix
	if len(in.OldParameters) != 0 && len(in.OldParameters) != oldCount {
		return nil, fmt.Errorf("The parameter rows do not match the instantiated call")
	}
	for n := 0; n < oldCount; n++ {
		name := m.sig.Params().At(n + m.receiverPrefix).Name()
		if len(in.OldParameters) > n {
			name = in.OldParameters[n].Name
		}
		if name != "" && name != "_" {
			m.bindings[name] = m.oldNames[n+m.receiverPrefix]
		}
	}
	seen := map[int]bool{}
	newParams := []string{}
	if m.receiverPrefix == 1 {
		newParams = append(newParams, m.oldTypes[0])
	}
	for n, row := range change.Parameters {
		if row.OldIndex != nil {
			if *row.OldIndex < 0 || *row.OldIndex >= oldCount || seen[*row.OldIndex] {
				return nil, fmt.Errorf("A parameter has an invalid or repeated old index")
			}
			seen[*row.OldIndex] = true
		}
		if row.Variadic && n != len(change.Parameters)-1 {
			return nil, fmt.Errorf("A variadic parameter must be last")
		}
		t := row.Type
		if n < len(in.NewParameterTypes) && in.NewParameterTypes[n] != "" {
			t = in.NewParameterTypes[n]
		}
		t, err := callSubstituteType(t, in.GenericSubstitutions)
		if err != nil {
			return nil, fmt.Errorf("Parameter %s: %w", row.Name, err)
		}
		t = strings.TrimPrefix(strings.TrimSpace(t), "...")
		if t == "" {
			return nil, fmt.Errorf("A new parameter type is empty")
		}
		m.newTypes = append(m.newTypes, t)
		if row.Variadic {
			t = "..." + t
		}
		newParams = append(newParams, t)
	}
	seen = map[int]bool{}
	for n, row := range change.Results {
		if row.OldIndex != nil {
			if *row.OldIndex < 0 || *row.OldIndex >= m.sig.Results().Len() || seen[*row.OldIndex] {
				return nil, fmt.Errorf("A result has an invalid or repeated old index")
			}
			seen[*row.OldIndex] = true
		}
		t := row.Type
		if n < len(in.NewResultTypes) && in.NewResultTypes[n] != "" {
			t = in.NewResultTypes[n]
		}
		t, err := callSubstituteType(t, in.GenericSubstitutions)
		if err != nil {
			return nil, err
		}
		m.newResultTypes = append(m.newResultTypes, t)
	}
	m.newFunctionType = "func(" + strings.Join(newParams, ", ") + ")" + resultTypeList(m.newResultTypes)
	m.calleeText = in.CalleeText
	if m.calleeText == "" {
		m.calleeText = callRenamedCallee(in, in.Call.Fun, change.Name)
	}
	if _, explicit := callStripParens(in.Call.Fun).(*ast.IndexExpr); !explicit {
		if _, explicit := callStripParens(in.Call.Fun).(*ast.IndexListExpr); !explicit {
			if ident := callBaseIdent(in.Call.Fun); ident != nil {
				if instance, ok := in.Info.Instances[ident]; ok && instance.TypeArgs.Len() > 0 {
					args := []string{}
					for n := 0; n < instance.TypeArgs.Len(); n++ {
						t, err := callRenderType(in, instance.TypeArgs.At(n))
						if err != nil {
							return nil, err
						}
						args = append(args, t)
					}
					m.calleeText += "[" + strings.Join(args, ", ") + "]"
				}
			}
		}
	}
	// Comments between the function expression and its opening parenthesis are
	// not part of either AST expression's span. Keep them with the captured callee.
	if len(in.Call.Args) > 0 || in.Call.Lparen < in.Call.Rparen {
		m.calleeText += callSource(in, in.Call.Fun.End(), in.Call.Lparen)
	}
	// Import-aware type rendering can introduce an alias absent from the original
	// file. Finalize the generated names only after those rendered types exist.
	scan := in
	scan.CalleeText = m.calleeText
	scan.NewParameterTypes = append(append([]string(nil), in.NewParameterTypes...), m.oldTypes...)
	scan.NewParameterTypes = append(scan.NewParameterTypes, m.newTypes...)
	scan.NewResultTypes = append(append([]string(nil), in.NewResultTypes...), m.oldResultTypes...)
	scan.NewResultTypes = append(scan.NewResultTypes, m.newResultTypes...)
	if prefix := callFreshPrefix(scan, change, callOffset(in, in.Call.Pos())); prefix != m.prefix {
		oldPrefix := m.prefix
		for n, oldName := range m.oldNames {
			m.oldNames[n] = prefix + strings.TrimPrefix(oldName, oldPrefix)
			m.oldDeclarations[n] = m.oldNames[n] + strings.TrimPrefix(m.oldDeclarations[n], oldName)
		}
		for name, binding := range m.bindings {
			m.bindings[name] = prefix + strings.TrimPrefix(binding, oldPrefix)
		}
		m.prefix, m.callee = prefix, prefix+"callee"
	}
	_, m.ignoreResults = in.Parent.(*ast.ExprStmt)
	return m, nil
}

func (m *callModel) expression() (string, error) {
	body, err := m.invocationBody(m.ignoreResults)
	if err != nil {
		return "", err
	}
	results := m.oldResultTypes
	if m.ignoreResults {
		results = nil
	}
	parameters := append([]string{m.callee + " " + m.newFunctionType}, m.oldDeclarations...)
	args := m.originalArguments()
	// A multi-valued sole argument cannot follow a separately supplied callee.
	tuple := len(m.in.Call.Args) == 1 && !m.in.Call.Ellipsis.IsValid() && isCallTuple(m.in.Info.TypeOf(m.in.Call.Args[0]))
	var text string
	if tuple {
		inner := "func(" + strings.Join(m.oldDeclarations, ", ") + ")" + resultTypeList(results) + " {\n" + body + "\n}(" + args + ")"
		prefix := ""
		if len(results) > 0 {
			prefix = "return "
		}
		text = "func()" + resultTypeList(results) + " {\n" + m.callee + " := " + m.calleeText + "\n" + prefix + inner + "\n}()"
	} else {
		actual := m.calleeText
		if strings.TrimSpace(args) != "" {
			actual += ", " + args
		}
		text = "func(" + strings.Join(parameters, ", ") + ")" + resultTypeList(results) + " {\n" + body + "\n}(" + actual + ")"
	}
	if _, err = parser.ParseExpr(text); err != nil {
		return "", fmt.Errorf("Cannot represent this call: %w", err)
	}
	return text, nil
}

// Keep ordinary call sites ordinary when no evaluation barrier is needed.
// Constant values and plain identifiers cannot mutate another argument between
// reads. Selectors, receives, indexing, dereferences and arbitrary expressions
// deliberately retain the typed adapter because they can panic or have effects.
func (m *callModel) directIfProven() (string, bool) {
	ignoreResults := m.ignoreResults
	switch parent := m.in.Parent.(type) {
	case *ast.GoStmt:
		ignoreResults = parent.Call == m.in.Call
	case *ast.DeferStmt:
		ignoreResults = parent.Call == m.in.Call
	}
	if !ignoreResults {
		if len(m.change.RemovedResultUseMigrations) > 0 || len(m.in.RemovedResultExpressions) > 0 || len(m.change.Results) != len(m.oldResultTypes) {
			return "", false
		}
		for n, row := range m.change.Results {
			if row.OldIndex == nil || *row.OldIndex != n || m.newResultTypes[n] != m.oldResultTypes[n] {
				return "", false
			}
		}
	}
	oldCount := len(m.oldNames) - m.receiverPrefix
	identity := len(m.change.Parameters) == oldCount
	for n, row := range m.change.Parameters {
		if row.OldIndex == nil || row.DefaultArgument != nil {
			return "", false
		}
		old := *row.OldIndex + m.receiverPrefix
		oldVariadic := m.sig.Variadic() && old == len(m.oldNames)-1
		newType := m.newTypes[n]
		if row.Variadic {
			newType = "[]" + newType
		}
		if newType != m.oldTypes[old] || row.Variadic != oldVariadic {
			return "", false
		}
		if *row.OldIndex != n {
			identity = false
		}
	}
	if identity {
		return m.calleeText + "(" + m.originalArguments() + ")", true
	}
	text := m.originalArguments()
	if strings.Contains(text, "//") || strings.Contains(text, "/*") {
		return "", false
	}
	for _, arg := range m.in.Call.Args {
		if !callPureValue(m.in.Info, arg) {
			return "", false
		}
	}
	groups := make([][]string, len(m.oldNames))
	fixed := len(groups)
	if m.sig.Variadic() {
		fixed--
	}
	if len(m.in.Call.Args) < fixed {
		return "", false
	}
	for n, arg := range m.in.Call.Args {
		index := n
		if n >= fixed {
			index = fixed
		}
		if index >= len(groups) {
			return "", false
		}
		value := callNodeText(m.in, arg)
		if m.in.Call.Ellipsis.IsValid() && n == len(m.in.Call.Args)-1 {
			value += "..."
		}
		groups[index] = append(groups[index], value)
	}
	args := []string{}
	if m.receiverPrefix == 1 {
		args = append(args, groups[0]...)
	}
	for _, row := range m.change.Parameters {
		args = append(args, groups[*row.OldIndex+m.receiverPrefix]...)
	}
	return m.calleeText + "(" + strings.Join(args, ", ") + ")", true
}

func callPureValue(info *types.Info, expr ast.Expr) bool {
	if value, ok := info.Types[expr]; ok && value.Value != nil {
		return true
	}
	expr = callStripParens(expr)
	id, ok := expr.(*ast.Ident)
	if !ok {
		return false
	}
	if id.Name == "nil" && info.Uses[id] == types.Universe.Lookup("nil") {
		return true
	}
	switch info.Uses[id].(type) {
	case *types.Var, *types.Const, *types.Func:
		return true
	}
	return false
}

func (m *callModel) directStatement(kind string, statement ast.Node) (string, error) {
	lines := []string{m.callee + " := " + m.calleeText}
	if len(m.oldNames) > 0 {
		identity := "func(" + strings.Join(m.oldDeclarations, ", ") + ")" + resultTypeList(m.oldTypes) + " { return " + strings.Join(m.oldNames, ", ") + " }(" + m.originalArguments() + ")"
		lines = append(lines, strings.Join(m.oldNames, ", ")+" := "+identity)
		for _, name := range m.oldNames {
			lines = append(lines, "_ = "+name)
		}
	}
	defaults, args, err := m.arguments()
	if err != nil {
		return "", err
	}
	lines = append(lines, defaults...)
	lines = append(lines, kind+" "+m.callee+"("+strings.Join(args, ", ")+")")
	trivia := ""
	if statement != nil {
		start := callOffset(m.in, statement.Pos()) + len(kind)
		end := callOffset(m.in, m.in.Call.Fun.Pos())
		if start >= 0 && end >= start && end <= len(m.in.Source) {
			trivia = m.in.Source[start:end]
		}
	}
	text := "{\n" + trivia + "\n" + strings.Join(lines, "\n") + "\n}"
	if _, err = parser.ParseFile(token.NewFileSet(), "generated.go", "package p\nfunc _() "+text, parser.AllErrors); err != nil {
		return "", fmt.Errorf("Cannot preserve the direct %s call: %w", kind, err)
	}
	return text, nil
}

func (m *callModel) arguments() ([]string, []string, error) {
	declarations, args := []string{}, []string{}
	bindings := m.bindings
	for _, row := range m.change.Parameters {
		if row.DefaultArgument == nil || row.DefaultArgument.Kind == "zero" {
			continue
		}
		// New expressions may deliberately write through &a or an inner closure.
		// Keep their virtual declaration parameters separate from the original
		// arguments already captured for the final call.
		bindings = map[string]string{}
		for name, binding := range m.bindings {
			bindings[name] = binding
		}
		for n, oldName := range m.oldNames {
			used := false
			for _, binding := range m.bindings {
				if binding == oldName {
					used = true
					break
				}
			}
			if !used {
				continue
			}
			name := m.prefix + "defaultSource" + strconv.Itoa(n)
			declarations = append(declarations, "var "+name+" "+m.oldTypes[n]+" = "+oldName, "_ = "+name)
			for parameter, binding := range m.bindings {
				if binding == oldName {
					bindings[parameter] = name
				}
			}
		}
		break
	}
	if m.receiverPrefix == 1 {
		args = append(args, m.oldNames[0])
	}
	for n, row := range m.change.Parameters {
		if row.OldIndex != nil && row.DefaultArgument == nil {
			index := *row.OldIndex + m.receiverPrefix
			arg := m.oldNames[index]
			if row.Variadic && m.sig.Variadic() && index == len(m.oldNames)-1 {
				arg += "..."
			}
			args = append(args, arg)
			continue
		}
		if row.DefaultArgument == nil {
			return nil, nil, fmt.Errorf("New parameter %s requires an explicit default argument or zero-value choice", row.Name)
		}
		if row.DefaultArgument.Kind != "zero" && row.DefaultArgument.Kind != "expression" && row.DefaultArgument.Kind != "" {
			return nil, nil, fmt.Errorf("Default argument for %s has an unknown expression kind", row.Name)
		}
		expr := row.DefaultArgument.Text
		spread := false
		t := m.newTypes[n]
		zero := row.DefaultArgument.Kind == "zero"
		if zero {
			if row.Variadic {
				t = "[]" + t
				spread = true
			}
			expr = ""
		} else {
			if strings.HasSuffix(strings.TrimSpace(expr), "...") {
				spread = true
				expr = strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(expr), "..."))
				t = "[]" + t
			}
			var err error
			expr, err = m.rewriteExpression(expr, bindings)
			if err != nil {
				return nil, nil, fmt.Errorf("Default argument for %s: %w", row.Name, err)
			}
		}
		if strings.TrimSpace(expr) == "" && !zero {
			return nil, nil, fmt.Errorf("Default argument for %s is empty", row.Name)
		}
		name := m.prefix + "default" + strconv.Itoa(n)
		declaration := "var " + name + " " + t
		if !zero {
			declaration += " = " + expr
		}
		declarations = append(declarations, declaration)
		if spread {
			name += "..."
		}
		args = append(args, name)
	}
	return declarations, args, nil
}

func (m *callModel) invocationBody(ignoreResults bool) (string, error) {
	lines, args, err := m.arguments()
	if err != nil {
		return "", err
	}
	call := m.callee + "(" + strings.Join(args, ", ") + ")"
	if ignoreResults || len(m.oldResultTypes) == 0 {
		lines = append(lines, call)
		return strings.Join(lines, "\n"), nil
	}
	newNames := []string{}
	bindings := map[string]string{}
	for n, row := range m.change.Results {
		name := m.prefix + "result" + strconv.Itoa(n)
		newNames = append(newNames, name)
		if row.Name != "" && row.Name != "_" {
			bindings[row.Name] = name
		}
	}
	// Migration expressions use the original declaration's named results. Install
	// those bindings last, so a new spelling cannot accidentally capture one.
	for n, row := range m.change.Results {
		if row.OldIndex != nil && *row.OldIndex < len(m.in.OldResults) {
			old := m.in.OldResults[*row.OldIndex].Name
			if old != "" && old != "_" {
				bindings[old] = newNames[n]
			}
		}
	}
	if len(newNames) > 0 {
		lines = append(lines, strings.Join(newNames, ", ")+" := "+call)
		for _, name := range newNames {
			lines = append(lines, "_ = "+name)
		}
	} else {
		lines = append(lines, call)
	}
	if len(m.in.RemovedResultExpressions) > 0 || len(m.change.RemovedResultUseMigrations) > 0 {
		for n, oldName := range newNames {
			name := m.prefix + "migrationSource" + strconv.Itoa(n)
			lines = append(lines, "var "+name+" "+m.newResultTypes[n]+" = "+oldName, "_ = "+name)
			for result, binding := range bindings {
				if binding == oldName {
					bindings[result] = name
				}
			}
		}
	}
	returns := []string{}
	for old := range m.oldResultTypes {
		if m.resultDiscarded(old) {
			name := m.prefix + "discardedResult" + strconv.Itoa(old)
			lines = append(lines, "var "+name+" "+m.oldResultTypes[old])
			returns = append(returns, name)
			continue
		}
		found := ""
		for n, row := range m.change.Results {
			if row.OldIndex != nil && *row.OldIndex == old {
				found = newNames[n]
				break
			}
		}
		expr := m.in.RemovedResultExpressions[old]
		if expr == "" && old < len(m.in.OldResults) {
			for _, migration := range m.change.RemovedResultUseMigrations {
				if migration.RowID == m.in.OldResults[old].RowID {
					expr = migration.Expression
					break
				}
			}
		}
		if found == "" || expr != "" {
			if expr == "" {
				return "", fmt.Errorf("Removed result %d is consumed at this call; choose a replacement expression or propagate its removal", old+1)
			}
			expr, err = m.rewriteExpression(expr, bindings)
			if err != nil {
				return "", fmt.Errorf("Result-use migration %d: %w", old+1, err)
			}
			found = m.prefix + "removedResult" + strconv.Itoa(old)
			lines = append(lines, "var "+found+" "+m.oldResultTypes[old]+" = "+expr)
		}
		returns = append(returns, found)
	}
	lines = append(lines, "return "+strings.Join(returns, ", "))
	return strings.Join(lines, "\n"), nil
}

func (m *callModel) resultDiscarded(index int) bool {
	if m.in.DiscardedResults[index] {
		return true
	}
	switch parent := m.in.Parent.(type) {
	case *ast.AssignStmt:
		if len(parent.Rhs) == 1 && callStripParens(parent.Rhs[0]) == m.in.Call && index < len(parent.Lhs) {
			id, ok := parent.Lhs[index].(*ast.Ident)
			return ok && id.Name == "_"
		}
	case *ast.ValueSpec:
		if len(parent.Values) == 1 && callStripParens(parent.Values[0]) == m.in.Call && index < len(parent.Names) {
			return parent.Names[index].Name == "_"
		}
	}
	return false
}

func (m *callModel) rewriteExpression(text string, bindings map[string]string) (string, error) {
	if m.in.RewriteExpression != nil {
		return m.in.RewriteExpression(text, bindings)
	}
	return callBindExpression(text, bindings)
}

func (m *callModel) originalArguments() string {
	start, end := callOffset(m.in, m.in.Call.Lparen)+1, callOffset(m.in, m.in.Call.Rparen)
	if start < 0 || end < start || end > len(m.in.Source) {
		return ""
	}
	var b strings.Builder
	cursor := start
	for _, arg := range m.in.Call.Args {
		a, z := callOffset(m.in, arg.Pos()), callOffset(m.in, arg.End())
		if a < cursor || z > end {
			continue
		}
		b.WriteString(m.in.Source[cursor:a])
		b.WriteString(callNodeText(m.in, arg))
		cursor = z
	}
	b.WriteString(m.in.Source[cursor:end])
	return b.String()
}

func callBindExpression(text string, bindings map[string]string) (string, error) {
	if _, err := parser.ParseExpr(text); err != nil {
		return "", err
	}
	// ParseExpr does not perform lexical object resolution. A file wrapper lets
	// the public parser distinguish a lambda's own parameters and locals from the
	// original declaration's free parameters without type-checking user code.
	const prefix = "package signature\nvar _ = "
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "migration.go", prefix+text+"\n", parser.AllErrors)
	if err != nil {
		return "", err
	}
	expr := file.Decls[0].(*ast.GenDecl).Specs[0].(*ast.ValueSpec).Values[0]
	parents := map[ast.Node]ast.Node{}
	stack := []ast.Node{}
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
	edits := []ByteEdit{}
	ast.Inspect(expr, func(n ast.Node) bool {
		id, ok := n.(*ast.Ident)
		if !ok || id.Obj != nil {
			return true
		}
		replacement, ok := bindings[id.Name]
		if !ok {
			return true
		}
		if selector, ok := parents[id].(*ast.SelectorExpr); ok && selector.Sel == id {
			return true
		}
		if pair, ok := parents[id].(*ast.KeyValueExpr); ok && pair.Key == id {
			literal, ok := parents[pair].(*ast.CompositeLit)
			if !ok {
				return true
			}
			switch literal.Type.(type) {
			case *ast.MapType:
			case *ast.StructType:
				return true
			default:
				err = fmt.Errorf("%s is an ambiguous key in a named composite literal; bind its field/key in declaration scope", id.Name)
				return false
			}
		}
		file := fset.File(id.Pos())
		edits = append(edits, ByteEdit{Start: file.Offset(id.Pos()) - len(prefix), End: file.Offset(id.End()) - len(prefix), NewText: replacement})
		return true
	})
	if err != nil {
		return "", err
	}
	sort.Slice(edits, func(i, j int) bool { return edits[i].Start > edits[j].Start })
	for _, edit := range edits {
		text = text[:edit.Start] + edit.NewText + text[edit.End:]
	}
	return text, nil
}

func callSubstituteType(text string, substitutions map[string]string) (string, error) {
	if strings.HasPrefix(strings.TrimSpace(text), "...") {
		text = strings.TrimPrefix(strings.TrimSpace(text), "...")
	}
	return callBindExpression(text, substitutions)
}

func callRenderType(in CallRewriteInput, t types.Type) (string, error) {
	if in.TypeText != nil {
		return in.TypeText(t)
	}
	aliases := map[string]string{}
	for _, spec := range in.File.Imports {
		path, err := strconv.Unquote(spec.Path.Value)
		if err != nil {
			continue
		}
		name := ""
		if spec.Name != nil {
			name = spec.Name.Name
		} else if obj, ok := in.Info.Implicits[spec].(*types.PkgName); ok {
			name = obj.Name()
		}
		if name == "." {
			name = ""
		}
		if name != "_" {
			aliases[path] = name
		}
	}
	var missing string
	text := types.TypeString(t, func(pkg *types.Package) string {
		if in.Package != nil && pkg.Path() == in.Package.Path() {
			return ""
		}
		if name, ok := aliases[pkg.Path()]; ok {
			return name
		}
		missing = pkg.Path()
		return pkg.Name()
	})
	if missing != "" {
		return "", fmt.Errorf("Type requires import %s in this caller; resolve its alias before rewriting", missing)
	}
	return text, nil
}

func callRenamedCallee(in CallRewriteInput, expr ast.Expr, name string) string {
	if name == "" {
		return callNodeText(in, expr)
	}
	switch x := expr.(type) {
	case *ast.Ident:
		return name
	case *ast.SelectorExpr:
		return callNodeText(in, x.X) + callSource(in, x.X.End(), x.Sel.Pos()) + name
	case *ast.IndexExpr:
		return callRenamedCallee(in, x.X, name) + callSource(in, x.X.End(), x.End())
	case *ast.IndexListExpr:
		return callRenamedCallee(in, x.X, name) + callSource(in, x.X.End(), x.End())
	case *ast.ParenExpr:
		return callSource(in, x.Pos(), x.X.Pos()) + callRenamedCallee(in, x.X, name) + callSource(in, x.X.End(), x.End())
	default:
		return callNodeText(in, expr)
	}
}

func callStripParens(expr ast.Expr) ast.Expr {
	for {
		p, ok := expr.(*ast.ParenExpr)
		if !ok {
			return expr
		}
		expr = p.X
	}
}

func callTransparentParent(in CallRewriteInput) ast.Node {
	current, parent := ast.Node(in.Call), in.Parent
	for {
		parens, ok := parent.(*ast.ParenExpr)
		if !ok || parens.X != current {
			return parent
		}
		current, parent = parent, nil
		stack := []ast.Node{}
		ast.Inspect(in.File, func(node ast.Node) bool {
			if node == nil {
				stack = stack[:len(stack)-1]
				return false
			}
			if node == current {
				if len(stack) > 0 {
					parent = stack[len(stack)-1]
				}
				return false
			}
			stack = append(stack, node)
			return true
		})
	}
}
func callBaseIdent(expr ast.Expr) *ast.Ident {
	switch x := callStripParens(expr).(type) {
	case *ast.Ident:
		return x
	case *ast.SelectorExpr:
		return x.Sel
	case *ast.IndexExpr:
		return callBaseIdent(x.X)
	case *ast.IndexListExpr:
		return callBaseIdent(x.X)
	}
	return nil
}
func callSelector(expr ast.Expr) *ast.SelectorExpr {
	switch x := callStripParens(expr).(type) {
	case *ast.SelectorExpr:
		return x
	case *ast.IndexExpr:
		return callSelector(x.X)
	case *ast.IndexListExpr:
		return callSelector(x.X)
	}
	return nil
}
func isCallTuple(t types.Type) bool { _, ok := t.(*types.Tuple); return ok }
func resultTypeList(types []string) string {
	if len(types) == 0 {
		return ""
	}
	return " (" + strings.Join(types, ", ") + ")"
}
func callOffset(in CallRewriteInput, pos token.Pos) int {
	if in.Fset == nil || !pos.IsValid() {
		return -1
	}
	f := in.Fset.File(pos)
	if f == nil {
		return -1
	}
	return f.Offset(pos)
}
func callSource(in CallRewriteInput, start, end token.Pos) string {
	a, b := callOffset(in, start), callOffset(in, end)
	if a < 0 || b < a || b > len(in.Source) {
		return ""
	}
	return in.Source[a:b]
}
func callNodeText(in CallRewriteInput, n ast.Node) string {
	if in.NodeText != nil {
		return in.NodeText(n)
	}
	return callSource(in, n.Pos(), n.End())
}
func callFreshPrefix(in CallRewriteInput, change Change, offset int) string {
	prefix := "__openGolandSignature" + strconv.Itoa(offset) + "_"
	// New expressions have not been inserted into File yet. Their lambda/local
	// names also reserve identifiers, otherwise substituting an old parameter can
	// capture it beneath a newly introduced, identically spelled local binding.
	extra := []string{in.CalleeText}
	extra = append(extra, in.NewParameterTypes...)
	extra = append(extra, in.NewResultTypes...)
	for _, replacement := range in.GenericSubstitutions {
		extra = append(extra, replacement)
	}
	for _, expression := range in.RemovedResultExpressions {
		extra = append(extra, expression)
	}
	for _, row := range change.Parameters {
		extra = append(extra, row.Type)
		if row.DefaultArgument != nil {
			extra = append(extra, row.DefaultArgument.Text)
		}
	}
	for _, row := range change.Results {
		extra = append(extra, row.Type)
	}
	for _, migration := range change.RemovedResultUseMigrations {
		extra = append(extra, migration.Expression)
	}
	for {
		collision := false
		for _, text := range extra {
			if strings.Contains(text, prefix) {
				collision = true
				break
			}
		}
		ast.Inspect(in.File, func(n ast.Node) bool {
			if id, ok := n.(*ast.Ident); ok && strings.HasPrefix(id.Name, prefix) {
				collision = true
			}
			return true
		})
		if !collision {
			return prefix
		}
		prefix += "x"
	}
}
func callConflict(in CallRewriteInput, n ast.Node, code, message string) Conflict {
	a, b := 0, 0
	if n != nil {
		a, b = callOffset(in, n.Pos()), callOffset(in, n.End())
	}
	return Conflict{URI: in.URI, Range: Range{Start: callPosition(in.Source, a), End: callPosition(in.Source, b)}, Code: code, Message: message}
}
func callPosition(source string, offset int) Position {
	if offset < 0 {
		offset = 0
	}
	if offset > len(source) {
		offset = len(source)
	}
	p := Position{}
	for _, r := range source[:offset] {
		if r == '\n' {
			p.Line++
			p.Character = 0
		} else {
			p.Character += len(utf16.Encode([]rune{r}))
		}
	}
	return p
}
