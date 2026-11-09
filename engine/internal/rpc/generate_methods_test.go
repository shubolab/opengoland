package rpc

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func TestTypedInterfaceGenerationRPCUsesOwnedWorkerAndReturnsOnlyEdits(t *testing.T) {
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	source := "package sample\n// 中文 😀\ntype Buffer struct{}\n"
	for name, text := range map[string]string{"go.mod": "module example.org/methodgenerationrpc\n\ngo 1.24.0\n", "buffer.go": source} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	initialized := c.reply(t, 1)
	if initialized["error"] != nil || initialized["result"].(map[string]any)["features"].(map[string]any)["interfaceGeneration"] != true {
		t.Fatal(initialized)
	}
	request := map[string]any{"operationId": "method-inspect", "receiverKind": "pointer", "interfaceExpression": "io.Reader", "target": map[string]any{
		"uri": signature.LocalURI(filepath.Join(root, "buffer.go")), "position": map[string]any{"line": 2, "character": 8}}}
	c.send(t, 2, "generate/methods/inspect", request)
	reply := c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	inspection := reply["result"].(map[string]any)
	if conflicts, ok := inspection["conflicts"].([]any); ok && len(conflicts) != 0 {
		t.Fatal(inspection)
	}
	methods := inspection["methods"].([]any)
	if len(methods) != 1 || methods[0].(map[string]any)["name"] != "Read" || methods[0].(map[string]any)["status"] != "missing" {
		t.Fatal(inspection)
	}
	request["operationId"] = "method-plan"
	request["inspectionId"] = inspection["inspectionId"]
	request["configurationDigest"] = inspection["configurationDigest"]
	request["receiverId"] = inspection["receiver"].(map[string]any)["receiverId"]
	request["receiverName"] = "b"
	request["allMissing"] = true
	c.send(t, 3, "generate/methods/plan", request)
	reply = c.reply(t, 3)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	plan := reply["result"].(map[string]any)
	if conflicts, ok := plan["conflicts"].([]any); ok && len(conflicts) != 0 {
		t.Fatal(plan)
	}
	if plan["validation"].(map[string]any)["typeChecked"] != true || plan["implemented"] != true {
		t.Fatal(plan)
	}
	changes := plan["workspaceEdit"].(map[string]any)["documentChanges"].([]any)
	if len(changes) != 1 {
		t.Fatal(changes)
	}
	edits := changes[0].(map[string]any)["edits"].([]any)
	if len(edits) == 0 || !strings.Contains(edits[0].(map[string]any)["newText"].(string), "Read(") {
		t.Fatal(edits)
	}
	bytes, err := os.ReadFile(filepath.Join(root, "buffer.go"))
	if err != nil || string(bytes) != source {
		t.Fatal("method planner wrote source instead of returning previewable edits")
	}
	c.send(t, 4, "shutdown", map[string]any{})
	_ = c.reply(t, 4)
}
