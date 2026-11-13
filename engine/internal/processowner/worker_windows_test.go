//go:build windows

package processowner

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

type processIdentity struct {
	PID     uint32 `json:"pid"`
	Created uint64 `json:"created"`
}

type pipeResult struct {
	data []byte
	err  error
}

type ownerControl struct {
	command  *exec.Cmd
	input    io.WriteCloser
	done     chan error
	finished chan struct{}
	stdout   chan pipeResult
	stderr   chan pipeResult
}

// This entry executes in an owned copy of the actual Go test executable. It
// avoids scripts/interpreters and exercises the real Windows API implementation.
func TestOwnedCommandFixture(t *testing.T) {
	var args []string
	for index, argument := range os.Args {
		if argument == "--" {
			args = os.Args[index+1:]
			break
		}
	}
	if len(args) == 0 {
		return
	}
	fail := func(err error) {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(99)
		}
	}
	switch args[0] {
	case "owner":
		handled, code, err := WorkerMain(append([]string{workerFlag, "--"}, args[1:]...))
		if !handled {
			fail(errors.New("owner fixture was not handled"))
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
		}
		os.Exit(code)
	case "sleeper":
		fail(writeIdentity(args[1], currentIdentity()))
		time.Sleep(30 * time.Second)
		os.Exit(0)
	case "parent":
		root := args[1]
		fail(writeIdentity(filepath.Join(root, "parent.json"), currentIdentity()))
		child := fixtureCommand("sleeper", filepath.Join(root, "child.json"))
		child.Stdout, child.Stderr = os.Stdout, os.Stderr
		fail(child.Start())
		deadline := time.Now().Add(5 * time.Second)
		for {
			if _, err := os.Stat(filepath.Join(root, "child.json")); err == nil {
				break
			}
			if time.Now().After(deadline) {
				_ = child.Process.Kill()
				_ = child.Wait()
				fail(errors.New("owned child did not become ready"))
			}
			time.Sleep(5 * time.Millisecond)
		}
		fail(os.WriteFile(filepath.Join(root, "ready"), []byte("ready"), 0600))
		if args[2] == "orphan" {
			os.Exit(0) // Deliberately retain the child's inherited output handles.
		}
		fail(child.Wait())
		os.Exit(0)
	case "echo":
		input, err := io.ReadAll(os.Stdin)
		fail(err)
		cwd, err := os.Getwd()
		fail(err)
		fail(json.NewEncoder(os.Stdout).Encode(struct {
			Args  []string `json:"args"`
			Env   string   `json:"env"`
			Cwd   string   `json:"cwd"`
			Input string   `json:"input"`
		}{args[1:], os.Getenv("OPENGOLAND_OWNER_CONTROL_VALUE"), cwd, string(input)}))
		fmt.Fprint(os.Stderr, "separate stderr 中文😀")
		os.Exit(7)
	default:
		fail(fmt.Errorf("unknown fixture mode %q", args[0]))
	}
}

func fixtureCommand(arguments ...string) *exec.Cmd {
	path, err := os.Executable()
	if err != nil {
		panic(err)
	}
	return exec.Command(path, append([]string{"-test.run=^TestOwnedCommandFixture$", "--"}, arguments...)...)
}

func currentIdentity() processIdentity {
	current, err := windows.GetCurrentProcess()
	if err != nil {
		panic(err)
	}
	var created, exited, kernel, user windows.Filetime
	if err := windows.GetProcessTimes(current, &created, &exited, &kernel, &user); err != nil {
		panic(err)
	}
	return processIdentity{uint32(os.Getpid()), uint64(created.HighDateTime)<<32 | uint64(created.LowDateTime)}
}

func writeIdentity(path string, value processIdentity) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	if err := os.WriteFile(path+".pending", data, 0600); err != nil {
		return err
	}
	return os.Rename(path+".pending", path)
}

// Retain a kernel handle and compare creation identity before observing/killing
// a helper. A recycled numeric PID must never select another process.
func matchingHandle(identity processIdentity, access uint32) (windows.Handle, error) {
	handle, err := windows.OpenProcess(access|windows.PROCESS_QUERY_LIMITED_INFORMATION, false, identity.PID)
	if errors.Is(err, windows.ERROR_INVALID_PARAMETER) {
		return 0, nil // The process has already been fully reaped.
	}
	if err != nil {
		return 0, err
	}
	var created, exited, kernel, user windows.Filetime
	if err := windows.GetProcessTimes(handle, &created, &exited, &kernel, &user); err != nil {
		windows.CloseHandle(handle)
		return 0, err
	}
	if uint64(created.HighDateTime)<<32|uint64(created.LowDateTime) != identity.Created {
		windows.CloseHandle(handle)
		return 0, nil
	}
	return handle, nil
}

