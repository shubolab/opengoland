package rpc

import (
	pprof "github.com/google/pprof/profile"
	"os"
	"path/filepath"
	"testing"
)

func TestProfileInspectProtocolPreservesSourceAndMetricValues(t *testing.T) {
	root := t.TempDir()
	_ = os.WriteFile(filepath.Join(root, "go.mod"), []byte("module example.org/rpcprofile\n\ngo 1.24.0\n"), 0600)
	function := &pprof.Function{ID: 1, Name: "example.org/rpcprofile.Work", Filename: "/source/真实目录 with spaces/work.go", StartLine: 17}
	location := &pprof.Location{ID: 1, Line: []pprof.Line{{Function: function, Line: 19}}}
	profile := &pprof.Profile{SampleType: []*pprof.ValueType{{Type: "cpu", Unit: "nanoseconds"}}, Function: []*pprof.Function{function}, Location: []*pprof.Location{location}, Sample: []*pprof.Sample{{Location: []*pprof.Location{location}, Value: []int64{37000000}}}}
	path := filepath.Join(t.TempDir(), "outside-project.pprof")
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = profile.Write(file); err != nil {
		t.Fatal(err)
	}
	_ = file.Close()
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	if reply := c.reply(t, 1); reply["error"] != nil {
		t.Fatal(reply)
	}
	c.send(t, 2, "profile/inspect", map[string]any{"path": path, "sampleIndex": 0})
	reply := c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	result := reply["result"].(map[string]any)
	row := result["rows"].([]any)[0].(map[string]any)
	if row["file"] != function.Filename || row["line"] != float64(17) || row["flat"] != float64(37000000) || row["cumulative"] != float64(37000000) {
		t.Fatal(reply)
	}
	c.send(t, 3, "profile/inspect", map[string]any{"path": path, "sampleIndex": 9})
	if c.reply(t, 3)["error"] == nil {
		t.Fatal("invalid metric accepted")
	}
	c.send(t, 4, "shutdown", map[string]any{})
	c.reply(t, 4)
}
