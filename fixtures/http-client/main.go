package main

import (
	"context"
	"encoding/json"
	"flag"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"time"
)

func main() {
	address := flag.String("listen", "127.0.0.1:18081", "local demo address")
	flag.Parse()
	mux := http.NewServeMux()
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(io.LimitReader(r.Body, 1024*1024+1))
		if err != nil || len(body) > 1024*1024 {
			http.Error(w, "request body too large or unreadable", http.StatusBadRequest)
			return
		}
		log.Printf("echo: method=%s bytes=%d", r.Method, len(body))
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		result := map[string]string{"method": r.Method, "message": "你好，open Goland", "body": string(body)}
		if probe := r.Header.Get("X-Probe"); probe != "" {
			result["probe"] = probe
		}
		json.NewEncoder(w).Encode(result)
	})
	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		log.Print("slow: request received")
		select {
		case <-r.Context().Done():
			log.Print("slow: client disconnected")
		case <-time.After(25 * time.Second):
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			io.WriteString(w, "finished after 25 seconds\n")
		}
	})
	mux.HandleFunc("/redirect", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/echo", http.StatusFound)
	})
	server := &http.Server{Addr: *address, Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		server.Shutdown(shutdown)
	}()
	log.Printf("HTTP example listening on http://%s", *address)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
