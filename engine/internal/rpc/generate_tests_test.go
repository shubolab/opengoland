package rpc

import (
	"os"
	"path/filepath"
	"testing"
)

func TestTypedTestGenerationRPCUsesAnOwnedWorkerAndReturnsPreviewableCreates(t *testing.T) {
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	root := t.TempDir()
	root, _ = filepath.EvalSymlinks(root)
	source := "package sample\n// 中文 😀\nfunc Sum(values ...int) int { total:=0;for _,value:=range values {total+=value};return total }\n"
	for name, text := range map[string]string{"go.mod": "module example.org/testgenerationrpc\n\ngo 1.24.0\n", "sum.go": source} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	initialized := c.reply(t, 1)
	if initialized["error"] != nil {
		t.Fatal(initialized)
	}
	if initialized["result"].(map[string]any)["features"].(map[string]any)["testGeneration"] != true {
		t.Fatal("missing native test generation capability")
	}
	request := map[string]any{"operationId": "generate-inspect", "scope": "function", "packageMode": "same", "target": map[string]any{
		"uri": "file://" + filepath.ToSlash(filepath.Join(root, "sum.go")), "position": map[string]any{"line": 2, "character": 6}}}
	c.send(t, 2, "generate/tests/inspect", request)
	reply := c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	inspection := reply["result"].(map[string]any)
	symbols := inspection["symbols"].([]any)
	if len(symbols) != 1 {
		t.Fatal(inspection)
	}
	symbol := symbols[0].(map[string]any)
	request["operationId"] = "generate-plan"
	request["inspectionId"] = inspection["inspectionId"]
	request["configurationDigest"] = inspection["configurationDigest"]
	request["selections"] = []any{map[string]any{"symbolId": symbol["symbolId"], "testName": symbol["suggestedTestName"]}}
	c.send(t, 3, "generate/tests/plan", request)
	reply = c.reply(t, 3)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	plan := reply["result"].(map[string]any)
	if len(plan["conflicts"].([]any)) != 0 || plan["validation"].(map[string]any)["typeChecked"] != true {
		t.Fatal(plan)
	}
	changes := plan["workspaceEdit"].(map[string]any)["documentChanges"].([]any)
	if len(changes) != 2 || changes[0].(map[string]any)["kind"] != "create" {
		t.Fatal(changes)
	}
	if _, err := os.Stat(filepath.Join(root, "sum_test.go")); !os.IsNotExist(err) {
		t.Fatal("planner created a file instead of returning a preview")
	}
	bytes, err := os.ReadFile(filepath.Join(root, "sum.go"))
	if err != nil || string(bytes) != source {
		t.Fatal("generation changed original source")
	}
	c.send(t, 4, "shutdown", map[string]any{})
	_ = c.reply(t, 4)
}
