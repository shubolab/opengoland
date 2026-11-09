package native

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func TestMain(m *testing.M) {
	if handled, err := WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			os.Stderr.WriteString(err.Error())
			os.Exit(1)
		}
		return
	}
	os.Exit(m.Run())
}

type replayFixture struct {
	Files map[string]string `json:"files"`
	Cases []struct {
		Label     string                  `json:"label"`
		Kind      string                  `json:"kind"`
		Selection signature.Range         `json:"selection"`
		Edit      signature.WorkspaceEdit `json:"edit"`
	} `json:"cases"`
}

func replay(t *testing.T) (replayFixture, string) {
	return replayNamed(t, "gopls-v0.21.1-candidates.json")
}

func replayNamed(t *testing.T, name string) (replayFixture, string) {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	var fixture replayFixture
	if err = json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for name, text := range fixture.Files {
		if err = os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	return fixture, root
}

func replayRequest(f replayFixture, root string, index int) Request {
	c := f.Cases[index]
	uri := signature.LocalURI(filepath.Join(root, "model.go"))
	edit := c.Edit
	for i := range edit.DocumentChanges {
		edit.DocumentChanges[i].TextDocument.URI = uri
	}
	return Request{Request: signature.Request{OperationID: "owned-native-refactor", Root: root, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}, Target: signature.Target{URI: uri, Position: c.Selection.Start}, Overlays: []signature.Overlay{{URI: uri, Text: f.Files["model.go"], DocumentStamp: 17, LSP: &signature.LSPVersion{Session: 3, Version: 1}}}}, Kind: c.Kind, CandidateKind: "refactor." + c.Kind, Selection: c.Selection, CandidateEdit: edit, CandidateSourceSHA256: sourceHash(f.Files["model.go"])}
}

func planned(t *testing.T, req Request, name string, inline string) (InspectResponse, PlanResponse) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	info, err := Inspect(ctx, req)
	if err != nil {
		t.Fatal(err)
	}
	if len(info.Conflicts) > 0 {
		t.Fatalf("inspection conflicts: %+v", info.Conflicts)
	}
	request := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest, InlineDeclaration: inline}
	for _, binding := range info.Names {
		chosen := name
		if chosen == "" {
			chosen = binding.SuggestedName
		}
		request.Names = append(request.Names, NameSelection{NameID: binding.NameID, Name: chosen})
	}
	plan, err := Plan(ctx, request)
	if err != nil {
		t.Fatal(err)
	}
	return info, plan
}

func successful(t *testing.T, plan PlanResponse) {
	t.Helper()
	if len(plan.Conflicts) > 0 || !plan.Validation.TypeChecked || len(plan.WorkspaceEdit.DocumentChanges) != 1 {
		t.Fatalf("plan conflicts=%+v validation=%+v edit=%+v", plan.Conflicts, plan.Validation, plan.WorkspaceEdit)
	}
	if len(plan.Preconditions) == 0 || len(plan.Directories) == 0 || plan.PlanDigest == "" {
		t.Fatal("missing analyzed input guards")
	}
}

func runtime(t *testing.T, root string, plan PlanResponse) {
	t.Helper()
	for _, document := range plan.WorkspaceEdit.DocumentChanges {
		path, err := signature.LocalPath(document.TextDocument.URI)
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(path, []byte(document.Edits[0].NewText), 0600); err != nil {
			t.Fatal(err)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "test", "-count=1", "./...")
	cmd.Dir = root
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOPROXY=off", "GOTOOLCHAIN=local", "GOTELEMETRY=off")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("actual fixture did not compile/run: %v\n%s", err, out)
	}
}

