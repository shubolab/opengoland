package jsonstruct

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"math/big"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Object members retain their first textual occurrence, including when shapes
// are merged across array samples. The decoder's map iteration order is unused.
type member struct {
	key     string
	node    *node
	present int
}
type node struct {
	kind     string
	nullable bool
	numbers  []string
	members  []*member
	objects  int
	element  *node
}

func merge(a, b *node) *node {
	if a == nil {
		return b
	}
	if b == nil {
		return a
	}
	nullable := a.nullable || b.nullable
	if a.kind == "" {
		b.nullable = nullable
		return b
	}
	if b.kind == "" {
		a.nullable = nullable
		return a
	}
	if a.kind != b.kind {
		return &node{kind: "mixed", nullable: nullable}
	}
	a.nullable = nullable
	switch a.kind {
	case "number":
		a.numbers = append(a.numbers, b.numbers...)
	case "array":
		a.element = merge(a.element, b.element)
	case "object":
		a.objects += b.objects
		for _, right := range b.members {
			var found *member
			for _, left := range a.members {
				if left.key == right.key {
					found = left
					break
				}
			}
			if found == nil {
				a.members = append(a.members, right)
			} else {
				found.node = merge(found.node, right.node)
				found.present += right.present
			}
		}
	}
	return a
}
func parseSample(text string) (*node, error) {
	if len(text) > 1<<20 {
		return nil, fmt.Errorf("JSON sample exceeds 1 MiB")
	}
	if !utf8.ValidString(text) {
		return nil, fmt.Errorf("JSON sample contains invalid UTF-8")
	}
	if err := validateSurrogates(text); err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.UseNumber()
	count := 0
	var read func(int) (*node, error)
	read = func(depth int) (*node, error) {
		count++
		if count > 20000 || depth > 64 {
			return nil, fmt.Errorf("JSON sample exceeds the supported depth or value count")
		}
		token, err := decoder.Token()
		if err != nil {
			return nil, err
		}
		switch value := token.(type) {
		case nil:
			return &node{nullable: true}, nil
		case bool:
			return &node{kind: "bool"}, nil
		case string:
			return &node{kind: "string"}, nil
		case json.Number:
			return &node{kind: "number", numbers: []string{string(value)}}, nil
		case json.Delim:
			switch value {
			case '{':
				n := &node{kind: "object", objects: 1}
				seen := map[string]bool{}
				for decoder.More() {
					raw, err := decoder.Token()
					if err != nil {
						return nil, err
					}
					key, ok := raw.(string)
					if !ok {
						return nil, fmt.Errorf("JSON object key is not a string")
					}
					if seen[key] {
						return nil, fmt.Errorf("duplicate JSON object key %q", key)
					}
					seen[key] = true
					child, err := read(depth + 1)
					if err != nil {
						return nil, err
					}
					n.members = append(n.members, &member{key: key, node: child, present: 1})
				}
				end, err := decoder.Token()
				if err != nil {
					return nil, err
				}
				if end != json.Delim('}') {
					return nil, fmt.Errorf("unterminated JSON object")
				}
				return n, nil
			case '[':
				n := &node{kind: "array"}
				for decoder.More() {
					child, err := read(depth + 1)
					if err != nil {
						return nil, err
					}
					n.element = merge(n.element, child)
				}
				end, err := decoder.Token()
				if err != nil {
					return nil, err
				}
				if end != json.Delim(']') {
					return nil, fmt.Errorf("unterminated JSON array")
				}
				return n, nil
			}
		}
		return nil, fmt.Errorf("invalid JSON value")
	}
	n, err := read(0)
	if err != nil {
		return nil, fmt.Errorf("invalid JSON sample: %w", err)
	}
	if _, err = decoder.Token(); err != io.EOF {
		if err == nil {
			return nil, fmt.Errorf("JSON sample must contain one value")
		}
		return nil, fmt.Errorf("invalid trailing JSON: %w", err)
	}
	return n, nil
}

// encoding/json replaces unpaired UTF-16 escapes. Reject them rather than
// silently infer a different object key or string value.
func validateSurrogates(text string) error {
	in := false
	for i := 0; i < len(text); i++ {
		if text[i] == '"' {
			in = !in
			continue
		}
		if !in || text[i] != '\\' {
			continue
		}
		i++
		if i >= len(text) {
			break
		}
		if text[i] != 'u' {
			continue
		}
		if i+4 >= len(text) {
			break
		}
		v, e := strconv.ParseUint(text[i+1:i+5], 16, 16)
		if e != nil {
			continue
		}
		i += 4
		if v >= 0xdc00 && v <= 0xdfff {
			return fmt.Errorf("JSON sample contains an unpaired UTF-16 surrogate")
		}
		if v >= 0xd800 && v <= 0xdbff {
			if i+6 >= len(text) || text[i+1:i+3] != "\\u" {
				return fmt.Errorf("JSON sample contains an unpaired UTF-16 surrogate")
			}
			next, e := strconv.ParseUint(text[i+3:i+7], 16, 16)
			if e != nil || next < 0xdc00 || next > 0xdfff {
				return fmt.Errorf("JSON sample contains an unpaired UTF-16 surrogate")
			}
			i += 6
		}
	}
	return nil
}

