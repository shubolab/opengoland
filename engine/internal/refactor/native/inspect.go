package native

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/format"
	"go/parser"
	"go/token"
	"go/types"
	"path/filepath"
	"reflect"
	"sort"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

type span struct{ start, end int }
type binding struct {
	wire        Name
	identifiers []*ast.Ident
	object      types.Object
}
type analysis struct {
	snapshot       *signature.SemanticSnapshot
	view           signature.SemanticAnalysis
	target         signature.SemanticFile
	request        Request
	inspection     InspectResponse
	start, end     int
	candidate      string
	parsed         *ast.File
	fset           *token.FileSet
	inserted       []span
	bindings       []binding
	parent, helper *ast.FuncDecl
	inline         *inlineBinding
	all            *allInline
}

func digest(value any) string {
	data, _ := json.Marshal(value)
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
func sourceHash(text string) string {
	sum := sha256.Sum256([]byte(text))
	return hex.EncodeToString(sum[:])
}
func validKind(kind string) bool {
	switch kind {
	case "extract.variable", "extract.variable-all", "extract.constant", "extract.constant-all", "extract.function", "extract.method", "inline", "inline.call", "inline.variable", "inline.call-all", "inline.variable-all":
		return true
	}
	return false
}
func (a *analysis) conflict(code, message string) signature.Conflict {
	return signature.Conflict{URI: a.target.URI, Range: a.request.Selection, Code: code, Message: message}
}
func (a *analysis) reject(code, message string) {
	a.inspection.Conflicts = append(a.inspection.Conflicts, a.conflict(code, message))
}

func Inspect(ctx context.Context, request Request) (InspectResponse, error) {
	a, err := open(ctx, request)
	if err != nil {
		return InspectResponse{}, err
	}
	defer a.snapshot.Close()
	if err = a.snapshot.Unchanged(); err != nil {
		return InspectResponse{}, err
	}
	return a.inspection, nil
}

func open(ctx context.Context, request Request) (*analysis, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	s, err := signature.OpenSemanticSnapshot(request.Request)
	if err != nil {
		return nil, err
	}
	ok := false
	defer func() {
		if !ok {
			s.Close()
		}
	}()
	view, err := s.Load(ctx, nil)
	if err != nil {
		return nil, err
	}
	path, err := signature.LocalPath(request.Target.URI)
	if err != nil {
		return nil, err
	}
	target, found := view.Files[path]
	if !found {
		return nil, fmt.Errorf("the selected Go source is not in the active build context")
	}
	a := &analysis{snapshot: s, view: view, target: target, request: request}
	a.inspection = InspectResponse{ConfigurationDigest: s.Digest(), Kind: request.Kind, Targets: []TargetChoice{}, Names: []Name{}, Coverage: view.Coverage, Conflicts: []signature.Conflict{}}
	a.inspection.SourceDigest = digest(struct {
		Configuration string
		Preconditions []signature.Precondition
		Directories   []signature.DirectoryCondition
	}{s.Digest(), s.Preconditions(), s.Directories()})
	identity := request
	identity.OperationID = ""
	a.inspection.InspectionID = digest(struct {
		Request               Request
		Configuration, Source string
	}{identity, s.Digest(), target.Text})[:24]
	if request.ExpectedSourceDigest != "" && request.ExpectedSourceDigest != a.inspection.SourceDigest {
		a.reject("stale-source-context", "An analyzed Go source or metadata input changed after target inspection; invoke the refactoring again")
	}
	if !validKind(request.Kind) {
		a.reject("kind", "Choose Extract Variable, Constant, Function, Method, or Inline")
		ok = true
		return a, nil
	}
	if !s.Editable(path) {
		a.reject("read-only", "The selected declaration is outside the editable Go workspace")
	}
	if len(view.Errors) > 0 {
		a.reject("before-errors", "The active workspace must type-check before this refactoring:\n"+strings.Join(view.Errors, "\n"))
	}
	a.start, err = signature.UTF16Offset(target.Text, request.Selection.Start)
	if err != nil {
		return nil, err
	}
	a.end, err = signature.UTF16Offset(target.Text, request.Selection.End)
	if err != nil {
		return nil, err
	}
	if a.end < a.start {
		return nil, fmt.Errorf("the Go selection is reversed")
	}
	if strings.HasPrefix(request.Kind, "inline.") && strings.HasSuffix(request.Kind, "-all") {
		if len(request.CandidateEdit.DocumentChanges) != 0 {
			a.reject("inline-all-candidate", "Workspace inline-all uses its own typed plan, without an unbound language-server candidate")
		}
		a.inspectAllInline()
		a.bindInspection()
		ok = true
		return a, nil
	}
	if len(request.CandidateEdit.DocumentChanges) == 0 {
		a.collectTargets()
		a.bindInspection()
		ok = true
		return a, nil
	}
	if request.CandidateSourceSHA256 != sourceHash(target.Text) {
		a.reject("candidate-source", "The language-server candidate does not match the captured source. Invoke the refactoring again.")
	}
	a.candidate, a.inserted, err = a.applyCandidate()
	if err != nil {
		a.reject("candidate-edit", err.Error())
		ok = true
		return a, nil
	}
	a.fset = token.NewFileSet()
	a.parsed, err = parser.ParseFile(a.fset, target.Path, a.candidate, parser.ParseComments|parser.AllErrors)
	if err != nil {
		a.reject("candidate-syntax", "The language-server candidate is not valid Go: "+err.Error())
		ok = true
		return a, nil
	}
	switch request.Kind {
	case "extract.function", "extract.method":
		a.findFunctionBinding()
	case "extract.variable", "extract.variable-all", "extract.constant", "extract.constant-all":
		if err = a.findLocalBindings(ctx); err != nil {
			return nil, err
		}
	case "inline.call", "inline.variable":
		a.inspection.Occurrences = 1
		a.inspectInline()
	}
	for _, b := range a.bindings {
		a.inspection.Names = append(a.inspection.Names, b.wire)
	}
	a.bindInspection()
	ok = true
	return a, nil
}

func (a *analysis) bindInspection() {
	identity := a.request
	identity.OperationID = ""
	a.inspection.InspectionID = digest(struct {
		Request       Request
		Configuration string
		Preconditions []signature.Precondition
		Directories   []signature.DirectoryCondition
	}{identity, a.snapshot.Digest(), a.snapshot.Preconditions(), a.snapshot.Directories()})[:24]
}

type candidateEdit struct {
	start, end int
	text       string
}

func (a *analysis) applyCandidate() (string, []span, error) {
	var edits []candidateEdit
	seenDocument := false
	for _, document := range a.request.CandidateEdit.DocumentChanges {
		if seenDocument {
			return "", nil, fmt.Errorf("multiple candidate document records need an intermediate version; resolve a fresh single-document candidate")
		}
		seenDocument = true
		path, err := signature.LocalPath(document.TextDocument.URI)
		if err != nil || filepath.Clean(path) != filepath.Clean(a.target.Path) {
			return "", nil, fmt.Errorf("this refactoring candidate must edit only the selected existing Go file")
		}
		if document.TextDocument.Version != nil && a.target.Overlay != nil && a.target.Overlay.LSP != nil && *document.TextDocument.Version != a.target.Overlay.LSP.Version {
			return "", nil, fmt.Errorf("the candidate document version is stale")
		}
		if document.TextDocument.Version != nil {
			version := *document.TextDocument.Version
			if version < 0 || (a.target.Overlay == nil || a.target.Overlay.LSP == nil) && version != 0 {
				return "", nil, fmt.Errorf("the versioned candidate has no matching captured language-server document")
			}
		}
		for _, edit := range document.Edits {
			start, err := signature.UTF16Offset(a.target.Text, edit.Range.Start)
			if err != nil {
				return "", nil, err
			}
			end, err := signature.UTF16Offset(a.target.Text, edit.Range.End)
			if err != nil {
				return "", nil, err
			}
			if end < start {
				return "", nil, fmt.Errorf("a candidate edit has a reversed range")
			}
			edits = append(edits, candidateEdit{start, end, edit.NewText})
		}
	}
	if len(edits) == 0 {
		return "", nil, fmt.Errorf("the server supplied no text edits")
	}
	sort.SliceStable(edits, func(i, j int) bool { return edits[i].start < edits[j].start })
	var result strings.Builder
	var inserted []span
	cursor := 0
	for i, edit := range edits {
		if edit.start < cursor || i > 0 && edit.start == edits[i-1].start {
			return "", nil, fmt.Errorf("overlapping or ambiguous candidate edits")
		}
		result.WriteString(a.target.Text[cursor:edit.start])
		start := result.Len()
		result.WriteString(edit.text)
		if edit.start == edit.end {
			inserted = append(inserted, span{start, result.Len()})
		}
		cursor = edit.end
	}
	result.WriteString(a.target.Text[cursor:])
	if result.String() == a.target.Text {
		return "", nil, fmt.Errorf("the refactoring candidate makes no change")
	}
	return result.String(), inserted, nil
}

func (a *analysis) collectTargets() {
	caret, err := signature.UTF16Offset(a.target.Text, a.request.Target.Position)
	if err != nil {
		a.reject("position", err.Error())
		return
	}
	if strings.HasPrefix(a.request.Kind, "inline") {
		a.collectInlineTargets(caret)
		return
	}
	seen := map[signature.Range]bool{}
	ast.Inspect(a.target.AST, func(node ast.Node) bool {
		if node == nil {
			return true
		}
		start, end := a.view.Fset.PositionFor(node.Pos(), false).Offset, a.view.Fset.PositionFor(node.End(), false).Offset
		if caret < start || caret > end {
			return false
		}
		accepted := false
		if strings.HasPrefix(a.request.Kind, "extract.variable") || strings.HasPrefix(a.request.Kind, "extract.constant") {
			if expr, yes := node.(ast.Expr); yes {
				tv, exists := a.target.Package.TypesInfo.Types[expr]
				accepted = exists && tv.IsValue() && tv.Type != nil
				if strings.HasPrefix(a.request.Kind, "extract.constant") {
					accepted = accepted && tv.Value != nil
				}
			}
		} else if a.request.Kind == "extract.function" || a.request.Kind == "extract.method" {
			_, accepted = node.(ast.Stmt)
			if _, block := node.(*ast.BlockStmt); block {
				accepted = false
			}
		} else if id, yes := node.(*ast.Ident); yes {
			obj := a.target.Package.TypesInfo.Uses[id]
			switch obj.(type) {
			case *types.Var:
				accepted = a.request.Kind == "inline.variable"
			case *types.Func:
				accepted = a.request.Kind == "inline.call"
			}
		}
		if accepted {
			rangeValue := signature.UTF16Range(a.target.Text, start, end)
			if !seen[rangeValue] {
				text := a.target.Text[start:end]
				if len([]rune(text)) > 180 {
					text = string([]rune(text)[:180]) + "…"
				}
				a.inspection.Targets = append(a.inspection.Targets, TargetChoice{TargetID: digest(rangeValue)[:16], URI: a.target.URI, Range: rangeValue, Text: text, Kind: a.request.Kind})
				seen[rangeValue] = true
			}
		}
		return true
	})
	sort.SliceStable(a.inspection.Targets, func(i, j int) bool {
		x, _ := signature.UTF16Offset(a.target.Text, a.inspection.Targets[i].Range.Start)
		y, _ := signature.UTF16Offset(a.target.Text, a.inspection.Targets[i].Range.End)
		u, _ := signature.UTF16Offset(a.target.Text, a.inspection.Targets[j].Range.Start)
		v, _ := signature.UTF16Offset(a.target.Text, a.inspection.Targets[j].Range.End)
		return y-x < v-u
	})
}

func rendered(node ast.Node) string {
	if node == nil || reflect.ValueOf(node).IsNil() {
		return ""
	}
	var out bytes.Buffer
	_ = format.Node(&out, token.NewFileSet(), node)
	return out.String()
}
