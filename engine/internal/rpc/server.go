package rpc

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"sync"

	"github.com/shubolab/opengoland/engine/internal/engine"
	"github.com/shubolab/opengoland/engine/internal/protocol"
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
	writer  *protocol.Writer
	mu      sync.Mutex
	engine  *engine.Manager
	ctx     context.Context
	cancel  context.CancelFunc
	done    chan struct{}
	once    sync.Once
	wg      sync.WaitGroup
	closing bool
}

func New(output io.Writer) *Server {
	ctx, cancel := context.WithCancel(context.Background())
	return &Server{writer: protocol.NewWriter(output), ctx: ctx, cancel: cancel, done: make(chan struct{})}
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
		}
		s.mu.Unlock()
		if err != nil {
			s.failure(req.ID, -32602, err.Error())
			return
		}
		info := m.Capabilities()
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
	case "project/info":
		result = m.Refresh(s.ctx)
	case "tools/status":
		result = m.Tools(s.ctx)
	case "profile/inspect":
		var params engine.ProfileInspectRequest
		if err = decode(req.Params, &params); err == nil {
			result, err = m.InspectProfile(s.ctx, params)
		}
	case "task/start":
		if len(req.ID) == 0 {
			return
		}
		var params engine.TaskRequest
		if err = decode(req.Params, &params); err == nil {
			result, after, err = m.Prepare(params)
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
			s.failure(req.ID, -32602, err.Error())
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
