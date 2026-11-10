package signature

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func fixture(t *testing.T, files map[string]string) (string, Request, InspectResponse) {
	t.Helper()
	root := t.TempDir()
	root, _ = filepath.EvalSymlinks(root)
	if _, ok := files["go.mod"]; !ok {
		files["go.mod"] = "module example.org/signature\n\ngo 1.24.0\n"
	}
	for path, text := range files {
		p := filepath.Join(root, path)
		if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	target := files["main.go"]
	offset := strings.Index(target, "Target(")
	if offset < 0 {
		t.Fatal("fixture has no Target")
	}
	req := Request{OperationID: "inspect", Root: root, Target: Target{fileURI(filepath.Join(root, "main.go")), positionAt(target, offset+2)}, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	info, err := Inspect(ctx, req)
	if err != nil {
		t.Fatal(err)
	}
	return root, req, info
}
func makePlan(t *testing.T, req Request, info InspectResponse, change Change) PlanResponse {
	t.Helper()
	req.OperationID = "plan"
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	plan, err := Plan(ctx, PlanRequest{req, info.SymbolID, info.ConfigurationDigest, change})
	if err != nil {
		t.Fatal(err)
	}
	return plan
}
func unchangedChange(info InspectResponse) Change {
	return Change{Name: info.Name, Parameters: append([]Row{}, info.Parameters...), Results: append([]Row{}, info.Results...)}
}
func applyPlan(t *testing.T, plan PlanResponse) {
	t.Helper()
	if len(plan.Conflicts) > 0 {
		b, _ := json.MarshalIndent(plan.Conflicts, "", "  ")
		t.Fatal(string(b))
	}
	if !plan.Validation.TypeChecked {
		t.Fatalf("candidate was not type checked: %+v", plan.Validation)
	}
	for _, doc := range plan.WorkspaceEdit.DocumentChanges {
		p, err := uriPath(doc.TextDocument.URI)
		if err != nil {
			t.Fatal(err)
		}
		if len(doc.Edits) != 1 {
			t.Fatal("expected immutable full-document edit")
		}
		if err = os.WriteFile(p, []byte(doc.Edits[0].NewText), 0600); err != nil {
			t.Fatal(err)
		}
	}
}
func runFixture(t *testing.T, root string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "run", ".")
	cmd.Dir = root
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOPROXY=off", "GOTOOLCHAIN=local")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("run: %v\n%s", err, out)
	}
	return string(out)
}

