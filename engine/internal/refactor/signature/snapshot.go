package signature

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"go/types"
	"go/version"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"unicode/utf8"

	"golang.org/x/mod/modfile"
	"golang.org/x/tools/go/packages"
)

type sourceFile struct {
	Path, URI, Text, DiskHash string
	Exists                    bool
	Overlay                   *Overlay
}
type snapshot struct {
	request                       Request
	root, work, digest, temporary string
	modules                       []string
	env                           []string
	mu                            sync.Mutex
	files                         map[string]*sourceFile
	overlays                      map[string]Overlay
	directories                   map[string][]string
	vendor                        bool
	vendorRoot                    string
	primaryDirectories            map[string]bool
}
type analysis struct {
	snap          *snapshot
	fset          *token.FileSet
	packages      []*packages.Package
	files         map[string]*fileInfo
	decls         []*declaration
	errors        []string
	relationTypes map[types.Type]bool
}
type fileInfo struct {
	source  *sourceFile
	ast     *ast.File
	pkg     *packages.Package
	parents map[ast.Node]ast.Node
}

func hash(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }
func fileURI(path string) string {
	return (&url.URL{Scheme: "file", Path: filepath.ToSlash(path)}).String()
}
func uriPath(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "file" || u.Host != "" || u.RawQuery != "" || u.Fragment != "" {
		return "", fmt.Errorf("requires a local file URI: %s", raw)
	}
	path := filepath.FromSlash(u.Path)
	if !filepath.IsAbs(path) {
		return "", errors.New("file URI must be absolute")
	}
	return filepath.Clean(path), nil
}
func normalized(data []byte) (string, error) {
	if !utf8.Valid(data) {
		return "", errors.New("source is not valid UTF-8")
	}
	s := strings.TrimPrefix(string(data), "\ufeff")
	s = strings.ReplaceAll(s, "\r\n", "\n")
	if strings.ContainsRune(s, '\r') {
		return "", errors.New("standalone CR line separators require conversion to LF before refactoring")
	}
	return s, nil
}
func sameExecutable(requested string) error {
	actual, err := exec.LookPath("go")
	if err != nil {
		return err
	}
	if requested == "" {
		return nil
	}
	want, err := filepath.EvalSymlinks(requested)
	if err != nil {
		return err
	}
	got, err := filepath.EvalSymlinks(actual)
	if err != nil {
		return err
	}
	if want != got {
		return fmt.Errorf("selected Go SDK %s differs from engine PATH Go %s; restart the engine with the selected toolchain", requested, actual)
	}
	return nil
}
func newSnapshot(req Request) (*snapshot, error) {
	if req.Root == "" {
		return nil, errors.New("signature request requires a project root")
	}
	root, err := filepath.Abs(req.Root)
	if err != nil {
		return nil, err
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return nil, err
	}
	if err = sameExecutable(req.GoExecutable); err != nil {
		return nil, err
	}
	s := &snapshot{request: req, root: root, files: map[string]*sourceFile{}, overlays: map[string]Overlay{}}
	for _, overlay := range req.Overlays {
		p, err := uriPath(overlay.URI)
		if err != nil {
			return nil, err
		}
		if _, ok := s.overlays[p]; ok {
			return nil, fmt.Errorf("duplicate overlay: %s", overlay.URI)
		}
		if _, err = normalized([]byte(overlay.Text)); err != nil {
			return nil, fmt.Errorf("overlay %s: %w", overlay.URI, err)
		}
		if strings.HasPrefix(overlay.Text, "\ufeff") || strings.Contains(overlay.Text, "\r") {
			return nil, errors.New("editor overlays must use LF text without a UTF-8 BOM")
		}
		s.overlays[p] = overlay
	}
	env := map[string]string{}
	for _, pair := range os.Environ() {
		k, v, ok := strings.Cut(pair, "=")
		if ok {
			env[k] = v
		}
	}
	for k, v := range req.Environment {
		if k == "" || strings.ContainsAny(k, "=\x00") || strings.ContainsRune(v, '\x00') {
			return nil, errors.New("invalid signature environment")
		}
		env[k] = v
	}
	// The selected IDE SDK is authoritative; analysis never downloads another toolchain.
	env["GOTOOLCHAIN"] = "local"
	env["GOTELEMETRY"] = "off"
	env["GOPACKAGESDRIVER"] = "off"
	work := env["GOWORK"]
	if work == "" {
		work = s.findAncestor(root, "go.work")
	}
	if work != "" && work != "off" {
		if !filepath.IsAbs(work) {
			return nil, errors.New("GOWORK must be an absolute path or off")
		}
		s.work = filepath.Clean(work)
		src, e := s.read(s.work)
		if e != nil {
			return nil, e
		}
		wf, e := modfile.ParseWork(s.work, []byte(src.Text), nil)
		if e != nil {
			return nil, e
		}
		originalUses := append([]*modfile.Use(nil), wf.Use...)
		for _, use := range originalUses {
			p := use.Path
			if !filepath.IsAbs(p) {
				p = filepath.Join(filepath.Dir(s.work), p)
			}
			p, e = filepath.EvalSymlinks(p)
			if e != nil {
				return nil, e
			}
			s.modules = append(s.modules, p)
			_ = wf.DropUse(use.Path)
			_ = wf.AddUse(p, use.ModulePath)
		}
		originalReplaces := append([]*modfile.Replace(nil), wf.Replace...)
		for _, rep := range originalReplaces {
			if rep.New.Version == "" && !filepath.IsAbs(rep.New.Path) {
				p := filepath.Join(filepath.Dir(s.work), rep.New.Path)
				_ = wf.DropReplace(rep.Old.Path, rep.Old.Version)
				_ = wf.AddReplace(rep.Old.Path, rep.Old.Version, p, "")
			}
		}
		wf.Cleanup()
		s.vendorRoot = filepath.Join(filepath.Dir(s.work), "vendor")
		if st, statErr := os.Stat(s.vendorRoot); statErr == nil && st.IsDir() {
			s.vendor = wf.Go != nil && version.Compare("go"+wf.Go.Version, "go1.22") >= 0
		}
		s.temporary, e = os.MkdirTemp("", "opengoland-signature-work-")
		if e != nil {
			return nil, e
		}
		fail := true
		defer func() {
			if fail {
				s.close()
			}
		}()
		tempWork := filepath.Join(s.temporary, "go.work")
		if e = os.WriteFile(tempWork, modfile.Format(wf.Syntax), 0600); e != nil {
			return nil, e
		}
		sum, e := s.readOptional(s.work + ".sum")
		if e != nil {
			return nil, e
		}
		if sum.Exists {
			if e = os.WriteFile(tempWork+".sum", []byte(sum.Text), 0600); e != nil {
				return nil, e
			}
		}
		if mode := explicitModMode(env["GOFLAGS"]); mode != "" {
			s.vendor = mode == "vendor"
		}
		if s.vendor {
			if e = os.Symlink(s.vendorRoot, filepath.Join(s.temporary, "vendor")); e != nil {
				return nil, fmt.Errorf("cannot prepare readonly vendor metadata snapshot: %w", e)
			}
		}
		env["GOWORK"] = tempWork
		fail = false
	} else {
		env["GOWORK"] = "off"
		mod := s.findAncestor(root, "go.mod")
		if mod == "" {
			return nil, errors.New("open a Go module or a go.work workspace before changing a signature")
		}
		s.modules = []string{filepath.Dir(mod)}
		s.vendorRoot = filepath.Join(filepath.Dir(mod), "vendor")
		if st, statErr := os.Stat(s.vendorRoot); statErr == nil && st.IsDir() {
			src, readErr := s.read(mod)
			if readErr != nil {
				return nil, readErr
			}
			mf, parseErr := modfile.Parse(mod, []byte(src.Text), nil)
			if parseErr != nil {
				return nil, parseErr
			}
			s.vendor = mf.Go != nil && version.Compare("go"+mf.Go.Version, "go1.14") >= 0
		}
	}
	if len(s.modules) == 0 {
		s.close()
		return nil, errors.New("Go workspace has no use modules")
	}
	sort.Strings(s.modules)
	s.modules = unique(s.modules)
	for _, dir := range s.modules {
		if _, err = s.read(filepath.Join(dir, "go.mod")); err != nil {
			s.close()
			return nil, err
		}
		if _, err = s.readOptional(filepath.Join(dir, "go.sum")); err != nil {
			s.close()
			return nil, err
		}
	}
	// Retain all supplied config overlays as analysis preconditions, even when a
	// package driver does not need a particular file in this build context.
	for path := range s.overlays {
		if strings.HasSuffix(path, "go.mod") || strings.HasSuffix(path, "go.sum") || strings.HasSuffix(path, "go.work") || strings.HasSuffix(path, "go.work.sum") {
			if _, err = s.readOptional(path); err != nil {
				s.close()
				return nil, err
			}
		}
	}
	for _, flag := range strings.Fields(env["GOFLAGS"]) {
		if strings.HasPrefix(flag, "-mod=") {
			s.vendor = strings.TrimPrefix(flag, "-mod=") == "vendor"
		}
	}
	if s.vendor {
		if _, err = s.readOptional(filepath.Join(s.vendorRoot, "modules.txt")); err != nil {
			s.close()
			return nil, err
		}
	}
	if err = s.captureDirectories(); err != nil {
		s.close()
		return nil, err
	}
	s.primaryDirectories = map[string]bool{}
	for dir := range s.directories {
		s.primaryDirectories[dir] = true
	}
	envKeys := make([]string, 0, len(env))
	for k := range env {
		envKeys = append(envKeys, k)
	}
	sort.Strings(envKeys)
	for _, k := range envKeys {
		s.env = append(s.env, k+"="+env[k])
	}
	config := struct {
		Root, Go, Work string
		Modules, Tags  []string
		Env            map[string]string
		Metadata       map[string]string
	}{root, req.GoExecutable, s.work, s.modules, req.BuildTags, env, map[string]string{}}
	// Exclude the unique temporary work path; its original content is a precondition.
	config.Env = map[string]string{}
	for k, v := range env {
		config.Env[k] = v
	}
	config.Env["GOWORK"] = s.work
	for path, file := range s.files {
		config.Metadata[path] = fmt.Sprint(file.Exists) + ":" + hash([]byte(file.Text))
	}
	data, _ := json.Marshal(config)
	s.digest = hash(data)
	return s, nil
}
func unique(xs []string) []string {
	out := xs[:0]
	for _, x := range xs {
		if len(out) == 0 || out[len(out)-1] != x {
			out = append(out, x)
		}
	}
	return out
}
func (s *snapshot) close() {
	if s.temporary != "" {
		_ = os.RemoveAll(s.temporary)
	}
}
func (s *snapshot) findAncestor(start, name string) string {
	for p := start; ; p = filepath.Dir(p) {
		candidate := filepath.Join(p, name)
		_, _ = s.readOptional(candidate)
		if _, ok := s.overlays[candidate]; ok {
			return candidate
		}
		if i, e := os.Stat(candidate); e == nil && !i.IsDir() {
			return candidate
		}
		if filepath.Dir(p) == p {
			return ""
		}
	}
}
func (s *snapshot) read(path string) (*sourceFile, error) {
	f, err := s.readOptional(path)
	if err == nil && !f.Exists && f.Overlay == nil {
		return nil, fmt.Errorf("file does not exist: %s", path)
	}
	return f, err
}
func (s *snapshot) readOptional(path string) (*sourceFile, error) {
	path = filepath.Clean(path)
	if s.temporary != "" {
		temporaryVendor := filepath.Join(s.temporary, "vendor")
		if rel, e := filepath.Rel(temporaryVendor, path); e == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			path = filepath.Join(s.vendorRoot, rel)
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if f := s.files[path]; f != nil {
		return f, nil
	}
	data, err := os.ReadFile(path)
	exists := err == nil
	if err != nil && !os.IsNotExist(err) {
		return nil, err
	}
	text, err := normalized(data)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	f := &sourceFile{Path: path, URI: fileURI(path), Text: text, Exists: exists}
	if exists {
		f.DiskHash = hash(data)
	}
	if overlay, ok := s.overlays[path]; ok {
		f.Overlay = &overlay
		f.Text = overlay.Text
		if overlay.DiskSHA256 != "" && overlay.DiskSHA256 != f.DiskHash {
			return nil, fmt.Errorf("disk changed before analysis: %s", path)
		}
	}
	s.files[path] = f
	return f, nil
}
func (s *snapshot) editable(path string) bool {
	if s.temporary != "" && strings.HasPrefix(path, s.temporary+string(filepath.Separator)) {
		return false
	}
	for _, root := range s.modules {
		rel, e := filepath.Rel(root, path)
		if e == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel) {
			for _, part := range strings.Split(filepath.ToSlash(rel), "/") {
				if part == "vendor" || strings.HasPrefix(part, ".") || strings.HasPrefix(part, "_") {
					return false
				}
			}
			return true
		}
	}
	return false
}
func (s *snapshot) preconditions() []Precondition {
	paths := make([]string, 0, len(s.files))
	for path := range s.files {
		paths = append(paths, path)
	}
	sort.Strings(paths)
	out := make([]Precondition, 0, len(paths))
	for _, path := range paths {
		f := s.files[path]
		p := Precondition{URI: f.URI, DiskSHA256: f.DiskHash, TextSHA256: hash([]byte(f.Text)), Exists: f.Exists}
		if f.Overlay != nil {
			stamp := f.Overlay.DocumentStamp
			p.DocumentStamp = &stamp
			p.LSP = f.Overlay.LSP
		}
		out = append(out, p)
	}
	return out
}
func (s *snapshot) unchanged() error {
	if err := s.unchangedDirectories(); err != nil {
		return err
	}
	for path, f := range s.files {
		data, err := os.ReadFile(path)
		if os.IsNotExist(err) && !f.Exists {
			continue
		}
		if err != nil {
			return fmt.Errorf("snapshot changed: %s: %w", path, err)
		}
		if !f.Exists || hash(data) != f.DiskHash {
			return fmt.Errorf("file changed while refactoring: %s", path)
		}
	}
	return nil
}
func (s *snapshot) load(ctx context.Context, candidates map[string]string) (*analysis, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	overlays := map[string][]byte{}
	for path, file := range s.files {
		base := filepath.Base(path)
		if base == "go.mod" || base == "go.sum" || base == "go.work" || base == "go.work.sum" {
			if file.Exists || file.Overlay != nil {
				overlays[path] = []byte(file.Text)
			}
		}
	}
	for p, o := range s.overlays {
		overlays[p] = []byte(o.Text)
	}
	for p, text := range candidates {
		overlays[p] = []byte(text)
	}
	fset := token.NewFileSet()
	cfg := &packages.Config{Context: ctx, Dir: s.root, Env: s.env, Fset: fset, Tests: true, Mode: packages.LoadAllSyntax | packages.NeedModule | packages.NeedForTest, Overlay: overlays, BuildFlags: []string{"-mod=readonly"}}
	if s.vendor {
		cfg.BuildFlags = []string{"-mod=vendor"}
	}
	if len(s.request.BuildTags) > 0 {
		cfg.BuildFlags = append(cfg.BuildFlags, "-tags="+strings.Join(s.request.BuildTags, ","))
	}
	cfg.ParseFile = func(fs *token.FileSet, name string, provided []byte) (*ast.File, error) {
		if err := s.captureParsedDirectory(name); err != nil {
			return nil, err
		}
		text, ok := candidates[name]
		if !ok {
			src, err := s.read(name)
			if err != nil {
				return nil, err
			}
			text = src.Text
		}
		return parser.ParseFile(fs, name, text, parser.ParseComments|parser.SkipObjectResolution)
	}
	patterns := make([]string, len(s.modules))
	for i, p := range s.modules {
		patterns[i] = filepath.Join(p, "...")
	}
	roots, err := packages.Load(cfg, patterns...)
	if err != nil {
		return nil, err
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	a := &analysis{snap: s, fset: fset, packages: roots, files: map[string]*fileInfo{}, errors: []string{}, relationTypes: map[types.Type]bool{}}
	// Visit every dependency in the same type universe. Only main-module source is editable.
	seen := map[string]bool{}
	discovery := map[string]map[string]bool{}
	var sourceError error
	packages.Visit(roots, func(p *packages.Package) bool {
		if seen[p.ID] {
			return false
		}
		seen[p.ID] = true
		if p.Types != nil {
			for _, name := range p.Types.Scope().Names() {
				if object, ok := p.Types.Scope().Lookup(name).(*types.TypeName); ok {
					a.addRelationType(object.Type())
				}
			}
		}
		if p.TypesInfo != nil {
			for _, value := range p.TypesInfo.Types {
				a.addRelationType(value.Type)
			}
			for _, instance := range p.TypesInfo.Instances {
				a.addRelationType(instance.Type)
			}
		}
		for _, e := range p.Errors {
			a.errors = append(a.errors, e.Error())
		}
		for _, path := range append(append([]string{}, p.GoFiles...), p.IgnoredFiles...) {
			path = s.sourcePath(path)
			dir := filepath.Dir(path)
			if discovery[dir] == nil {
				discovery[dir] = map[string]bool{}
			}
			discovery[dir][filepath.Base(path)] = true
		}
		if p.Module != nil && p.Module.GoMod != "" {
			if _, readErr := s.readOptional(p.Module.GoMod); readErr != nil {
				sourceError = readErr
			}
		}
		for _, f := range p.Syntax {
			path := fset.PositionFor(f.Pos(), false).Filename
			if !s.editable(path) {
				continue
			}
			src, e := s.read(path)
			if e != nil {
				continue
			}
			if text, changed := candidates[path]; changed {
				copy := *src
				copy.Text = text
				src = &copy
			}
			// Prefer the test variant: it has all same-package test uses. Distinct external
			// test packages keep their own file. Physical source identity deduplicates edits.
			old := a.files[path]
			if old != nil && len(old.pkg.CompiledGoFiles) >= len(p.CompiledGoFiles) {
				continue
			}
			fi := &fileInfo{source: src, ast: f, pkg: p, parents: map[ast.Node]ast.Node{}}
			var stack []ast.Node
			ast.Inspect(f, func(n ast.Node) bool {
				if n == nil {
					stack = stack[:len(stack)-1]
					return false
				}
				if len(stack) > 0 {
					fi.parents[n] = stack[len(stack)-1]
				}
				stack = append(stack, n)
				return true
			})
			a.files[path] = fi
		}
		return true
	}, nil)
	if sourceError != nil {
		return nil, sourceError
	}
	if err := s.validateDiscoveryDirectories(discovery); err != nil {
		return nil, err
	}
	sort.Strings(a.errors)
	a.errors = unique(a.errors)
	if len(a.files) == 0 {
		return nil, errors.New("the selected build context contains no editable Go packages")
	}
	a.collectDeclarations()
	return a, nil
}
func (a *analysis) coverage() Coverage {
	return Coverage{Modules: append([]string{}, a.snap.modules...), Packages: len(a.packages), Files: len(a.files), Tests: true, ExternalBoundary: true, BuildContext: effectiveEnv(safeEnv(a.snap.env), "GOOS", runtime.GOOS) + "/" + effectiveEnv(safeEnv(a.snap.env), "GOARCH", runtime.GOARCH) + " tags=" + strings.Join(a.snap.request.BuildTags, ",")}
}
func byteOffset(text string, p Position) (int, error) {
	if p.Line < 0 || p.Character < 0 {
		return 0, errors.New("negative UTF-16 position")
	}
	line := 0
	start := 0
	for line < p.Line {
		i := strings.IndexByte(text[start:], '\n')
		if i < 0 {
			return 0, errors.New("position line is outside source")
		}
		start += i + 1
		line++
	}
	units := 0
	for i, r := range text[start:] {
		if units == p.Character {
			return start + i, nil
		}
		if r == '\n' {
			break
		}
		if r > 0xffff {
			units += 2
		} else {
			units++
		}
		if units > p.Character {
			return 0, errors.New("UTF-16 position splits a surrogate pair")
		}
	}
	if units == p.Character {
		end := strings.IndexByte(text[start:], '\n')
		if end < 0 {
			end = len(text) - start
		}
		return start + end, nil
	}
	return 0, errors.New("position character is outside source line")
}
func positionAt(text string, offset int) Position {
	if offset < 0 {
		offset = 0
	}
	if offset > len(text) {
		offset = len(text)
	}
	prefix := text[:offset]
	line := strings.Count(prefix, "\n")
	last := strings.LastIndexByte(prefix, '\n') + 1
	units := 0
	for _, r := range prefix[last:] {
		if r > 0xffff {
			units += 2
		} else {
			units++
		}
	}
	return Position{line, units}
}
func sourceRange(f *sourceFile, start, end int) Range {
	return Range{positionAt(f.Text, start), positionAt(f.Text, end)}
}
func (fi *fileInfo) offset(pos token.Pos) int { return fi.pkg.Fset.PositionFor(pos, false).Offset }
func (fi *fileInfo) text(n ast.Node) string {
	if n == nil {
		return ""
	}
	return fi.source.Text[fi.offset(n.Pos()):fi.offset(n.End())]
}

func explicitModMode(flags string) string {
	mode := ""
	for _, flag := range strings.Fields(flags) {
		if strings.HasPrefix(flag, "-mod=") {
			mode = strings.TrimPrefix(flag, "-mod=")
		}
	}
	return mode
}

func safeEnv(entries []string) map[string]string {
	out := map[string]string{}
	for _, entry := range entries {
		if key, value, ok := strings.Cut(entry, "="); ok {
			out[key] = value
		}
	}
	return out
}
func effectiveEnv(env map[string]string, key, fallback string) string {
	if value := env[key]; value != "" {
		return value
	}
	return fallback
}
