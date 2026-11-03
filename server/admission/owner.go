//go:build linux

package admission

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"syscall"
	"time"
)

type ownerUIDKey struct{}

func withOwnerUID(ctx context.Context, uid uint32) context.Context {
	return context.WithValue(ctx, ownerUIDKey{}, uid)
}
func ownerHandler(s *Service) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Type", "application/json")
		uid, ok := r.Context().Value(ownerUIDKey{}).(uint32)
		if !ok || uid != 0 {
			http.Error(w, "owner authorization required", 403)
			return
		}
		respond := func(v any) { json.NewEncoder(w).Encode(v) }
		decode := func(v any) bool {
			r.Body = http.MaxBytesReader(w, r.Body, 65536)
			d := json.NewDecoder(r.Body)
			d.DisallowUnknownFields()
			return d.Decode(v) == nil && d.Decode(new(any)) == io.EOF
		}
		switch r.URL.Path {
		case "/v1/status":
			if r.Method != "GET" {
				w.WriteHeader(405)
				return
			}
			s.mu.Lock()
			defer s.mu.Unlock()
			if s.sealed {
				w.WriteHeader(503)
				return
			}
			respond(map[string]any{"version": 1, "issuerFingerprint": fingerprint(s.public), "issuerPublic": base64.RawURLEncoding.EncodeToString(s.public), "devices": len(s.data.Devices), "invitations": len(s.data.Invitations), "liveLeases": len(s.leases)})
		case "/v1/devices":
			if r.Method != "GET" {
				w.WriteHeader(405)
				return
			}
			s.mu.Lock()
			defer s.mu.Unlock()
			if s.sealed {
				w.WriteHeader(503)
				return
			}
			items := make([]map[string]any, 0, len(s.data.Devices))
			for id, d := range s.data.Devices {
				items = append(items, map[string]any{"fingerprint": id, "scope": d.Scope, "expires": d.Expires, "revoked": d.Revoked, "hardwareAssurance": "not-verified"})
			}
			respond(items)
		case "/v1/issue":
			if r.Method != "POST" {
				w.WriteHeader(405)
				return
			}
			var p struct {
				Scope                 Scope `json:"scope"`
				TTLSeconds            int64 `json:"ttlSeconds"`
				DeviceLifetimeSeconds int64 `json:"deviceLifetimeSeconds"`
			}
			if !decode(&p) || p.Scope != VK || p.TTLSeconds < 1 || p.TTLSeconds > 604800 || p.DeviceLifetimeSeconds < 1 || p.DeviceLifetimeSeconds > 31536000 {
				w.WriteHeader(400)
				return
			}
			token, e := s.Issue(p.Scope, time.Duration(p.TTLSeconds)*time.Second, time.Duration(p.DeviceLifetimeSeconds)*time.Second)
			if e != nil {
				w.WriteHeader(400)
				return
			}
			respond(map[string]string{"invitation": token})
		case "/v1/issue-profile":
			if r.Method != "POST" {
				w.WriteHeader(405)
				return
			}
			var p struct {
				Scope                 Scope  `json:"scope"`
				TTLSeconds            int64  `json:"ttlSeconds"`
				DeviceLifetimeSeconds int64  `json:"deviceLifetimeSeconds"`
				Name                  string `json:"name"`
				Configuration         string `json:"configuration"`
			}
			if !decode(&p) || p.Scope != VK || !validProfile(p.Scope, p.Name, p.Configuration) || p.TTLSeconds < 1 || p.TTLSeconds > 604800 || p.DeviceLifetimeSeconds < 1 || p.DeviceLifetimeSeconds > 31536000 {
				w.WriteHeader(400)
				return
			}
			token, e := s.Issue(p.Scope, time.Duration(p.TTLSeconds)*time.Second, time.Duration(p.DeviceLifetimeSeconds)*time.Second)
			if e != nil {
				w.WriteHeader(503)
				return
			}
			link, e := s.InviteLink(token, p.Scope, p.Name, p.Configuration)
			if e != nil {
				w.WriteHeader(503)
				return
			}
			respond(map[string]string{"link": link})
		case "/v1/revoke":
			if r.Method != "POST" {
				w.WriteHeader(405)
				return
			}
			var p struct {
				Device string `json:"device"`
			}
			if !decode(&p) || !validHash(p.Device) {
				w.WriteHeader(400)
				return
			}
			if s.Revoke(p.Device) != nil {
				w.WriteHeader(400)
				return
			}
			respond(map[string]bool{"revoked": true})
		default:
			w.WriteHeader(404)
		}
	})
}

// ServeOwner permits only the kernel-reported uid0 peer. This socket must never be
// proxied to TCP; transport admission uses a separate protocol boundary.
func ServeOwner(listener *net.UnixListener, s *Service) (*http.Server, <-chan error) {
	server := &http.Server{Handler: ownerHandler(s), ReadHeaderTimeout: 3 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 10 * time.Second, MaxHeaderBytes: 8192, ErrorLog: log.New(io.Discard, "", 0)}
	server.ConnContext = func(ctx context.Context, c net.Conn) context.Context {
		u, ok := c.(*net.UnixConn)
		if !ok {
			return ctx
		}
		raw, e := u.SyscallConn()
		if e != nil {
			return ctx
		}
		var credentials *syscall.Ucred
		var err error
		if e = raw.Control(func(fd uintptr) {
			credentials, err = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
		}); e != nil || err != nil || credentials == nil {
			return ctx
		}
		return withOwnerUID(ctx, credentials.Uid)
	}
	result := make(chan error, 1)
	go func() { result <- server.Serve(listener) }()
	return server, result
}