func TestTypedPlanReordersArgumentsPreservingEffectsAndDisk(t *testing.T) {
	source := "package main\nimport \"fmt\"\nvar trace []int\nfunc mark(n int) int { trace=append(trace,n); return n }\nfunc Target(a,b int) int { return a*10+b }\nfunc main(){fmt.Println(Target(mark(1),mark(2)),trace)}\n"
	root, req, info := fixture(t, map[string]string{"main.go": source})
	before := runFixture(t, root)
	change := unchangedChange(info)
	change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
	plan := makePlan(t, req, info, change)
	current, _ := os.ReadFile(filepath.Join(root, "main.go"))
	if string(current) != source {
		t.Fatal("planner wrote user source")
	}
	applyPlan(t, plan)
	if got := runFixture(t, root); got != before {
		t.Fatalf("effects changed: %q != %q", got, before)
	}
}
func TestTypedInterfaceClosureUpdatesDynamicAndStaticCalls(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\ntype API interface { Target(x,y int) int }\ntype Worker struct{}\nfunc (Worker) Target(a,b int) int{return a*10+b}\nfunc main(){ var api API=Worker{};fmt.Println(api.Target(1,2),Worker{}.Target(1,2)) }\n"})
	if info.RelatedDeclarations != 2 {
		t.Fatalf("interface closure=%d", info.RelatedDeclarations)
	}
	before := runFixture(t, root)
	change := unchangedChange(info)
	change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != before {
		t.Fatalf("interface semantics changed: %q != %q", got, before)
	}
}
func TestNamedResultReorderKeepsDeferredWrites(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target()(a,b int){defer func(){a++;b+=2}();return 3,4}\nfunc main(){a,b:=Target();fmt.Println(a,b)}\n"})
	before := runFixture(t, root)
	change := unchangedChange(info)
	change.Results[0], change.Results[1] = change.Results[1], change.Results[0]
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != before {
		t.Fatalf("deferred named result changed: %q != %q", got, before)
	}
}
func TestRemovedNamedResultStillFeedsDeferredWrite(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target()(a,b int){defer func(){a+=b}();return 3,4}\nfunc main(){a,_:=Target();fmt.Println(a)}\n"})
	before := runFixture(t, root)
	change := unchangedChange(info)
	change.Results = change.Results[:1]
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != before {
		t.Fatalf("removed named slot changed defer: %q != %q", got, before)
	}
}
func TestAddParametersResultsRenameAndTypedFunctionValues(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target(a int) int{return a+1}\nfunc main(){f:=Target;fmt.Println(f(2),Target(3))}\n"})
	before := runFixture(t, root)
	change := unchangedChange(info)
	change.Name = "Changed"
	change.Parameters = append(change.Parameters, Row{RowID: "new", Name: "extra", Type: "string", DefaultArgument: &Expression{Kind: "expression", Text: "\"new\""}})
	change.Results = append(change.Results, Row{RowID: "error", Type: "error", BodyDefault: &Expression{Kind: "zero"}})
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != before {
		t.Fatalf("typed function values changed: %q != %q", got, before)
	}
}
func TestRemovedUsedParameterRequiresExplicitMigration(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target(a,b int)int{return a+b}\nfunc main(){fmt.Println(Target(1,2))}\n"})
	change := unchangedChange(info)
	change.Parameters = change.Parameters[:1]
	bad := makePlan(t, req, info, change)
	if len(bad.Conflicts) == 0 || len(bad.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("missing body migration was accepted")
	}
	change.ParameterBodyMigrations = []Migration{{info.Parameters[1].RowID, "2"}}
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != "3\n" {
		t.Fatal(got)
	}
}
func TestDirtyOverlayUTF16CRLFAndStaleSymbol(t *testing.T) {
	source := "package main\nfunc Target(a,b int)int{return a+b}\nfunc main(){println(Target(1,2))}\n"
	root, req, _ := fixture(t, map[string]string{"main.go": source})
	raw := "\ufeff" + strings.ReplaceAll(source, "\n", "\r\n")
	if err := os.WriteFile(filepath.Join(root, "main.go"), []byte(raw), 0600); err != nil {
		t.Fatal(err)
	}
	dirty := strings.Replace(source, "func Target", "// 中文 😀\nfunc Target", 1)
	req.Overlays = []Overlay{{URI: req.Target.URI, Text: dirty, DocumentStamp: 37, DiskSHA256: hash([]byte(raw)), LSP: &LSPVersion{4, 8}}}
	req.Target.Position = positionAt(dirty, strings.Index(dirty, "Target(")+2)
	info, err := Inspect(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	change := unchangedChange(info)
	change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
	plan := makePlan(t, req, info, change)
	if len(plan.Conflicts) > 0 {
		t.Fatal(plan.Conflicts)
	}
	doc := plan.WorkspaceEdit.DocumentChanges[0]
	if doc.TextDocument.Version == nil || *doc.TextDocument.Version != 8 {
		t.Fatal("actual overlay LSP version lost")
	}
	if doc.Edits[0].Range.End != positionAt(dirty, len(dirty)) {
		t.Fatal("edit was not in canonical editor coordinates")
	}
	found := false
	for _, p := range plan.Preconditions {
		if p.URI == req.Target.URI {
			found = p.TextSHA256 == hash([]byte(dirty)) && p.DiskSHA256 == hash([]byte(raw)) && p.DocumentStamp != nil && *p.DocumentStamp == 37
		}
	}
	if !found {
		t.Fatal("overlay/native/disk baseline not preserved")
	}
	req.Overlays[0].Text += "\n"
	stale := makePlan(t, req, info, change)
	if len(stale.Conflicts) == 0 {
		t.Fatal("stale symbol accepted")
	}
}

func signatureWorkspaceGo(t *testing.T) string {
	t.Helper()
	selected := os.Getenv("OPENGOLAND_GO_TEST")
	if selected == "" {
		// Preserve the existing default PATH behavior when no SDK was selected.
		return ""
	}
	selected, err := exec.LookPath(selected)
	if err != nil {
		t.Fatal(err)
	}
	selected, err = filepath.Abs(selected)
	if err != nil {
		t.Fatal(err)
	}
	// Keep the executable entry point: resolving a Go shim can change argv0 dispatch.
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, selected, "env", "-json", "GOROOT", "GOVERSION")
	for _, pair := range os.Environ() {
		name, _, _ := strings.Cut(pair, "=")
		if name != "GOROOT" && name != "GOTOOLCHAIN" && name != "GOENV" && name != "GOFLAGS" {
			command.Env = append(command.Env, pair)
		}
	}
	command.Env = append(command.Env, "GOTOOLCHAIN=local", "GOENV=off", "GOFLAGS=")
	out, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("selected Go environment: %v\n%s", err, out)
	}
	var sdk struct{ GOROOT, GOVERSION string }
	if err := json.Unmarshal(out, &sdk); err != nil {
		t.Fatalf("selected Go environment JSON: %v\n%s", err, out)
	}
	if !filepath.IsAbs(sdk.GOROOT) || sdk.GOVERSION == "" {
		t.Fatalf("selected Go environment is incomplete: %s", out)
	}
	t.Setenv("PATH", filepath.Dir(selected)+string(os.PathListSeparator)+os.Getenv("PATH"))
	t.Setenv("GOROOT", sdk.GOROOT)
	t.Setenv("GOTOOLCHAIN", "local")
	t.Setenv("GOENV", "off")
	t.Setenv("GOFLAGS", "")
	t.Logf("selected workspace SDK: %s (%s; GOROOT=%s)", sdk.GOVERSION, selected, sdk.GOROOT)
	return selected
}

