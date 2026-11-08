package main

import (
	"os"
	"testing"
)

func TestApplication(t *testing.T) {
	t.Run("happy", func(t *testing.T) { t.Log("完整测试链路") })
	t.Run("controlledFailure", func(t *testing.T) {
		if os.Getenv("OPENIDEA_TEST_FAIL") == "1" {
			t.Fatal("intentional failure for IDE test-tree verification")
		}
	})
}

func BenchmarkApplication(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = i * i
	}
}
