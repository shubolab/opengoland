package native

import (
	"fmt"
	"go/token"
	"go/types"
	"sort"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

// safeTypeTextAt renders the original type identity in the receiving lexical
// scope. A compiling conversion is not enough: a caller-local Count (or even a
// shadowed builtin int) can silently change a value's dynamic type.
func (a *analysis) safeTypeTextAt(file signature.SemanticFile, pos token.Pos, want types.Type) (string, error) {
	if want == nil {
		return "", fmt.Errorf("the original Go type is unavailable")
	}
	primary := ""
	switch value := want.(type) {
	case *types.Named:
		primary = value.Obj().Name()
	case *types.Alias:
		primary = value.Obj().Name()
	case *types.Basic:
		primary = value.Name()
	}
	candidates := map[string]bool{}
	addScope := func(scope *types.Scope) {
		for _, name := range scope.Names() {
			if _, ok := scope.Lookup(name).(*types.TypeName); ok {
				candidates[name] = true
			}
		}
	}
	addScope(types.Universe)
	addScope(file.Package.Types.Scope())
	for _, scope := range file.Package.TypesInfo.Scopes {
		if scope.Contains(pos) {
			addScope(scope)
		}
	}
	names := make([]string, 0, len(candidates))
	for name := range candidates {
		names = append(names, name)
	}
	sort.Strings(names)
	if primary != "" {
		names = append([]string{primary}, names...)
	}
	for _, name := range names {
		if object, ok := lookupAt(file, pos, name).(*types.TypeName); ok && types.Identical(types.Unalias(object.Type()), types.Unalias(want)) {
			return name, nil
		}
	}
	switch value := types.Unalias(want).(type) {
	case *types.Pointer:
		element, err := a.safeTypeTextAt(file, pos, value.Elem())
		if err != nil {
			return "", err
		}
		return "*" + element, nil
	case *types.Named:
		object := value.Obj()
		base := ""
		if object.Pkg() != nil && object.Pkg().Path() != file.Package.Types.Path() {
			for _, spec := range file.AST.Imports {
				var imported *types.PkgName
				if spec.Name != nil {
					imported, _ = file.Package.TypesInfo.Defs[spec.Name].(*types.PkgName)
				} else {
					imported, _ = file.Package.TypesInfo.Implicits[spec].(*types.PkgName)
				}
				if imported != nil && imported.Imported().Path() == object.Pkg().Path() && imported.Name() != "_" && imported.Name() != "." && a.objectKey(lookupAt(file, pos, imported.Name())) == a.objectKey(imported) {
					base = imported.Name() + "." + object.Name()
					break
				}
			}
		} else if candidate, ok := lookupAt(file, pos, object.Name()).(*types.TypeName); ok && a.objectKey(candidate) == a.objectKey(object) {
			base = object.Name()
		}
		if base != "" {
			if value.TypeArgs().Len() == 0 {
				return base, nil
			}
			arguments := []string{}
			for index := 0; index < value.TypeArgs().Len(); index++ {
				text, err := a.safeTypeTextAt(file, pos, value.TypeArgs().At(index))
				if err != nil {
					return "", err
				}
				arguments = append(arguments, text)
			}
			return base + "[" + strings.Join(arguments, ", ") + "]", nil
		}
	}
	return "", fmt.Errorf("the original type %s has no spelling with the same binding at this inline use; source is unchanged", types.TypeString(want, nil))
}
