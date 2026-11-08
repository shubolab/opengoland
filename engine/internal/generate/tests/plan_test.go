package tests

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
	"time"
	"unicode/utf16"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func TestMain(m *testing.M) {
	if handled, err := WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			os.Stderr.WriteString(err.Error())
			os.Exit(1)
		}
		return
	}
	os.Exit(m.Run())
}
func fixture(t *testing.T, files map[string]string) Request {
	t.Helper()
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if files["go.mod"] == "" {
		files["go.mod"] = "module example.org/generatedtests\n\ngo 1.24.0\n"
	}
	for name, text := range files {
		p := filepath.Join(root, name)
		if err = os.MkdirAll(filepath.Dir(p), 0700); err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(p, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	return Request{Request: signature.Request{OperationID: "test", Root: root, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}, Target: signature.Target{URI: signature.LocalURI(filepath.Join(root, "sample.go")), Position: signature.Position{Line: 1, Character: 6}}}, Scope: ScopeFile, PackageMode: PackageSame}
}
func inspectedPlan(t *testing.T, req Request, configure func(*PlanRequest, InspectResponse)) PlanResponse {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	info, err := Inspect(ctx, req)
	if err != nil {
		t.Fatal(err)
	}
	plan := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, Selections: []Selection{}}
	for _, symbol := range info.Symbols {
		if !symbol.ExistingTest {
			plan.Selections = append(plan.Selections, Selection{SymbolID: symbol.SymbolID})
		}
	}
	if configure != nil {
		configure(&plan, info)
	}
	result, err := Plan(ctx, plan)
	if err != nil {
		t.Fatal(err)
	}
	return result
}
func success(t *testing.T, result PlanResponse) {
	t.Helper()
	if len(result.Conflicts) > 0 || !result.Validation.TypeChecked {
		t.Fatalf("plan failed: %+v; validation=%+v", result.Conflicts, result.Validation)
	}
	if len(result.WorkspaceEdit.DocumentChanges) == 0 {
		t.Fatal("missing edits")
	}
}
func candidateTexts(result PlanResponse) map[string]string {
	out := map[string]string{}
	for _, change := range result.WorkspaceEdit.DocumentChanges {
		if edit, ok := change.(signature.DocumentEdit); ok {
			path, _ := signature.LocalPath(edit.TextDocument.URI)
			out[path] = edit.Edits[0].NewText
		}
	}
	return out
}
func compileFixture(t *testing.T, req Request, result PlanResponse) string {
	t.Helper()
	for path, text := range candidateTexts(result) {
		if err := os.WriteFile(path, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "test", "-json", "./...")
	cmd.Dir = req.Root
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOPROXY=off", "GOTOOLCHAIN=local", "GOTELEMETRY=off")
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("generated fixture did not compile/run: %v\n%s", err, output)
	}
	return string(output)
}

