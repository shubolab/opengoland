//go:build !windows

package engine

import (
	"fmt"
	"os/exec"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestCancelKillsGoRunDescendant(t *testing.T) {
	m, r, _ := newManager(t)
	start(t, m, TaskRequest{ID: "long", Command: "run", CWD: "app", Args: []string{"./cmd/wait"}})
	e := r.wait(t, func(e observation) bool {
		return e.Method == "task/output" && strings.Contains(e.Params["text"].(string), "READY ")
	})
	var pid int
	text := e.Params["text"].(string)
	if _, err := fmt.Sscanf(text[strings.Index(text, "READY "):], "READY %d", &pid); err != nil || pid <= 0 {
		t.Fatal(text, err)
	}
	if ok, err := m.Cancel("long"); !ok || err != nil {
		t.Fatal(ok, err)
	}
	if e := r.wait(t, exited("long")); e.Params["cancelled"] != true {
		t.Fatal(e)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if syscall.Kill(pid, 0) != nil {
			return
		}
		data, _ := exec.Command("ps", "-p", fmt.Sprint(pid), "-o", "stat=").Output()
		if strings.Contains(string(data), "Z") || strings.TrimSpace(string(data)) == "" {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("go run descendant %d survived cancellation", pid)
}
