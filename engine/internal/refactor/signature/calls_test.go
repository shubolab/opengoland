package signature

import (
	"context"
	"fmt"
	"go/ast"
	"go/importer"
	"go/parser"
	"go/token"
	"go/types"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"
)

// These tests type-check the original source, use production lowering, compile
// the complete candidate and compare observable outputs of owned fixtures.
func TestCallRewritePreservesObservableEffects(t *testing.T) {
	tests := []struct {
		name, source, oldDeclaration, newDeclaration string
		parameters                                   []int
		configure                                    func(*Change)
		expectedAfter                                string
	}{
		{"reorder", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,b int) int { return a*10+b }
func main(){ fmt.Println(Target(mark(1),mark(2)),trace) }
`, "func Target(a,b int) int { return a*10+b }", "func Target(b,a int) int { return a*10+b }", []int{1, 0}, nil, ""},
		{"remove_effectful_argument", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,unused int) int { return a }
func main(){ fmt.Println(Target(mark(1),mark(2)),trace) }
`, "func Target(a,unused int) int { return a }", "func Target(a int) int { return a }", []int{0}, nil, ""},
		{"typed_nil", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a int,p *int) int { if p!=nil { panic("expected nil") }; return a }
func main(){ fmt.Println(Target(mark(1),nil),trace) }
`, "func Target(a int,p *int) int", "func Target(p *int,a int) int", []int{1, 0}, nil, ""},
		{"tuple_argument", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func pair()(int,int){ return mark(1),mark(2) }
func Target(a,b int) int { return a*10+b }
func main(){ fmt.Println(Target(pair()),trace) }
`, "func Target(a,b int) int", "func Target(b,a int) int", []int{1, 0}, nil, ""},
		{"method_value_receiver", `package main
import "fmt"
var trace []int
type Counter struct { Value int }
var counter=Counter{Value:100}
func receiver()*Counter{ trace=append(trace,0); return &counter }
func mark(n int) int { trace=append(trace,n); return n }
func (c *Counter) Target(a,b int) int { return c.Value+a*10+b }
func main(){ fmt.Println(receiver().Target(mark(1),mark(2)),trace) }
`, "func (c *Counter) Target(a,b int) int", "func (c *Counter) Target(b,a int) int", []int{1, 0}, nil, ""},
		{"method_expression_receiver", `package main
import "fmt"
var trace []int
type Counter struct { Value int }
var counter=Counter{Value:100}
func receiver()*Counter{ trace=append(trace,0); return &counter }
func mark(n int) int { trace=append(trace,n); return n }
func (c *Counter) Target(a,b int) int { return c.Value+a*10+b }
func main(){ fmt.Println((*Counter).Target(receiver(),mark(1),mark(2)),trace) }
`, "func (c *Counter) Target(a,b int) int", "func (c *Counter) Target(b,a int) int", []int{1, 0}, nil, ""},
		{"same_type_dynamic_interface_call", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n);return n }
type API interface { Target(x,y int) int }
type Counter struct{}
func(Counter)Target(a,b int)int{return a*10+b}
func main(){ var api API=Counter{};fmt.Println(api.Target(mark(1),mark(2)),trace) }
`, "type API interface { Target(x,y int) int }\ntype Counter struct{}\nfunc(Counter)Target(a,b int)int", "type API interface { Target(y,x int) int }\ntype Counter struct{}\nfunc(Counter)Target(b,a int)int", []int{1, 0}, nil, ""},
		{"variadic_elements", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,b int,rest ...int) int { return a*10+b+rest[0]*100+rest[1]*1000 }
func main(){ fmt.Println(Target(mark(1),mark(2),mark(3),mark(4)),trace) }
`, "func Target(a,b int,rest ...int) int", "func Target(b,a int,rest ...int) int", []int{1, 0, 2}, nil, ""},
		{"variadic_spread", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,b int,rest ...int) int { return a*10+b+rest[0]*100+rest[1]*1000 }
