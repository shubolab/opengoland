//go:build !windows

package native

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestOwnedNativeWorkerCancellationStopsItsMetadataSubprocess(t *testing.T) {
	req := allRequest(t, "package fixture\nfunc F(value int)int{return value+1}\n", "extract.variable", "F")
	receipt := filepath.Join(req.Root, "owned-sdk.pid")
	bin := filepath.Join(req.Root, "owned-sdk", "bin")
	if err := os.MkdirAll(bin, 0700); err != nil {
		t.Fatal(err)
	}
	sdk := filepath.Join(bin, "go")
	t.Setenv("PATH", bin+string(os.PathListSeparator)+os.Getenv("PATH"))
	// This is our own isolated fixture, never the workspace's or user's tool.
	script := "#!/bin/sh\nprintf '%s\\n' \"$$\" > '" + strings.ReplaceAll(receipt, "'", "'\\''") + "'\nexec /bin/sleep 60\n"
	if err := os.WriteFile(sdk, []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	req.GoExecutable = sdk
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := RunIsolated(ctx, "inspect", req); done <- err }()
	var pid int
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if data, err := os.ReadFile(receipt); err == nil {
			pid, _ = strconv.Atoi(strings.TrimSpace(string(data)))
			if pid > 0 {
				break
			}
		}
		select {
		case err := <-done:
			t.Fatalf("owned worker exited before the SDK fixture started: %v", err)
		default:
		}
		time.Sleep(5 * time.Millisecond)
	}
	if pid <= 0 {
		t.Fatal("owned SDK process did not start")
	}
	defer func() {
		if syscall.Kill(pid, 0) == nil {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		}
	}()
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("cancellation lost: %v", err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("owned worker did not terminate")
	}
	deadline = time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if errors.Is(syscall.Kill(pid, 0), syscall.ESRCH) {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("the owned metadata subprocess %d survived worker cancellation", pid)
}
