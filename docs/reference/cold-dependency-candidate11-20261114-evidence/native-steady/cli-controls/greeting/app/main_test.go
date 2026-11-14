package main

import "testing"

func TestCompose(t *testing.T) {
 if got := Compose(); got != "依赖-v1|中文😀" { t.Fatalf("unexpected: %q", got) }
}
