package main

import (
 "fmt"
 "example.invalid/accept/dep"
)

func Compose() string { return dep.Message() + "|中文😀" }

func main() { fmt.Println(Compose()) }
