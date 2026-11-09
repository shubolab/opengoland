package typesearch

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
)

func within(path, root string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func digest(value any) string {
	raw, _ := json.Marshal(value)
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func Catalog(ctx context.Context, request Request) (Response, error) {
	result := Response{Types: []Type{}, Issues: []Issue{}}
	if err := ctx.Err(); err != nil {
		return result, err
	}
	// Metadata uses real paths. Normalize existing-parent aliases as well as
	// existing files, so an unsaved file below a symlink is the same overlay.
	request.Overlays = append([]signature.Overlay{}, request.Overlays...)
	for i := range request.Overlays {
		path, err := signature.LocalPath(request.Overlays[i].URI)
		if err != nil {
			return result, err
		}
		resolved, err := canonical(path)
		if err != nil {
			return result, err
		}
		request.Overlays[i].URI = signature.LocalURI(resolved)
	}
	snapshot, err := signature.OpenCatalogSnapshot(request.Request)
	if err != nil {
		return result, err
	}
	defer snapshot.Close()
	metadata, err := snapshot.LoadCatalogMetadata(ctx, request.IncludeDependencies, request.IncludeSDK)
	if err != nil {
		return result, err
	}
	result.ConfigurationDigest = snapshot.Digest()
	for _, problem := range metadata.Issues {
		result.Issues = append(result.Issues, Issue{Code: "metadata", Message: problem})
	}
	files, packages, declarations := map[string]bool{}, map[string]bool{}, map[string]bool{}
	for _, pkg := range metadata.Packages {
		if err = ctx.Err(); err != nil {
			return result, err
		}
		// GoFiles names original active sources, including CGO input. CompiledGoFiles
		// may instead name compiler-cache artifacts which are never declarations.
		for _, candidate := range pkg.GoFiles {
			path := snapshot.CatalogSourcePath(candidate)
			if !strings.HasSuffix(path, ".go") {
				continue
			}
			path, err = canonical(path)
			if err != nil {
				return result, err
			}
			origin := "dependency"
			if snapshot.Editable(path) {
				origin = "project"
			} else if metadata.GOROOT != "" && within(path, filepath.Join(metadata.GOROOT, "src")) {
				origin = "sdk"
			}
			if origin == "dependency" && !request.IncludeDependencies || origin == "sdk" && !request.IncludeSDK {
				continue
			}
			// Synthetic test variants share source files. Keep one declaration row,
			// without collapsing equal names from distinct packages/files.
			if files[path] {
				continue
			}
			files[path], packages[pkg.PkgPath] = true, true
			if err = snapshot.CaptureCatalogDirectory(path); err != nil {
				return result, err
			}
			source, readErr := snapshot.ReadOptional(path)
			if readErr != nil {
				return result, readErr
			}
			if !source.Exists && source.Overlay == nil {
				return result, fmt.Errorf("Go type source disappeared: %s", path)
			}
			set := token.NewFileSet()
			file, parseErr := parser.ParseFile(set, path, source.Text, parser.AllErrors|parser.SkipObjectResolution)
			if parseErr != nil {
				result.Issues = append(result.Issues, Issue{Code: "syntax", Message: parseErr.Error(), URI: source.URI})
			}
			if file == nil {
				continue
			}
			ast.Inspect(file, func(node ast.Node) bool {
				if ctx.Err() != nil {
					return false
				}
				declaration, ok := node.(*ast.TypeSpec)
				if !ok || declaration.Name == nil || declaration.Name.Name == "_" || !declaration.Name.Pos().IsValid() || !declaration.Name.End().IsValid() {
					return true
				}
				begin, end := set.Position(declaration.Pos()).Offset, set.Position(declaration.End()).Offset
				nameStart, nameEnd := set.Position(declaration.Name.Pos()).Offset, set.Position(declaration.Name.End()).Offset
				if parseErr != nil && (end < nameEnd || end > len(source.Text)) {
					begin, end = nameStart, nameEnd
				}
				if begin < 0 || end > len(source.Text) || begin > nameStart || nameStart < 0 || nameEnd > len(source.Text) || nameEnd > end || source.Text[nameStart:nameEnd] != declaration.Name.Name {
					return true
				}
				key := fmt.Sprintf("%s:%d:%s", source.URI, nameStart, declaration.Name.Name)
				if declarations[key] {
					return true
				}
				declarations[key] = true
				kind := "type"
				if declaration.Assign.IsValid() {
					kind = "alias"
				} else {
					switch declaration.Type.(type) {
					case *ast.StructType:
						kind = "struct"
					case *ast.InterfaceType:
						kind = "interface"
					case *ast.FuncType:
						kind = "function"
					}
				}
				result.Types = append(result.Types, Type{Name: declaration.Name.Name, PackagePath: pkg.PkgPath, PackageName: file.Name.Name, URI: source.URI,
					Range: signature.UTF16Range(source.Text, begin, end), SelectionRange: signature.UTF16Range(source.Text, nameStart, nameEnd), Kind: kind, Origin: origin})
				return true
			})
		}
	}
	if err = ctx.Err(); err != nil {
		return result, err
	}
	if err = snapshot.Unchanged(); err != nil {
		return result, err
	}
	sort.Slice(result.Types, func(i, j int) bool {
		a, b := result.Types[i], result.Types[j]
		if a.Name != b.Name {
			return a.Name < b.Name
		}
		if a.PackagePath != b.PackagePath {
			return a.PackagePath < b.PackagePath
		}
		if a.URI != b.URI {
			return a.URI < b.URI
		}
		if a.SelectionRange.Start.Line != b.SelectionRange.Start.Line {
			return a.SelectionRange.Start.Line < b.SelectionRange.Start.Line
		}
		return a.SelectionRange.Start.Character < b.SelectionRange.Start.Character
	})
	result.SourceGuards, result.Directories = snapshot.Preconditions(), snapshot.Directories()
	result.Coverage = Coverage{Modules: metadata.Modules, Packages: len(packages), Files: len(files), Tests: true,
		IncludeDependencies: request.IncludeDependencies, IncludeSDK: request.IncludeSDK, BuildContext: metadata.Context, Complete: len(result.Issues) == 0}
	result.CatalogID = digest(struct {
		Configuration string
		Dependencies  bool
		SDK           bool
		Types         []Type
		Sources       []signature.Precondition
		Directories   []signature.DirectoryCondition
	}{result.ConfigurationDigest, request.IncludeDependencies, request.IncludeSDK, result.Types, result.SourceGuards, result.Directories})
	return result, nil
}

func canonical(path string) (string, error) {
	resolved, err := filepath.EvalSymlinks(path)
	if err == nil {
		return resolved, nil
	}
	if !os.IsNotExist(err) {
		return "", err
	}
	parent := filepath.Dir(path)
	if parent == path {
		return "", err
	}
	resolved, err = canonical(parent)
	if err != nil {
		return "", err
	}
	return filepath.Join(resolved, filepath.Base(path)), nil
}
