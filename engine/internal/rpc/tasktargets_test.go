package rpc

import (
	"os"
	"path/filepath"
	"testing"
)

func TestTaskTargetsRPCOwnsSelectionCancellationAndRunsTheSelectedRealModule(t *testing.T) {
	t.Setenv("GOTOOLCHAIN", "local")
	t.Setenv("GOWORK", "")
	t.Setenv("GOPROXY", "off")
	t.Setenv("GOFLAGS", "")
	t.Setenv("GOENV", "off")
	root, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	for name, text := range map[string]string{"go.work": "go 1.24.0\nuse (\n./a\n./b\n)\n", "a/go.mod": "module example.org/a\n\ngo 1.24.0\n", "a/source.go": "package a\n", "b/go.mod": "module example.org/b\n\ngo 1.24.0\n", "b/模型.go": "package b\n"} {
		path := filepath.Join(root, name)
		if e = os.MkdirAll(filepath.Dir(path), 0700); e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(path, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	reply := c.reply(t, 1)
	if reply["error"] != nil || reply["result"].(map[string]any)["features"].(map[string]any)["taskTargets"] != true {
		t.Fatal(reply)
	}
	c.send(t, 2, "refactor/cancel", map[string]any{"operationId": "cancel-before-targets"})
	_ = c.reply(t, 2)
	c.send(t, 3, "project/taskTargets", map[string]any{"command": "tidy", "operationId": "cancel-before-targets"})
	reply = c.reply(t, 3)
	if reply["error"] == nil || reply["error"].(map[string]any)["code"] != float64(-32800) {
		t.Fatal("lost cancellation ownership", reply)
	}
	c.send(t, 4, "project/taskTargets", map[string]any{"command": "tidy", "contextPath": filepath.Join(root, "b/模型.go"), "operationId": "live-targets"})
	reply = c.reply(t, 4)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	info := reply["result"].(map[string]any)
	targetID := info["defaultTargetId"].(string)
	if targetID == "" {
		t.Fatal(info)
	}
	found := false
	for _, raw := range info["targets"].([]any) {
		target := raw.(map[string]any)
		if target["id"] == targetID {
			found = target["kind"] == "module" && target["cwd"] == filepath.Join(root, "b")
		}
	}
	if !found {
		t.Fatal(info)
	}
	selection := map[string]any{"inspectionId": info["inspectionId"], "targetId": targetID}
	c.send(t, 5, "task/start", map[string]any{"id": "selected-tidy", "command": "tidy", "operationId": "prepare-selected-tidy", "targetSelection": selection})
	reply = c.next(t)
	if reply["id"] != float64(5) || reply["error"] != nil {
		t.Fatal("selected task was not accepted before execution", reply)
	}
	steps := 0
	for {
		message := c.next(t)
		params, _ := message["params"].(map[string]any)
		if message["method"] == "task/step" {
			steps++
			if params["cwd"] != filepath.Join(root, "b") {
				t.Fatal("wrong cwd", message)
			}
		}
		if message["method"] == "task/exit" {
			if params["code"] != float64(0) || params["cancelled"] != false || steps != 1 {
				t.Fatal(message, steps)
			}
			break
		}
	}
	if e = os.WriteFile(filepath.Join(root, "b/模型.go"), []byte("package b\n// changed after inspection\n"), 0600); e != nil {
		t.Fatal(e)
	}
	c.send(t, 6, "task/start", map[string]any{"id": "stale-tidy", "command": "tidy", "operationId": "prepare-stale-tidy", "targetSelection": selection})
	reply = c.reply(t, 6)
	if reply["error"] == nil {
		t.Fatal("stale source was accepted", reply)
	}
	c.send(t, 7, "shutdown", map[string]any{})
	_ = c.reply(t, 7)
}
func TestTaskPrepareResumeRPCSeparatesMetadataPreparationFromActualExecution(t *testing.T) {
	t.Setenv("GOTOOLCHAIN", "local")
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	t.Setenv("GOFLAGS", "")
	t.Setenv("GOENV", "off")
	root, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	for name, text := range map[string]string{"go.mod": "module example.org/prepared\n\ngo 1.24.0\n", "source.go": "package fixture\n//go:generate sh -c \"echo resumed > marker\"\n"} {
		if e = os.WriteFile(filepath.Join(root, name), []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	if reply := c.reply(t, 1); reply["error"] != nil {
		t.Fatal(reply)
	}
	c.send(t, 2, "project/taskTargets", map[string]any{"command": "generate", "contextPath": filepath.Join(root, "source.go"), "operationId": "inspect-prepared"})
	reply := c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	info := reply["result"].(map[string]any)
	selection := map[string]any{"inspectionId": info["inspectionId"], "targetId": info["defaultTargetId"]}
	request := map[string]any{"id": "prepared-generator", "command": "generate", "operationId": "prepare-generator", "targetSelection": selection}
	c.send(t, 3, "task/prepare", request)
	reply = c.reply(t, 3)
	if reply["error"] != nil || reply["result"].(map[string]any)["paused"] != true {
		t.Fatal(reply)
	}
	if _, e = os.Stat(filepath.Join(root, "marker")); !os.IsNotExist(e) {
		t.Fatal("prepare executed the generator", e)
	}
	c.send(t, 4, "task/resume", map[string]any{"id": "prepared-generator"})
	resumed, exited, steps := false, false, 0
	for !resumed || !exited {
		message := c.next(t)
		if message["id"] == float64(4) {
			if message["error"] != nil || message["result"].(map[string]any)["resumed"] != true {
				t.Fatal(message)
			}
			resumed = true
		}
		if message["method"] == "task/step" {
			steps++
		}
		if message["method"] == "task/exit" {
			params := message["params"].(map[string]any)
			if params["code"] != float64(0) || params["cancelled"] != false {
				t.Fatal(message)
			}
			exited = true
		}
	}
	if steps != 1 {
		t.Fatal("resume command count", steps)
	}
	if _, e = os.Stat(filepath.Join(root, "marker")); e != nil {
		t.Fatal("resume did not execute actual Go generate", e)
	}
	request["id"] = "stale-paused"
	request["operationId"] = "prepare-stale"
	c.send(t, 5, "task/prepare", request)
	reply = c.reply(t, 5)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	file := filepath.Join(root, "go.mod")
	data, e := os.ReadFile(file)
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(file, append(data, []byte("\n// changed after prepare\n")...), 0600); e != nil {
		t.Fatal(e)
	}
	c.send(t, 6, "task/resume", map[string]any{"id": "stale-paused"})
	rejected, cancelled := false, false
	for !rejected || !cancelled {
		message := c.next(t)
		if message["id"] == float64(6) {
			if message["error"] == nil {
				t.Fatal("stale paused task resumed", message)
			}
			rejected = true
		}
		if message["method"] == "task/step" {
			t.Fatal("stale paused task executed", message)
		}
		if message["method"] == "task/exit" {
			if message["params"].(map[string]any)["cancelled"] != true {
				t.Fatal(message)
			}
			cancelled = true
		}
	}
	c.send(t, 7, "task/resume", map[string]any{"id": "stale-paused"})
	if reply = c.reply(t, 7); reply["error"] == nil {
		t.Fatal("cancelled task resumed again", reply)
	}
	c.send(t, 8, "refactor/cancel", map[string]any{"operationId": "cancel-future-prepare"})
	_ = c.reply(t, 8)
	request["id"] = "never-prepared"
	request["operationId"] = "cancel-future-prepare"
	c.send(t, 9, "task/prepare", request)
	reply = c.reply(t, 9)
	if reply["error"] == nil || reply["error"].(map[string]any)["code"] != float64(-32800) {
		t.Fatal("cancelled future prepare was accepted", reply)
	}
	c.send(t, 10, "task/resume", map[string]any{"id": "never-prepared"})
	if reply = c.reply(t, 10); reply["error"] == nil {
		t.Fatal(reply)
	}
	c.send(t, 11, "task/prepare", map[string]any{"id": "no-selection", "command": "tidy"})
	if reply = c.reply(t, 11); reply["error"] == nil {
		t.Fatal("prepare requires a frozen selection", reply)
	}
	c.send(t, 12, "shutdown", map[string]any{})
	_ = c.reply(t, 12)
}
