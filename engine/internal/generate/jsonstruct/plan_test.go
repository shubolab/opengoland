package jsonstruct

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
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
		files["go.mod"] = "module example.org/jsonstruct\n\ngo 1.24.0\n"
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
	req := Request{Request: signature.Request{OperationID: "owned-json-test", Root: root, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}, Target: signature.Target{URI: signature.LocalURI(filepath.Join(root, "model.go")), Position: signature.Position{Line: 0, Character: 0}}}, Mode: "type", TypeName: "Root", JSONText: `{"value":1}`}
	return req
}
func position(text string, offset int) signature.Position {
	line := strings.Count(text[:offset], "\n")
	start := strings.LastIndex(text[:offset], "\n") + 1
	return signature.Position{Line: line, Character: len(utf16.Encode([]rune(text[start:offset])))}
}
func planFor(t *testing.T, req Request, configure func(*PlanRequest, InspectResponse)) PlanResponse {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	info, e := Inspect(ctx, req)
	if e != nil {
		t.Fatal(e)
	}
	request := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID}
	if req.Mode == "fields" {
		for _, field := range info.Fields {
			if field.Editable {
				request.FieldIDs = append(request.FieldIDs, field.FieldID)
			}
		}
	}
	if configure != nil {
		configure(&request, info)
	}
	response, e := Plan(ctx, request)
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
func apply(t *testing.T, response PlanResponse) {
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
}
func text(response PlanResponse) string {
	if len(response.WorkspaceEdit.DocumentChanges) == 0 {
		return ""
	}
	return response.WorkspaceEdit.DocumentChanges[0].Edits[0].NewText
}
func run(t *testing.T, req Request, testSource string) {
	t.Helper()
	if testSource != "" {
		if e := os.WriteFile(filepath.Join(req.Root, "json_runtime_test.go"), []byte(testSource), 0600); e != nil {
			t.Fatal(e)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "test", "./...")
	cmd.Dir = req.Root
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOPROXY=off", "GOTOOLCHAIN=local", "GOTELEMETRY=off")
	out, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("actual JSON fixture failed: %v\n%s", e, out)
	}
}

