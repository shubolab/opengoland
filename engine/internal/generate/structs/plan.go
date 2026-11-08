package structs

import (
	"bytes"
	"context"
	"fmt"
	"go/format"
	"go/parser"
	"go/token"
	"go/types"
	"sort"
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
func identifier(name string) bool { return name != "_" && token.IsIdentifier(name) }
func namedUse(named *types.Named) string {
	name := named.Obj().Name()
	if named.TypeParams().Len() > 0 {
		args := []string{}
		for i := 0; i < named.TypeParams().Len(); i++ {
			args = append(args, named.TypeParams().At(i).Obj().Name())
		}
		name += "[" + strings.Join(args, ", ") + "]"
	}
	return name
}
func symbolic(named *types.Named) types.Type {
	if named.TypeParams().Len() == 0 {
		return named
	}
	args := []types.Type{}
	for i := 0; i < named.TypeParams().Len(); i++ {
		args = append(args, named.TypeParams().At(i))
	}
	if typ, e := types.Instantiate(nil, named, args, false); e == nil {
		return typ
	}
	return named
}

type selected struct {
	selection Selection
	field     field
	index     int
	typeText  string
}

func Plan(ctx context.Context, request PlanRequest) (PlanResponse, error) {
	a, e := open(ctx, request.Request)
	if e != nil {
		return PlanResponse{}, e
	}
	defer a.snapshot.Close()
	response := PlanResponse{SymbolID: a.inspection.StructID, InspectionID: a.inspection.InspectionID, ConfigurationDigest: a.snapshot.Digest(), StructID: a.inspection.StructID, Generated: []Generated{}, Preconditions: []signature.Precondition{}, Directories: []signature.DirectoryCondition{}, WorkspaceEdit: signature.WorkspaceEdit{DocumentChanges: []signature.DocumentEdit{}}, Usages: []signature.Usage{}, Coverage: a.view.Coverage, Conflicts: append([]signature.Conflict{}, a.inspection.Conflicts...), Validation: signature.Validation{BeforeErrors: a.view.Errors, AfterErrors: []string{}}}
	reject := func(code, message string) { response.Conflicts = append(response.Conflicts, a.conflict(code, message)) }
	if request.InspectionID != a.inspection.InspectionID || request.StructID != a.inspection.StructID {
		reject("stale-struct", "The inspected struct or source context changed; inspect helpers again")
	}
	if request.ConfigurationDigest != a.snapshot.Digest() {
		reject("stale-configuration", "The Go SDK or workspace configuration changed; inspect helpers again")
	}
	constructor := request.Kind == "constructor"
	getter := request.Kind == "getter" || request.Kind == "both"
	setter := request.Kind == "setter" || request.Kind == "both"
	if !constructor && !getter && !setter {
		reject("kind", "Choose constructor, getter, setter or both")
	}
	if request.ConstructorKind == "" {
		request.ConstructorKind = "pointer"
	}
	if request.GetterKind == "" {
		request.GetterKind = "pointer"
	}
	if constructor && request.ConstructorKind != "pointer" && request.ConstructorKind != "value" {
		reject("constructor-kind", "Choose pointer or value constructor result")
	}
	if getter && request.GetterKind != "pointer" && request.GetterKind != "value" {
		reject("getter-kind", "Choose pointer or value getter receiver")
	}
	if request.ReceiverName == "" {
		request.ReceiverName = "r"
	}
	if !constructor && !identifier(request.ReceiverName) {
		reject("receiver-name", "Choose a valid receiver variable name")
	}
	reserved := map[string]bool{a.named.Obj().Name(): true}
	typeParameterNames := map[string]bool{}
	for i := 0; i < a.named.TypeParams().Len(); i++ {
		reserved[a.named.TypeParams().At(i).Obj().Name()] = true
		typeParameterNames[a.named.TypeParams().At(i).Obj().Name()] = true
	}
	if !constructor && typeParameterNames[request.ReceiverName] {
		reject("receiver-name", "The receiver variable conflicts with a generic type parameter")
	}
	if constructor {
		if request.ConstructorName == "" {
			request.ConstructorName = a.inspection.ConstructorName
		}
		if !identifier(request.ConstructorName) || request.ConstructorName == "init" {
			reject("constructor-name", "Choose a valid callable constructor function name")
		} else if a.owner.Package.Types.Scope().Lookup(request.ConstructorName) != nil {
			reject("constructor-name", "The constructor name already exists in this package")
		}
	}
	known := map[string]int{}
	for i, field := range a.fields {
		known[field.wire.FieldID] = i
	}
	chosen := []selected{}
	seen := map[string]bool{}
	parameters := map[string]bool{}
	methods := map[string]bool{}
	im := newImports(a)
	for _, selection := range request.Selections {
		i, ok := known[selection.FieldID]
		if !ok {
			reject("stale-field", "A selected field no longer belongs to the struct")
			continue
		}
		if seen[selection.FieldID] {
			reject("selection", "A field was selected more than once")
			continue
		}
		seen[selection.FieldID] = true
		field := a.fields[i]
		if !field.wire.Editable {
			reject("field", field.wire.Name+": "+field.wire.Reason)
			continue
		}
		if selection.ParameterName == "" {
			selection.ParameterName = field.wire.ParameterName
		}
		if selection.GetterName == "" {
			selection.GetterName = field.wire.GetterName
		}
		if selection.SetterName == "" {
			selection.SetterName = field.wire.SetterName
		}
		if constructor || setter {
			if !identifier(selection.ParameterName) || constructor && reserved[selection.ParameterName] || setter && typeParameterNames[selection.ParameterName] {
				reject("parameter-name", "Parameter "+selection.ParameterName+" conflicts with an identifier, the struct or its generic parameters")
			}
			if constructor && parameters[selection.ParameterName] {
				reject("parameter-name", "Constructor parameters must have distinct names")
			}
			parameters[selection.ParameterName] = true
			im.used[selection.ParameterName] = true
			if setter && selection.ParameterName == request.ReceiverName {
				reject("parameter-name", "A setter value parameter must differ from its receiver variable")
			}
		}
		for _, entry := range []struct {
			enabled bool
			name    string
		}{{getter, selection.GetterName}, {setter, selection.SetterName}} {
			if !entry.enabled {
				continue
			}
			if !identifier(entry.name) {
				reject("method-name", "Choose a valid helper method name")
				continue
			}
			if methods[entry.name] {
				reject("method-name", "Selected helpers use the same method name "+entry.name)
			}
			methods[entry.name] = true
			if message := a.methodConflict(entry.name); message != "" {
				reject("method-name", entry.name+": "+message)
			}
		}
		typeText := field.typeSource
		if typeText == "" {
			var err error
			typeText, err = im.render(field.variable.Type())
			if err != nil {
				reject("field-type", field.wire.Name+": "+err.Error())
			}
		}
		chosen = append(chosen, selected{selection: selection, field: field, index: i, typeText: typeText})
	}
	if !constructor && len(chosen) == 0 {
		reject("selection", "Select one or more fields for accessors")
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	use := namedUse(a.named)
	var generated strings.Builder
	if constructor {
		parameters := []string{}
		members := []string{}
		for _, chosen := range chosen {
			parameters = append(parameters, chosen.selection.ParameterName+" "+chosen.typeText)
			members = append(members, chosen.field.wire.Name+": "+chosen.selection.ParameterName)
		}
		generic := ""
		if a.spec.TypeParams != nil {
			start, end := a.view.Fset.PositionFor(a.spec.TypeParams.Pos(), false).Offset, a.view.Fset.PositionFor(a.spec.TypeParams.End(), false).Offset
			generic = a.owner.Text[start:end]
		}
		result, expression := use, use
		if request.ConstructorKind == "pointer" {
			result = "*" + result
			expression = "&" + expression
		}
		fmt.Fprintf(&generated, "// %s constructs a %s using the selected fields.\nfunc %s%s(%s) %s {\nreturn %s{%s}\n}\n", request.ConstructorName, a.named.Obj().Name(), request.ConstructorName, generic, strings.Join(parameters, ", "), result, expression, strings.Join(members, ", "))
		response.Generated = append(response.Generated, Generated{Kind: "constructor", Name: request.ConstructorName})
	} else {
		for _, chosen := range chosen {
			if getter {
				receiver := use
				if request.GetterKind == "pointer" {
					receiver = "*" + receiver
				}
				fmt.Fprintf(&generated, "// %s returns the %s field.\nfunc (%s %s) %s() (%s) {\nreturn %s.%s\n}\n", chosen.selection.GetterName, chosen.field.wire.Name, request.ReceiverName, receiver, chosen.selection.GetterName, chosen.typeText, request.ReceiverName, chosen.field.wire.Name)
				response.Generated = append(response.Generated, Generated{Kind: "getter", Name: chosen.selection.GetterName, FieldID: chosen.field.wire.FieldID})
			}
			if setter {
				fmt.Fprintf(&generated, "// %s updates the %s field.\nfunc (%s *%s) %s(%s %s) {\n%s.%s = %s\n}\n", chosen.selection.SetterName, chosen.field.wire.Name, request.ReceiverName, use, chosen.selection.SetterName, chosen.selection.ParameterName, chosen.typeText, request.ReceiverName, chosen.field.wire.Name, chosen.selection.ParameterName)
				response.Generated = append(response.Generated, Generated{Kind: "setter", Name: chosen.selection.SetterName, FieldID: chosen.field.wire.FieldID})
			}
		}
	}
	text := a.owner.Text + "\n" + generated.String()
	fs := token.NewFileSet()
	file, e := parser.ParseFile(fs, a.owner.Path, text, parser.ParseComments|parser.AllErrors)
	if e != nil {
		reject("parse", e.Error())
		return finish(response, a)
	}
	paths := []string{}
	for path := range im.added {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	for _, path := range paths {
		astutil.AddNamedImport(fs, file, im.added[path], path)
	}
	var formatted bytes.Buffer
	if e = format.Node(&formatted, fs, file); e != nil {
		reject("format", e.Error())
		return finish(response, a)
	}
	checked, e := a.snapshot.Load(ctx, map[string]string{a.owner.Path: formatted.String()})
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
	checkedFile, ok := checked.Files[a.owner.Path]
	if !ok {
		reject("intent", "The selected struct file disappeared from the active context")
		return finish(response, a)
	}
	obj, ok := checkedFile.Package.Types.Scope().Lookup(a.named.Obj().Name()).(*types.TypeName)
	if !ok {
		reject("intent", "The selected struct disappeared")
		return finish(response, a)
	}
	actual, ok := obj.Type().(*types.Named)
	if !ok {
		reject("intent", "The selected struct ceased to be a defined type")
		return finish(response, a)
	}
	actualStruct, ok := actual.Underlying().(*types.Struct)
	if !ok || actualStruct.NumFields() != a.structure.NumFields() {
		reject("intent", "The candidate changed the original struct shape")
		return finish(response, a)
	}
	for i := 0; i < actualStruct.NumFields(); i++ {
		if actualStruct.Field(i).Name() != a.structure.Field(i).Name() || actualStruct.Tag(i) != a.structure.Tag(i) {
			reject("intent", "The candidate changed an original field or tag")
		}
	}
	if constructor {
		fn, ok := checkedFile.Package.Types.Scope().Lookup(request.ConstructorName).(*types.Func)
		if !ok {
			reject("intent", "The constructor was not added")
		} else {
			sig := fn.Type().(*types.Signature)
			if actual.TypeParams().Len() > 0 {
				arguments := []types.Type{}
				for i := 0; i < actual.TypeParams().Len(); i++ {
					arguments = append(arguments, actual.TypeParams().At(i))
				}
				instantiated, err := types.Instantiate(nil, sig, arguments, true)
				if err != nil {
					reject("intent", err.Error())
				} else {
					sig = instantiated.(*types.Signature)
				}
			}
			want := symbolic(actual)
			if request.ConstructorKind == "pointer" {
				want = types.NewPointer(want)
			}
			if sig.Params().Len() != len(chosen) || sig.Results().Len() != 1 || !types.Identical(sig.Results().At(0).Type(), want) {
				reject("intent", "The constructor signature does not match the selected fields/result")
			}
			for i, item := range chosen {
				if i < sig.Params().Len() && !types.Identical(sig.Params().At(i).Type(), actualStruct.Field(item.index).Type()) {
					reject("intent", "A constructor parameter has an unexpected field type")
				}
			}
		}
	} else {
		for _, entry := range response.Generated {
			var item *selected
			for i := range chosen {
				if chosen[i].field.wire.FieldID == entry.FieldID {
					item = &chosen[i]
					break
				}
			}
			if item == nil {
				reject("intent", "A helper lost its field identity")
				continue
			}
			receiver := symbolic(actual)
			pointer := entry.Kind == "setter" || request.GetterKind == "pointer"
			if pointer {
				receiver = types.NewPointer(receiver)
			}
			method := types.NewMethodSet(receiver).Lookup(checkedFile.Package.Types, entry.Name)
			if method == nil {
				reject("intent", "A generated method is missing from the requested method set")
				continue
			}
			sig := method.Type().(*types.Signature)
			own := method.Obj().Type().(*types.Signature)
			_, actualPointer := own.Recv().Type().(*types.Pointer)
			if actualPointer != pointer {
				reject("intent", "A helper has an unexpected pointer/value receiver")
			}
			want := actualStruct.Field(item.index).Type()
			if entry.Kind == "getter" {
				if sig.Params().Len() != 0 || sig.Results().Len() != 1 || !types.Identical(sig.Results().At(0).Type(), want) {
					reject("intent", "A getter does not return its selected field type")
				}
			} else if sig.Params().Len() != 1 || sig.Results().Len() != 0 || !types.Identical(sig.Params().At(0).Type(), want) {
				reject("intent", "A setter does not accept its selected field type")
			}
		}
	}
	if len(response.Conflicts) > 0 {
		return finish(response, a)
	}
	var version *int
	if a.owner.Overlay != nil && a.owner.Overlay.LSP != nil {
		v := a.owner.Overlay.LSP.Version
		version = &v
	}
	response.WorkspaceEdit.DocumentChanges = append(response.WorkspaceEdit.DocumentChanges, signature.DocumentEdit{TextDocument: signature.TextDocument{URI: a.owner.URI, Version: version}, Edits: []signature.TextEdit{{Range: signature.UTF16Range(a.owner.Text, 0, len(a.owner.Text)), NewText: formatted.String()}}})
	for _, item := range chosen {
		response.Usages = append(response.Usages, signature.Usage{URI: a.owner.URI, Range: item.field.position, Kind: "struct-helper", Message: request.Kind + " helper for " + item.field.wire.Name})
	}
	return finish(response, a)
}
