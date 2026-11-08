package rpc

import (
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestTypedStructTagsRPCUsesOwnedWorkerAndReturnsOnlyVersionedEdits(t *testing.T) {
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	root, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	source := "package sample\n// 中文 😀\ntype Model struct{HTTPServerID int `xml:\"keep\"`}\n"
	path := filepath.Join(root, "源 文件.go")
	uri := (&url.URL{Scheme: "file", Path: filepath.ToSlash(path)}).String()
	for p, text := range map[string]string{filepath.Join(root, "go.mod"): "module example.org/tagrpc\n\ngo 1.24.0\n", path: source} {
		if e = os.WriteFile(p, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	reply := c.reply(t, 1)
	if reply["error"] != nil || reply["result"].(map[string]any)["features"].(map[string]any)["structTags"] != true {
		t.Fatal(reply)
	}
	dirty := source + "// unsaved 😀\n"
	request := map[string]any{"operationId": "tag-inspect", "target": map[string]any{"uri": uri, "position": map[string]any{"line": 2, "character": 5}}, "overlays": []any{map[string]any{"uri": uri, "text": dirty, "documentStamp": 91, "lsp": map[string]any{"session": 4, "version": 7}}}}
	c.send(t, 2, "generate/tags/inspect", request)
	reply = c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	info := reply["result"].(map[string]any)
	fields := info["fields"].([]any)
	if len(fields) != 1 || fields[0].(map[string]any)["name"] != "HTTPServerID" {
		t.Fatal(info)
	}
	request["operationId"] = "tag-plan"
	request["inspectionId"] = info["inspectionId"]
	request["configurationDigest"] = info["configurationDigest"]
	request["structId"] = info["structId"]
	request["fieldIds"] = []any{fields[0].(map[string]any)["fieldId"]}
	request["key"] = "json"
	request["mode"] = "add-missing"
	request["style"] = "snake"
	request["options"] = []string{"omitempty"}
	c.send(t, 3, "generate/tags/plan", request)
	reply = c.reply(t, 3)
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
	if change["textDocument"].(map[string]any)["version"] != float64(7) {
		t.Fatal(change)
	}
	text := change["edits"].([]any)[0].(map[string]any)["newText"].(string)
	if !strings.Contains(text, `json:"http_server_id,omitempty"`) || !strings.Contains(text, `xml:"keep"`) || !strings.Contains(text, "unsaved 😀") {
		t.Fatal(text)
	}
	actual, e := os.ReadFile(path)
	if e != nil || string(actual) != source {
		t.Fatal("tag planner wrote source instead of returning a native preview edit")
	}
	c.send(t, 4, "shutdown", map[string]any{})
	_ = c.reply(t, 4)
}
