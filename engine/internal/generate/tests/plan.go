package tests

import (
	"bytes"
	"context"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"unicode"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	"golang.org/x/tools/go/ast/astutil"
	"golang.org/x/tools/go/packages"
)

func validTestName(name string) bool {
	if !token.IsIdentifier(name) || !strings.HasPrefix(name, "Test") || name == "TestMain" {
		return false
	}
	r := []rune(strings.TrimPrefix(name, "Test"))
	return len(r) == 0 || !unicode.IsLower(r[0])
}
func (a *analysis) conflict(code, message string) signature.Conflict {
	return signature.Conflict{URI: a.target.URI, Range: signature.UTF16Range(a.target.Text, 0, 0), Code: code, Message: message}
}
func finish(response PlanResponse, a *analysis) (PlanResponse, error) {
	if err := a.snapshot.Unchanged(); err != nil {
		return PlanResponse{}, err
	}
	response.Preconditions = a.snapshot.Preconditions()
	response.Directories = a.snapshot.Directories()
	response.PlanDigest = digest(response)
	response.PlanID = response.PlanDigest[:24]
	return response, nil
}

type outputFile struct {
	original     signature.SemanticFile
	imports      *imports
	declarations []string
	header       string
	names        []string
}

func Plan(ctx context.Context, request PlanRequest) (PlanResponse, error) {
	a, err := open(ctx, request.Request)
	if err != nil {
		return PlanResponse{}, err
	}
	defer a.snapshot.Close()
	response := PlanResponse{SymbolID: a.inspection.InspectionID, InspectionID: a.inspection.InspectionID, ConfigurationDigest: a.snapshot.Digest(),
		Preconditions: []signature.Precondition{}, Directories: []signature.DirectoryCondition{}, WorkspaceEdit: WorkspaceEdit{DocumentChanges: []any{}},
		Usages: []signature.Usage{}, Coverage: a.view.Coverage, Conflicts: []signature.Conflict{}, Validation: signature.Validation{BeforeErrors: a.view.Errors, AfterErrors: []string{}}}
	reject := func(code, message string) { response.Conflicts = append(response.Conflicts, a.conflict(code, message)) }
	if request.ConfigurationDigest != a.snapshot.Digest() {
		reject("stale-configuration", "Go build settings changed; inspect test generation again")
	}
	if request.InspectionID != a.inspection.InspectionID {
		reject("stale-inspection", "The inspected declarations changed; inspect test generation again")
	}
	if len(a.view.Errors) > 0 {
		reject("source-errors", "Fix the active workspace's type errors before generating compiled test scaffolds")
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	outputs := map[string]*outputFile{}
	getOutput := func(defaultURI string, source signature.SemanticFile) (*outputFile, error) {
		uri := request.DestinationURI
		if uri == "" {
			uri = defaultURI
		}
		path, e := signature.LocalPath(uri)
		if e != nil {
			return nil, e
		}
		if filepath.Dir(path) != a.directory || !strings.HasSuffix(path, "_test.go") || !a.snapshot.Editable(path) {
			return nil, fmt.Errorf("choose a _test.go destination in the selected package directory")
		}
		if info, e := os.Lstat(path); e == nil && info.Mode()&os.ModeSymlink != 0 {
			return nil, fmt.Errorf("the test destination cannot be a symbolic link")
		}
		if out := outputs[path]; out != nil {
			if out.header != buildHeader(source.Text) {
				return nil, fmt.Errorf("selected source files have different build constraints; use their corresponding test destinations")
			}
			return out, nil
		}
		original, e := a.snapshot.ReadOptional(path)
		if e != nil {
			return nil, e
		}
		packageName := a.packageName
		if a.request.PackageMode == PackageExternal {
			packageName += "_test"
		}
		if original.Exists || original.Overlay != nil {
			active, ok := a.view.Files[path]
			if !ok {
				return nil, fmt.Errorf("the existing destination is not in the active Go build context")
			}
			original = active
			if original.AST.Name.Name != packageName {
				return nil, fmt.Errorf("destination package is %s, expected %s; choose another destination", original.AST.Name.Name, packageName)
			}
			if ast.IsGenerated(original.AST) {
				return nil, fmt.Errorf("do not append tests to a generated file")
			}
		}
		header := buildHeader(source.Text)
		if original.Exists && header != buildHeader(original.Text) {
			return nil, fmt.Errorf("destination build constraints differ from the source; choose its corresponding _test.go")
		}
		out := &outputFile{original: original, imports: newImports(original, a), header: header}
		outputs[path] = out
		return out, nil
	}
	reserved := map[string]bool{}
	for name := range a.names {
		reserved[name] = true
	}
	reserve := func(name string) error {
		if !validTestName(name) {
			return fmt.Errorf("%q is not a valid Go test name (TestMain is reserved)", name)
		}
		if reserved[name] {
			return fmt.Errorf("%s already exists in this test package; select a different test name", name)
		}
		reserved[name] = true
		return nil
	}
	if a.request.Scope == ScopeEmpty {
		name := request.EmptyName
		if name == "" {
			name = "TestSomething"
		}
		if e := reserve(name); e != nil {
			reject("test-name", e.Error())
		} else if out, e := getOutput(a.inspection.DefaultDestinationURI, a.target); e != nil {
			reject("destination", e.Error())
		} else {
			out.declarations = append(out.declarations, renderEmpty(name, out.imports))
			out.names = append(out.names, name)
		}
	} else {
		if len(request.Selections) == 0 {
			reject("selection", "Select at least one declaration to generate tests")
		}
		seen := map[string]bool{}
		for _, selection := range request.Selections {
			if err = ctx.Err(); err != nil {
				return PlanResponse{}, err
			}
			if seen[selection.SymbolID] {
				reject("selection", "A declaration was selected more than once")
				continue
			}
			seen[selection.SymbolID] = true
			var selected *declaration
			for i := range a.declarations {
				if a.declarations[i].symbol.SymbolID == selection.SymbolID {
					selected = &a.declarations[i]
					break
				}
			}
			if selected == nil {
				reject("stale-symbol", "A selected declaration is no longer in this generation scope")
				continue
			}
			name := selection.TestName
			if name == "" {
				name = selected.symbol.SuggestedTestName
			}
			if e := reserve(name); e != nil {
				reject("test-name", e.Error())
				continue
			}
			out, e := getOutput(selected.symbol.DestinationURI, selected.file)
			if e != nil {
				reject("destination", e.Error())
				continue
			}
			text, e := renderTest(a, *selected, selection, name, out.imports)
			if e != nil {
				reject("typed-generation", e.Error())
				continue
			}
			out.declarations = append(out.declarations, text)
			out.names = append(out.names, name)
			response.Usages = append(response.Usages, signature.Usage{URI: selected.file.URI, Range: selected.symbol.Range, Kind: "test-subject", Message: "Generate " + name + " for this typed declaration"})
		}
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	candidates := map[string]string{}
	paths := []string{}
	for path := range outputs {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	for _, path := range paths {
		out := outputs[path]
		if out.imports.err != nil {
			reject("imports", out.imports.err.Error())
			continue
		}
		text := out.original.Text
		if !out.original.Exists && out.original.Overlay == nil {
			pkg := a.packageName
			if a.request.PackageMode == PackageExternal {
				pkg += "_test"
			}
			text = out.header + "package " + pkg + "\n"
		}
		text += "\n" + strings.Join(out.declarations, "\n")
		fs := token.NewFileSet()
		file, e := parser.ParseFile(fs, path, text, parser.ParseComments|parser.AllErrors)
		if e != nil {
			reject("parse", e.Error())
			continue
		}
		importPaths := []string{}
		for p := range out.imports.added {
			importPaths = append(importPaths, p)
		}
		sort.Strings(importPaths)
		for _, p := range importPaths {
			astutil.AddNamedImport(fs, file, out.imports.added[p], p)
		}
		var formatted bytes.Buffer
		if e = format.Node(&formatted, fs, file); e != nil {
			reject("format", e.Error())
			continue
		}
		candidates[path] = formatted.String()
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	checked, e := a.snapshot.Load(ctx, candidates)
	if e != nil {
		return PlanResponse{}, e
	}
	response.Validation.AfterErrors = checked.Errors
	response.Validation.TypeChecked = len(checked.Errors) == 0
	if !response.Validation.TypeChecked {
		for _, message := range checked.Errors {
			reject("typecheck", message)
		}
		return finish(response, a)
	}
	// A type checker can succeed while ignoring a platform-excluded destination.
	// Prove the generated tests actually belong to the active test package graph.
	actual := map[string]map[string]bool{}
	packages.Visit(checked.Packages, func(pkg *packages.Package) bool {
		for _, file := range pkg.Syntax {
			path := checked.Fset.PositionFor(file.Pos(), false).Filename
			if _, ok := outputs[path]; !ok {
				continue
			}
			if actual[path] == nil {
				actual[path] = map[string]bool{}
			}
			for _, node := range file.Decls {
				if fn, ok := node.(*ast.FuncDecl); ok {
					actual[path][fn.Name.Name] = true
				}
			}
		}
		return true
	}, nil)
	for _, path := range paths {
		for _, name := range outputs[path].names {
			if !actual[path][name] {
				reject("inactive-destination", "The generated test "+name+" is excluded from this active Go test context")
			}
		}
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	for _, path := range paths {
		old := outputs[path].original
		var version *int
		if old.Overlay != nil && old.Overlay.LSP != nil {
			v := old.Overlay.LSP.Version
			version = &v
		}
		if !old.Exists && old.Overlay == nil {
			response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, CreateFile{Kind: "create", URI: signature.LocalURI(path)})
		}
		response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, signature.DocumentEdit{TextDocument: signature.TextDocument{URI: signature.LocalURI(path), Version: version}, Edits: []signature.TextEdit{{Range: signature.UTF16Range(old.Text, 0, len(old.Text)), NewText: candidates[path]}}})
	}
	return finish(response, a)
}

func buildHeader(text string) string {
	var out []string
	for _, line := range strings.Split(text, "\n") {
		trim := strings.TrimSpace(line)
		if strings.HasPrefix(trim, "package ") {
			break
		}
		if strings.HasPrefix(trim, "//go:build ") || strings.HasPrefix(trim, "// +build ") {
			out = append(out, line)
		}
	}
	if len(out) == 0 {
		return ""
	}
	return strings.Join(out, "\n") + "\n\n"
}
