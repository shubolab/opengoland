package protocol

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"sync"
)

const MaxMessageBytes = 16 << 20

// Reader accepts the Content-Length framing used by LSP and this engine.
type Reader struct{ r *bufio.Reader }

func NewReader(r io.Reader) *Reader { return &Reader{r: bufio.NewReaderSize(r, 8192)} }

func (r *Reader) Read() ([]byte, error) {
	length := -1
	for headerBytes := 0; ; {
		lineBytes, err := r.r.ReadSlice('\n')
		if err != nil {
			if errors.Is(err, bufio.ErrBufferFull) {
				return nil, errors.New("protocol header line exceeds 8192 bytes")
			}
			if errors.Is(err, io.EOF) && headerBytes != 0 {
				return nil, io.ErrUnexpectedEOF
			}
			return nil, err
		}
		headerBytes += len(lineBytes)
		if headerBytes > 8192 {
			return nil, errors.New("protocol header exceeds 8192 bytes")
		}
		line := strings.TrimSuffix(strings.TrimSuffix(string(lineBytes), "\n"), "\r")
		if line == "" {
			break
		}
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			return nil, errors.New("malformed protocol header")
		}
		if strings.EqualFold(strings.TrimSpace(name), "Content-Length") {
			if length != -1 {
				return nil, errors.New("duplicate Content-Length header")
			}
			length, err = strconv.Atoi(strings.TrimSpace(value))
			if err != nil || length < 0 || length > MaxMessageBytes {
				return nil, errors.New("invalid or oversized Content-Length")
			}
		}
	}
	if length < 0 {
		return nil, errors.New("missing Content-Length header")
	}
	body := make([]byte, length)
	_, err := io.ReadFull(r.r, body)
	return body, err
}

// Writer serializes whole messages so responses and asynchronous events cannot interleave.
type Writer struct {
	mu sync.Mutex
	w  io.Writer
}

func NewWriter(w io.Writer) *Writer { return &Writer{w: w} }

func (w *Writer) Write(message any) error {
	body, err := json.Marshal(message)
	if err != nil {
		return err
	}
	if len(body) > MaxMessageBytes {
		return errors.New("outgoing protocol message exceeds limit")
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if _, err = fmt.Fprintf(w.w, "Content-Length: %d\r\n\r\n", len(body)); err != nil {
		return err
	}
	_, err = w.w.Write(body)
	return err
}
