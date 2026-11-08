package engine

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	pprof "github.com/google/pprof/profile"
)

func TestProfileInlineRecursionAndMetricSelection(t *testing.T) {
	a := &pprof.Function{ID: 1, Name: "inline", Filename: "/exact/source/inline.go", StartLine: 11}
	b := &pprof.Function{ID: 2, Name: "caller", Filename: "example.org/mod/caller.go", StartLine: 21}
	p := &pprof.Profile{SampleType: []*pprof.ValueType{{Type: "count", Unit: "count"}, {Type: "cpu", Unit: "nanoseconds"}}, Function: []*pprof.Function{a, b}, DefaultSampleType: "cpu"}
	leaf := &pprof.Location{ID: 1, Line: []pprof.Line{{Function: a, Line: 12}, {Function: b, Line: 22}}}
	recursive := &pprof.Location{ID: 2, Line: []pprof.Line{{Function: b, Line: 25}}}
	p.Location = []*pprof.Location{leaf, recursive}
	p.Sample = []*pprof.Sample{{Location: p.Location, Value: []int64{2, 500}}}
	report, err := summarizeProfile(context.Background(), p, ProfileInspectRequest{})
	if err != nil {
		t.Fatal(err)
	}
	if report.SampleIndex != 1 || report.SampleTypes[1].Total != 500 || len(report.Rows) != 2 {
		t.Fatal(report)
	}
	for _, row := range report.Rows {
		if row.Name == "inline" && (row.Flat != 500 || row.Cumulative != 500 || row.File != a.Filename || row.Line != 11) {
			t.Fatal(row)
		}
		if row.Name == "caller" && (row.Flat != 0 || row.Cumulative != 500) {
			t.Fatal("recursion counted twice", row)
		}
	}
	index := 0
	report, err = summarizeProfile(context.Background(), p, ProfileInspectRequest{SampleIndex: &index, Limit: 1, Sort: "cumulative"})
	if err != nil || report.Rows[0].Cumulative != 2 || !report.Truncated {
		t.Fatal(report, err)
	}
}

