package main

import (
 "fmt"
 dep "example.invalid/accept/dep/deep/a/b"
)

func Compose() string { return dep.Message() + "|中文😀" }

func main() { fmt.Println(Compose()) }