func TestCapturedGoplsCandidatesAreNamedAndValidatedBeforeAnyWrite(t *testing.T) {
	for index := 0; index < 5; index++ {
		fixture, root := replay(t)
		c := fixture.Cases[index]
		t.Run(c.Label+"-"+c.Kind, func(t *testing.T) {
			req := replayRequest(fixture, root, index)
			_, plan := planned(t, req, "calculateValue", "keep")
			successful(t, plan)
			data, err := os.ReadFile(filepath.Join(root, "model.go"))
			if err != nil {
				t.Fatal(err)
			}
			if string(data) != fixture.Files["model.go"] {
				t.Fatal("the planner wrote source")
			}
			text := plan.WorkspaceEdit.DocumentChanges[0].Edits[0].NewText
			if c.Kind == "extract.method" {
				if !strings.Contains(text, "func (t T) calculateValue() int") || strings.Count(text, "func (t T) newMethod()") != 1 {
					t.Fatal("the duplicate raw newMethod was not normalized independently", text)
				}
			}
			runtime(t, root, plan)
		})
	}
}

func TestRawMethodCollisionCanBeInspectedButRequestedCollisionIsRejected(t *testing.T) {
	f, root := replay(t)
	req := replayRequest(f, root, 1)
	info, plan := planned(t, req, "newMethod", "keep")
	if len(info.Names) != 1 || info.Names[0].GeneratedName != "newMethod" {
		t.Fatal("raw collision prevented useful inspection", info)
	}
	if len(plan.Conflicts) == 0 || len(plan.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("existing method collision was accepted", plan)
	}
}

func TestCandidateVersionAndSourceHashAreRequiredToMatchTheImmutableOverlay(t *testing.T) {
	for _, which := range []string{"version", "hash"} {
		t.Run(which, func(t *testing.T) {
			f, root := replay(t)
			req := replayRequest(f, root, 0)
			if which == "version" {
				v := 99
				req.CandidateEdit.DocumentChanges[0].TextDocument.Version = &v
			} else {
				req.CandidateSourceSHA256 = strings.Repeat("0", 64)
			}
			info, err := Inspect(context.Background(), req)
			if err != nil {
				t.Fatal(err)
			}
			if len(info.Conflicts) == 0 {
				t.Fatal("stale candidate accepted")
			}
		})
	}
}

func requestFor(t *testing.T, source, kind, selected string, edits []signature.TextEdit) Request {
	t.Helper()
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for name, text := range map[string]string{"go.mod": "module example.org/nativerefactor\n\ngo 1.24.0\n", "model.go": source} {
		if err = os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	uri := signature.LocalURI(filepath.Join(root, "model.go"))
	start := strings.Index(source, selected)
	if start < 0 {
		t.Fatal("missing fixture selection")
	}
	selection := signature.UTF16Range(source, start, start+len(selected))
	version := 1
	return Request{Request: signature.Request{OperationID: "native-fixture", Root: root, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}, Target: signature.Target{URI: uri, Position: selection.Start}, Overlays: []signature.Overlay{{URI: uri, Text: source, DocumentStamp: 17, LSP: &signature.LSPVersion{Session: 3, Version: 1}}}}, Kind: kind, Selection: selection, CandidateSourceSHA256: sourceHash(source), CandidateEdit: signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{{TextDocument: signature.TextDocument{URI: uri, Version: &version}, Edits: edits}}}}
}