func main(){ rest:=[]int{3,4};fmt.Println(Target(mark(1),mark(2),rest...),trace) }
`, "func Target(a,b int,rest ...int) int", "func Target(b,a int,rest ...int) int", []int{1, 0, 2}, nil, ""},
		{"variadic_to_slice", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a int,rest ...int) int { return a*10+rest[0] }
func main(){ fmt.Println(Target(mark(1),mark(2)),trace) }
`, "func Target(a int,rest ...int) int", "func Target(a int,rest []int) int", []int{0, 1}, func(change *Change) { change.Parameters[1].Variadic = false; change.Parameters[1].Type = "[]int" }, ""},
		{"defer_direct_recover", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
//go:noinline
func Target(a,b int) { fmt.Println(recover(),trace,a,b) }
func main(){ defer Target(mark(1),mark(2)); panic("boom") }
`, "func Target(a,b int)", "func Target(b,a int)", []int{1, 0}, nil, ""},
		{"defer_tuple", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func pair()(int,int){ return mark(1),mark(2) }
//go:noinline
func Target(a,b int) { fmt.Println(recover(),trace,a,b) }
func main(){ defer Target(pair()); panic("boom") }
`, "func Target(a,b int)", "func Target(b,a int)", []int{1, 0}, nil, ""},
		{"go_registration_order", `package main
import "fmt"
var trace []int
var done=make(chan int)
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,b int) { done<-a*10+b }
func main(){ go Target(mark(1),mark(2));mark(3);fmt.Println(<-done,trace) }
`, "func Target(a,b int)", "func Target(b,a int)", []int{1, 0}, nil, ""},
		{"short_circuit", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,b int) int { return a*10+b }
func main(){ fmt.Println(false&&Target(mark(1),mark(2))>0,trace) }
`, "func Target(a,b int) int", "func Target(b,a int) int", []int{1, 0}, nil, ""},
		{"remove_panic_argument", `package main
import "fmt"
var trace []int
func fail()int{ trace=append(trace,2);panic("argument panic") }
func Target(a,unused int) int { return a }
func main(){ defer func(){fmt.Println(recover(),trace)}();fmt.Println(Target(1,fail())) }
`, "func Target(a,unused int) int", "func Target(a int) int", []int{0}, nil, ""},
		{"inferred_generic", `package main
