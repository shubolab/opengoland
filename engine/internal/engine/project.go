package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

type Tool struct {
	Name      string `json:"name"`
	Available bool   `json:"available"`
	Path      string `json:"path,omitempty"`
	Version   string `json:"version,omitempty"`
	Error     string `json:"error,omitempty"`
}

type Module struct {
	Path      string          `json:"Path"`
	Version   string          `json:"Version,omitempty"`
	Main      bool            `json:"Main"`
	Dir       string          `json:"Dir"`
	GoMod     string          `json:"GoMod"`
	GoVersion string          `json:"GoVersion,omitempty"`
	Replace   json.RawMessage `json:"Replace,omitempty"`
	Error     json.RawMessage `json:"Error,omitempty"`
}

type ProjectInfo struct {
	Root        string         `json:"root"`
	Name        string         `json:"name"`
	Environment map[string]any `json:"environment"`
	Modules     []Module       `json:"modules"`
	ModuleRoots []string       `json:"moduleRoots"`
	Workspace   string         `json:"workspace,omitempty"`
	Warnings    []string       `json:"warnings"`
}

type limitedBuffer struct {
	bytes.Buffer
	remaining int
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	if len(p) > b.remaining {
		_, _ = b.Buffer.Write(p[:b.remaining])
		b.remaining = 0
		return 0, errors.New("tool output exceeded size limit")
	}
	b.remaining -= len(p)
	return b.Buffer.Write(p)
}

func commandOutput(ctx context.Context, executable, cwd string, env []string, args ...string) ([]byte, error) {
	cmd := exec.CommandContext(ctx, executable, args...)
	cmd.Dir, cmd.Env = cwd, env
	configureProcess(cmd)
	cmd.Cancel = func() error { return killProcess(cmd) }
	cmd.WaitDelay = time.Second
	stdout, stderr := &limitedBuffer{remaining: 8 << 20}, &limitedBuffer{remaining: 64 << 10}
	cmd.Stdout, cmd.Stderr = stdout, stderr
	err := cmd.Run()
	// This command and its dedicated group are ours, including SDK descendants
	// left behind by a launcher which exits before closing all pipes.
	_ = killProcess(cmd)
	if ctx.Err() != nil {
		return stdout.Bytes(), ctx.Err()
	}
	if err != nil {
		message := strings.TrimSpace(stderr.String())
		if message == "" {
			message = err.Error()
		}
		return stdout.Bytes(), fmt.Errorf("%s %s: %s", filepath.Base(executable), strings.Join(args, " "), message)
	}
	return stdout.Bytes(), nil
}

func mergeEnvironment(overrides map[string]string) ([]string, error) {
	values := make(map[string]string)
	for _, pair := range os.Environ() {
		key, value, ok := strings.Cut(pair, "=")
		if ok {
			values[key] = value
		}
	}
	for key, value := range overrides {
		if key == "" || strings.ContainsAny(key, "=\x00") || strings.ContainsRune(value, '\x00') {
			return nil, errors.New("environment contains an invalid name or value")
		}
		values[key] = value
	}
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	env := make([]string, 0, len(keys))
	for _, key := range keys {
		env = append(env, key+"="+values[key])
	}
	return env, nil
}

func toolStatus(ctx context.Context, name, executable string, args ...string) Tool {
	if executable == "" {
		var err error
		executable, err = exec.LookPath(name)
		if err != nil {
			return Tool{Name: name, Error: "not found on PATH"}
		}
	}
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	data, err := commandOutput(ctx, executable, "", nil, args...)
	t := Tool{Name: name, Available: err == nil, Path: executable, Version: strings.TrimSpace(string(data))}
	if err != nil {
		t.Error = err.Error()
	}
	return t
}

func (m *Manager) Tools(ctx context.Context) []Tool {
	return []Tool{
		toolStatus(ctx, "go", m.goExecutable, "version"),
		toolStatus(ctx, "gopls", "", "version"),
		toolStatus(ctx, "dlv", "", "version"),
	}
}

func resolveDirectory(path string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	abs, err = filepath.EvalSymlinks(abs)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(abs)
	if err != nil || !info.IsDir() {
		return "", fmt.Errorf("not an accessible directory: %s", abs)
	}
	return filepath.Clean(abs), nil
}

