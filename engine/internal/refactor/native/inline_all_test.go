package native

import (
	"context"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func allRequest(t *testing.T, source, kind, name string) Request {
	t.Helper()
	req := requestFor(t, source, kind, name, nil)
	req.CandidateEdit.DocumentChanges = nil
	fs := token.NewFileSet()
	file, err := parser.ParseFile(fs, "model.go", source, 0)
	if err != nil {
		t.Fatal(err)
	}
	var selected *ast.Ident
	ast.Inspect(file, func(node ast.Node) bool {
		if id, ok := node.(*ast.Ident); ok && selected == nil && id.Name == name {
			selected = id
		}
		return true
	})
	if selected == nil {
		t.Fatal("missing declaration", name)
	}
	req.Selection = signature.UTF16Range(source, fs.Position(selected.Pos()).Offset, fs.Position(selected.End()).Offset)
	req.Target.Position = req.Selection.Start
	return req
}
func assertWorkspacePlan(t *testing.T, plan PlanResponse, documents int) {
	t.Helper()
	if len(plan.Conflicts) > 0 || !plan.Validation.TypeChecked || len(plan.WorkspaceEdit.DocumentChanges) != documents {
		t.Fatalf("workspace plan: %+v", plan)
	}
}
func writeFixture(t *testing.T, root, name, text string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
}

func TestInlineAllFunctionAcrossFilesRebindsImportAliasesAndRemovesDeclarationAtomically(t *testing.T) {
	source := `package fixture
import text "strings"
// Transform preserves its parameter and package bindings.
func Transform(value string) string { return text.ToUpper(value) }
`
	req := allRequest(t, source, "inline.call-all", "Transform")
	writeFixture(t, req.Root, "caller.go", `package fixture
import stringsAlias "strings"
func First()string{return Transform("前😀")+stringsAlias.ToLower("X")}
`)
	writeFixture(t, req.Root, "second.go", `package fixture
func Second()string{return Transform("go")}
`)
	_, plan := planned(t, req, "", "remove")
	assertWorkspacePlan(t, plan, 3)
	for _, doc := range plan.WorkspaceEdit.DocumentChanges {
		if strings.HasSuffix(doc.TextDocument.URI, "model.go") && strings.Contains(doc.Edits[0].NewText, "Transform") {
			t.Fatal("removed declaration/doc remained", doc.Edits[0].NewText)
		}
	}
	writeFixture(t, req.Root, "runtime_test.go", `package fixture
import "testing"
func TestAll(t *testing.T){if First()!="前😀x"||Second()!="GO"{t.Fatal(First(),Second())}}
`)
	runtime(t, req.Root, plan)
}

func TestInlineAllPreservesVariadicEvaluationNamedResultsDeferAndRecover(t *testing.T) {
	source := `package fixture
var Events []int
func Next()int{Events=append(Events,1);return len(Events)}
func Work(values ...int)(result int){
 defer func(){if recover()!=nil{result=42};Events=append(Events,9)}()
 result=values[0]+values[1]
 if result<0{panic("marker")}
 return
}
func Run()int{return Work(Next(),Next())+Work(-2,-3)}
`
	req := allRequest(t, source, "inline.call-all", "Work")
	_, plan := planned(t, req, "", "keep")
	assertWorkspacePlan(t, plan, 1)
	writeFixture(t, req.Root, "runtime_test.go", `package fixture
import("testing";"reflect")
func TestAll(t *testing.T){if n:=Run();n!=45{t.Fatal(n)};if !reflect.DeepEqual(Events,[]int{1,1,9,9}){t.Fatal(Events)}}
`)
	runtime(t, req.Root, plan)
}

func TestInlineAllPointerAndValueMethodsPreserveReceiverEvaluation(t *testing.T) {
	for _, pointer := range []bool{false, true} {
		t.Run(map[bool]string{false: "value", true: "pointer"}[pointer], func(t *testing.T) {
			receiver := "T"
			body := "return t.Value+v"
			callReceiver := "&T{Value:10}"
			if pointer {
				receiver = "*T"
				body = "t.Value+=v;return t.Value"
				callReceiver = "T{Value:10}"
			}
			source := "package fixture\ntype T struct{Value int}\nfunc(t " + receiver + ") Add(v int)int{" + body + "}\nfunc Run()int{t:=" + callReceiver + ";return t.Add(2)+t.Add(3)}\n"
			req := allRequest(t, source, "inline.call-all", "Add")
			_, plan := planned(t, req, "", "remove")
			assertWorkspacePlan(t, plan, 1)
			want := "25"
			if pointer {
				want = "27"
			}
			writeFixture(t, req.Root, "runtime_test.go", "package fixture\nimport \"testing\"\nfunc TestAll(t *testing.T){if Run()!="+want+"{t.Fatal(Run())}}\n")
			runtime(t, req.Root, plan)
		})
	}
}

func TestInlineAllScalarVariableReplacesEveryClosureUseAndRemovesItsDeclaration(t *testing.T) {
	source := `package fixture
func Work(n int)int{x:=n+1;f:=func()int{return x*2};return x+f()}
`
	req := allRequest(t, source, "inline.variable-all", "x")
	info, plan := planned(t, req, "", "remove")
	assertWorkspacePlan(t, plan, 1)
	if info.Inline == nil || !info.Inline.All || info.Inline.CanKeep || !info.Inline.CanRemove || info.Occurrences != 2 {
		t.Fatal(info)
	}
	writeFixture(t, req.Root, "runtime_test.go", `package fixture
import "testing"
func TestAll(t *testing.T){if Work(3)!=12{t.Fatal(Work(3))}}
`)
	runtime(t, req.Root, plan)
}

func TestInlineAllAndExactScalarInlineRejectLexicalCaptureDespiteSuccessfulTypes(t *testing.T) {
	source := `package fixture
func Work()int{n:=1;x:=n+1;{n:=100;_ = n;return x}}
`
	for _, kind := range []string{"inline.variable", "inline.variable-all"} {
		t.Run(kind, func(t *testing.T) {
			use := strings.LastIndex(source, "x")
			req := allRequest(t, source, kind, "x")
			if kind == "inline.variable" {
				req.Selection = signature.UTF16Range(source, use, use+1)
				req.CandidateEdit = signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{{TextDocument: signature.TextDocument{URI: req.Target.URI}, Edits: []signature.TextEdit{{Range: req.Selection, NewText: "n+1"}}}}}
			}
			_, plan := planned(t, req, "", "remove")
			if len(plan.Conflicts) == 0 || len(plan.WorkspaceEdit.DocumentChanges) > 0 {
				t.Fatal("captured initializer was accepted", plan)
			}
		})
	}
	function := `package fixture
var Global=7
func Work()int{return Global}
func Run()int{Global:=99;_ = Global;return Work()}
`
	req := allRequest(t, function, "inline.call-all", "Work")
	info, err := Inspect(context.Background(), req)
	if err != nil {
		t.Fatal(err)
	}
	if len(info.Conflicts) == 0 || !strings.Contains(info.Conflicts[0].Message, "Global") {
		t.Fatal("package binding captured by a local was accepted", info)
	}
}

