package tags

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/types"
	"strconv"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

type field struct {
	wire     Field
	ast      *ast.Field
	position signature.Range
	tag      string
	pairs    []pair
	literal  string
}
type analysis struct {
	snapshot   *signature.SemanticSnapshot
	view       signature.SemanticAnalysis
	file       signature.SemanticFile
	structure  *ast.StructType
	typed      *types.Struct
	ordinal    int
	fields     []field
	inspection InspectResponse
}

func digest(v any) string {
	data, _ := json.Marshal(v)
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:])
}
func structs(file *ast.File) []*ast.StructType {
	out := []*ast.StructType{}
	ast.Inspect(file, func(node ast.Node) bool {
		if s, ok := node.(*ast.StructType); ok {
			out = append(out, s)
		}
		return true
	})
	return out
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
func open(ctx context.Context, request Request) (*analysis, error) {
	if e := ctx.Err(); e != nil {
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
		return nil, fmt.Errorf("select an editable Go struct or one of its fields in the active build context")
	}
	offset, e := signature.UTF16Offset(file.Text, request.Target.Position)
	if e != nil {
		return nil, e
	}
	all := structs(file.AST)
	var selected *ast.StructType
	ordinal := -1
	smallest := len(file.Text) + 1
	name := "anonymous struct"
	choose := func(node *ast.StructType, start, end int, label string) {
		if offset >= start && offset <= end && end-start < smallest {
			selected = node
			smallest = end - start
			name = label
		}
	}
	for _, node := range all {
		choose(node, view.Fset.PositionFor(node.Pos(), false).Offset, view.Fset.PositionFor(node.End(), false).Offset, "anonymous struct")
	}
	ast.Inspect(file.AST, func(node ast.Node) bool {
		spec, ok := node.(*ast.TypeSpec)
		if !ok {
			return true
		}
		if structure, ok := spec.Type.(*ast.StructType); ok {
			start, end := view.Fset.PositionFor(spec.Name.Pos(), false).Offset, view.Fset.PositionFor(spec.End(), false).Offset
			if offset >= start && offset <= end && (selected == structure || selected == nil) {
				selected = structure
				smallest = end - start
				name = spec.Name.Name
			}
		}
		return true
	})
	if selected == nil {
		return nil, fmt.Errorf("place the caret on a struct name, field or tag")
	}
	for i, node := range all {
		if node == selected {
			ordinal = i
			break
		}
	}
	typeValue, ok := file.Package.TypesInfo.Types[selected]
	if !ok || typeValue.Type == nil {
		return nil, fmt.Errorf("the selected struct has no resolved Go type; fix its syntax/types first")
	}
	typed, ok := typeValue.Type.Underlying().(*types.Struct)
	if !ok {
		return nil, fmt.Errorf("the target is not a Go struct")
	}
	start, end := view.Fset.PositionFor(selected.Pos(), false).Offset, view.Fset.PositionFor(selected.End(), false).Offset
	a := &analysis{snapshot: s, view: view, file: file, structure: selected, typed: typed, ordinal: ordinal, fields: []field{}}
	response := InspectResponse{ConfigurationDigest: s.Digest(), StructID: digest([]any{file.URI, ordinal, start, end, file.Text}), Name: name, URI: file.URI, Range: signature.UTF16Range(file.Text, start, end), Fields: []Field{}, Coverage: view.Coverage, Conflicts: []signature.Conflict{}}
	if ast.IsGenerated(file.AST) {
		response.Conflicts = append(response.Conflicts, signature.Conflict{URI: file.URI, Range: response.Range, Code: "generated-file", Message: "Choose a struct in a source file which is not generated"})
	}
	index := 0
	for groupIndex, node := range selected.Fields.List {
		count := len(node.Names)
		if count == 0 {
			count = 1
		}
		if index+count > typed.NumFields() {
			return nil, fmt.Errorf("the struct field/type mapping is incomplete")
		}
		names := []string{}
		for i := 0; i < count; i++ {
			names = append(names, typed.Field(index+i).Name())
		}
		literal, tag := "", ""
		var entries []pair
		var tagError error
		if node.Tag != nil {
			literal = node.Tag.Value
			tag, tagError = strconv.Unquote(literal)
			if tagError == nil {
				entries, tagError = parseTag(tag)
			}
		} else {
			entries = []pair{}
		}
		groupID := digest([]any{response.StructID, groupIndex})
		for i := 0; i < count; i++ {
			variable := typed.Field(index)
			fieldStart, fieldEnd := view.Fset.PositionFor(node.Pos(), false).Offset, view.Fset.PositionFor(node.End(), false).Offset
			if len(node.Names) > 0 {
				fieldStart = view.Fset.PositionFor(node.Names[i].Pos(), false).Offset
				fieldEnd = view.Fset.PositionFor(node.Names[i].End(), false).Offset
			}
			wire := Field{FieldID: digest([]any{response.StructID, index, variable.Name()}), Name: variable.Name(), Names: []string{variable.Name()}, GroupID: groupID, GroupNames: append([]string{}, names...), Type: types.TypeString(variable.Type(), func(p *types.Package) string { return p.Name() }), Tag: tag, Entries: []Entry{}, Exported: variable.Exported(), Embedded: variable.Embedded(), Editable: variable.Name() != "_"}
			for _, entry := range entries {
				wire.Entries = append(wire.Entries, entry.Entry)
			}
			if tagError != nil {
				wire.Editable = false
				wire.Reason = tagError.Error()
			} else if variable.Name() == "_" {
				wire.Reason = "Blank fields have no generated serialization name"
			} else if len(names) > 1 {
				wire.Reason = "Shared declaration: " + strings.Join(names, ", ") + "; select the whole group and produce the same tag, or split it first"
			}
			a.fields = append(a.fields, field{wire: wire, ast: node, position: signature.UTF16Range(file.Text, fieldStart, fieldEnd), tag: tag, pairs: entries, literal: literal})
			response.Fields = append(response.Fields, wire)
			index++
		}
	}
	if index != typed.NumFields() {
		return nil, fmt.Errorf("the struct field/type mapping is incomplete")
	}
	response.InspectionID = digest([]any{response.ConfigurationDigest, response.StructID, response.Fields})
	a.inspection = response
	fail = false
	return a, nil
}