func inside(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func (m *Manager) resolveCWD(path string) (string, error) {
	if path == "" {
		path = m.root
	} else if !filepath.IsAbs(path) {
		// On Windows, /child and C:child are neither fully qualified nor
		// project-relative. Joining them would silently change their meaning
		// (notably, "/" could become the project root). Preserve ordinary
		// relative traversal; the canonical owned-root check below bounds it.
		if filepath.VolumeName(path) != "" || os.IsPathSeparator(path[0]) {
			return "", errors.New("working directory must be project-relative or a fully qualified absolute path")
		}
		path = filepath.Join(m.root, path)
	}
	abs, err := resolveDirectory(path)
	if err != nil {
		return "", err
	}
	m.mu.Lock()
	roots := append([]string{m.root}, m.moduleRoots...)
	m.mu.Unlock()
	for _, root := range roots {
		if inside(root, abs) {
			return abs, nil
		}
	}
	return "", errors.New("working directory is outside the project and its workspace modules")
}

func (m *Manager) Project(ctx context.Context) ProjectInfo {
	info := ProjectInfo{Root: m.root, Name: filepath.Base(m.root), Environment: map[string]any{}, Modules: []Module{}, ModuleRoots: []string{}, Warnings: []string{}}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	env, _ := mergeEnvironment(nil)
	data, err := commandOutput(ctx, m.goExecutable, m.root, env, "env", "-json")
	if err != nil {
		info.Warnings = append(info.Warnings, err.Error())
	} else if err = json.Unmarshal(data, &info.Environment); err != nil {
		info.Warnings = append(info.Warnings, "invalid go env response: "+err.Error())
	}
	work, _ := info.Environment["GOWORK"].(string)
	if work != "" && work != "off" {
		info.Workspace = work
		data, err = commandOutput(ctx, m.goExecutable, m.root, env, "work", "edit", "-json", work)
		if err != nil {
			info.Warnings = append(info.Warnings, err.Error())
		} else {
			var workspace struct{ Use []struct{ DiskPath string } }
			if err := json.Unmarshal(data, &workspace); err != nil {
				info.Warnings = append(info.Warnings, "invalid go work response: "+err.Error())
			} else {
				for _, use := range workspace.Use {
					path := use.DiskPath
					if !filepath.IsAbs(path) {
						path = filepath.Join(filepath.Dir(work), path)
					}
					if path, err = resolveDirectory(path); err == nil {
						info.ModuleRoots = append(info.ModuleRoots, path)
					} else {
						info.Warnings = append(info.Warnings, "workspace member: "+err.Error())
					}
				}
			}
		}
	}
	data, err = commandOutput(ctx, m.goExecutable, m.root, env, "list", "-m", "-json")
	if err != nil {
		info.Warnings = append(info.Warnings, err.Error())
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	for {
		var module Module
		if err := decoder.Decode(&module); err != nil {
			if !errors.Is(err, io.EOF) {
				info.Warnings = append(info.Warnings, "invalid go list response: "+err.Error())
			}
			break
		}
		info.Modules = append(info.Modules, module)
		if module.Main && module.Dir != "" {
			if root, err := resolveDirectory(module.Dir); err == nil && inside(m.root, root) {
				info.ModuleRoots = append(info.ModuleRoots, root)
			}
		}
	}
	sort.Strings(info.ModuleRoots)
	info.ModuleRoots = deduplicate(info.ModuleRoots)
	m.mu.Lock()
	m.moduleRoots = append([]string(nil), info.ModuleRoots...)
	m.mu.Unlock()
	return info
}

func deduplicate(sorted []string) []string {
	result := sorted[:0]
	for _, value := range sorted {
		if len(result) == 0 || result[len(result)-1] != value {
			result = append(result, value)
		}
	}
	return result
}

func (m *Manager) Capabilities() map[string]any {
	return map[string]any{
		"name": "opengoland-engine", "version": Version, "protocolVersion": 1,
		"root": m.root, "os": runtime.GOOS, "arch": runtime.GOARCH,
		"commands": []string{"build", "run", "test", "vet", "tidy", "download", "generate", "benchmark", "coverage", "profile", "vendor", "list", "workSync"},
		"features": map[string]bool{"streamOutput": true, "cancelProcessGroup": true, "testEvents": true, "coverageProfile": true, "performanceProfiles": true, "workspaces": true},
	}
}