import "fmt"
var trace []int
type Number int
func mark(n int) Number { trace=append(trace,n); return Number(n) }
func Target[T ~int](a,b T) T { return a*10+b }
func main(){ fmt.Println(Target(mark(1),mark(2)),trace) }
`, "func Target[T ~int](a,b T) T", "func Target[T ~int](b,a T) T", []int{1, 0}, nil, ""},
		{"explicit_generic", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target[T ~int](a,b T) T { return a*10+b }
func main(){ fmt.Println(Target[int](mark(1),mark(2)),trace) }
`, "func Target[T ~int](a,b T) T", "func Target[T ~int](b,a T) T", []int{1, 0}, nil, ""},
		{"added_default_after_old_arguments", `package main
import "fmt"
var trace []int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,b int) int { return a*10+b }
func main(){ fmt.Println(Target(mark(1),mark(2)),trace) }
`, "func Target(a,b int) int", "func Target(extra,a,b int) int", []int{0, 1}, func(change *Change) {
			change.Parameters = append([]Row{{RowID: "extra", Name: "extra", Type: "int", DefaultArgument: &Expression{Kind: "expression", Text: "mark(3)"}}}, change.Parameters...)
		}, "12 [1 2 3]\n"},
		{"default_reference_uses_argument_binding", `package main
import "fmt"
var trace []int
var seen int
func mark(n int) int { trace=append(trace,n); return n }
func Target(a,b int) int { return a*10+b }
func main(){ a:=100;_ = a;v:=Target(mark(1),mark(2));fmt.Println(v,seen,trace) }
`, "func Target(a,b int) int { return a*10+b }", "func Target(extra,a,b int) int { seen=extra;return a*10+b }", []int{0, 1}, func(change *Change) {
			change.Parameters = append([]Row{{RowID: "extra", Name: "extra", Type: "int", DefaultArgument: &Expression{Kind: "expression", Text: "a + mark(3)"}}}, change.Parameters...)
		}, "12 4 [1 2 3]\n"},
		{"default_lambda_shadowing", `package main
import "fmt"
var seen int
func Target(a int) int { return a }
func main(){ a:=100;_=a;v:=Target(1);fmt.Println(v,seen) }
`, "func Target(a int) int { return a }", "func Target(extra,a int) int { seen=extra;return a }", []int{0}, func(change *Change) {
			change.Parameters = append([]Row{{RowID: "extra", Name: "extra", Type: "int", DefaultArgument: &Expression{Kind: "expression", Text: "(func(a int) int { return a })(5) + a"}}}, change.Parameters...)
		}, "1 6\n"},
		{"default_cannot_mutate_mapped_argument_snapshot", `package main
import "fmt"
var seen int
func bump(a *int)int{*a++;return *a}
func Target(a int)int{return a}
func main(){v:=Target(1);fmt.Println(v,seen)}
`, "func Target(a int)int{return a}", "func Target(extra,a int)int{seen=extra;return a}", []int{0}, func(change *Change) {
			change.Parameters = append([]Row{{RowID: "extra", Name: "extra", Type: "int", DefaultArgument: &Expression{Kind: "expression", Text: "bump(&a)"}}}, change.Parameters...)
		}, "1 2\n"},
		{"explicit_parameter_conversion", `package main
import "fmt"
func Target(a int) int { return a }
func main(){ fmt.Println(Target(7)) }
`, "func Target(a int) int { return a }", "func Target(a int64) int { return int(a) }", []int{0}, func(change *Change) {
			change.Parameters[0].Type = "int64"
			change.Parameters[0].DefaultArgument = &Expression{Kind: "expression", Text: "int64(a)"}
		}, ""},
		{"new_variadic_zero", `package main
import "fmt"
func Target(a int) int { return a }
func main(){ fmt.Println(Target(7)) }
`, "func Target(a int) int", "func Target(a int,rest ...string) int", []int{0}, func(change *Change) {
			change.Parameters = append(change.Parameters, Row{RowID: "rest", Name: "rest", Type: "string", Variadic: true, DefaultArgument: &Expression{Kind: "zero"}})
		}, ""},
		{"zero_with_shadowed_new_builtin", `package main
import "fmt"
func Target(a int)int{return a}
func main(){new:=17;_ = new;fmt.Println(Target(7))}
`, "func Target(a int)int", "func Target(a,extra int)int", []int{0}, func(change *Change) {
			change.Parameters = append(change.Parameters, Row{RowID: "extra", Name: "extra", Type: "int", DefaultArgument: &Expression{Kind: "zero"}})
		}, ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			fixture := callTestFixture(t, tt.source, "Target")
			change := callTestChange(fixture, tt.parameters, nil)
			if tt.configure != nil {
				tt.configure(&change)
			}
			edits, conflicts := RewriteCall(fixture.input, change)
			if len(conflicts) > 0 {
				t.Fatalf("unexpected conflicts: %+v", conflicts)
			}
			candidate := callApplyTestEdits(t, tt.source, edits)
			if !strings.Contains(candidate, tt.oldDeclaration) {
				t.Fatal("declaration to replace missing")
			}
			candidate = strings.Replace(candidate, tt.oldDeclaration, tt.newDeclaration, 1)
			before, after := callRunOwned(t, tt.source), callRunOwned(t, candidate)
			expected := before
			if tt.expectedAfter != "" {
				expected = tt.expectedAfter
			}
			if after != expected {
				t.Fatalf("observable output changed: before=%q after=%q expected=%q\n%s", before, after, expected, candidate)
			}
		})
	}
}

