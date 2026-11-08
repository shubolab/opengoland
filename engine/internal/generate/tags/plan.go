package tags

import (
	"context"
	"fmt"
	"go/format"
	"go/types"
	"sort"
	"strings"
	"unicode"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func (a *analysis) conflict(code, message string) signature.Conflict {
	return signature.Conflict{URI: a.file.URI, Range: a.inspection.Range, Code: code, Message: message}
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

type byteEdit struct {
	start, end int
	text       string
}

func Plan(ctx context.Context, request PlanRequest) (PlanResponse, error) {
	a, e := open(ctx, request.Request)
	if e != nil {
		return PlanResponse{}, e
	}
	defer a.snapshot.Close()
	response := PlanResponse{SymbolID: a.inspection.StructID, InspectionID: a.inspection.InspectionID, ConfigurationDigest: a.snapshot.Digest(), StructID: a.inspection.StructID, Changes: []Change{}, Preconditions: []signature.Precondition{}, Directories: []signature.DirectoryCondition{}, WorkspaceEdit: signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{}}, Usages: []signature.Usage{}, Coverage: a.view.Coverage, Conflicts: append([]signature.Conflict{}, a.inspection.Conflicts...), Validation: signature.Validation{BeforeErrors: a.view.Errors, AfterErrors: []string{}}}
	reject := func(code, message string) { response.Conflicts = append(response.Conflicts, a.conflict(code, message)) }
	if request.InspectionID != a.inspection.InspectionID || request.StructID != a.inspection.StructID {
		reject("stale-struct", "The selected struct or fields changed; inspect tags again")
	}
	if request.ConfigurationDigest != a.snapshot.Digest() {
		reject("stale-configuration", "The Go SDK or workspace configuration changed; inspect tags again")
	}
	if !validKey(request.Key) {
		reject("key", "A tag key must be nonempty and contain no whitespace/control, quote or colon")
	}
	if request.Mode != "add-missing" && request.Mode != "update" && request.Mode != "remove" {
		reject("mode", "Choose add-missing, update or remove")
	}
	if request.Style == "" {
		request.Style = "snake"
	}
	if request.Mode != "remove" {
		if request.Style != "original" && request.Style != "camel" && request.Style != "snake" && request.Style != "lower" {
			reject("style", "Choose original, camel, snake or lower naming")
		}
		seen := map[string]bool{}
		for _, option := range request.Options {
			if option == "" || strings.ContainsRune(option, ',') || strings.IndexFunc(option, unicode.IsControl) >= 0 || seen[option] {
				reject("options", "Options must be distinct nonempty comma-separated values without control characters")
			}
			seen[option] = true
		}
	}
	known := map[string]int{}
	for i, field := range a.fields {
		known[field.wire.FieldID] = i
	}
	selected := map[string]bool{}
	for _, id := range request.FieldIDs {
		if _, ok := known[id]; !ok {
			reject("stale-field", "A selected field no longer belongs to this struct")
		}
		if selected[id] {
			reject("selection", "A field was selected more than once")
		}
		selected[id] = true
	}
	if len(selected) == 0 {
		reject("selection", "Select one or more struct fields")
	}
	for id := range request.ValueOverrides {
		if !selected[id] {
			reject("selection", "An explicit value refers to an unselected field")
		}
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	after := make([]string, len(a.fields))
	groups := map[string][]int{}
	for i, field := range a.fields {
		after[i] = field.tag
		groups[field.wire.GroupID] = append(groups[field.wire.GroupID], i)
		if !selected[field.wire.FieldID] {
			continue
		}
		if !field.wire.Editable {
			reject("field", field.wire.Name+": "+field.wire.Reason)
			continue
		}
		value, err := changedTag(field.tag, field.pairs, field.wire, request)
		if err != nil {
			reject("tag", err.Error())
			continue
		}
		after[i] = value
	}
	edits := []byteEdit{}
	for _, indices := range groups {
		first := indices[0]
		changed := false
		for _, i := range indices {
			if after[i] != a.fields[i].tag {
				changed = true
			}
		}
		if !changed {
			continue
		}
		valid := true
		for _, i := range indices {
			if !selected[a.fields[i].wire.FieldID] || after[i] != after[first] {
				valid = false
			}
		}
		if !valid {
			reject("shared-field", "Fields "+strings.Join(a.fields[first].wire.GroupNames, ", ")+" share one tag; select the whole group with identical values or split the declaration first")
			continue
		}
		node := a.fields[first].ast
		literal := tagLiteral(after[first], a.fields[first].literal)
		start, end := a.view.Fset.PositionFor(node.Type.End(), false).Offset, a.view.Fset.PositionFor(node.Type.End(), false).Offset
		if node.Tag != nil {
			start = a.view.Fset.PositionFor(node.Tag.Pos(), false).Offset
			end = a.view.Fset.PositionFor(node.Tag.End(), false).Offset
		} else if literal != "" {
			literal = " " + literal
		}
		edits = append(edits, byteEdit{start: start, end: end, text: literal})
		for _, i := range indices {
			response.Changes = append(response.Changes, Change{FieldID: a.fields[i].wire.FieldID, Before: a.fields[i].tag, After: after[i]})
			response.Usages = append(response.Usages, signature.Usage{URI: a.file.URI, Range: a.fields[i].position, Kind: "struct-tag", Message: request.Mode + " " + request.Key + " tag on " + a.fields[i].wire.Name})
		}
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	if len(edits) == 0 {
		response.Validation.AfterErrors = a.view.Errors
		response.Validation.TypeChecked = len(a.view.Errors) == 0
		if !response.Validation.TypeChecked {
			reject("typecheck", strings.Join(a.view.Errors, "\n"))
		}
		return finish(response, a)
	}
	sort.Slice(edits, func(i, j int) bool { return edits[i].start > edits[j].start })
	text := a.file.Text
	last := len(text) + 1
	for _, edit := range edits {
		if e = ctx.Err(); e != nil {
			return PlanResponse{}, e
		}
		if edit.end > last {
			reject("overlap", "The selected tag edits overlap")
			return finish(response, a)
		}
		text = text[:edit.start] + edit.text + text[edit.end:]
		last = edit.start
	}
	formatted, e := format.Source([]byte(text))
	if e != nil {
		reject("parse", e.Error())
		return finish(response, a)
	}
	checked, e := a.snapshot.Load(ctx, map[string]string{a.file.Path: string(formatted)})
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
	file, ok := checked.Files[a.file.Path]
	if !ok {
		reject("intent", "The edited struct file disappeared from the active Go context")
		return finish(response, a)
	}
	structures := structs(file.AST)
	if a.ordinal >= len(structures) {
		reject("intent", "The edited struct no longer exists")
		return finish(response, a)
	}
	typeValue := file.Package.TypesInfo.Types[structures[a.ordinal]]
	var actual *types.Struct
	if typeValue.Type != nil {
		actual, _ = typeValue.Type.Underlying().(*types.Struct)
	}
	if actual == nil || actual.NumFields() != len(a.fields) {
		reject("intent", "The candidate changed the struct's field shape")
		return finish(response, a)
	}
	for i, field := range a.fields {
		if actual.Field(i).Name() != field.wire.Name || actual.Tag(i) != after[i] {
			reject("intent", fmt.Sprintf("The candidate did not preserve the requested tag and field identity for %s", field.wire.Name))
		}
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	var version *int
	if a.file.Overlay != nil && a.file.Overlay.LSP != nil {
		v := a.file.Overlay.LSP.Version
		version = &v
	}
	response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, signature.DocumentEdit{TextDocument: signature.TextDocument{URI: a.file.URI, Version: version}, Edits: []signature.TextEdit{{Range: signature.UTF16Range(a.file.Text, 0, len(a.file.Text)), NewText: string(formatted)}}})
	return finish(response, a)
}
