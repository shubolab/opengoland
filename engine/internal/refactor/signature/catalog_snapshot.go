package signature

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"

	"golang.org/x/tools/go/packages"
)

// CatalogMetadata uses the same immutable workspace/overlay/toolchain ownership
// as typed refactorings, but does not type-check packages just to enumerate names.
type CatalogMetadata struct {
	Packages []*packages.Package
	Modules  []string
	GOROOT   string
	Issues   []string
	Context  string
	Roots    []CatalogRoot
}

// OpenCatalogSnapshot also admits an explicitly selected GOPATH build context.
// Type search does not require writable source or a refactoring target.
func OpenCatalogSnapshot(request Request) (*SemanticSnapshot, error) {
	if request.Root == "" {
		return nil, errors.New("Go type search requires a project root")
	}
	mode, specified := request.Environment["GO111MODULE"]
	if !specified {
		mode = os.Getenv("GO111MODULE")
	}
	if mode != "off" {
		value, err := OpenSemanticSnapshot(request)
		if err != nil && err.Error() == "open a Go module or a go.work workspace before changing a signature" {
			return nil, errors.New("open a Go module or go.work workspace before searching Go types in module mode")
		}
		return value, err
	}
	root, err := filepath.Abs(request.Root)
	if err != nil {
		return nil, err
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return nil, err
	}
	if err = sameExecutable(request.GoExecutable); err != nil {
		return nil, err
	}
	s := &snapshot{request: request, root: root, modules: []string{root}, files: map[string]*sourceFile{}, overlays: map[string]Overlay{}}
	for _, overlay := range request.Overlays {
		path, pathErr := uriPath(overlay.URI)
		if pathErr != nil {
			return nil, pathErr
		}
		if _, duplicate := s.overlays[path]; duplicate {
			return nil, fmt.Errorf("duplicate overlay: %s", overlay.URI)
		}
		if _, err = normalized([]byte(overlay.Text)); err != nil {
			return nil, err
		}
		if strings.HasPrefix(overlay.Text, "\ufeff") || strings.Contains(overlay.Text, "\r") {
			return nil, errors.New("editor overlays must use LF text without a UTF-8 BOM")
		}
		s.overlays[path] = overlay
	}
	environment := map[string]string{}
	for _, pair := range os.Environ() {
		if k, v, ok := strings.Cut(pair, "="); ok {
			environment[k] = v
		}
	}
	for k, v := range request.Environment {
		if k == "" || strings.ContainsAny(k, "=\x00") || strings.ContainsRune(v, '\x00') {
			return nil, errors.New("invalid Go type search environment")
		}
		environment[k] = v
	}
	environment["GOTOOLCHAIN"], environment["GOTELEMETRY"], environment["GOPACKAGESDRIVER"], environment["GOWORK"] = "local", "off", "off", "off"
	keys := make([]string, 0, len(environment))
	for k := range environment {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		s.env = append(s.env, k+"="+environment[k])
	}
	if err = s.captureDirectories(); err != nil {
		return nil, err
	}
	s.primaryDirectories = map[string]bool{}
	for dir := range s.directories {
		s.primaryDirectories[dir] = true
	}
	return &SemanticSnapshot{s}, nil
}

type CatalogRoot struct {
	Path, Origin string
}

func (s *SemanticSnapshot) catalogGo(ctx context.Context, arguments ...string) ([]byte, error) {
	if len(arguments) > 0 && arguments[0] == "list" && len(s.snapshot.overlays) > 0 {
		// The module build list must see the same dirty go.mod/go.sum inputs
		// as packages.Load, including unused newly selected dependencies.
		dir, err := os.MkdirTemp("", "opengoland-type-catalog-overlay-")
		if err != nil {
			return nil, err
		}
		defer os.RemoveAll(dir)
		replacements := map[string]string{}
		for path, overlay := range s.snapshot.overlays {
			file := filepath.Join(dir, fmt.Sprint(len(replacements)))
			if err = os.WriteFile(file, []byte(overlay.Text), 0600); err != nil {
				return nil, err
			}
			replacements[path] = file
		}
		data, err := json.Marshal(struct{ Replace map[string]string }{replacements})
		if err != nil {
			return nil, err
		}
		path := filepath.Join(dir, "overlay.json")
		if err = os.WriteFile(path, data, 0600); err != nil {
			return nil, err
		}
		arguments = append([]string{arguments[0], "-overlay=" + path}, arguments[1:]...)
	}
	command := s.snapshot.request.GoExecutable
	if command == "" {
		command = "go"
	}
	cmd := exec.CommandContext(ctx, command, arguments...)
	cmd.Dir, cmd.Env = s.snapshot.root, s.snapshot.env
	output, err := cmd.Output()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			return nil, fmt.Errorf("go %s: %s", strings.Join(arguments, " "), strings.TrimSpace(string(exit.Stderr)))
		}
		return nil, err
	}
	return output, nil
}