func TestTypedVariadicMethodsResultsAndSkipScaffoldsCompile(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": `package sample
import clock "time"
type Widget struct { value int }
func (w *Widget) Add(prefix string, values ...int) (int,error) {return w.value,nil}
func Echo(at clock.Time, callback func(int) string) (clock.Time,func(int) string) { return at,callback }
`})
	result := inspectedPlan(t, req, nil)
	success(t, result)
	text := candidateTexts(result)[filepath.Join(req.Root, "sample_test.go")]
	for _, want := range []string{"values []int", ".args.values...)", "receiver *Widget", ".receiver.Add(", "wantErr1 bool", "time.Time", "reflect.DeepEqual", "Skip(\"TODO: Add test cases.\")"} {
		if !strings.Contains(text, want) {
			t.Errorf("missing %q in\n%s", want, text)
		}
	}
	output := compileFixture(t, req, result)
	if !strings.Contains(output, `"Action":"skip"`) {
		t.Fatal("empty scaffolds must explicitly skip, not falsely pass")
	}
}

func TestGenericsRequireConcreteArgumentsAndValidateConstraintsIncludingReceivers(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": `package sample
func Identity[T ~int](value T) T { return value }
type Box[T comparable] struct { Value T }
func (b *Box[T]) Get(fallback T) T { return b.Value }
`})
	missing := inspectedPlan(t, req, nil)
	if len(missing.Conflicts) != 2 || len(missing.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatalf("missing generic arguments: %+v", missing)
	}
	wrong := inspectedPlan(t, req, func(plan *PlanRequest, _ InspectResponse) {
		for i := range plan.Selections {
			plan.Selections[i].TypeArguments = []string{"[]int"}
		}
	})
	if len(wrong.Conflicts) == 0 {
		t.Fatal("invalid constraints were accepted")
	}
	result := inspectedPlan(t, req, func(plan *PlanRequest, _ InspectResponse) {
		for i := range plan.Selections {
			plan.Selections[i].TypeArguments = []string{"int"}
		}
	})
	success(t, result)
	text := candidateTexts(result)[filepath.Join(req.Root, "sample_test.go")]
	if !strings.Contains(text, "Identity[int](") || !strings.Contains(text, "*Box[int]") {
		t.Fatal(text)
	}
	compileFixture(t, req, result)
}

func TestExternalPackageExportsImportsAndInaccessibleTypes(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": `package sample
type Public struct { hidden int }
func New(value int) Public { return Public{value} }
func (p Public) Read() int { return p.hidden }
func hidden() int { return 1 }
`})
	req.PackageMode = PackageExternal
	result := inspectedPlan(t, req, func(plan *PlanRequest, info InspectResponse) {
		plan.Selections = nil
		for _, symbol := range info.Symbols {
			if symbol.Exported {
				plan.Selections = append(plan.Selections, Selection{SymbolID: symbol.SymbolID})
			}
		}
	})
	success(t, result)
	text := candidateTexts(result)[filepath.Join(req.Root, "sample_test.go")]
	if !strings.Contains(text, "package sample_test") || !strings.Contains(text, "sample.New(") || !strings.Contains(text, "receiver sample.Public") {
		t.Fatal(text)
	}
	compileFixture(t, req, result)
	private := fixture(t, map[string]string{"sample.go": "package sample\ntype hidden struct{}\nfunc Exported() hidden{return hidden{}}\n"})
	private.PackageMode = PackageExternal
	blocked := inspectedPlan(t, private, nil)
	if len(blocked.Conflicts) == 0 || len(blocked.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("external inaccessible result type must be a conflict")
	}
}

func TestExistingTestsImportsAndUnsavedOverlayRemainIntact(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": "package sample\nfunc Sum(a,b int) int{return a+b}\nfunc Already(){}\n", "sample_test.go": `package sample
import t "testing"
func TestAlready(ctx *t.T) {}
`})
	path := filepath.Join(req.Root, "sample_test.go")
	dirty := "package sample\nimport t \"testing\"\n// unsaved 中文 😀\nfunc TestAlready(ctx *t.T) {}\n"
	req.Overlays = []signature.Overlay{{URI: signature.LocalURI(path), Text: dirty, DocumentStamp: 19, LSP: &signature.LSPVersion{Session: 8, Version: 3}}}
	result := inspectedPlan(t, req, func(plan *PlanRequest, info InspectResponse) {
		if len(info.Symbols) != 2 || !info.Symbols[1].ExistingTest {
			t.Fatalf("existing test discovery: %+v", info.Symbols)
		}
	})
	success(t, result)
	if len(result.WorkspaceEdit.DocumentChanges) != 1 {
		t.Fatalf("existing file must not be created: %+v", result.WorkspaceEdit)
	}
	edit := result.WorkspaceEdit.DocumentChanges[0].(signature.DocumentEdit)
	if edit.TextDocument.Version == nil || *edit.TextDocument.Version != 3 {
		t.Fatal("lost captured document version")
	}
	if !strings.Contains(edit.Edits[0].NewText, "unsaved 中文 😀") || strings.Count(edit.Edits[0].NewText, "func TestAlready(") != 1 {
		t.Fatal(edit.Edits[0].NewText)
	}
	for _, pre := range result.Preconditions {
		if pre.URI == signature.LocalURI(path) && (pre.DocumentStamp == nil || *pre.DocumentStamp != 19) {
			t.Fatal("lost dirty document precondition")
		}
	}
	compileFixture(t, req, result)
}

func TestEmptyScopeNameConflictsAndStaleInspection(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": "package sample\nfunc Work(){}\n"})
	req.Scope = ScopeEmpty
	result := inspectedPlan(t, req, func(plan *PlanRequest, _ InspectResponse) { plan.EmptyName = "TestEmpty" })
	success(t, result)
	compileFixture(t, req, result)
	conflict := inspectedPlan(t, req, func(plan *PlanRequest, _ InspectResponse) { plan.EmptyName = "TestEmpty" })
	if len(conflict.Conflicts) == 0 {
		t.Fatal("existing test must not be duplicated")
	}
	info, err := Inspect(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(req.Root, "sample.go"), []byte("package sample\nfunc Changed(){}\n"), 0600)
	stale, err := Plan(context.Background(), PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, EmptyName: "TestAnother"})
	if err != nil {
		t.Fatal(err)
	}
	if len(stale.Conflicts) == 0 || len(stale.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("stale inspection emitted edits")
	}
}

func TestFilePackageScopesPreserveBuildConstraintsAndNoUserInitialization(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": "//go:build !opengoland_excluded\n\npackage sample\nfunc First(){}\n", "other.go": "package sample\nimport \"os\"\nfunc init(){os.WriteFile(\"must-not-execute\",[]byte(\"bad\"),0600)}\nfunc Second(){}\n"})
	req.Scope = ScopePackage
	result := inspectedPlan(t, req, nil)
	success(t, result)
	texts := candidateTexts(result)
	if len(texts) != 2 {
		t.Fatalf("package scope destinations: %+v", texts)
	}
	if !strings.HasPrefix(texts[filepath.Join(req.Root, "sample_test.go")], "//go:build !opengoland_excluded") {
		t.Fatal("lost build constraints")
	}
	if _, err := os.Stat(filepath.Join(req.Root, "must-not-execute")); !os.IsNotExist(err) {
		t.Fatal("planner executed user init")
	}
}

func TestIsolatedWorkerCarriesTheExactProtocolAndCancellation(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": "package sample\nfunc Work(){}\n"})
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	raw, err := RunIsolated(ctx, "inspect", req)
	if err != nil {
		t.Fatal(err)
	}
	var info InspectResponse
	if err = json.Unmarshal(raw, &info); err != nil || len(info.Symbols) != 1 {
		t.Fatalf("worker result %s, %v", raw, err)
	}
	stopped, stop := context.WithCancel(context.Background())
	stop()
	if _, err = RunIsolated(stopped, "inspect", req); err != context.Canceled {
		t.Fatalf("cancelled worker: %v", err)
	}
}

func TestFunctionScopeUsesUTF16AndUnsavedSourceSignature(t *testing.T) {
	source := "package sample\nfunc First(){}; /*😀*/ func Second(_ int, values ...string) {}\n"
	req := fixture(t, map[string]string{"sample.go": source})
	req.Scope = ScopeFunction
	line := strings.Split(source, "\n")[1]
	prefix := line[:strings.Index(line, "func Second")]
	req.Target.Position = signature.Position{Line: 1, Character: len(utf16.Encode([]rune(prefix)))}
	dirty := strings.Replace(source, "...string", "...int", 1)
	req.Overlays = []signature.Overlay{{URI: req.Target.URI, Text: dirty, DocumentStamp: 21}}
	result := inspectedPlan(t, req, func(_ *PlanRequest, info InspectResponse) {
		if len(info.Symbols) != 1 || info.Symbols[0].Name != "Second" {
			t.Fatalf("wrong UTF-16 function selection: %+v", info.Symbols)
		}
	})
	success(t, result)
	text := candidateTexts(result)[filepath.Join(req.Root, "sample_test.go")]
	if !strings.Contains(text, "values []int") || !regexp.MustCompile(`arg0\s+int`).MatchString(text) {
		t.Fatal(text)
	}
	actual, _ := os.ReadFile(filepath.Join(req.Root, "sample.go"))
	if string(actual) != source {
		t.Fatal("planner saved dirty source")
	}
}

func TestConcreteTypeArgumentsResolveSourceImportAliases(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": "package sample\nimport clock \"time\"\ntype Timestamp = clock.Time\nfunc Identity[T any](value T) T { return value }\n"})
	result := inspectedPlan(t, req, func(plan *PlanRequest, _ InspectResponse) { plan.Selections[0].TypeArguments = []string{"clock.Time"} })
	success(t, result)
	text := candidateTexts(result)[filepath.Join(req.Root, "sample_test.go")]
	if !strings.Contains(text, "Identity[time.Time](") {
		t.Fatal(text)
	}
	compileFixture(t, req, result)
}

func TestPlatformExcludedDestinationIsNotMistakenForValidatedGeneratedTests(t *testing.T) {
	req := fixture(t, map[string]string{"sample.go": "package sample\nfunc Work(){}\n"})
	other := "windows"
	if runtime.GOOS == other {
		other = "linux"
	}
	result := inspectedPlan(t, req, func(plan *PlanRequest, _ InspectResponse) {
		plan.DestinationURI = signature.LocalURI(filepath.Join(req.Root, "generated_"+other+"_test.go"))
	})
	if len(result.Conflicts) == 0 || len(result.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatalf("inactive generated tests were accepted: %+v", result)
	}
}

func TestDirtyWorkspaceAndModuleMetadataAreTypedWithoutSaving(t *testing.T) {
	req := fixture(t, map[string]string{"go.work": "go 1.24.0\nuse ./a\n", "a/go.mod": "module example.org/a\n\ngo 1.24.0\n", "a/api.go": "package a\nfunc Work(value int) int{return value}\n", "b/go.mod": "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ./missing\n", "b/main.go": "package b\nimport \"example.org/a\"\nvar _ = a.Work(\"dirty\")\n"})
	req.Target.URI = signature.LocalURI(filepath.Join(req.Root, "a/api.go"))
	req.Environment["GOWORK"] = filepath.Join(req.Root, "go.work")
	req.Overlays = []signature.Overlay{
		{URI: signature.LocalURI(filepath.Join(req.Root, "go.work")), Text: "go 1.24.0\nuse (\n./a\n./b\n)\n", DocumentStamp: 31},
		{URI: signature.LocalURI(filepath.Join(req.Root, "b/go.mod")), Text: "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ../a\n", DocumentStamp: 32},
		{URI: req.Target.URI, Text: "package a\nfunc Work(value string) string{return value}\n", DocumentStamp: 33},
	}
	result := inspectedPlan(t, req, nil)
	success(t, result)
	if len(result.Coverage.Modules) != 2 {
		t.Fatalf("dirty go.work module graph lost: %+v", result.Coverage)
	}
	text := candidateTexts(result)[filepath.Join(req.Root, "a/api_test.go")]
	if !strings.Contains(text, "value string") {
		t.Fatal(text)
	}
	for _, overlay := range req.Overlays {
		found := false
		for _, pre := range result.Preconditions {
			if pre.URI == overlay.URI {
				found = pre.DocumentStamp != nil && *pre.DocumentStamp == overlay.DocumentStamp
			}
		}
		if !found {
			t.Fatalf("lost config/source overlay precondition: %s", overlay.URI)
		}
	}
	disk, _ := os.ReadFile(filepath.Join(req.Root, "go.work"))
	if strings.Contains(string(disk), "./b") {
		t.Fatal("planner saved dirty workspace metadata")
	}
}
