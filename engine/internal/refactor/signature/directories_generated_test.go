package signature

import (
	"os"
	"path/filepath"
	"testing"
)

func directorySnapshot(t *testing.T) (*snapshot, string) {
	t.Helper()
	root, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	for name, text := range map[string]string{"go.mod": "module example.org/directoryguard\n\ngo 1.24.0\n", "main.go": "package fixture\ntype Buffer struct{}\n"} {
		if e = os.WriteFile(filepath.Join(root, name), []byte(text), 0600); e != nil {
			t.Fatal(e)
		}
	}
	s, e := newSnapshot(Request{Root: root, Environment: map[string]string{"GOWORK": "off", "GOPROXY": "off"}})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(s.close)
	return s, root
}

func TestGeneratedExternalInputKeepsItsHashWithoutGuardingUnrelatedCacheEntries(t *testing.T) {
	s, _ := directorySnapshot(t)
	cache, e := filepath.EvalSymlinks(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	input := filepath.Join(cache, "generated-test-main-d")
	if e = os.WriteFile(input, []byte("package main\nfunc main(){}\n"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = s.captureParsedDirectory(input); e != nil {
		t.Fatal(e)
	}
	if _, e = s.read(input); e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(cache, "another-build-d"), []byte("unrelated compiler cache artifact"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = s.unchanged(); e != nil {
		t.Fatalf("another build cache entry is not package membership: %v", e)
	}
	found := false
	for _, pre := range s.preconditions() {
		if pre.URI == fileURI(input) && pre.DiskSHA256 != "" {
			found = true
		}
	}
	if !found {
		t.Fatal("the actual generated compiler input lost its byte/hash precondition")
	}
	if e = os.WriteFile(input, []byte("package main\nfunc changed(){}\n"), 0600); e != nil {
		t.Fatal(e)
	}
	if e = s.unchanged(); e == nil {
		t.Fatal("changing the selected generated input itself must invalidate the snapshot")
	}
}

func TestOrdinaryExternalGoAndWorkspaceDirectoryMembershipStillInvalidateSnapshots(t *testing.T) {
	t.Run("external-sdk-go", func(t *testing.T) {
		s, _ := directorySnapshot(t)
		sdk, e := filepath.EvalSymlinks(t.TempDir())
		if e != nil {
			t.Fatal(e)
		}
		source := filepath.Join(sdk, "sdk.go")
		if e = os.WriteFile(source, []byte("package sdk\n"), 0600); e != nil {
			t.Fatal(e)
		}
		if e = s.captureParsedDirectory(source); e != nil {
			t.Fatal(e)
		}
		if _, e = s.read(source); e != nil {
			t.Fatal(e)
		}
		if e = os.WriteFile(filepath.Join(sdk, "new.go"), []byte("package sdk\n"), 0600); e != nil {
			t.Fatal(e)
		}
		if e = s.unchanged(); e == nil {
			t.Fatal("SDK .go directory membership must stay guarded")
		}
	})
	t.Run("workspace-go", func(t *testing.T) {
		s, root := directorySnapshot(t)
		if e := os.WriteFile(filepath.Join(root, "new.go"), []byte("package fixture\n"), 0600); e != nil {
			t.Fatal(e)
		}
		if e := s.unchanged(); e == nil {
			t.Fatal("workspace Go directory membership must stay guarded")
		}
	})
}