// This is the public encoding/json tag-name contract in Go 1.24. Empty names,
// commas/options, quotes, backslashes, controls, and non-letter/digit symbols
// cannot identify arbitrary JSON keys through a struct field tag.
func validTagName(name string) bool {
	if name == "" {
		return false
	}
	for _, r := range name {
		if !unicode.IsLetter(r) && !unicode.IsDigit(r) && !strings.ContainsRune("!#$%&()*+-./:;<=>?@[]^_{|}~ ", r) {
			return false
		}
	}
	return true
}

// Common initialisms are an independent naming policy following Go's public
// review guidance (https://go.dev/wiki/CodeReviewComments#initialisms). JSON
// tags, rather than these suggested identifiers, retain the original keys.
var commonInitialisms = map[string]bool{
	"ACL": true, "API": true, "ASCII": true, "CPU": true, "CSS": true,
	"DNS": true, "EOF": true, "GUID": true, "HTML": true, "HTTP": true,
	"HTTPS": true, "ID": true, "IP": true, "JSON": true, "QPS": true,
	"RAM": true, "RPC": true, "SDK": true, "SLA": true, "SMTP": true,
	"SQL": true, "SSH": true, "TCP": true, "TLS": true, "TTL": true,
	"UDP": true, "UI": true, "UID": true, "UUID": true, "URI": true,
	"URL": true, "UTF8": true, "VM": true, "XML": true, "XMPP": true,
	"XSRF": true, "XSS": true,
}

func fieldName(key string) string {
	var out strings.Builder
	var run []rune
	appendWord := func(word []rune) {
		if len(word) == 0 {
			return
		}
		// A leading digit is legal after the final Field prefix. Still recognize
		// a following initialism, so both 123_id and 123id become Field123ID.
		digits := 0
		for digits < len(word) && unicode.IsDigit(word[digits]) {
			digits++
		}
		out.WriteString(string(word[:digits]))
		word = word[digits:]
		if len(word) == 0 {
			return
		}
		upper := strings.ToUpper(string(word))
		if commonInitialisms[upper] {
			out.WriteString(upper)
			return
		}
		out.WriteRune(unicode.ToUpper(word[0]))
		out.WriteString(string(word[1:]))
	}
	flush := func() {
		start := 0
		for i := 1; i < len(run); i++ {
			previous, current := run[i-1], run[i]
			// userId => user + Id; HTTPStatus => HTTP + Status. Uncased
			// Unicode letters can precede a camel-case ASCII word as well.
			wordStart := !unicode.IsUpper(previous) && (unicode.IsLetter(previous) || unicode.IsDigit(previous))
			acronymEnd := unicode.IsUpper(previous) && i+1 < len(run) && unicode.IsLower(run[i+1])
			boundary := unicode.IsUpper(current) && (wordStart || acronymEnd)
			if boundary {
				appendWord(run[start:i])
				start = i
			}
		}
		appendWord(run[start:])
		run = nil
	}
	for _, r := range key {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			run = append(run, r)
		} else {
			flush()
		}
	}
	flush()
	name := out.String()
	first, _ := utf8.DecodeRuneInString(name)
	if !unicode.IsUpper(first) {
		name = "Field" + name
	}
	return name
}
func numberType(values []string, policy string) string {
	if policy == "number" {
		return "json.Number"
	}
	integers := true
	for _, s := range values {
		if strings.ContainsAny(s, ".eE") || s == "-0" {
			integers = false
			break
		}
		if _, err := strconv.ParseInt(s, 10, 64); err != nil {
			integers = false
			break
		}
	}
	if integers {
		return "int64"
	}
	for _, s := range values {
		value, err := strconv.ParseFloat(s, 64)
		if err != nil || math.IsNaN(value) || math.IsInf(value, 0) {
			return "json.Number"
		}
		rendered := strconv.FormatFloat(value, 'g', -1, 64)
		before, ok := decimal(s)
		if !ok {
			return "json.Number"
		}
		after, ok := decimal(rendered)
		if !ok || before.Cmp(after) != 0 {
			return "json.Number"
		}
	}
	return "float64"
}

// Bound exponent expansion before allocating big.Int. Large valid exponents
// remain json.Number, which marshals their original decimal representation.
func decimal(s string) (*big.Rat, bool) {
	mantissa, exponent := s, 0
	if i := strings.IndexAny(s, "eE"); i >= 0 {
		mantissa = s[:i]
		e, err := strconv.Atoi(s[i+1:])
		if err != nil || e > 4096 || e < -4096 {
			return nil, false
		}
		exponent = e
	}
	if i := strings.IndexByte(mantissa, '.'); i >= 0 {
		exponent -= len(mantissa) - i - 1
		mantissa = mantissa[:i] + mantissa[i+1:]
	}
	numerator, ok := new(big.Int).SetString(mantissa, 10)
	if !ok {
		return nil, false
	}
	power := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(abs(exponent))), nil)
	if exponent >= 0 {
		numerator.Mul(numerator, power)
		return new(big.Rat).SetInt(numerator), true
	}
	return new(big.Rat).SetFrac(numerator, power), true
}
func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}
