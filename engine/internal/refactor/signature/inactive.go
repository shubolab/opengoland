package signature

import (
	"go/scanner"
	"go/token"
	"path/filepath"
	"strings"
)

// Inactive build files cannot be assigned the active type world's object
// identities. Until every relevant context is loaded, a possible affected
// reference is a source-located conflict, never a silently omitted call.
func (a *analysis) inactiveReferences(name string) []Conflict {
	var conflicts []Conflict
	for dir := range a.snap.primaryDirectories {
		for _, entry := range a.snap.directories[dir] {
			if !strings.HasPrefix(entry, "f:") && !strings.HasPrefix(entry, "l:") {
				continue
			}
			base := entry[2:]
			if !strings.HasSuffix(base, ".go") {
				continue
			}
			path := filepath.Join(dir, base)
			if a.files[path] != nil || !a.snap.editable(path) {
				continue
			}
			source, err := a.snap.read(path)
			if err != nil {
				conflicts = append(conflicts, Conflict{URI: fileURI(path), Code: "inactive-source", Message: err.Error()})
				continue
			}
			file := token.NewFileSet().AddFile(path, -1, len(source.Text))
			var scan scanner.Scanner
			scan.Init(file, []byte(source.Text), nil, 0)
			for {
				pos, tok, text := scan.Scan()
				if tok == token.EOF {
					break
				}
				if tok == token.IDENT && text == name {
					start := file.Offset(pos)
					conflicts = append(conflicts, Conflict{source.URI, sourceRange(source, start, start+len(text)), "inactive-build-context", "This excluded Go build file contains a potentially affected " + name + " reference. Its build context needs a typed relationship/call migration before this workspace-wide signature can be changed"})
					break
				}
			}
		}
	}
	return conflicts
}
