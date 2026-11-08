package structs

import (
	"fmt"
	"go/token"
	"go/types"
	"path"
	"strconv"
)

type imports struct {
	aliases     map[string]string
	added       map[string]string
	used        map[string]bool
	packagePath string
	err         error
}

func newImports(a *analysis) *imports {
	file := a.owner
	im := &imports{aliases: map[string]string{}, added: map[string]string{}, used: map[string]bool{}, packagePath: file.Package.Types.Path()}
	for _, name := range file.Package.Types.Scope().Names() {
		im.used[name] = true
	}
	for i := 0; i < a.named.TypeParams().Len(); i++ {
		im.used[a.named.TypeParams().At(i).Obj().Name()] = true
	}
	for _, spec := range file.AST.Imports {
		p, e := strconv.Unquote(spec.Path.Value)
		if e != nil {
			continue
		}
		name := path.Base(p)
		if spec.Name != nil {
			name = spec.Name.Name
		} else if obj, ok := file.Package.TypesInfo.Implicits[spec].(*types.PkgName); ok {
			name = obj.Imported().Name()
		}
		im.aliases[p] = name
		im.used[name] = true
	}
	return im
}
func (im *imports) qualifier(pkg *types.Package) string {
	if pkg.Path() == im.packagePath {
		return ""
	}
	if alias, ok := im.aliases[pkg.Path()]; ok {
		if alias == "_" || alias == "." {
			im.err = fmt.Errorf("import %s needs an ordinary alias before generating a method signature", pkg.Path())
		}
		return alias
	}
	base := pkg.Name()
	alias := base
	for i := 2; im.used[alias] || !token.IsIdentifier(alias); i++ {
		alias = fmt.Sprintf("%s%d", base, i)
	}
	im.used[alias] = true
	im.aliases[pkg.Path()] = alias
	im.added[pkg.Path()] = alias
	return alias
}
func (im *imports) render(t types.Type) (string, error) {
	if e := accessible(t, im.packagePath, map[types.Type]bool{}); e != nil {
		return "", e
	}
	text := types.TypeString(t, im.qualifier)
	return text, im.err
}
func accessible(t types.Type, owner string, seen map[types.Type]bool) error {
	if t == nil || seen[t] {
		return nil
	}
	seen[t] = true
	check := func(obj *types.TypeName) error {
		if obj.Pkg() != nil && obj.Pkg().Path() != owner && !obj.Exported() {
			return fmt.Errorf("type %s is private to package %s and cannot appear in this method declaration", obj.Name(), obj.Pkg().Path())
		}
		return nil
	}
	switch v := t.(type) {
	case *types.Named:
		if e := check(v.Obj()); e != nil {
			return e
		}
		for i := 0; i < v.TypeArgs().Len(); i++ {
			if e := accessible(v.TypeArgs().At(i), owner, seen); e != nil {
				return e
			}
		}
	case *types.Alias:
		if e := check(v.Obj()); e != nil {
			return e
		}
		for i := 0; i < v.TypeArgs().Len(); i++ {
			if e := accessible(v.TypeArgs().At(i), owner, seen); e != nil {
				return e
			}
		}
	case *types.Pointer:
		return accessible(v.Elem(), owner, seen)
	case *types.Slice:
		return accessible(v.Elem(), owner, seen)
	case *types.Array:
		return accessible(v.Elem(), owner, seen)
	case *types.Chan:
		return accessible(v.Elem(), owner, seen)
	case *types.Map:
		if e := accessible(v.Key(), owner, seen); e != nil {
			return e
		}
		return accessible(v.Elem(), owner, seen)
	case *types.Tuple:
		for i := 0; i < v.Len(); i++ {
			if e := accessible(v.At(i).Type(), owner, seen); e != nil {
				return e
			}
		}
	case *types.Signature:
		if e := accessible(v.Params(), owner, seen); e != nil {
			return e
		}
		return accessible(v.Results(), owner, seen)
	case *types.Struct:
		for i := 0; i < v.NumFields(); i++ {
			f := v.Field(i)
			if f.Pkg() != nil && f.Pkg().Path() != owner && !f.Exported() {
				return fmt.Errorf("anonymous struct contains package-private field %s", f.Name())
			}
			if e := accessible(f.Type(), owner, seen); e != nil {
				return e
			}
		}
	case *types.Interface:
		for i := 0; i < v.NumMethods(); i++ {
			m := v.Method(i)
			if m.Pkg() != nil && m.Pkg().Path() != owner && !m.Exported() {
				return fmt.Errorf("anonymous interface contains package-private method %s", m.Name())
			}
			if e := accessible(m.Type(), owner, seen); e != nil {
				return e
			}
		}
	}
	return nil
}
