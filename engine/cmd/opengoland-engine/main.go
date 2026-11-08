package main

import (
	"fmt"
	"os"

	"github.com/shubolab/opengoland/engine/internal/engine"
	"github.com/shubolab/opengoland/engine/internal/rpc"
)

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "--version", "version":
			fmt.Println("opengoland-engine " + engine.Version)
			return
		case "--help", "-h":
			fmt.Fprintln(os.Stderr, "open Goland engine. JSON-RPC 2.0 over stdio with Content-Length framing. See engine/README.md.")
			return
		default:
			fmt.Fprintln(os.Stderr, "unknown argument; use --help")
			os.Exit(2)
		}
	}
	if err := rpc.New(os.Stdout).Serve(os.Stdin); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
