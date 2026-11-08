package signature

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type DirectoryCondition struct {
	URI     string   `json:"uri"`
	Entries []string `json:"entries"`
}

func directoryEntries(path string) ([]string, error) {
	entries, err := os.ReadDir(path)
	if err != nil {
		return nil, err
	}
	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		kind := "f:"
		if entry.Type()&os.ModeSymlink != 0 {
			kind = "l:"
		} else if entry.IsDir() {
			kind = "d:"
		} else if !entry.Type().IsRegular() {
			kind = "o:"
		}
		names = append(names, kind+entry.Name())
	}
	sort.Strings(names)
	return names, nil
}
func ignoredGoDirectory(name string, vendor bool) bool {
	return strings.HasPrefix(name, ".") || strings.HasPrefix(name, "_") || name == "testdata" || (name == "vendor" && !vendor)
}
func (s *snapshot) captureDirectories() error {
	s.directories = map[string][]string{}
	var visit func(string) error
	visit = func(dir string) error {
		if _, ok := s.directories[dir]; ok {
			return nil
		}
		entries, err := directoryEntries(dir)
		if err != nil {
			return err
		}
		s.directories[dir] = entries
		for _, entry := range entries {
			if !strings.HasPrefix(entry, "d:") {
				continue
			}
			name := entry[2:]
			if ignoredGoDirectory(name, s.vendor) {
				continue
			}
			if err = visit(filepath.Join(dir, name)); err != nil {
				return err
			}
		}
		return nil
	}
	for _, root := range s.modules {
		if err := visit(root); err != nil {
			return err
		}
	}
	if s.vendor {
		if err := visit(s.vendorRoot); err != nil {
			return err
		}
	}
	return nil
}
func (s *snapshot) directoryConditions() []DirectoryCondition {
	paths := make([]string, 0, len(s.directories))
	for p := range s.directories {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	result := make([]DirectoryCondition, 0, len(paths))
	for _, p := range paths {
		result = append(result, DirectoryCondition{fileURI(p), append([]string{}, s.directories[p]...)})
	}
	return result
}
func (s *snapshot) unchangedDirectories() error {
	for p, expected := range s.directories {
		actual, err := directoryEntries(p)
		if err != nil {
			return fmt.Errorf("workspace directory changed: %s: %w", p, err)
		}
		if strings.Join(actual, "\x00") != strings.Join(expected, "\x00") {
			return fmt.Errorf("workspace directory membership changed while refactoring: %s", p)
		}
	}
	return nil
}

func (s *snapshot) sourcePath(path string) string {
	path = filepath.Clean(path)
	if s.temporary != "" {
		prefix := filepath.Join(s.temporary, "vendor")
		if rel, e := filepath.Rel(prefix, path); e == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return filepath.Join(s.vendorRoot, rel)
		}
	}
	return path
}
func (s *snapshot) captureParsedDirectory(path string) error {
	dir := filepath.Dir(s.sourcePath(path))
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.directories[dir]; ok {
		return nil
	}
	entries, err := directoryEntries(dir)
	if err != nil {
		return err
	}
	s.directories[dir] = entries
	return nil
}

// The driver discovers files before ParseFile. A dependency directory is first
// seen at ParseFile, so cross-check its .go membership against driver metadata;
// this closes creation between go/list and our directory snapshot.
func (s *snapshot) validateDiscoveryDirectories(known map[string]map[string]bool) error {
	for dir := range s.directories {
		if s.primaryDirectories[dir] {
			continue
		}
		members, ok := known[dir]
		if !ok {
			delete(s.directories, dir)
			continue
		}
		for _, entry := range s.directories[dir] {
			name := entry[2:]
			if strings.HasPrefix(entry, "d:") || !strings.HasSuffix(name, ".go") || strings.HasSuffix(name, "_test.go") || strings.HasPrefix(name, ".") || strings.HasPrefix(name, "_") {
				continue
			}
			if !members[name] {
				return fmt.Errorf("Go package membership changed during analysis: %s", filepath.Join(dir, name))
			}
		}
	}
	return nil
}