func TestCallRewriteResultsPreserveConsumers(t *testing.T) {
	tests := []struct {
		name, source, oldDeclaration, newDeclaration string
		results                                      []int
		configure                                    func(*Change)
	}{
		{"reorder_lhs_effects", `package main
import "fmt"
var trace []string
func next()int{trace=append(trace,"lhs");return 1}
func Target()(int,int){trace=append(trace,"call");return 10,20}
func main(){var out [2]int;out[0],out[next()]=Target();fmt.Println(trace,out)}
`, "return 10,20", "return 20,10", []int{1, 0}, nil},
		{"remove_blank_result", `package main
import "fmt"
func Target()(int,int){return 10,20}
func main(){n,_:=Target();fmt.Println(n)}
`, "func Target()(int,int){return 10,20}", "func Target()int{return 10}", []int{0}, nil},
		{"removed_consumed_result_migration", `package main
import "fmt"
func Target()(first,second int){return 10,20}
func main(){n,m:=Target();fmt.Println(n,m)}
`, "func Target()(first,second int){return 10,20}", "func Target()(first int){return 10}", []int{0}, func(change *Change) {
			change.RemovedResultUseMigrations = []Migration{{RowID: "result-1", Expression: "first+10"}}
		}},
		{"migration_cannot_mutate_retained_result_snapshot", `package main
import "fmt"
func Target()(first,second int){return 10,20}
func main(){n,m:=Target();fmt.Println(n,m)}
`, "func Target()(first,second int){return 10,20}", "func Target()(first int){return 10}", []int{0}, func(change *Change) {
			change.RemovedResultUseMigrations = []Migration{{RowID: "result-1", Expression: "(func() int { first++;return first+9 })()"}}
		}},
		{"added_result", `package main
import "fmt"
func Target()int{return 10}
func main(){fmt.Println(Target())}
`, "func Target()int{return 10}", "func Target()(int,error){return 10,nil}", []int{0}, func(change *Change) {
			change.Results = append(change.Results, Row{RowID: "new-error", Type: "error", BodyDefault: &Expression{Kind: "zero"}})
		}},
		{"retyped_result_explicit_conversion", `package main
import "fmt"
func Target()(value int){return 10}
func main(){var n int=Target();fmt.Println(n)}
`, "func Target()(value int){return 10}", "func Target()(value int64){return 10}", []int{0}, func(change *Change) {
			change.Results[0].Type = "int64"
			change.RemovedResultUseMigrations = []Migration{{RowID: "result-0", Expression: "int(value)"}}
		}},
		{"retyped_discarded_result", `package main
import "fmt"
func Target()(first,second int){return 10,20}
func main(){n,_:=Target();fmt.Println(n)}
`, "func Target()(first,second int){return 10,20}", "func Target()(first int,second string){return 10,\"twenty\"}", []int{0, 1}, func(change *Change) { change.Results[1].Type = "string" }},
		{"discard_all_results", `package main
import "fmt"
func Target()int{fmt.Println("called");return 10}
func main(){Target()}
`, "func Target()int{fmt.Println(\"called\");return 10}", "func Target(){fmt.Println(\"called\")}", []int{}, nil},
		{"parenthesized_blank_result", `package main
import "fmt"
func Target()int{fmt.Println("called");return 10}
func main(){_ = (Target())}
`, "func Target()int{fmt.Println(\"called\");return 10}", "func Target(){fmt.Println(\"called\")}", []int{}, nil},
		{"discarded_result_with_shadowed_new_builtin", `package main
import "fmt"
func Target()(int,int){return 10,20}
func main(){new:=17;_=new;n,_:=Target();fmt.Println(n)}
`, "func Target()(int,int){return 10,20}", "func Target()int{return 10}", []int{0}, nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f := callTestFixture(t, tt.source, "Target")
			change := callTestChange(f, nil, tt.results)
			if tt.configure != nil {
				tt.configure(&change)
			}
			edits, conflicts := RewriteCall(f.input, change)
			if len(conflicts) > 0 {
				t.Fatalf("conflicts: %+v", conflicts)
			}
			candidate := callApplyTestEdits(t, tt.source, edits)
			candidate = strings.Replace(candidate, tt.oldDeclaration, tt.newDeclaration, 1)
			before, after := callRunOwned(t, tt.source), callRunOwned(t, candidate)
			if before != after {
				t.Fatalf("before=%q after=%q\n%s", before, after, candidate)
			}
		})
	}
}

func TestFunctionValueCapturesReceiverOnce(t *testing.T) {
	source := `package main
import "fmt"
var trace []int
type Counter struct{Value int}
var counter=Counter{Value:100}
func receiver()*Counter{trace=append(trace,0);return &counter}
func mark(n int)int{trace=append(trace,n);return n}
func(c *Counter)Target(a,b int)int{return c.Value+a*10+b}
func main(){saved:=receiver().Target;fmt.Println(saved(mark(1),mark(2)),saved(mark(3),mark(4)),trace)}
`
	f := callTestTyped(t, source, "Target")
	var value ast.Expr
	ast.Inspect(f.input.File, func(n ast.Node) bool {
		if sel, ok := n.(*ast.SelectorExpr); ok && sel.Sel.Name == "Target" {
			value = sel
		}
		return true
	})
	if value == nil {
		t.Fatal("method value missing")
	}
	f.input.Signature, _ = f.input.Info.TypeOf(value).(*types.Signature)
	change := callTestChange(f, []int{1, 0}, nil)
	edits, conflicts := RewriteValue(ValueRewriteInput{CallRewriteInput: f.input, Expr: value}, change)
	if len(conflicts) > 0 {
		t.Fatalf("conflicts: %+v", conflicts)
	}
	candidate := callApplyTestEdits(t, source, edits)
	candidate = strings.Replace(candidate, "Target(a,b int)", "Target(b,a int)", 1)
	if before, after := callRunOwned(t, source), callRunOwned(t, candidate); before != after {
		t.Fatalf("before=%q after=%q\n%s", before, after, candidate)
	}
}

