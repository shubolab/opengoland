package jsonstruct

import (
	"bytes"
	"context"
	"fmt"
	"go/format"
	"go/parser"
	"go/token"
	"go/types"
	"strconv"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	"golang.org/x/tools/go/ast/astutil"
)

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
	response := PlanResponse{SymbolID: a.inspection.StructID, InspectionID: a.inspection.InspectionID, ConfigurationDigest: a.snapshot.Digest(), StructID: a.inspection.StructID, RootType: a.inspection.RootType, Declarations: []Declaration{}, Warnings: append([]Warning{}, a.inspection.Warnings...), Preconditions: []signature.Precondition{}, Directories: []signature.DirectoryCondition{}, WorkspaceEdit: signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{}}, Usages: []signature.Usage{}, Coverage: a.view.Coverage, Conflicts: append([]signature.Conflict{}, a.inspection.Conflicts...), Validation: signature.Validation{BeforeErrors: a.view.Errors, AfterErrors: []string{}}}
	reject := func(code, message string) { response.Conflicts = append(response.Conflicts, a.conflict(code, message)) }
	if request.InspectionID != a.inspection.InspectionID || request.StructID != a.inspection.StructID {
		reject("stale-json", "The JSON sample, settings or source context changed; analyze again")
	}
	if request.ConfigurationDigest != a.snapshot.Digest() {
		reject("stale-configuration", "The selected Go SDK or workspace configuration changed; analyze again")
	}
	text := a.file.Text
	generated := ""
	chosen := []inferredField{}
	if request.Mode == "type" {
		if len(request.FieldIDs) > 0 {
			reject("selection", "Type generation always includes the complete sampled root; field selection is available only when adding fields")
		}
		response.Declarations = append(response.Declarations, a.inspection.Declarations...)
		generated = sourceDeclarations(response.Declarations)
	} else {
		known := map[string]inferredField{}
		for _, f := range a.builder.fields {
			known[f.wire.FieldID] = f
		}
		seen := map[string]bool{}
		for _, id := range request.FieldIDs {
			f, ok := known[id]
			if !ok {
				reject("stale-field", "A selected JSON field does not belong to this analysis")
				continue
			}
			if seen[id] {
				reject("selection", "A JSON field was selected more than once")
				continue
			}
			seen[id] = true
			if !f.wire.Editable {
				reject("field", f.wire.JSONKey+": "+f.wire.Reason)
			}
		}
		// Text order is independent of checkbox click order.
		for _, f := range a.builder.fields {
			if seen[f.wire.FieldID] {
				chosen = append(chosen, f)
				response.Declarations = append(response.Declarations, f.declarations...)
			}
		}
		if len(chosen) == 0 {
			reject("selection", "Select one or more available JSON fields")
		}
		lines := []string{}
		for _, f := range chosen {
			lines = append(lines, f.wire.Name+" "+f.wire.Type+" "+f.tag)
		}
		generated = strings.Join(lines, "\n") + "\n" + sourceDeclarations(response.Declarations)
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	if request.Mode == "type" {
		text += "\n\n" + sourceDeclarations(response.Declarations)
	} else {
		lines := []string{}
		for _, f := range chosen {
			lines = append(lines, f.wire.Name+" "+f.wire.Type+" "+f.tag)
		}
		offset := a.view.Fset.PositionFor(a.structure.Fields.Closing, false).Offset
		text = text[:offset] + "\n" + strings.Join(lines, "\n") + "\n" + text[offset:] + "\n\n" + sourceDeclarations(response.Declarations)
	}
	fs := token.NewFileSet()
	file, e := parser.ParseFile(fs, a.file.Path, text, parser.ParseComments|parser.AllErrors)
	if e != nil {
		reject("parse", e.Error())
		return finish(response, a)
	}
	if a.addImport && strings.Contains(generated, a.alias+".") {
		astutil.AddNamedImport(fs, file, a.alias, "encoding/json")
	}
	var formatted bytes.Buffer
	if e = format.Node(&formatted, fs, file); e != nil {
		reject("format", e.Error())
		return finish(response, a)
	}
	checked, e := a.snapshot.Load(ctx, map[string]string{a.file.Path: formatted.String()})
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
	actual, ok := checked.Files[a.file.Path]
	if !ok {
		reject("intent", "The candidate file is outside the active Go context")
		return finish(response, a)
	}
	for _, decl := range response.Declarations {
		if _, ok := actual.Package.Types.Scope().Lookup(decl.Name).(*types.TypeName); !ok {
			reject("intent", "A generated declaration is missing: "+decl.Name)
		}
	}
	if request.Mode == "fields" {
		structures := allStructs(actual.AST)
		if a.ordinal >= len(structures) {
			reject("intent", "The destination struct disappeared")
		} else {
			value := actual.Package.TypesInfo.Types[structures[a.ordinal]]
			var st *types.Struct
			if value.Type != nil {
				st, _ = value.Type.Underlying().(*types.Struct)
			}
			if st == nil || st.NumFields() != a.typed.NumFields()+len(chosen) {
				reject("intent", "The candidate does not contain exactly the selected additional fields")
			} else {
				qualify := func(pkg *types.Package) string { return pkg.Path() }
				for i := 0; i < a.typed.NumFields(); i++ {
					before, after := a.typed.Field(i), st.Field(i)
					if before.Name() != after.Name() || a.typed.Tag(i) != st.Tag(i) || before.Embedded() != after.Embedded() || types.TypeString(before.Type(), qualify) != types.TypeString(after.Type(), qualify) {
						reject("intent", "The candidate changed an existing field, type or tag")
					}
				}
				for i, f := range chosen {
					index := a.typed.NumFields() + i
					if st.Field(index).Name() != f.wire.Name {
						reject("intent", "An added field has the wrong Go name")
					}
					tag, err := strconv.Unquote(f.tag)
					if err != nil || st.Tag(index) != tag {
						reject("intent", "An added field has the wrong serialization tag")
					}
				}
			}
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
	response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, signature.DocumentEdit{TextDocument: signature.TextDocument{URI: a.file.URI, Version: version}, Edits: []signature.TextEdit{{Range: signature.UTF16Range(a.file.Text, 0, len(a.file.Text)), NewText: formatted.String()}}})
	response.Usages = append(response.Usages, signature.Usage{URI: a.file.URI, Range: a.inspection.Range, Kind: "json-generation", Message: fmt.Sprintf("Generate Go %s from JSON samples", request.Mode)})
	return finish(response, a)
}