func profileFixture(t *testing.T) string {
	t.Helper()
	root := t.TempDir()
	root, _ = filepath.EvalSymlinks(root)
	write := func(name, text string) {
		if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	write("go.mod", "module example.org/profilefixture\n\ngo 1.24.0\n")
	write("work.go", `package profilefixture
import ("runtime";"sync";"time")
var Retained [][]byte
var Sink uint64
//go:noinline
func CPUWork() { until:=time.Now().Add(250*time.Millisecond); var n uint64; for time.Now().Before(until) { for i:=0;i<20000;i++ {n=n*1664525+1013904223} }; Sink=n }
//go:noinline
func MemoryWork() { for i:=0;i<64;i++ {Retained=append(Retained,make([]byte,16384))};runtime.GC() }
//go:noinline
func BlockWork() { ch:=make(chan struct{}); go func(){time.Sleep(15*time.Millisecond);close(ch)}(); <-ch }
//go:noinline
func MutexWork() { var m sync.Mutex; m.Lock(); done:=make(chan struct{});go func(){m.Lock();m.Unlock();close(done)}();time.Sleep(15*time.Millisecond);m.Unlock();<-done }
`)
	write("work_test.go", `//go:build profilingfixture
package profilefixture
import ("os";"testing";"time")
func TestWork(t *testing.T) { if os.Getenv("PROFILE_FIXTURE_ENV")!="present" {t.Fatal("environment was lost")}; CPUWork();MemoryWork();BlockWork();MutexWork() }
func TestLong(t *testing.T) { println("PROFILE_LONG_READY");for {time.Sleep(time.Second)} }
func BenchmarkWork(b *testing.B) {for b.Loop(){CPUWork();MemoryWork();BlockWork();MutexWork()}}
`)
	return root
}

func TestRealFourProfilesMatchOfficialTopProto(t *testing.T) {
	root := profileFixture(t)
	r := newRecorder()
	m, err := New(root, "", r.notify)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	for _, kind := range []string{"cpu", "memory", "block", "mutex"} {
		t.Run(kind, func(t *testing.T) {
			start(t, m, TaskRequest{ID: kind, Command: "profile", Profile: &ProfileOptions{Kind: kind, Mode: "test", Target: ".", Pattern: "^TestWork$"}, Env: map[string]string{"GOWORK": "off", "GOTOOLCHAIN": "local", "GOPROXY": "off", "GOFLAGS": "-tags=profilingfixture", "PROFILE_FIXTURE_ENV": "present"}})
			event := r.wait(t, exited(kind))
			if event.Params["code"].(float64) != 0 {
				t.Fatal(event, r.snapshot())
			}
			artifact := event.Params["profile"].(map[string]any)
			path := artifact["path"].(string)
			defer os.RemoveAll(filepath.Dir(path))
			report, err := m.InspectProfile(context.Background(), ProfileInspectRequest{Path: path, Limit: 5000})
			if err != nil {
				t.Fatal(err)
			}
			metric := report.SampleTypes[report.SampleIndex]
			if metric.Total <= 0 {
				t.Fatal("empty profile", report)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			output, err := commandOutput(ctx, m.goExecutable, root, nil, "tool", "pprof", "-topproto", "-filefunctions", "-symbolize=none", "-nodecount=0", "-nodefraction=0", "-edgefraction=0", fmt.Sprintf("-sample_index=%d", report.SampleIndex), "-unit="+metric.Unit, path)
			if err != nil {
				t.Fatal(err)
			}
			official, err := pprof.ParseData(output)
			if err != nil {
				t.Fatal(err)
			}
			actual := map[string]ProfileHotspot{}
			for _, row := range report.Rows {
				// -filefunctions deliberately aggregates away line numbers, while our
				// navigation keeps the original function's start line.
				key := fmt.Sprintf("%s\x00%s", row.Name, row.File)
				value := actual[key]
				value.Flat += row.Flat
				value.Cumulative += row.Cumulative
				actual[key] = value
			}
			compared := 0
			for _, sample := range official.Sample {
				f := sample.Location[0].Line[0].Function
				key := fmt.Sprintf("%s\x00%s", f.Name, f.Filename)
				row, ok := actual[key]
				if !ok {
					t.Fatalf("missing official hotspot %q", key)
				}
				if row.Cumulative != sample.Value[0] || row.Flat != sample.Value[1] {
					t.Fatalf("official values %v disagree with %+v", sample.Value, row)
				}
				compared++
			}
			if compared == 0 {
				t.Fatal("official pprof had no hotspots")
			}
			foundSource := false
			for _, row := range report.Rows {
				if strings.Contains(row.Name, "example.org/profilefixture.") && row.File == filepath.Join(root, "work.go") && row.Line > 0 {
					foundSource = true
				}
			}
			if !foundSource {
				t.Fatal("no exact fixture source navigation metadata", report.Rows)
			}
		})
	}
	if binaries, _ := filepath.Glob(filepath.Join(root, "*.test")); len(binaries) != 0 {
		t.Fatal("profiling polluted the source directory", binaries)
	}
	start(t, m, TaskRequest{ID: "benchmark-profile", Command: "profile", Args: []string{"-benchtime=1x"}, Profile: &ProfileOptions{Kind: "cpu", Mode: "benchmark", Target: ".", Pattern: "^BenchmarkWork$"}, Env: map[string]string{"GOWORK": "off", "GOTOOLCHAIN": "local", "GOPROXY": "off", "GOFLAGS": "-tags=profilingfixture"}})
	event := r.wait(t, exited("benchmark-profile"))
	if event.Params["code"].(float64) != 0 {
		t.Fatal(event)
	}
	path := event.Params["profile"].(map[string]any)["path"].(string)
	defer os.RemoveAll(filepath.Dir(path))
	if report, err := m.InspectProfile(context.Background(), ProfileInspectRequest{Path: path}); err != nil || report.SampleTypes[report.SampleIndex].Total <= 0 {
		t.Fatal("benchmark profile did not record samples", report, err)
	}
}

func TestProfileValidationCorruptionAndCancellation(t *testing.T) {
	root := profileFixture(t)
	r := newRecorder()
	m, err := New(root, "", r.notify)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	for _, req := range []TaskRequest{{Command: "profile"}, {Command: "profile", Profile: &ProfileOptions{Kind: "cpu", Mode: "test", Target: "./..."}}, {Command: "profile", Profile: &ProfileOptions{Kind: "cpu", Mode: "test"}, Args: []string{"-test.cpuprofile=elsewhere"}}} {
		req.ID = "invalid"
		if _, _, err := m.Prepare(req); err == nil {
			t.Fatal("accepted invalid profile", req)
		}
	}
	bad := filepath.Join(root, "bad.pprof")
	_ = os.WriteFile(bad, []byte("not a profile"), 0600)
	if _, err := m.InspectProfile(context.Background(), ProfileInspectRequest{Path: bad}); err == nil {
		t.Fatal("accepted corrupt profile")
	}
	start(t, m, TaskRequest{ID: "cancel-profile", Command: "profile", Profile: &ProfileOptions{Kind: "cpu", Mode: "test", Pattern: "^TestLong$"}, Env: map[string]string{"GOWORK": "off", "GOFLAGS": "-tags=profilingfixture"}})
	r.wait(t, func(o observation) bool {
		return o.Method == "task/output" && strings.Contains(fmt.Sprint(o.Params["text"]), "PROFILE_LONG_READY")
	})
	if _, err := m.Cancel("cancel-profile"); err != nil {
		t.Fatal(err)
	}
	event := r.wait(t, exited("cancel-profile"))
	if event.Params["cancelled"] != true || event.Params["profile"] != nil {
		t.Fatal(event)
	}
	for _, o := range r.snapshot() {
		if o.Method == "task/step" && o.Params["id"] == "cancel-profile" {
			for _, v := range o.Params["args"].([]any) {
				arg := v.(string)
				if strings.HasPrefix(arg, "-cpuprofile=") {
					if _, err := os.Stat(strings.TrimPrefix(arg, "-cpuprofile=")); !os.IsNotExist(err) {
						t.Fatal("cancelled profile artifact retained", err)
					}
				}
			}
		}
	}
}

func TestVendorUsesEffectiveWorkspaceAndExplicitOff(t *testing.T) {
	m, r, root := newManager(t)
	for _, tc := range []struct {
		id, cwd, mode string
		env           map[string]string
	}{{"workspace", "", "work", nil}, {"nested-workspace", "app", "work", nil}, {"module", "app", "mod", map[string]string{"GOWORK": "off"}}} {
		start(t, m, TaskRequest{ID: tc.id, Command: "vendor", CWD: tc.cwd, Env: tc.env})
		event := r.wait(t, exited(tc.id))
		if event.Params["code"].(float64) != 0 {
			t.Fatal(event, r.snapshot())
		}
		matched := false
		for _, o := range r.snapshot() {
			if o.Method == "task/step" && o.Params["id"] == tc.id {
				args := o.Params["args"].([]any)
				if len(args) >= 2 && args[0] == tc.mode && args[1] == "vendor" {
					matched = true
				}
			}
		}
		if !matched {
			t.Fatal("wrong vendor mode", tc.id, r.snapshot())
		}
	}
	if _, err := os.Stat(filepath.Join(root, "vendor", "modules.txt")); err != nil {
		t.Fatal("workspace vendor missing", err)
	}
	if _, err := os.Stat(filepath.Join(root, "app", "vendor", "example.org", "openidea", "library", "sum.go")); err != nil {
		// Verify the actual local replacement was copied without assuming its source basename.
		entries, _ := os.ReadDir(filepath.Join(root, "app", "vendor", "example.org", "openidea", "library"))
		if len(entries) == 0 {
			t.Fatal("module replacement was not vendored", err)
		}
	}
}
