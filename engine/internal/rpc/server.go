package rpc

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"sync"

	"github.com/shubolab/opengoland/engine/internal/engine"
	jsongen "github.com/shubolab/opengoland/engine/internal/generate/jsonstruct"
	methodgen "github.com/shubolab/opengoland/engine/internal/generate/methods"
	structgen "github.com/shubolab/opengoland/engine/internal/generate/structs"
	taggen "github.com/shubolab/opengoland/engine/internal/generate/tags"
	testgen "github.com/shubolab/opengoland/engine/internal/generate/tests"
	"github.com/shubolab/opengoland/engine/internal/protocol"
	nativerefactor "github.com/shubolab/opengoland/engine/internal/refactor/native"
	"github.com/shubolab/opengoland/engine/internal/refactor/signature"
	typesearch "github.com/shubolab/opengoland/engine/internal/search/types"
)

type request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type Server struct {
	writer                     *protocol.Writer
	mu                         sync.Mutex
	engine                     *engine.Manager
	ctx                        context.Context
	cancel                     context.CancelFunc
	done                       chan struct{}
	once                       sync.Once
	wg                         sync.WaitGroup
	closing                    bool
	signatureRoot              string
	signatureGo                string
	signatureOps               map[string]*signatureOperation
	signatureCancelled         map[string]bool
	signatureCancellationOrder []string
}

type signatureOperation struct{ cancel context.CancelFunc }

func New(output io.Writer) *Server {
	ctx, cancel := context.WithCancel(context.Background())
	return &Server{writer: protocol.NewWriter(output), ctx: ctx, cancel: cancel, done: make(chan struct{}), signatureOps: make(map[string]*signatureOperation), signatureCancelled: make(map[string]bool)}
}

func (s *Server) notify(method string, params any) {
	_ = s.writer.Write(map[string]any{"jsonrpc": "2.0", "method": method, "params": params})
}

func (s *Server) result(id json.RawMessage, result any) error {
	return s.writer.Write(map[string]any{"jsonrpc": "2.0", "id": id, "result": result})
}

func (s *Server) failure(id json.RawMessage, code int, message string) {
	if len(id) == 0 {
		id = json.RawMessage("null")
	}
	_ = s.writer.Write(map[string]any{"jsonrpc": "2.0", "id": id, "error": rpcError{Code: code, Message: message}})
}

func (s *Server) stop() {
	s.once.Do(func() {
		s.mu.Lock()
		s.closing = true
		s.mu.Unlock()
		s.cancel()
		close(s.done)
	})
}

func (s *Server) closeEngine() error {
	s.mu.Lock()
	m := s.engine
	s.mu.Unlock()
	if m != nil {
		return m.Close()
	}
	return nil
}

func (s *Server) Serve(input io.Reader) error {
	defer func() {
		s.stop()
		_ = s.closeEngine()
		s.wg.Wait()
	}()
	frames := make(chan []byte)
	errors := make(chan error, 1)
	go func() {
		reader := protocol.NewReader(input)
		for {
			body, err := reader.Read()
			if err != nil {
				errors <- err
				return
			}
			select {
			case frames <- body:
			case <-s.done:
				return
			}
		}
	}()
	for {
		select {
		case body := <-frames:
			var req request
			if !json.Valid(body) {
				s.failure(nil, -32700, "invalid JSON")
				continue
			}
			if err := json.Unmarshal(body, &req); err != nil || req.JSONRPC != "2.0" || req.Method == "" || !validID(req.ID) {
				s.failure(nil, -32600, "invalid JSON-RPC request")
				continue
			}
			s.wg.Add(1)
			go func() {
				defer s.wg.Done()
				s.handle(req)
			}()
		case err := <-errors:
			if err == io.EOF {
				return nil
			}
			return err
		case <-s.done:
			return nil
		}
	}
}

func validID(id json.RawMessage) bool {
	if len(id) == 0 {
		return true
	}
	var value any
	if json.Unmarshal(id, &value) != nil {
		return false
	}
	switch value.(type) {
	case nil, string, float64:
		return true
	default:
		return false
	}
}

func decode(raw json.RawMessage, dest any) error {
	if len(raw) == 0 {
		raw = json.RawMessage("{}")
	}
	return json.Unmarshal(raw, dest)
}

