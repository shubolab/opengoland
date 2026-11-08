package tags

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"
	"time"
	"unicode/utf16"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func TestMain(m *testing.M) {
	if handled, e := WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if e != nil {
			os.Stderr.WriteString(e.Error())
			os.Exit(1)
		}
		return
	}
	os.Exit(m.Run())
}
func fixture(t *testing.T, files map[string]string) Request {
	t.Helper()
	root, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	if files["go.mod"] == "" {
		files["go.mod"] = "module example.org/taggen\n\ngo 1.24.0\n"
	}
	for name, text := range files {
		p := filepath.Join(root, name)
		if e = os.MkdirAll(filepath.Dir(p), 0700); e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(p, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	return Request{signature.Request{OperationID: "owned-tag-test", Root: root, Target: signature.Target{URI: signature.LocalURI(filepath.Join(root, "model.go")), Position: signature.Position{Line: 1, Character: 5}}, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}}}
}
func position(text string, offset int) signature.Position {
	line := strings.Count(text[:offset], "\n")
	start := strings.LastIndex(text[:offset], "\n") + 1
	return signature.Position{Line: line, Character: len(utf16.Encode([]rune(text[start:offset])))}
}
func planFor(t *testing.T, request Request, configure func(*PlanRequest, InspectResponse)) PlanResponse {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Second)
	defer cancel()
	info, e := Inspect(ctx, request)
	if e != nil {
		t.Fatal(e)
	}
	plan := PlanRequest{Request: request, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID, Key: "json", Mode: "add-missing", Style: "snake", FieldIDs: []string{}}
	for _, field := range info.Fields {
		if field.Exported && field.Editable && !field.Embedded {
			plan.FieldIDs = append(plan.FieldIDs, field.FieldID)
		}
	}
	if configure != nil {
		configure(&plan, info)
	}
	response, e := Plan(ctx, plan)
	if e != nil {
		t.Fatal(e)
	}
	return response
}
func success(t *testing.T, response PlanResponse) {
	t.Helper()
	if len(response.Conflicts) > 0 || !response.Validation.TypeChecked {
		t.Fatalf("conflicts=%+v validation=%+v", response.Conflicts, response.Validation)
	}
}
func text(response PlanResponse) string {
	if len(response.WorkspaceEdit.DocumentChanges) == 0 {
		return ""
	}
	return response.WorkspaceEdit.DocumentChanges[0].Edits[0].NewText
}
func compile(t *testing.T, req Request, response PlanResponse, testSource string) {
	t.Helper()
	for _, edit := range response.WorkspaceEdit.DocumentChanges {
		path, e := signature.LocalPath(edit.TextDocument.URI)
		if e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(path, []byte(edit.Edits[0].NewText), 0600); e != nil {
			t.Fatal(e)
		}
	}
	if testSource != "" {
		if e := os.WriteFile(filepath.Join(req.Root, "tag_behavior_test.go"), []byte(testSource), 0600); e != nil {
			t.Fatal(e)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "test", "./...")
	cmd.Dir = req.Root
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOPROXY=off", "GOTOOLCHAIN=local", "GOTELEMETRY=off")
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("actual tag fixture failed: %v\n%s", e, out)
	}
}

