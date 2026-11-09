//go:build !windows

package engine

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func quoteTaskShell(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
func TestTaskTargetsSelectedSDKReceivesExactEnvironmentAndArgumentScope(t *testing.T) {
	real, _, root, _ := taskScopeFixture(t)
	active, e := real.taskGoEnvironment(context.Background(), filepath.Join(root, "a"), nil)
	if e != nil {
		t.Fatal(e)
	}
	active.GOWORK = "off"
	body, _ := json.Marshal(active)
	script := filepath.Join(root, "selected-sdk")
	log := filepath.Join(root, "sdk.log")
	text := "#!/bin/sh\nif [ \"$1\" = env ]; then printf '%s\\n' " + quoteTaskShell(string(body)) + ";exit 0;fi\nprintf '%s|%s|%s|%s\\n' \"$GOTOOLCHAIN\" \"$GOFLAGS\" \"$PWD\" \"$*\" >> " + quoteTaskShell(log) + "\n"
	if e = os.WriteFile(script, []byte(text), 0700); e != nil {
		t.Fatal(e)
	}
	r := newRecorder()
	m, e := New(root, script, r.notify)
	if e != nil {
		t.Fatal(e)
	}
	defer m.Close()
	env := map[string]string{"GOTOOLCHAIN": "off", "GOWORK": "off", "GOFLAGS": "-tags=-Cscope"}
	info := inspectTask(t, m, "vet", filepath.Join(root, "a/source.go"), env)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	request := selectedRequest(info, target, "exact-env", env)
	request.Args = []string{"-tags", "one,two"}
	start(t, m, request)
	exit := r.wait(t, exited(request.ID))
	if exit.Params["code"] != float64(0) {
		t.Fatal(exit)
	}
	data, e := os.ReadFile(log)
	if e != nil {
		t.Fatal(e)
	}
	expected := "off|-tags=-Cscope|" + filepath.Join(root, "a") + "|vet -tags one,two ./..."
	if strings.TrimSpace(string(data)) != expected {
		t.Fatalf("SDK env/argv changed: %s", data)
	}
}
func TestTaskTargetInspectionCancellationKillsOnlyItsOwnedSDKDescendant(t *testing.T) {
	real, _, root, _ := taskScopeFixture(t)
	script := filepath.Join(root, "waiting-sdk")
	pidfile := filepath.Join(root, "owned-inspection.pid")
	text := "#!/bin/sh\nsleep 60 &\nchild=$!\nprintf '%s\\n' \"$child\" > " + quoteTaskShell(pidfile) + "\nwait \"$child\"\n"
	if e := os.WriteFile(script, []byte(text), 0700); e != nil {
		t.Fatal(e)
	}
	m, e := New(root, script, nil)
	if e != nil {
		t.Fatal(e)
	}
	defer m.Close()
	_ = real
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := m.TaskTargets(ctx, TaskTargetsRequest{Command: "tidy", ContextPath: filepath.Join(root, "a")})
		done <- err
	}()
	pid := 0
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(pidfile)
		pid, _ = strconv.Atoi(strings.TrimSpace(string(data)))
		if pid > 0 && syscall.Kill(pid, 0) == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if pid <= 0 {
		t.Fatal("owned SDK child did not start")
	}
	cancel()
	select {
	case e = <-done:
		if e != context.Canceled {
			t.Fatal(e)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cancelled inspection did not exit")
	}
	deadline = time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		if e = syscall.Kill(pid, 0); e != nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("owned SDK descendant %d survived cancellation", pid)
}
func TestResumePerformsNoSDKProbeAndCancellationDuringPrepareLeavesNoPausedTask(t *testing.T) {
	real, _, root, _ := taskScopeFixture(t)
	script := filepath.Join(root, "two-phase-sdk")
	gate := filepath.Join(root, "hold-probe")
	pidfile := filepath.Join(root, "owned-prepare.pid")
	probeLog := filepath.Join(root, "unexpected-probe")
	text := "#!/bin/sh\nif [ \"$1\" = env ] && [ -f " + quoteTaskShell(gate) + " ]; then\nif [ \"$(cat " + quoteTaskShell(gate) + ")\" = deny ];then echo unexpected > " + quoteTaskShell(probeLog) + ";exit 99;fi\nsleep 60 &\nchild=$!\nprintf '%s\\n' \"$child\" > " + quoteTaskShell(pidfile) + "\nwait \"$child\";exit 1\nfi\nexec " + quoteTaskShell(real.goExecutable) + " \"$@\"\n"
	if e := os.WriteFile(script, []byte(text), 0700); e != nil {
		t.Fatal(e)
	}
	r := newRecorder()
	m, e := New(root, script, r.notify)
	if e != nil {
		t.Fatal(e)
	}
	defer m.Close()
	env := map[string]string{"GOWORK": "off"}
	source := filepath.Join(root, "a/source.go")
	info := inspectTask(t, m, "vet", source, env)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	request := selectedRequest(info, target, "resume-no-probe", env)
	_, handoff, e := m.PreparePaused(context.Background(), request)
	if e != nil {
		t.Fatal(e)
	}
	handoff()
	if e = os.WriteFile(gate, []byte("deny"), 0600); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Resume(context.Background(), request.ID); e != nil {
		t.Fatal(e)
	}
	exit := r.wait(t, exited(request.ID))
	if exit.Params["code"] != float64(0) {
		t.Fatal(exit, r.snapshot())
	}
	if _, e = os.Stat(probeLog); !os.IsNotExist(e) {
		t.Fatal("resume launched an SDK metadata probe", e)
	}
	os.Remove(gate)
	info = inspectTask(t, m, "vet", source, env)
	target = taskTarget(t, info, "module", filepath.Join(root, "a"))
	request = selectedRequest(info, target, "cancel-prepare-probe", env)
	if e = os.WriteFile(gate, []byte("hold"), 0600); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() { _, _, err := m.PreparePaused(ctx, request); done <- err }()
	pid := 0
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(pidfile)
		pid, _ = strconv.Atoi(strings.TrimSpace(string(data)))
		if pid > 0 && syscall.Kill(pid, 0) == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if pid <= 0 {
		t.Fatal("prepare did not enter owned SDK probe")
	}
	cancel()
	select {
	case e = <-done:
		if e != context.Canceled {
			t.Fatal(e)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cancelled preparation did not return")
	}
	m.mu.Lock()
	_, exists := m.tasks[request.ID]
	m.mu.Unlock()
	if exists {
		t.Fatal("cancelled SDK preparation retained a paused task")
	}
	if _, e = m.Resume(context.Background(), request.ID); e == nil {
		t.Fatal("cancelled preparation could be resumed")
	}
	deadline = time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		if e = syscall.Kill(pid, 0); e != nil {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("owned prepare descendant survived cancellation")
}
func TestPreparedTaskSDKIdentityIsRevalidatedBeforeResume(t *testing.T) {
	real, _, root, _ := taskScopeFixture(t)
	script := filepath.Join(root, "guarded-sdk")
	text := "#!/bin/sh\nexec " + quoteTaskShell(real.goExecutable) + " \"$@\"\n"
	if e := os.WriteFile(script, []byte(text), 0700); e != nil {
		t.Fatal(e)
	}
	r := newRecorder()
	m, e := New(root, script, r.notify)
	if e != nil {
		t.Fatal(e)
	}
	defer m.Close()
	info := inspectTask(t, m, "vet", filepath.Join(root, "a/source.go"), nil)
	target := taskTarget(t, info, "module", filepath.Join(root, "a"))
	request := selectedRequest(info, target, "stale-sdk-resume", nil)
	_, handoff, e := m.PreparePaused(context.Background(), request)
	if e != nil {
		t.Fatal(e)
	}
	handoff()
	if e = os.WriteFile(script, []byte(text+"# SDK changed after preparation\n"), 0700); e != nil {
		t.Fatal(e)
	}
	if _, e = m.Resume(context.Background(), request.ID); e == nil {
		t.Fatal("modified owned SDK resumed")
	}
	exit := r.wait(t, exited(request.ID))
	if exit.Params["cancelled"] != true {
		t.Fatal(exit)
	}
	for _, event := range r.snapshot() {
		if event.Method == "task/step" {
			t.Fatal("stale SDK launched task", event)
		}
	}
}