func TestDirtyWorkAndModuleMetadataLoadCrossModuleInterfaceClosure(t *testing.T) {
	selectedGo := signatureWorkspaceGo(t)
	cases := []struct {
		name, cwd, work          string
		wantModules, wantRelated int
		wantRejected             bool
	}{
		{"absolute-workspace-root", "", "absolute", 2, 2, false},
		{"empty-module-root", "a", "", 2, 2, false},
		{"auto-module-root", "a", "auto", 2, 2, false},
		{"absolute-module-root", "a", "absolute", 2, 2, false},
		{"off-module-root", "a", "off", 1, 1, false},
		{"relative-module-root", "a", "./go.work", 0, 0, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			root := t.TempDir()
			root, _ = filepath.EvalSymlinks(root)
			api := "package a\ntype Number int\ntype API interface{Target(x,y Number) Number}\nfunc Call(api API)Number{return api.Target(1,2)}\n"
			main := "package main\nimport(\"fmt\";\"example.org/a\")\ntype Worker struct{}\nfunc(Worker)Target(x,y a.Number)a.Number{return x*10+y}\nfunc main(){fmt.Println(a.Call(Worker{}),Worker{}.Target(1,2))}\n"
			files := map[string]string{"go.work": "go 1.24.0\nuse ./a\n", "a/go.mod": "module example.org/a\n\ngo 1.24.0\n", "a/api.go": api, "a/api_test.go": "package a\nimport \"testing\"\nfunc TestNumber(t *testing.T){var _ Number=1}\n", "b/go.mod": "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ./missing\n", "b/main.go": main}
			for name, source := range files {
				p := filepath.Join(root, name)
				if err := os.MkdirAll(filepath.Dir(p), 0755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(p, []byte(source), 0600); err != nil {
					t.Fatal(err)
				}
			}
			assertDiskUnchanged := func() {
				t.Helper()
				for name, before := range files {
					data, err := os.ReadFile(filepath.Join(root, name))
					if err != nil || string(data) != before {
						t.Fatalf("analysis modified %s: %v", name, err)
					}
				}
			}
			dirtyWork := "go 1.24.0\nuse (\n./a\n./b\n)\n"
			dirtyMod := strings.Replace(files["b/go.mod"], "./missing", "../a", 1)
			work := tc.work
			if work == "absolute" {
				work = filepath.Join(root, "go.work")
			}
			req := Request{OperationID: "dirty-metadata", Root: filepath.Join(root, tc.cwd), GoExecutable: selectedGo, Target: Target{fileURI(filepath.Join(root, "a/api.go")), positionAt(api, strings.Index(api, "Target(")+2)}, Environment: map[string]string{"GOWORK": work, "GOPROXY": "off", "GOENV": "off", "GOFLAGS": ""}, Overlays: []Overlay{{URI: fileURI(filepath.Join(root, "go.work")), Text: dirtyWork, DocumentStamp: 77}, {URI: fileURI(filepath.Join(root, "b/go.mod")), Text: dirtyMod, DocumentStamp: 78}}}
			ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
			defer cancel()
			info, err := Inspect(ctx, req)
			if tc.wantRejected {
				if err == nil || !strings.Contains(err.Error(), "GOWORK") || !strings.Contains(err.Error(), "absolute path") {
					t.Fatalf("relative GOWORK must remain rejected: %v", err)
				}
				assertDiskUnchanged()
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if len(info.Coverage.Modules) != tc.wantModules || info.RelatedDeclarations != tc.wantRelated {
				t.Fatalf("selected workspace/type-world closure not loaded: %+v", info)
			}
			change := unchangedChange(info)
			change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
			plan := makePlan(t, req, info, change)
			if len(plan.Conflicts) > 0 || !plan.Validation.TypeChecked {
				t.Fatalf("cross-module candidate was not type checked: %+v", plan)
			}
			assertDiskUnchanged()
			conditions := map[string]Precondition{}
			for _, p := range plan.Preconditions {
				conditions[p.URI] = p
			}
			for name, stamp := range map[string]int64{"go.work": 77, "b/go.mod": 78} {
				p := conditions[fileURI(filepath.Join(root, name))]
				if p.DocumentStamp == nil || *p.DocumentStamp != stamp {
					t.Fatalf("missing dirty config baseline %s", name)
				}
			}
			wantedEdits := map[string]bool{fileURI(filepath.Join(root, "a/api.go")): true}
			if tc.wantModules == 2 {
				wantedEdits[fileURI(filepath.Join(root, "b/main.go"))] = true
			}
			for _, edit := range plan.WorkspaceEdit.DocumentChanges {
				if !wantedEdits[edit.TextDocument.URI] {
					t.Fatalf("edit escaped selected module scope: %s", edit.TextDocument.URI)
				}
				delete(wantedEdits, edit.TextDocument.URI)
			}
			if len(wantedEdits) != 0 {
				t.Fatalf("missing related declaration or caller edits: %v", wantedEdits)
			}
			if tc.wantModules == 1 {
				// off must not load or edit the other module, even with supplied metadata overlays.
				return
			}
			applyPlan(t, plan)
			// Only the test harness applies its own unsaved module overlay for execution.
			if err = os.WriteFile(filepath.Join(root, "b/go.mod"), []byte(dirtyMod), 0600); err != nil {
				t.Fatal(err)
			}
			if got := runFixture(t, filepath.Join(root, "b")); got != "12 12\n" {
				t.Fatalf("cross-module dynamic semantics changed: %q", got)
			}
		})
	}
}

func TestParameterRenameDetectsCaptureAndTypeChangeUsesExplicitConversions(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target(a int) int{return a+1}\nfunc main(){fmt.Println(Target(2))}\n"})
	change := unchangedChange(info)
	change.Parameters[0].Type = "int64"
	change.Parameters[0].DefaultArgument = &Expression{Kind: "expression", Text: "int64(a)"}
	change.ParameterBodyMigrations = []Migration{{info.Parameters[0].RowID, "int(a)"}}
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != "3\n" {
		t.Fatal(got)
	}
}

func TestDirectorySnapshotRejectsNewCallersAndNewPackages(t *testing.T) {
	root, req, _ := fixture(t, map[string]string{"main.go": "package main\nfunc Target(a int)int{return a}\n"})
	if err := os.MkdirAll(filepath.Join(root, "empty"), 0755); err != nil {
		t.Fatal(err)
	}
	s, err := newSnapshot(req)
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	if _, err = s.load(context.Background(), nil); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(root, "empty", "new.go"), []byte("package empty\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = s.unchanged(); err == nil {
		t.Fatal("a new package in a previously empty directory escaped the snapshot")
	}
	if err = os.Remove(filepath.Join(root, "empty", "new.go")); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(root, "new.go"), []byte("package main\nfunc added(){Target(1)}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = s.unchanged(); err == nil {
		t.Fatal("a new caller escaped the snapshot")
	}
}
func TestDefaultExpressionBindsSymbolsWithoutCapturingLambdaFields(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target(a int)int{return a}\nfunc main(){fmt.Println(Target(2))}\n"})
	change := unchangedChange(info)
	change.Parameters = append(change.Parameters, Row{RowID: "new", Name: "extra", Type: "int", DefaultArgument: &Expression{Kind: "expression", Text: "(func() int { type S struct{ a int }; return S{a:5}.a })()"}})
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != "2\n" {
		t.Fatal(got)
	}
}
func TestNestedFunctionValueAndCallsUseOnePostorder(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target(a,b int)int{return a*10+b}\nfunc main(){fmt.Println(Target((func()int{saved:=Target;return saved(1,2)})(),3))}\n"})
	before := runFixture(t, root)
	change := unchangedChange(info)
	change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != before {
		t.Fatalf("nested value/call changed: %q != %q", got, before)
	}
}
func TestVendorGraphIsRetainedAndImportsAreCleaned(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"go.mod": "module example.org/signature\n\ngo 1.24.0\nrequire example.org/vendoronly v1.0.0\n", "vendor/modules.txt": "# example.org/vendoronly v1.0.0\n## explicit; go 1.24.0\nexample.org/vendoronly\n", "vendor/example.org/vendoronly/value.go": "package vendoronly\nfunc Value(n int)int{return n}\n", "main.go": "package main\nimport(\"fmt\";\"time\";\"example.org/vendoronly\")\nfunc Target(a int, unused time.Duration)int{return vendoronly.Value(a)}\nfunc main(){fmt.Println(Target(2,3))}\n"})
	change := unchangedChange(info)
	change.Parameters = change.Parameters[:1]
	plan := makePlan(t, req, info, change)
	applyPlan(t, plan)
	data, _ := os.ReadFile(filepath.Join(root, "main.go"))
	if strings.Contains(string(data), "\"time\"") {
		t.Fatalf("obsolete type import was retained:\n%s", data)
	}
	if got := runFixture(t, root); got != "2\n" {
		t.Fatal(got)
	}
}
func TestRenamingParameterCannotCapturePackageFunction(t *testing.T) {
	_, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Other()int{return 8}\nfunc Target(a func()int)int{return a()+Other()}\n"})
	change := unchangedChange(info)
	change.Parameters[0].Name = "Other"
	plan := makePlan(t, req, info, change)
	if len(plan.Conflicts) == 0 {
		t.Fatal("compilable but semantically captured package function was accepted")
	}
}
func TestAddedZeroResultWorksWhenBuiltinNewIsShadowed(t *testing.T) {
	root, req, info := fixture(t, map[string]string{"main.go": "package main\nimport \"fmt\"\nfunc Target(a int)int{new:=a;return new+1}\nfunc main(){fmt.Println(Target(2))}\n"})
	change := unchangedChange(info)
	change.Results = append(change.Results, Row{RowID: "new", Type: "error", BodyDefault: &Expression{Kind: "zero"}})
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != "3\n" {
		t.Fatal(got)
	}
}

