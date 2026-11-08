//go:build !windows

package jsonstruct

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func shellQuote(text string) string { return "'" + strings.ReplaceAll(text, "'", "'\\''") + "'" }
func TestJSONGenerationWorkerCancellationReapsItsActualGoDriverDescendant(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{Value int}\n"})
	actual, err := exec.LookPath("go")
	if err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(req.Root, "tool")
	if err = os.Mkdir(bin, 0700); err != nil {
		t.Fatal(err)
	}
	pidfile := filepath.Join(req.Root, "owned-driver.pid")
	wrapper := filepath.Join(bin, "go")
	script := "#!/bin/sh\ncase \" $* \" in *\" list \"*) sleep 60 &\nchild=$!\nprintf '%s\\n' \"$child\" > " + shellQuote(pidfile) + "\nwait \"$child\";;\n*) exec " + shellQuote(actual) + " \"$@\";;\nesac\n"
	if err = os.WriteFile(wrapper, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin+string(filepath.ListSeparator)+os.Getenv("PATH"))
	req.GoExecutable = wrapper
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() { _, e := RunIsolated(ctx, "inspect", req); done <- e }()
	pid := 0
	deadline := time.Now().Add(7 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(pidfile)
		pid, _ = strconv.Atoi(strings.TrimSpace(string(data)))
		if pid > 0 && syscall.Kill(pid, 0) == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if pid <= 0 {
		t.Fatal("the owned Go driver did not start")
	}
	cancel()
	select {
	case err = <-done:
		if err != context.Canceled {
			t.Fatalf("cancellation result: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("worker did not exit")
	}
	deadline = time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		state, e := exec.Command("ps", "-p", strconv.Itoa(pid), "-o", "stat=").Output()
		if e != nil || strings.Contains(string(state), "Z") {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("owned Go descendant %d survived cancellation", pid)
}
