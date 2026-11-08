package protocol

import (
	"bytes"
	"fmt"
	"io"
	"strings"
	"sync"
	"testing"
)

func TestUTF8ByteLengthAndConsecutiveFrames(t *testing.T) {
	var transport bytes.Buffer
	w := NewWriter(&transport)
	for _, value := range []string{"你好，😀", "second"} {
		if err := w.Write(map[string]string{"text": value}); err != nil {
			t.Fatal(err)
		}
	}
	r := NewReader(&transport)
	first, err := r.Read()
	if err != nil || !bytes.Contains(first, []byte("你好，😀")) {
		t.Fatalf("first frame: %s %v", first, err)
	}
	second, err := r.Read()
	if err != nil || !bytes.Contains(second, []byte("second")) {
		t.Fatalf("second frame: %s %v", second, err)
	}
	if _, err := r.Read(); err != io.EOF {
		t.Fatalf("after frames: %v", err)
	}
}

func TestRejectInvalidAndTruncatedFrames(t *testing.T) {
	for _, input := range []string{
		"Content-Length: -1\r\n\r\n",
		fmt.Sprintf("Content-Length: %d\r\n\r\n", MaxMessageBytes+1),
		"Content-Length: 1\r\nContent-Length: 1\r\n\r\nx",
		"Other: x\r\n\r\n",
		"Content-Length: 2\r\n\r\nx",
		"Content-Length: 2\r\n",
	} {
		t.Run(input, func(t *testing.T) {
			if _, err := NewReader(strings.NewReader(input)).Read(); err == nil {
				t.Fatal("invalid frame was accepted")
			}
		})
	}
}

func TestConcurrentWritesRemainFramed(t *testing.T) {
	var b bytes.Buffer
	w := NewWriter(&b)
	var wg sync.WaitGroup
	for n := 0; n < 50; n++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			if err := w.Write(map[string]int{"n": n}); err != nil {
				t.Error(err)
			}
		}(n)
	}
	wg.Wait()
	r := NewReader(&b)
	for n := 0; n < 50; n++ {
		if _, err := r.Read(); err != nil {
			t.Fatalf("message %d: %v", n, err)
		}
	}
}
