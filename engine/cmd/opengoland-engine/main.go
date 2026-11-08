package main

import (
	"fmt"
	"os"

	"github.com/shubolab/opengoland/engine/internal/engine"
	jsongen "github.com/shubolab/opengoland/engine/internal/generate/jsonstruct"
	methodgen "github.com/shubolab/opengoland/engine/internal/generate/methods"
	structgen "github.com/shubolab/opengoland/engine/internal/generate/structs"
	taggen "github.com/shubolab/opengoland/engine/internal/generate/tags"
	testgen "github.com/shubolab/opengoland/engine/internal/generate/tests"
	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	"github.com/shubolab/opengoland/engine/internal/rpc"
)

func main() {
	if handled, err := jsongen.WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if handled, err := structgen.WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if handled, err := taggen.WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if handled, err := methodgen.WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if handled, err := testgen.WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	if handled, err := signature.WorkerMain(os.Args[1:], os.Stdin, os.Stdout); handled {
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
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