func TestTagGrammarEscapesOptionalSpacesAndOtherPairsArePreserved(t *testing.T) {
	for _, tag := range []string{`json:"x" xml:"x"`, "json:\"quoted\\\"name\"xml:\"x\"", `db:"column:id;type:text"  json:"x,omitempty" `, "back`tick:\"value\""} {
		pairs, e := parseTag(tag)
		if e != nil {
			t.Fatal(tag, e)
		}
		for _, pair := range pairs {
			value, ok := reflect.StructTag(tag).Lookup(pair.Key)
			if !ok || value != pair.Value {
				t.Fatalf("reflect mismatch %q %+v", tag, pair)
			}
		}
	}
	pairs, e := parseTag(`db:"column:id;type:text"  json:"old,omitempty,string" xml:"kept"`)
	if e != nil {
		t.Fatal(e)
	}
	got, e := changedTag(`db:"column:id;type:text"  json:"old,omitempty,string" xml:"kept"`, pairs, Field{Name: "HTTPServerID"}, PlanRequest{Key: "json", Mode: "update", Style: "snake"})
	if e != nil {
		t.Fatal(e)
	}
	if got != `db:"column:id;type:text"  json:"http_server_id,omitempty,string" xml:"kept"` {
		t.Fatal(got)
	}
	for _, bad := range []string{`json:x`, `json:"unfinished`, "json:\"bad\nvalue\""} {
		if _, e := parseTag(bad); e == nil {
			t.Fatal("malformed tag accepted: " + bad)
		}
	}
	for _, bad := range []string{"", "bad key", "bad:key", "bad\"key", "bad\x7fkey"} {
		if validKey(bad) {
			t.Fatal("invalid key accepted: " + bad)
		}
	}
}

func TestNamingStylesHandleInitialismsDigitsAndUnicode(t *testing.T) {
	for _, row := range [][3]string{{"HTTPServerID", "snake", "http_server_id"}, {"HTTPServerID", "camel", "httpServerId"}, {"HTTPServerID", "lower", "httpserverid"}, {"HTTPServerID", "original", "HTTPServerID"}, {"HTTP2Server", "snake", "http2_server"}, {"ΔName", "snake", "δ_name"}, {"ΔName", "camel", "δName"}} {
		if got := styleName(row[0], row[1]); got != row[2] {
			t.Errorf("%v: %q", row, got)
		}
	}
}

func TestAddMissingPreservesExistingTagsAndActualJSONBehavior(t *testing.T) {
	source := "package fixture\ntype User struct { HTTPServerID int; Existing int `json:\"custom,omitempty,string\" xml:\"kept\"`; Private string; ignored int }\n"
	req := fixture(t, map[string]string{"model.go": source})
	response := planFor(t, req, func(plan *PlanRequest, info InspectResponse) {
		plan.Style = "camel"
		plan.Options = []string{"omitempty"}
		if info.Name != "User" || info.Fields[3].Exported || !info.Fields[3].Editable {
			t.Fatal(info)
		}
	})
	success(t, response)
	actual, _ := os.ReadFile(filepath.Join(req.Root, "model.go"))
	if string(actual) != source {
		t.Fatal("planner wrote source")
	}
	if !strings.Contains(text(response), `json:"custom,omitempty,string" xml:"kept"`) {
		t.Fatal(text(response))
	}
	compile(t, req, response, `package fixture
import("encoding/json";"reflect";"strings";"testing")
func TestTags(t *testing.T){field,_:=reflect.TypeOf(User{}).FieldByName("HTTPServerID");if field.Tag.Get("json")!="httpServerId,omitempty"{t.Fatal(field.Tag)};data,err:=json.Marshal(User{HTTPServerID:7});if err!=nil||!strings.Contains(string(data),"httpServerId"){t.Fatal(string(data),err)}}
`)
}

func TestUpdateOptionsArePreservedUntilExplicitReplacementAndIgnoredFieldsStayIgnored(t *testing.T) {
	for _, replace := range []bool{false, true} {
		req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{HTTPServerID int `json:\"old,omitempty,string\"`; Hidden string `json:\"-\"`}\n"})
		response := planFor(t, req, func(plan *PlanRequest, _ InspectResponse) { plan.Mode = "update"; plan.ReplaceOptions = replace })
		success(t, response)
		want := "http_server_id,omitempty,string"
		if replace {
			want = "http_server_id"
		}
		if !strings.Contains(text(response), `json:"`+want+`"`) || !strings.Contains(text(response), `json:"-"`) {
			t.Fatal(text(response))
		}
		compile(t, req, response, "")
	}
}