func helperAlive(t *testing.T, identity processIdentity) bool {
	t.Helper()
	handle, err := matchingHandle(identity, windows.SYNCHRONIZE)
	if err != nil {
		t.Fatal(err)
	}
	if handle == 0 {
		return false
	}
	defer windows.CloseHandle(handle)
	state, err := windows.WaitForSingleObject(handle, 0)
	if err != nil {
		t.Fatal(err)
	}
	return state == uint32(windows.WAIT_TIMEOUT)
}

func waitIdentity(t *testing.T, path string) processIdentity {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var lastError error
	for time.Now().Before(deadline) {
		data, err := os.ReadFile(path)
		if err == nil {
			var identity processIdentity
			if err := json.Unmarshal(data, &identity); err != nil {
				t.Fatal(err)
			}
			return identity
		}
		// Publication is atomic, but Windows can briefly deny an open with a
		// sharing violation even after the final filename becomes visible. The
		// readiness deadline remains five seconds; cleanup/liveness gates below
		// are unchanged. Other read errors and malformed identities still fail.
		if !errors.Is(err, os.ErrNotExist) && !errors.Is(err, windows.ERROR_SHARING_VIOLATION) {
			t.Fatal(err)
		}
		lastError = err
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("helper readiness missing: %s: %v", path, lastError)
	return processIdentity{}
}

func TestReadinessWaitsForARealWindowsSharingLock(t *testing.T) {
	path := filepath.Join(t.TempDir(), "identity.json")
	want := currentIdentity()
	if err := writeIdentity(path, want); err != nil {
		t.Fatal(err)
	}
	widePath, err := windows.UTF16PtrFromString(path)
	if err != nil {
		t.Fatal(err)
	}
	handle, err := windows.CreateFile(widePath, windows.GENERIC_READ, 0, nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		t.Fatal(err)
	}
	release := make(chan struct{})
	closed := make(chan error, 1)
	go func() {
		<-release
		time.Sleep(100 * time.Millisecond)
		closed <- windows.CloseHandle(handle)
	}()
	released := false
	defer func() {
		if !released {
			close(release)
		}
		if err := <-closed; err != nil {
			t.Error(err)
		}
	}()
	if _, err := os.ReadFile(path); !errors.Is(err, windows.ERROR_SHARING_VIOLATION) {
		t.Fatalf("fixture must first establish an actual sharing violation, got %v", err)
	}
	released = true
	close(release)
	if got := waitIdentity(t, path); got != want {
		t.Fatalf("identity changed while waiting for file sharing: got %+v, want %+v", got, want)
	}
}

func startOwner(t *testing.T, directory string, commandArgs ...string) *ownerControl {
	t.Helper()
	command := fixtureCommand(append([]string{"owner"}, commandArgs...)...)
	command.Dir = directory
	command.Env = append(os.Environ(), "OPENGOLAND_OWNER_CONTROL_VALUE=value=中文😀")
	input, err := command.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	readers := make([]*os.File, 2)
	writers := make([]*os.File, 2)
	for index := range readers {
		readers[index], writers[index], err = os.Pipe()
		if err != nil {
			t.Fatal(err)
		}
	}
	command.Stdout, command.Stderr = writers[0], writers[1]
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	for _, writer := range writers {
		writer.Close()
	}
	control := &ownerControl{command: command, input: input, done: make(chan error, 1), finished: make(chan struct{}), stdout: make(chan pipeResult, 1), stderr: make(chan pipeResult, 1)}
	go func() { control.done <- command.Wait(); close(control.finished) }()
	for index, output := range []chan pipeResult{control.stdout, control.stderr} {
		reader := readers[index]
		go func() {
			data, err := io.ReadAll(reader)
			output <- pipeResult{data, err}
		}()
	}
	t.Cleanup(func() {
		input.Close()
		_ = command.Process.Kill()
		select {
		case <-control.finished:
		case <-time.After(5 * time.Second):
			t.Error("failed to reap the owned owner process before fixture cleanup")
		}
		// Failure recovery is separate from the assertions. Only exact helper
		// identities recorded inside this private directory may be terminated.
		for _, name := range []string{"parent.json", "child.json"} {
			if data, err := os.ReadFile(filepath.Join(directory, name)); err == nil {
				var identity processIdentity
				if json.Unmarshal(data, &identity) == nil {
					handle, err := matchingHandle(identity, windows.PROCESS_TERMINATE|windows.SYNCHRONIZE)
					if err == nil && handle != 0 {
						_ = windows.TerminateProcess(handle, 98)
						_, _ = windows.WaitForSingleObject(handle, 3000)
						windows.CloseHandle(handle)
					}
				}
			}
		}
		for _, reader := range readers {
			reader.Close()
		}
	})
	return control
}

func (control *ownerControl) wait(t *testing.T) error {
	t.Helper()
	select {
	case err := <-control.done:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("owner did not exit promptly; a helper's natural 30-second exit is not cleanup")
		return nil
	}
}

func pipeBytes(t *testing.T, result <-chan pipeResult) []byte {
	t.Helper()
	select {
	case value := <-result:
		if value.err != nil {
			t.Fatal(value.err)
		}
		return value.data
	case <-time.After(5 * time.Second):
		t.Fatal("an owned descendant still holds the command output pipe")
		return nil
	}
}

func startTree(t *testing.T, mode string) (string, *ownerControl, processIdentity) {
	t.Helper()
	root := t.TempDir()
	sentinel := fixtureCommand("sleeper", filepath.Join(root, "sentinel.json"))
	if err := sentinel.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sentinel.Process.Kill(); _ = sentinel.Wait() })
	identity := waitIdentity(t, filepath.Join(root, "sentinel.json"))
	if !helperAlive(t, identity) {
		t.Fatal("unrelated sentinel must be live before starting the owner")
	}
	self, _ := os.Executable()
	control := startOwner(t, root, self, "-test.run=^TestOwnedCommandFixture$", "--", "parent", root, mode)
	return root, control, identity
}

