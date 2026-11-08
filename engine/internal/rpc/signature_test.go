package rpc

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func TestSignatureRPCInspectPlanAndNativeBaselines(t *testing.T) {
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	root := t.TempDir()
	root, _ = filepath.EvalSymlinks(root)
	source := "package sample\n// 中文 😀\nfunc Target(a,b int) int { return a*10+b }\nfunc use() int { return Target(1,2) }\n"
	for name, text := range map[string]string{"go.mod": "module example.org/signaturerpc\n\ngo 1.24.0\n", "main.go": source} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	if result := c.reply(t, 1); result["error"] != nil {
		t.Fatal(result)
	}
	request := map[string]any{"operationId": "inspect-wire", "target": map[string]any{"uri": "file://" + filepath.ToSlash(filepath.Join(root, "main.go")), "position": map[string]any{"line": 2, "character": 8}}}
	c.send(t, 2, "refactor/signature/inspect", request)
	reply := c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	info := reply["result"].(map[string]any)
	params := info["parameters"].([]any)
	request["operationId"] = "plan-wire"
	request["symbolId"] = info["symbolId"]
	request["configurationDigest"] = info["configurationDigest"]
	request["change"] = map[string]any{"name": "Target", "parameters": []any{params[1], params[0]}, "results": info["results"]}
	c.send(t, 3, "refactor/signature/plan", request)
	reply = c.reply(t, 3)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	plan := reply["result"].(map[string]any)
	if len(plan["conflicts"].([]any)) != 0 || len(plan["workspaceEdit"].(map[string]any)["documentChanges"].([]any)) != 1 {
		t.Fatal(plan)
	}
	if plan["validation"].(map[string]any)["typeChecked"] != true {
		t.Fatal("candidate did not typecheck")
	}
	data, _ := os.ReadFile(filepath.Join(root, "main.go"))
	if string(data) != source {
		t.Fatal("RPC planner changed disk")
	}
	c.send(t, 4, "refactor/cancel", map[string]any{"operationId": "already-completed"})
	if c.reply(t, 4)["result"].(map[string]any)["cancelled"] != false {
		t.Fatal("completed request is still active")
	}
	c.send(t, 5, "shutdown", map[string]any{})
	_ = c.reply(t, 5)
}
func TestSignatureCancellationIsolationDuplicatesAndShutdown(t *testing.T) {
	s := New(os.Stdout)
	s.signatureRoot = t.TempDir()
	req := signature.Request{OperationID: "one"}
	one, finish, err := s.signatureContext(&req)
	if err != nil {
		t.Fatal(err)
	}
	defer finish()
	if _, _, err = s.signatureContext(&signature.Request{OperationID: "one"}); err == nil {
		t.Fatal("concurrent duplicate operation accepted")
	}
	two, end, err := s.signatureContext(&signature.Request{OperationID: "two"})
	if err != nil {
		t.Fatal(err)
	}
	defer end()
	s.signatureOps["one"].cancel()
	if one.Err() != context.Canceled || two.Err() != nil || s.ctx.Err() != nil {
		t.Fatal("cancellation escaped operation ownership")
	}
	finish()
	if _, done, err := s.signatureContext(&signature.Request{OperationID: "one"}); err != nil {
		t.Fatal(err)
	} else {
		done()
	}
	s.stop()
	if two.Err() != context.Canceled {
		t.Fatal("shutdown did not cancel analysis")
	}
	if _, _, err = s.signatureContext(&signature.Request{OperationID: "after-shutdown"}); err == nil {
		t.Fatal("analysis started after shutdown")
	}
}

func TestMain(m *testing.M) {
	if runSignatureParentProbe() {
		return
	}
	if handled, err := signature.WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			os.Exit(1)
		}
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func TestSignatureCancelBeforeRegistrationAndBoundedTombstones(t *testing.T) {
	s := New(os.Stdout)
	s.signatureRoot = t.TempDir()
	s.mu.Lock()
	s.rememberSignatureCancellation("early")
	s.mu.Unlock()
	if _, _, err := s.signatureContext(&signature.Request{OperationID: "early"}); err != context.Canceled {
		t.Fatalf("early cancellation was lost: %v", err)
	}
	s.mu.Lock()
	for i := 0; i < 700; i++ {
		s.rememberSignatureCancellation(string(rune(i + 1000)))
	}
	n := len(s.signatureCancelled)
	s.mu.Unlock()
	if n > 512 {
		t.Fatalf("unbounded cancellation history: %d", n)
	}
	s.stop()
}

func TestSignatureEarlyCancelBeforeInitializationIsRetainedWithoutStartingEngine(t *testing.T) {
	c := connect(t)
	c.send(t, 1, "refactor/cancel", map[string]any{"operationId": "before-init"})
	reply := c.reply(t, 1)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	c.send(t, 2, "tools/status", map[string]any{})
	if c.reply(t, 2)["error"].(map[string]any)["code"] != float64(-32002) {
		t.Fatal("cancellation unexpectedly initialized the engine")
	}
	c.send(t, 3, "shutdown", map[string]any{})
	_ = c.reply(t, 3)
}
