package signature

import (
	"go/types"
)

type methodRelations struct {
	edges     map[string]map[string]bool
	readonly  map[string]string
	uncertain map[string]bool
}

func (a *analysis) methodRelations(name string) *methodRelations {
	out := &methodRelations{edges: map[string]map[string]bool{}, readonly: map[string]string{}, uncertain: map[string]bool{}}
	type candidate struct {
		typ    types.Type
		method *types.Func
	}
	var receivers, interfaces []candidate
	for typ := range a.relationTypes {
		method := lookupMethod(typ, name)
		if method == nil {
			continue
		}
		entry := candidate{typ, method}
		receivers = append(receivers, entry)
		if _, ok := typ.Underlying().(*types.Interface); ok {
			interfaces = append(interfaces, entry)
		}
	}
	link := func(x, y string) {
		if out.edges[x] == nil {
			out.edges[x] = map[string]bool{}
		}
		if out.edges[y] == nil {
			out.edges[y] = map[string]bool{}
		}
		out.edges[x][y] = true
		out.edges[y][x] = true
	}
	for _, iface := range interfaces {
		under := iface.typ.Underlying().(*types.Interface).Complete()
		ik := objectKey(a.fset, iface.method)
		pos := a.fset.PositionFor(iface.method.Origin().Pos(), false)
		external := !a.snap.editable(a.snap.sourcePath(pos.Filename))
		for _, receiver := range receivers {
			if !implementsAcrossVariants(receiver.typ, under) {
				continue
			}
			rk := objectKey(a.fset, receiver.method)
			link(ik, rk)
			if external {
				contract := types.TypeString(iface.typ, func(p *types.Package) string { return p.Path() })
				if out.readonly[rk] == "" || contract < out.readonly[rk] {
					out.readonly[rk] = contract
				}
			}
		}
	}
	// Declaration-level symbolic instantiation also covers potential generic
	// implementations without any concrete instantiation in the current program.
	for _, left := range a.decls {
		if left.object.Name() != name {
			continue
		}
		for _, right := range a.decls {
			if left == right || right.object.Name() != name {
				continue
			}
			related, uncertain := symbolicMethodRelation(left.object, right.object)
			if related {
				link(left.key, right.key)
			} else if uncertain {
				out.uncertain[left.key] = true
				out.uncertain[right.key] = true
			}
		}
	}
	return out
}
func lookupMethod(typ types.Type, name string) *types.Func {
	set := types.NewMethodSet(typ)
	for i := 0; i < set.Len(); i++ {
		if method, ok := set.At(i).Obj().(*types.Func); ok && method.Name() == name {
			return method
		}
	}
	return nil
}
func relationType(t types.Type) bool {
	switch x := t.(type) {
	case *types.Named, *types.Alias:
		return true
	case *types.Pointer:
		_, ok := x.Elem().(*types.Named)
		return ok
	case *types.Interface:
		return x.NumMethods() > 0
	}
	return false
}
func (a *analysis) addRelationType(t types.Type) {
	if t == nil || !relationType(t) {
		return
	}
	a.relationTypes[t] = true
	if _, ok := t.(*types.Named); ok {
		a.relationTypes[types.NewPointer(t)] = true
	}
}
func namedReceiver(t types.Type) (*types.Named, bool) {
	pointer := false
	if p, ok := t.(*types.Pointer); ok {
		pointer = true
		t = p.Elem()
	}
	n, _ := t.(*types.Named)
	return n, pointer
}
func symbolicMethodRelation(left, right *types.Func) (bool, bool) {
	ls, _ := left.Type().(*types.Signature)
	rs, _ := right.Type().(*types.Signature)
	if ls == nil || rs == nil || ls.Recv() == nil || rs.Recv() == nil {
		return false, false
	}
	if _, ok := ls.Recv().Type().Underlying().(*types.Interface); ok {
		return symbolicInterfaceRelation(ls.Recv().Type(), rs.Recv().Type(), left.Name())
	}
	if _, ok := rs.Recv().Type().Underlying().(*types.Interface); ok {
		return symbolicInterfaceRelation(rs.Recv().Type(), ls.Recv().Type(), left.Name())
	}
	return false, false
}
func symbolicInterfaceRelation(ifaceType, receiverType types.Type, name string) (bool, bool) {
	iface := ifaceType.Underlying().(*types.Interface).Complete()
	in, _ := namedReceiver(ifaceType)
	rn, rptr := namedReceiver(receiverType)
	generic := in != nil && in.Origin().TypeParams().Len() > 0 || rn != nil && rn.Origin().TypeParams().Len() > 0
	if !generic {
		return false, false
	}
	forward, reverse := map[string]types.Type{}, map[string]types.Type{}
	for i := 0; i < iface.NumMethods(); i++ {
		wanted := iface.Method(i)
		got := lookupMethod(receiverType, wanted.Name())
		if got == nil {
			got = lookupMethod(types.NewPointer(receiverType), wanted.Name())
		}
		if got == nil {
			return false, false
		}
		ws, _ := wanted.Type().(*types.Signature)
		gs, _ := got.Type().(*types.Signature)
		if ws == nil || gs == nil || ws.Variadic() != gs.Variadic() || !bindTuple(ws.Params(), gs.Params(), forward) || !bindTuple(ws.Results(), gs.Results(), forward) {
			return false, false
		}
		_ = bindTuple(gs.Params(), ws.Params(), reverse)
		_ = bindTuple(gs.Results(), ws.Results(), reverse)
	}
	if in != nil && in.Origin().TypeParams().Len() > 0 {
		if args, ok := instantiationArgs(in.Origin(), forward); ok {
			if instantiated, err := types.Instantiate(nil, in.Origin(), args, true); err == nil {
				it := instantiated.Underlying().(*types.Interface).Complete()
				if implementsAcrossVariants(receiverType, it) || implementsAcrossVariants(types.NewPointer(receiverType), it) {
					return true, false
				}
			}
		}
	}
	if rn != nil && rn.Origin().TypeParams().Len() > 0 {
		if args, ok := instantiationArgs(rn.Origin(), reverse); ok {
			if instantiated, err := types.Instantiate(nil, rn.Origin(), args, true); err == nil {
				if rptr {
					instantiated = types.NewPointer(instantiated)
				}
				if implementsAcrossVariants(instantiated, iface) || implementsAcrossVariants(types.NewPointer(instantiated), iface) {
					return true, false
				}
			}
		}
	}
	// Simple disjoint basic terms are a proof of no relation. More elaborate
	// non-subset constraints need a deliberate migration choice, never silent edits.
	if in != nil && rn != nil && in.Origin().TypeParams().Len() == 1 && rn.Origin().TypeParams().Len() == 1 {
		a, ak := basicConstraintTerms(in.Origin().TypeParams().At(0).Constraint())
		b, bk := basicConstraintTerms(rn.Origin().TypeParams().At(0).Constraint())
		if ak && bk {
			overlap := false
			for key := range a {
				if b[key] {
					overlap = true
				}
			}
			if !overlap {
				return false, false
			}
		}
	}
	return false, true
}
func instantiationArgs(named *types.Named, bindings map[string]types.Type) ([]types.Type, bool) {
	params := named.TypeParams()
	args := make([]types.Type, params.Len())
	for i := range args {
		args[i] = bindings[params.At(i).Obj().Name()]
		if args[i] == nil {
			return nil, false
		}
	}
	return args, true
}
func bindTuple(pattern, actual *types.Tuple, bindings map[string]types.Type) bool {
	if pattern.Len() != actual.Len() {
		return false
	}
	for i := 0; i < pattern.Len(); i++ {
		if !bindType(pattern.At(i).Type(), actual.At(i).Type(), bindings) {
			return false
		}
	}
	return true
}
func bindType(pattern, actual types.Type, bindings map[string]types.Type) bool {
	pattern = types.Unalias(pattern)
	actual = types.Unalias(actual)
	if p, ok := pattern.(*types.TypeParam); ok {
		if old := bindings[p.Obj().Name()]; old != nil {
			return canonicalType(old) == canonicalType(actual)
		}
		bindings[p.Obj().Name()] = actual
		return true
	}
	switch p := pattern.(type) {
	case *types.Slice:
		a, ok := actual.(*types.Slice)
		return ok && bindType(p.Elem(), a.Elem(), bindings)
	case *types.Pointer:
		a, ok := actual.(*types.Pointer)
		return ok && bindType(p.Elem(), a.Elem(), bindings)
	case *types.Array:
		a, ok := actual.(*types.Array)
		return ok && p.Len() == a.Len() && bindType(p.Elem(), a.Elem(), bindings)
	case *types.Map:
		a, ok := actual.(*types.Map)
		return ok && bindType(p.Key(), a.Key(), bindings) && bindType(p.Elem(), a.Elem(), bindings)
	case *types.Chan:
		a, ok := actual.(*types.Chan)
		return ok && p.Dir() == a.Dir() && bindType(p.Elem(), a.Elem(), bindings)
	case *types.Named:
		a, ok := actual.(*types.Named)
		if !ok || p.Obj().Name() != a.Obj().Name() || p.Obj().Pkg().Path() != a.Obj().Pkg().Path() || p.TypeArgs().Len() != a.TypeArgs().Len() {
			return false
		}
		for i := 0; i < p.TypeArgs().Len(); i++ {
			if !bindType(p.TypeArgs().At(i), a.TypeArgs().At(i), bindings) {
				return false
			}
		}
		return true
	case *types.Signature:
		a, ok := actual.(*types.Signature)
		return ok && p.Variadic() == a.Variadic() && bindTuple(p.Params(), a.Params(), bindings) && bindTuple(p.Results(), a.Results(), bindings)
	}
	// A type parameter in the other direction is handled by the inverse proof.
	if _, ok := actual.(*types.TypeParam); ok {
		return true
	}
	return canonicalType(pattern) == canonicalType(actual)
}
func canonicalType(t types.Type) string {
	return types.TypeString(t, func(p *types.Package) string { return p.Path() })
}
func basicConstraintTerms(t types.Type) (map[types.BasicKind]bool, bool) {
	iface, ok := t.Underlying().(*types.Interface)
	if !ok {
		return nil, false
	}
	if iface.NumEmbeddeds() != 1 {
		return nil, false
	}
	embedded := iface.EmbeddedType(0)
	out := map[types.BasicKind]bool{}
	if union, ok := embedded.(*types.Union); ok {
		for i := 0; i < union.Len(); i++ {
			basic, ok := union.Term(i).Type().Underlying().(*types.Basic)
			if !ok {
				return nil, false
			}
			out[basic.Kind()] = true
		}
		return out, true
	}
	if basic, ok := embedded.Underlying().(*types.Basic); ok {
		out[basic.Kind()] = true
		return out, true
	}
	return nil, false
}

func packagePath(pkg *types.Package) string {
	if pkg == nil {
		return ""
	}
	return pkg.Path()
}
