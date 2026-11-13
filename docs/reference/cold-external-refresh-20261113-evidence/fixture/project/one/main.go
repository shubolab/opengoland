package main

import (
	"fmt"

	"example.invalid/open-goland/consistency/external"
	"example.invalid/open-goland/consistency/two"
)

func Compose() string {
	return fmt.Sprintf("%s|%s|%s|中文😀", external.Message(), two.Origin(), Flavor())
}

func main() {
	fmt.Println(Compose())
}