func TestNamedResultRenameCannotTurnShortDeclarationIntoReassignment(t *testing.T) {
	_, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Target()(a int){ b,x:=5,6;_,_=b,x;return }\n"})
	change := unchangedChange(info)
	change.Results[0].Name = "b"
	plan := makePlan(t, req, info, change)
	if len(plan.Conflicts) == 0 {
		t.Fatal("named result rename captured an existing short declaration")
	}
}

func TestGenericInterfaceInstantiationsAndPotentialImplementationsAreInClosure(t *testing.T) {
	for _, observed := range []bool{true, false} {
		t.Run(map[bool]string{true: "concrete", false: "symbolic"}[observed], func(t *testing.T) {
			main := "func main(){}\n"
			if observed {
				main = "func main(){s:=S[int]{};var api API[int]=s;println(s.Target(1,2),api.Target(1,2))}\n"
			}
			source := "package main\ntype API[T ~int] interface{Target(a,b T)T}\ntype S[U ~int] struct{}\nfunc(S[U])Target(a,b U)U{return a*10+b}\nfunc consume[V ~int](api API[V])V{return api.Target(1,2)}\n" + main
			root, req, _ := fixture(t, map[string]string{"main.go": source})
			req.Target.Position = positionAt(source, strings.Index(source, "func(S[U])Target")+len("func(S[U])Tar"))
			info, err := Inspect(context.Background(), req)
			if err != nil {
				t.Fatal(err)
			}
			if info.RelatedDeclarations != 2 {
				t.Fatalf("generic potential closure=%d", info.RelatedDeclarations)
			}
			change := unchangedChange(info)
			change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
			plan := makePlan(t, req, info, change)
			applyPlan(t, plan)
			if observed {
				if got := runFixture(t, root); got != "12 12\n" {
					t.Fatalf("generic interface semantics changed: %q", got)
				}
			}
		})
	}
}
func TestReadOnlyInterfaceContractAndInactiveBuildCallsCannotBeSilentlyOmitted(t *testing.T) {
	t.Run("external-interface", func(t *testing.T) {
		dep := t.TempDir()
		dep, _ = filepath.EvalSymlinks(dep)
		_ = os.WriteFile(filepath.Join(dep, "go.mod"), []byte("module example.org/contract\n\ngo 1.24.0\n"), 0600)
		_ = os.WriteFile(filepath.Join(dep, "api.go"), []byte("package contract\ntype API interface{Target(a,b int)int}\n"), 0600)
		source := "package main\nimport \"example.org/contract\"\ntype S struct{}\nfunc(S)Target(a,b int)int{return a*10+b}\nfunc main(){var api contract.API=S{};println(api.Target(1,2))}\n"
		_, req, info := fixture(t, map[string]string{"go.mod": "module example.org/signature\n\ngo 1.24.0\nrequire example.org/contract v0.0.0\nreplace example.org/contract => " + filepath.ToSlash(dep) + "\n", "main.go": source})
		change := unchangedChange(info)
		change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
		plan := makePlan(t, req, info, change)
		found := false
		for _, c := range plan.Conflicts {
			found = found || c.Code == "readonly-interface-contract"
		}
		if !found || len(plan.WorkspaceEdit.DocumentChanges) != 0 {
			t.Fatalf("read-only interface was silently omitted: %+v", plan)
		}
	})
	t.Run("inactive-caller", func(t *testing.T) {
		_, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Target(a,b int)int{return a*10+b}\nfunc main(){println(run())}\n", "run_default.go": "//go:build !variant\n\npackage main\nfunc run()int{return Target(1,2)}\n", "run_variant.go": "//go:build variant\n\npackage main\nfunc run()int{return Target(1,2)}\n"})
		change := unchangedChange(info)
		change.Parameters[0], change.Parameters[1] = change.Parameters[1], change.Parameters[0]
		plan := makePlan(t, req, info, change)
		found := false
		for _, c := range plan.Conflicts {
			found = found || c.Code == "inactive-build-context" && strings.HasSuffix(c.URI, "run_variant.go")
		}
		if !found || len(plan.WorkspaceEdit.DocumentChanges) != 0 {
			t.Fatalf("inactive caller was silently omitted: %+v", plan)
		}
	})
}