func TestRemoveOnlyTheChosenKeyAndSafelyRepresentBacktickCustomKeys(t *testing.T) {
	literal := strconv.Quote(`json:"x" xml:"x" yaml:"kept"`)
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ X int " + literal + " }\n"})
	removed := planFor(t, req, func(plan *PlanRequest, _ InspectResponse) {
		plan.Key = "xml"
		plan.Mode = "remove"
		plan.Style = "ignored-in-remove"
		plan.Options = []string{""}
	})
	success(t, removed)
	if strings.Contains(text(removed), `xml:`) || !strings.Contains(text(removed), `yaml:`) {
		t.Fatal(text(removed))
	}
	compile(t, req, removed, "")
	custom := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ X int }\n"})
	result := planFor(t, custom, func(plan *PlanRequest, _ InspectResponse) {
		plan.Key = "custom`key"
		plan.Options = []string{"quote\"", "back`tick", "值:with space"}
	})
	success(t, result)
	compile(t, custom, result, "package fixture\nimport(\"reflect\";\"testing\")\nfunc TestCustom(t *testing.T){field,_:=reflect.TypeOf(Model{}).FieldByName(\"X\");if _,ok:=field.Tag.Lookup(\"custom`key\");!ok{t.Fatal(field.Tag)}}\n")
}

func TestGroupedFieldsNeverChangeAnUnselectedSiblingAndAmbiguousTagsAreRejected(t *testing.T) {
	for _, all := range []bool{false, true} {
		req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ A,B int `json:\"shared\" xml:\"kept\"` }\n"})
		response := planFor(t, req, func(plan *PlanRequest, info InspectResponse) {
			plan.Mode = "remove"
			if !all {
				plan.FieldIDs = []string{info.Fields[0].FieldID}
			}
		})
		if all {
			success(t, response)
			if strings.Contains(text(response), `json:`) || !strings.Contains(text(response), `xml:"kept"`) {
				t.Fatal(text(response))
			}
		} else if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
			t.Fatal("partial shared declaration was modified")
		}
	}
	group := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ A,B int }\n"})
	blocked := planFor(t, group, nil)
	if len(blocked.Conflicts) == 0 || len(blocked.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("distinct generated names cannot fit one shared tag")
	}
	duplicate := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ A int `json:\"one\" json:\"two\"` }\n"})
	blocked = planFor(t, duplicate, func(plan *PlanRequest, _ InspectResponse) { plan.Mode = "update" })
	if len(blocked.Conflicts) == 0 {
		t.Fatal("duplicate managed key was silently rewritten")
	}
}

func TestNestedAnonymousGenericEmbeddedAndUTF16DirtySourceMapping(t *testing.T) {
	source := "package fixture\ntype Inner struct{Value int}\ntype Box[T any] struct{ *Inner; private T; Details struct{ ΔName T `yaml:\"keep\"` } }\n"
	req := fixture(t, map[string]string{"model.go": source})
	dirty := strings.Replace(source, "ΔName T", "/*😀*/ ΔName T", 1)
	req.Target.Position = position(dirty, strings.Index(dirty, "yaml:"))
	req.Overlays = []signature.Overlay{{URI: req.Target.URI, Text: dirty, DocumentStamp: 71, LSP: &signature.LSPVersion{Session: 4, Version: 11}}}
	response := planFor(t, req, func(_ *PlanRequest, info InspectResponse) {
		if info.Name != "anonymous struct" || len(info.Fields) != 1 || info.Fields[0].Name != "ΔName" {
			t.Fatal(info)
		}
	})
	success(t, response)
	edit := response.WorkspaceEdit.DocumentChanges[0]
	if edit.TextDocument.Version == nil || *edit.TextDocument.Version != 11 || !strings.Contains(text(response), `json:"δ_name"`) {
		t.Fatal(response)
	}
	actual, _ := os.ReadFile(filepath.Join(req.Root, "model.go"))
	if string(actual) != source {
		t.Fatal("dirty source was saved")
	}
	parent := req
	parent.Target.Position = position(dirty, strings.Index(dirty, "Box["))
	info, e := Inspect(context.Background(), parent)
	if e != nil {
		t.Fatal(e)
	}
	if !info.Fields[0].Embedded || !info.Fields[0].Editable || info.Fields[1].Exported || !info.Fields[1].Editable {
		t.Fatal(info.Fields)
	}
}

