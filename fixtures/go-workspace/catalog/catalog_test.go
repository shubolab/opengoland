package catalog

import (
	"os"
	"testing"
)

func TestCartTotal(t *testing.T) {
	for _, test := range []struct {
		name  string
		items []Product
		want  int
	}{
		{"empty", nil, 0},
		{"two products", []Product{{"Book", 25}, {"Coffee", 12}}, 37},
	} {
		t.Run(test.name, func(t *testing.T) {
			if got := (Cart{Items: test.items}).Total(); got != test.want {
				t.Fatalf("Total() = %d, want %d", got, test.want)
			}
		})
	}
}

func TestGreeting(t *testing.T) {
	if got := Greeting("Go 开发者"); got != "你好，Go 开发者 👋" {
		t.Fatalf("unexpected UTF-8 greeting: %q", got)
	}
}

func TestMap(t *testing.T) {
	got := Map([]int{1, 2, 3}, func(n int) int { return n * 2 })
	if got[0] != 2 || got[2] != 6 {
		t.Fatalf("unexpected generic result: %v", got)
	}
}

func TestFailureNavigation(t *testing.T) {
	if os.Getenv("OPENIDEA_FAIL") == "1" {
		t.Fatal("intentional failure for test-tree and source-navigation acceptance")
	}
}

func BenchmarkCartTotal(b *testing.B) {
	cart := Cart{Items: []Product{{"Book", 25}, {"Coffee", 12}}}
	for b.Loop() {
		_ = cart.Total()
	}
}

func FuzzGreeting(f *testing.F) {
	f.Add("Go 开发者")
	f.Fuzz(func(t *testing.T, name string) {
		if len(Greeting(name)) < len(name) {
			t.Fatal("greeting lost content")
		}
	})
}