func TestAddFirstResultToVoidFallthroughKeepsOldCallABIsAndEffects(t *testing.T) {
	source := "package main\nimport \"fmt\"\nvar trace []int\nfunc mark(n int){trace=append(trace,n)}\nfunc Target(a int){defer mark(9);if a==0{return};mark(a)}\nfunc main(){Target(0);f:=Target;f(1);func(){defer Target(2)}();done:=make(chan bool);go func(){Target(3);done<-true}();<-done;fmt.Println(trace)}\n"
	root, req, info := fixture(t, map[string]string{"main.go": source})
	before := runFixture(t, root)
	change := unchangedChange(info)
	change.Results = []Row{{RowID: "added", Type: "error", BodyDefault: &Expression{Kind: "zero"}}}
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != before {
		t.Fatalf("void call ABI or effect order changed: %q != %q", got, before)
	}
}
func TestVoidFallthroughResultDefaultEvaluatesOnlyAtOriginalReturns(t *testing.T) {
	source := "package main\nimport \"fmt\"\nvar trace []int\nfunc result(n int)int{trace=append(trace,n);return n}\nfunc Target(a int){defer func(){trace=append(trace,9)}();if a==0{return};trace=append(trace,a)}\nfunc main(){Target(0);Target(2);fmt.Println(trace)}\n"
	root, req, info := fixture(t, map[string]string{"main.go": source})
	change := unchangedChange(info)
	change.Results = []Row{{RowID: "added", Type: "int", BodyDefault: &Expression{Kind: "expression", Text: "result(a+3)"}}}
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != "[3 9 2 5 9]\n" {
		t.Fatalf("return default ran at wrong phase: %q", got)
	}
}

