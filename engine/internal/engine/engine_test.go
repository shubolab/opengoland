package engine

import (
	"context"
	"encoding/json"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type observation struct {
	Method string
	Params map[string]any
}

type recorder struct {
	mu      sync.Mutex
	events  []observation
	changes chan struct{}
}

func newRecorder() *recorder { return &recorder{changes: make(chan struct{}, 1024)} }

func (r *recorder) notify(method string, params any) {
	data, _ := json.Marshal(params)
	var p map[string]any
	_ = json.Unmarshal(data, &p)
	r.mu.Lock()
	r.events = append(r.events, observation{method, p})
	r.mu.Unlock()
	select {
	case r.changes <- struct{}{}:
	default:
	}
}

func (r *recorder) snapshot() []observation {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]observation(nil), r.events...)
}

func (r *recorder) wait(t *testing.T, predicate func(observation) bool) observation {
	t.Helper()
	timeout := time.After(45 * time.Second)
	for {
		for _, event := range r.snapshot() {
			if predicate(event) {
				return event
			}
		}
		select {
		case <-r.changes:
		case <-timeout:
			t.Fatal("timed out waiting for event", r.snapshot())
		}
	}
}

func fixture(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	err := filepath.WalkDir("../../testdata/workspace", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, _ := filepath.Rel("../../testdata/workspace", path)
		target := filepath.Join(root, rel)
		if d.IsDir() {
			return os.MkdirAll(target, 0755)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(target, data, 0644)
	})
	if err != nil {
		t.Fatal(err)
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		t.Fatal(err)
	}
	return root
}

func newManager(t *testing.T) (*Manager, *recorder, string) {
	t.Helper()
	t.Setenv("GOTOOLCHAIN", "local")
	t.Setenv("GOWORK", "")
	root := fixture(t)
	r := newRecorder()
	m, err := New(root, "", r.notify)
	if err != nil {
		t.Fatal(err)
	}
	m.cacheRoot = t.TempDir()
	t.Cleanup(func() {
		if err := m.Close(); err != nil {
			t.Error(err)
		}
	})
	return m, r, root
}

func TestWorkspaceMetadataAndDirectoryConfinement(t *testing.T) {
	m, _, root := newManager(t)
	info := m.Project(context.Background())
	if len(info.Modules) != 2 || len(info.ModuleRoots) != 2 || info.Workspace != filepath.Join(root, "go.work") {
		t.Fatalf("unexpected workspace metadata: %+v", info)
	}
	if _, err := m.resolveCWD(filepath.Join(root, "app")); err != nil {
		t.Fatal(err)
	}
	outside := t.TempDir()
	if _, err := m.resolveCWD(outside); err == nil {
		t.Fatal("outside directory accepted")
	}
	if err := os.Symlink(outside, filepath.Join(root, "escape")); err == nil {
		if _, err := m.resolveCWD("escape"); err == nil {
			t.Fatal("symlink escaped confinement")
		}
	}
	if _, err := m.resolveCWD("../"); err == nil {
		t.Fatal("parent traversal accepted")
	}
}

func start(t *testing.T, m *Manager, req TaskRequest) {
	t.Helper()
	_, resume, err := m.Prepare(req)
	if err != nil {
		t.Fatal(err)
	}
	resume()
}

func exited(id string) func(observation) bool {
	return func(e observation) bool { return e.Method == "task/exit" && e.Params["id"] == id }
}

func TestRealGoWorkspaceBuildAndTests(t *testing.T) {
	m, r, _ := newManager(t)
	m.Project(context.Background())
	start(t, m, TaskRequest{ID: "build", Command: "build"})
	if e := r.wait(t, exited("build")); e.Params["code"] != float64(0) {
		t.Fatal(e)
	}
	start(t, m, TaskRequest{ID: "pass", Command: "test"})
	if e := r.wait(t, exited("pass")); e.Params["code"] != float64(0) {
		t.Fatal(e)
	}
	packages := map[string]bool{}
	for _, e := range r.snapshot() {
		if e.Method == "test/event" && e.Params["id"] == "pass" {
			v := e.Params["event"].(map[string]any)
			if v["Action"] == "pass" {
				packages[v["Package"].(string)] = true
			}
		}
	}
	if !packages["example.org/openidea/app"] || !packages["example.org/openidea/library"] {
		t.Fatal("missing module test results", packages)
	}
	start(t, m, TaskRequest{ID: "fail", Command: "test", CWD: "app", Env: map[string]string{"OPENIDEA_TEST_FAIL": "1"}})
	if e := r.wait(t, exited("fail")); e.Params["code"] == float64(0) {
		t.Fatal("deliberately failing test passed")
	}
	r.wait(t, func(e observation) bool {
		if e.Method != "test/event" || e.Params["id"] != "fail" {
			return false
		}
		v := e.Params["event"].(map[string]any)
		return v["Action"] == "fail" && v["Test"] == "TestApplication/controlledFailure"
	})
}