func TestInlineAllKeepsFunctionValuesAndRejectsUnsupportedExpansionWithoutPartialEdits(t *testing.T) {
	source := `package fixture
func Work(v int)int{return v+1}
var F=Work
func Run()int{return Work(3)}
`
	req := allRequest(t, source, "inline.call-all", "Work")
	info, plan := planned(t, req, "", "keep")
	assertWorkspacePlan(t, plan, 1)
	if info.Inline.CanRemove || info.Inline.References != 2 {
		t.Fatal(info)
	}
	_, remove := planned(t, req, "", "remove")
	if len(remove.Conflicts) == 0 || len(remove.WorkspaceEdit.DocumentChanges) > 0 {
		t.Fatal("removed a function with remaining value references")
	}
	for _, source := range []string{
		"package fixture\nfunc Work(v int)int{if v>0{return Work(v-1)};return 0}\nfunc Run()int{return Work(2)}\n",
		"package fixture\nfunc Work[T any](v T)T{return v}\nfunc Run()int{return Work(2)}\n",
		"package fixture\nfunc Work(v int)int{return v+1}\nfunc Run()int{return Work(Work(2))}\n",
	} {
		req := allRequest(t, source, "inline.call-all", "Work")
		info, err := Inspect(context.Background(), req)
		if err != nil {
			t.Fatal(err)
		}
		if len(info.Conflicts) == 0 {
			t.Fatal("unsupported expansion was accepted", source, info)
		}
	}
}

