package main

import (
	"fmt"
	"os"
	"time"
)

func main() {
	fmt.Printf("READY %d\n", os.Getpid())
	for {
		time.Sleep(time.Hour)
	}
}
