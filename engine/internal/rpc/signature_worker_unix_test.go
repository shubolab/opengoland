//go:build !windows

package rpc

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func quoteShell(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
func stalledAnalysis(t *testing.T) (signature.Request, string) {
	t.Helper()
	root := t.TempDir()
	root, _ = filepath.EvalSymlinks(root)
	actual, err := exec.LookPath("go")
	if err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(root, "go.mod"), []byte("module example.org/ownedworker\n\ngo 1.24.0\n"), 0600)
	_ = os.WriteFile(filepath.Join(root, "main.go"), []byte("package main\nfunc Target(a int)int{return a}\n"), 0600)
	bin := filepath.Join(root, "tool")
	_ = os.MkdirAll(bin, 0700)
	pidfile := filepath.Join(root, "driver-child.pid")
	wrapper := filepath.Join(bin, "go")
	script := "#!/bin/sh\ncase \" $* \" in *\" list \"*) sleep 300 &\nchild=$!\nprintf '%s\\n' \"$child\" > " + quoteShell(pidfile) + "\nwait \"$child\";;\n*) exec " + quoteShell(actual) + " \"$@\";;\nesac\n"
	if err = os.WriteFile(wrapper, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin+string(filepath.ListSeparator)+os.Getenv("PATH"))
	return signature.Request{OperationID: "owned-analysis", Root: root, GoExecutable: wrapper, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}, Target: signature.Target{URI: "file://" + filepath.ToSlash(filepath.Join(root, "main.go")), Position: signature.Position{Line: 1, Character: 7}}}, pidfile
}
func waitOwnedChild(t *testing.T, pidfile string) int {
	t.Helper()
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(pidfile)
		pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
		if err == nil && pid > 0 {
			if err = syscall.Kill(pid, 0); err == nil {
				return pid
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("analysis driver did not start its owned live child")
	return 0
}
func waitOwnedExit(t *testing.T, pid int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		out, err := exec.Command("ps", "-p", strconv.Itoa(pid), "-o", "stat=").Output()
		if err != nil || strings.Contains(string(out), "Z") {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("owned analysis descendant %d remained active", pid)
}
func TestSignatureWorkerCancellationStopsLiveDriverDescendant(t *testing.T) {
	req, pidfile := stalledAnalysis(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := signature.RunIsolated(ctx, "inspect", req); done <- err }()
	pid := waitOwnedChild(t, pidfile)
	cancel()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("cancel reply=%v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cancel did not reap worker")
	}
	waitOwnedExit(t, pid)
}
func TestSignatureWorkerParentDeathStopsLiveDriverDescendant(t *testing.T) {
	req, pidfile := stalledAnalysis(t)
	data, _ := json.Marshal(req)
	requestFile := filepath.Join(req.Root, "request.json")
	_ = os.WriteFile(requestFile, data, 0600)
	executable, _ := os.Executable()
	cmd := exec.Command(executable, "--test-signature-parent", requestFile)
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
	pid := waitOwnedChild(t, pidfile)
	if err := cmd.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = cmd.Wait()
	waitOwnedExit(t, pid)
}
func runSignatureParentProbe() bool {
	if len(os.Args) != 3 || os.Args[1] != "--test-signature-parent" {
		return false
	}
	data, err := os.ReadFile(os.Args[2])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	var req signature.Request
	if err = json.Unmarshal(data, &req); err != nil {
		os.Exit(2)
	}
	_, _ = signature.RunIsolated(context.Background(), "inspect", req)
	return true
}

func TestSignatureServerShutdownStopsLiveDriverDescendant(t *testing.T) {
	req, pidfile := stalledAnalysis(t)
	s := New(os.Stdout)
	s.signatureRoot = req.Root
	s.signatureGo = req.GoExecutable
	ctx, finish, err := s.signatureContext(&req)
	if err != nil {
		t.Fatal(err)
	}
	defer finish()
	done := make(chan error, 1)
	go func() { _, err := signature.RunIsolated(ctx, "inspect", req); done <- err }()
	pid := waitOwnedChild(t, pidfile)
	s.stop()
	select {
	case err := <-done:
		if err != context.Canceled {
			t.Fatalf("shutdown reply=%v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("shutdown did not reap worker")
	}
	waitOwnedExit(t, pid)
}
