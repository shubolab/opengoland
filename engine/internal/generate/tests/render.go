package tests

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
	subjectPath string
	external    bool
	err         error
}

func newImports(file signature.SemanticFile, a *analysis) *imports {
	im := &imports{aliases: map[string]string{}, added: map[string]string{}, used: map[string]bool{}, subjectPath: a.packagePath, external: a.request.PackageMode == PackageExternal}
	for name := range a.names {
		im.used[name] = true
	}
	if file.AST != nil {
		for _, spec := range file.AST.Imports {
			p, e := strconv.Unquote(spec.Path.Value)
			if e != nil {
				continue
			}
			name := path.Base(p)
			if spec.Name != nil {
				name = spec.Name.Name
			} else if file.Package != nil {
				if obj, ok := file.Package.TypesInfo.Implicits[spec].(*types.PkgName); ok {
					name = obj.Imported().Name()
				}
			}
			im.aliases[p] = name
			im.used[name] = true
		}
	}
	return im
}
func (im *imports) local(base string) string {
	name := base
	for i := 2; im.used[name]; i++ {
		name = fmt.Sprintf("%s%d", base, i)
	}
	im.used[name] = true
	return name
}
func (im *imports) require(p, name string) string {
	if old, ok := im.aliases[p]; ok {
		if old == "_" || old == "." {
			im.err = fmt.Errorf("import %s uses %s; choose an explicit ordinary alias before generating tests", p, old)
		}
		return old
	}
	alias := name
	for i := 2; im.used[alias] || !token.IsIdentifier(alias); i++ {
		alias = fmt.Sprintf("%s%d", name, i)
	}
	im.aliases[p] = alias
	im.added[p] = alias
	im.used[alias] = true
	return alias
}
func (im *imports) qualifier(p *types.Package) string {
	if p.Path() == im.subjectPath && !im.external {
		return ""
	}
	return im.require(p.Path(), p.Name())
}
func (im *imports) render(t types.Type) (string, error) {
	if im.external && !accessible(t, map[types.Type]bool{}) {
		return "", fmt.Errorf("type %s cannot be named from an external test package; use same-package tests", typeText(t))
	}
	text := types.TypeString(t, im.qualifier)
	return text, im.err
}
func accessible(t types.Type, seen map[types.Type]bool) bool {
	if t == nil || seen[t] {
		return true
	}
	seen[t] = true
	switch t := t.(type) {
	case *types.TypeParam:
		return false
	case *types.Alias:
		if t.Obj().Pkg() != nil && !t.Obj().Exported() {
			return false
		}
		for i := 0; i < t.TypeArgs().Len(); i++ {
			if !accessible(t.TypeArgs().At(i), seen) {
				return false
			}
		}
		return true
	case *types.Named:
		if t.Obj().Pkg() != nil && !t.Obj().Exported() {
			return false
		}
		for i := 0; i < t.TypeArgs().Len(); i++ {
			if !accessible(t.TypeArgs().At(i), seen) {
				return false
			}
		}
		return true
	case *types.Pointer:
		return accessible(t.Elem(), seen)
	case *types.Slice:
		return accessible(t.Elem(), seen)
	case *types.Array:
		return accessible(t.Elem(), seen)
	case *types.Map:
		return accessible(t.Key(), seen) && accessible(t.Elem(), seen)
	case *types.Chan:
		return accessible(t.Elem(), seen)
	case *types.Tuple:
		for i := 0; i < t.Len(); i++ {
			if !accessible(t.At(i).Type(), seen) {
				return false
			}
		}
	case *types.Signature:
		return t.TypeParams().Len() == 0 && accessible(t.Params(), seen) && accessible(t.Results(), seen)
	case *types.Struct:
		for i := 0; i < t.NumFields(); i++ {
			if !t.Field(i).Exported() || !accessible(t.Field(i).Type(), seen) {
				return false
			}
		}
	case *types.Interface:
		for i := 0; i < t.NumMethods(); i++ {
			if !t.Method(i).Exported() || !accessible(t.Method(i).Type(), seen) {
				return false
			}
		}
	}
	return true
}
func hasTypeParameter(t types.Type, seen map[types.Type]bool) bool {
	if t == nil || seen[t] {
		return false
	}
	seen[t] = true
	switch t := t.(type) {
	case *types.TypeParam:
		return true
	case *types.Alias:
		for i := 0; i < t.TypeArgs().Len(); i++ {
			if hasTypeParameter(t.TypeArgs().At(i), seen) {
				return true
			}
		}
	case *types.Named:
		for i := 0; i < t.TypeArgs().Len(); i++ {
			if hasTypeParameter(t.TypeArgs().At(i), seen) {
				return true
			}
		}
	case *types.Pointer:
		return hasTypeParameter(t.Elem(), seen)
	case *types.Slice:
		return hasTypeParameter(t.Elem(), seen)
	case *types.Array:
		return hasTypeParameter(t.Elem(), seen)
	case *types.Map:
		return hasTypeParameter(t.Key(), seen) || hasTypeParameter(t.Elem(), seen)
	case *types.Chan:
		return hasTypeParameter(t.Elem(), seen)
	case *types.Tuple:
		for i := 0; i < t.Len(); i++ {
			if hasTypeParameter(t.At(i).Type(), seen) {
				return true
			}
		}
	case *types.Signature:
		return hasTypeParameter(t.Params(), seen) || hasTypeParameter(t.Results(), seen)
	case *types.Struct:
		for i := 0; i < t.NumFields(); i++ {
			if hasTypeParameter(t.Field(i).Type(), seen) {
				return true
			}
		}
	}
	return false
}

