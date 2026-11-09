package engine

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"time"
)

type TaskTargetsRequest struct {
	Command     string            `json:"command"`
	ContextPath string            `json:"contextPath,omitempty"`
	Env         map[string]string `json:"env,omitempty"`
	OperationID string            `json:"operationId,omitempty"`
}
type TaskTarget struct {
	ID          string   `json:"id"`
	Kind        string   `json:"kind"`
	Label       string   `json:"label"`
	Description string   `json:"description"`
	CWD         string   `json:"cwd"`
	ModuleRoots []string `json:"moduleRoots,omitempty"`
}
type TaskTargetsResponse struct {
	InspectionID    string       `json:"inspectionId"`
	Command         string       `json:"command"`
	Workspace       string       `json:"workspace,omitempty"`
	Targets         []TaskTarget `json:"targets"`
	DefaultTargetID string       `json:"defaultTargetId,omitempty"`
	Warnings        []string     `json:"warnings"`
}
type TaskTargetSelection struct {
	InspectionID string `json:"inspectionId"`
	TargetID     string `json:"targetId"`
}
type taskTargetInspection struct {
	response         TaskTargetsResponse
	request          TaskTargetsRequest
	env              []string
	executionEnv     []string
	metadata         map[string]string
	contextDirectory string
	contextFile      string
	active           taskGoEnvironment
	members          []string
	created          time.Time
}

func opaqueTaskID() (string, error) {
	b := make([]byte, 24)
	if _, e := rand.Read(b); e != nil {
		return "", e
	}
	return hex.EncodeToString(b), nil
}
func taskTargetCommand(command string) bool {
	switch command {
	case "tidy", "download", "vendor", "workSync", "generate", "benchmark", "vet":
		return true
	}
	return false
}
func taskEnvironment(overrides map[string]string) ([]string, error) {
	// Ordinary engineering tasks retain Go's effective user environment,
	// including explicit or automatic toolchain selection. The resolved SDK
	// version, GOROOT and executable bytes are frozen and verified separately.
	env, err := mergeEnvironment(overrides)
	if err != nil {
		return nil, err
	}
	for _, entry := range env {
		if !strings.HasPrefix(entry, "GOFLAGS=") {
			continue
		}
		for _, word := range strings.Fields(strings.TrimPrefix(entry, "GOFLAGS=")) {
			flag := strings.Trim(word, "\"'")
			flag, _, _ = strings.Cut(flag, "=")
			switch flag {
			case "-modfile", "-overlay", "-C":
				return nil, fmt.Errorf("task target inspection cannot freeze the alternate input scope selected by GOFLAGS %s; use the existing explicit task cwd interface for that override", flag)
			}
		}
	}
	return env, nil
}
func hashTaskFile(path string) (string, error) {
	data, e := os.ReadFile(path)
	if errors.Is(e, os.ErrNotExist) {
		return "missing", nil
	}
	if e != nil {
		return "", e
	}
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:]), nil
}
func contextDirectory(root, path string) (string, string, error) {
	if path == "" {
		return root, "", nil
	}
	if !filepath.IsAbs(path) {
		path = filepath.Join(root, path)
	}
	canonical, e := filepath.EvalSymlinks(path)
	if e != nil {
		return "", "", fmt.Errorf("task context: %w", e)
	}
	canonical, e = filepath.Abs(canonical)
	if e != nil {
		return "", "", e
	}
	info, e := os.Stat(canonical)
	if e != nil {
		return "", "", e
	}
	if info.IsDir() {
		return filepath.Clean(canonical), "", nil
	}
	if !info.Mode().IsRegular() {
		return "", "", errors.New("task context must be a regular file or directory")
	}
	return filepath.Dir(canonical), canonical, nil
}

type taskGoEnvironment struct{ GOMOD, GOWORK, GOENV, GOROOT, GOVERSION string }

