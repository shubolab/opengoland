package tests

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/types"
	"path/filepath"
	"sort"
	"strings"
	"unicode"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

type declaration struct {
	file      signature.SemanticFile
	ast       *ast.FuncDecl
	function  *types.Func
	signature *types.Signature
	symbol    Symbol
}
type analysis struct {
	snapshot                            *signature.SemanticSnapshot
	view                                signature.SemanticAnalysis
	request                             Request
	target                              signature.SemanticFile
	directory, packageName, packagePath string
	declarations                        []declaration
	names                               map[string]bool
	inspection                          InspectResponse
}

func digest(value any) string {
	data, _ := json.Marshal(value)
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}
func typeText(t types.Type) string {
	return types.TypeString(t, func(p *types.Package) string { return p.Name() })
}
func upperFirst(name string) string {
	r := []rune(name)
	if len(r) > 0 {
		r[0] = unicode.ToUpper(r[0])
	}
	return string(r)
}
func defaultTestName(fn *types.Func, sig *types.Signature) string {
	name := upperFirst(fn.Name())
	if sig.Recv() != nil {
		t := sig.Recv().Type()
		if pointer, ok := t.(*types.Pointer); ok {
			t = pointer.Elem()
		}
		if named, ok := t.(*types.Named); ok {
			name = upperFirst(named.Obj().Name()) + "_" + name
		}
	}
	if name == "Main" {
		name = "MainFunction"
	}
	return "Test" + name
}
func typeParameters(sig *types.Signature) *types.TypeParamList {
	if sig.Recv() != nil && sig.RecvTypeParams().Len() > 0 {
		return sig.RecvTypeParams()
	}
	return sig.TypeParams()
}
func parameters(tuple *types.Tuple, variadic bool) []Parameter {
	out := []Parameter{}
	for i := 0; i < tuple.Len(); i++ {
		p := tuple.At(i)
		out = append(out, Parameter{Name: p.Name(), Type: typeText(p.Type()), Variadic: variadic && i == tuple.Len()-1})
	}
	return out
}

func Inspect(ctx context.Context, request Request) (InspectResponse, error) {
	a, err := open(ctx, request)
	if err != nil {
		return InspectResponse{}, err
	}
	defer a.snapshot.Close()
	if err = a.snapshot.Unchanged(); err != nil {
		return InspectResponse{}, err
	}
	return a.inspection, nil
}