func TestNamingDoesNotRewriteExistingCallsCopiedIntoTheExtractedMethod(t *testing.T) {
	source := "package sample\ntype T int\nfunc(t T)newMethod()int{return 99}\nfunc(t T)Keep()int{\n result:=t.newMethod()+int(t)\n println(result)\n return result+t.newMethod()\n}\n"
	selected := "result:=t.newMethod()+int(t)\n println(result)"
	start, end := strings.Index(source, "func(t T)Keep"), strings.LastIndex(source, "}")+1
	raw := "func(t T)Keep()int{\n result:=t.newMethod()\n return result+t.newMethod()\n}\n\nfunc(t T)newMethod()int{\n result:=t.newMethod()+int(t)\n println(result)\n return result\n}"
	req := requestFor(t, source, "extract.method", selected, []signature.TextEdit{{Range: signature.UTF16Range(source, start, end), NewText: raw}})
	_, plan := planned(t, req, "calculate", "keep")
	successful(t, plan)
	text := plan.WorkspaceEdit.DocumentChanges[0].Edits[0].NewText
	if strings.Count(text, ".calculate()") != 1 || strings.Count(text, ".newMethod()") != 2 {
		t.Fatal("normalization changed old binding uses", text)
	}
	if err := os.WriteFile(filepath.Join(req.Root, "model_test.go"), []byte("package sample\nimport \"testing\"\nfunc TestRuntime(t *testing.T){if T(4).Keep()!=202{t.Fatal(\"old method was rebound\")}}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	// The runtime file is added after planning only in this authorized disposable
	// fixture. Production application still validates the original directory guard.
	runtime(t, req.Root, plan)
}

func TestInlineVariablePreservesPrecedenceAndExplicitScalarTypeWithSafeRemoval(t *testing.T) {
	source := "package sample\nfunc Value(n int) int { x := n+1; return x*2 }\nfunc Narrow() any { var x int8 = 1; return x }\n"
	for _, part := range []string{"x*2", "return x }"} {
		t.Run(part, func(t *testing.T) {
			use := strings.Index(source, part)
			if strings.HasPrefix(part, "return") {
				use += len("return ")
			}
			rhs := "n + 1"
			if strings.HasPrefix(part, "return") {
				rhs = "1"
			}
			req := requestFor(t, source, "inline.variable", source[use:use+1], []signature.TextEdit{{Range: signature.UTF16Range(source, use, use+1), NewText: rhs}})
			// requestFor selects the first x; the real selected use is this exact one.
			req.Selection = signature.UTF16Range(source, use, use+1)
			req.Target.Position = req.Selection.Start
			info, plan := planned(t, req, "", "remove")
			successful(t, plan)
			if info.Inline == nil || info.Inline.CanKeep || !info.Inline.CanRemove {
				t.Fatal("unsafe keep/remove model", info)
			}
			if err := os.WriteFile(filepath.Join(req.Root, "model_test.go"), []byte("package sample\nimport \"testing\"\nfunc TestRuntime(t *testing.T){if Value(4)!=10{t.Fatal(\"precedence\")};if _,ok:=Narrow().(int8);!ok{t.Fatal(\"concrete type\")}}\n"), 0600); err != nil {
				t.Fatal(err)
			}
			runtime(t, req.Root, plan)
		})
	}
}

func TestInlineRefusesReassignmentAndEffectfulInitializerInsteadOfChangingBehavior(t *testing.T) {
	for _, source := range []string{
		"package sample\nfunc F()int{x:=1;x=2;println(x);return x}\n",
		"package sample\nvar count int\nfunc next()int{count++;return count}\nfunc F()int{x:=next();println(x);return x}\n",
		"package sample\nfunc F()int{x:=1;for x=range []int{3}{};println(x);return x}\n",
		"package sample\ntype Count int\nfunc(c *Count)Inc(){(*c)++}\nfunc F()Count{x:=Count(1);x.Inc();println(x);return x}\n",
	} {
		use := strings.LastIndex(source, "return x") + len("return ")
		req := requestFor(t, source, "inline.variable", "x", []signature.TextEdit{{Range: signature.UTF16Range(source, use, use+1), NewText: "1"}})
		req.Selection = signature.UTF16Range(source, use, use+1)
		req.Target.Position = req.Selection.Start
		info, err := Inspect(context.Background(), req)
		if err != nil {
			t.Fatal(err)
		}
		if len(info.Conflicts) == 0 {
			t.Fatal("unsafe inline candidate accepted", source)
		}
	}
}

func TestTypedCaretAndDeclarationChoicesUseActualSourceRanges(t *testing.T) {
	source := "package sample\n// 前😀\nfunc F(value int)int{return value+1}\nfunc Use()int{return F(2)}\n"
	req := requestFor(t, source, "extract.variable", "value+1", nil)
	req.CandidateEdit = signature.WorkspaceEdit{}
	offset := strings.Index(source, "value+1") + 2
	req.Target.Position = signature.UTF16Range(source, offset, offset).Start
	info, err := Inspect(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	if info.SourceDigest == "" || len(info.Targets) < 2 {
		t.Fatal("caret chooser missed enclosing typed value expressions", info)
	}
	found := false
	for _, target := range info.Targets {
		if target.Text == "value+1" {
			found = true
		}
	}
	if !found {
		t.Fatal("no full expression choice", info.Targets)
	}
	req.Kind = "inline"
	offset = strings.Index(source, "F(value")
	req.Target.Position = signature.UTF16Range(source, offset, offset).Start
	info, err = Inspect(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	if len(info.Targets) != 2 || info.Targets[0].Kind != "inline.call-all" || info.Targets[0].Range.Start.Line != 2 || info.Targets[1].Kind != "inline.call" || !strings.Contains(info.Targets[1].Text, "F(2)") {
		t.Fatal("declaration chooser did not resolve its actual call", info)
	}
}

func TestRequestedFunctionNameCannotBeCapturedByACompatibleFunctionParameter(t *testing.T) {
	source := "package sample\nfunc Keep(compute func(int)int,value int)int{\n result:=value+1\n println(result)\n return result\n}\n"
	selected := "result:=value+1\n println(result)"
	start, end := strings.Index(source, "func Keep"), strings.LastIndex(source, "}")+1
	raw := "func Keep(compute func(int)int,value int)int{\n result:=newFunction(value)\n return result\n}\n\nfunc newFunction(value int)int{\n result:=value+1\n println(result)\n return result\n}"
	req := requestFor(t, source, "extract.function", selected, []signature.TextEdit{{Range: signature.UTF16Range(source, start, end), NewText: raw}})
	_, plan := planned(t, req, "compute", "keep")
	if len(plan.Conflicts) == 0 || len(plan.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("type-checkable function parameter capture was accepted", plan)
	}
}

func TestTypedContextGuardsRejectAChangedDependencyBeforeNamingOrApplication(t *testing.T) {
	f, root := replay(t)
	req := replayRequest(f, root, 0)
	dependency := filepath.Join(root, "dependency.go")
	if err := os.WriteFile(dependency, []byte("package sample\nfunc Other()int{return 1}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	contextRequest := req
	contextRequest.CandidateEdit = signature.WorkspaceEdit{}
	contextInfo, err := Inspect(context.Background(), contextRequest)
	if err != nil {
		t.Fatal(err)
	}
	req.ExpectedSourceDigest = contextInfo.SourceDigest
	info, err := Inspect(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	if len(info.Conflicts) > 0 {
		t.Fatal(info.Conflicts)
	}
	if err = os.WriteFile(dependency, []byte("package sample\nfunc Other()int{return 2}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	request := PlanRequest{Request: req, InspectionID: info.InspectionID, ConfigurationDigest: info.ConfigurationDigest}
	for _, name := range info.Names {
		request.Names = append(request.Names, NameSelection{NameID: name.NameID, Name: name.SuggestedName})
	}
	plan, err := Plan(context.Background(), request)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Conflicts) == 0 || len(plan.WorkspaceEdit.DocumentChanges) != 0 {
		t.Fatal("dependency changed without rejecting old context", plan)
	}
}

func TestCapturedGoplsFrameEffectsAreRejectedBeforeAnyAtomicEdit(t *testing.T) {
	for index, code := range []string{"extraction-defer-frame", "extraction-recover-frame"} {
		f, root := replayNamed(t, "gopls-v0.21.1-frame-effects.json")
		t.Run(f.Cases[index].Label, func(t *testing.T) {
			req := replayRequest(f, root, index)
			_, plan := planned(t, req, "preserveBehavior", "keep")
			found := false
			for _, conflict := range plan.Conflicts {
				found = found || conflict.Code == code
			}
			if !found || len(plan.WorkspaceEdit.DocumentChanges) != 0 || plan.Validation.TypeChecked {
				t.Fatalf("unsafe frame candidate was accepted: %+v", plan)
			}
			data, err := os.ReadFile(filepath.Join(root, "model.go"))
			if err != nil || string(data) != f.Files["model.go"] {
				t.Fatal("the rejected planner changed original source", err)
			}
		})
	}
}

func TestAnEntireMovedNestedFunctionKeepsItsOwnDeferFrame(t *testing.T) {
	source := `package fixture
var Events []string
func Work(){
 func(){
  defer func(){Events=append(Events,"defer")}()
  Events=append(Events,"selected")
 }()
 Events=append(Events,"after")
}
`
	selected := `func(){
  defer func(){Events=append(Events,"defer")}()
  Events=append(Events,"selected")
 }()`
	raw := `func Work(){newFunction(); Events=append(Events,"after")}
func newFunction(){
 func(){
  defer func(){Events=append(Events,"defer")}()
  Events=append(Events,"selected")
 }()
}`
	start, end := strings.Index(source, "func Work"), len(source)-1
	req := requestFor(t, source, "extract.function", selected, []signature.TextEdit{{Range: signature.UTF16Range(source, start, end), NewText: raw}})
	_, plan := planned(t, req, "performWork", "keep")
	successful(t, plan)
	// Only an authorized disposable fixture is executed, after immutable planning.
	if err := os.WriteFile(filepath.Join(req.Root, "runtime_test.go"), []byte(`package fixture
import("testing";"strings")
func TestOrder(t *testing.T){Work();if strings.Join(Events,",")!="selected,defer,after"{t.Fatal(Events)}}
`), 0600); err != nil {
		t.Fatal(err)
	}
	runtime(t, req.Root, plan)
}

func TestMovedFrameGuardDistinguishesShadowedRecoverFromTheBuiltin(t *testing.T) {
	source := "package fixture\nfunc Work()int{recover:=func()int{return 7};return recover()}\n"
	req := requestFor(t, source, "extract.function", "return recover()", nil)
	req.CandidateEdit.DocumentChanges = nil
	a, err := open(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	defer a.snapshot.Close()
	a.validateMovedFrameEffects()
	if len(a.inspection.Conflicts) > 0 {
		t.Fatalf("ordinary function named recover was rejected: %+v", a.inspection.Conflicts)
	}
}

func TestNamedExtractionPreservesGenericMethodsAndMultipleResults(t *testing.T) {
	for _, method := range []bool{false, true} {
		t.Run(map[bool]string{false: "function", true: "method"}[method], func(t *testing.T) {
			source := `package fixture
func Keep[T ~int](value T)(T,string){
 result:=value+1
 label:="前😀"
 return result,label
}
`
			raw := `func Keep[T ~int](value T)(T,string){result,label:=newFunction(value);return result,label}
func newFunction[T ~int](value T)(T,string){result:=value+1;label:="前😀";return result,label}`
			selected := "result:=value+1\n label:=\"前😀\""
			kind := "extract.function"
			if method {
				source = `package fixture
type Box[T ~int]struct{value T}
func(b Box[T])Keep()(T,string){
 result:=b.value+1
 label:="前😀"
 return result,label
}
`
				raw = `func(b Box[T])Keep()(T,string){result,label:=b.newMethod();return result,label}
func(b Box[T])newMethod()(T,string){result:=b.value+1;label:="前😀";return result,label}`
				selected = "result:=b.value+1\n label:=\"前😀\""
				kind = "extract.method"
			}
			start := strings.Index(source, "func")
			end := strings.LastIndex(source, "}") + 1
			req := requestFor(t, source, kind, selected, []signature.TextEdit{{Range: signature.UTF16Range(source, start, end), NewText: raw}})
			_, plan := planned(t, req, "calculate", "keep")
			successful(t, plan)
			call := "Keep(3)"
			if method {
				call = "(Box[int]{value:3}).Keep()"
			}
			writeFixture(t, req.Root, "runtime_test.go", "package fixture\nimport \"testing\"\nfunc TestGeneric(t *testing.T){value,label:="+call+";if value!=4||label!=\"前😀\"{t.Fatal(value,label)}}\n")
			runtime(t, req.Root, plan)
		})
	}
}

func TestStandardVariableRequestUsesAVariableForAConstantGoplsCandidate(t *testing.T) {
	f, root := replay(t)
	req := replayRequest(f, root, 2)
	// The server offers a constant for this constant-valued expression. A user
	// explicitly invoking Extract Variable must still receive a mutable binding.
	req.Kind = "extract.variable"
	_, plan := planned(t, req, "size", "keep")
	successful(t, plan)
	text := plan.WorkspaceEdit.DocumentChanges[0].Edits[0].NewText
	if strings.Contains(text, "const size") || !strings.Contains(text, "var size") {
		t.Fatal(text)
	}
	runtime(t, root, plan)
}
