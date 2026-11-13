//go:build !alternate

package main

import "testing"

func TestCompose(t *testing.T) {
	const want = "外部-v1|workspace-two|default|中文😀"
	if got := Compose(); got != want {
		t.Fatalf("Compose() = %q, want %q", got, want)
	}
}
