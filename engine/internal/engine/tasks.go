package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"
)

const Version = "0.1.0-dev"

type Notify func(method string, params any)

type Manager struct {
	root            string
	goExecutable    string
	mu              sync.Mutex
	moduleRoots     []string
	taskTargets     map[string]*taskTargetInspection
	taskTargetOrder []string
	tasks           map[string]*task
	closed          bool
	notify          Notify
	cacheRoot       string
	sequence        atomic.Uint64
}

type task struct {
	id                 string
	plan               []step
	env                []string
	profile            string
	sampling           *ProfileArtifact
	vendor             bool
	stopOnFailure      bool
	cancelled          atomic.Bool
	mu                 sync.Mutex
	cmd                *exec.Cmd
	done               chan struct{}
	gate               chan struct{}
	resume             sync.Once
	manual             bool
	resuming           bool
	resumed            bool
	guard              *taskResumeGuard
	releasePreparation func() error
}

type step struct {
	CWD  string   `json:"cwd"`
	Args []string `json:"args"`
}

type TaskRequest struct {
	ID              string               `json:"id"`
	Command         string               `json:"command"`
	Args            []string             `json:"args"`
	CWD             string               `json:"cwd"`
	Env             map[string]string    `json:"env"`
	Profile         *ProfileOptions      `json:"profile,omitempty"`
	TargetSelection *TaskTargetSelection `json:"targetSelection,omitempty"`
	OperationID     string               `json:"operationId,omitempty"`
}

func New(root, goExecutable string, notify Notify) (*Manager, error) {
	root, err := resolveDirectory(root)
	if err != nil {
		return nil, err
	}
	if goExecutable == "" {
		goExecutable, err = exec.LookPath("go")
	} else {
		goExecutable, err = exec.LookPath(goExecutable)
	}
	if err != nil {
		return nil, fmt.Errorf("Go SDK unavailable: %w", err)
	}
	goExecutable, err = filepath.Abs(goExecutable)
	if err != nil {
		return nil, err
	}
	cache, err := os.UserCacheDir()
	if err != nil {
		cache = os.TempDir()
	}
	if notify == nil {
		notify = func(string, any) {}
	}
	return &Manager{root: root, goExecutable: goExecutable, tasks: map[string]*task{}, notify: notify, cacheRoot: filepath.Join(cache, "openidea-go", "coverage")}, nil
}

func (m *Manager) Prepare(req TaskRequest) (map[string]any, func(), error) {
	return m.PrepareContext(context.Background(), req)
}

func (m *Manager) PrepareContext(ctx context.Context, req TaskRequest) (map[string]any, func(), error) {
	return m.prepareTask(ctx, req, false)
}

