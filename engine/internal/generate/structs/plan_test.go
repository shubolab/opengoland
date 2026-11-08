package structs

import (
	"context"
	"encoding/json"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"os/exec"
	"path/filepath"
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
		files["go.mod"] = "module example.org/structhelpers\n\ngo 1.24.0\n"
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
	req := Request{signature.Request{OperationID: "owned-struct-test", Root: root, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}, Target: signature.Target{URI: signature.LocalURI(filepath.Join(root, "model.go")), Position: signature.Position{Line: 1, Character: 5}}}}
	return req
}
func position(text string, offset int) signature.Position {
	line := strings.Count(text[:offset], "\n")
	start := strings.LastIndex(text[:offset], "\n") + 1
	return signature.Position{Line: line, Character: len(utf16.Encode([]rune(text[start:offset])))}
}
func target(t *testing.T, req *Request, name string) {
	t.Helper()
	path, e := signature.LocalPath(req.Target.URI)
	if e != nil {
		t.Fatal(e)
	}
	data, e := os.ReadFile(path)
	if e != nil {
		t.Fatal(e)
	}
	source := string(data)
	for _, overlay := range req.Overlays {
		if overlay.URI == req.Target.URI {
			source = overlay.Text
		}
	}
	fs := token.NewFileSet()
	file, e := parser.ParseFile(fs, path, source, 0)
	if e != nil {
		t.Fatal(e)
	}
	found := false
	ast.Inspect(file, func(node ast.Node) bool {
		if spec, ok := node.(*ast.TypeSpec); ok && spec.Name.Name == name {
			req.Target.Position = position(source, fs.PositionFor(spec.Name.Pos(), false).Offset)
			found = true
			return false
		}
		return true
	})
	if !found {
		t.Fatal("missing fixture type " + name)
	}
}
func planFor(t *testing.T, req Request, kind string, configure func(*PlanRequest, InspectResponse)) PlanResponse {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	info, e := Inspect(ctx, req)
	if e != nil {
		t.Fatal(e)
	}
	plan := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID, Kind: kind, ConstructorName: info.ConstructorName, ConstructorKind: "pointer", GetterKind: "pointer", ReceiverName: "r", Selections: []Selection{}}
	for _, field := range info.Fields {
		if field.Editable {
			plan.Selections = append(plan.Selections, Selection{FieldID: field.FieldID, ParameterName: field.ParameterName, GetterName: field.GetterName, SetterName: field.SetterName})
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
		if e := os.WriteFile(filepath.Join(req.Root, "helpers_runtime_test.go"), []byte(testSource), 0600); e != nil {
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
		t.Fatalf("actual helper fixture failed: %v\n%s", e, out)
	}
}

func TestConstructorsAndAccessorsHaveActualRuntimeSemanticsForEveryDirectFieldKind(t *testing.T) {
	source := `package fixture
import clock "time"
type Embedded struct{Flag bool}
type Model struct {ID int; name string; A,B int; *Embedded; At clock.Time; Callback func(int)string; _ int}
`
	req := fixture(t, map[string]string{"model.go": source})
	target(t, &req, "Model")
	constructor := planFor(t, req, "constructor", nil)
	success(t, constructor)
	actual, _ := os.ReadFile(filepath.Join(req.Root, "model.go"))
	if string(actual) != source {
		t.Fatal("planner wrote source")
	}
	apply(t, constructor)
	target(t, &req, "Model")
	accessors := planFor(t, req, "both", func(plan *PlanRequest, _ InspectResponse) { plan.GetterKind = "value" })
	success(t, accessors)
	apply(t, accessors)
	run(t, req, `package fixture
import("testing";"time")
var _ func(Model)int = Model.GetID
var _ func(*Model,string) = (*Model).SetName
func TestRuntime(t *testing.T){at:=time.Unix(5,0);m:=NewModel(7,"before",1,2,&Embedded{Flag:true},at,func(v int)string{return "callback"});if m.GetID()!=7||m.GetName()!="before"||m.GetA()!=1||m.GetB()!=2||!m.GetEmbedded().Flag||!m.GetAt().Equal(at)||m.GetCallback()(3)!="callback"{t.Fatal(m)};m.SetName("after");m.SetB(99);if m.name!="after"||m.B!=99||m.A!=1{t.Fatal("setter did not mutate the original struct")}}
`)
}

func TestValueConstructorAndZeroFieldGenericConstructorAreRealCallableFunctions(t *testing.T) {
	for _, generic := range []bool{false, true} {
		source := "package fixture\ntype Model struct{Value int}\n"
		if generic {
			source = "package fixture\ntype Model[T any] struct{Value T}\n"
		}
		req := fixture(t, map[string]string{"model.go": source})
		response := planFor(t, req, "constructor", func(plan *PlanRequest, _ InspectResponse) { plan.Selections = nil; plan.ConstructorKind = "value" })
		success(t, response)
		apply(t, response)
		test := "package fixture\nimport \"testing\"\nfunc TestZero(t *testing.T){var m Model=NewModel();if m.Value!=0{t.Fatal(m)}}\n"
		if generic {
			test = "package fixture\nimport \"testing\"\nfunc TestZero(t *testing.T){var m Model[int]=NewModel[int]();if m.Value!=0{t.Fatal(m)}}\n"
		}
		run(t, req, test)
	}
}

func TestGenericConstraintsCustomNamesAndSetterMutationRemainTyped(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Box[T comparable,S ~[]T] struct{Value T; Items S}\n"})
	target(t, &req, "Box")
	constructor := planFor(t, req, "constructor", func(plan *PlanRequest, _ InspectResponse) {
		plan.ConstructorName = "MakeBox"
		plan.Selections[0].ParameterName = "initial"
		plan.Selections[1].ParameterName = "items"
	})
	success(t, constructor)
	apply(t, constructor)
	target(t, &req, "Box")
	methods := planFor(t, req, "both", func(plan *PlanRequest, _ InspectResponse) {
		plan.Selections[0].GetterName = "ValueNow"
		plan.Selections[0].SetterName = "ReplaceValue"
	})
	success(t, methods)
	apply(t, methods)
	run(t, req, `package fixture
import "testing"
func TestGeneric(t *testing.T){box:=MakeBox[int,[]int](3,[]int{4});if box.ValueNow()!=3||box.GetItems()[0]!=4{t.Fatal(box)};box.ReplaceValue(8);box.SetItems([]int{9,10});if box.Value!=8||len(box.Items)!=2{t.Fatal("generic setter lost writeback")}}
`)
}

func TestCollisionsKeywordsAndCrossHelperDuplicatesRejectTheWholePlan(t *testing.T) {
	for _, kind := range []string{"constructor", "both"} {
		req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{Value int; GetValue string}\nvar NewModel=1\nfunc(*Model)SetValue(int){}\n"})
		response := planFor(t, req, kind, nil)
		if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
			t.Fatal("name collision produced a partial edit")
		}
	}
	for _, testCase := range []string{"keyword", "duplicate-parameters", "setter-receiver", "duplicate-methods", "field-method"} {
		req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{First int;Second int}\n"})
		kind := "both"
		if testCase == "duplicate-parameters" {
			kind = "constructor"
		}
		response := planFor(t, req, kind, func(plan *PlanRequest, _ InspectResponse) {
			switch testCase {
			case "keyword":
				plan.Selections[0].GetterName = "type"
			case "duplicate-parameters":
				for i := range plan.Selections {
					plan.Selections[i].ParameterName = "same"
				}
			case "setter-receiver":
				plan.Selections[0].ParameterName = "r"
			case "duplicate-methods":
				plan.Selections[1].SetterName = plan.Selections[0].GetterName
			case "field-method":
				plan.Selections[0].GetterName = "Second"
			}
		})
		if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
			t.Fatal(testCase + " was not rejected")
		}
	}
}