func TestCallRewriteNestedCallsComposeWithoutOverlappingEdits(t *testing.T) {
	source := `package main
import "fmt"
var trace []int
func mark(n int)int{trace=append(trace,n);return n}
func Target(a,b int)int{return a*10+b}
func main(){fmt.Println(Target(1,Target(mark(1),mark(2))),trace)}
`
	f := callTestFixture(t, source, "Target")
	change := callTestChange(f, []int{1, 0}, nil)
	calls := []*ast.CallExpr{}
	ast.Inspect(f.input.File, func(n ast.Node) bool {
		if call, ok := n.(*ast.CallExpr); ok {
			if id := callBaseIdent(call.Fun); id != nil && id.Name == "Target" {
				calls = append(calls, call)
			}
		}
		return true
	})
	if len(calls) != 2 {
		t.Fatal("expected nested calls")
	}
	inner := f.input
	inner.Call = calls[1]
	inner.Parent = calls[0]
	innerEdits, conflicts := RewriteCall(inner, change)
	if len(conflicts) > 0 {
		t.Fatal(conflicts)
	}
	outer := f.input
	outer.Call = calls[0]
	outer.NodeText = func(n ast.Node) string {
		a, b := callOffset(outer, n.Pos()), callOffset(outer, n.End())
		text := source[a:b]
		for _, e := range innerEdits {
			if e.Start >= a && e.End <= b {
				text = text[:e.Start-a] + e.NewText + text[e.End-a:]
			}
		}
		return text
	}
	edits, conflicts := RewriteCall(outer, change)
	if len(conflicts) > 0 {
		t.Fatal(conflicts)
	}
	candidate := callApplyTestEdits(t, source, edits)
	candidate = strings.Replace(candidate, "Target(a,b int)", "Target(b,a int)", 1)
	if before, after := callRunOwned(t, source), callRunOwned(t, candidate); before != after {
		t.Fatalf("before=%q after=%q\n%s", before, after, candidate)
	}
}

func TestCallRewriteKeepsArgumentComments(t *testing.T) {
	source := `package main
import "fmt"
func Target(a,b int)int{return a*10+b}
func main(){fmt.Println(Target(/* first */ 1, // second follows
2, /* trailing */
))}
`
	f := callTestFixture(t, source, "Target")
	change := callTestChange(f, []int{1, 0}, nil)
	edits, conflicts := RewriteCall(f.input, change)
	if len(conflicts) > 0 {
		t.Fatal(conflicts)
	}
	candidate := callApplyTestEdits(t, source, edits)
	candidate = strings.Replace(candidate, "Target(a,b int)", "Target(b,a int)", 1)
	for _, comment := range []string{"/* first */", "// second follows", "/* trailing */"} {
		if !strings.Contains(candidate, comment) {
			t.Fatal("lost comment", comment)
		}
	}
	if before, after := callRunOwned(t, source), callRunOwned(t, candidate); before != after {
		t.Fatalf("before=%q after=%q\n%s", before, after, candidate)
	}
}

