package rpc

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	nativerefactor "github.com/shubolab/opengoland/engine/internal/refactor/native"
	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func TestNativeRefactoringRPCNamesRawMethodBeforeTypecheckAndPlansWorkspaceInline(t *testing.T) {
	t.Setenv("GOWORK", "off")
	t.Setenv("GOPROXY", "off")
	var fixture struct {
		Files map[string]string `json:"files"`
		Cases []struct {
			Kind      string                  `json:"kind"`
			Selection signature.Range         `json:"selection"`
			Edit      signature.WorkspaceEdit `json:"edit"`
		} `json:"cases"`
	}
	data, err := os.ReadFile("../refactor/native/testdata/gopls-v0.21.1-candidates.json")
	if err != nil {
		t.Fatal(err)
	}
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
	c := connect(t)
	c.send(t, 1, "initialize", map[string]any{"root": root})
	initialized := c.reply(t, 1)
	if initialized["error"] != nil || initialized["result"].(map[string]any)["features"].(map[string]any)["nativeRefactoring"] != true {
		t.Fatal(initialized)
	}
	raw := fixture.Cases[1]
	uri := signature.LocalURI(filepath.Join(root, "model.go"))
	for i := range raw.Edit.DocumentChanges {
		raw.Edit.DocumentChanges[i].TextDocument.URI = uri
	}
	sum := sha256.Sum256([]byte(fixture.Files["model.go"]))
	req := nativerefactor.Request{Request: signature.Request{OperationID: "native-inspect", Target: signature.Target{URI: uri, Position: raw.Selection.Start}, Overlays: []signature.Overlay{{URI: uri, Text: fixture.Files["model.go"], DocumentStamp: 42, LSP: &signature.LSPVersion{Session: 1, Version: 1}}}}, Kind: raw.Kind, Selection: raw.Selection, CandidateSourceSHA256: hex.EncodeToString(sum[:]), CandidateEdit: raw.Edit}
	c.send(t, 2, "refactor/native/inspect", req)
	reply := c.reply(t, 2)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	inspection := reply["result"].(map[string]any)
	if len(inspection["conflicts"].([]any)) != 0 {
		t.Fatal(inspection)
	}
	names := inspection["names"].([]any)
	if len(names) != 1 {
		t.Fatal(inspection)
	}
	req.OperationID = "native-plan"
	planReq := nativerefactor.PlanRequest{Request: req, InspectionID: inspection["inspectionId"].(string), ConfigurationDigest: inspection["configurationDigest"].(string), Names: []nativerefactor.NameSelection{{NameID: names[0].(map[string]any)["nameId"].(string), Name: "calculate"}}}
	c.send(t, 3, "refactor/native/plan", planReq)
	reply = c.reply(t, 3)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	plan := reply["result"].(map[string]any)
	if len(plan["conflicts"].([]any)) != 0 || plan["validation"].(map[string]any)["typeChecked"] != true {
		t.Fatal(plan)
	}
	changes := plan["workspaceEdit"].(map[string]any)["documentChanges"].([]any)
	text := changes[0].(map[string]any)["edits"].([]any)[0].(map[string]any)["newText"].(string)
	if !strings.Contains(text, "func (t T) calculate() int") || strings.Count(text, "func (t T) newMethod()") != 1 {
		t.Fatal(text)
	}
	// A pre-cancelled operation must never be started by the owned worker route.
	c.send(t, 4, "refactor/cancel", map[string]any{"operationId": "native-pre-cancel"})
	_ = c.reply(t, 4)
	req.OperationID = "native-pre-cancel"
	c.send(t, 5, "refactor/native/inspect", req)
	if cancelled := c.reply(t, 5); cancelled["error"] == nil {
		t.Fatal(cancelled)
	}
	// Independently planned all-use local inline: no gopls candidate is replayed.
	req.OperationID = "native-all-inspect"
	req.Kind = "inline.variable-all"
	req.Selection = signature.Range{Start: signature.Position{Line: 5, Character: 1}, End: signature.Position{Line: 5, Character: 7}}
	req.Target.Position = req.Selection.Start
	req.CandidateEdit = signature.WorkspaceEdit{}
	c.send(t, 6, "refactor/native/inspect", req)
	reply = c.reply(t, 6)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	inspection = reply["result"].(map[string]any)
	if len(inspection["conflicts"].([]any)) != 0 {
		t.Fatal(inspection)
	}
	req.OperationID = "native-all-plan"
	planReq = nativerefactor.PlanRequest{Request: req, InspectionID: inspection["inspectionId"].(string), ConfigurationDigest: inspection["configurationDigest"].(string), InlineDeclaration: "remove"}
	c.send(t, 7, "refactor/native/plan", planReq)
	reply = c.reply(t, 7)
	if reply["error"] != nil {
		t.Fatal(reply)
	}
	plan = reply["result"].(map[string]any)
	if len(plan["conflicts"].([]any)) != 0 || plan["validation"].(map[string]any)["typeChecked"] != true {
		t.Fatal(plan)
	}
	disk, err := os.ReadFile(filepath.Join(root, "model.go"))
	if err != nil || string(disk) != fixture.Files["model.go"] {
		t.Fatal("RPC planner changed source", err)
	}
	c.send(t, 8, "shutdown", map[string]any{})
	_ = c.reply(t, 8)
}