// LoadCatalogMetadata enumerates all active workspace packages. Optional library
// discovery includes the selected module build list (also packages not imported
// by a workspace file) and the selected SDK's std packages. No workspace symbol
// result cap or query-dependent truncation is used.
func (s *SemanticSnapshot) LoadCatalogMetadata(ctx context.Context, dependencies, sdk bool) (CatalogMetadata, error) {
	result := CatalogMetadata{Modules: append([]string{}, s.snapshot.modules...), Issues: []string{}}
	if err := ctx.Err(); err != nil {
		return result, err
	}
	data, err := s.catalogGo(ctx, "env", "-json", "GOROOT", "GOOS", "GOARCH", "CGO_ENABLED", "GO111MODULE", "GOPATH")
	if err != nil {
		return result, err
	}
	environment := map[string]string{}
	if err = json.Unmarshal(data, &environment); err != nil {
		return result, err
	}
	result.GOROOT = environment["GOROOT"]
	if result.GOROOT == "" || !filepath.IsAbs(result.GOROOT) || environment["GOOS"] == "" || environment["GOARCH"] == "" {
		return result, fmt.Errorf("the selected Go SDK did not report a valid GOROOT/GOOS/GOARCH")
	}
	result.Context = environment["GOOS"] + "/" + environment["GOARCH"] + " cgo=" + environment["CGO_ENABLED"] + " tags=" + strings.Join(s.snapshot.request.BuildTags, ",")
	patterns := make([]string, 0, len(result.Modules)+1)
	for _, root := range result.Modules {
		pattern := filepath.Join(root, "...")
		if environment["GO111MODULE"] == "off" && root == s.snapshot.root {
			pattern = "./..."
		}
		patterns = append(patterns, pattern)
		result.Roots = append(result.Roots, CatalogRoot{root, "project"})
	}
	if sdk {
		patterns = append(patterns, "std")
		result.Roots = append(result.Roots, CatalogRoot{filepath.Join(result.GOROOT, "src"), "sdk"})
	}
	if dependencies {
		if environment["GO111MODULE"] == "off" {
			for _, root := range filepath.SplitList(environment["GOPATH"]) {
				source := filepath.Join(root, "src")
				if info, statErr := os.Stat(source); statErr == nil && info.IsDir() {
					patterns = append(patterns, filepath.Join(source, "..."))
					result.Roots = append(result.Roots, CatalogRoot{source, "dependency"})
				}
			}
		} else if s.snapshot.vendor {
			result.Roots = append(result.Roots, CatalogRoot{s.snapshot.vendorRoot, "dependency"})
			// modules.txt lists the packages actually present in vendor. Asking
			// `go list -m all` in vendor mode is unsupported by the Go command.
			file, readErr := s.snapshot.read(filepath.Join(s.snapshot.vendorRoot, "modules.txt"))
			if readErr != nil {
				return result, readErr
			}
			for _, line := range strings.Split(file.Text, "\n") {
				line = strings.TrimSpace(line)
				if line != "" && !strings.HasPrefix(line, "#") {
					patterns = append(patterns, line)
				}
			}
		} else {
			modules, listErr := s.catalogGo(ctx, "list", "-m", "-json", "-mod=readonly", "all")
			if listErr != nil {
				if ctx.Err() != nil {
					return result, ctx.Err()
				}
				result.Issues = append(result.Issues, listErr.Error())
			} else {
				decoder := json.NewDecoder(strings.NewReader(string(modules)))
				for {
					var module struct {
						Path    string
						Main    bool
						Dir     string
						Replace *struct{ Dir string }
					}
					if err = decoder.Decode(&module); err == io.EOF {
						break
					} else if err != nil {
						return result, err
					}
					if !module.Main && module.Path != "" {
						patterns = append(patterns, module.Path+"/...")
						dir := module.Dir
						if module.Replace != nil && module.Replace.Dir != "" {
							dir = module.Replace.Dir
						}
						if dir != "" {
							result.Roots = append(result.Roots, CatalogRoot{dir, "dependency"})
						}
					}
				}
			}
		}
	}
	sort.Strings(patterns)
	patterns = unique(patterns)
	for i := range result.Roots {
		resolved, resolveErr := filepath.EvalSymlinks(result.Roots[i].Path)
		if resolveErr != nil {
			return result, resolveErr
		}
		result.Roots[i].Path = resolved
	}
	if sdk {
		if resolved, resolveErr := filepath.EvalSymlinks(result.GOROOT); resolveErr == nil {
			result.GOROOT = resolved
		}
	}
	for _, root := range result.Roots {
		if err = s.captureCatalogRoot(ctx, root.Path); err != nil {
			return result, err
		}
	}
	s.restrictCatalogOverlays(result.Roots)
	overlays := map[string][]byte{}
	for path, file := range s.snapshot.files {
		if file.Exists || file.Overlay != nil {
			overlays[path] = []byte(file.Text)
		}
	}
	for path, overlay := range s.snapshot.overlays {
		overlays[path] = []byte(overlay.Text)
	}
	flags := []string{"-mod=readonly"}
	if environment["GO111MODULE"] == "off" {
		flags = nil
		result.Context += " modules=off"
		result.Modules = nil
	}
	if s.snapshot.vendor && environment["GO111MODULE"] != "off" {
		flags = []string{"-mod=vendor"}
	}
	if len(s.snapshot.request.BuildTags) != 0 {
		flags = append(flags, "-tags="+strings.Join(s.snapshot.request.BuildTags, ","))
	}
	config := &packages.Config{Context: ctx, Dir: s.snapshot.root, Env: s.snapshot.env, Tests: true,
		Mode:    packages.NeedName | packages.NeedFiles | packages.NeedCompiledGoFiles | packages.NeedImports | packages.NeedDeps | packages.NeedModule | packages.NeedForTest,
		Overlay: overlays, BuildFlags: flags}
	roots, err := packages.Load(config, patterns...)
	if err != nil {
		return result, err
	}
	if ctx.Err() != nil {
		return result, ctx.Err()
	}
	seen := map[string]bool{}
	packages.Visit(roots, func(pkg *packages.Package) bool {
		if seen[pkg.ID] {
			return false
		}
		seen[pkg.ID] = true
		result.Packages = append(result.Packages, pkg)
		for _, problem := range pkg.Errors {
			result.Issues = append(result.Issues, problem.Error())
		}
		return true
	}, nil)
	sort.Slice(result.Packages, func(i, j int) bool { return result.Packages[i].ID < result.Packages[j].ID })
	return result, nil
}