// PreparePaused completes SDK/metadata verification but runs no task command.
// Its returned handoff only detaches preparation cancellation after a reply;
// task/resume owns the separate execution gate.
func (m *Manager) PreparePaused(ctx context.Context, req TaskRequest) (map[string]any, func(), error) {
	if req.TargetSelection == nil {
		return nil, nil, errors.New("task/prepare requires a targetSelection")
	}
	return m.prepareTask(ctx, req, true)
}
func (m *Manager) prepareTask(ctx context.Context, req TaskRequest, manual bool) (map[string]any, func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, nil, err
	}
	if req.ID == "" || len(req.ID) > 256 || strings.ContainsRune(req.ID, '\x00') {
		return nil, nil, errors.New("task id must contain 1 to 256 characters")
	}
	var selectedPlan []step
	var guard *taskResumeGuard
	var env []string
	var cwd string
	var err error
	if req.TargetSelection != nil {
		selectedPlan, env, guard, err = m.selectedTaskPlan(ctx, req)
		if err != nil {
			return nil, nil, err
		}
		cwd = selectedPlan[0].CWD
	} else {
		cwd, err = m.resolveCWD(req.CWD)
		if err != nil {
			return nil, nil, err
		}
		env, err = mergeEnvironment(req.Env)
		if err != nil {
			return nil, nil, err
		}
	}
	for _, arg := range req.Args {
		if strings.ContainsRune(arg, '\x00') {
			return nil, nil, errors.New("arguments contain NUL")
		}
	}
	profile := ""
	if req.Command == "coverage" {
		digest := sha256.Sum256([]byte(m.root + "\x00" + req.ID))
		dir := filepath.Join(m.cacheRoot, hex.EncodeToString(digest[:8]))
		if err := os.MkdirAll(dir, 0700); err != nil {
			return nil, nil, err
		}
		profile = filepath.Join(dir, fmt.Sprintf("%d.cover", m.sequence.Add(1)))
	}
	var args []string
	var patterns bool
	var sampling *ProfileArtifact
	if req.Command == "profile" {
		args, sampling, err = m.prepareProfile(req.Profile, req.Args)
	} else if req.Command == "vendor" {
		args = []string{"env", "-json", "GOWORK"}
	} else {
		args, patterns, err = planArguments(req.Command, req.Args, profile)
	}
	if err != nil {
		return nil, nil, err
	}
	plan := []step{{CWD: cwd, Args: args}}
	if req.TargetSelection == nil && patterns && len(req.Args) == 0 && cwd == m.root {
		if _, err := os.Stat(filepath.Join(cwd, "go.mod")); errors.Is(err, os.ErrNotExist) {
			m.mu.Lock()
			roots := append([]string(nil), m.moduleRoots...)
			m.mu.Unlock()
			if len(roots) > 0 {
				if profile != "" && len(roots) > 1 {
					// A single profile cannot represent module-relative import roots reliably.
					return nil, nil, errors.New("select a module working directory for coverage in a multi-module workspace")
				}
				plan = nil
				for _, root := range roots {
					plan = append(plan, step{CWD: root, Args: args})
				}
			}
		}
	}
	if req.TargetSelection != nil {
		plan = selectedPlan
	}
	t := &task{id: req.ID, plan: plan, env: env, profile: profile, sampling: sampling, vendor: req.Command == "vendor" && req.TargetSelection == nil, stopOnFailure: req.TargetSelection != nil, manual: manual, guard: guard, done: make(chan struct{}), gate: make(chan struct{})}
	if t.vendor {
		t.plan = append(t.plan, step{CWD: cwd, Args: append([]string{"vendor"}, req.Args...)})
	}
	m.mu.Lock()
	if m.closed || ctx.Err() != nil {
		m.mu.Unlock()
		if sampling != nil {
			_ = os.RemoveAll(sampling.directory)
		}
		if ctx.Err() != nil {
			return nil, nil, ctx.Err()
		}
		return nil, nil, errors.New("engine is shutting down")
	}
	if _, exists := m.tasks[t.id]; exists {
		m.mu.Unlock()
		if sampling != nil {
			_ = os.RemoveAll(sampling.directory)
		}
		return nil, nil, errors.New("task id is already in use")
	}
	// Install the handoff before publishing the task, so an immediate
	// task/resume after the accepted reply cannot race an unset callback.
	pending := &atomic.Bool{}
	pending.Store(true)
	var releaseOnce sync.Once
	var releaseError error
	var stop func() bool
	release := func() error {
		releaseOnce.Do(func() {
			if !pending.CompareAndSwap(true, false) {
				releaseError = context.Canceled
			} else {
				stop()
				releaseError = ctx.Err()
			}
			if releaseError != nil {
				_, _ = m.Cancel(t.id)
			}
		})
		return releaseError
	}
	stop = context.AfterFunc(ctx, func() {
		if pending.CompareAndSwap(true, false) {
			_, _ = m.Cancel(t.id)
		}
	})
	// Cancellation may start concurrently but waits for this publication lock.
	// Resume never observes an uninitialized handoff or stop callback.
	t.releasePreparation = release
	m.tasks[t.id] = t
	m.mu.Unlock()
	go m.execute(t)
	after := func() {
		if release() != nil {
			return
		}
		if !manual {
			t.resume.Do(func() { close(t.gate) })
		}
	}
	response := map[string]any{"id": req.ID, "accepted": true, "steps": plan}
	if manual {
		response["paused"] = true
	}
	return response, after, nil
}

