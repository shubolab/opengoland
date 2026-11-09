package engine

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func taskScopeFixture(t *testing.T) (*Manager, *recorder, string, string) {
	t.Helper()
	t.Setenv("GOTOOLCHAIN", "local")
	t.Setenv("GOWORK", "")
	t.Setenv("GOPROXY", "off")
	t.Setenv("GOFLAGS", "")
	t.Setenv("GOENV", "off")
	parent, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	root := filepath.Join(parent, "工作空间 😀")
	external := filepath.Join(parent, "外部 module")
	files := map[string]string{"go.work": "go 1.24.0\nuse (\n./a\n./b\n\"../外部 module\"\n)\n", "a/go.mod": "module example.org/a\n\ngo 1.24.0\n", "a/source.go": "package a\nconst Value=1\n", "b/go.mod": "module example.org/b\n\ngo 1.24.0\n", "b/source.go": "package b\nconst Value=2\n"}
	for name, text := range files {
		p := filepath.Join(root, name)
		if e = os.MkdirAll(filepath.Dir(p), 0700); e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(p, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	if e = os.MkdirAll(external, 0700); e != nil {
		t.Fatal(e)
	}
	for name, text := range map[string]string{"go.mod": "module example.org/external\n\ngo 1.24.0\n", "source.go": "package external\nconst Value=3\n"} {
		if e = os.WriteFile(filepath.Join(external, name), []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	r := newRecorder()
	m, e := New(root, "", r.notify)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		if e := m.Close(); e != nil {
			t.Error(e)
		}
	})
	return m, r, root, external
}
func taskTarget(t *testing.T, info TaskTargetsResponse, kind, cwd string) TaskTarget {
	t.Helper()
	for _, target := range info.Targets {
		if target.Kind == kind && (cwd == "" || cwd == target.CWD) {
			return target
		}
	}
	t.Fatalf("missing target %s %s: %+v", kind, cwd, info)
	return TaskTarget{}
}
func inspectTask(t *testing.T, m *Manager, command, path string, env map[string]string) TaskTargetsResponse {
	t.Helper()
	info, e := m.TaskTargets(context.Background(), TaskTargetsRequest{Command: command, ContextPath: path, Env: env})
	if e != nil {
		t.Fatal(e)
	}
	if info.InspectionID == "" {
		t.Fatal(info)
	}
	return info
}
func selectedRequest(info TaskTargetsResponse, target TaskTarget, id string, env map[string]string) TaskRequest {
	return TaskRequest{ID: id, Command: info.Command, Env: env, TargetSelection: &TaskTargetSelection{InspectionID: info.InspectionID, TargetID: target.ID}}
}
func TestTaskTargetsUseActualWorkspaceNearestModuleAndExternalMembers(t *testing.T) {
	m, _, root, external := taskScopeFixture(t)
	m.moduleRoots = []string{filepath.Join(root, "stale-cache")}
	info := inspectTask(t, m, "tidy", filepath.Join(root, "b/source.go"), nil)
	b := taskTarget(t, info, "module", filepath.Join(root, "b"))
	if info.DefaultTargetID != b.ID || info.Workspace != filepath.Join(root, "go.work") || len(info.Targets) != 4 {
		t.Fatal(info)
	}
	all := taskTarget(t, info, "workspaceModules", "")
	if len(all.ModuleRoots) != 3 {
		t.Fatal(all)
	}
	noContext := inspectTask(t, m, "tidy", "", nil)
	if noContext.DefaultTargetID != "" {
		t.Fatal("multi-module root guessed a module")
	}
	explicit := map[string]string{"GOWORK": filepath.Join(root, "go.work")}
	outside := inspectTask(t, m, "tidy", filepath.Join(external, "source.go"), explicit)
	member := taskTarget(t, outside, "module", external)
	if outside.DefaultTargetID != member.ID {
		t.Fatal(outside)
	}
	off := map[string]string{"GOWORK": "off"}
	isolated := inspectTask(t, m, "tidy", filepath.Join(root, "b/source.go"), off)
	if isolated.Workspace != "" || len(isolated.Targets) != 1 || isolated.Targets[0].CWD != filepath.Join(root, "b") {
		t.Fatal(isolated)
	}
	empty := inspectTask(t, m, "tidy", "", off)
	if len(empty.Targets) != 0 || empty.DefaultTargetID != "" {
		t.Fatal("GOWORK off reused cached modules", empty)
	}
	if _, e := m.TaskTargets(context.Background(), TaskTargetsRequest{Command: "tidy", ContextPath: external, Env: off}); e == nil {
		t.Fatal("external context escaped GOWORK off")
	}
}
func TestSelectedRealGoTasksRunExactModuleAndAllMembersWithBuildTags(t *testing.T) {
	m, r, root, _ := taskScopeFixture(t)
	for i, command := range []string{"tidy", "vet", "benchmark", "generate"} {
		info := inspectTask(t, m, command, filepath.Join(root, "b/source.go"), nil)
		kind := "module"
		if command != "tidy" {
			kind = "workspaceModules"
		}
		target := taskTarget(t, info, kind, "")
		request := selectedRequest(info, target, command, nil)
		if command != "tidy" {
			request.Args = []string{"-tags=scopefixture"}
		}
		accepted, resume, e := m.Prepare(request)
		if e != nil {
			t.Fatal(e)
		}
		steps := accepted["steps"].([]step)
		expected := 1
		if kind == "workspaceModules" {
			expected = 3
		}
		if len(steps) != expected {
			t.Fatal(accepted)
		}
		for _, s := range steps {
			if i > 0 && !strings.Contains(strings.Join(s.Args, " "), "./...") {
				t.Fatal("build tags suppressed recursive package scope", steps)
			}
		}
		resume()
		event := r.wait(t, exited(command))
		if event.Params["code"] != float64(0) {
			t.Fatal(event, r.snapshot())
		}
	}
	for _, command := range []string{"download", "workSync", "vendor"} {
		info := inspectTask(t, m, command, filepath.Join(root, "a/source.go"), nil)
		target := taskTarget(t, info, "workspace", root)
		if len(info.Targets) != 1 || info.DefaultTargetID != target.ID {
			t.Fatal(info)
		}
		request := selectedRequest(info, target, command, nil)
		accepted, resume, e := m.Prepare(request)
		if e != nil {
			t.Fatal(e)
		}
		steps := accepted["steps"].([]step)
		if len(steps) != 1 || steps[0].CWD != root {
			t.Fatal(accepted)
		}
		if command == "vendor" && strings.Join(steps[0].Args, " ") != "work vendor" {
			t.Fatal(steps)
		}
		resume()
		event := r.wait(t, exited(command))
		if event.Params["code"] != float64(0) {
			t.Fatal(event, r.snapshot())
		}
	}
}
func TestTaskTargetMetadataEnvironmentForeignAndClientMutationAreRejected(t *testing.T) {
	for _, change := range []string{"module", "workspace", "source", "environment", "cwd", "foreign-target", "foreign-inspection", "build-context"} {
		t.Run(change, func(t *testing.T) {
			m, _, root, _ := taskScopeFixture(t)
			info := inspectTask(t, m, "tidy", filepath.Join(root, "a/source.go"), nil)
			target := taskTarget(t, info, "module", filepath.Join(root, "a"))
			request := selectedRequest(info, target, "stale", nil)
			switch change {
			case "module":
				os.WriteFile(filepath.Join(root, "a/go.mod"), []byte("module example.org/a\n\ngo 1.24.0\n// changed\n"), 0600)
			case "workspace":
				os.WriteFile(filepath.Join(root, "go.work"), []byte("go 1.24.0\nuse ./a\n"), 0600)
			case "source":
				os.WriteFile(filepath.Join(root, "a/source.go"), []byte("package a\nconst Value=7\n"), 0600)
			case "environment":
				request.Env = map[string]string{"GOWORK": "off"}
			case "cwd":
				request.CWD = filepath.Join(root, "b")
			case "foreign-target":
				other := inspectTask(t, m, "tidy", filepath.Join(root, "b/source.go"), nil)
				request.TargetSelection.TargetID = other.Targets[0].ID
			case "foreign-inspection":
				other, e := New(root, "", nil)
				if e != nil {
					t.Fatal(e)
				}
				defer other.Close()
				if _, _, e = other.Prepare(request); e == nil {
					t.Fatal("another manager accepted an inspection")
				}
				return
			case "build-context":
				request.Command = "vet"
			}
			if _, _, e := m.Prepare(request); e == nil {
				t.Fatal("stale/foreign target accepted: " + change)
			}
		})
	}
	m, _, root, _ := taskScopeFixture(t)
	info := inspectTask(t, m, "tidy", filepath.Join(root, "a/source.go"), nil)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	for i := range info.Targets {
		info.Targets[i].CWD = filepath.Join(root, "b")
		info.Targets[i].ModuleRoots = []string{root}
	}
	accepted, _, e := m.Prepare(selectedRequest(info, target, "immutable", nil))
	if e != nil {
		t.Fatal(e)
	}
	if accepted["steps"].([]step)[0].CWD != target.CWD {
		t.Fatal("client mutated retained target")
	}
	m.Cancel("immutable")
}
func TestTaskTargetsCanonicalSymlinksCannotEscapeCurrentWorkspace(t *testing.T) {
	m, _, root, external := taskScopeFixture(t)
	link := filepath.Join(root, "external-link")
	if e := os.Symlink(external, link); e != nil {
		t.Skip(e)
	}
	info := inspectTask(t, m, "tidy", filepath.Join(link, "source.go"), nil)
	if taskTarget(t, info, "module", external).ID != info.DefaultTargetID {
		t.Fatal(info)
	}
	outside := t.TempDir()
	if e := os.Symlink(outside, filepath.Join(root, "escape")); e != nil {
		t.Fatal(e)
	}
	if _, e := m.TaskTargets(context.Background(), TaskTargetsRequest{Command: "tidy", ContextPath: filepath.Join(root, "escape")}); e == nil {
		t.Fatal("symlink escaped actual workspace")
	}
}
func TestSelectedWorkspaceModuleFailureStopsBeforeAnyNextModule(t *testing.T) {
	m, r, root, external := taskScopeFixture(t)
	for name, text := range map[string]string{"a/source.go": "package a\n//go:generate sh -c \"exit 7\"\n", "b/source.go": "package b\n//go:generate sh -c \"echo should-not-run > marker\"\n"} {
		if e := os.WriteFile(filepath.Join(root, name), []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	if e := os.WriteFile(filepath.Join(external, "source.go"), []byte("package external\n//go:generate sh -c \"exit 7\"\n"), 0600); e != nil {
		t.Fatal(e)
	}
	info := inspectTask(t, m, "generate", "", nil)
	target := taskTarget(t, info, "workspaceModules", "")
	request := selectedRequest(info, target, "fail-stop", nil)
	start(t, m, request)
	exit := r.wait(t, exited(request.ID))
	if exit.Params["code"] == float64(0) {
		t.Fatal(exit)
	}
	count := 0
	for _, e := range r.snapshot() {
		if e.Method == "task/step" && e.Params["id"] == request.ID {
			count++
		}
	}
	if count != 1 {
		t.Fatal("continued after failed module", r.snapshot())
	}
	if _, e := os.Stat(filepath.Join(root, "b/marker")); !os.IsNotExist(e) {
		t.Fatal("later generator executed", e)
	}
}
func TestTargetInspectionAndPausedPrepareCancellationCannotStartCommands(t *testing.T) {
	m, r, root, _ := taskScopeFixture(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e := m.TaskTargets(ctx, TaskTargetsRequest{Command: "tidy"}); e != context.Canceled {
		t.Fatal(e)
	}
	info := inspectTask(t, m, "tidy", filepath.Join(root, "a/source.go"), nil)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	ctx, cancel = context.WithCancel(context.Background())
	_, resume, e := m.PrepareContext(ctx, selectedRequest(info, target, "paused-cancel", nil))
	if e != nil {
		t.Fatal(e)
	}
	cancel()
	resume()
	exit := r.wait(t, exited("paused-cancel"))
	if exit.Params["cancelled"] != true {
		t.Fatal(exit)
	}
	for _, e := range r.snapshot() {
		if e.Method == "task/step" && e.Params["id"] == "paused-cancel" {
			t.Fatal("cancelled preparation executed Go", e)
		}
	}
}
func TestTaskTargetCacheIsBoundedAndPatternFlagsAreNotPackages(t *testing.T) {
	if hasTaskPattern([]string{"-tags", "a,b", "-benchtime=1x", "-count", "1"}) || !hasTaskPattern([]string{"-tags=foo", "./sub/..."}) {
		t.Fatal("flag values were treated as package patterns")
	}
	m, _, root, _ := taskScopeFixture(t)
	info := inspectTask(t, m, "tidy", filepath.Join(root, "a"), nil)
	m.mu.Lock()
	saved := m.taskTargets[info.InspectionID]
	m.taskTargetOrder = nil
	m.taskTargets = map[string]*taskTargetInspection{}
	for i := 0; i < 64; i++ {
		id := string(rune('a' + i))
		m.taskTargetOrder = append(m.taskTargetOrder, id)
		m.taskTargets[id] = saved
	}
	m.mu.Unlock()
	inspectTask(t, m, "tidy", filepath.Join(root, "a"), nil)
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.taskTargets) != 64 || len(m.taskTargetOrder) != 64 || m.taskTargets["a"] != nil {
		t.Fatal("unbounded or unevicted inspection cache")
	}
}
func TestRealTaskTargetsRespectAutomaticAndExplicitSelectedToolchainEnvironment(t *testing.T) {
	m, r, root, _ := taskScopeFixture(t)
	active, e := m.taskGoEnvironment(context.Background(), filepath.Join(root, "a"), nil)
	if e != nil {
		t.Fatal(e)
	}
	for index, toolchain := range []string{"local", "auto", active.GOVERSION} {
		env := map[string]string{"GOTOOLCHAIN": toolchain, "GOFLAGS": "-tags=-Cscope"}
		info := inspectTask(t, m, "vet", filepath.Join(root, "a/source.go"), env)
		target := taskTarget(t, info, "module", filepath.Join(root, "a"))
		request := selectedRequest(info, target, "toolchain-"+toolchain, env)
		start(t, m, request)
		exit := r.wait(t, exited(request.ID))
		if exit.Params["code"] != float64(0) {
			t.Fatal(index, toolchain, exit, r.snapshot())
		}
		m.mu.Lock()
		saved := m.taskTargets[info.InspectionID]
		m.mu.Unlock()
		found := false
		for _, pair := range saved.env {
			if pair == "GOTOOLCHAIN="+toolchain {
				found = true
			}
		}
		if !found {
			t.Fatal("toolchain selection was silently rewritten", toolchain)
		}
	}
}
func TestSelectedWorkspaceRetainsItsScopeInExternalMembersAtActualGoRuntime(t *testing.T) {
	m, r, root, external := taskScopeFixture(t)
	if e := os.WriteFile(filepath.Join(external, "source.go"), []byte("package external\nimport \"example.org/a\"\nconst Value=a.Value\n"), 0600); e != nil {
		t.Fatal(e)
	}
	for _, fromExternal := range []bool{false, true} {
		path := ""
		kind := "workspaceModules"
		id := "all-with-external-import"
		if fromExternal {
			path = filepath.Join(external, "source.go")
			kind = "module"
			id = "external-context-import"
		}
		info := inspectTask(t, m, "vet", path, nil)
		if info.Workspace != filepath.Join(root, "go.work") {
			t.Fatal("lost project workspace for external member", info)
		}
		target := taskTarget(t, info, kind, "")
		if fromExternal {
			target = taskTarget(t, info, kind, external)
			if info.DefaultTargetID != target.ID {
				t.Fatal(info)
			}
		}
		request := selectedRequest(info, target, id, nil)
		start(t, m, request)
		exit := r.wait(t, exited(id))
		if exit.Params["code"] != float64(0) {
			t.Fatal("actual Go lost its workspace across module cwd changes", exit, r.snapshot())
		}
	}
}
func TestPreparedTaskWaitsForExplicitResumeAndOwnsItsGuardBeyondTheInspectionCache(t *testing.T) {
	m, r, root, _ := taskScopeFixture(t)
	source := filepath.Join(root, "a/source.go")
	if e := os.WriteFile(source, []byte("package a\n//go:generate sh -c \"echo resumed > marker\"\n"), 0600); e != nil {
		t.Fatal(e)
	}
	info := inspectTask(t, m, "generate", source, nil)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	request := selectedRequest(info, target, "two-phase", nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	response, handoff, e := m.PreparePaused(ctx, request)
	if e != nil {
		t.Fatal(e)
	}
	if response["paused"] != true {
		t.Fatal(response)
	}
	handoff()
	cancel() // Normal RPC preparation completion does not cancel its handed-off task.
	m.mu.Lock()
	paused := m.tasks[request.ID]
	m.taskTargets = nil
	m.taskTargetOrder = nil
	m.mu.Unlock()
	if paused == nil || paused.cancelled.Load() {
		t.Fatal("successful handoff cancelled the paused task")
	}
	select {
	case <-paused.gate:
		t.Fatal("prepare opened the execution gate")
	default:
	}
	for _, event := range r.snapshot() {
		if event.Method == "task/step" {
			t.Fatal("command ran before resume", event)
		}
	}
	if _, e = os.Stat(filepath.Join(root, "a/marker")); !os.IsNotExist(e) {
		t.Fatal("generator ran during prepare", e)
	}
	resumed, e := m.Resume(context.Background(), request.ID)
	if e != nil || resumed["resumed"] != true {
		t.Fatal(resumed, e)
	}
	exit := r.wait(t, exited(request.ID))
	if exit.Params["code"] != float64(0) {
		t.Fatal(exit, r.snapshot())
	}
	data, e := os.ReadFile(filepath.Join(root, "a/marker"))
	if e != nil || strings.TrimSpace(string(data)) != "resumed" {
		t.Fatal(string(data), e)
	}
	if _, e = m.Resume(context.Background(), request.ID); e == nil {
		t.Fatal("completed task resumed twice")
	}
}
func TestPreparedTaskRejectsResumeAfterFrozenManifestOrSourceChanges(t *testing.T) {
	for _, name := range []string{"a/go.mod", "go.work", "a/source.go"} {
		t.Run(name, func(t *testing.T) {
			m, r, root, _ := taskScopeFixture(t)
			info := inspectTask(t, m, "tidy", filepath.Join(root, "a/source.go"), nil)
			target := taskTarget(t, info, "module", filepath.Join(root, "a"))
			request := selectedRequest(info, target, "stale-resume", nil)
			_, handoff, e := m.PreparePaused(context.Background(), request)
			if e != nil {
				t.Fatal(e)
			}
			handoff()
			path := filepath.Join(root, name)
			data, e := os.ReadFile(path)
			if e != nil {
				t.Fatal(e)
			}
			if e = os.WriteFile(path, append(data, []byte("\n// changed after preparation\n")...), 0600); e != nil {
				t.Fatal(e)
			}
			if _, e = m.Resume(context.Background(), request.ID); e == nil {
				t.Fatal("stale prepared task resumed")
			}
			exit := r.wait(t, exited(request.ID))
			if exit.Params["cancelled"] != true {
				t.Fatal(exit)
			}
			rejectionSeen := false
			for _, event := range r.snapshot() {
				if event.Method == "task/output" && strings.Contains(event.Params["text"].(string), "changed after preparation") {
					rejectionSeen = true
				}
				if event.Method == "task/exit" && !rejectionSeen {
					t.Fatal("task terminated before its rejection reason was visible")
				}
				if event.Method == "task/step" {
					t.Fatal("stale task executed", event)
				}
			}
		})
	}
}
func TestPreparedTaskCancellationAndMissingSelectionCannotLeaveAnExecutableGate(t *testing.T) {
	m, r, root, _ := taskScopeFixture(t)
	if _, _, e := m.PreparePaused(context.Background(), TaskRequest{ID: "missing", Command: "tidy"}); e == nil {
		t.Fatal("task/prepare accepted no selection")
	}
	info := inspectTask(t, m, "tidy", filepath.Join(root, "a/source.go"), nil)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	request := selectedRequest(info, target, "cancelled-paused", nil)
	_, handoff, e := m.PreparePaused(context.Background(), request)
	if e != nil {
		t.Fatal(e)
	}
	handoff()
	if _, e = m.Cancel(request.ID); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Resume(context.Background(), request.ID); e == nil {
		t.Fatal("cancelled paused task resumed")
	}
	exit := r.wait(t, exited(request.ID))
	if exit.Params["cancelled"] != true {
		t.Fatal(exit)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	request.ID = "cancelled-before-prepare"
	if _, _, e = m.PreparePaused(ctx, request); e != context.Canceled {
		t.Fatal(e)
	}
	m.mu.Lock()
	_, exists := m.tasks[request.ID]
	m.mu.Unlock()
	if exists {
		t.Fatal("cancelled prepare retained a paused task")
	}
}
func TestPreparedTaskCancellationBeforeReplyHandoffReleasesOnlyACancelledGate(t *testing.T) {
	m, r, root, _ := taskScopeFixture(t)
	info := inspectTask(t, m, "tidy", filepath.Join(root, "a/source.go"), nil)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	request := selectedRequest(info, target, "cancel-before-handoff", nil)
	ctx, cancel := context.WithCancel(context.Background())
	_, handoff, e := m.PreparePaused(ctx, request)
	if e != nil {
		t.Fatal(e)
	}
	cancel()
	handoff()
	exit := r.wait(t, exited(request.ID))
	if exit.Params["cancelled"] != true {
		t.Fatal(exit)
	}
	if _, e = m.Resume(context.Background(), request.ID); e == nil {
		t.Fatal("cancelled handoff resumed")
	}
	for _, event := range r.snapshot() {
		if event.Method == "task/step" {
			t.Fatal("cancelled handoff executed a task", event)
		}
	}
}
func TestOnlyOneConcurrentResumeCanAcceptThePreparedTask(t *testing.T) {
	m, r, root, _ := taskScopeFixture(t)
	info := inspectTask(t, m, "vet", filepath.Join(root, "a/source.go"), nil)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	request := selectedRequest(info, target, "concurrent-resume", nil)
	_, handoff, e := m.PreparePaused(context.Background(), request)
	if e != nil {
		t.Fatal(e)
	}
	handoff()
	results := make(chan error, 2)
	begin := make(chan struct{})
	for i := 0; i < 2; i++ {
		go func() { <-begin; _, err := m.Resume(context.Background(), request.ID); results <- err }()
	}
	close(begin)
	successes := 0
	for i := 0; i < 2; i++ {
		if <-results == nil {
			successes++
		}
	}
	if successes != 1 {
		t.Fatal("concurrent resume accepted task more than once", successes)
	}
	exit := r.wait(t, exited(request.ID))
	if exit.Params["code"] != float64(0) {
		t.Fatal(exit)
	}
	steps := 0
	for _, event := range r.snapshot() {
		if event.Method == "task/step" {
			steps++
		}
	}
	if steps != 1 {
		t.Fatal("concurrent resume executed multiple commands", steps)
	}
}