func (s *Server) handle(req request) {
	if req.Method == "exit" {
		s.stop()
		return
	}
	if req.Method == "refactor/cancel" {
		s.cancelSignature(req)
		return
	}
	if req.Method == "initialize" {
		if len(req.ID) == 0 {
			return
		}
		var params struct {
			Root         string `json:"root"`
			GoExecutable string `json:"goExecutable"`
		}
		if decode(req.Params, &params) != nil || params.Root == "" {
			s.failure(req.ID, -32602, "initialize requires root")
			return
		}
		s.mu.Lock()
		if s.engine != nil || s.closing {
			s.mu.Unlock()
			s.failure(req.ID, -32600, "engine is already initialized")
			return
		}
		m, err := engine.New(params.Root, params.GoExecutable, s.notify)
		if err == nil {
			s.engine = m
			s.signatureRoot = params.Root
			s.signatureGo = params.GoExecutable
		}
		s.mu.Unlock()
		if err != nil {
			s.failure(req.ID, -32602, err.Error())
			return
		}
		info := m.Capabilities()
		info["features"].(map[string]bool)["signatureRefactoring"] = true
		info["features"].(map[string]bool)["testGeneration"] = true
		info["features"].(map[string]bool)["interfaceGeneration"] = true
		info["features"].(map[string]bool)["structTags"] = true
		info["features"].(map[string]bool)["structHelpers"] = true
		info["features"].(map[string]bool)["typeCatalog"] = true
		info["features"].(map[string]bool)["nativeRefactoring"] = true
		info["features"].(map[string]bool)["jsonGeneration"] = true
		info["features"].(map[string]bool)["taskTargets"] = true
		info["project"] = m.Project(s.ctx)
		info["tools"] = m.Tools(s.ctx)
		_ = s.result(req.ID, info)
		return
	}
	s.mu.Lock()
	m := s.engine
	s.mu.Unlock()
	if req.Method == "shutdown" {
		s.cancel()
		err := s.closeEngine()
		if len(req.ID) > 0 {
			if err != nil {
				s.failure(req.ID, -32000, err.Error())
			} else {
				_ = s.result(req.ID, nil)
			}
		}
		s.stop()
		return
	}
	if m == nil {
		if len(req.ID) > 0 {
			s.failure(req.ID, -32002, "engine is not initialized")
		}
		return
	}
	var result any
	var after func()
	var err error
	switch req.Method {
	case "refactor/native/inspect":
		var params nativerefactor.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = nativerefactor.RunIsolated(ctx, "inspect", params)
			}
		}
	case "refactor/native/plan":
		var params nativerefactor.PlanRequest
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request.Request)
			if err == nil {
				defer finish()
				result, err = nativerefactor.RunIsolated(ctx, "plan", params)
			}
		}
	case "search/types/catalog":
		var params typesearch.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = typesearch.RunIsolated(ctx, "catalog", params)
			}
		}
	case "generate/json/inspect":
		var params jsongen.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = jsongen.RunIsolated(ctx, "inspect", params)
			}
		}
	case "generate/json/plan":
		var params jsongen.PlanRequest
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request.Request)
			if err == nil {
				defer finish()
				result, err = jsongen.RunIsolated(ctx, "plan", params)
			}
		}
	case "generate/structs/inspect":
		var params structgen.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = structgen.RunIsolated(ctx, "inspect", params)
			}
		}
	case "generate/structs/plan":
		var params structgen.PlanRequest
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request.Request)
			if err == nil {
				defer finish()
				result, err = structgen.RunIsolated(ctx, "plan", params)
			}
		}
	case "generate/tags/inspect":
		var params taggen.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = taggen.RunIsolated(ctx, "inspect", params)
			}
		}
	case "generate/tags/plan":
		var params taggen.PlanRequest
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request.Request)
			if err == nil {
				defer finish()
				result, err = taggen.RunIsolated(ctx, "plan", params)
			}
		}
	case "generate/methods/inspect":
		var params methodgen.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = methodgen.RunIsolated(ctx, "inspect", params)
			}
		}
	case "generate/methods/plan":
		var params methodgen.PlanRequest
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request.Request)
			if err == nil {
				defer finish()
				result, err = methodgen.RunIsolated(ctx, "plan", params)
			}
		}
	case "generate/tests/inspect":
		var params testgen.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = testgen.RunIsolated(ctx, "inspect", params)
			}
		}
	case "generate/tests/plan":
		var params testgen.PlanRequest
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request.Request)
			if err == nil {
				defer finish()
				result, err = testgen.RunIsolated(ctx, "plan", params)
			}
		}
	case "refactor/signature/inspect":
		var params signature.Request
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params)
			if err == nil {
				defer finish()
				result, err = signature.RunIsolated(ctx, "inspect", params)
			}
		}
	case "refactor/signature/plan":
		var params signature.PlanRequest
		if err = decode(req.Params, &params); err == nil {
			var ctx context.Context
			var finish func()
			ctx, finish, err = s.signatureContext(&params.Request)
			if err == nil {
				defer finish()
				result, err = signature.RunIsolated(ctx, "plan", params)
			}
		}
	case "project/taskTargets":
		var params engine.TaskTargetsRequest
		if err = decode(req.Params, &params); err == nil {
			ctx := s.ctx
			if params.OperationID != "" {
				var finish func()
				ctx, finish, err = s.signatureContext(&signature.Request{OperationID: params.OperationID})
				if err == nil {
					defer finish()
				}
			}
			if err == nil {
				result, err = m.TaskTargets(ctx, params)
			}
		}
	case "project/info":
		result = m.Refresh(s.ctx)
	case "tools/status":
		result = m.Tools(s.ctx)
	case "profile/inspect":
		var params engine.ProfileInspectRequest
		if err = decode(req.Params, &params); err == nil {
			result, err = m.InspectProfile(s.ctx, params)
		}
	case "task/start", "task/prepare":
		if len(req.ID) == 0 {
			return
		}
		var params engine.TaskRequest
		if err = decode(req.Params, &params); err == nil {
			ctx := s.ctx
			if params.OperationID != "" {
				var finish func()
				ctx, finish, err = s.signatureContext(&signature.Request{OperationID: params.OperationID})
				if err == nil {
					defer finish()
				}
			}
			if err == nil {
				if req.Method == "task/prepare" {
					result, after, err = m.PreparePaused(ctx, params)
				} else {
					result, after, err = m.PrepareContext(ctx, params)
				}
			}
		}
	case "task/resume":
		var params struct {
			ID string `json:"id"`
		}
		if err = decode(req.Params, &params); err == nil {
			result, err = m.Resume(s.ctx, params.ID)
		}
	case "task/cancel":
		var params struct {
			ID string `json:"id"`
		}
		if err = decode(req.Params, &params); err == nil {
			if params.ID == "" {
				err = errors.New("task/cancel requires id")
			} else {
				var cancelled bool
				cancelled, err = m.Cancel(params.ID)
				result = map[string]any{"id": params.ID, "cancelled": cancelled}
			}
		}
	default:
		if len(req.ID) > 0 {
			s.failure(req.ID, -32601, "unknown method: "+req.Method)
		}
		return
	}
	if err != nil {
		if len(req.ID) > 0 {
			if errors.Is(err, context.Canceled) {
				s.failure(req.ID, -32800, err.Error())
			} else {
				s.failure(req.ID, -32602, err.Error())
			}
		}
		return
	}
	if len(req.ID) > 0 {
		if err := s.result(req.ID, result); err != nil {
			if after != nil {
				var params engine.TaskRequest
				_ = decode(req.Params, &params)
				_, _ = m.Cancel(params.ID)
			}
			return
		}
	}
	if after != nil {
		after()
	}
}

