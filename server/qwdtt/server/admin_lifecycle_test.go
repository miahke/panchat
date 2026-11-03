package main

import (
    "net"
    "net/http"
    "net/http/httptest"
    "net/url"
    "path/filepath"
    "strings"
    "testing"
    "time"
)

func TestAdminSubscriptionLifecycleRevokesActiveConnection(t *testing.T) {
    previousDB, previousFile, previousKeys := db, dbFile, serverWrapKeys
    db = &Database{Passwords: map[string]*PasswordEntry{}, Devices: map[string]*ClientDevice{}}
    dbFile = filepath.Join(t.TempDir(), "passwords.json")
    serverWrapKeys = newWrapKeyStore()
    setAdminAPIToken("synthetic-admin-token")
    t.Cleanup(func() { db, dbFile, serverWrapKeys = previousDB, previousFile, previousKeys; setAdminAPIToken("") })
    mux := http.NewServeMux()
    registerAdminAPIRoutes(mux)
    call := func(path string, fields url.Values, authorized bool) *httptest.ResponseRecorder {
        req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(fields.Encode()))
        req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
        if authorized { req.Header.Set("Authorization", "Bearer synthetic-admin-token") }
        response := httptest.NewRecorder(); mux.ServeHTTP(response, req); return response
    }
    fields := url.Values{"vk_hash":{"synthetic-hash"},"days":{"1"},"max_devices":{"1"}}
    if response := call("/admin/passwords", fields, false); response.Code != http.StatusUnauthorized { t.Fatal("unauthorized issuance accepted") }
    if len(db.Passwords) != 0 { t.Fatal("unauthorized issuance mutated database") }
    response := call("/admin/passwords", fields, true)
    if response.Code != http.StatusOK { t.Fatal("issuance failed") }
    var password string
    for key := range db.Passwords { password = key }
    if password == "" || serverWrapKeys.Count() != 1 { t.Fatal("credential was not installed") }
    client, server := net.Pipe(); defer client.Close(); defer server.Close()
    unregister := trackCredentialConnection(password, "synthetic-device", server); defer unregister()
    response = call("/admin/passwords/deactivate", url.Values{"password":{password}}, true)
    if response.Code != http.StatusOK || !db.Passwords[password].IsDeactivated || serverWrapKeys.Count() != 0 { t.Fatal("revocation not applied") }
    client.SetReadDeadline(time.Now().Add(time.Second))
    if _, err := client.Read(make([]byte,1)); err == nil { t.Fatal("revoked stream still open") } else if timeout, ok := err.(net.Error); ok && timeout.Timeout() { t.Fatal("revoked stream was not closed") }
    if response = call("/admin/passwords/activate", url.Values{"password":{password}}, true); response.Code != http.StatusOK || serverWrapKeys.Count() != 1 { t.Fatal("reactivation failed") }
    if response = call("/admin/passwords/delete", url.Values{"password":{password}}, true); response.Code != http.StatusOK || len(db.Passwords) != 0 || serverWrapKeys.Count() != 0 { t.Fatal("deletion failed") }
}
