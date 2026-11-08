package structs

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"time"
)

const workerFlag = "--opengoland-struct-helpers-worker"
const workerLimit = 16 << 20

type workerRequest struct {
	Method string          `json:"method"`
	Params json.RawMessage `json:"params"`
}
type workerResponse struct {
	Result json.RawMessage `json:"result,omitempty"`
	Error  string          `json:"error,omitempty"`
}

// WorkerMain is the explicit private CLI route for one analysis. The parent
// retains this child and (on Unix) its process group, including go/list and any
// compiler or CGO descendants. Normal engine stdio never enters this route.
func WorkerMain(arguments []string, input io.Reader, output io.Writer) (bool, error) {
	if len(arguments) != 1 || arguments[0] != workerFlag {
		return false, nil
	}

	var request workerRequest
	decoder := json.NewDecoder(io.LimitReader(input, workerLimit+1))
	if err := decoder.Decode(&request); err != nil {
		return true, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	expectedParent, _ := strconv.Atoi(os.Getenv("OPENGOLAND_STRUCT_HELPERS_PARENT_PID"))
	// RunIsolated overwrites this private handoff marker for its retained child.
	// Keep the claim after reparenting too: the parent may die before Decode.
	ownedParent := expectedParent > 0
	// The parent keeps this pipe open until worker exit. EOF therefore signals
	// parent death/disconnect, rather than the end of the initial JSON packet.
	go func() {
		_, _ = io.Copy(io.Discard, input)
		cancel()
		if ownedParent {
			terminateOwnWorkerGroup()
		}
	}()
	var result any
	var err error
	switch request.Method {
	case "inspect":
		var params Request
		if err = json.Unmarshal(request.Params, &params); err == nil {
			result, err = Inspect(ctx, params)
		}
	case "plan":
		var params PlanRequest
		if err = json.Unmarshal(request.Params, &params); err == nil {
			result, err = Plan(ctx, params)
		}
	default:
		err = errors.New("unknown struct helpers worker operation")
	}
	response := workerResponse{}
	if err != nil {
		response.Error = err.Error()
	} else {
		response.Result, err = json.Marshal(result)
		if err != nil {
			return true, err
		}
	}
	data, err := json.Marshal(response)
	if err != nil {
		return true, err
	}
	if len(data) > workerLimit {
		return true, errors.New("struct helpers worker response exceeds the transport limit")
	}
	_, err = output.Write(data)
	return true, err
}

type workerBuffer struct {
	bytes.Buffer
	limit int
}

func (b *workerBuffer) Write(data []byte) (int, error) {
	if b.Len()+len(data) > b.limit {
		return 0, errors.New("struct helpers worker output exceeds its bounded buffer")
	}
	return b.Buffer.Write(data)
}

// RunIsolated owns exactly one subprocess. No user Go binary is executed;
// inspect/plan only load metadata, type-check sources, and return an edit plan.
func RunIsolated(ctx context.Context, method string, params any) (json.RawMessage, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	raw, err := json.Marshal(params)
	if err != nil {
		return nil, err
	}
	body, err := json.Marshal(workerRequest{method, raw})
	if err != nil {
		return nil, err
	}
	if len(body) > workerLimit {
		return nil, errors.New("struct helpers request exceeds the transport limit")
	}
	executable, err := os.Executable()
	if err != nil {
		return nil, err
	}
	cmd := exec.CommandContext(ctx, executable, workerFlag)
	configureWorker(cmd)
	cmd.Cancel = func() error { return killWorker(cmd) }
	cmd.WaitDelay = time.Second
	out, stderr := &workerBuffer{limit: workerLimit}, &workerBuffer{limit: 64 << 10}
	cmd.Env = append(os.Environ(), "OPENGOLAND_STRUCT_HELPERS_PARENT_PID="+strconv.Itoa(os.Getpid()))
	cmd.Stdout = out
	cmd.Stderr = stderr
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	defer stdin.Close()
	if err = cmd.Start(); err != nil {
		return nil, err
	}
	defer func() { _ = killWorker(cmd) }()
	if _, err = stdin.Write(append(body, '\n')); err != nil {
		_ = killWorker(cmd)
		_ = cmd.Wait()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, err
	}
	err = cmd.Wait()
	// A failed worker may have left a compiler behind even when its own process
	// exited. Its original dedicated group remains ours and is cleaned as well.
	if err != nil {
		_ = killWorker(cmd)
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("struct helpers worker: %w: %s", err, stderr.String())
	}
	if ctx.Err() != nil {
		_ = killWorker(cmd)
		return nil, ctx.Err()
	}
	var response workerResponse
	if err = json.Unmarshal(out.Bytes(), &response); err != nil {
		return nil, fmt.Errorf("invalid struct helpers worker reply: %w", err)
	}
	if response.Error != "" {
		return nil, errors.New(response.Error)
	}
	return response.Result, nil
}
