package jsonstruct

import (
	"fmt"
	"strconv"
	"strings"
)

type inferredField struct {
	wire         Field
	tag          string
	declarations []Declaration
}
type builder struct {
	policy, alias string
	used          map[string]bool
	builtins      map[string]bool
	declarations  []Declaration
	warnings      []Warning
	fields        []inferredField
}

func (b *builder) warning(path, code, message string) {
	b.warnings = append(b.warnings, Warning{Path: path, Code: code, Message: message})
}
func (b *builder) json(name string) string { return b.alias + "." + name }
func (b *builder) fresh(name, path string) string {
	base := name
	for i := 2; b.used[name]; i++ {
		name = fmt.Sprintf("%s%d", base, i)
	}
	b.used[name] = true
	if base != name {
		b.warning(path, "nested-name", fmt.Sprintf("Nested type %s was renamed to %s to avoid an existing declaration", base, name))
	}
	return name
}
func (b *builder) raw(path, code, message string) string {
	b.warning(path, code, message)
	return b.json("RawMessage")
}
func (b *builder) value(n *node, path, name string, root bool) string {
	if n == nil {
		return b.raw(path, "empty-array", "No array element was sampled; RawMessage retains future element values without guessing")
	}
	var text string
	switch n.kind {
	case "":
		return b.raw(path, "null-only", "Only null was sampled; RawMessage preserves null without inventing a schema")
	case "mixed":
		return b.raw(path, "heterogeneous", "Incompatible sampled types use RawMessage to preserve their JSON values")
	case "bool", "string":
		text = n.kind
		b.builtins[text] = true
	case "number":
		text = numberType(n.numbers, b.policy)
		if text == "json.Number" {
			text = b.json("Number")
			if b.policy == "auto" {
				b.warning(path, "exact-number", "The sampled number cannot safely round-trip through int64/float64; json.Number preserves it")
			}
		} else {
			b.builtins[text] = true
		}
	case "array":
		text = "[]" + b.value(n.element, path+"[]", name+"Item", false)
	case "object":
		unsafe := false
		for _, m := range n.members {
			if !validTagName(m.key) {
				unsafe = true
				break
			}
		}
		if unsafe {
			b.builtins["string"] = true
			return b.rawObject(path)
		}
		typeName := name
		if !root {
			typeName = b.fresh(name, path)
		}
		fields := b.objectFields(n, path, typeName, root)
		lines := []string{}
		for _, f := range fields {
			lines = append(lines, f.wire.Name+" "+f.wire.Type+" "+f.tag)
		}
		text = "struct {\n" + strings.Join(lines, "\n") + "\n}"
		b.declarations = append(b.declarations, Declaration{Name: typeName, Kind: "struct", Type: text})
		text = typeName
	default:
		panic("unknown inference kind")
	}
	if n.nullable {
		text = "*" + text
	}
	return text
}
func (b *builder) rawObject(path string) string {
	b.warning(path, "unrepresentable-key", "This object contains a key which encoding/json cannot represent in a struct tag; map[string]json.RawMessage preserves every original key")
	return "map[string]" + b.json("RawMessage")
}
func (b *builder) objectFields(n *node, path, name string, top bool) []inferredField {
	used := map[string]bool{}
	out := []inferredField{}
	for _, m := range n.members {
		base := fieldName(m.key)
		goName := base
		for i := 2; used[goName]; i++ {
			goName = fmt.Sprintf("%s%d", base, i)
		}
		used[goName] = true
		fieldPath := path + "[" + strconv.Quote(m.key) + "]"
		if goName != base {
			b.warning(fieldPath, "field-name", "Distinct JSON keys require a suffixed Go field name; the tag retains the original key")
		}
		optional := m.present < n.objects
		before := len(b.declarations)
		wire := Field{JSONKey: m.key, Name: goName, Optional: optional, Nullable: m.node.nullable, Editable: validTagName(m.key)}
		if !wire.Editable {
			wire.Reason = "encoding/json cannot represent this key in a struct tag"
		}
		if optional && m.node.nullable {
			wire.Type = b.raw(fieldPath, "missing-null", "RawMessage with omitempty distinguishes an absent member from an explicit null")
		} else {
			wire.Type = b.value(m.node, fieldPath, name+goName, false)
			if optional && !strings.HasPrefix(wire.Type, "*") && wire.Type != b.json("RawMessage") {
				wire.Type = "*" + wire.Type
			}
		}
		key := m.key
		if optional {
			key += ",omitempty"
		} else if key == "-" {
			key = "-,"
		}
		tag := "json:" + strconv.Quote(key)
		literal := "`" + tag + "`"
		if strings.ContainsRune(tag, '`') {
			literal = strconv.Quote(tag)
		}
		field := inferredField{wire: wire, tag: literal, declarations: append([]Declaration{}, b.declarations[before:]...)}
		out = append(out, field)
	}
	if top {
		b.fields = out
	}
	return out
}
func sourceDeclarations(declarations []Declaration) string {
	var out strings.Builder
	for _, d := range declarations {
		out.WriteString("type " + d.Name)
		if d.Kind == "alias" {
			out.WriteString(" = ")
		} else {
			out.WriteString(" ")
		}
		out.WriteString(d.Type + "\n\n")
	}
	return out.String()
}