func open(ctx context.Context, request Request) (*analysis, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if request.Scope == "" {
		request.Scope = ScopeFunction
	}
	if request.PackageMode == "" {
		request.PackageMode = PackageSame
	}
	if request.Scope != ScopeEmpty && request.Scope != ScopeFunction && request.Scope != ScopeFile && request.Scope != ScopePackage {
		return nil, fmt.Errorf("unknown test generation scope %q", request.Scope)
	}
	if request.PackageMode != PackageSame && request.PackageMode != PackageExternal {
		return nil, fmt.Errorf("unknown test package mode %q", request.PackageMode)
	}
	s, err := signature.OpenSemanticSnapshot(request.Request)
	if err != nil {
		return nil, err
	}
	fail := true
	defer func() {
		if fail {
			s.Close()
		}
	}()
	view, err := s.Load(ctx, nil)
	if err != nil {
		return nil, err
	}
	path, err := signature.LocalPath(request.Target.URI)
	if err != nil {
		return nil, err
	}
	target, ok := view.Files[path]
	if !ok {
		return nil, fmt.Errorf("the target is not in the active editable Go build context")
	}
	a := &analysis{snapshot: s, view: view, request: request, target: target, directory: filepath.Dir(path), names: map[string]bool{}}
	subject := target
	paths := make([]string, 0, len(view.Files))
	for p := range view.Files {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	// An external _test.go editor still generates for the corresponding subject.
	if strings.HasSuffix(subject.AST.Name.Name, "_test") {
		for _, p := range paths {
			f := view.Files[p]
			if filepath.Dir(p) == a.directory && !strings.HasSuffix(p, "_test.go") {
				subject = f
				break
			}
		}
	}
	a.packageName, a.packagePath = subject.AST.Name.Name, subject.Package.Types.Path()
	testPackageName := a.packageName
	if request.PackageMode == PackageExternal {
		testPackageName += "_test"
	}
	for _, p := range paths {
		f := view.Files[p]
		if filepath.Dir(p) != a.directory {
			continue
		}
		if f.AST.Name.Name == testPackageName {
			for _, name := range f.Package.Types.Scope().Names() {
				a.names[name] = true
			}
		}
		if strings.HasSuffix(p, "_test.go") || f.AST.Name.Name != a.packageName {
			continue
		}
		for _, node := range f.AST.Decls {
			fd, ok := node.(*ast.FuncDecl)
			if !ok || fd.Name.Name == "init" || fd.Name.Name == "_" {
				continue
			}
			fn, ok := f.Package.TypesInfo.Defs[fd.Name].(*types.Func)
			if !ok {
				continue
			}
			sig, ok := fn.Type().(*types.Signature)
			if !ok {
				continue
			}
			start, end := view.Fset.PositionFor(fd.Pos(), false).Offset, view.Fset.PositionFor(fd.End(), false).Offset
			if request.Scope == ScopeFile && p != path {
				continue
			}
			if request.Scope == ScopeFunction {
				offset, e := signature.UTF16Offset(target.Text, request.Target.Position)
				if e != nil {
					return nil, e
				}
				if p != path || offset < start || offset > end {
					continue
				}
			}
			if request.Scope == ScopeEmpty {
				continue
			}
			symbol := Symbol{Name: fn.Name(), Kind: "function", URI: f.URI, Range: signature.UTF16Range(f.Text, start, end), Parameters: parameters(sig.Params(), sig.Variadic()), Results: parameters(sig.Results(), false), TypeParameters: []TypeParameter{}, SuggestedTestName: defaultTestName(fn, sig), DestinationURI: signature.LocalURI(strings.TrimSuffix(p, ".go") + "_test.go"), Exported: fn.Exported()}
			if sig.Recv() != nil {
				symbol.Kind = "method"
				symbol.Receiver = typeText(sig.Recv().Type())
				r := sig.Recv().Type()
				if pointer, ok := r.(*types.Pointer); ok {
					r = pointer.Elem()
				}
				if n, ok := r.(*types.Named); ok {
					symbol.Exported = symbol.Exported && n.Obj().Exported()
				}
			}
			for i := 0; i < typeParameters(sig).Len(); i++ {
				t := typeParameters(sig).At(i)
				symbol.TypeParameters = append(symbol.TypeParameters, TypeParameter{Name: t.Obj().Name(), Constraint: typeText(t.Constraint())})
			}
			symbol.NeedsTypeArguments = len(symbol.TypeParameters) > 0
			symbol.SymbolID = digest([]any{f.URI, start, types.TypeString(sig, func(p *types.Package) string { return p.Path() }), f.Text})
			a.declarations = append(a.declarations, declaration{f, fd, fn, sig, symbol})
		}
	}
	response := InspectResponse{ConfigurationDigest: s.Digest(), Scope: request.Scope, PackageMode: request.PackageMode, PackageName: a.packageName, PackagePath: a.packagePath, DefaultDestinationURI: signature.LocalURI(strings.TrimSuffix(path, ".go") + "_test.go"), Symbols: []Symbol{}, Coverage: view.Coverage, Conflicts: []signature.Conflict{}}
	if strings.HasSuffix(path, "_test.go") {
		response.DefaultDestinationURI = target.URI
	}
	for i := range a.declarations {
		a.declarations[i].symbol.ExistingTest = a.names[a.declarations[i].symbol.SuggestedTestName]
		response.Symbols = append(response.Symbols, a.declarations[i].symbol)
	}
	response.InspectionID = digest([]any{response.ConfigurationDigest, request.Scope, request.PackageMode, target.URI, target.Text, response.Symbols})
	a.inspection = response
	fail = false
	return a, nil
}
