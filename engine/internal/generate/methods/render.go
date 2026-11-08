package methods

import (
	"fmt"
	"go/token"
	"go/types"
	"path"
	"strconv"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

type imports struct {
	aliases     map[string]string
	added       map[string]string
	used        map[string]bool
	packagePath string
	err         error
}

func newImports(file signature.SemanticFile, a *analysis) *imports {
	im := &imports{aliases: map[string]string{}, added: map[string]string{}, used: map[string]bool{"panic": true}, packagePath: file.Package.Types.Path()}
	for _, name := range file.Package.Types.Scope().Names() {
		im.used[name] = true
	}
	if a.receiver != nil {
		for i := 0; i < a.receiver.named.TypeParams().Len(); i++ {
			im.used[a.receiver.named.TypeParams().At(i).Obj().Name()] = true
		}
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
func localName(base string, used map[string]bool) string {
	if base == "" || base == "_" || !token.IsIdentifier(base) {
		base = "arg"
	}
	name := base
	for i := 2; used[name]; i++ {
		name = fmt.Sprintf("%s%d", base, i)
	}
	used[name] = true
	return name
}
func renderMethod(a *analysis, method requiredMethod, receiverName string, im *imports) (string, error) {
	name := a.newName
	typeParameters := []string{}
	if a.receiver != nil {
		name = a.receiver.wire.Name
		for i := 0; i < a.receiver.named.TypeParams().Len(); i++ {
			typeParameters = append(typeParameters, a.receiver.named.TypeParams().At(i).Obj().Name())
		}
	}
	if len(typeParameters) > 0 {
		name += "[" + strings.Join(typeParameters, ", ") + "]"
	}
	if a.request.ReceiverKind == "pointer" {
		name = "*" + name
	}
	used := map[string]bool{"panic": true, receiverName: true}
	for _, p := range typeParameters {
		used[p] = true
	}
	params := []string{}
	results := []string{}
	for i := 0; i < method.signature.Params().Len(); i++ {
		p := method.signature.Params().At(i)
		typ := p.Type()
		variadic := method.signature.Variadic() && i == method.signature.Params().Len()-1
		if variadic {
			typ = typ.(*types.Slice).Elem()
		}
		text, e := im.render(typ)
		if e != nil {
			return "", e
		}
		if variadic {
			text = "..." + text
		}
		base := p.Name()
		if base == "" || base == "_" {
			base = fmt.Sprintf("arg%d", i)
		}
		params = append(params, localName(base, used)+" "+text)
	}
	for i := 0; i < method.signature.Results().Len(); i++ {
		p := method.signature.Results().At(i)
		text, e := im.render(p.Type())
		if e != nil {
			return "", e
		}
		if p.Name() != "" && p.Name() != "_" {
			text = localName(p.Name(), used) + " " + text
		}
		results = append(results, text)
	}
	// Go does not permit a mixture of named and unnamed results.
	if len(results) > 1 {
		named := false
		unnamed := false
		for i := 0; i < method.signature.Results().Len(); i++ {
			p := method.signature.Results().At(i)
			if p.Name() != "" && p.Name() != "_" {
				named = true
			} else {
				unnamed = true
			}
		}
		if named && unnamed {
			for i := range results {
				if method.signature.Results().At(i).Name() == "" || method.signature.Results().At(i).Name() == "_" {
					results[i] = localName(fmt.Sprintf("result%d", i), used) + " " + results[i]
				}
			}
		}
	}
	suffix := ""
	if len(results) > 0 {
		suffix = " (" + strings.Join(results, ", ") + ")"
	}
	return fmt.Sprintf("// %s implements %s.\nfunc (%s %s) %s(%s)%s {\n\tpanic(%s)\n}\n", method.wire.Name, a.selected.wire.Display, receiverName, name, method.wire.Name, strings.Join(params, ", "), suffix, strconv.Quote("TODO: implement "+method.wire.Name)), im.err
}