func TestDerivedLocalStructFieldsUseQualifiedImportsAndRespectForeignPrivacy(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\nimport contract \"example.org/structhelpers/contract\"\ntype Model contract.Foreign\n", "contract/api.go": "package contract\nimport \"time\"\ntype Foreign struct{Visible time.Time;hidden int}\n"})
	target(t, &req, "Model")
	constructor := planFor(t, req, "constructor", func(_ *PlanRequest, info InspectResponse) {
		if !info.Fields[0].Editable || info.Fields[1].Editable {
			t.Fatal(info.Fields)
		}
	})
	success(t, constructor)
	if !strings.Contains(text(constructor), `"time"`) {
		t.Fatal("missing typed import")
	}
	apply(t, constructor)
	target(t, &req, "Model")
	methods := planFor(t, req, "both", nil)
	success(t, methods)
	apply(t, methods)
	run(t, req, `package fixture
import("testing";"time")
func TestDerived(t *testing.T){first:=time.Unix(1,0);m:=NewModel(first);if !m.GetVisible().Equal(first){t.Fatal(m)};second:=time.Unix(2,0);m.SetVisible(second);if !m.Visible.Equal(second){t.Fatal(m)}}
`)
}

func TestAliasesAndInternalExternalTestStructsKeepCanonicalIdentity(t *testing.T) {
	t.Run("local-alias", func(t *testing.T) {
		req := fixture(t, map[string]string{"model.go": "package fixture\ntype Alias=Model\ntype Model struct{Value int}\n"})
		target(t, &req, "Alias")
		response := planFor(t, req, "both", nil)
		success(t, response)
		apply(t, response)
		run(t, req, "package fixture\nimport \"testing\"\nfunc TestAlias(t *testing.T){m:=&Alias{};m.SetValue(4);if m.GetValue()!=4{t.Fatal(m)}}\n")
	})
	for _, pkg := range []string{"fixture", "fixture_test"} {
		t.Run(pkg, func(t *testing.T) {
			req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{}\n", "mock_test.go": "package " + pkg + "\ntype Mock struct{Value int}\n"})
			req.Target.URI = signature.LocalURI(filepath.Join(req.Root, "mock_test.go"))
			target(t, &req, "Mock")
			constructor := planFor(t, req, "constructor", nil)
			success(t, constructor)
			apply(t, constructor)
			target(t, &req, "Mock")
			methods := planFor(t, req, "both", nil)
			success(t, methods)
			apply(t, methods)
			run(t, req, "package "+pkg+"\nimport \"testing\"\nfunc TestMock(t *testing.T){m:=NewMock(1);m.SetValue(2);if m.GetValue()!=2{t.Fatal(m)}}\n")
		})
	}
}

