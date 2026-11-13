package two

import "testing"

func TestOrigin(t *testing.T) {
	if got := Origin(); got != "fallback-two" {
		t.Fatalf("Origin() = %q", got)
	}
}
