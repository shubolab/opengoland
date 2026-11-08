package jsonstruct

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"reflect"
	"strconv"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

type analysis struct {
	snapshot                 *signature.SemanticSnapshot
	view                     signature.SemanticAnalysis
	file                     signature.SemanticFile
	structure                *ast.StructType
	typed                    *types.Struct
	ordinal                  int
	root                     *node
	request                  Request
	builder                  *builder
	inspection               InspectResponse
	alias                    string
	addImport, invalidImport bool
}

func digest(value any) string {
	data, _ := json.Marshal(value)
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}
func (a *analysis) conflict(code, message string) signature.Conflict {
	return signature.Conflict{URI: a.file.URI, Range: a.inspection.Range, Code: code, Message: message}
}
func Inspect(ctx context.Context, request Request) (InspectResponse, error) {
	a, e := open(ctx, request)
	if e != nil {
		return InspectResponse{}, e
	}
	defer a.snapshot.Close()
	if e = a.snapshot.Unchanged(); e != nil {
		return InspectResponse{}, e
	}
	return a.inspection, nil
}
func allStructs(file *ast.File) []*ast.StructType {
	out := []*ast.StructType{}
	ast.Inspect(file, func(node ast.Node) bool {
		if s, ok := node.(*ast.StructType); ok {
			out = append(out, s)
		}
		return true
	})
	return out
}
func open(ctx context.Context, request Request) (*analysis, error) {
	if e := ctx.Err(); e != nil {
		return nil, e
	}
	if request.Mode != "type" && request.Mode != "fields" {
		return nil, fmt.Errorf("choose type or fields JSON generation mode")
	}
	if request.NumericPolicy == "" {
		request.NumericPolicy = "auto"
	}
	if request.NumericPolicy != "auto" && request.NumericPolicy != "number" {
		return nil, fmt.Errorf("choose auto or number numeric policy")
	}
	if request.TypeName == "" {
		request.TypeName = "Root"
	}
	root, e := parseSample(request.JSONText)
	if e != nil {
		return nil, e
	}
	s, e := signature.OpenSemanticSnapshot(request.Request)
	if e != nil {
		return nil, e
	}
	fail := true
	defer func() {
		if fail {
			s.Close()
		}
	}()
	view, e := s.Load(ctx, nil)
	if e != nil {
		return nil, e
	}
	path, e := signature.LocalPath(request.Target.URI)
	if e != nil {
		return nil, e
	}
	file, ok := view.Files[path]
	if !ok {
		return nil, fmt.Errorf("select an editable Go file in the active build context")
	}
	offset, e := signature.UTF16Offset(file.Text, request.Target.Position)
	if e != nil {
		return nil, e
	}
	a := &analysis{snapshot: s, view: view, file: file, root: root, request: request, ordinal: -1}
	response := InspectResponse{ConfigurationDigest: s.Digest(), Mode: request.Mode, Name: request.TypeName, TypeName: request.TypeName, URI: file.URI, Range: signature.UTF16Range(file.Text, offset, offset), Fields: []Field{}, Declarations: []Declaration{}, Warnings: []Warning{}, Coverage: view.Coverage, Conflicts: []signature.Conflict{}}
	a.inspection = response
	reject := func(code, message string) { response.Conflicts = append(response.Conflicts, a.conflict(code, message)) }
	if ast.IsGenerated(file.AST) {
		reject("generated-file", "Choose a source file which is not generated")
	}
	if request.Mode == "fields" {
		smallest := len(file.Text) + 1
		all := allStructs(file.AST)
		for i, node := range all {
			start, end := view.Fset.PositionFor(node.Pos(), false).Offset, view.Fset.PositionFor(node.End(), false).Offset
			if offset >= start && offset <= end && end-start < smallest {
				a.structure = node
				a.ordinal = i
				smallest = end - start
				response.Name = "anonymous struct"
			}
		}
		ast.Inspect(file.AST, func(node ast.Node) bool {
			if spec, ok := node.(*ast.TypeSpec); ok {
				if structure, ok := spec.Type.(*ast.StructType); ok {
					start, end := view.Fset.PositionFor(spec.Name.Pos(), false).Offset, view.Fset.PositionFor(spec.End(), false).Offset
					if offset >= start && offset <= end && (a.structure == nil || a.structure == structure) {
						a.structure = structure
						response.Name = spec.Name.Name
						for i, s := range all {
							if s == structure {
								a.ordinal = i
								break
							}
						}
					}
				}
			}
			return true
		})
		if a.structure == nil {
			return nil, fmt.Errorf("place the caret on the destination struct name, field or tag")
		}
		value := file.Package.TypesInfo.Types[a.structure]
		if value.Type != nil {
			a.typed, _ = value.Type.Underlying().(*types.Struct)
		}
		if a.typed == nil {
			return nil, fmt.Errorf("the destination struct has no resolved Go type")
		}
		start, end := view.Fset.PositionFor(a.structure.Pos(), false).Offset, view.Fset.PositionFor(a.structure.End(), false).Offset
		response.Range = signature.UTF16Range(file.Text, start, end)
		response.TypeName = response.Name
		if root.kind == "array" && root.element != nil && root.element.kind == "object" {
			root = root.element
			a.root = root
			message := "Fields are inferred from the array's object samples; decode the original sample into a slice of this struct"
			if root.nullable {
				message = "Fields are inferred from non-null object samples; use a slice of pointers to this struct to preserve null array elements"
			}
			response.Warnings = append(response.Warnings, Warning{Path: "$", Code: "array-fields", Message: message})
		}
		if root.kind != "object" {
			reject("object-required", "Adding fields requires an object or an array of object samples")
		}
	} else {
		if !token.IsIdentifier(request.TypeName) || request.TypeName == "_" || types.Universe.Lookup(request.TypeName) != nil {
			reject("type-name", "Choose a non-predeclared Go type identifier")
		}
		if file.Package.Types.Scope().Lookup(request.TypeName) != nil {
			reject("type-name", "The requested root name already exists in this package")
		}
		for _, spec := range file.AST.Imports {
			if name := importName(file, spec); name == request.TypeName {
				reject("type-name", "The requested root name conflicts with an import in this file")
			}
		}
	}
	response.StructID = digest([]any{file.URI, file.Text, request.Mode, a.ordinal, response.TypeName})
	used := map[string]bool{}
	for _, name := range file.Package.Types.Scope().Names() {
		used[name] = true
	}
	for _, spec := range file.AST.Imports {
		used[importName(file, spec)] = true
	}
	used[request.TypeName] = true
	// Fields can be inserted inside generic declarations or function-local
	// anonymous structs. Their lexical names must not shadow our import or the
	// package-level nested types referenced by the generated fields.
	if a.structure != nil {
		for _, scope := range file.Package.TypesInfo.Scopes {
			if scope.Pos() <= a.structure.Pos() && scope.End() >= a.structure.End() {
				for _, name := range scope.Names() {
					used[name] = true
				}
			}
		}
	}
	alias := "json"
	existing := false
	for _, spec := range file.AST.Imports {
		path, _ := strconv.Unquote(spec.Path.Value)
		if path == "encoding/json" {
			existing = true
			alias = importName(file, spec)
			if alias == "." || alias == "_" {
				a.invalidImport = true
				alias = "json"
			}
			if a.structure != nil {
				for _, scope := range file.Package.TypesInfo.Scopes {
					if scope.Pos() <= a.structure.Pos() && scope.End() >= a.structure.End() && scope.Lookup(alias) != nil && scope.Lookup(alias).Parent() != file.Package.TypesInfo.Scopes[file.AST] {
						a.invalidImport = true
					}
				}
			}
		}
	}
	if !existing {
		base := alias
		for i := 2; used[alias]; i++ {
			alias = fmt.Sprintf("%s%d", base, i)
		}
		a.addImport = true
	}
	used[alias] = true
	a.alias = alias
	b := &builder{policy: request.NumericPolicy, alias: alias, used: used, builtins: map[string]bool{}, declarations: []Declaration{}, warnings: []Warning{}, fields: []inferredField{}}
	a.builder = b
	if request.Mode == "type" {
		inferred := b.value(root, "$", request.TypeName, true)
		response.RootType = inferred
		if inferred != request.TypeName {
			kind := "type"
			if inferred == b.json("RawMessage") || inferred == b.json("Number") {
				kind = "alias"
			}
			b.declarations = append(b.declarations, Declaration{Name: request.TypeName, Kind: kind, Type: inferred})
		}
	} else if root.kind == "object" {
		prefix := response.Name
		if prefix == "anonymous struct" {
			prefix = "Generated"
		}
		b.objectFields(root, "$", prefix, true)
		response.RootType = response.Name
		names, keys := a.existingNames()
		for i := range b.fields {
			f := &b.fields[i]
			if names[f.wire.Name] {
				f.wire.Editable = false
				f.wire.Reason = "An existing field or method already has this Go name"
			} else if keys[f.wire.JSONKey] {
				f.wire.Editable = false
				f.wire.Reason = "An existing or promoted field already uses this JSON key"
			}
		}
		if hasCustomCodec(file, a.structure, a.typed) {
			b.warning("$", "custom-codec", "The destination type has custom JSON methods; adding fields does not change those methods")
		}
	}
	for i := range b.fields {
		b.fields[i].wire.FieldID = digest([]any{response.StructID, request.JSONText, request.NumericPolicy, i, b.fields[i].wire.JSONKey})
		response.Fields = append(response.Fields, b.fields[i].wire)
	}
	response.Declarations = append(response.Declarations, b.declarations...)
	response.Warnings = append(response.Warnings, b.warnings...)
	response.Warnings = append(response.Warnings, Warning{Path: "$", Code: "sample-inference", Message: "Types describe the supplied samples, not a complete JSON schema; review inferred optional, null and heterogeneous values"})
	response.Preview = sourceDeclarations(response.Declarations)
	if request.Mode == "fields" {
		lines := []string{}
		for _, f := range b.fields {
			lines = append(lines, f.wire.Name+" "+f.wire.Type+" "+f.tag)
		}
		response.Preview = "// Fields to add\n" + strings.Join(lines, "\n") + "\n\n" + response.Preview
	}
	if a.invalidImport && strings.Contains(response.Preview, alias+".") {
		reject("json-import", "Use an unshadowed ordinary encoding/json import alias before generating JSON types")
	}
	for name := range b.builtins {
		shadowed := file.Package.Types.Scope().Lookup(name) != nil
		if a.structure != nil {
			for _, scope := range file.Package.TypesInfo.Scopes {
				if scope.Pos() <= a.structure.Pos() && scope.End() >= a.structure.End() && scope.Lookup(name) != nil {
					shadowed = true
				}
			}
		}
		if shadowed {
			reject("shadowed-type", "The predeclared type "+name+" is shadowed in this context; generated JSON fields would have the wrong type")
		}
	}
	response.InspectionID = digest([]any{response.ConfigurationDigest, response.StructID, request.JSONText, request.NumericPolicy, response.Fields, response.Declarations, s.Preconditions()})
	a.inspection = response
	fail = false
	return a, nil
}
func importName(file signature.SemanticFile, spec *ast.ImportSpec) string {
	if spec.Name != nil {
		return spec.Name.Name
	}
	if obj, ok := file.Package.TypesInfo.Implicits[spec].(*types.PkgName); ok {
		return obj.Imported().Name()
	}
	path, _ := strconv.Unquote(spec.Path.Value)
	i := strings.LastIndexByte(path, '/')
	return path[i+1:]
}
func (a *analysis) existingNames() (map[string]bool, map[string]bool) {
	names, keys := map[string]bool{}, map[string]bool{}
	for i := 0; i < a.typed.NumFields(); i++ {
		names[a.typed.Field(i).Name()] = true
	}
	// Direct method declarations share the field namespace. Promoted methods are
	// also conservatively protected from being hidden by generated direct fields.
	ast.Inspect(a.file.AST, func(node ast.Node) bool {
		if spec, ok := node.(*ast.TypeSpec); ok && spec.Type == a.structure {
			if obj, ok := a.file.Package.TypesInfo.Defs[spec.Name].(*types.TypeName); ok {
				set := types.NewMethodSet(types.NewPointer(obj.Type()))
				for i := 0; i < set.Len(); i++ {
					names[set.At(i).Obj().Name()] = true
				}
			}
		}
		return true
	})
	var collect func(*types.Struct, map[*types.Struct]bool)
	collect = func(st *types.Struct, seen map[*types.Struct]bool) {
		if seen[st] {
			return
		}
		seen[st] = true
		for i := 0; i < st.NumFields(); i++ {
			field := st.Field(i)
			tag := reflect.StructTag(st.Tag(i)).Get("json")
			if tag == "-" {
				continue
			}
			name := strings.Split(tag, ",")[0]
			if !validTagName(name) {
				name = ""
			}
			typ := types.Unalias(field.Type())
			if p, ok := typ.(*types.Pointer); ok {
				typ = types.Unalias(p.Elem())
			}
			nested, isStruct := typ.Underlying().(*types.Struct)
			if !field.Exported() && (!field.Embedded() || !isStruct) {
				continue
			}
			if field.Embedded() && name == "" && isStruct {
				collect(nested, seen)
				continue
			}
			if name == "" {
				name = field.Name()
			}
			keys[name] = true
		}
	}
	collect(a.typed, map[*types.Struct]bool{})
	return names, keys
}
func hasCustomCodec(file signature.SemanticFile, structure *ast.StructType, typed *types.Struct) bool {
	found := false
	ast.Inspect(file.AST, func(node ast.Node) bool {
		if spec, ok := node.(*ast.TypeSpec); ok && spec.Type == structure {
			if obj, ok := file.Package.TypesInfo.Defs[spec.Name].(*types.TypeName); ok {
				set := types.NewMethodSet(types.NewPointer(obj.Type()))
				found = set.Lookup(nil, "MarshalJSON") != nil || set.Lookup(nil, "UnmarshalJSON") != nil
			}
		}
		return true
	})
	return found
}