func TestCallRewriteConflictsAreLocatedAndProduceNoEdits(t *testing.T) {
	source := `package main
func Target(a int)(int,int){return a,2}
func main(){ /* 中文 😀 */ n,m:=Target(1);_,_=n,m }
`
	for _, name := range []string{"missing_default", "removed_consumed_result", "repeated_parameter", "variadic_not_last", "malformed_default"} {
		t.Run(name, func(t *testing.T) {
			f := callTestFixture(t, source, "Target")
			change := callTestChange(f, nil, nil)
			switch name {
			case "missing_default":
				change.Parameters = append(change.Parameters, Row{RowID: "new", Name: "b", Type: "int"})
			case "removed_consumed_result":
				change.Results = change.Results[:1]
			case "repeated_parameter":
				change.Parameters = append(change.Parameters, change.Parameters[0])
			case "variadic_not_last":
				change.Parameters[0].Variadic = true
				change.Parameters = append(change.Parameters, Row{RowID: "new", Name: "b", Type: "int", DefaultArgument: &Expression{Kind: "zero"}})
			case "malformed_default":
				change.Parameters[0].DefaultArgument = &Expression{Kind: "expression", Text: "("}
			}
			edits, conflicts := RewriteCall(f.input, change)
			if len(edits) != 0 || len(conflicts) != 1 {
				t.Fatalf("edits=%v conflicts=%v", edits, conflicts)
			}
			if conflicts[0].URI != "file:///fixture.go" || conflicts[0].Range.Start.Line != 2 || conflicts[0].Range.Start.Character != 30 {
				t.Fatalf("wrong UTF-16 location: %+v", conflicts[0])
			}
		})
	}
	if edits, conflicts := RewriteCall(CallRewriteInput{}, Change{}); len(edits) != 0 || len(conflicts) != 1 {
		t.Fatalf("nil context must conflict: %v %v", edits, conflicts)
	}
}

func TestFunctionValueRecoverRequiresTypedFlowPropagation(t *testing.T) {
	source := `package main
func Target(a,b int){recover()}
func main(){saved:=Target;defer saved(1,2)}
`
	f := callTestTyped(t, source, "Target")
	var expr ast.Expr
	ast.Inspect(f.input.File, func(n ast.Node) bool {
		if assign, ok := n.(*ast.AssignStmt); ok {
			expr = assign.Rhs[0]
		}
		return true
	})
	f.input.Signature, _ = f.input.Info.TypeOf(expr).(*types.Signature)
	edits, conflicts := RewriteValue(ValueRewriteInput{CallRewriteInput: f.input, Expr: expr, TargetMayRecover: true}, callTestChange(f, []int{1, 0}, nil))
	if len(edits) != 0 || len(conflicts) != 1 || conflicts[0].Code != "deferred-function-value" {
		t.Fatalf("edits=%v conflicts=%v", edits, conflicts)
	}
}

func TestCallDefaultBindingsAreLexicalAndRejectAmbiguousKeys(t *testing.T) {
	bindings := map[string]string{"a": "__oldA"}
	text, err := callBindExpression("(func(a int) int { return a })(5) + a", bindings)
	if err != nil || text != "(func(a int) int { return a })(5) + __oldA" {
		t.Fatalf("%q %v", text, err)
	}
	text, err = callBindExpression("map[int]int{a: a}", bindings)
	if err != nil || text != "map[int]int{__oldA: __oldA}" {
		t.Fatalf("%q %v", text, err)
	}
	text, err = callBindExpression("struct{a int}{a: a}", bindings)
	if err != nil || text != "struct{a int}{a: __oldA}" {
		t.Fatalf("%q %v", text, err)
	}
	if _, err = callBindExpression("Named{a: a}", bindings); err == nil {
		t.Fatal("A declaration-bound resolver must disambiguate a named literal's key")
	}
}

