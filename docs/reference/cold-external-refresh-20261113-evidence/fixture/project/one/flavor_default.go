//go:build !alternate

package main

func Flavor() string {
	return "default"
}

func DefaultOnly() string {
	return "default-only"
}