// Resume does no Go subprocess work. Native callers can therefore perform a
// final cached-document check after preparation without opening another long
// metadata probe window. Disk/source/SDK guards still run before the gate opens.
func (m *Manager) Resume(ctx context.Context, id string) (map[string]any, error) {
	m.mu.Lock()
	t := m.tasks[id]
	closed := m.closed
	m.mu.Unlock()
	if closed {
		return nil, errors.New("engine is shutting down")
	}
	if t == nil {
		return nil, errors.New("unknown, cancelled or completed prepared task")
	}
	if !t.manual {
		return nil, errors.New("task was not prepared for explicit resume")
	}
	if err := t.releasePreparation(); err != nil {
		return nil, err
	}
	t.mu.Lock()
	if t.cancelled.Load() || t.resuming || t.resumed {
		t.mu.Unlock()
		return nil, errors.New("prepared task is cancelled or has already been resumed")
	}
	t.resuming = true
	t.mu.Unlock()
	if err := t.guard.validate(ctx, m, t.plan); err != nil {
		// Publish the rejection before task/exit. Native listeners may finish
		// on that terminal event before the separate RPC error is delivered.
		if !errors.Is(err, context.Canceled) {
			m.notify("task/output", map[string]any{"id": id, "stream": "stderr", "text": err.Error() + "\n"})
		}
		_, _ = m.Cancel(id)
		return nil, err
	}
	m.mu.Lock()
	t.mu.Lock()
	if m.closed || t.cancelled.Load() || ctx.Err() != nil {
		t.mu.Unlock()
		m.mu.Unlock()
		_, _ = m.Cancel(id)
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, errors.New("prepared task was cancelled before resume")
	}
	t.resumed = true
	t.resume.Do(func() { close(t.gate) })
	t.mu.Unlock()
	m.mu.Unlock()
	return map[string]any{"id": id, "resumed": true}, nil
}

func planArguments(command string, args []string, profile string) ([]string, bool, error) {
	var prefix []string
	patterns := false
	switch command {
	case "build", "vet", "generate":
		prefix, patterns = []string{command}, true
	case "run":
		prefix = []string{"run"}
		if len(args) == 0 {
			args = []string{"."}
		}
	case "test", "benchmark", "coverage":
		prefix, patterns = []string{"test", "-json"}, true
		for _, arg := range args {
			if arg == "-json" || strings.HasPrefix(arg, "-json=") || command == "coverage" && (arg == "-coverprofile" || strings.HasPrefix(arg, "-coverprofile=")) {
				return nil, false, errors.New("JSON output and coverage profile flags are managed by the engine")
			}
		}
		if command == "benchmark" {
			prefix = append(prefix, "-run=^$", "-bench=.")
		} else if command == "coverage" {
			prefix = append(prefix, "-coverprofile="+profile)
		}
	case "tidy", "download":
		prefix = []string{"mod", command}
	case "workSync":
		prefix = []string{"work", "sync"}
	case "list":
		prefix, patterns = []string{"list", "-json"}, true
	default:
		return nil, false, fmt.Errorf("unsupported task command: %s", command)
	}
	if patterns && len(args) == 0 {
		args = []string{"./..."}
	}
	return append(prefix, args...), patterns, nil
}

func (m *Manager) execute(t *task) {
	defer close(t.done)
	<-t.gate
	exitCode := 0
	var vendorEnvironment []byte
	for index, step := range t.plan {
		if t.cancelled.Load() {
			exitCode = 130
			break
		}
		var capture *limitedBuffer
		if t.vendor && index == 1 {
			// The first step queries the selected SDK with this task's effective environment.
			var environment struct{ GOWORK string }
			if err := json.Unmarshal(vendorEnvironment, &environment); err != nil {
				m.notify("task/output", map[string]any{"id": t.id, "stream": "stderr", "text": "Unable to read Go workspace mode: " + err.Error() + "\n"})
				exitCode = 1
				break
			}
			command := "mod"
			if environment.GOWORK != "" && environment.GOWORK != "off" {
				command = "work"
			}
			step.Args = append([]string{command}, step.Args...)
		}
		cmd := exec.Command(m.goExecutable, step.Args...)
		cmd.Dir, cmd.Env = step.CWD, t.env
		configureProcess(cmd)
		stdout := &streamOutput{id: t.id, stream: "stdout", notify: m.notify, tests: len(step.Args) > 1 && step.Args[0] == "test" && step.Args[1] == "-json"}
		stderr := &streamOutput{id: t.id, stream: "stderr", notify: m.notify}
		cmd.Stdout, cmd.Stderr = stdout, stderr
		if t.vendor && index == 0 {
			capture = &limitedBuffer{remaining: 64 << 10}
			cmd.Stdout = capture
		}
		cmd.WaitDelay = time.Second
		m.notify("task/step", map[string]any{"id": t.id, "cwd": step.CWD, "executable": m.goExecutable, "args": step.Args})
		t.mu.Lock()
		if t.cancelled.Load() {
			t.mu.Unlock()
			exitCode = 130
			break
		}
		err := cmd.Start()
		if err == nil {
			t.cmd = cmd
		}
		t.mu.Unlock()
		if err == nil {
			err = cmd.Wait()
			// A completed launcher can leave children retaining its process group.
			// End the owned group before publishing the task's terminal event.
			_ = killProcess(cmd)
		}
		t.mu.Lock()
		t.cmd = nil
		t.mu.Unlock()
		stdout.Flush()
		stderr.Flush()
		if capture != nil {
			vendorEnvironment = append([]byte(nil), capture.Bytes()...)
		}
		if err != nil {
			code := 1
			var exit *exec.ExitError
			if errors.As(err, &exit) {
				code = exit.ExitCode()
			} else {
				m.notify("task/output", map[string]any{"id": t.id, "stream": "stderr", "text": err.Error() + "\n"})
			}
			exitCode = code
			if t.vendor || t.stopOnFailure {
				break
			}
		}
		if t.cancelled.Load() {
			exitCode = 130
			break
		}
	}
	result := map[string]any{"id": t.id, "code": exitCode, "cancelled": t.cancelled.Load()}
	if t.profile != "" {
		if st, err := os.Stat(t.profile); err == nil && st.Size() > 0 {
			result["coverageProfile"] = t.profile
		}
	}
	if t.sampling != nil {
		if t.cancelled.Load() {
			_ = os.RemoveAll(t.sampling.directory)
		} else if st, err := os.Stat(t.sampling.Path); err == nil && st.Size() > 0 {
			result["profile"] = t.sampling
		}
	}
	m.notify("task/exit", result)
	m.mu.Lock()
	delete(m.tasks, t.id)
	m.mu.Unlock()
}

