package signature

import (
	"net/url"
	"path/filepath"
	"runtime"
	"testing"
)

func TestLocalFileURIEncodesDriveInPathNotAuthority(t *testing.T) {
	// Exercise the wire representation on every host, even before Windows CI.
	uri := fileURI("c:/Users/前😀/a #%.go")
	want := "file:///C:/Users/%E5%89%8D%F0%9F%98%80/a%20%23%25.go"
	if uri != want {
		t.Fatalf("drive URI = %q, want %q", uri, want)
	}
	parsed, err := url.Parse(uri)
	if err != nil || parsed.Host != "" || parsed.Path != "/C:/Users/前😀/a #%.go" {
		t.Fatalf("drive must remain a local URI path: %#v, %v", parsed, err)
	}
}

func TestLocalFileURIDecodesLiteralRFCPath(t *testing.T) {
	// This input is independent of fileURI, so a matching producer/consumer bug
	// cannot make the native round trip appear correct.
	uri := "file:///tmp/%E5%89%8D%F0%9F%98%80/a%20%23%25.go"
	want := "/tmp/前😀/a #%.go"
	if runtime.GOOS == "windows" {
		uri = "file:///C:/Users/%E5%89%8D%F0%9F%98%80/a%20%23%25.go"
		want = `C:\Users\前😀\a #%.go`
	}
	actual, err := uriPath(uri)
	if err != nil || actual != want {
		t.Fatalf("literal RFC URI %q -> %q, want %q: %v", uri, actual, want, err)
	}
}

func TestLocalFileURINativeAbsoluteRoundTrip(t *testing.T) {
	// On Windows this is a real drive path; no emulated filesystem/path parser.
	path := filepath.Join(t.TempDir(), "前😀", "a #%.go")
	uri := fileURI(path)
	parsed, err := url.Parse(uri)
	if err != nil || parsed.Host != "" {
		t.Fatalf("local URI has an authority: %q (%v)", uri, err)
	}
	actual, err := uriPath(uri)
	if err != nil || actual != filepath.Clean(path) {
		t.Fatalf("round trip %q -> %q -> %q: %v", path, uri, actual, err)
	}
	if runtime.GOOS == "windows" && !filepath.IsAbs(actual) {
		t.Fatalf("decoded Windows drive path is not absolute: %q", actual)
	}
}

func TestLocalFileURIRejectsRemoteOrRelativeInputs(t *testing.T) {
	for _, uri := range []string{"file://server/share/file.go", "file://C:/source.go", "file:source.go", "file:///tmp/file.go?query", "file:///tmp/file.go#fragment", "https://example.org/file.go"} {
		if path, err := uriPath(uri); err == nil {
			t.Errorf("accepted non-local/relative URI %q as %q", uri, path)
		}
	}
}