func concrete(a *analysis, d declaration, selection Selection) (*types.Signature, types.Type, []types.Type, error) {
	parameters := typeParameters(d.signature)
	if len(selection.TypeArguments) != parameters.Len() {
		return nil, nil, nil, fmt.Errorf("%s requires %d explicit concrete type arguments", d.symbol.Name, parameters.Len())
	}
	arguments := []types.Type{}
	for _, text := range selection.TypeArguments {
		tv, err := types.Eval(a.view.Fset, d.file.Package.Types, d.ast.Type.Pos(), text)
		if err != nil {
			return nil, nil, nil, fmt.Errorf("invalid concrete type %q: %w", text, err)
		}
		if !tv.IsType() || hasTypeParameter(tv.Type, map[types.Type]bool{}) {
			return nil, nil, nil, fmt.Errorf("%q must be a concrete Go type", text)
		}
		arguments = append(arguments, tv.Type)
	}
	if d.signature.Recv() == nil {
		if len(arguments) == 0 {
			return d.signature, nil, arguments, nil
		}
		instantiated, err := types.Instantiate(nil, d.signature, arguments, true)
		if err != nil {
			return nil, nil, nil, err
		}
		return instantiated.(*types.Signature), nil, arguments, nil
	}
	receiver := d.signature.Recv().Type()
	if len(arguments) > 0 {
		base := receiver
		pointer := false
		if p, ok := base.(*types.Pointer); ok {
			base = p.Elem()
			pointer = true
		}
		named, ok := base.(*types.Named)
		if !ok {
			return nil, nil, nil, fmt.Errorf("unsupported generic receiver")
		}
		instantiated, err := types.Instantiate(nil, named.Origin(), arguments, true)
		if err != nil {
			return nil, nil, nil, err
		}
		receiver = instantiated
		if pointer {
			receiver = types.NewPointer(receiver)
		}
	}
	method := types.NewMethodSet(receiver).Lookup(d.function.Pkg(), d.function.Name())
	if method == nil {
		return nil, nil, nil, fmt.Errorf("cannot resolve instantiated receiver method %s", d.function.Name())
	}
	return method.Type().(*types.Signature), receiver, arguments, nil
}

