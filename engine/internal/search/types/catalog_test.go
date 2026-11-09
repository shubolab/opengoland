package typesearch

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func TestMain(m *testing.M) {
	if handled, err := WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func fixture(t *testing.T) (string, Request) {
	t.Helper()
	root := t.TempDir()
	write(t, filepath.Join(root, "go.mod"), "module example.org/typecatalog\n\ngo 1.24.0\n")
	goexe, err := exec.LookPath("go")
	if err != nil {
		t.Fatal(err)
	}
	return root, Request{Request: signature.Request{Root: root, GoExecutable: goexe, Environment: map[string]string{"GOPROXY": "off", "GOWORK": "off", "GOTOOLCHAIN": "local", "CGO_ENABLED": "0"}}}
}

func write(t *testing.T, path, text string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
}

func catalog(t *testing.T, request Request) Response {
	t.Helper()
	value, err := Catalog(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func named(value Response, name string) []Type {
	var result []Type
	for _, entry := range value.Types {
		if entry.Name == name {
			result = append(result, entry)
		}
	}
	return result
}

func TestCatalogHasNoWorkspaceSymbolCountCap(t *testing.T) {
	root, request := fixture(t)
	var source strings.Builder
	source.WriteString("package catalog\ntype Callback func(int) int\ntype Alias[T any] = Box[T]\ntype Box[T any] struct{ Value T }\n")
	for n := 0; n < 240; n++ {
		fmt.Fprintf(&source, "type Record%03d struct{}\nfunc RecordFunction%03d() {}\n", n, n)
	}
	write(t, filepath.Join(root, "model.go"), source.String())
	value := catalog(t, request)
	if len(value.Types) != 243 || len(named(value, "Record239")) != 1 || len(named(value, "RecordFunction239")) != 0 {
		t.Fatalf("incomplete or non-type rows: %d", len(value.Types))
	}
	if got := named(value, "Callback"); len(got) != 1 || got[0].Kind != "function" || got[0].Origin != "project" {
		t.Fatalf("named function type missing: %#v", got)
	}
	if got := named(value, "Alias"); len(got) != 1 || got[0].Kind != "alias" {
		t.Fatalf("generic alias missing: %#v", got)
	}
	if !value.Coverage.Complete || value.CatalogID == "" || value.ConfigurationDigest == "" {
		t.Fatalf("unexpected coverage: %#v %#v", value.Coverage, value.Issues)
	}
}

func TestCatalogOverlayUnicodeAndTests(t *testing.T) {
	root, request := fixture(t)
	path := filepath.Join(root, "model.go")
	write(t, path, "package catalog\r\ntype DiskOnly struct{}\r\n")
	text := "package catalog\nvar _ = \"前😀\"; type 𐐀Record struct { 名称 string }\n"
	request.Overlays = []signature.Overlay{{URI: signature.LocalURI(path), Text: text, DocumentStamp: 17}}
	write(t, filepath.Join(root, "model_test.go"), "package catalog\ntype InternalTestType struct{}\n")
	write(t, filepath.Join(root, "external_test.go"), "package catalog_test\ntype ExternalTestType struct{}\n")
	value := catalog(t, request)
	if len(named(value, "DiskOnly")) != 0 || len(named(value, "𐐀Record")) != 1 || len(named(value, "InternalTestType")) != 1 || len(named(value, "ExternalTestType")) != 1 {
		t.Fatalf("overlay/test catalog: %#v", value.Types)
	}
	entry := named(value, "𐐀Record")[0]
	start, err := signature.UTF16Offset(text, entry.SelectionRange.Start)
	if err != nil {
		t.Fatal(err)
	}
	end, err := signature.UTF16Offset(text, entry.SelectionRange.End)
	if err != nil || text[start:end] != entry.Name {
		t.Fatalf("UTF16 declaration selection: %v %#v", err, entry)
	}
	canonicalPath, _ := canonical(path)
	foundGuard := false
	for _, guard := range value.SourceGuards {
		if guard.URI == signature.LocalURI(canonicalPath) {
			foundGuard = guard.DocumentStamp != nil && *guard.DocumentStamp == 17
		}
	}
	if !foundGuard {
		t.Fatal("overlay owner stamp omitted")
	}
	data, _ := os.ReadFile(path)
	if string(data) != "package catalog\r\ntype DiskOnly struct{}\r\n" {
		t.Fatal("catalog wrote the overlay")
	}
}

func TestCatalogUsesBuildContextAndIgnoresNonGoSources(t *testing.T) {
	root, request := fixture(t)
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Common struct{}\n")
	write(t, filepath.Join(root, "active.go"), "//go:build custom\n\npackage catalog\ntype Tagged struct{}\n")
	write(t, filepath.Join(root, "model_windows.go"), "package catalog\ntype Windows struct{}\n")
	write(t, filepath.Join(root, "_ignored.go"), "package catalog\ntype Ignored struct{}\n")
	write(t, filepath.Join(root, "testdata", "ignored.go"), "package ignored\ntype Testdata struct{}\n")
	request.Environment["GOOS"] = "linux"
	first := catalog(t, request)
	if len(first.Types) != 1 || first.Types[0].Name != "Common" {
		t.Fatalf("wrong active sources: %#v", first.Types)
	}
	request.BuildTags, request.Environment["GOOS"] = []string{"custom"}, "windows"
	second := catalog(t, request)
	if len(second.Types) != 3 || len(named(second, "Tagged")) != 1 || len(named(second, "Windows")) != 1 || second.ConfigurationDigest == first.ConfigurationDigest {
		t.Fatalf("build context was ignored: %#v", second.Types)
	}
}

func TestCatalogIncludesUnimportedSelectedDependency(t *testing.T) {
	root, request := fixture(t)
	dependency := t.TempDir()
	write(t, filepath.Join(dependency, "go.mod"), "module example.org/dependency\n\ngo 1.24.0\n")
	write(t, filepath.Join(dependency, "unused", "model.go"), "package unused\ntype DependencyOnly struct{}\n")
	write(t, filepath.Join(root, "go.mod"), "module example.org/typecatalog\n\ngo 1.24.0\nrequire example.org/dependency v0.0.0\nreplace example.org/dependency => "+filepath.ToSlash(dependency)+"\n")
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Local struct{}\n")
	if got := catalog(t, request); len(got.Types) != 1 {
		t.Fatalf("project catalog leaked dependencies: %#v", got.Types)
	}
	request.IncludeDependencies = true
	value := catalog(t, request)
	if got := named(value, "DependencyOnly"); len(got) != 1 || got[0].Origin != "dependency" || got[0].PackagePath != "example.org/dependency/unused" {
		t.Fatalf("unimported selected dependency missing: %#v %#v", value.Types, value.Issues)
	}
	request.Overlays = []signature.Overlay{{URI: signature.LocalURI(filepath.Join(dependency, "unused", "model.go")), Text: "package unused\ntype DirtyOutsideDependency struct{}\n", DocumentStamp: 31}}
	dirty := catalog(t, request)
	if len(named(dirty, "DirtyOutsideDependency")) != 1 || len(named(dirty, "DependencyOnly")) != 0 {
		t.Fatalf("dirty dependency source was lost: %#v", dirty.Types)
	}
}

func TestCatalogSymlinkAndUnsavedFileIdentity(t *testing.T) {
	root, request := fixture(t)
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Disk struct{}\n")
	alias := filepath.Join(t.TempDir(), "source alias")
	if err := os.Symlink(root, alias); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	request.Root = alias
	request.Overlays = []signature.Overlay{{URI: signature.LocalURI(filepath.Join(alias, "model.go")), Text: "package catalog\ntype DirtyAlias struct{}\n", DocumentStamp: 41}, {URI: signature.LocalURI(filepath.Join(alias, "unsaved.go")), Text: "package catalog\ntype Unsaved struct{}\n", DocumentStamp: 42}}
	value := catalog(t, request)
	if len(named(value, "DirtyAlias")) != 1 || len(named(value, "Disk")) != 0 || len(named(value, "Unsaved")) != 1 {
		t.Fatalf("symlink overlay identity: %#v", value.Types)
	}
	if _, err := os.Stat(filepath.Join(root, "unsaved.go")); !os.IsNotExist(err) {
		t.Fatal("new overlay was written")
	}
}

func TestCatalogCGOUsesOriginalSources(t *testing.T) {
	if _, err := exec.LookPath("cc"); err != nil {
		t.Skip("C compiler unavailable")
	}
	root, request := fixture(t)
	request.Environment["CGO_ENABLED"] = "1"
	write(t, filepath.Join(root, "model.go"), "package catalog\n/* int answer(void) { return 42; } */\nimport \"C\"\ntype CgoRecord struct{}\nfunc Answer() int { return int(C.answer()) }\n")
	value := catalog(t, request)
	if len(value.Types) != 1 || value.Types[0].Name != "CgoRecord" || len(value.Issues) != 0 {
		t.Fatalf("CGO artifacts leaked or source lost: %#v %#v", value.Types, value.Issues)
	}
}

func TestCatalogUsesDirtyModuleMetadataAndIgnoresUnrelatedOverlays(t *testing.T) {
	root, request := fixture(t)
	dependency := t.TempDir()
	write(t, filepath.Join(dependency, "go.mod"), "module example.org/dirtydependency\n\ngo 1.24.0\n")
	write(t, filepath.Join(dependency, "model.go"), "package dependency\ntype DirtyDependency struct{}\n")
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Local struct{}\n")
	dirty := "module example.org/typecatalog\n\ngo 1.24.0\nrequire example.org/dirtydependency v0.0.0\nreplace example.org/dirtydependency => " + filepath.ToSlash(dependency) + "\n"
	unrelated := filepath.Join(t.TempDir(), "go.mod")
	write(t, unrelated, "module unrelated\n\ngo 1.24.0\n")
	request.Overlays = []signature.Overlay{{URI: signature.LocalURI(filepath.Join(root, "go.mod")), Text: dirty, DocumentStamp: 8}, {URI: signature.LocalURI(unrelated), Text: "unrelated unfinished module input", DocumentStamp: 9}}
	request.IncludeDependencies = true
	value := catalog(t, request)
	if len(named(value, "DirtyDependency")) != 1 {
		t.Fatalf("dirty module dependency missing: %#v %#v", value.Types, value.Issues)
	}
	canonicalUnrelated, _ := canonical(unrelated)
	for _, guard := range value.SourceGuards {
		if guard.URI == signature.LocalURI(canonicalUnrelated) {
			t.Fatal("unrelated project overlay became a catalog precondition")
		}
	}
}

func TestCatalogDirtyWorkspaceIncludesOutsideModule(t *testing.T) {
	root, request := fixture(t)
	peer := t.TempDir()
	write(t, filepath.Join(peer, "go.mod"), "module example.org/catalogpeer\n\ngo 1.24.0\n")
	write(t, filepath.Join(peer, "model.go"), "package peer\ntype OutsideWorkspaceType struct{}\n")
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Local struct{}\n")
	delete(request.Environment, "GOWORK")
	request.Overlays = []signature.Overlay{{URI: signature.LocalURI(filepath.Join(root, "go.work")), Text: fmt.Sprintf("go 1.24.0\nuse (\n %q\n %q\n)\n", root, peer), DocumentStamp: 22}}
	value := catalog(t, request)
	if got := named(value, "OutsideWorkspaceType"); len(got) != 1 || got[0].Origin != "project" {
		t.Fatalf("outside workspace module missing: %#v %#v", value.Types, value.Issues)
	}
	if len(value.Coverage.Modules) != 2 {
		t.Fatalf("module ownership: %#v", value.Coverage)
	}
	if _, err := os.Stat(filepath.Join(root, "go.work")); !os.IsNotExist(err) {
		t.Fatal("dirty workspace was written")
	}
}

func TestCatalogVendorIncludesUnimportedVendoredPackage(t *testing.T) {
	root, request := fixture(t)
	dependency := t.TempDir()
	write(t, filepath.Join(dependency, "go.mod"), "module example.org/dependency\n\ngo 1.24.0\n")
	write(t, filepath.Join(root, "go.mod"), "module example.org/typecatalog\n\ngo 1.24.0\nrequire example.org/dependency v0.0.0\nreplace example.org/dependency => "+filepath.ToSlash(dependency)+"\n")
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Local struct{}\n")
	write(t, filepath.Join(root, "vendor", "modules.txt"), "# example.org/dependency v0.0.0 => "+filepath.ToSlash(dependency)+"\n## explicit; go 1.24.0\nexample.org/dependency\n# example.org/dependency => "+filepath.ToSlash(dependency)+"\n")
	write(t, filepath.Join(root, "vendor", "example.org", "dependency", "model.go"), "package dependency\ntype VendoredType struct{}\n")
	request.IncludeDependencies = true
	value := catalog(t, request)
	if got := named(value, "VendoredType"); len(got) != 1 || got[0].Origin != "dependency" {
		t.Fatalf("vendor catalog: %#v %#v", value.Types, value.Issues)
	}
}

func TestCatalogExplicitGOPATHContext(t *testing.T) {
	root, request := fixture(t)
	if err := os.Remove(filepath.Join(root, "go.mod")); err != nil {
		t.Fatal(err)
	}
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype LooseProject struct{}\n")
	gopath := t.TempDir()
	write(t, filepath.Join(gopath, "src", "example.org", "other", "model.go"), "package other\ntype GOPATHDependency struct{}\n")
	request.Environment["GO111MODULE"], request.Environment["GOPATH"] = "off", gopath
	request.IncludeDependencies = true
	value := catalog(t, request)
	if len(named(value, "LooseProject")) != 1 || len(named(value, "GOPATHDependency")) != 1 || !value.Coverage.Complete {
		t.Fatalf("GOPATH context: %#v %#v", value.Types, value.Issues)
	}
}

func TestCatalogSDKAndIsolatedWorker(t *testing.T) {
	root, request := fixture(t)
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Local struct{}\n")
	request.IncludeSDK = true
	raw, err := RunIsolated(context.Background(), "catalog", request)
	if err != nil {
		t.Fatal(err)
	}
	var value Response
	if err = json.Unmarshal(raw, &value); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, entry := range named(value, "Buffer") {
		if entry.PackagePath == "bytes" && entry.Origin == "sdk" {
			found = true
		}
	}
	if !found || len(value.Types) < 500 || len(named(value, "NewBuffer")) != 0 {
		t.Fatalf("SDK type catalog: %d types, Buffer found=%v", len(value.Types), found)
	}
	if len(raw) >= 16<<20 {
		t.Fatalf("SDK catalog exceeds engine framing: %d", len(raw))
	}
	t.Logf("uncapped selected-SDK catalog: %d types, %d source guards, %d bytes; coverage complete=%v, issues=%d", len(value.Types), len(value.SourceGuards), len(raw), value.Coverage.Complete, len(value.Issues))
	if !value.Coverage.Complete {
		t.Fatalf("SDK catalog metadata: %#v", value.Issues)
	}
}

func TestCatalogSyntaxRecoveryAndCancellation(t *testing.T) {
	root, request := fixture(t)
	write(t, filepath.Join(root, "model.go"), "package catalog\ntype Partial struct { Value int\n")
	value := catalog(t, request)
	if len(named(value, "Partial")) != 1 || value.Coverage.Complete || len(value.Issues) == 0 {
		t.Fatalf("partial syntax was misrepresented: %#v", value)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := Catalog(ctx, request); err != context.Canceled {
		t.Fatalf("cancellation: %v", err)
	}
}
