package admission

import (
	"bytes"
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestOwnerAPIRejectsUnprivilegedAndUnknownPayload(t *testing.T) {
	s := openTest(t)
	h := ownerHandler(s)
	call := func(uid uint32, path, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, path, bytes.NewBufferString(body))
		r = r.WithContext(withOwnerUID(r.Context(), uid))
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	if w := call(1000, "/v1/issue", `{"scope":1,"ttlSeconds":60,"deviceLifetimeSeconds":3600}`); w.Code != 403 {
		t.Fatal("non-owner allowed")
	}
	if w := call(0, "/v1/issue", `{"scope":2,"ttlSeconds":60,"deviceLifetimeSeconds":3600}`); w.Code != 400 {
		t.Fatal("removed transport accepted")
	}
	if w := call(0, "/v1/issue", `{"scope":1,"ttlSeconds":60,"deviceLifetimeSeconds":3600,"unknown":true}`); w.Code != 400 {
		t.Fatal("unknown field")
	}
	if w := call(0, "/v1/issue", `{"scope":1,"ttlSeconds":9223372036854775807,"deviceLifetimeSeconds":3600}`); w.Code != 400 {
		t.Fatal("overflow ttl")
	}
	w := call(0, "/v1/issue", `{"scope":1,"ttlSeconds":60,"deviceLifetimeSeconds":3600}`)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	var result map[string]string
	if json.Unmarshal(w.Body.Bytes(), &result) != nil || len(result["invitation"]) != 43 {
		t.Fatal("invalid token response")
	}
	r := httptest.NewRequest(http.MethodGet, "/v1/status", nil)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("missing peer credentials allowed")
	}
}

func TestOwnerSocketUsesKernelCredentials(t *testing.T) {
	s := openTest(t)
	path := filepath.Join(secureDir(t), "owner.sock")
	listener, e := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if e != nil {
		t.Fatal(e)
	}
	server, _ := ServeOwner(listener, s)
	defer server.Close()
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", path)
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: time.Second}
	response, e := client.Get("http://unix/v1/status")
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	expected := 403
	if os.Getuid() == 0 {
		expected = 200
	}
	if response.StatusCode != expected {
		t.Fatal("kernel peer authorization failed")
	}
}
func TestOwnerCanIssueSignedProfile(t *testing.T) {
	s := openTest(t)
	body := `{"scope":1,"ttlSeconds":600,"deviceLifetimeSeconds":3600,"name":"Synthetic exit","configuration":"qwdtt://config?peer=192.0.2.1&pass=synthetic-profile"}`
	r := httptest.NewRequest(http.MethodPost, "/v1/issue-profile", bytes.NewBufferString(body))
	r = r.WithContext(withOwnerUID(r.Context(), 0))
	w := httptest.NewRecorder()
	ownerHandler(s).ServeHTTP(w, r)
	if w.Code != 200 {
		t.Fatalf("status=%d", w.Code)
	}
	var result map[string]string
	if json.Unmarshal(w.Body.Bytes(), &result) != nil {
		t.Fatal("invalid response")
	}
	if _, e := VerifyInvite(result["link"], fingerprint(s.PublicKey()), time.Now().Unix()); e != nil {
		t.Fatal("invalid signed profile")
	}
}