func TestRealCoverageProfileAndBenchmark(t *testing.T) {
	m, r, _ := newManager(t)
	start(t, m, TaskRequest{ID: "coverage", Command: "coverage", CWD: "library"})
	e := r.wait(t, exited("coverage"))
	if e.Params["code"] != float64(0) {
		t.Fatal(e)
	}
	profile, ok := e.Params["coverageProfile"].(string)
	if !ok {
		t.Fatal("no coverage profile", e)
	}
	data, err := os.ReadFile(profile)
	if err != nil || !strings.HasPrefix(string(data), "mode:") || !strings.Contains(string(data), "greet.go") {
		t.Fatalf("bad coverage %s %v", data, err)
	}
	start(t, m, TaskRequest{ID: "benchmark", Command: "benchmark", CWD: "app", Args: []string{"-benchtime=1x", "."}})
	if e := r.wait(t, exited("benchmark")); e.Params["code"] != float64(0) {
		t.Fatal(e)
	}
	r.wait(t, func(e observation) bool {
		return e.Method == "task/output" && e.Params["id"] == "benchmark" && strings.Contains(e.Params["text"].(string), "BenchmarkApplication")
	})
}

func TestCancelBeforeAcceptedTaskIsResumed(t *testing.T) {
	m, r, _ := newManager(t)
	_, resume, err := m.Prepare(TaskRequest{ID: "cancel", Command: "run"})
	if err != nil {
		t.Fatal(err)
	}
	if accepted, err := m.Cancel("cancel"); !accepted || err != nil {
		t.Fatal(accepted, err)
	}
	resume()
	if e := r.wait(t, exited("cancel")); e.Params["cancelled"] != true {
		t.Fatal(e)
	}
	for _, e := range r.snapshot() {
		if e.Method == "task/step" {
			t.Fatal("cancelled task spawned a process")
		}
	}
}

func TestTaskValidationAndIDReservation(t *testing.T) {
	m, _, _ := newManager(t)
	for _, req := range []TaskRequest{
		{ID: "", Command: "build"}, {ID: "bad", Command: "shell"}, {ID: "escape", Command: "build", CWD: "/"},
		{ID: "env", Command: "build", Env: map[string]string{"BAD=NAME": "x"}},
		{ID: "args", Command: "build", Args: []string{"\x00"}}, {ID: "json", Command: "test", Args: []string{"-json=false"}},
	} {
		if _, _, err := m.Prepare(req); err == nil {
			t.Fatal("accepted invalid request", req)
		}
	}
	_, _, err := m.Prepare(TaskRequest{ID: "same", Command: "run"})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := m.Prepare(TaskRequest{ID: "same", Command: "run"}); err == nil {
		t.Fatal("duplicate live task id accepted")
	}
}

func TestStreamingUnicodeAndFragmentedTestJSON(t *testing.T) {
	r := newRecorder()
	w := &streamOutput{id: "unicode", stream: "stdout", notify: r.notify, tests: true}
	line := []byte("{\"Action\":\"output\",\"Output\":\"你好😀\"}\n")
	for _, b := range line {
		_, _ = w.Write([]byte{b})
	}
	w.Flush()
	var text strings.Builder
	events := 0
	for _, e := range r.snapshot() {
		if e.Method == "task/output" {
			text.WriteString(e.Params["text"].(string))
		}
		if e.Method == "test/event" {
			events++
		}
	}
	if text.String() != string(line) || events != 1 {
		t.Fatal(text.String(), events)
	}
}