func (m *Manager) taskGoEnvironment(ctx context.Context, cwd string, env []string) (taskGoEnvironment, error) {
	var result taskGoEnvironment
	data, e := commandOutput(ctx, m.goExecutable, cwd, env, "env", "-json", "GOMOD", "GOWORK", "GOENV", "GOROOT", "GOVERSION")
	if e != nil {
		return result, e
	}
	if e = json.Unmarshal(data, &result); e != nil {
		return result, fmt.Errorf("invalid selected SDK go env: %w", e)
	}
	return result, nil
}
func (m *Manager) TaskTargets(ctx context.Context, request TaskTargetsRequest) (TaskTargetsResponse, error) {
	if !taskTargetCommand(request.Command) {
		return TaskTargetsResponse{}, fmt.Errorf("task target selection is not supported for %s", request.Command)
	}
	if e := ctx.Err(); e != nil {
		return TaskTargetsResponse{}, e
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	env, e := taskEnvironment(request.Env)
	if e != nil {
		return TaskTargetsResponse{}, e
	}
	inputEnv := append([]string{}, env...)
	rootWorkspace := ""
	cwd, file, e := contextDirectory(m.root, request.ContextPath)
	if e != nil {
		return TaskTargetsResponse{}, e
	}
	// An external file is eligible only through the current effective workspace,
	// never through stale Project() metadata or a symlink escape from the root.
	if !inside(m.root, cwd) {
		rootEnv, e := m.taskGoEnvironment(ctx, m.root, env)
		if e != nil {
			return TaskTargetsResponse{}, e
		}
		rootWorkspace = rootEnv.GOWORK
		members, e := m.workspaceTaskMembers(ctx, rootEnv.GOWORK, env)
		if e != nil {
			return TaskTargetsResponse{}, e
		}
		allowed := false
		for _, member := range members {
			if inside(member, cwd) {
				allowed = true
				break
			}
		}
		if !allowed {
			return TaskTargetsResponse{}, errors.New("task context is outside the project and current effective workspace modules")
		}
	}
	active, e := m.taskGoEnvironment(ctx, cwd, env)
	if e != nil {
		return TaskTargetsResponse{}, e
	}
	// External workspace members need the selected project workspace made
	// explicit when Go's cwd-based ancestor search cannot discover it.
	if active.GOWORK == "" && rootWorkspace != "" && rootWorkspace != "off" {
		env = taskEnvValue(env, "GOWORK", rootWorkspace)
		active, e = m.taskGoEnvironment(ctx, cwd, env)
		if e != nil {
			return TaskTargetsResponse{}, e
		}
	}
	workspace := ""
	if active.GOWORK != "" && active.GOWORK != "off" {
		workspace, e = filepath.EvalSymlinks(active.GOWORK)
		if e != nil {
			return TaskTargetsResponse{}, e
		}
		workspace, e = filepath.Abs(workspace)
		if e != nil {
			return TaskTargetsResponse{}, e
		}
	}
	if workspace != "" {
		// Retain this chosen workspace when a task step enters an external member.
		env = taskEnvValue(env, "GOWORK", workspace)
		active.GOWORK = workspace
	}
	members, e := m.workspaceTaskMembers(ctx, workspace, env)
	if e != nil {
		return TaskTargetsResponse{}, e
	}
	module := ""
	if active.GOMOD != "" && active.GOMOD != os.DevNull && active.GOMOD != "off" {
		module, e = resolveDirectory(filepath.Dir(active.GOMOD))
		if e != nil {
			return TaskTargetsResponse{}, e
		}
	}
	modules := append([]string{}, members...)
	if module != "" && (workspace == "" || request.Command == "tidy" || containsTaskPath(members, module)) {
		modules = append(modules, module)
	}
	sort.Strings(modules)
	modules = deduplicate(modules)
	response := TaskTargetsResponse{Command: request.Command, Workspace: workspace, Targets: []TaskTarget{}, Warnings: []string{}}
	add := func(kind, cwd, label, description string, roots []string) error {
		id, e := opaqueTaskID()
		if e != nil {
			return e
		}
		response.Targets = append(response.Targets, TaskTarget{ID: id, Kind: kind, CWD: cwd, Label: label, Description: description, ModuleRoots: append([]string{}, roots...)})
		return nil
	}
	switch request.Command {
	case "vendor", "workSync":
		if workspace != "" {
			e = add("workspace", filepath.Dir(workspace), "Workspace: "+filepath.Base(filepath.Dir(workspace)), "Run go work "+map[string]string{"vendor": "vendor", "workSync": "sync"}[request.Command]+" using the active workspace", members)
		} else if request.Command == "vendor" && module != "" {
			e = add("module", module, "Module: "+filepath.Base(module), "Run go mod vendor with workspace mode disabled", []string{module})
		} else {
			response.Warnings = append(response.Warnings, "This command requires an active go.work workspace")
		}
	case "download":
		if workspace != "" {
			e = add("workspace", filepath.Dir(workspace), "Workspace dependency graph", "go mod download uses the active workspace build list, even when invoked from a member module", members)
		} else if module != "" {
			e = add("module", module, "Module: "+filepath.Base(module), "Download dependencies of this module", []string{module})
		}
	default:
		for _, root := range modules {
			if e = add("module", root, "Module: "+filepath.Base(root), root, []string{root}); e != nil {
				break
			}
		}
		if e == nil && workspace != "" && len(members) > 0 {
			e = add("workspaceModules", filepath.Dir(workspace), "All workspace modules", fmt.Sprintf("Run %s separately in all %d workspace modules; stop at the first failed step", request.Command, len(members)), members)
		}
	}
	if e != nil {
		return TaskTargetsResponse{}, e
	}
	if len(response.Targets) == 0 {
		response.Warnings = append(response.Warnings, "No eligible module or workspace target was found in this effective Go context")
	}
	if request.Command == "vendor" || request.Command == "workSync" || request.Command == "download" {
		if len(response.Targets) == 1 {
			response.DefaultTargetID = response.Targets[0].ID
		}
	} else {
		for _, target := range response.Targets {
			if target.Kind == "module" && target.CWD == module {
				response.DefaultTargetID = target.ID
				break
			}
		}
		if response.DefaultTargetID == "" && len(modules) == 1 {
			for _, target := range response.Targets {
				if target.Kind == "module" {
					response.DefaultTargetID = target.ID
					break
				}
			}
		}
	}
	// Freeze all discovery metadata, its absent alternatives, the exact context
	// source and executable. A later inspection is required after any change.
	metadata := map[string]string{}
	capture := func(path string) error {
		if path == "" || path == "off" {
			return nil
		}
		if !filepath.IsAbs(path) {
			return fmt.Errorf("non-absolute task metadata path: %s", path)
		}
		hash, e := hashTaskFile(path)
		if e == nil {
			metadata[filepath.Clean(path)] = hash
		}
		return e
	}
	for _, path := range []string{m.goExecutable, file, active.GOENV, active.GOMOD, workspace} {
		if path == os.DevNull {
			continue
		}
		if e = capture(path); e != nil {
			return TaskTargetsResponse{}, e
		}
	}
	if active.GOROOT != "" {
		for _, name := range []string{"VERSION", filepath.Join("bin", "go")} {
			if e = capture(filepath.Join(active.GOROOT, name)); e != nil {
				return TaskTargetsResponse{}, e
			}
		}
	}
	for _, root := range modules {
		for _, name := range []string{"go.mod", "go.sum"} {
			if e = capture(filepath.Join(root, name)); e != nil {
				return TaskTargetsResponse{}, e
			}
		}
	}
	if workspace != "" {
		if e = capture(workspace + ".sum"); e != nil {
			return TaskTargetsResponse{}, e
		}
	}
	for directory := cwd; ; directory = filepath.Dir(directory) {
		for _, name := range []string{"go.mod", "go.work"} {
			if e = capture(filepath.Join(directory, name)); e != nil {
				return TaskTargetsResponse{}, e
			}
		}
		if filepath.Dir(directory) == directory {
			break
		}
	}
	response.InspectionID, e = opaqueTaskID()
	if e != nil {
		return TaskTargetsResponse{}, e
	}
	// Re-query after metadata capture, so concurrent workspace edits cannot
	// leave a response describing an older member list with newer fingerprints.
	finalActive, err := m.taskGoEnvironment(ctx, cwd, env)
	if err != nil {
		return TaskTargetsResponse{}, err
	}
	if finalActive != active {
		return TaskTargetsResponse{}, errors.New("Go task context changed during inspection; choose targets again")
	}
	finalMembers, err := m.workspaceTaskMembers(ctx, workspace, env)
	if err != nil {
		return TaskTargetsResponse{}, err
	}
	if !reflect.DeepEqual(finalMembers, members) {
		return TaskTargetsResponse{}, errors.New("workspace membership changed during inspection; choose targets again")
	}
	for path, old := range metadata {
		hash, err := hashTaskFile(path)
		if err != nil || hash != old {
			return TaskTargetsResponse{}, fmt.Errorf("task input changed during inspection: %s", path)
		}
	}
	if e = ctx.Err(); e != nil {
		return TaskTargetsResponse{}, e
	}
	inspection := &taskTargetInspection{response: cloneTaskTargets(response), request: request, env: inputEnv, executionEnv: env, metadata: metadata, contextDirectory: cwd, contextFile: file, active: active, members: append([]string{}, members...), created: time.Now()}
	inspection.request.Env = map[string]string{}
	for k, v := range request.Env {
		inspection.request.Env[k] = v
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return TaskTargetsResponse{}, errors.New("engine is shutting down")
	}
	if m.taskTargets == nil {
		m.taskTargets = map[string]*taskTargetInspection{}
	}
	for len(m.taskTargetOrder) >= 64 {
		old := m.taskTargetOrder[0]
		m.taskTargetOrder = m.taskTargetOrder[1:]
		delete(m.taskTargets, old)
	}
	m.taskTargets[response.InspectionID] = inspection
	m.taskTargetOrder = append(m.taskTargetOrder, response.InspectionID)
	return response, nil
}
func containsTaskPath(paths []string, path string) bool {
	for _, p := range paths {
		if p == path {
			return true
		}
	}
	return false
}
func (m *Manager) workspaceTaskMembers(ctx context.Context, workspace string, env []string) ([]string, error) {
	roots := []string{}
	if workspace == "" || workspace == "off" {
		return roots, nil
	}
	work, e := filepath.EvalSymlinks(workspace)
	if e != nil {
		return nil, e
	}
	work, e = filepath.Abs(work)
	if e != nil {
		return nil, e
	}
	data, e := commandOutput(ctx, m.goExecutable, filepath.Dir(work), env, "work", "edit", "-json", work)
	if e != nil {
		return nil, e
	}
	var value struct{ Use []struct{ DiskPath string } }
	if e = json.Unmarshal(data, &value); e != nil {
		return nil, fmt.Errorf("invalid selected SDK go.work response: %w", e)
	}
	for _, use := range value.Use {
		path := use.DiskPath
		if !filepath.IsAbs(path) {
			path = filepath.Join(filepath.Dir(work), path)
		}
		root, e := resolveDirectory(path)
		if e != nil {
			return nil, fmt.Errorf("workspace member: %w", e)
		}
		info, e := os.Stat(filepath.Join(root, "go.mod"))
		if e != nil || !info.Mode().IsRegular() {
			return nil, fmt.Errorf("workspace member has no regular go.mod: %s", root)
		}
		roots = append(roots, root)
	}
	sort.Strings(roots)
	return deduplicate(roots), nil
}
func (m *Manager) selectedTaskPlan(ctx context.Context, req TaskRequest) ([]step, []string, *taskResumeGuard, error) {
	selection := req.TargetSelection
	if selection == nil {
		return nil, nil, nil, errors.New("missing target selection")
	}
	m.mu.Lock()
	saved := m.taskTargets[selection.InspectionID]
	m.mu.Unlock()
	if saved == nil {
		return nil, nil, nil, errors.New("unknown or expired task target inspection; choose a target again")
	}
	if time.Since(saved.created) > 10*time.Minute {
		return nil, nil, nil, errors.New("task target inspection expired; choose a target again")
	}
	if req.Command != saved.response.Command {
		return nil, nil, nil, errors.New("task command differs from its inspected target")
	}
	env, e := taskEnvironment(req.Env)
	if e != nil {
		return nil, nil, nil, e
	}
	if !reflect.DeepEqual(env, saved.env) {
		return nil, nil, nil, errors.New("effective task environment changed; choose a target again")
	}
	env = append([]string{}, saved.executionEnv...)
	var selected *TaskTarget
	for i := range saved.response.Targets {
		if saved.response.Targets[i].ID == selection.TargetID {
			copy := saved.response.Targets[i]
			selected = &copy
			break
		}
	}
	if selected == nil {
		return nil, nil, nil, errors.New("task target does not belong to this inspection")
	}
	if req.CWD != "" {
		path := req.CWD
		if !filepath.IsAbs(path) {
			path = filepath.Join(m.root, path)
		}
		cwd, e := resolveDirectory(path)
		if e != nil || cwd != selected.CWD {
			return nil, nil, nil, errors.New("task cwd differs from the inspected target")
		}
	}
	if e = ctx.Err(); e != nil {
		return nil, nil, nil, e
	}
	for path, old := range saved.metadata {
		hash, e := hashTaskFile(path)
		if e != nil || hash != old {
			return nil, nil, nil, fmt.Errorf("task input changed: %s; choose a target again", path)
		}
	}
	cwd, file, e := contextDirectory(m.root, saved.request.ContextPath)
	if e != nil || cwd != saved.contextDirectory || file != saved.contextFile {
		return nil, nil, nil, errors.New("task context path changed; choose a target again")
	}
	active, e := m.taskGoEnvironment(ctx, cwd, env)
	if e != nil {
		return nil, nil, nil, e
	}
	if active != saved.active {
		return nil, nil, nil, errors.New("selected SDK or effective module/workspace context changed; choose a target again")
	}
	work := ""
	if active.GOWORK != "" && active.GOWORK != "off" {
		work, e = filepath.EvalSymlinks(active.GOWORK)
		if e != nil {
			return nil, nil, nil, e
		}
		work, e = filepath.Abs(work)
		if e != nil {
			return nil, nil, nil, e
		}
	}
	if work != saved.response.Workspace {
		return nil, nil, nil, errors.New("active workspace changed; choose a target again")
	}
	members, e := m.workspaceTaskMembers(ctx, work, env)
	if e != nil {
		return nil, nil, nil, e
	}
	if !reflect.DeepEqual(members, saved.members) {
		return nil, nil, nil, errors.New("workspace membership changed; choose a target again")
	}
	if selected.Kind == "workspace" || selected.Kind == "workspaceModules" {
		if !reflect.DeepEqual(members, selected.ModuleRoots) {
			return nil, nil, nil, errors.New("workspace members changed; choose a target again")
		}
	} else {
		canonical, e := resolveDirectory(selected.CWD)
		if e != nil || canonical != selected.CWD {
			return nil, nil, nil, errors.New("selected module path changed")
		}
	}
	for _, arg := range req.Args {
		if strings.ContainsRune(arg, '\x00') {
			return nil, nil, nil, errors.New("arguments contain NUL")
		}
		if arg == "-C" || strings.HasPrefix(arg, "-C=") || arg == "-modfile" || strings.HasPrefix(arg, "-modfile=") || arg == "-overlay" || strings.HasPrefix(arg, "-overlay=") {
			return nil, nil, nil, errors.New("task target selection does not support cwd, modfile or overlay overrides")
		}
	}
	var args []string
	if req.Command == "vendor" {
		mode := "mod"
		if selected.Kind == "workspace" {
			mode = "work"
		}
		args = append([]string{mode, "vendor"}, req.Args...)
	} else {
		args, _, e = planArguments(req.Command, req.Args, "")
		if e != nil {
			return nil, nil, nil, e
		}
		if req.Command == "generate" || req.Command == "benchmark" || req.Command == "vet" {
			if len(req.Args) > 0 && !hasTaskPattern(req.Args) {
				args = append(args, "./...")
			}
		}
	}
	roots := []string{selected.CWD}
	if selected.Kind == "workspaceModules" {
		roots = selected.ModuleRoots
	}
	plan := []step{}
	for _, root := range roots {
		plan = append(plan, step{CWD: root, Args: append([]string{}, args...)})
	}
	for path, old := range saved.metadata {
		hash, err := hashTaskFile(path)
		if err != nil || hash != old {
			return nil, nil, nil, fmt.Errorf("task input changed during preparation: %s", path)
		}
	}
	if e = ctx.Err(); e != nil {
		return nil, nil, nil, e
	}
	guard := &taskResumeGuard{metadata: map[string]string{}, inputEnv: append([]string{}, saved.env...), overrides: map[string]string{}, contextPath: saved.request.ContextPath, contextDirectory: saved.contextDirectory, contextFile: saved.contextFile, executable: m.goExecutable}
	for path, hash := range saved.metadata {
		guard.metadata[path] = hash
	}
	for key, value := range req.Env {
		guard.overrides[key] = value
	}
	guard.executableTarget, e = filepath.EvalSymlinks(m.goExecutable)
	if e != nil {
		return nil, nil, nil, e
	}
	return plan, env, guard, nil
}

// Flags added by the native build-tag setting must not suppress ./... or the
// explicit multi-module iteration. Flags taking separate values are skipped.
func hasTaskPattern(args []string) bool {
	values := map[string]bool{"-tags": true, "-run": true, "-bench": true, "-benchtime": true, "-count": true, "-timeout": true, "-cpu": true, "-parallel": true, "-p": true, "-exec": true, "-vet": true, "-gcflags": true, "-asmflags": true, "-ldflags": true, "-mod": true, "-coverpkg": true, "-covermode": true, "-list": true, "-shuffle": true}
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			return i+1 < len(args)
		}
		if !strings.HasPrefix(arg, "-") {
			return true
		}
		if !strings.ContainsRune(arg, '=') && values[arg] {
			i++
		}
	}
	return false
}