// These fixtures execute only code in our own temporary projects. Production
// inspect/plan never executes user Go programs.
func roundTrip(t *testing.T, req Request, expression string) {
	t.Helper()
	run(t, req, `package fixture
import("encoding/json";"strings";"testing";"reflect";"math/big")
func equivalent(a,b any)bool{switch x:=a.(type){case json.Number:y,ok:=b.(json.Number);if !ok{return false};if string(x)==string(y){return true};p,pok:=new(big.Rat).SetString(string(x));q,qok:=new(big.Rat).SetString(string(y));return pok&&qok&&p.Cmp(q)==0;case map[string]any:y,ok:=b.(map[string]any);if !ok||len(x)!=len(y){return false};for k,v:=range x{w,ok:=y[k];if !ok||!equivalent(v,w){return false}};return true;case []any:y,ok:=b.([]any);if !ok||len(x)!=len(y){return false};for i:=range x{if !equivalent(x[i],y[i]){return false}};return true;default:return reflect.DeepEqual(a,b)}}
func decode(t *testing.T,b string)any{t.Helper();d:=json.NewDecoder(strings.NewReader(b));d.UseNumber();var v any;if e:=d.Decode(&v);e!=nil{t.Fatal(e)};return v}
func TestSampleRoundTrip(t *testing.T){sample:=`+strconv.Quote(req.JSONText)+`;var value `+expression+`;if e:=json.Unmarshal([]byte(sample),&value);e!=nil{t.Fatal(e)};out,e:=json.Marshal(value);if e!=nil{t.Fatal(e)};if !equivalent(decode(t,sample),decode(t,string(out))){t.Fatalf("sample changed:\n%s\n%s",sample,out)}}
`)
}
func TestNestedArraysMergeSampleShapesAndRetainAbsentNullZeroValuesAtRuntime(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{}\n"})
	req.JSONText = `[{"id":1,"nested":{"zero":0,"keep":false},"sometimes":null,"non_null":0,"list":[],"maybe":null},{"id":2,"nested":{"zero":4,"extra":""},"sometimes":"x","list":[{"n":1},{"n":2,"tag":null}]},{"id":3,"nested":null,"non_null":3,"maybe":[1,null,3]}]`
	response := planFor(t, req, nil)
	success(t, response)
	generated := text(response)
	for _, expected := range []string{"type Root []RootItem", "Sometimes json.RawMessage", "NonNull", "omitempty"} {
		if !strings.Contains(generated, expected) {
			t.Fatalf("missing %s\n%s", expected, generated)
		}
	}
	apply(t, response)
	roundTrip(t, req, "Root")
}
func TestEveryRootKindAndExactNumberFallbackDecodeAndEncode(t *testing.T) {
	samples := []struct{ name, sample, contains string }{
		{"boolean", "true", "type Root bool"}, {"string", `"hello 世界"`, "type Root string"}, {"integer", "-17", "type Root int64"}, {"decimal", "1e3", "type Root float64"}, {"negative_zero", "-0", "type Root float64"},
		{"big_integer", "900719925474099312345678901234567890", "type Root = json.Number"}, {"precise_decimal", "0.100000000000000005", "type Root = json.Number"}, {"huge_exponent", "1e99999", "type Root = json.Number"},
		{"null", "null", "type Root = json.RawMessage"}, {"empty_object", "{}", "type Root struct"}, {"empty_array", "[]", "type Root []json.RawMessage"}, {"mixed_array", `[1,"a",null,{"v":true},[]]`, "type Root []json.RawMessage"},
		{"nullable_array", `[1,null,3]`, "type Root []*int64"}, {"numeric_merge", `[9223372036854775807,0.5]`, "type Root []json.Number"},
	}
	for _, test := range samples {
		t.Run(test.name, func(t *testing.T) {
			req := fixture(t, map[string]string{"model.go": "package fixture\n"})
			req.JSONText = test.sample
			response := planFor(t, req, nil)
			success(t, response)
			if !strings.Contains(text(response), test.contains) {
				t.Fatal(text(response))
			}
			apply(t, response)
			roundTrip(t, req, "Root")
		})
	}
}
func TestNumberPolicyRetainsAllNumericLexemesAndReusesExistingImportAlias(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\nimport wire \"encoding/json\"\nvar _ wire.Number\n"})
	req.JSONText = `{"integer":1,"decimal":0.5,"exponent":1e3}`
	req.NumericPolicy = "number"
	response := planFor(t, req, nil)
	success(t, response)
	if !strings.Contains(text(response), "wire.Number") || strings.Contains(text(response), "json.Number") {
		t.Fatal(text(response))
	}
	apply(t, response)
	roundTrip(t, req, "Root")
}
func TestUnsupportedTagKeysUseLosslessRawMapAndMinusTagIsNotIgnored(t *testing.T) {
	for _, sample := range []string{`{"":1,"normal":2}`, `{"a,b":1,"quote\"":2,"slash\\":3}`, `{"😀":true,"line\n":null}`, "{\"`\":3}", `{"nested":{"":1},"normal":true}`, `{"-":1,"a-b":2,"a b":3,"1":4,"汉字":5}`} {
		t.Run(sample, func(t *testing.T) {
			req := fixture(t, map[string]string{"model.go": "package fixture\n"})
			req.JSONText = sample
			response := planFor(t, req, nil)
			success(t, response)
			if strings.Contains(sample, `"-"`) && !strings.Contains(text(response), "json:\"-,\"") {
				t.Fatal("minus key was not escaped from ignore semantics")
			}
			apply(t, response)
			roundTrip(t, req, "Root")
		})
	}
}
func TestTextOrderFieldNameNormalizationAndNestedDeclarationCollisionsAreDeterministic(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype RootChild struct{}\n"})
	req.JSONText = `{"z-last":1,"child":{"value":2},"a b":3,"a-b":4,"é":5,"_":6}`
	response := planFor(t, req, func(_ *PlanRequest, info InspectResponse) {
		names := []string{}
		for _, f := range info.Fields {
			names = append(names, f.Name)
		}
		if strings.Join(names, ",") != "ZLast,Child,AB,AB2,É,Field" {
			t.Fatal(names)
		}
	})
	success(t, response)
	if !strings.Contains(text(response), "RootChild2") {
		t.Fatal(text(response))
	}
	apply(t, response)
	roundTrip(t, req, "Root")
}
func TestAddingFieldsPreservesGenericGroupedPrivateEmbeddedTagsAndComments(t *testing.T) {
	source := "package fixture\nimport raw \"encoding/json\"\ntype Embedded struct{Hidden string `json:\"promoted,omitempty\"`}\ntype Model[T any] struct { /*keep 😀*/ A,B T `json:\"-\"`; private int; *Embedded; Old raw.RawMessage `json:\"old,omitempty\"` }\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `{"new":{"value":1},"nullable":null,"-":2}`
	req.Target.Position = position(source, strings.Index(source, "private"))
	response := planFor(t, req, nil)
	success(t, response)
	for _, expected := range []string{"keep 😀", "private int", "A, B", "json:\"-\"", "Old", "raw.RawMessage", "type ModelNew struct"} {
		if !strings.Contains(text(response), expected) {
			t.Fatalf("lost %s\n%s", expected, text(response))
		}
	}
	apply(t, response)
	roundTrip(t, req, "Model[int]")
}
func TestAnonymousStructAndObjectArrayProjectionSupportSelectedSubsetInTextOrder(t *testing.T) {
	source := "package fixture\nvar Value struct { Inner struct{/*😀*/ Existing int `json:\"existing,omitempty\"`} }\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `[{"later":null,"first":1},{"first":2,"other":""}]`
	req.Target.Position = position(source, strings.Index(source, "Existing"))
	response := planFor(t, req, func(p *PlanRequest, i InspectResponse) {
		if i.Name != "anonymous struct" || len(i.Fields) != 3 {
			t.Fatal(i)
		}
		p.FieldIDs = []string{i.Fields[2].FieldID, i.Fields[1].FieldID}
	})
	success(t, response)
	if strings.Contains(text(response), "Later ") || strings.Index(text(response), "First ") > strings.Index(text(response), "Other ") {
		t.Fatal(text(response))
	}
	apply(t, response)
	run(t, req, "package fixture\nimport \"testing\"\nfunc TestAnonymous(t *testing.T){Value.Inner.First=3;if Value.Inner.First!=3{t.Fatal(Value)}}\n")
}
func TestFieldAndJSONKeyConflictsNeverOverwriteExistingOrPromotedFields(t *testing.T) {
	source := "package fixture\ntype Embedded struct{Promoted string `json:\"reserved\"`}\ntype Model struct{Existing int `json:\"old\"`; *Embedded}\nfunc(*Model)Method(){}\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `{"existing":1,"old":2,"reserved":3,"method":4,"":5,"safe":6}`
	req.Target.Position = position(source, strings.Index(source, "Model struct"))
	response := planFor(t, req, func(p *PlanRequest, i InspectResponse) {
		if len(i.Fields) != 6 {
			t.Fatal(i)
		}
		for j, f := range i.Fields {
			if f.Editable != (j == 5) {
				t.Fatal(i.Fields)
			}
		}
		p.FieldIDs = []string{i.Fields[0].FieldID, i.Fields[5].FieldID}
	})
	if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("conflicts emitted partial changes")
	}
	valid := planFor(t, req, nil)
	success(t, valid)
	if !strings.Contains(text(valid), "Safe int64") {
		t.Fatal(text(valid))
	}
}
func TestExistingNameMalformedInputAndStaleAnalysisAreRejected(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Root struct{}\n"})
	response := planFor(t, req, nil)
	if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("root name collision accepted")
	}
	for _, sample := range []string{"", `{"a":1,"a":2}`, `{} []`, `{"broken":}`, `{"\ud800":1}`, `"\udc00"`} {
		req.JSONText = sample
		if _, e := Inspect(context.Background(), req); e == nil {
			t.Fatal("malformed/ambiguous sample accepted: " + sample)
		}
	}
	req.TypeName = "Renamed"
	req.JSONText = `{"first":1}`
	info, e := Inspect(context.Background(), req)
	if e != nil {
		t.Fatal(e)
	}
	request := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID}
	request.JSONText = `{"changed":2}`
	stale, e := Plan(context.Background(), request)
	if e != nil {
		t.Fatal(e)
	}
	if len(stale.Conflicts) == 0 || len(stale.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("changed sample reused analysis")
	}
}
func TestDirtyUnicodeUTF16MetadataAndTypedCandidateGuardDoNotSaveInputs(t *testing.T) {
	source := "package fixture\n// 中文 😀\ntype Model[T any] struct{Old T}\n"
	req := fixture(t, map[string]string{"model.go": source})
	dirty := strings.Replace(source, "Old T", "Old []T", 1) + "// unsaved 😀\n"
	req.Mode = "fields"
	req.JSONText = `{"new":1}`
	req.Target.Position = position(dirty, strings.Index(dirty, "Old"))
	req.Overlays = []signature.Overlay{{URI: req.Target.URI, Text: dirty, DocumentStamp: 77, LSP: &signature.LSPVersion{Session: 6, Version: 12}}, {URI: signature.LocalURI(filepath.Join(req.Root, "go.mod")), Text: "module example.org/jsonstruct\n\ngo 1.24.0\n// dirty metadata\n", DocumentStamp: 78}}
	response := planFor(t, req, nil)
	success(t, response)
	edit := response.WorkspaceEdit.DocumentChanges[0]
	if edit.TextDocument.Version == nil || *edit.TextDocument.Version != 12 || !strings.Contains(text(response), "[]T") || !strings.Contains(text(response), "unsaved 😀") {
		t.Fatal(response)
	}
	for _, overlay := range req.Overlays {
		found := false
		for _, pre := range response.Preconditions {
			if pre.URI == overlay.URI && pre.DocumentStamp != nil && *pre.DocumentStamp == overlay.DocumentStamp {
				found = true
			}
		}
		if !found {
			t.Fatal("missing dirty precondition")
		}
	}
	disk, _ := os.ReadFile(filepath.Join(req.Root, "model.go"))
	if string(disk) != source {
		t.Fatal("saved source")
	}
	mod, _ := os.ReadFile(filepath.Join(req.Root, "go.mod"))
	if strings.Contains(string(mod), "dirty") {
		t.Fatal("saved metadata")
	}
}
func TestFullWorkspaceTypecheckRejectsChangedAnonymousStructIdentity(t *testing.T) {
	source := "package fixture\nvar Model struct{Value int}\nvar _ struct{Value int}=Model\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `{"added":1}`
	req.Target.Position = position(source, strings.Index(source, "Value int"))
	response := planFor(t, req, nil)
	if response.Validation.TypeChecked || len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("assignability-breaking field addition accepted")
	}
}
func TestPredeclaredTypeShadowingCannotSilentlyChangeInferredWireTypes(t *testing.T) {
	for _, source := range []string{"package fixture\ntype int64 string\ntype Model struct{}\n", "package fixture\ntype Model[int64 any] struct{}\n"} {
		req := fixture(t, map[string]string{"model.go": source})
		req.Mode = "fields"
		req.JSONText = `{"value":1}`
		req.Target.Position = position(source, strings.Index(source, "Model"))
		response := planFor(t, req, nil)
		if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
			t.Fatal("shadowed builtin silently accepted")
		}
	}
}
func TestIsolatedJSONWorkerReturnsGuardedPlansAndHonorsCancellation(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\n"})
	req.JSONText = `{"n":9007199254740993}`
	raw, e := RunIsolated(context.Background(), "inspect", req)
	if e != nil {
		t.Fatal(e)
	}
	var info InspectResponse
	if e = json.Unmarshal(raw, &info); e != nil || info.StructID == "" {
		t.Fatalf("%s %v", raw, e)
	}
	request := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID}
	raw, e = RunIsolated(context.Background(), "plan", request)
	if e != nil {
		t.Fatal(e)
	}
	var plan PlanResponse
	if e = json.Unmarshal(raw, &plan); e != nil {
		t.Fatal(e)
	}
	success(t, plan)
	if len(plan.Preconditions) == 0 || len(plan.Directories) == 0 {
		t.Fatal("missing guard")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e = RunIsolated(ctx, "inspect", req); e != context.Canceled {
		t.Fatal(e)
	}
}
func TestGenericAndLocalLexicalNamesCannotCaptureNestedTypesOrNewJSONImport(t *testing.T) {
	source := "package fixture\ntype Model[ModelChild any,json any] struct{}\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `{"child":{"n":1},"raw":null}`
	req.Target.Position = position(source, strings.Index(source, "Model"))
	response := planFor(t, req, nil)
	success(t, response)
	if !strings.Contains(text(response), "type ModelChild2 struct") || !strings.Contains(text(response), "json2.RawMessage") {
		t.Fatal(text(response))
	}
	apply(t, response)
	roundTrip(t, req, "Model[int,int]")
	local := "package fixture\nfunc f(){type GeneratedChild int;var model struct{};_ = model;var _ GeneratedChild}\n"
	req = fixture(t, map[string]string{"model.go": local})
	req.Mode = "fields"
	req.JSONText = `{"child":{"n":1}}`
	req.Target.Position = position(local, strings.Index(local, "struct{}"))
	response = planFor(t, req, nil)
	success(t, response)
	if !strings.Contains(text(response), "type GeneratedChild2 struct") {
		t.Fatal(text(response))
	}
}
func TestTaggedPrivateEmbeddedStructJSONKeyIsProtected(t *testing.T) {
	source := "package fixture\ntype hidden struct{Value int}\ntype Model struct{hidden `json:\"reserved\"`}\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `{"reserved":1}`
	req.Target.Position = position(source, strings.Index(source, "Model"))
	response := planFor(t, req, func(p *PlanRequest, i InspectResponse) {
		if len(i.Fields) != 1 || i.Fields[0].Editable {
			t.Fatal(i.Fields)
		}
		p.FieldIDs = []string{i.Fields[0].FieldID}
	})
	if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("private embedded JSON key was overwritten")
	}
}
func TestStaleDependencyAndConfigurationRejectAllChanges(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\nvar _=Dependency\n", "dependency.go": "package fixture\nconst Dependency=1\n"})
	info, e := Inspect(context.Background(), req)
	if e != nil {
		t.Fatal(e)
	}
	request := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID}
	if e = os.WriteFile(filepath.Join(req.Root, "dependency.go"), []byte("package fixture\nconst Dependency=2\n"), 0600); e != nil {
		t.Fatal(e)
	}
	response, e := Plan(context.Background(), request)
	if e != nil {
		t.Fatal(e)
	}
	if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("changed source dependency reused analysis")
	}
	info, e = Inspect(context.Background(), req)
	if e != nil {
		t.Fatal(e)
	}
	request = PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID}
	request.BuildTags = []string{"new-context"}
	response, e = Plan(context.Background(), request)
	if e != nil {
		t.Fatal(e)
	}
	if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("changed build context reused analysis")
	}
}
func TestDirtyWorkspaceGraphCanResolveUsesOfTheGeneratedRootWithoutSavingMetadata(t *testing.T) {
	req := fixture(t, map[string]string{"go.work": "go 1.24.0\nuse ./a\n", "a/go.mod": "module example.org/a\n\ngo 1.24.0\n", "a/model.go": "package a\n", "b/go.mod": "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ./missing\n", "b/use.go": "package b\nimport \"example.org/a\"\nvar _=a.Root{Value:1}\n"})
	req.Target.URI = signature.LocalURI(filepath.Join(req.Root, "a/model.go"))
	req.Environment["GOWORK"] = filepath.Join(req.Root, "go.work")
	req.Overlays = []signature.Overlay{{URI: signature.LocalURI(filepath.Join(req.Root, "go.work")), Text: "go 1.24.0\nuse (\n./a\n./b\n)\n", DocumentStamp: 81}, {URI: signature.LocalURI(filepath.Join(req.Root, "b/go.mod")), Text: "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ../a\n", DocumentStamp: 82}}
	response := planFor(t, req, nil)
	success(t, response)
	if len(response.Validation.BeforeErrors) == 0 || len(response.Coverage.Modules) != 2 {
		t.Fatal("missing generated-type use or dirty graph was not checked")
	}
	for _, overlay := range req.Overlays {
		found := false
		for _, pre := range response.Preconditions {
			if pre.URI == overlay.URI && pre.DocumentStamp != nil && *pre.DocumentStamp == overlay.DocumentStamp {
				found = true
			}
		}
		if !found {
			t.Fatal("lost metadata guard")
		}
	}
	actual, _ := os.ReadFile(filepath.Join(req.Root, "go.work"))
	if strings.Contains(string(actual), "./b") {
		t.Fatal("saved workspace graph")
	}
}
func TestNullableObjectArrayFieldsProjectionExplainsPointerSliceAndRoundTrips(t *testing.T) {
	source := "package fixture\ntype Model struct{}\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `[null,{"value":1},{"value":2}]`
	req.Target.Position = position(source, strings.Index(source, "Model"))
	response := planFor(t, req, func(_ *PlanRequest, i InspectResponse) {
		found := false
		for _, w := range i.Warnings {
			if w.Code == "array-fields" && strings.Contains(w.Message, "pointers") {
				found = true
			}
		}
		if !found {
			t.Fatal("null array projection omitted pointer guidance")
		}
	})
	success(t, response)
	apply(t, response)
	roundTrip(t, req, "[]*Model")
}
func TestInitialismNamesRetainDistinctRawKeysAndRuntimeRoundTrip(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\n"})
	req.JSONText = `{"id":1,"ID":2,"trace_id":"first","traceId":"second","userId":3,"api_url":"https://a","http_status":200,"HTTPStatus":201,"jsonData":{"requestUUID":"x"},"123id":4,"汉字_id":5,"utf8Text":"世界"}`
	response := planFor(t, req, func(_ *PlanRequest, info InspectResponse) {
		expected := []string{"ID", "ID2", "TraceID", "TraceID2", "UserID", "APIURL", "HTTPStatus", "HTTPStatus2", "JSONData", "Field123ID", "Field汉字ID", "UTF8Text"}
		if len(info.Fields) != len(expected) {
			t.Fatal(info.Fields)
		}
		for i, name := range expected {
			if info.Fields[i].Name != name {
				t.Fatalf("key %q inferred %q, want %q", info.Fields[i].JSONKey, info.Fields[i].Name, name)
			}
		}
	})
	success(t, response)
	if !strings.Contains(text(response), "type RootJSONData struct") || !strings.Contains(text(response), "RequestUUID string") {
		t.Fatal(text(response))
	}
	apply(t, response)
	roundTrip(t, req, "Root")
}
func TestInitialismFieldAndNestedNameCollisionsRejectOrSuffixWithoutChangingKeys(t *testing.T) {
	source := "package fixture\ntype ModelAPIURL struct{}\ntype Model struct{ID int `json:\"old,omitempty\"`;TraceID string `json:\"trace,omitempty\"`}\nfunc(*Model)HTTPStatus(){}\n"
	req := fixture(t, map[string]string{"model.go": source})
	req.Mode = "fields"
	req.JSONText = `{"id":1,"ID":2,"trace_id":"a","http_status":200,"api_url":{"userId":3}}`
	req.Target.Position = position(source, strings.Index(source, "Model struct"))
	response := planFor(t, req, func(p *PlanRequest, info InspectResponse) {
		for i, editable := range []bool{false, true, false, false, true} {
			if info.Fields[i].Editable != editable {
				t.Fatal(info.Fields)
			}
		}
		p.FieldIDs = []string{info.Fields[0].FieldID, info.Fields[4].FieldID}
	})
	if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("initialism collision produced a partial edit")
	}
	accepted := planFor(t, req, nil)
	success(t, accepted)
	if !strings.Contains(text(accepted), "type ModelAPIURL2 struct") || !strings.Contains(text(accepted), "UserID int64") || !strings.Contains(text(accepted), "json:\"ID\"") {
		t.Fatal(text(accepted))
	}
	apply(t, accepted)
	run(t, req, `package fixture
import("encoding/json";"testing")
func TestInitialismSelection(t *testing.T){var value Model;if e:=json.Unmarshal([]byte("{\"ID\":2,\"api_url\":{\"userId\":3}}"),&value);e!=nil{t.Fatal(e)};if value.ID!=0||value.ID2!=2||value.APIURL.UserID!=3{t.Fatal(value)};out,e:=json.Marshal(value);if e!=nil{t.Fatal(e)};var keys map[string]json.RawMessage;if e=json.Unmarshal(out,&keys);e!=nil{t.Fatal(e)};if _,ok:=keys["ID"];!ok{t.Fatal(string(out))};if _,ok:=keys["id"];ok{t.Fatal("selected raw key changed",string(out))}}
`)
}
