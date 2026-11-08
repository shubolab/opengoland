package rpc

import (
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestJSONGenerationRPCUsesOwnedWorkerAndNeverWritesDirtyGenericSources(t *testing.T) {
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	root, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	source := "package sample\n// 中文 😀\ntype Model[T any] struct{Old T `json:\"-\"`}\n"
	dirty := source + "// unsaved 😀\n"
	path := filepath.Join(root, "模型 文件.go")
	uri := (&url.URL{Scheme: "file", Path: filepath.ToSlash(path)}).String()
	for p, text := range map[string]string{filepath.Join(root, "go.mod"): "module example.org/jsonrpc\n\ngo 1.24.0\n", path: source} {
		if e = os.WriteFile(p, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	reply := c.reply(t, 1)
	if reply["error"] != nil || reply["result"].(map[string]any)["features"].(map[string]any)["jsonGeneration"] != true {
		t.Fatal(reply)
	}
	for index, mode := range []string{"type", "fields"} {
		request := map[string]any{"operationId": "json-inspect-" + mode, "target": map[string]any{"uri": uri, "position": map[string]any{"line": 2, "character": 5}}, "mode": mode, "typeName": "Message", "numericPolicy": "number", "jsonText": `{"n":9007199254740993123456789,"nested":{"value":true},"nullable":null}`, "overlays": []any{map[string]any{"uri": uri, "text": dirty, "documentStamp": 95, "lsp": map[string]any{"session": 5, "version": 8}}}}
		id := 2 + index*2
		c.send(t, id, "generate/json/inspect", request)
		reply = c.reply(t, id)
		if reply["error"] != nil {
			t.Fatal(reply)
		}
		info := reply["result"].(map[string]any)
		if info["inspectionId"] == "" || info["structId"] == "" || len(info["fields"].([]any)) != 3 {
			t.Fatal(info)
		}
		request["operationId"] = "json-plan-" + mode
		request["inspectionId"] = info["inspectionId"]
		request["configurationDigest"] = info["configurationDigest"]
		request["structId"] = info["structId"]
		if mode == "fields" {
			ids := []any{}
			for _, field := range info["fields"].([]any) {
				ids = append(ids, field.(map[string]any)["fieldId"])
			}
			request["fieldIds"] = ids
		}
		c.send(t, id+1, "generate/json/plan", request)
		reply = c.reply(t, id+1)
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
		if !strings.Contains(text, "unsaved 😀") || !strings.Contains(text, "json.Number") || !strings.Contains(text, "json.RawMessage") {
			t.Fatal(text)
		}
		if mode == "type" && !strings.Contains(text, "type Message struct") {
			t.Fatal(text)
		}
		if mode == "fields" && (!strings.Contains(text, "type ModelNested struct") || !strings.Contains(text, "type Model[T any] struct")) {
			t.Fatal(text)
		}
	}
	actual, e := os.ReadFile(path)
	if e != nil || string(actual) != source {
		t.Fatal("JSON planner wrote source instead of returning edits")
	}
	c.send(t, 6, "shutdown", map[string]any{})
	_ = c.reply(t, 6)
}
