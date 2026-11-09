package engine

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestWorkingDirectoryPreservesHostAbsoluteAndProjectRelativeMeaning(t *testing.T) {
	base, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(base, "project 中文 😀")
	child := filepath.Join(root, "child")
	member := filepath.Join(base, "workspace member")
	for _, path := range []string{child, member} {
		if err := os.MkdirAll(path, 0700); err != nil {
			t.Fatal(err)
		}
	}
	m := &Manager{root: root}
	for _, tc := range []struct{ name, input, expected string }{
		{"empty", "", root},
		{"relative dot", ".", root},
		{"relative child", "child", child},
		{"relative cleanup", "child" + string(filepath.Separator) + "..", root},
		{"absolute root", root, root},
		{"absolute child", child, child},
	} {
		t.Run(tc.name, func(t *testing.T) {
			actual, err := m.resolveCWD(tc.input)
			if err != nil || actual != tc.expected {
				t.Fatalf("resolve %q = %q, %v; expected %q", tc.input, actual, err, tc.expected)
			}
		})
	}
	for _, path := range []string{member, "..", filepath.Join("..", "workspace member"), filepath.VolumeName(root) + string(filepath.Separator)} {
		if _, err := m.resolveCWD(path); err == nil {
			t.Fatalf("unowned working directory accepted: %q", path)
		}
	}
	// Traversal to an explicitly owned external go.work member stays valid.
	m.moduleRoots = []string{member}
	for _, path := range []string{member, filepath.Join("..", "workspace member")} {
		if actual, err := m.resolveCWD(path); err != nil || actual != member {
			t.Fatalf("owned workspace member %q = %q, %v", path, actual, err)
		}
	}
	if runtime.GOOS == "windows" {
		for _, path := range []string{"/", `\`, "/child", `\child`, filepath.VolumeName(root) + "child", filepath.VolumeName(root)} {
			t.Run("ambiguous "+path, func(t *testing.T) {
				if filepath.IsAbs(path) {
					t.Fatalf("control must be rooted or drive-relative, not absolute: %q", path)
				}
				if _, err := m.resolveCWD(path); err == nil || !strings.Contains(err.Error(), "fully qualified") {
					t.Fatalf("ambiguous working directory %q was reinterpreted: %v", path, err)
				}
			})
		}
	} else {
		// These names are valid relative POSIX paths. Windows-specific syntax
		// restrictions must not accidentally become cross-platform bans.
		for _, name := range []string{"C:notes", `\child`} {
			path := filepath.Join(root, name)
			if err := os.Mkdir(path, 0700); err != nil {
				t.Fatal(err)
			}
			if actual, err := m.resolveCWD(name); err != nil || actual != path {
				t.Fatalf("legal POSIX relative name %q = %q, %v", name, actual, err)
			}
		}
	}
}