func cloneTaskTargets(value TaskTargetsResponse) TaskTargetsResponse {
	value.Targets = append([]TaskTarget{}, value.Targets...)
	for i := range value.Targets {
		value.Targets[i].ModuleRoots = append([]string{}, value.Targets[i].ModuleRoots...)
	}
	value.Warnings = append([]string{}, value.Warnings...)
	return value
}

func taskEnvValue(env []string, key, value string) []string {
	result := append([]string{}, env...)
	prefix := key + "="
	for i, pair := range result {
		if strings.HasPrefix(pair, prefix) {
			result[i] = prefix + value
			return result
		}
	}
	result = append(result, prefix+value)
	sort.Strings(result)
	return result
}

// Each prepared task owns an immutable copy. Cache eviction or another task's
// analysis cannot invalidate or mutate a paused task's guard accidentally.
type taskResumeGuard struct {
	metadata                                   map[string]string
	inputEnv                                   []string
	overrides                                  map[string]string
	contextPath, contextDirectory, contextFile string
	executable, executableTarget               string
}

func (guard *taskResumeGuard) validate(ctx context.Context, m *Manager, plan []step) error {
	if guard == nil {
		return errors.New("prepared task has no frozen target guard")
	}
	env, err := taskEnvironment(guard.overrides)
	if err != nil {
		return err
	}
	if !reflect.DeepEqual(env, guard.inputEnv) {
		return errors.New("effective task environment changed after preparation")
	}
	executable, err := filepath.EvalSymlinks(guard.executable)
	if err != nil || executable != guard.executableTarget {
		return errors.New("selected Go executable path changed after preparation")
	}
	for path, old := range guard.metadata {
		if err = ctx.Err(); err != nil {
			return err
		}
		hash, e := hashTaskFile(path)
		if e != nil || hash != old {
			return fmt.Errorf("task input changed after preparation: %s; choose a target again", path)
		}
	}
	directory, file, err := contextDirectory(m.root, guard.contextPath)
	if err != nil || directory != guard.contextDirectory || file != guard.contextFile {
		return errors.New("task context path changed after preparation")
	}
	for _, step := range plan {
		directory, err := resolveDirectory(step.CWD)
		if err != nil || directory != step.CWD {
			return errors.New("prepared task directory changed")
		}
	}
	return ctx.Err()
}