func requireTreeStopped(t *testing.T, root string, control *ownerControl, sentinel processIdentity) {
	t.Helper()
	pipeBytes(t, control.stdout)
	pipeBytes(t, control.stderr)
	for _, name := range []string{"parent.json", "child.json"} {
		identity := waitIdentity(t, filepath.Join(root, name))
		deadline := time.Now().Add(3 * time.Second)
		for helperAlive(t, identity) && time.Now().Before(deadline) {
			time.Sleep(5 * time.Millisecond)
		}
		if helperAlive(t, identity) {
			t.Fatalf("owned helper remains after owner exit: %+v", identity)
		}
	}
	if !helperAlive(t, sentinel) {
		t.Fatal("unrelated live sentinel must not be terminated")
	}
}

func TestCompletedLauncherCannotLeaveItsChildOrPipes(t *testing.T) {
	root, control, sentinel := startTree(t, "orphan")
	if err := control.wait(t); err != nil {
		t.Fatal(err)
	}
	requireTreeStopped(t, root, control, sentinel)
}

func TestKillingOwnerStopsAlreadyStartedChildrenAndPipes(t *testing.T) {
	root, control, sentinel := startTree(t, "hold")
	for _, name := range []string{"parent.json", "child.json"} {
		identity := waitIdentity(t, filepath.Join(root, name))
		if !helperAlive(t, identity) {
			t.Fatal("owned helper must be live before killing owner")
		}
	}
	if err := control.command.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	if err := control.wait(t); err == nil {
		t.Fatal("killed owner must not be reported as natural success")
	}
	requireTreeStopped(t, root, control, sentinel)
}

func TestParentLifetimePipeEOFStopsOwnedChildrenAndPipes(t *testing.T) {
	root, control, sentinel := startTree(t, "hold")
	for _, name := range []string{"parent.json", "child.json"} {
		if !helperAlive(t, waitIdentity(t, filepath.Join(root, name))) {
			t.Fatal("owned helper must be live before lifetime pipe closes")
		}
	}
	if err := control.input.Close(); err != nil {
		t.Fatal(err)
	}
	_ = control.wait(t) // A lifetime cancellation is not a natural target exit.
	requireTreeStopped(t, root, control, sentinel)
}

func TestOwnedCommandPreservesArgumentsEnvironmentDirectoryStreamsAndStatus(t *testing.T) {
	root := filepath.Join(t.TempDir(), "工程 space😀")
	if err := os.Mkdir(root, 0700); err != nil {
		t.Fatal(err)
	}
	self, _ := os.Executable()
	args := []string{"space value", "中文😀", "", `quoted"value`, `trailing\`}
	control := startOwner(t, root, append([]string{self, "-test.run=^TestOwnedCommandFixture$", "--", "echo"}, args...)...)
	err := control.wait(t)
	var exit *exec.ExitError
	if !errors.As(err, &exit) || exit.ExitCode() != 7 {
		t.Fatalf("expected original target exit7, got %v; stderr=%s", err, pipeBytes(t, control.stderr))
	}
	var got struct {
		Args  []string `json:"args"`
		Env   string   `json:"env"`
		Cwd   string   `json:"cwd"`
		Input string   `json:"input"`
	}
	if err := json.Unmarshal(pipeBytes(t, control.stdout), &got); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(got.Args, args) || got.Env != "value=中文😀" || !strings.EqualFold(filepath.Clean(got.Cwd), filepath.Clean(root)) || got.Input != "" {
		t.Fatalf("command context changed: %+v", got)
	}
	if stderr := string(pipeBytes(t, control.stderr)); stderr != "separate stderr 中文😀" {
		t.Fatalf("stderr changed or merged: %q", stderr)
	}
}