func TestUnchangedResultShapeStillAppliesExplicitBodyConversion(t *testing.T) {
	cases := []struct {
		name       string
		body       string
		conversion Expression
		want       string
	}{
		{"unnamed", "func Target() int { return mark(1) }", Expression{Kind: "expression", Text: "mark(oldResult0 + 1)"}, "2 [1 2]\n"},
		{"zero", "func Target() int { return mark(1) }", Expression{Kind: "zero"}, "0 [1]\n"},
		{"named-bare-return", "func Target() (r int) { defer func(){ trace=append(trace,r); r*=10 }(); r=mark(1); return }", Expression{Kind: "expression", Text: "mark(oldResult0 + 1)"}, "20 [1 2 2]\n"},
		{"named-explicit-return", "func Target() (r int) { defer func(){ trace=append(trace,r); r*=10 }(); return mark(1) }", Expression{Kind: "expression", Text: "mark(oldResult0 + 1)"}, "20 [1 2 2]\n"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			source := "package main\nimport \"fmt\"\nvar trace []int\nfunc mark(n int)int{trace=append(trace,n);return n}\n" + tc.body + "\nfunc main(){fmt.Println(Target(),trace)}\n"
			root, req, info := fixture(t, map[string]string{"main.go": source})
			change := unchangedChange(info)
			change.Results[0].BodyDefault = &tc.conversion
			applyPlan(t, makePlan(t, req, info, change))
			if got := runFixture(t, root); got != tc.want {
				t.Fatalf("explicit result conversion was ignored or reordered: %q != %q", got, tc.want)
			}
		})
	}
}

