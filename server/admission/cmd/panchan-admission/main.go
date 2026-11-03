//go:build linux

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"panchan/admission"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	if run() != nil {
		fmt.Fprintln(os.Stderr, "Admission command failed; no configuration details were logged.")
		os.Exit(1)
	}
}
func run() error {
	if len(os.Args) < 2 {
		return fmt.Errorf("usage")
	}
	command := os.Args[1]
	flags := flag.NewFlagSet("admission", flag.ContinueOnError)
	socket := flags.String("socket", "/run/panchan-admission/owner.sock", "Local owner socket")
	state := flags.String("state", "/var/lib/panchan-admission/state.json", "Private state file (serve only)")
	scope := flags.Uint("scope", 1, "Invitation scope:1VK (WG/Raw)")
	ttl := flags.Int64("ttl", 3600, "Enrollment validity seconds")
	life := flags.Int64("device-lifetime", 2592000, "Approved device validity seconds")
	profileFile := flags.String("profile-file", "", "Private configuration file for share")
	name := flags.String("name", "Connection profile", "Display name for share")
	device := flags.String("device", "", "Device fingerprint to revoke")
	if e := flags.Parse(os.Args[2:]); e != nil {
		return e
	}
	if !filepath.IsAbs(*socket) || !filepath.IsAbs(*state) {
		return fmt.Errorf("absolute path required")
	}
	if command == "serve" {
		for _, dir := range []string{filepath.Dir(*state), filepath.Dir(*socket)} {
			if e := os.MkdirAll(dir, 0700); e != nil {
				return e
			}
		}
		store, e := admission.Open(*state)
		if e != nil {
			return e
		}
		defer store.Close()
		listener, e := net.ListenUnix("unix", &net.UnixAddr{Name: *socket, Net: "unix"})
		if e != nil {
			return e
		}
		defer listener.Close()
		if e = os.Chmod(*socket, 0600); e != nil {
			return e
		}
		server, serving := admission.ServeOwner(listener, store)
		ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
		defer cancel()
		fmt.Println("Admission owner socket ready; forwarding disabled.")
		select {
		case <-ctx.Done():
		case err := <-serving:
			if err != http.ErrServerClosed {
				return err
			}
		}
		stop, done := context.WithTimeout(context.Background(), 5*time.Second)
		defer done()
		return server.Shutdown(stop)
	}
	method, path := "GET", "/v1/status"
	var payload any
	switch command {
	case "status":
	case "devices":
		path = "/v1/devices"
	case "issue":
		if *scope > 3 {
			return fmt.Errorf("invalid scope")
		}
		method, path = "POST", "/v1/issue"
		payload = map[string]any{"scope": *scope, "ttlSeconds": *ttl, "deviceLifetimeSeconds": *life}
	case "share":
		if *scope != 1 && *scope != 2 {
			return fmt.Errorf("share requires one transport scope")
		}
		file, e := os.Open(*profileFile)
		if e != nil {
			return e
		}
		defer file.Close()
		info, e := file.Stat()
		if e != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
			return fmt.Errorf("private profile file required")
		}
		config, e := io.ReadAll(io.LimitReader(file, 32769))
		if e != nil || len(config) == 0 || len(config) > 32768 {
			return fmt.Errorf("invalid configuration length")
		}
		method, path = "POST", "/v1/issue-profile"
		payload = map[string]any{"scope": *scope, "ttlSeconds": *ttl, "deviceLifetimeSeconds": *life, "name": *name, "configuration": string(config)}
	case "revoke":
		method, path = "POST", "/v1/revoke"
		payload = map[string]string{"device": *device}
	default:
		return fmt.Errorf("unknown command")
	}
	var body []byte
	if payload != nil {
		body, _ = json.Marshal(payload)
	}
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", *socket)
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return fmt.Errorf("redirect denied") }}
	request, e := http.NewRequest(method, "http://unix"+path, bytes.NewReader(body))
	if e != nil {
		return e
	}
	response, e := client.Do(request)
	if e != nil {
		return e
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return fmt.Errorf("command denied")
	}
	data, e := io.ReadAll(io.LimitReader(response.Body, 2<<20))
	if e != nil {
		return e
	}
	_, e = os.Stdout.Write(data)
	return e
}