// Each analysis owns its cancellation context. Cancel never affects another
// refactoring or the engine's build/test tasks, and shutdown cancels them all.
func (s *Server) signatureContext(req *signature.Request) (context.Context, func(), error) {
	if req.OperationID == "" || len(req.OperationID) > 200 {
		return nil, nil, errors.New("signature request requires an operationId of at most 200 characters")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closing || s.ctx.Err() != nil {
		return nil, nil, errors.New("engine is shutting down")
	}
	if s.signatureCancelled[req.OperationID] {
		return nil, nil, context.Canceled
	}
	if s.signatureOps[req.OperationID] != nil {
		return nil, nil, errors.New("refactor operationId is already active")
	}
	req.Root = s.signatureRoot
	req.GoExecutable = s.signatureGo
	ctx, cancel := context.WithCancel(s.ctx)
	op := &signatureOperation{cancel: cancel}
	s.signatureOps[req.OperationID] = op
	return ctx, func() {
		cancel()
		s.mu.Lock()
		if s.signatureOps[req.OperationID] == op {
			delete(s.signatureOps, req.OperationID)
		}
		s.mu.Unlock()
	}, nil
}

// Remember cancellation even if its earlier transport request has not registered
// yet. Unique operation UUIDs plus bounded tombstones prevent a delayed start.
// Caller holds s.mu.
func (s *Server) rememberSignatureCancellation(id string) {
	if s.signatureCancelled[id] {
		return
	}
	s.signatureCancelled[id] = true
	s.signatureCancellationOrder = append(s.signatureCancellationOrder, id)
	if len(s.signatureCancellationOrder) > 512 {
		old := s.signatureCancellationOrder[0]
		s.signatureCancellationOrder = s.signatureCancellationOrder[1:]
		delete(s.signatureCancelled, old)
	}
}

func (s *Server) cancelSignature(req request) {
	var params struct {
		OperationID string `json:"operationId"`
	}
	if err := decode(req.Params, &params); err != nil || params.OperationID == "" || len(params.OperationID) > 200 {
		if len(req.ID) > 0 {
			s.failure(req.ID, -32602, "refactor/cancel requires an operationId of at most 200 characters")
		}
		return
	}
	s.mu.Lock()
	op := s.signatureOps[params.OperationID]
	s.rememberSignatureCancellation(params.OperationID)
	s.mu.Unlock()
	if op != nil {
		op.cancel()
	}
	if len(req.ID) > 0 {
		_ = s.result(req.ID, map[string]any{"operationId": params.OperationID, "cancelled": op != nil})
	}
}