func TestUnchangedResultShapeStillAppliesExplicitCallerConversion(t *testing.T) {
	source := "package main\nimport \"fmt\"\nvar trace []int\nfunc mark(n int)int{trace=append(trace,n);return n}\nfunc Target()(r int){return mark(1)}\nfunc main(){f:=Target;fmt.Println(Target(),f(),trace)}\n"
	root, req, info := fixture(t, map[string]string{"main.go": source})
	change := unchangedChange(info)
	change.RemovedResultUseMigrations = []Migration{{RowID: info.Results[0].RowID, Expression: "mark(r + 1)"}}
	applyPlan(t, makePlan(t, req, info, change))
	if got := runFixture(t, root); got != "2 2 [1 2 1 2]\n" {
		t.Fatalf("same-type caller conversion was ignored or reordered: %q", got)
	}
}

func TestExplicitChangeFieldsCannotBeSilentlyIgnored(t *testing.T) {
	cases := map[string]func(*Change, InspectResponse){
		"parameter-result-default": func(c *Change, _ InspectResponse) { c.Parameters[0].BodyDefault = &Expression{Kind: "zero"} },
		"result-argument-default":  func(c *Change, _ InspectResponse) { c.Results[0].DefaultArgument = &Expression{Kind: "zero"} },
		"unknown-expression-kind":  func(c *Change, _ InspectResponse) { c.Results[0].BodyDefault = &Expression{Kind: "unknown"} },
		"malformed-unused-default": func(c *Change, _ InspectResponse) {
			c.Parameters[0].DefaultArgument = &Expression{Kind: "expression", Text: "("}
		},
		"zero-with-expression-text": func(c *Change, _ InspectResponse) {
			c.Results[0].BodyDefault = &Expression{Kind: "zero", Text: "sideEffect()"}
		},
		"unknown-parameter-migration": func(c *Change, _ InspectResponse) {
			c.ParameterBodyMigrations = []Migration{{RowID: "missing", Expression: "1"}}
		},
		"unknown-result-migration": func(c *Change, _ InspectResponse) {
			c.RemovedResultUseMigrations = []Migration{{RowID: "missing", Expression: "1"}}
		},
		"duplicate-parameter-migration": func(c *Change, i InspectResponse) {
			c.Parameters = nil
			c.ParameterBodyMigrations = []Migration{{RowID: i.Parameters[0].RowID, Expression: "1"}, {RowID: i.Parameters[0].RowID, Expression: "2"}}
		},
		"duplicate-result-migration": func(c *Change, i InspectResponse) {
			c.RemovedResultUseMigrations = []Migration{{RowID: i.Results[0].RowID, Expression: "1"}, {RowID: i.Results[0].RowID, Expression: "2"}}
		},
		"unchanged-parameter-migration": func(c *Change, i InspectResponse) {
			c.ParameterBodyMigrations = []Migration{{RowID: i.Parameters[0].RowID, Expression: "1"}}
		},
		"empty-result-migration": func(c *Change, i InspectResponse) {
			c.RemovedResultUseMigrations = []Migration{{RowID: i.Results[0].RowID, Expression: ""}}
		},
	}
	_, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Target(a int)(r int){return a}\nfunc main(){}\n"})
	for name, modify := range cases {
		t.Run(name, func(t *testing.T) {
			change := unchangedChange(info)
			modify(&change, info)
			plan := makePlan(t, req, info, change)
			if len(plan.Conflicts) == 0 || len(plan.WorkspaceEdit.DocumentChanges) != 0 {
				t.Fatalf("explicit invalid change was silently accepted: %+v", plan)
			}
			if plan.Conflicts[0].URI != req.Target.URI || plan.Conflicts[0].Code != "signature" {
				t.Fatalf("missing declaration-located schema conflict: %+v", plan.Conflicts)
			}
		})
	}
}

