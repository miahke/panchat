package main

import (
	"bytes"
	"context"
	"io"
	"net"
	"path/filepath"
	"testing"
	"time"
)

type ownershipConn struct {
	first []byte
	out   bytes.Buffer
}

func (c *ownershipConn) Read(b []byte) (int, error) {
	if c.first == nil {
		return 0, io.EOF
	}
	n := copy(b, c.first)
	c.first = nil
	return n, nil
}
func (c *ownershipConn) Write(b []byte) (int, error)      { return c.out.Write(b) }
func (c *ownershipConn) Close() error                     { return nil }
func (c *ownershipConn) LocalAddr() net.Addr              { return &net.UDPAddr{Port: 12340} }
func (c *ownershipConn) RemoteAddr() net.Addr             { return &net.UDPAddr{Port: 12341} }
func (c *ownershipConn) SetDeadline(time.Time) error      { return nil }
func (c *ownershipConn) SetReadDeadline(time.Time) error  { return nil }
func (c *ownershipConn) SetWriteDeadline(time.Time) error { return nil }
func TestWGHandlersDenyOtherCredentialBeforeMutation(t *testing.T) {
	for _, command := range []string{"GETCONF:1234|victim|attacker", "AUTH:victim|attacker"} {
		t.Run(command[:4], func(t *testing.T) {
			db = &Database{MainPassword: "main", Passwords: map[string]*PasswordEntry{"attacker": {MaxDevices: 5}}, Devices: map[string]*ClientDevice{"victim": {DeviceID: "victim", PrivKey: "synthetic-private", OwnerID: wrapKeyID("owner")}}}
			dbFile = filepath.Join(t.TempDir(), "db.json")
			c := &ownershipConn{first: []byte(command)}
			id := wrapConnectionBindingID(c.LocalAddr(), c.RemoteAddr())
			wrapCredentialBindings.Store(id, "pass:"+wrapKeyID("attacker"))
			defer wrapCredentialBindings.Delete(id)
			handleConn(context.Background(), c, "", nil, nil)
			if c.out.String() != "DENIED:device_mismatch" {
				t.Fatalf("unexpected response: %q", c.out.String())
			}
			if db.Devices["victim"].OwnerID != wrapKeyID("owner") || passwordEntryHasDevice(db.Passwords["attacker"], "victim") {
				t.Fatal("ownership or bindings changed")
			}
		})
	}
}

func TestRawHandlersDenyOtherCredentialBeforeRegistration(t *testing.T) {
	for _, command := range []string{"GETCONF_RAW:victim|attacker", "AUTH:victim|attacker"} {
		t.Run(command[:4], func(t *testing.T) {
			db = &Database{MainPassword: "main", Passwords: map[string]*PasswordEntry{"attacker": {DeviceIDs: []string{"victim"}, MaxDevices: 5}}, Devices: map[string]*ClientDevice{"victim": {DeviceID: "victim", RawIP: "10.90.0.2", OwnerID: wrapKeyID("owner"), RawOwnerID: wrapKeyID("owner")}}}
			dbFile = filepath.Join(t.TempDir(), "db.json")
			a, b := net.Pipe()
			defer a.Close()
			defer b.Close()
			id := wrapConnectionBindingID(b.LocalAddr(), b.RemoteAddr())
			wrapCredentialBindings.Store(id, "pass:"+wrapKeyID("attacker"))
			defer wrapCredentialBindings.Delete(id)
			done := make(chan struct{})
			go func() { defer close(done); defer b.Close(); handleConnRaw(context.Background(), b, nil) }()
			c, err := rawClientHandshake(a, "attacker")
			if err != nil {
				t.Fatal(err)
			}
			c.SetDeadline(time.Now().Add(time.Second))
			if _, err = c.Write([]byte(command)); err != nil {
				t.Fatal(err)
			}
			response, err := rawRead(c)
			if err != nil {
				t.Fatal(err)
			}
			if string(response) != "DENIED:device_mismatch" {
				t.Fatalf("unexpected response: %q", response)
			}
			<-done
			if db.Devices["victim"].OwnerID != wrapKeyID("owner") || db.Devices["victim"].RawOwnerID != wrapKeyID("owner") {
				t.Fatal("ownership changed")
			}
		})
	}
}