func (m *Manager) Cancel(id string) (bool, error) {
	m.mu.Lock()
	t := m.tasks[id]
	m.mu.Unlock()
	if t == nil {
		return false, nil
	}
	t.cancelled.Store(true)
	t.resume.Do(func() { close(t.gate) })
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.cmd != nil {
		if err := killProcess(t.cmd); err != nil && !errors.Is(err, os.ErrProcessDone) {
			return true, err
		}
	}
	return true, nil
}

func (m *Manager) Close() error {
	m.mu.Lock()
	m.closed = true
	m.taskTargets = nil
	m.taskTargetOrder = nil
	tasks := make([]*task, 0, len(m.tasks))
	for _, t := range m.tasks {
		tasks = append(tasks, t)
	}
	m.mu.Unlock()
	for _, t := range tasks {
		_, _ = m.Cancel(t.id)
	}
	deadline := time.After(8 * time.Second)
	for _, t := range tasks {
		select {
		case <-t.done:
		case <-deadline:
			return errors.New("timed out waiting for owned tasks to exit")
		}
	}
	return nil
}

func (m *Manager) Refresh(ctx context.Context) ProjectInfo { return m.Project(ctx) }

// streamOutput emits raw chunks and separately decodes newline-delimited go test events.
type streamOutput struct {
	id      string
	stream  string
	notify  Notify
	tests   bool
	buffer  []byte
	skip    bool
	pending []byte
}

func (w *streamOutput) Write(p []byte) (int, error) {
	text := append(w.pending, p...)
	valid := 0
	for valid < len(text) {
		_, size := utf8.DecodeRune(text[valid:])
		if size == 1 && !utf8.FullRune(text[valid:]) {
			break
		}
		valid += size
	}
	w.pending = append([]byte(nil), text[valid:]...)
	text = text[:valid]
	for offset := 0; offset < len(text); {
		end := offset + 32*1024
		if end > len(text) {
			end = len(text)
		}
		for end < len(text) && !utf8.RuneStart(text[end]) && end > offset {
			end--
		}
		w.notify("task/output", map[string]any{"id": w.id, "stream": w.stream, "text": string(text[offset:end])})
		offset = end
	}
	if w.tests {
		for _, b := range p {
			if b == '\n' {
				if !w.skip {
					w.emitTest()
				}
				w.buffer, w.skip = w.buffer[:0], false
			} else if !w.skip {
				if len(w.buffer) == 1<<20 {
					w.buffer, w.skip = w.buffer[:0], true
				} else {
					w.buffer = append(w.buffer, b)
				}
			}
		}
	}
	return len(p), nil
}

func (w *streamOutput) Flush() {
	if len(w.pending) > 0 {
		w.notify("task/output", map[string]any{"id": w.id, "stream": w.stream, "text": string(w.pending)})
		w.pending = nil
	}
	if w.tests && len(w.buffer) > 0 && !w.skip {
		w.emitTest()
		w.buffer = nil
	}
}

func (w *streamOutput) emitTest() {
	if !json.Valid(w.buffer) {
		return
	}
	var header struct{ Action string }
	if json.Unmarshal(w.buffer, &header) == nil && header.Action != "" {
		w.notify("test/event", map[string]any{"id": w.id, "event": json.RawMessage(append([]byte(nil), w.buffer...))})
	}
}