func catalogWithin(path, root string) bool {
	rel, err := filepath.Rel(root, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func (s *SemanticSnapshot) captureCatalogRoot(ctx context.Context, root string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if _, ok := s.snapshot.directories[root]; ok {
		return nil
	}
	entries, err := directoryEntries(root)
	if err != nil {
		return err
	}
	s.snapshot.directories[root] = entries
	for _, entry := range entries {
		if strings.HasPrefix(entry, "d:") && !ignoredGoDirectory(entry[2:], false) {
			if err = s.captureCatalogRoot(ctx, filepath.Join(root, entry[2:])); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *SemanticSnapshot) restrictCatalogOverlays(roots []CatalogRoot) {
	belongs := func(path string) bool {
		if path == s.snapshot.work || path == s.snapshot.work+".sum" {
			return true
		}
		for _, root := range roots {
			if catalogWithin(path, root.Path) {
				return true
			}
			// Preserve ancestor metadata/absence observations used to select a
			// workspace, while excluding another project's dirty configuration.
			name := filepath.Base(path)
			if (name == "go.mod" || name == "go.work" || name == "go.sum" || name == "go.work.sum") && catalogWithin(root.Path, filepath.Dir(path)) {
				return true
			}
		}
		return false
	}
	for path := range s.snapshot.overlays {
		if !belongs(path) {
			delete(s.snapshot.overlays, path)
		}
	}
	for path := range s.snapshot.files {
		if !belongs(path) {
			delete(s.snapshot.files, path)
		}
	}
	environment := safeEnv(s.snapshot.env)
	environment["GOWORK"] = s.snapshot.work
	metadata := map[string]string{}
	for path, source := range s.snapshot.files {
		metadata[path] = fmt.Sprint(source.Exists) + ":" + hash([]byte(source.Text))
	}
	value := struct {
		Root, Go, Work        string
		Modules, Tags         []string
		Environment, Metadata map[string]string
	}{
		s.snapshot.root, s.snapshot.request.GoExecutable, s.snapshot.work, s.snapshot.modules, s.snapshot.request.BuildTags, environment, metadata}
	raw, _ := json.Marshal(value)
	s.snapshot.digest = hash(raw)
}

// CaptureCatalogDirectory protects source membership without treating compiler
// caches or generated test-main artifacts as source-package directories.
func (s *SemanticSnapshot) CaptureCatalogDirectory(path string) error {
	return s.snapshot.captureParsedDirectory(path)
}

// CatalogSourcePath undoes an owned shadow-vendor path before publishing a URI.
func (s *SemanticSnapshot) CatalogSourcePath(path string) string {
	return s.snapshot.sourcePath(path)
}
