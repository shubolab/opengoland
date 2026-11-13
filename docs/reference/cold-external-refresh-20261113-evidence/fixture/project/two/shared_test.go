package two

import "testing"

func TestOrigin(t *testing.T) {
	if got := Origin(); got != "workspace-two" {
		t.Fatalf("Origin() = %q", got)
	}
}
