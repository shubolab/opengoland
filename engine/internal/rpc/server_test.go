package rpc

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/shubolab/opengoland/engine/internal/protocol"
)

type client struct {
	input    *io.PipeWriter
	writer   *protocol.Writer
	messages chan map[string]any
	done     chan error
	output   *io.PipeReader
}

func connect(t *testing.T) *client {
	t.Helper()
	in, input := io.Pipe()
	output, out := io.Pipe()
	c := &client{input: input, writer: protocol.NewWriter(input), messages: make(chan map[string]any, 1024), done: make(chan error, 1), output: output}
	go func() { c.done <- New(out).Serve(in); _ = out.Close() }()
	go func() {
		reader := protocol.NewReader(output)
		defer close(c.messages)
		for {
			data, err := reader.Read()
			if err != nil {
				return
			}
			var message map[string]any
			if json.Unmarshal(data, &message) != nil {
				return
			}
			c.messages <- message
		}
	}()
	t.Cleanup(func() { _ = input.Close(); _ = output.Close(); _ = in.Close() })
	return c
}

func (c *client) send(t *testing.T, id int, method string, params any) {
	t.Helper()
	if err := c.writer.Write(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params}); err != nil {
		t.Fatal(err)
	}
}

func (c *client) next(t *testing.T) map[string]any {
	t.Helper()
	select {
	case m, ok := <-c.messages:
		if !ok {
			t.Fatal("engine transport closed")
		}
		return m
	case <-time.After(45 * time.Second):
		t.Fatal("timed out waiting for RPC message")
	}
	return nil
}

func (c *client) reply(t *testing.T, id int) map[string]any {
	t.Helper()
	for {
		m := c.next(t)
		if m["id"] == float64(id) {
			return m
		}
	}
}

func TestProtocolRealTestEventsAndResponseOrdering(t *testing.T) {
	t.Setenv("GOTOOLCHAIN", "local")
	t.Setenv("GOWORK", "")
	root := t.TempDir()
	for name, text := range map[string]string{
		"go.mod":         "module example.org/rpcfixture\n\ngo 1.24.0\n",
		"sample_test.go": "package rpcfixture\nimport \"testing\"\nfunc TestFailure(t *testing.T) { t.Fatal(\"expected failure\") }\n",
	} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0644); err != nil {
			t.Fatal(err)
		}
	}
	c := connect(t)
	c.send(t, 1, "tools/status", map[string]any{})
	if err := c.reply(t, 1)["error"].(map[string]any)["code"]; err != float64(-32002) {
		t.Fatal(err)
	}
	c.send(t, 2, "initialize", map[string]any{"root": root})
	initialized := c.reply(t, 2)
	if initialized["error"] != nil {
		t.Fatal(initialized)
	}
	result := initialized["result"].(map[string]any)
	if result["protocolVersion"] != float64(1) || len(result["tools"].([]any)) != 3 {
		t.Fatal(result)
	}
	c.send(t, 3, "task/start", map[string]any{"id": "test", "command": "test"})
	accepted := c.next(t)
	if accepted["id"] != float64(3) || accepted["error"] != nil {
		t.Fatal("task events arrived before accepted response", accepted)
	}
	sawFailure := false
	for {
		message := c.next(t)
		if message["method"] == "test/event" {
			event := message["params"].(map[string]any)["event"].(map[string]any)
			if event["Action"] == "fail" && event["Test"] == "TestFailure" {
				sawFailure = true
			}
		}
		if message["method"] == "task/exit" {
			params := message["params"].(map[string]any)
			if params["code"] == float64(0) || !sawFailure {
				t.Fatal("failed test not represented", params, sawFailure)
			}
			break
		}
	}
	c.send(t, 4, "shutdown", map[string]any{})
	if m := c.reply(t, 4); m["error"] != nil {
		t.Fatal(m)
	}
	select {
	case err := <-c.done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("shutdown did not exit")
	}
}

func TestProtocolParseAndInvalidRequestErrors(t *testing.T) {
	c := connect(t)
	for _, test := range []struct {
		body string
		code float64
	}{{"{", -32700}, {"[]", -32600}, {`{"jsonrpc":"1.0","id":1,"method":"initialize"}`, -32600}} {
		if _, err := fmt.Fprintf(c.input, "Content-Length: %d\r\n\r\n%s", len(test.body), test.body); err != nil {
			t.Fatal(err)
		}
		m := c.next(t)
		if m["error"].(map[string]any)["code"] != test.code {
			t.Fatal(m)
		}
	}
	c.send(t, 8, "shutdown", map[string]any{})
	_ = c.reply(t, 8)
	select {
	case <-c.done:
	case <-time.After(3 * time.Second):
		t.Fatal("shutdown timed out")
	}
}