func renderTest(a *analysis, d declaration, selection Selection, name string, im *imports) (string, error) {
	if a.request.PackageMode == PackageExternal && !d.symbol.Exported {
		return "", fmt.Errorf("%s is not accessible from an external test package", d.symbol.Name)
	}
	sig, receiver, typeArguments, err := concrete(a, d, selection)
	if err != nil {
		return "", err
	}
	t, tt, cases, argsType := im.local("t"), im.local("tt"), im.local("tests"), im.local("args")
	testing := im.require("testing", "testing")
	var text strings.Builder
	fmt.Fprintf(&text, "func %s(%s *%s.T) {\n", name, t, testing)
	fields := []string{}
	names := map[string]bool{}
	if sig.Params().Len() > 0 {
		text.WriteString("type " + argsType + " struct {\n")
		for i := 0; i < sig.Params().Len(); i++ {
			p := sig.Params().At(i)
			field := p.Name()
			if field == "_" || field == "" {
				field = fmt.Sprintf("arg%d", i)
			}
			base := field
			for n := 2; names[field]; n++ {
				field = fmt.Sprintf("%s%d", base, n)
			}
			names[field] = true
			typeName, e := im.render(p.Type())
			if e != nil {
				return "", e
			}
			fmt.Fprintf(&text, "%s %s\n", field, typeName)
			fields = append(fields, field)
		}
		text.WriteString("}\n")
	}
	text.WriteString(cases + " := []struct {\nname string\n")
	if receiver != nil {
		r, e := im.render(receiver)
		if e != nil {
			return "", e
		}
		fmt.Fprintf(&text, "receiver %s\n", r)
	}
	if len(fields) > 0 {
		text.WriteString("args " + argsType + "\n")
	}
	errorResults := map[int]bool{}
	for i := 0; i < sig.Results().Len(); i++ {
		r := sig.Results().At(i).Type()
		if types.Identical(r, types.Universe.Lookup("error").Type()) {
			errorResults[i] = true
			fmt.Fprintf(&text, "wantErr%d bool\n", i)
		} else {
			rt, e := im.render(r)
			if e != nil {
				return "", e
			}
			fmt.Fprintf(&text, "want%d %s\n", i, rt)
		}
	}
	fmt.Fprintf(&text, "}{\n// TODO: Add test cases, including receiver and argument values.\n}\nif len(%s) == 0 { %s.Skip(\"TODO: Add test cases.\") }\nfor _, %s := range %s {\n%s.Run(%s.name, func(%s *", cases, t, tt, cases, t, tt, t)
	text.WriteString(testing + ".T) {\n")
	call := d.function.Name()
	if receiver != nil {
		call = tt + ".receiver." + call
	} else if a.request.PackageMode == PackageExternal {
		call = im.require(a.packagePath, a.packageName) + "." + call
	}
	if receiver == nil && len(typeArguments) > 0 {
		ts := []string{}
		for _, argument := range typeArguments {
			v, e := im.render(argument)
			if e != nil {
				return "", e
			}
			ts = append(ts, v)
		}
		call += "[" + strings.Join(ts, ", ") + "]"
	}
	values := []string{}
	for i, field := range fields {
		value := tt + ".args." + field
		if sig.Variadic() && i == len(fields)-1 {
			value += "..."
		}
		values = append(values, value)
	}
	got := []string{}
	for i := 0; i < sig.Results().Len(); i++ {
		got = append(got, im.local(fmt.Sprintf("got%d", i)))
	}
	if len(got) > 0 {
		text.WriteString(strings.Join(got, ", ") + " := ")
	}
	text.WriteString(call + "(" + strings.Join(values, ", ") + ")\n")
	for i, value := range got {
		if errorResults[i] {
			fmt.Fprintf(&text, "if (%s != nil) != %s.wantErr%d { %s.Errorf(\"%s() error = %%v, wantErr %%v\", %s, %s.wantErr%d) }\n", value, tt, i, t, d.function.Name(), value, tt, i)
		} else {
			reflection := im.require("reflect", "reflect")
			if _, function := sig.Results().At(i).Type().Underlying().(*types.Signature); function {
				// go test's printf analyzer rejects directly formatting a function
				// value. Functions only compare equal to nil in DeepEqual.
				fmt.Fprintf(&text, "if !%s.DeepEqual(%s, %s.want%d) { %s.Errorf(\"%s() function result %d differs from expected (only nil functions compare equal)\") }\n", reflection, value, tt, i, t, d.function.Name(), i)
			} else {
				fmt.Fprintf(&text, "if !%s.DeepEqual(%s, %s.want%d) { %s.Errorf(\"%s() = %%#v, want %%#v\", %s, %s.want%d) }\n", reflection, value, tt, i, t, d.function.Name(), value, tt, i)
			}
		}
	}
	text.WriteString("})\n}\n}\n")
	return text.String(), im.err
}

func renderEmpty(name string, im *imports) string {
	t := im.local("t")
	return fmt.Sprintf("func %s(%s *%s.T) {\n %s.Skip(\"TODO: Implement test.\")\n}\n", name, t, im.require("testing", "testing"), t)
}