func TestCallRewriteCancellationAndSnapshotIsolation(t *testing.T) {
	source := `package main
func Target[T ~int](a,b T)T{return a*10+b}
func main(){_ = Target(1,2)}
`
	f := callTestFixture(t, source, "Target")
	change := callTestChange(f, []int{1, 0}, nil)
	f.input.GenericSubstitutions = map[string]string{"U": "int"}
	if _, conflicts := RewriteCall(f.input, change); len(conflicts) > 0 {
		t.Fatal(conflicts)
	}
	if len(f.input.GenericSubstitutions) != 1 || f.input.GenericSubstitutions["U"] != "int" {
		t.Fatalf("Input snapshot mutated: %v", f.input.GenericSubstitutions)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	f.input.Context = ctx
	if edits, conflicts := RewriteCall(f.input, change); len(edits) != 0 || len(conflicts) != 1 || conflicts[0].Code != "cancelled" {
		t.Fatalf("%v %v", edits, conflicts)
	}
}

func TestCallRewriteConcurrentPlansShareOnlyImmutableSnapshot(t *testing.T) {
	source := "package main\r\nfunc Target[T ~int](α,β T)T{return α*10+β}\r\nfunc main(){ /* 中文 😀 */ _ = Target(1,2)}\r\n"
	f := callTestFixture(t, source, "Target")
	f.input.GenericSubstitutions = map[string]string{"U": "int"}
	change := callTestChange(f, []int{1, 0}, nil)
	baseline, conflicts := RewriteCall(f.input, change)
	if len(conflicts) > 0 || len(baseline) != 1 {
		t.Fatalf("%v %v", baseline, conflicts)
	}
	var wait sync.WaitGroup
	failures := make(chan string, 24)
	for n := 0; n < 24; n++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			edits, conflicts := RewriteCall(f.input, change)
			if len(conflicts) > 0 || len(edits) != 1 || edits[0] != baseline[0] {
				failures <- fmt.Sprintf("%v %v", edits, conflicts)
			}
		}()
	}
	wait.Wait()
	close(failures)
	for failure := range failures {
		t.Error(failure)
	}
	if len(f.input.GenericSubstitutions) != 1 {
		t.Fatal("Shared generic substitutions were mutated")
	}
}

func TestCallDefaultNewLambdaCannotCaptureGeneratedArgument(t *testing.T) {
	source := `package main
import "fmt"
var seen int
func Target(a int)int{return a}
func main(){v:=Target(1);fmt.Println(v,seen)}
`
	f := callTestFixture(t, source, "Target")
	change := callTestChange(f, nil, nil)
	collidingName := fmt.Sprintf("__openGolandSignature%d_arg0", callOffset(f.input, f.input.Call.Pos()))
	expression := "(func(" + collidingName + " int) int { return a })(5)"
	change.Parameters = append([]Row{{RowID: "extra", Name: "extra", Type: "int", DefaultArgument: &Expression{Kind: "expression", Text: expression}}}, change.Parameters...)
	edits, conflicts := RewriteCall(f.input, change)
	if len(conflicts) > 0 {
		t.Fatal(conflicts)
	}
	candidate := callApplyTestEdits(t, source, edits)
	candidate = strings.Replace(candidate, "func Target(a int)int{return a}", "func Target(extra,a int)int{seen=extra;return a}", 1)
	if output := callRunOwned(t, candidate); output != "1 1\n" {
		t.Fatalf("A new lambda captured the original argument: %q\n%s", output, candidate)
	}
}

func TestCallRewriteKeepsProvenPureSitesReadable(t *testing.T) {
	source := `package main
func Target(a,b int)int{return a*10+b}
func main(){a,b:=1,2;_ = Target(a,b)}
`
	f := callTestFixture(t, source, "Target")
	change := callTestChange(f, []int{1, 0}, nil)
	edits, conflicts := RewriteCall(f.input, change)
	if len(conflicts) > 0 || len(edits) != 1 || edits[0].NewText != "Target(b, a)" {
		t.Fatalf("Expected the proven pure permutation without an adapter: %v %v", edits, conflicts)
	}
	change = callTestChange(f, nil, nil)
	change.Name = "Renamed"
	edits, conflicts = RewriteCall(f.input, change)
	if len(conflicts) > 0 || len(edits) != 1 || edits[0].NewText != "Renamed(a,b)" {
		t.Fatalf("An unchanged mapping should retain its original argument text: %v %v", edits, conflicts)
	}
}

type callTypedFixture struct {
	input       CallRewriteInput
	declaration *ast.FuncDecl
}

