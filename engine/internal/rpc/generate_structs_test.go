package rpc

import (
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTypedStructHelpersRPCUsesOwnedWorkerAndNeverWritesDirtyGenericSources(t *testing.T) {
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	root, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	source := "package sample\n// 中文 😀\ntype Model[T any] struct{Value T}\n"
	dirty := source + "// unsaved 😀\n"
	path := filepath.Join(root, "模型 文件.go")
	uri := (&url.URL{Scheme: "file", Path: filepath.ToSlash(path)}).String()
	for p, text := range map[string]string{filepath.Join(root, "go.mod"): "module example.org/structrpc\n\ngo 1.24.0\n", path: source} {
		if e = os.WriteFile(p, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	reply := c.reply(t, 1)
	if reply["error"] != nil || reply["result"].(map[string]any)["features"].(map[string]any)["structHelpers"] != true {
		t.Fatal(reply)
	}
	request := map[string]any{"operationId": "helpers-inspect", "target": map[string]any{"uri": uri, "position": map[string]any{"line": 2, "character": 5}}, "overlays": []any{map[string]any{"uri": uri, "text": dirty, "documentStamp": 95, "lsp": map[string]any{"session": 5, "version": 8}}}}
	c.send(t, 2, "generate/structs/inspect", request)
	reply = c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	info := reply["result"].(map[string]any)
	fields := info["fields"].([]any)
	if len(fields) != 1 || fields[0].(map[string]any)["name"] != "Value" || len(info["typeParameters"].([]any)) != 1 {
		t.Fatal(info)
	}
	request["inspectionId"] = info["inspectionId"]
	request["configurationDigest"] = info["configurationDigest"]
	request["structId"] = info["structId"]
	request["selections"] = []any{map[string]any{"fieldId": fields[0].(map[string]any)["fieldId"], "getterName": "Current", "setterName": "Replace", "parameterName": "value"}}
	request["receiverName"] = "m"
	request["getterKind"] = "value"
	for i, kind := range []string{"both", "constructor"} {
		request["operationId"] = "helpers-plan-" + kind
		request["kind"] = kind
		request["constructorName"] = "MakeModel"
		request["constructorKind"] = "value"
		id := 3 + i
		c.send(t, id, "generate/structs/plan", request)
		reply = c.reply(t, id)
		if reply["error"] != nil {
			t.Fatal(reply)
		}
		plan := reply["result"].(map[string]any)
		if len(plan["conflicts"].([]any)) != 0 || plan["validation"].(map[string]any)["typeChecked"] != true {
			t.Fatal(plan)
		}
		changes := plan["workspaceEdit"].(map[string]any)["documentChanges"].([]any)
		if len(changes) != 1 {
			t.Fatal(changes)
		}
		change := changes[0].(map[string]any)
		if change["textDocument"].(map[string]any)["version"] != float64(8) {
			t.Fatal(change)
		}
		text := change["edits"].([]any)[0].(map[string]any)["newText"].(string)
		if !strings.Contains(text, "unsaved 😀") {
			t.Fatal(text)
		}
		if kind == "both" {
			if !strings.Contains(text, "func (m Model[T]) Current()") || !strings.Contains(text, "func (m *Model[T]) Replace(value T)") {
				t.Fatal(text)
			}
		} else if !strings.Contains(text, "func MakeModel[T any](value T) Model[T]") {
			t.Fatal(text)
		}
	}
	actual, e := os.ReadFile(path)
	if e != nil || string(actual) != source {
		t.Fatal("struct helper planner wrote dirty source instead of returning an edit")
	}
	c.send(t, 5, "shutdown", map[string]any{})
	_ = c.reply(t, 5)
}
