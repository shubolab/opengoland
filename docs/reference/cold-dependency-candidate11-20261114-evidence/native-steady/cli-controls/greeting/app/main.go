package main

import (
 "fmt"
 dep "example.invalid/accept/dep/fresh/a/b"
 anchor "example.invalid/accept/dep"
)

func Compose() string { return anchor.Anchor() + dep.Message() + "|中文😀" }

func main() { fmt.Println(Compose()) }