func TestInlineAllReceiverTypeBindingUsesAnUnshadowedAlias(t *testing.T) {
	source := `package fixture
type T int
type A=T
func(t T) F()any{return t}
func H()any{type T int;return A.F(0)}
`
	req := allRequest(t, source, "inline.call-all", "F")
	_, plan := planned(t, req, "", "remove")
	assertWorkspacePlan(t, plan, 1)
	writeFixture(t, req.Root, "runtime_test.go", `package fixture
import "testing"
func TestIdentity(t *testing.T){if _,ok:=H().(T);!ok{t.Fatal("receiver rebound to the caller-local type")}}
`)
	runtime(t, req.Root, plan)
}

func TestInlineAllValueReceiverThroughNilPointerPreservesArgumentPanicOrder(t *testing.T) {
	source := `package fixture
type T int
var Events []int
func(t T) F(v int){}
func argument()int{Events=append(Events,1);return 1}
func Run(){var p *T;defer func(){if recover()!=nil{Events=append(Events,2)}}();p.F(argument())}
`
	req := allRequest(t, source, "inline.call-all", "F")
	_, plan := planned(t, req, "", "remove")
	assertWorkspacePlan(t, plan, 1)
	writeFixture(t, req.Root, "runtime_test.go", `package fixture
import("testing";"reflect")
func TestOrder(t *testing.T){Run();if !reflect.DeepEqual(Events,[]int{1,2}){t.Fatal(Events)}}
`)
	runtime(t, req.Root, plan)
	for _, prefix := range []string{"defer ", "go "} {
		req := allRequest(t, strings.Replace(source, "p.F(argument())", prefix+"p.F(argument())", 1), "inline.call-all", "F")
		info, err := Inspect(context.Background(), req)
		if err != nil {
			t.Fatal(err)
		}
		if len(info.Conflicts) == 0 {
			t.Fatal("implicit receiver dereference was moved after scheduling", prefix)
		}
	}
}

func TestScalarInlineTypeConversionKeepsNamedAndBuiltinIdentityUnderShadowing(t *testing.T) {
	for _, kind := range []string{"inline.variable", "inline.variable-all"} {
		t.Run(kind, func(t *testing.T) {
			source := `package fixture
type Count int
type Original=Count
func F()bool{var x Count=7;{type Count int;_,ok:=any(x).(Original);return ok}}
`
			req := allRequest(t, source, kind, "x")
			if kind == "inline.variable" {
				use := strings.Index(source, "any(x)") + len("any(")
				req.Selection = signature.UTF16Range(source, use, use+1)
				req.Target.Position = req.Selection.Start
				req.CandidateEdit = signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{{TextDocument: signature.TextDocument{URI: req.Target.URI}, Edits: []signature.TextEdit{{Range: req.Selection, NewText: "7"}}}}}
			}
			_, plan := planned(t, req, "", "remove")
			assertWorkspacePlan(t, plan, 1)
			writeFixture(t, req.Root, "runtime_test.go", "package fixture\nimport \"testing\"\nfunc TestIdentity(t *testing.T){if !F(){t.Fatal(\"named type was rebound\")}}\n")
			runtime(t, req.Root, plan)
		})
	}
	for _, hasAlias := range []bool{false, true} {
		t.Run(map[bool]string{false: "shadowed-builtin-conflict", true: "original-builtin-alias"}[hasAlias], func(t *testing.T) {
			source := "package fixture\nfunc F()any{var x int=65;{type int=string;return any(x)}}\n"
			if hasAlias {
				source = strings.Replace(source, "func F", "type Original=int\nfunc F", 1)
			}
			req := allRequest(t, source, "inline.variable-all", "x")
			_, plan := planned(t, req, "", "remove")
			if !hasAlias {
				if len(plan.Conflicts) == 0 || len(plan.WorkspaceEdit.DocumentChanges) > 0 {
					t.Fatal("shadowed builtin conversion was accepted", plan)
				}
				return
			}
			assertWorkspacePlan(t, plan, 1)
			writeFixture(t, req.Root, "runtime_test.go", "package fixture\nimport \"testing\"\nfunc TestIdentity(t *testing.T){if value,ok:=F().(int);!ok||value!=65{t.Fatal(\"builtin rebound\",F())}}\n")
			runtime(t, req.Root, plan)
		})
	}
}