func TestFunctionAndMethodRenamePreserveBindingsOrReportConflicts(t *testing.T) {
	cases := map[string]string{
		"caller-local":   "package main\nfunc Target(a,b int)int{return a*10+b}\nfunc main(){Renamed:=func(a,b int)int{return 999};_=Renamed;println(Target(1,2))}\n",
		"promoted-field": "package main\ntype Embedded struct{Renamed func(int,int)int}\ntype S struct{Embedded}\nfunc(S)Target(a,b int)int{return a*10+b}\nfunc main(){s:=S{Embedded{func(a,b int)int{return 999}}};println(s.Target(1,2),s.Renamed(3,4))}\n",
	}
	for name, source := range cases {
		t.Run(name, func(t *testing.T) {
			_, req, info := fixture(t, map[string]string{"main.go": source})
			change := unchangedChange(info)
			change.Name = "Renamed"
			plan := makePlan(t, req, info, change)
			if len(plan.Conflicts) == 0 || len(plan.WorkspaceEdit.DocumentChanges) != 0 {
				t.Fatalf("rename silently changed binding: %+v", plan)
			}
		})
	}
	t.Run("used-builtin", func(t *testing.T) {
		_, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Target(s string)int{return len(s)}\nfunc main(){println(Target(\"abc\"),len(\"xy\"))}\n"})
		change := unchangedChange(info)
		change.Name = "len"
		plan := makePlan(t, req, info, change)
		if len(plan.Conflicts) == 0 {
			t.Fatal("rename captured builtin uses and introduced recursion")
		}
	})
	t.Run("unused-builtin-allowed", func(t *testing.T) {
		root, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Target(s string)int{return 3}\nfunc main(){println(Target(\"abc\"))}\n"})
		change := unchangedChange(info)
		change.Name = "len"
		applyPlan(t, makePlan(t, req, info, change))
		if got := runFixture(t, root); got != "3\n" {
			t.Fatal(got)
		}
	})
	t.Run("dot-import-visibility", func(t *testing.T) {
		_, req, info := fixture(t, map[string]string{"main.go": "package signature\nfunc Target(s string)int{return 3}\nfunc Other(){}\n", "caller/main.go": "package main\nimport . \"example.org/signature\"\nfunc main(){Other();println(Target(\"abc\"))}\n"})
		change := unchangedChange(info)
		change.Name = "len"
		plan := makePlan(t, req, info, change)
		if len(plan.Conflicts) == 0 {
			t.Fatal("dot import reference rebound to an exported builtin")
		}
	})
	t.Run("inactive-new-name", func(t *testing.T) {
		_, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Target(s string)int{return 3}\nfunc main(){println(Target(\"abc\"))}\n", "variant.go": "//go:build variant\n\npackage main\nfunc extra()int{return len(\"xy\")}\n"})
		change := unchangedChange(info)
		change.Name = "len"
		plan := makePlan(t, req, info, change)
		if len(plan.Conflicts) == 0 {
			t.Fatal("inactive builtin use was silently captured")
		}
	})
}

func TestRenameCannotSilentlyChangeImplicitGoEntryPoints(t *testing.T) {
	for _, name := range []string{"init", "main"} {
		t.Run(name, func(t *testing.T) {
			_, req, info := fixture(t, map[string]string{"main.go": "package main\nfunc Target(){}\n"})
			change := unchangedChange(info)
			change.Name = name
			plan := makePlan(t, req, info, change)
			found := false
			for _, c := range plan.Conflicts {
				found = found || c.Code == "implicit-entrypoint"
			}
			if !found {
				t.Fatal("implicit entry lifecycle change accepted", plan)
			}
		})
	}
}
