package signature

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"strings"
)

type declarationPlan struct {
	declaration                 *declaration
	parameterTypes, resultTypes []string
	inserted                    []string
	renamed                     map[types.Object]string
}

func conflictAt(fi *fileInfo, node ast.Node, code, message string) Conflict {
	start, end := 0, 0
	if node != nil {
		start, end = fi.offset(node.Pos()), fi.offset(node.End())
	}
	return Conflict{fi.source.URI, sourceRange(fi.source, start, end), code, message}
}
func validRows(change Change, d *declaration) error {
	if !token.IsIdentifier(change.Name) || change.Name == "_" || token.Lookup(change.Name).IsKeyword() {
		return fmt.Errorf("the function name must be a Go identifier")
	}
	for kind, list := range map[string][]Row{"parameter": change.Parameters, "result": change.Results} {
		old := d.parameters
		if kind == "result" {
			old = d.results
		}
		indices := map[int]bool{}
		ids := map[string]bool{}
		named, unnamed := false, false
		names := map[string]bool{}
		for i, row := range list {
			if kind == "parameter" && row.BodyDefault != nil {
				return fmt.Errorf("a parameter cannot have a result bodyDefault")
			}
			if kind == "result" && row.DefaultArgument != nil {
				return fmt.Errorf("a result cannot have a parameter defaultArgument")
			}
			expression := row.DefaultArgument
			if kind == "result" {
				expression = row.BodyDefault
			}
			if expression != nil {
				switch expression.Kind {
				case "zero":
					if strings.TrimSpace(expression.Text) != "" {
						return fmt.Errorf("a zero %s expression cannot also contain expression text", kind)
					}
				case "expression":
					if _, err := parser.ParseExpr(expression.Text); err != nil {
						return fmt.Errorf("invalid %s expression: %w", kind, err)
					}
				default:
					return fmt.Errorf("unknown %s expression kind %q", kind, expression.Kind)
				}
			}
			if row.RowID == "" || ids[row.RowID] {
				return fmt.Errorf("each %s needs a unique rowId", kind)
			}
			ids[row.RowID] = true
			if row.OldIndex != nil {
				n := *row.OldIndex
				if n < 0 || n >= len(old) || indices[n] {
					return fmt.Errorf("%s oldIndex must identify each original row at most once", kind)
				}
				indices[n] = true
				if row.RowID != old[n].row.RowID {
					return fmt.Errorf("%s row identity changed; reopen Change Signature", kind)
				}
			}
			if row.Type == "" {
				return fmt.Errorf("every %s requires a type", kind)
			}
			if _, e := parser.ParseExpr(row.Type); e != nil {
				return fmt.Errorf("invalid %s type %q: %w", kind, row.Type, e)
			}
			if row.Name == "" {
				unnamed = true
			} else {
				named = true
				if !token.IsIdentifier(row.Name) || token.Lookup(row.Name).IsKeyword() {
					return fmt.Errorf("invalid %s name %q", kind, row.Name)
				}
				if row.Name != "_" && names[row.Name] {
					return fmt.Errorf("duplicate %s name %s", kind, row.Name)
				}
				names[row.Name] = true
			}
			if row.Variadic && (kind != "parameter" || i != len(list)-1) {
				return fmt.Errorf("only the final parameter may be variadic")
			}
		}
		if named && unnamed {
			return fmt.Errorf("%ss must be either all named or all unnamed", kind)
		}
	}
	names := map[string]bool{}
	for _, row := range append(append([]Row{}, change.Parameters...), change.Results...) {
		if row.Name != "" && row.Name != "_" {
			if names[row.Name] {
				return fmt.Errorf("parameter/result name %s is duplicated", row.Name)
			}
			names[row.Name] = true
		}
	}
	for kind, migrations := range map[string][]Migration{"parameter": change.ParameterBodyMigrations, "result": change.RemovedResultUseMigrations} {
		old := d.parameters
		if kind == "result" {
			old = d.results
		}
		original := map[string]int{}
		for i, parameter := range old {
			original[parameter.row.RowID] = i
		}
		seen := map[string]bool{}
		for _, migration := range migrations {
			oldIndex, known := original[migration.RowID]
			if !known || seen[migration.RowID] {
				return fmt.Errorf("each %s migration must identify a distinct original rowId", kind)
			}
			seen[migration.RowID] = true
			if _, err := parser.ParseExpr(migration.Expression); err != nil {
				return fmt.Errorf("invalid %s migration expression: %w", kind, err)
			}
			if kind == "parameter" {
				for _, row := range change.Parameters {
					if row.OldIndex != nil && *row.OldIndex == oldIndex && row.Type == old[oldIndex].row.Type && row.Variadic == old[oldIndex].row.Variadic {
						return fmt.Errorf("a parameter body migration requires removing the parameter or changing its type")
					}
				}
			}
		}
	}
	return nil
}
func rowTypes(a *analysis, target, d *declaration, list []Row, old []parameter, targetOld []parameter, imports *importManager) ([]string, error) {
	result := make([]string, len(list))
	genericNames := map[string]string{}
	for i, parameter := range target.parameters {
		if i < len(d.parameters) {
			inferSubstitutions(parameter.typ, d.parameters[i].typ, genericNames, func(t types.Type) string { text, _ := imports.render(t, d.ft.Pos()); return text })
		}
	}
	for i, parameter := range target.results {
		if i < len(d.results) {
			inferSubstitutions(parameter.typ, d.results[i].typ, genericNames, func(t types.Type) string { text, _ := imports.render(t, d.ft.Pos()); return text })
		}
	}
	for i, row := range list {
		if row.OldIndex != nil && *row.OldIndex < len(old) && *row.OldIndex < len(targetOld) && row.Type == targetOld[*row.OldIndex].row.Type && row.Variadic == targetOld[*row.OldIndex].row.Variadic {
			result[i] = old[*row.OldIndex].row.Type
			continue
		}
		value, err := types.Eval(a.fset, target.file.pkg.Types, target.ft.End()-1, row.Type)
		if err != nil || !value.IsType() {
			return nil, fmt.Errorf("cannot resolve type %q in the declaration context: %v", row.Type, err)
		}
		text, err := imports.render(value.Type, d.ft.Pos())
		if err != nil {
			return nil, err
		}
		text, err = callSubstituteType(text, genericNames)
		if err != nil {
			return nil, err
		}
		result[i] = text
	}
	return result, nil
}
func signatureList(d *declaration, list []Row, typesText []string, old []parameter, fieldList *ast.FieldList) string {
	// Bind parameter-list comments to the closest original row. Comments inside a
	// type expression remain in that preserved type slice and are not duplicated.
	comments := map[string][]string{}
	orphan := []string{}
	hasComments := false
	if fieldList != nil {
		for _, group := range d.file.ast.Comments {
			if group.Pos() < fieldList.Opening || group.End() > fieldList.Closing {
				continue
			}
			insideType := false
			for _, p := range old {
				if group.Pos() >= p.field.Type.Pos() && group.End() <= p.field.Type.End() {
					insideType = true
					break
				}
			}
			if insideType {
				continue
			}
			hasComments = true
			nearest := ""
			distance := int(^uint(0) >> 1)
			for _, p := range old {
				at := p.field.Pos()
				if p.ident != nil {
					at = p.ident.Pos()
				}
				dist := int(at - group.End())
				if dist >= 0 && dist < distance {
					distance = dist
					nearest = p.row.RowID
				}
			}
			if nearest == "" && len(old) > 0 {
				nearest = old[len(old)-1].row.RowID
			}
			if nearest != "" {
				comments[nearest] = append(comments[nearest], d.file.text(group))
			} else {
				orphan = append(orphan, d.file.text(group))
			}
		}
	}
	pieces := []string{}
	for i, row := range list {
		t := typesText[i]
		if row.Variadic {
			t = "..." + t
		}
		piece := t
		if row.Name != "" {
			piece = row.Name + " " + piece
		}
		if hasComments {
			prefix := strings.Join(comments[row.RowID], "\n")
			if prefix != "" {
				piece = prefix + "\n" + piece
			}
			delete(comments, row.RowID)
		}
		pieces = append(pieces, piece)
	}
	if !hasComments {
		return "(" + strings.Join(pieces, ", ") + ")"
	}
	var b strings.Builder
	b.WriteString("(\n")
	for _, piece := range pieces {
		b.WriteString(piece)
		b.WriteString(",\n")
	}
	for _, p := range old {
		for _, comment := range comments[p.row.RowID] {
			b.WriteString(comment)
			b.WriteByte('\n')
		}
	}
	for _, comment := range orphan {
		b.WriteString(comment)
		b.WriteByte('\n')
	}
	b.WriteByte(')')
	return b.String()
}
func planDeclaration(a *analysis, target, d *declaration, change Change, e *edits, imports *importManager) (*declarationPlan, []Conflict) {
	dp := &declarationPlan{declaration: d, renamed: map[types.Object]string{}}
	var err error
	dp.parameterTypes, err = rowTypes(a, target, d, change.Parameters, d.parameters, target.parameters, imports)
	if err != nil {
		return nil, []Conflict{conflictAt(d.file, d.ft, "parameter-type", err.Error())}
	}
	dp.resultTypes, err = rowTypes(a, target, d, change.Results, d.results, target.results, imports)
	if err != nil {
		return nil, []Conflict{conflictAt(d.file, d.ft, "result-type", err.Error())}
	}
	paramText := signatureList(d, change.Parameters, dp.parameterTypes, d.parameters, d.ft.Params)
	resultText := ""
	if len(change.Results) > 0 {
		resultText = " " + signatureList(d, change.Results, dp.resultTypes, d.results, d.ft.Results)
	}
	if err = e.add(d.file.source.Path, ByteEdit{d.file.offset(d.ft.Params.Pos()), d.file.offset(d.ft.End()), paramText + resultText}); err != nil {
		return nil, []Conflict{conflictAt(d.file, d.ft, "overlap", err.Error())}
	}
	if change.Name != d.name.Name {
		_ = e.add(d.file.source.Path, ByteEdit{d.file.offset(d.name.Pos()), d.file.offset(d.name.End()), change.Name})
	}
	if d.body == nil {
		return dp, nil
	}
	migrations := map[string]string{}
	for _, m := range change.ParameterBodyMigrations {
		migrations[m.RowID] = m.Expression
	}
	usedNames := map[string]bool{}
	ast.Inspect(d.file.ast, func(n ast.Node) bool {
		if id, ok := n.(*ast.Ident); ok {
			usedNames[id.Name] = true
		}
		return true
	})
	for _, r := range append(append([]Row{}, change.Parameters...), change.Results...) {
		usedNames[r.Name] = true
	}
	reserved := d.file.source.Text
	for _, row := range append(append([]Row{}, change.Parameters...), change.Results...) {
		reserved += " " + row.Name
		if row.DefaultArgument != nil {
			reserved += " " + row.DefaultArgument.Text
		}
		if row.BodyDefault != nil {
			reserved += " " + row.BodyDefault.Text
		}
	}
	for _, migration := range change.ParameterBodyMigrations {
		reserved += " " + migration.Expression
	}
	fresh := func(base string) string {
		name := base
		for n := 2; usedNames[name] || strings.Contains(reserved, name); n++ {
			name = base + fmt.Sprint(n)
		}
		usedNames[name] = true
		return name
	}
	parameterMappings := map[int]int{}
	for n, row := range change.Parameters {
		if row.OldIndex != nil {
			parameterMappings[*row.OldIndex] = n
		}
	}
	activeOriginals := map[types.Object]bool{}
	for _, p := range append(append([]parameter{}, d.parameters...), d.results...) {
		if p.object != nil {
			activeOriginals[p.object] = true
		}
	}
	var conflicts []Conflict
	for _, row := range append(append([]Row{}, change.Parameters...), change.Results...) {
		if scope := d.file.pkg.TypesInfo.Scopes[d.ft]; scope != nil && row.Name != "" && row.Name != "_" {
			if existing := scope.Lookup(row.Name); existing != nil && !activeOriginals[existing] {
				for id, obj := range d.file.pkg.TypesInfo.Defs {
					if obj == existing {
						conflicts = append(conflicts, conflictAt(d.file, id, "local-binding-collision", fmt.Sprintf("The new binding %s collides with an existing function-scope declaration", row.Name)))
						break
					}
				}
			}
		}
		if row.Name == "" || row.Name == "_" {
			continue
		}
		for id, obj := range d.file.pkg.TypesInfo.Uses {
			if id.Name != row.Name || id.Pos() < d.body.Pos() || id.End() > d.body.End() || activeOriginals[obj] {
				continue
			}
			if obj.Parent() == types.Universe || obj.Parent() == d.file.pkg.Types.Scope() {
				conflicts = append(conflicts, conflictAt(d.file, id, "binding-capture", fmt.Sprintf("The new binding %s would capture this existing package/builtin reference", row.Name)))
			} else if _, ok := obj.(*types.PkgName); ok {
				conflicts = append(conflicts, conflictAt(d.file, id, "binding-capture", fmt.Sprintf("The new binding %s would shadow this import", row.Name)))
			}
		}
	}
	for i, old := range d.parameters {
		if old.object == nil {
			continue
		}
		n, retained := parameterMappings[i]
		migration := migrations[target.parameters[i].row.RowID]
		var uses []*ast.Ident
		for id, obj := range d.file.pkg.TypesInfo.Uses {
			if obj == old.object && id.Pos() >= d.body.Pos() && id.End() <= d.body.End() {
				uses = append(uses, id)
			}
		}
		if len(uses) == 0 {
			continue
		}
		desired := ""
		if retained {
			desired = change.Parameters[n].Name
		}
		changedType := retained && (change.Parameters[n].Type != target.parameters[i].row.Type || change.Parameters[n].Variadic != old.row.Variadic)
		if !retained || changedType {
			if strings.TrimSpace(migration) == "" {
				message := "Removed parameter is still used in this body; supply a body migration expression"
				if retained {
					message = "Parameter type changes require a body migration into its original type"
				}
				conflicts = append(conflicts, conflictAt(d.file, uses[0], "parameter-body-migration", message))
				continue
			}
			if _, err = parser.ParseExpr(migration); err != nil {
				conflicts = append(conflicts, conflictAt(d.file, uses[0], "parameter-body-migration", err.Error()))
				continue
			}
			desired = fresh("openGolandOriginal" + fmt.Sprint(i))
			oldType, er := imports.render(old.typ, d.body.Pos())
			if er != nil {
				conflicts = append(conflicts, conflictAt(d.file, uses[0], "parameter-body-type", er.Error()))
				continue
			}
			bindings := map[string]string{}
			for oldIndex, newIndex := range parameterMappings {
				if name := target.parameters[oldIndex].row.Name; name != "" {
					bindings[name] = change.Parameters[newIndex].Name
				}
			}
			for _, row := range change.Parameters {
				if row.Name != "" && row.Name != "_" {
					bindings[row.Name] = row.Name
				}
			}
			migrated, er := qualifiedExpression(a, target, d.file, imports, d.body.Pos(), migration, bindings, true)
			if er != nil {
				conflicts = append(conflicts, conflictAt(d.file, uses[0], "parameter-body-migration", er.Error()))
				continue
			}
			dp.inserted = append(dp.inserted, "var "+desired+" "+oldType+" = "+migrated)
		}
		if desired == "" || desired == "_" {
			conflicts = append(conflicts, conflictAt(d.file, uses[0], "parameter-name", "A parameter used in the body needs a non-blank name"))
			continue
		}
		for _, id := range uses {
			if desired == old.row.Name {
				continue
			}
			if scope := d.file.pkg.Types.Scope().Innermost(id.Pos()); scope != nil {
				_, shadow := scope.LookupParent(desired, id.Pos())
				if shadow != nil && !activeOriginals[shadow] {
					conflicts = append(conflicts, conflictAt(d.file, id, "parameter-shadowing", fmt.Sprintf("Renaming to %s would capture a different local declaration", desired)))
					continue
				}
			}
			_ = e.add(d.file.source.Path, ByteEdit{d.file.offset(id.Pos()), d.file.offset(id.End()), desired})
		}
		dp.renamed[old.object] = desired
	}
	resultMappings := map[int]int{}
	for n, row := range change.Results {
		if row.OldIndex != nil {
			resultMappings[*row.OldIndex] = n
		}
	}
	for i, old := range d.results {
		if old.object == nil || old.row.Name == "_" {
			continue
		}
		n, retained := resultMappings[i]
		desired := ""
		if retained {
			desired = change.Results[n].Name
		}
		changedType := retained && change.Results[n].Type != target.results[i].row.Type
		if changedType && change.Results[n].BodyDefault == nil {
			conflicts = append(conflicts, conflictAt(d.file, d.name, "result-body-migration", "Changing a result type requires an explicit body conversion/default expression"))
		}
		if retained && (changedType || desired == "" || desired == "_") && hasDefer(d.body) {
			conflicts = append(conflicts, conflictAt(d.file, d.name, "named-result-defer-migration", "Changing or removing this named result binding requires an explicit migration of deferred result writes"))
		}
		if !retained || desired == "" || desired == "_" || changedType {
			desired = fresh("openGolandOldResult" + fmt.Sprint(i))
			oldType, er := imports.render(old.typ, d.body.Pos())
			if er != nil {
				conflicts = append(conflicts, conflictAt(d.file, d.name, "result-body-type", er.Error()))
				continue
			}
			dp.inserted = append(dp.inserted, "var "+desired+" "+oldType, "_ = "+desired)
		}
		dp.renamed[old.object] = desired
		for id, obj := range d.file.pkg.TypesInfo.Uses {
			if obj == old.object && id.Pos() >= d.body.Pos() && id.End() <= d.body.End() {
				if desired != id.Name {
					if scope := d.file.pkg.Types.Scope().Innermost(id.Pos()); scope != nil {
						_, shadow := scope.LookupParent(desired, id.Pos())
						if shadow != nil && !activeOriginals[shadow] {
							conflicts = append(conflicts, conflictAt(d.file, id, "result-shadowing", fmt.Sprintf("Renaming to %s would capture a different local declaration", desired)))
							continue
						}
					}
					_ = e.add(d.file.source.Path, ByteEdit{d.file.offset(id.Pos()), d.file.offset(id.End()), desired})
				}
			}
		}
	}
	return dp, conflicts
}
func resultShapeChanged(old []parameter, next []Row) bool {
	if len(old) != len(next) {
		return true
	}
	for i, r := range next {
		if r.OldIndex == nil || *r.OldIndex != i || r.Type != old[i].row.Type || r.Name != old[i].row.Name || r.BodyDefault != nil {
			return true
		}
	}
	return false
}
func planReturns(a *analysis, target *declaration, dp *declarationPlan, change Change, e *edits, imports *importManager) []Conflict {
	d := dp.declaration
	if d.body == nil {
		return nil
	}
	var conflicts []Conflict
	if resultShapeChanged(target.results, change.Results) {
		var returns []*ast.ReturnStmt
		ast.Inspect(d.body, func(n ast.Node) bool {
			if _, ok := n.(*ast.FuncLit); ok {
				return false
			}
			if ret, ok := n.(*ast.ReturnStmt); ok {
				returns = append(returns, ret)
			}
			return true
		})
		fallthroughReturns := map[*ast.ReturnStmt]bool{}
		if len(d.results) == 0 && len(change.Results) > 0 {
			ret := &ast.ReturnStmt{Return: d.body.Rbrace}
			returns = append(returns, ret)
			fallthroughReturns[ret] = true
		}
		reserved := d.file.source.Text
		for _, row := range append(append([]Row{}, change.Parameters...), change.Results...) {
			reserved += " " + row.Name
			if row.BodyDefault != nil {
				reserved += " " + row.BodyDefault.Text
			}
			if row.DefaultArgument != nil {
				reserved += " " + row.DefaultArgument.Text
			}
		}
		for ordinal, ret := range returns {
			var errorNode ast.Node = ret
			if fallthroughReturns[ret] {
				errorNode = d.body
			}
			oldNames := make([]string, len(d.results))
			oldTypes := make([]string, len(d.results))
			bindings := map[string]string{}
			for i, old := range d.parameters {
				bound := ""
				if old.object != nil {
					bound = dp.renamed[old.object]
				}
				if bound == "" {
					for _, row := range change.Parameters {
						if row.OldIndex != nil && *row.OldIndex == i {
							bound = row.Name
							break
						}
					}
				}
				if bound != "" && bound != "_" && target.parameters[i].row.Name != "" {
					bindings[target.parameters[i].row.Name] = bound
				}
			}
			for _, row := range change.Parameters {
				if row.Name != "" && row.Name != "_" {
					bindings[row.Name] = row.Name
				}
			}
			for _, row := range change.Results {
				if row.Name != "" && row.Name != "_" {
					bindings[row.Name] = row.Name
				}
			}

			var b strings.Builder
			b.WriteString("{\n")
			for i, p := range d.results {
				name := fmt.Sprintf("openGolandReturn%d_%d", ordinal, i)
				for strings.Contains(reserved, name) {
					name += "_"
				}
				oldNames[i] = name
				t, err := imports.render(p.typ, ret.Pos())
				if err != nil {
					conflicts = append(conflicts, conflictAt(d.file, errorNode, "return-type", err.Error()))
					continue
				}
				oldTypes[i] = t
				fmt.Fprintf(&b, "var %s %s\n", name, t)
				fmt.Fprintf(&b, "_ = %s\n", name)
				if target.results[i].row.Name != "" && target.results[i].row.Name != "_" {
					bindings[target.results[i].row.Name] = name
				}
				bindings["oldResult"+fmt.Sprint(i)] = name
			}
			if len(ret.Results) > 0 {
				exprs := make([]string, len(ret.Results))
				for i, expr := range ret.Results {
					exprs[i] = e.text(d.file, expr)
				}
				if len(oldNames) == 0 {
					conflicts = append(conflicts, conflictAt(d.file, errorNode, "return-values", "Original return has unexpected values"))
					continue
				}
				fmt.Fprintf(&b, "%s = %s\n", strings.Join(oldNames, ", "), strings.Join(exprs, ", "))
			} else {
				for i, p := range d.results {
					name := p.row.Name
					if p.object != nil && dp.renamed[p.object] != "" {
						name = dp.renamed[p.object]
					}
					if name == "" || name == "_" {
						// The typed temporary already contains the old zero value.
					} else {
						fmt.Fprintf(&b, "%s = %s\n", oldNames[i], name)
					}
				}
			}
			// Explicit returns assign the original named result slots before defer.
			// Removed/retyped slots now live in generated old-typed locals.
			if len(ret.Results) > 0 {
				for i, p := range d.results {
					if p.object != nil {
						if bound := dp.renamed[p.object]; strings.HasPrefix(bound, "openGolandOldResult") {
							fmt.Fprintf(&b, "%s = %s\n", bound, oldNames[i])
						}
					}
				}
			}
			values := []string{}
			for i, row := range change.Results {
				value := ""
				if row.OldIndex != nil {
					value = oldNames[*row.OldIndex]
				}
				if row.BodyDefault != nil {
					switch row.BodyDefault.Kind {
					case "zero":
						zero := fmt.Sprintf("openGolandZero%d_%d", ordinal, i)
						for strings.Contains(reserved, zero) {
							zero += "_"
						}
						fmt.Fprintf(&b, "var %s %s\n", zero, dp.resultTypes[i])
						value = zero
					case "expression":
						v, er := qualifiedExpression(a, target, d.file, imports, ret.Pos(), row.BodyDefault.Text, bindings, true)
						if er != nil {
							conflicts = append(conflicts, conflictAt(d.file, errorNode, "return-default", er.Error()))
						} else {
							value = v
						}
					default:
						conflicts = append(conflicts, conflictAt(d.file, errorNode, "return-default", "Unknown result default kind"))
					}
				}
				if value == "" {
					zero := fmt.Sprintf("openGolandZero%d_%d", ordinal, i)
					for strings.Contains(reserved, zero) {
						zero += "_"
					}
					fmt.Fprintf(&b, "var %s %s\n", zero, dp.resultTypes[i])
					value = zero
				}
				values = append(values, value)
			}
			b.WriteString("return")
			if len(values) > 0 {
				b.WriteByte(' ')
				b.WriteString(strings.Join(values, ", "))
			}
			b.WriteString("\n}")
			start, end := d.file.offset(ret.Pos()), 0
			replacement := b.String()
			if fallthroughReturns[ret] {
				end = start
				replacement = "\n" + replacement + "\n"
			} else {
				end = d.file.offset(ret.End())
			}
			if err := e.add(d.file.source.Path, ByteEdit{start, end, replacement}); err != nil {
				conflicts = append(conflicts, conflictAt(d.file, errorNode, "overlap", err.Error()))
			}
		}
	}
	if len(dp.inserted) > 0 {
		at := d.file.offset(d.body.Lbrace) + 1
		if err := e.add(d.file.source.Path, ByteEdit{at, at, "\n" + strings.Join(dp.inserted, "\n") + "\n"}); err != nil {
			conflicts = append(conflicts, conflictAt(d.file, d.body, "overlap", err.Error()))
		}
	}
	return conflicts
}

func hasDefer(body *ast.BlockStmt) bool {
	found := false
	ast.Inspect(body, func(n ast.Node) bool {
		if n == nil {
			return false
		}
		if _, ok := n.(*ast.FuncLit); ok {
			return false
		}
		if _, ok := n.(*ast.DeferStmt); ok {
			found = true
		}
		return !found
	})
	return found
}
