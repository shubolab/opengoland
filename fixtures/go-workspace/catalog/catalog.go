// Package catalog provides a small, dependency-free Go IDE acceptance project.
package catalog

import "fmt"

type Product struct {
	Name  string
	Price int
}

type Pricer interface {
	Total() int
}

type Cart struct {
	Items []Product
}

func (c Cart) Total() int {
	total := 0
	for _, item := range c.Items {
		total += item.Price
	}
	return total
}

func Greeting(name string) string {
	return fmt.Sprintf("你好，%s 👋", name)
}

func Map[T, R any](values []T, transform func(T) R) []R {
	result := make([]R, len(values))
	for i, value := range values {
		result[i] = transform(value)
	}
	return result
}
