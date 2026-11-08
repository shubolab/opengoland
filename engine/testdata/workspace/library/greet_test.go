package library

import "testing"

func TestGreeting(t *testing.T) {
	if got := Greeting("Go"); got != "你好，Go" {
		t.Fatalf("unexpected greeting: %s", got)
	}
}
