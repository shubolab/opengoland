package methods

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
		files["go.mod"] = "module example.org/methodgen\n\ngo 1.24.0\n"
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
	position := signature.Position{Line: 1, Character: 5}
	fs := token.NewFileSet()
	if file, err := parser.ParseFile(fs, "buffer.go", files["buffer.go"], 0); err == nil {
		found := false
		ast.Inspect(file, func(node ast.Node) bool {
			if spec, ok := node.(*ast.TypeSpec); ok && !found {
				offset := fs.Position(spec.Name.Pos()).Offset
				position = signature.UTF16Range(files["buffer.go"], offset, offset).Start
				found = true
			}
			return !found
		})
	}
	return Request{Request: signature.Request{OperationID: "owned-method-test", Root: root, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}, Target: signature.Target{URI: signature.LocalURI(filepath.Join(root, "buffer.go")), Position: position}}, ReceiverKind: "pointer"}
}
func planFor(t *testing.T, req Request, configure func(*PlanRequest, InspectResponse)) PlanResponse {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	info, e := Inspect(ctx, req)
	if e != nil {
		t.Fatal(e)
	}
	plan := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, AllMissing: true}
	if info.Receiver != nil {
		plan.ReceiverID = info.Receiver.ReceiverID
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
func texts(response PlanResponse) map[string]string {
	out := map[string]string{}
	for _, edit := range response.WorkspaceEdit.DocumentChanges {
		p, _ := signature.LocalPath(edit.TextDocument.URI)
		out[p] = edit.Edits[0].NewText
	}
	return out
}
func compile(t *testing.T, req Request, response PlanResponse, assertions string) {
	t.Helper()
	for p, text := range texts(response) {
		if e := os.WriteFile(p, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	if assertions != "" {
		if e := os.WriteFile(filepath.Join(req.Root, "implementation_assertions_test.go"), []byte(assertions), 0600); e != nil {
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
		t.Fatalf("actual generated Go did not compile: %v\n%s", e, out)
	}
}

func TestUnimportedIOReaderAndBuiltinErrorProduceRealImplementationsWithoutWriting(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\n"})
	req.InterfaceExpression = "io.Reader"
	response := planFor(t, req, nil)
	success(t, response)
	if !response.Implemented || len(response.GeneratedMethods) != 1 || response.GeneratedMethods[0].Name != "Read" {
		t.Fatalf("wrong implementation %+v", response)
	}
	text := texts(response)[filepath.Join(req.Root, "buffer.go")]
	if !strings.Contains(text, "func (r *Buffer) Read(p []byte) (n int, err error)") {
		t.Fatal(text)
	}
	original, _ := os.ReadFile(filepath.Join(req.Root, "buffer.go"))
	if strings.Contains(string(original), "func (") {
		t.Fatal("planner wrote user source")
	}
	compile(t, req, response, "package fixture\nimport \"io\"\nvar _ io.Reader = (*Buffer)(nil)\n")
	errorReq := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Failure struct{}\n"})
	errorReq.InterfaceExpression = "error"
	errorReq.ReceiverKind = "value"
	errorPlan := planFor(t, errorReq, nil)
	success(t, errorPlan)
	compile(t, errorReq, errorPlan, "package fixture\nvar _ error = Failure{}\n")
}

func TestAnExistingMissingMethodAssertionIsFixedRatherThanRejectedBeforeGeneration(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": "package fixture\nimport \"io\"\ntype Buffer struct{}\nvar _ io.Reader = (*Buffer)(nil)\n"})
	req.Target.Position.Line = 2
	req.InterfaceExpression = "io.Reader"
	response := planFor(t, req, nil)
	success(t, response)
	if len(response.Validation.BeforeErrors) == 0 || len(response.Validation.AfterErrors) != 0 || !response.Implemented {
		t.Fatalf("expected assertion repair: %+v", response.Validation)
	}
	compile(t, req, response, "")
}

func TestInterfaceCaretCanCreateANewStructOrChooseAnExistingTypeInAnotherFile(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Reader interface { Read([]byte)(int,error) }\n", "existing.go": "package fixture\ntype Existing struct{}\n"})
	req.NewTypeName = "Created"
	created := planFor(t, req, func(_ *PlanRequest, info InspectResponse) {
		if info.EntryKind != "interface" || len(info.ReceiverCandidates) != 1 || !info.Receiver.New {
			t.Fatalf("interface entry %+v", info)
		}
	})
	success(t, created)
	compile(t, req, created, "package fixture\nvar _ Reader=(*Created)(nil)\n")
	req.NewTypeName = ""
	req.ReceiverType = "Existing"
	req.InterfaceExpression = "Reader"
	existing := planFor(t, req, nil)
	success(t, existing)
	if _, ok := texts(existing)[filepath.Join(req.Root, "existing.go")]; !ok {
		t.Fatal("methods must be added to the chosen receiver's original source")
	}
	compile(t, req, existing, "package fixture\nvar _ Reader=(*Existing)(nil)\n")
}

func TestEmbeddedImportedTypesVariadicsAndExistingImportAliases(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": `package fixture
import clock "time"
type Buffer struct{}
type Base interface { Stamp(clock.Time) clock.Time }
type Contract interface { Base; Send(prefix string, values ...int) (int,error) }
`})
	req.InterfaceExpression = "Contract"
	response := planFor(t, req, nil)
	success(t, response)
	text := texts(response)[filepath.Join(req.Root, "buffer.go")]
	for _, want := range []string{"Stamp(arg0 clock.Time)", "values ...int", "panic(\"TODO: implement"} {
		if !strings.Contains(text, want) {
			t.Errorf("missing %q in\n%s", want, text)
		}
	}
	compile(t, req, response, "package fixture\nvar _ Contract=(*Buffer)(nil)\n")
}

func TestGenericInterfacesUseExplicitConcreteOrReceiverBoundArguments(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": `package fixture
type Box[T comparable] struct{}
type Mapper[T comparable] interface { Map(value T, rest ...T) (T,error) }
`})
	req.InterfaceExpression = "Mapper"
	req.InterfaceTypeArguments = []string{"T"}
	response := planFor(t, req, nil)
	success(t, response)
	if !strings.Contains(texts(response)[filepath.Join(req.Root, "buffer.go")], "*Box[T]") {
		t.Fatal("lost generic receiver")
	}
	compile(t, req, response, "package fixture\nvar _ Mapper[string]=(*Box[string])(nil)\n")
	concrete := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\ntype Getter[T any] interface {Get() T}\n"})
	concrete.InterfaceExpression = "Getter[[]byte]"
	plan := planFor(t, concrete, nil)
	success(t, plan)
	compile(t, concrete, plan, "package fixture\nvar _ Getter[[]byte]=(*Buffer)(nil)\n")
	bad := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\ntype Getter[T comparable] interface {Get() T}\n"})
	bad.InterfaceExpression = "Getter[[]byte]"
	if _, e := Inspect(context.Background(), bad); e == nil {
		t.Fatal("invalid interface constraint was accepted")
	}
}

func TestPartialSelectionPromotedMethodsAndPointerValueConflicts(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\ntype Pair interface{First();Second()}\n"})
	req.InterfaceExpression = "Pair"
	response := planFor(t, req, func(plan *PlanRequest, info InspectResponse) {
		plan.AllMissing = false
		plan.MethodIDs = []string{info.Methods[0].MethodID}
	})
	success(t, response)
	if response.Implemented || len(response.GeneratedMethods) != 1 {
		t.Fatal("partial selection pretended to implement the whole interface")
	}
	compile(t, req, response, "")
	promoted := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{Base}\ntype Base struct{}\nfunc (Base) First(){}\ntype Pair interface{First();Second()}\n"})
	promoted.InterfaceExpression = "Pair"
	complete := planFor(t, promoted, func(_ *PlanRequest, info InspectResponse) {
		if info.Methods[0].Status != "existing" {
			t.Fatalf("promoted method lost %+v", info.Methods)
		}
	})
	success(t, complete)
	if len(complete.GeneratedMethods) != 1 {
		t.Fatal("existing promoted method duplicated")
	}
	compile(t, promoted, complete, "package fixture\nvar _ Pair=(*Buffer)(nil)\n")
	conflict := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\nfunc (*Buffer) Read([]byte)(int,error){return 0,nil}\n"})
	conflict.InterfaceExpression = "io.Reader"
	conflict.ReceiverKind = "value"
	blocked := planFor(t, conflict, nil)
	if len(blocked.Conflicts) == 0 || len(blocked.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("cannot generate a duplicate value method over an existing pointer method")
	}
}

func TestConflictingSignaturesFieldsForeignPrivateMethodsAndInvalidAliasesAreRejected(t *testing.T) {
	for _, source := range []string{"package fixture\ntype Buffer struct{}\nfunc (*Buffer) Read(string){}\n", "package fixture\ntype Buffer struct{Read int}\n"} {
		req := fixture(t, map[string]string{"buffer.go": source})
		req.InterfaceExpression = "io.Reader"
		response := planFor(t, req, nil)
		if len(response.Conflicts) == 0 || len(response.WorkspaceEdit.DocumentChanges) != 0 {
			t.Fatal("conflict emitted edits")
		}
	}
	private := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\n", "contract/api.go": "package contract\ntype Sealed interface{ hidden() }\n"})
	private.InterfaceExpression = "example.org/methodgen/contract.Sealed"
	blocked := planFor(t, private, nil)
	if len(blocked.Conflicts) == 0 {
		t.Fatal("foreign private method incorrectly generated")
	}
	alias := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Base[T any] struct{}\ntype Buffer = Base[int]\n"})
	alias.ReceiverType = "Buffer"
	alias.InterfaceExpression = "io.Reader"
	if _, e := Inspect(context.Background(), alias); e == nil {
		t.Fatal("alias denoting an instantiated generic receiver incorrectly accepted")
	}
}

func TestLegalLocalAliasesResolveToCanonicalBaseIncludingTestPackageMocks(t *testing.T) {
	t.Run("ordinary-alias-caret", func(t *testing.T) {
		req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Alias = Base\n", "base.go": "package fixture\ntype Base struct{}\n"})
		req.InterfaceExpression = "io.Reader"
		response := planFor(t, req, nil)
		success(t, response)
		if response.Receiver.Name != "Base" || len(texts(response)) != 1 || texts(response)[filepath.Join(req.Root, "base.go")] == "" {
			t.Fatal("alias must resolve to its canonical local declaration")
		}
		compile(t, req, response, "package fixture\nimport \"io\"\nvar _ io.Reader=(*Alias)(nil)\n")
	})
	t.Run("pointer-alias-request", func(t *testing.T) {
		req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype PointerAlias = *Base\n", "base.go": "package fixture\ntype Base struct{}\n"})
		req.ReceiverType = "PointerAlias"
		req.InterfaceExpression = "io.Reader"
		response := planFor(t, req, nil)
		success(t, response)
		compile(t, req, response, "package fixture\nimport \"io\"\nvar _ io.Reader=PointerAlias(nil)\n")
	})
	for _, pkg := range []string{"fixture", "fixture_test"} {
		t.Run(pkg+"-mock-alias", func(t *testing.T) {
			mock := "package " + pkg + "\ntype Mock struct{}\ntype MockAlias = Mock\n"
			req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\n", "mock_test.go": mock})
			req.Target.URI = signature.LocalURI(filepath.Join(req.Root, "mock_test.go"))
			req.Target.Position = signature.Position{Line: 2, Character: 5}
			req.InterfaceExpression = "io.Reader"
			response := planFor(t, req, nil)
			success(t, response)
			if response.Receiver.Name != "Mock" || texts(response)[filepath.Join(req.Root, "mock_test.go")] == "" {
				t.Fatal("test variant mock identity was not preserved")
			}
			compile(t, req, response, "package "+pkg+"\nimport \"io\"\nvar _ io.Reader=(*MockAlias)(nil)\n")
		})
	}
	foreign := fixture(t, map[string]string{"buffer.go": "package fixture\nimport \"example.org/methodgen/other\"\ntype Alias=other.Base\n", "other/base.go": "package other\ntype Base struct{}\n"})
	foreign.ReceiverType = "Alias"
	foreign.InterfaceExpression = "io.Reader"
	if _, e := Inspect(context.Background(), foreign); e == nil {
		t.Fatal("foreign receiver base alias was accepted")
	}
}

func TestUTF16DirtySourceVersionsAndWorkspaceMetadataRemainUnsaved(t *testing.T) {
	source := "package fixture\ntype Before struct{}; /*😀*/ type Buffer struct{}\n"
	req := fixture(t, map[string]string{"buffer.go": source})
	line := strings.Split(source, "\n")[1]
	prefix := line[:strings.Index(line, "type Buffer")]
	req.Target.Position = signature.Position{Line: 1, Character: len(utf16.Encode([]rune(prefix))) + 5}
	req.InterfaceExpression = "io.WriterTo"
	dirty := source + "// 未保存 中文 😀\n"
	req.Overlays = []signature.Overlay{{URI: req.Target.URI, Text: dirty, DocumentStamp: 44, LSP: &signature.LSPVersion{Session: 3, Version: 9}}}
	response := planFor(t, req, nil)
	success(t, response)
	edit := response.WorkspaceEdit.DocumentChanges[0]
	if edit.TextDocument.Version == nil || *edit.TextDocument.Version != 9 || !strings.Contains(edit.Edits[0].NewText, "未保存 中文 😀") || !strings.Contains(edit.Edits[0].NewText, "*Buffer") {
		t.Fatal("dirty/UTF16 receiver context lost")
	}
	actual, _ := os.ReadFile(filepath.Join(req.Root, "buffer.go"))
	if string(actual) != source {
		t.Fatal("planner saved dirty source")
	}
	compile(t, req, response, "package fixture\nimport \"io\"\nvar _ io.WriterTo=(*Buffer)(nil)\n")
}

func TestStaleInspectionNewTypeCollisionsAndIsolatedProtocol(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\n"})
	req.InterfaceExpression = "io.Reader"
	info, e := Inspect(context.Background(), req)
	if e != nil {
		t.Fatal(e)
	}
	os.WriteFile(filepath.Join(req.Root, "buffer.go"), []byte("package fixture\ntype Buffer struct{Value int}\n"), 0600)
	stale, e := Plan(context.Background(), PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, ReceiverID: info.Receiver.ReceiverID, AllMissing: true})
	if e != nil {
		t.Fatal(e)
	}
	if len(stale.Conflicts) == 0 || len(stale.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("stale receiver produced edits")
	}
	req.NewTypeName = "Buffer"
	if _, e = Inspect(context.Background(), req); e == nil {
		t.Fatal("new type name collision accepted")
	}
	req.NewTypeName = ""
	raw, e := RunIsolated(context.Background(), "inspect", req)
	if e != nil {
		t.Fatal(e)
	}
	var response InspectResponse
	if e = json.Unmarshal(raw, &response); e != nil || len(response.Methods) != 1 {
		t.Fatalf("real worker response: %s %v", raw, e)
	}
}

func TestExplicitAdditionalSDKPackageAndUnrelatedErrorsDoNotYieldPartialEdits(t *testing.T) {
	req := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\n"})
	req.InterfaceExpression = "encoding.TextMarshaler"
	response := planFor(t, req, nil)
	success(t, response)
	compile(t, req, response, "package fixture\nimport \"encoding\"\nvar _ encoding.TextMarshaler=(*Buffer)(nil)\n")
	broken := fixture(t, map[string]string{"buffer.go": "package fixture\ntype Buffer struct{}\nvar Broken = unknownName\n"})
	broken.InterfaceExpression = "io.Reader"
	blocked := planFor(t, broken, nil)
	if len(blocked.Conflicts) == 0 || len(blocked.WorkspaceEdit.DocumentChanges) != 0 || blocked.Validation.TypeChecked {
		t.Fatal("unrelated source errors must reject the entire candidate")
	}
}

func TestDirtyWorkspaceGraphAndImportedSubjectTypesShareOneTypeUniverse(t *testing.T) {
	req := fixture(t, map[string]string{
		"go.work":     "go 1.24.0\nuse ./a\n",
		"a/go.mod":    "module example.org/a\n\ngo 1.24.0\n",
		"a/buffer.go": "package a\ntype Buffer struct{}\n",
		"b/go.mod":    "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ./missing\n",
		"b/api.go":    "package b\nimport \"example.org/a\"\ntype Contract interface { Accept(*a.Buffer) }\n",
	})
	req.Target.URI = signature.LocalURI(filepath.Join(req.Root, "a/buffer.go"))
	req.Target.Position = signature.Position{Line: 1, Character: 5}
	req.InterfaceExpression = "example.org/b.Contract"
	req.Environment["GOWORK"] = filepath.Join(req.Root, "go.work")
	req.Overlays = []signature.Overlay{
		{URI: signature.LocalURI(filepath.Join(req.Root, "go.work")), Text: "go 1.24.0\nuse (\n./a\n./b\n)\n", DocumentStamp: 61},
		{URI: signature.LocalURI(filepath.Join(req.Root, "b/go.mod")), Text: "module example.org/b\n\ngo 1.24.0\nrequire example.org/a v0.0.0\nreplace example.org/a => ../a\n", DocumentStamp: 62},
		{URI: req.Target.URI, Text: "package a\ntype Buffer struct{Value string}\n// 未保存 😀\n", DocumentStamp: 63},
	}
	response := planFor(t, req, nil)
	success(t, response)
	if !response.Implemented || len(response.Coverage.Modules) != 2 {
		t.Fatalf("dirty module graph/identity lost: %+v", response)
	}
	text := texts(response)[filepath.Join(req.Root, "a/buffer.go")]
	if !strings.Contains(text, "Accept(arg0 *Buffer)") || strings.Contains(text, "\"example.org/b\"") {
		t.Fatal("an implementation must not introduce an artificial interface import cycle: " + text)
	}
	for _, overlay := range req.Overlays {
		found := false
		for _, pre := range response.Preconditions {
			if pre.URI == overlay.URI {
				found = pre.DocumentStamp != nil && *pre.DocumentStamp == overlay.DocumentStamp
			}
		}
		if !found {
			t.Fatal("lost dirty metadata/source precondition: " + overlay.URI)
		}
	}
	disk, _ := os.ReadFile(filepath.Join(req.Root, "go.work"))
	if strings.Contains(string(disk), "./b") {
		t.Fatal("planner saved workspace metadata")
	}
	// Only this test applies its own editor overlays before checking the actual
	// compiler; production planning itself has not written a single source byte.
	for _, overlay := range req.Overlays {
		p, _ := signature.LocalPath(overlay.URI)
		if e := os.WriteFile(p, []byte(overlay.Text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	for p, text := range texts(response) {
		if e := os.WriteFile(p, []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	assertions := "package a_test\nimport(\"example.org/a\";\"example.org/b\")\nvar _ b.Contract=(*a.Buffer)(nil)\n"
	if e := os.WriteFile(filepath.Join(req.Root, "a/implementation_test.go"), []byte(assertions), 0600); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "test", "./a/...", "./b/...")
	cmd.Dir = req.Root
	cmd.Env = append(os.Environ(), "GOWORK="+filepath.Join(req.Root, "go.work"), "GOPROXY=off", "GOTOOLCHAIN=local")
	if out, e := cmd.CombinedOutput(); e != nil {
		t.Fatalf("actual dirty workspace implementation: %v\n%s", e, out)
	}
}