func callTestTyped(t *testing.T, source, target string) callTypedFixture {
	t.Helper()
	fset := token.NewFileSet()
	file, err := parser.ParseFile(fset, "fixture.go", source, parser.ParseComments|parser.AllErrors)
	if err != nil {
		t.Fatal(err)
	}
	info := &types.Info{Types: map[ast.Expr]types.TypeAndValue{}, Defs: map[*ast.Ident]types.Object{}, Uses: map[*ast.Ident]types.Object{}, Selections: map[*ast.SelectorExpr]*types.Selection{}, Instances: map[*ast.Ident]types.Instance{}, Implicits: map[ast.Node]types.Object{}}
	config := types.Config{Importer: importer.Default()}
	pkg, err := config.Check("main", fset, []*ast.File{file}, info)
	if err != nil {
		t.Fatal("baseline type check:", err)
	}
	f := callTypedFixture{input: CallRewriteInput{Fset: fset, File: file, Info: info, Package: pkg, Source: source, URI: "file:///fixture.go"}}
	for _, decl := range file.Decls {
		if fn, ok := decl.(*ast.FuncDecl); ok && fn.Name.Name == target {
			f.declaration = fn
			sig := info.Defs[fn.Name].Type().(*types.Signature)
			for n := 0; n < sig.Params().Len(); n++ {
				v := sig.Params().At(n)
				typ := v.Type()
				variadic := sig.Variadic() && n == sig.Params().Len()-1
				if variadic {
					typ = typ.(*types.Slice).Elem()
				}
				f.input.OldParameters = append(f.input.OldParameters, Row{RowID: fmt.Sprintf("parameter-%d", n), OldIndex: callTestInt(n), Name: v.Name(), Type: types.TypeString(typ, func(p *types.Package) string {
					if p == pkg {
						return ""
					}
					return p.Name()
				}), Variadic: variadic})
			}
			for n := 0; n < sig.Results().Len(); n++ {
				v := sig.Results().At(n)
				f.input.OldResults = append(f.input.OldResults, Row{RowID: fmt.Sprintf("result-%d", n), OldIndex: callTestInt(n), Name: v.Name(), Type: types.TypeString(v.Type(), func(p *types.Package) string {
					if p == pkg {
						return ""
					}
					return p.Name()
				})})
			}
		}
	}
	if f.declaration == nil {
		t.Fatal("target declaration missing")
	}
	return f
}

func callTestFixture(t *testing.T, source, target string) callTypedFixture {
	t.Helper()
	f := callTestTyped(t, source, target)
	parents := map[ast.Node]ast.Node{}
	stack := []ast.Node{}
	ast.Inspect(f.input.File, func(n ast.Node) bool {
		if n == nil {
			stack = stack[:len(stack)-1]
			return false
		}
		if len(stack) > 0 {
			parents[n] = stack[len(stack)-1]
		}
		stack = append(stack, n)
		if call, ok := n.(*ast.CallExpr); ok {
			if id := callBaseIdent(call.Fun); id != nil && id.Name == target {
				if f.input.Call == nil {
					f.input.Call = call
				}
			}
		}
		return true
	})
	if f.input.Call == nil {
		t.Fatal("target call missing")
	}
	f.input.Parent = parents[f.input.Call]
	f.input.Signature, _ = f.input.Info.TypeOf(f.input.Call.Fun).(*types.Signature)
	return f
}

func callTestChange(f callTypedFixture, parameters, results []int) Change {
	c := Change{Name: f.declaration.Name.Name}
	if parameters == nil {
		for n := range f.input.OldParameters {
			parameters = append(parameters, n)
		}
	}
	if results == nil {
		for n := range f.input.OldResults {
			results = append(results, n)
		}
	}
	for _, n := range parameters {
		c.Parameters = append(c.Parameters, f.input.OldParameters[n])
	}
	for _, n := range results {
		c.Results = append(c.Results, f.input.OldResults[n])
	}
	return c
}

func callTestInt(n int) *int { return &n }
func callApplyTestEdits(t *testing.T, source string, edits []ByteEdit) string {
	t.Helper()
	edits = append([]ByteEdit(nil), edits...)
	sort.Slice(edits, func(i, j int) bool { return edits[i].Start > edits[j].Start })
	previous := len(source) + 1
	for _, e := range edits {
		if e.Start < 0 || e.End < e.Start || e.End > len(source) || e.End > previous {
			t.Fatal("overlapping/invalid edit", e)
		}
		source = source[:e.Start] + e.NewText + source[e.End:]
		previous = e.Start
	}
	return source
}
func callRunOwned(t *testing.T, source string) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "go.mod"), []byte("module example.com/opengoland-call-fixture\n\ngo 1.24.0\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "main.go"), []byte(source), 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "run", ".")
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), "GOWORK=off", "GOTOOLCHAIN=local", "GOPROXY=off", "GOTELEMETRY=off", "GOFLAGS=")
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("owned fixture failed: %v\n%s\n%s", err, output, source)
	}
	return string(output)
}
