package tags

import (
	"fmt"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

type pair struct {
	Entry
	start, end int
}

func validKey(key string) bool {
	if key == "" || !utf8.ValidString(key) {
		return false
	}
	for _, r := range key {
		if r <= ' ' || r == ':' || r == '"' || r == 127 {
			return false
		}
	}
	return true
}

// Parse the reflect.StructTag key:"quoted value" convention while retaining
// exact byte spans. Other key/value pairs and their spacing are not rebuilt.
func parseTag(tag string) ([]pair, error) {
	if !utf8.ValidString(tag) {
		return nil, fmt.Errorf("the tag contains non-UTF-8 bytes")
	}
	out := []pair{}
	index := 0
	for index < len(tag) {
		for index < len(tag) && tag[index] == ' ' {
			index++
		}
		if index == len(tag) {
			break
		}
		start := index
		for index < len(tag) && tag[index] > ' ' && tag[index] != ':' && tag[index] != '"' && tag[index] != 127 {
			index++
		}
		if index == start || index+1 >= len(tag) || tag[index] != ':' || tag[index+1] != '"' {
			return nil, fmt.Errorf("the existing tag does not follow key:\"value\" syntax")
		}
		key := tag[start:index]
		quote := index + 1
		index = quote + 1
		for index < len(tag) {
			if tag[index] == '\\' {
				index += 2
				continue
			}
			if tag[index] == '"' {
				break
			}
			index++
		}
		if index >= len(tag) {
			return nil, fmt.Errorf("the existing tag contains an unterminated quoted value")
		}
		index++
		value, e := strconv.Unquote(tag[quote:index])
		if e != nil {
			return nil, fmt.Errorf("the existing tag contains an invalid quoted value: %w", e)
		}
		out = append(out, pair{Entry: Entry{Key: key, Value: value}, start: start, end: index})
	}
	return out, nil
}

func styleName(name, style string) string {
	if style == "original" {
		return name
	}
	if style == "lower" {
		return strings.ToLower(name)
	}
	runes := []rune(name)
	words := []string{}
	start := 0
	for i, r := range runes {
		if r == '_' || r == '-' || unicode.IsSpace(r) {
			if start < i {
				words = append(words, string(runes[start:i]))
			}
			start = i + 1
			continue
		}
		if i <= start {
			continue
		}
		previous := runes[i-1]
		nextLower := i+1 < len(runes) && unicode.IsLower(runes[i+1])
		boundary := unicode.IsUpper(r) && ((unicode.IsLetter(previous) && !unicode.IsUpper(previous)) || unicode.IsDigit(previous) || unicode.IsUpper(previous) && nextLower)
		if boundary {
			words = append(words, string(runes[start:i]))
			start = i
		}
	}
	if start < len(runes) {
		words = append(words, string(runes[start:]))
	}
	if len(words) == 0 {
		return ""
	}
	for i := range words {
		words[i] = strings.ToLower(words[i])
	}
	if style == "snake" {
		return strings.Join(words, "_")
	}
	for i := 1; i < len(words); i++ {
		r := []rune(words[i])
		if len(r) > 0 {
			r[0] = unicode.ToUpper(r[0])
			words[i] = string(r)
		}
	}
	return strings.Join(words, "")
}

func changedTag(tag string, pairs []pair, field Field, request PlanRequest) (string, error) {
	var found *pair
	for i := range pairs {
		if pairs[i].Key == request.Key {
			if found != nil {
				return "", fmt.Errorf("field %s contains duplicate %s keys; resolve that ambiguity first", field.Name, request.Key)
			}
			found = &pairs[i]
		}
	}
	if request.Mode == "add-missing" && found != nil {
		return tag, nil
	}
	if request.Mode == "remove" {
		if found == nil {
			return tag, nil
		}
		after := tag[:found.start] + tag[found.end:]
		if strings.TrimSpace(after) == "" {
			return "", nil
		}
		return after, nil
	}
	value, override := request.ValueOverrides[field.FieldID]
	if !override {
		if found != nil && found.Value == "-" {
			return tag, nil
		}
		value = styleName(field.Name, request.Style)
		if found != nil && !request.ReplaceOptions {
			if comma := strings.IndexByte(found.Value, ','); comma >= 0 {
				value += found.Value[comma:]
			}
		} else if len(request.Options) > 0 {
			value += "," + strings.Join(request.Options, ",")
		}
	}
	replacement := request.Key + ":" + strconv.Quote(value)
	if found != nil {
		return tag[:found.start] + replacement + tag[found.end:], nil
	}
	if tag == "" {
		return replacement, nil
	}
	separator := " "
	if strings.HasSuffix(tag, " ") {
		separator = ""
	}
	return tag + separator + replacement, nil
}
func tagLiteral(tag, old string) string {
	if tag == "" {
		return ""
	}
	if strings.HasPrefix(old, "\"") || strings.ContainsRune(tag, '`') {
		return strconv.Quote(tag)
	}
	return "`" + tag + "`"
}