func TestTagTypeIdentityAndStaleSnapshotsRejectTheCompleteEdit(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ X int }\nvar _ struct{X int}=Model{}\n"})
	response := planFor(t, req, nil)
	if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 || len(response.Validation.AfterErrors) == 0 {
		t.Fatal("struct type identity was not checked")
	}
	valid := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ X int }\n"})
	info, e := Inspect(context.Background(), valid)
	if e != nil {
		t.Fatal(e)
	}
	os.WriteFile(filepath.Join(valid.Root, "model.go"), []byte("package fixture\ntype Model struct{ X string }\n"), 0600)
	stale, e := Plan(context.Background(), PlanRequest{Request: valid, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID, FieldIDs: []string{info.Fields[0].FieldID}, Key: "json", Mode: "update", Style: "snake"})
	if e != nil {
		t.Fatal(e)
	}
	if len(stale.Conflicts) == 0 || len(stale.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("stale inspection emitted edits")
	}
}

func TestIsolatedTagsWorkerKeepsTheExactWireAndHonorsEarlyCancellation(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{ X int }\n"})
	raw, e := RunIsolated(context.Background(), "inspect", req)
	if e != nil {
		t.Fatal(e)
	}
	var response InspectResponse
	if e = json.Unmarshal(raw, &response); e != nil || len(response.Fields) != 1 || response.Fields[0].Name != "X" {
		t.Fatalf("worker %s %v", raw, e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e = RunIsolated(ctx, "inspect", req); e != context.Canceled {
		t.Fatal(e)
	}
}

func TestDirtyWorkspaceMetadataIsPreservedAndXMLPresetsHaveActualRuntimeBehavior(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{DisplayName string `json:\"kept\"`}\n"})
	response := planFor(t, req, func(plan *PlanRequest, _ InspectResponse) {
		plan.Key = "xml"
		plan.Style = "snake"
		plan.Options = []string{"omitempty"}
	})
	success(t, response)
	compile(t, req, response, `package fixture
import("encoding/xml";"strings";"testing")
func TestXML(t *testing.T){data,err:=xml.Marshal(Model{DisplayName:"yes"});if err!=nil||!strings.Contains(string(data),"<display_name>yes</display_name>"){t.Fatal(string(data),err)}}
`)
	workspace := fixture(t, map[string]string{"go.work": "go 1.24.0\nuse ./a\n", "a/go.mod": "module example.org/a\n\ngo 1.24.0\n", "a/model.go": "package a\ntype Model struct{ Value int }\n", "b/go.mod": "module example.org/b\n\ngo 1.24.0\n", "b/other.go": "package b\nvar Other=1\n"})
	workspace.Target.URI = signature.LocalURI(filepath.Join(workspace.Root, "a/model.go"))
	workspace.Environment["GOWORK"] = filepath.Join(workspace.Root, "go.work")
	workspace.Overlays = []signature.Overlay{{URI: signature.LocalURI(filepath.Join(workspace.Root, "go.work")), Text: "go 1.24.0\nuse (\n./a\n./b\n)\n", DocumentStamp: 81}, {URI: signature.LocalURI(filepath.Join(workspace.Root, "b/go.mod")), Text: "module example.org/b\n\ngo 1.24.0\n// unsaved module metadata\n", DocumentStamp: 82}}
	result := planFor(t, workspace, nil)
	success(t, result)
	if len(result.Coverage.Modules) != 2 {
		t.Fatal("dirty workspace module graph was lost")
	}
	for _, overlay := range workspace.Overlays {
		found := false
		for _, pre := range result.Preconditions {
			if pre.URI == overlay.URI && pre.DocumentStamp != nil && *pre.DocumentStamp == overlay.DocumentStamp {
				found = true
			}
		}
		if !found {
			t.Fatal("lost dirty configuration precondition")
		}
	}
	disk, _ := os.ReadFile(filepath.Join(workspace.Root, "go.work"))
	if strings.Contains(string(disk), "./b") {
		t.Fatal("planner saved dirty metadata")
	}
}
