package signature

import (
	"context"
	"go/ast"
	"go/token"

	"golang.org/x/tools/go/packages"
)

// SemanticSnapshot shares the signature planner's immutable overlay, selected
// toolchain, module/work/vendor and filesystem preconditions with other typed
// planners. Callers must not mutate the returned ASTs, packages or source views.
// It never applies edits or executes the workspace's application/test binaries.
type SemanticSnapshot struct{ snapshot *snapshot }

type SemanticFile struct {
	Path, URI, Text, DiskSHA256 string
	Exists                      bool
	Overlay                     *Overlay
	AST                         *ast.File
	Package                     *packages.Package
}

type SemanticAnalysis struct {
	Fset     *token.FileSet
	Files    map[string]SemanticFile
	Packages []*packages.Package
	Errors   []string
	Coverage Coverage
}

func OpenSemanticSnapshot(request Request) (*SemanticSnapshot, error) {
	s, err := newSnapshot(request)
	if err != nil {
		return nil, err
	}
	return &SemanticSnapshot{s}, nil
}

func semanticSource(source *sourceFile) SemanticFile {
	return SemanticFile{Path: source.Path, URI: source.URI, Text: source.Text, DiskSHA256: source.DiskHash,
		Exists: source.Exists, Overlay: source.Overlay}
}

func (s *SemanticSnapshot) Load(ctx context.Context, candidates map[string]string) (SemanticAnalysis, error) {
	a, err := s.snapshot.load(ctx, candidates)
	if err != nil {
		return SemanticAnalysis{}, err
	}
	view := SemanticAnalysis{Fset: a.fset, Files: map[string]SemanticFile{}, Packages: a.packages,
		Errors: append([]string{}, a.errors...), Coverage: a.coverage()}
	for path, file := range a.files {
		value := semanticSource(file.source)
		value.AST, value.Package = file.ast, file.pkg
		view.Files[path] = value
	}
	return view, nil
}

func (s *SemanticSnapshot) ReadOptional(path string) (SemanticFile, error) {
	file, err := s.snapshot.readOptional(path)
	if err != nil {
		return SemanticFile{}, err
	}
	return semanticSource(file), nil
}

func (s *SemanticSnapshot) Editable(path string) bool     { return s.snapshot.editable(path) }
func (s *SemanticSnapshot) Digest() string                { return s.snapshot.digest }
func (s *SemanticSnapshot) Preconditions() []Precondition { return s.snapshot.preconditions() }
func (s *SemanticSnapshot) Directories() []DirectoryCondition {
	return s.snapshot.directoryConditions()
}
func (s *SemanticSnapshot) Unchanged() error { return s.snapshot.unchanged() }
func (s *SemanticSnapshot) Close()           { s.snapshot.close() }

func LocalURI(path string) string                             { return fileURI(path) }
func LocalPath(uri string) (string, error)                    { return uriPath(uri) }
func UTF16Offset(text string, position Position) (int, error) { return byteOffset(text, position) }
func UTF16Range(text string, start, end int) Range {
	return sourceRange(&sourceFile{Text: text}, start, end)
}
