package signature

import (
	"go/ast"
	"go/token"
	"go/types"
)

// Type-checking alone cannot detect a rename that rebinds a compilable call to
// an existing local, builtin or promoted member. Validate those bindings before
// generating the typed-object reference edits.
func (a *analysis) renameConflicts(family []*declaration, keys map[string]bool, newName string) []Conflict {
	var conflicts []Conflict
	globalPackages := map[string]bool{}
	for _, d := range family {
		signature := d.object.Type().(*types.Signature)
		if signature.Recv() == nil {
			if d.object.Name() == "init" || newName == "init" || (d.object.Pkg().Name() == "main" && (d.object.Name() == "main" || newName == "main")) {
				conflicts = append(conflicts, conflictAt(d.file, d.name, "implicit-entrypoint", "Renaming init or the main entry point changes implicit Go execution; it requires an explicit lifecycle migration"))
			}
			globalPackages[d.object.Pkg().Path()] = true
			continue
		}
		existing, _, _ := types.LookupFieldOrMethod(signature.Recv().Type(), true, d.object.Pkg(), newName)
		if existing != nil {
			fn, ok := existing.(*types.Func)
			if !ok || !keys[objectKey(a.fset, fn)] {
				conflicts = append(conflicts, conflictAt(d.file, d.name, "method-name-collision", "The new method name hides an existing or promoted member; choose an explicit compatibility migration"))
			}
		}
	}
	for _, fi := range a.files {
		for id, obj := range fi.pkg.TypesInfo.Uses {
			function, selected := obj.(*types.Func)
			selected = selected && keys[objectKey(a.fset, function)]
			selector, isSelector := fi.parents[id].(*ast.SelectorExpr)
			qualified := isSelector && selector.Sel == id
			if selected && !qualified {
				if function.Pkg() != nil && function.Pkg().Path() != fi.pkg.Types.Path() && !token.IsExported(newName) {
					conflicts = append(conflicts, conflictAt(fi, id, "rename-visibility", "The new unexported function is not visible at this dot-imported use"))
					continue
				}
				if scope := fi.pkg.Types.Scope().Innermost(id.Pos()); scope != nil {
					_, binding := scope.LookupParent(newName, id.Pos())
					if binding != nil && binding.Parent() != types.Universe {
						same := false
						if fn, ok := binding.(*types.Func); ok {
							same = keys[objectKey(a.fset, fn)]
						}
						if !same {
							conflicts = append(conflicts, conflictAt(fi, id, "rename-capture", "The renamed function would bind to a different local/import/package declaration at this use"))
						}
					}
				}
			}
			if !selected && !qualified && id.Name == newName && globalPackages[fi.pkg.Types.Path()] && obj.Parent() == types.Universe {
				conflicts = append(conflicts, conflictAt(fi, id, "rename-builtin-capture", "The new package function would capture this existing predeclared identifier use"))
			}
		}
	}
	return conflicts
}
