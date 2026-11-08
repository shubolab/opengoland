package main

import (
	"fmt"

	"example.com/openidea/catalog"
)

func main() {
	cart := catalog.Cart{Items: []catalog.Product{
		{Name: "Book", Price: 25},
		{Name: "Coffee", Price: 12},
	}}
	var price catalog.Pricer = cart
	fmt.Println(catalog.Greeting("Go 开发者"))
	fmt.Printf("Total: %d\n", price.Total())
}
