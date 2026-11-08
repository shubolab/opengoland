package methods

import (
	"bytes"
	"context"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"go/types"
	"sort"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	"golang.org/x/tools/go/ast/astutil"
)

func (a *analysis) conflict(code, message string) signature.Conflict {
	return signature.Conflict{URI: a.target.URI, Range: signature.UTF16Range(a.target.Text, 0, 0), Code: code, Message: message}
}
func finish(response PlanResponse, a *analysis) (PlanResponse, error) {
	if e := a.snapshot.Unchanged(); e != nil {
		return PlanResponse{}, e
	}
	response.Preconditions = a.snapshot.Preconditions()
	response.Directories = a.snapshot.Directories()
	if len(response.Conflicts) > 0 {
		response.WorkspaceEdit.DocumentChanges = []signature.DocumentEdit{}
	}
	response.PlanDigest = digest(response)
	response.PlanID = response.PlanDigest[:24]
	return response, nil
}

func Plan(ctx context.Context, request PlanRequest) (PlanResponse, error) {
	a, e := open(ctx, request.Request)
	if e != nil {
		return PlanResponse{}, e
	}
	defer a.snapshot.Close()
	response := PlanResponse{SymbolID: a.inspection.InspectionID, InspectionID: a.inspection.InspectionID, ConfigurationDigest: a.snapshot.Digest(), Receiver: a.inspection.Receiver, Implemented: a.inspection.Implemented, GeneratedMethods: []Method{}, Preconditions: []signature.Precondition{}, Directories: []signature.DirectoryCondition{}, WorkspaceEdit: signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{}}, Usages: []signature.Usage{}, Coverage: a.view.Coverage, Conflicts: []signature.Conflict{}, Validation: signature.Validation{BeforeErrors: a.view.Errors, AfterErrors: []string{}}}
	reject := func(code, message string) { response.Conflicts = append(response.Conflicts, a.conflict(code, message)) }
	if request.InspectionID != a.inspection.InspectionID {
		reject("stale-inspection", "The receiver, interface or source context changed; inspect again")
	}
	if request.ConfigurationDigest != a.snapshot.Digest() {
		reject("stale-configuration", "The Go SDK or workspace configuration changed; inspect again")
	}
	if response.Receiver == nil {
		reject("receiver", "Choose an existing local receiver or enter a new type name")
	} else if request.ReceiverID != response.Receiver.ReceiverID {
		reject("stale-receiver", "The selected receiver changed; inspect again")
	}
	if a.selected == nil {
		reject("interface", "Choose an interface to implement")
	} else if a.interfaceType == nil {
		reject("type-arguments", "Supply all explicit generic interface type arguments")
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	owner := a.target
	if a.receiver != nil {
		owner = a.receiver.file
	}
	if ast.IsGenerated(owner.AST) {
		reject("generated-file", "Choose a receiver in a source file which is not generated")
		return finish(response, a)
	}
	if owner.Package.Types.Scope().Lookup("panic") != nil {
		reject("builtin-shadow", "The package shadows builtin panic; rename that declaration before generating unimplemented method bodies")
		return finish(response, a)
	}
	receiverName := request.ReceiverName
	if receiverName == "" {
		receiverName = "r"
	}
	if !token.IsIdentifier(receiverName) || receiverName == "panic" {
		reject("receiver-name", "Choose a valid receiver variable name other than panic")
		return finish(response, a)
	}
	selected := map[string]bool{}
	for _, id := range request.MethodIDs {
		if selected[id] {
			reject("selection", "A method was selected more than once")
		}
		selected[id] = true
	}
	if request.AllMissing {
		for _, m := range a.methods {
			if m.wire.Status == "conflict" {
				reject("method-conflict", m.wire.Name+": "+m.wire.Message)
			} else if m.wire.Status == "missing" {
				selected[m.wire.MethodID] = true
			}
		}
	}
	known := map[string]requiredMethod{}
	for _, m := range a.methods {
		known[m.wire.MethodID] = m
	}
	for id := range selected {
		m, ok := known[id]
		if !ok {
			reject("stale-method", "A selected method is no longer in this interface")
			continue
		}
		if m.wire.Status != "missing" {
			reject("method-conflict", m.wire.Name+": "+m.wire.Status+" "+m.wire.Message)
		}
	}
	if len(selected) == 0 && !a.inspection.Implemented {
		reject("selection", "Select one or more missing interface methods")
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	if len(selected) == 0 && a.newName == "" {
		response.Validation.AfterErrors = a.view.Errors
		response.Validation.TypeChecked = len(a.view.Errors) == 0
		if !response.Validation.TypeChecked {
			reject("typecheck", strings.Join(a.view.Errors, "\n"))
		}
		return finish(response, a)
	}
	im := newImports(owner, a)
	for _, alias := range im.aliases {
		if alias == "panic" {
			reject("builtin-shadow", "A destination import shadows builtin panic; choose another alias")
		}
	}
	text := owner.Text + "\n"
	if a.newName != "" {
		text += "type " + a.newName + " struct {}\n\n"
	}
	for _, method := range a.methods {
		if !selected[method.wire.MethodID] {
			continue
		}
		if e = ctx.Err(); e != nil {
			return PlanResponse{}, e
		}
		rendered, err := renderMethod(a, method, receiverName, im)
		if err != nil {
			reject("method-type", method.wire.Name+": "+err.Error())
			continue
		}
		text += rendered + "\n"
		response.GeneratedMethods = append(response.GeneratedMethods, method.wire)
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	fs := token.NewFileSet()
	file, e := parser.ParseFile(fs, owner.Path, text, parser.ParseComments|parser.AllErrors)
	if e != nil {
		reject("parse", e.Error())
		return finish(response, a)
	}
	paths := []string{}
	for p := range im.added {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, p := range paths {
		astutil.AddNamedImport(fs, file, im.added[p], p)
	}
	var formatted bytes.Buffer
	if e = format.Node(&formatted, fs, file); e != nil {
		reject("format", e.Error())
		return finish(response, a)
	}
	candidates := map[string]string{owner.Path: formatted.String()}
	checked, e := a.snapshot.LoadPackages(ctx, candidates, a.imports)
	if e != nil {
		return PlanResponse{}, e
	}
	response.Validation.AfterErrors = checked.Errors
	response.Validation.TypeChecked = len(checked.Errors) == 0
	if !response.Validation.TypeChecked {
		for _, message := range checked.Errors {
			reject("typecheck", message)
		}
		return finish(response, a)
	}
	// Resolve the interface and receiver afresh in the candidate's type universe.
	checkRequest := a.request
	if checkRequest.InterfaceID == "" {
		checkRequest.InterfaceID = a.selected.wire.InterfaceID
	}
	// Formatting can move the original caret. Re-resolve by the already
	// validated receiver identity/name, never by that obsolete byte position.
	checkRequest.ReceiverType = response.Receiver.Name
	checkRequest.NewTypeName = ""
	check, e := analyze(a.snapshot, checked, checkRequest, a.imports)
	if e == nil {
		e = check.selectInterface()
	}
	if e != nil {
		reject("intent", e.Error())
		return finish(response, a)
	}
	if check.receiver == nil || check.interfaceType == nil {
		reject("intent", "The candidate does not contain the requested receiver and interface")
		return finish(response, a)
	}
	typ := receiverType(check.receiver.named, a.request.ReceiverKind)
	for _, method := range a.methods {
		if !selected[method.wire.MethodID] {
			continue
		}
		found := false
		for _, actual := range check.methods {
			if actual.wire.Name == method.wire.Name && actual.wire.Status == "existing" {
				found = true
				break
			}
		}
		if !found {
			reject("intent", "The generated method "+method.wire.Name+" does not have the requested typed signature")
		}
	}
	response.Implemented = types.Implements(typ, check.interfaceType)
	if request.AllMissing && !response.Implemented {
		reject("intent", "The selected receiver still does not implement the requested interface")
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	var version *int
	if owner.Overlay != nil && owner.Overlay.LSP != nil {
		v := owner.Overlay.LSP.Version
		version = &v
	}
	response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, signature.DocumentEdit{TextDocument: signature.TextDocument{URI: owner.URI, Version: version}, Edits: []signature.TextEdit{{Range: signature.UTF16Range(owner.Text, 0, len(owner.Text)), NewText: formatted.String()}}})
	response.Usages = append(response.Usages, signature.Usage{URI: owner.URI, Range: response.Receiver.Range, Kind: "implementation", Message: fmt.Sprintf("Generate %d methods for %s", len(response.GeneratedMethods), a.selected.wire.Display)})
	return finish(response, a)
}