func TestCaretUTF16DirtyOverlaysAndExistingTagsRemainUnchanged(t *testing.T) {
	source := "package fixture\ntype Model[T any] struct{ First T; /*😀*/ Second string `json:\"keep\"` }\n"
	req := fixture(t, map[string]string{"model.go": source})
	dirty := strings.Replace(source, "Second string", "Second []string", 1) + "// 未保存 😀\n"
	req.Target.Position = position(dirty, strings.Index(dirty, "json:"))
	req.Overlays = []signature.Overlay{{URI: req.Target.URI, Text: dirty, DocumentStamp: 77, LSP: &signature.LSPVersion{Session: 6, Version: 12}}}
	response := planFor(t, req, "both", func(plan *PlanRequest, info InspectResponse) {
		if info.CaretFieldID == "" || info.CaretFieldID != info.Fields[1].FieldID {
			t.Fatal("wrong direct field at UTF16 caret")
		}
		plan.Selections = []Selection{{FieldID: info.CaretFieldID}}
	})
	success(t, response)
	edit := response.WorkspaceEdit.DocumentChanges[0]
	if edit.TextDocument.Version == nil || *edit.TextDocument.Version != 12 || !strings.Contains(text(response), "[]string") || !strings.Contains(text(response), `json:"keep"`) || !strings.Contains(text(response), "未保存 😀") {
		t.Fatal(response)
	}
	disk, _ := os.ReadFile(filepath.Join(req.Root, "model.go"))
	if string(disk) != source {
		t.Fatal("planner saved dirty source")
	}
}

func TestDirtyWorkspaceMetadataAndMissingConstructorUsesAreCheckedTogether(t *testing.T) {
	req := fixture(t, map[string]string{"go.work": "go 1.24.0\nuse ./a\n", "a/go.mod": "module example.org/a\n\ngo 1.24.0\n", "a/model.go": "package a\ntype Model struct{Value int}\n", "b/go.mod": "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ./missing\n", "b/use.go": "package b\nimport \"example.org/a\"\nvar Created=a.NewModel(1)\n"})
	req.Target.URI = signature.LocalURI(filepath.Join(req.Root, "a/model.go"))
	req.Environment["GOWORK"] = filepath.Join(req.Root, "go.work")
	target(t, &req, "Model")
	req.Overlays = []signature.Overlay{{URI: signature.LocalURI(filepath.Join(req.Root, "go.work")), Text: "go 1.24.0\nuse (\n./a\n./b\n)\n", DocumentStamp: 81}, {URI: signature.LocalURI(filepath.Join(req.Root, "b/go.mod")), Text: "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ../a\n", DocumentStamp: 82}}
	response := planFor(t, req, "constructor", nil)
	success(t, response)
	if len(response.Validation.BeforeErrors) == 0 || len(response.Coverage.Modules) != 2 {
		t.Fatal("missing constructor use or dirty graph was not analyzed")
	}
	for _, overlay := range req.Overlays {
		found := false
		for _, pre := range response.Preconditions {
			if pre.URI == overlay.URI && pre.DocumentStamp != nil && *pre.DocumentStamp == overlay.DocumentStamp {
				found = true
			}
		}
		if !found {
			t.Fatal("lost metadata overlay precondition")
		}
	}
	actual, _ := os.ReadFile(filepath.Join(req.Root, "go.work"))
	if strings.Contains(string(actual), "./b") {
		t.Fatal("planner saved metadata")
	}
}

func TestStaleSnapshotsAndIsolatedWorkerDoNotMutateSources(t *testing.T) {
	req := fixture(t, map[string]string{"model.go": "package fixture\ntype Model struct{Value int}\n"})
	info, e := Inspect(context.Background(), req)
	if e != nil {
		t.Fatal(e)
	}
	os.WriteFile(filepath.Join(req.Root, "model.go"), []byte("package fixture\ntype Model struct{Value string}\n"), 0600)
	stale, e := Plan(context.Background(), PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, StructID: info.StructID, Kind: "constructor"})
	if e != nil {
		t.Fatal(e)
	}
	if len(stale.Conflicts) == 0 || len(stale.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("stale plan emitted changes")
	}
	raw, e := RunIsolated(context.Background(), "inspect", req)
	if e != nil {
		t.Fatal(e)
	}
	var wire InspectResponse
	if e = json.Unmarshal(raw, &wire); e != nil || len(wire.Fields) != 1 || wire.Fields[0].Name != "Value" {
		t.Fatalf("worker response %s %v", raw, e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e = RunIsolated(ctx, "inspect", req); e != context.Canceled {
		t.Fatal(e)
	}
}
