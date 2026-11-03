package main

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

func TestRawV2EncryptedSocksTransfersMedia(t *testing.T) {
	client, clientNet, err := newRawStack("RAWCONF:192.0.2.2|192.0.2.3|1280")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	server, serverNet, err := newRawStack("RAWCONF:192.0.2.3|192.0.2.3|1280")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	serverSecure := make(chan net.Conn, 1)
	go func() {
		c, e := rawServerHandshake(b, func(string) (string, bool) { return "synthetic-password", true })
		if e == nil {
			serverSecure <- c
		} else {
			serverSecure <- nil
		}
	}()
	clientConn, err := rawClientHandshake(a, "synthetic-password")
	if err != nil {
		t.Fatal(err)
	}
	serverConn := <-serverSecure
	if serverConn == nil {
		t.Fatal("server handshake failed")
	}
	pump := func(device *packetDevice, secure net.Conn) {
		go func() {
			buf := make([]byte, 2048)
			for {
				n, e := device.Read(buf)
				if e != nil {
					return
				}
				if _, e = secure.Write(buf[:n]); e != nil {
					return
				}
			}
		}()
		go func() {
			buf := make([]byte, 2048)
			for {
				n, e := secure.Read(buf)
				if e != nil {
					return
				}
				if _, e = device.Write(buf[:n]); e != nil {
					return
				}
			}
		}()
	}
	pump(client, clientConn)
	pump(server, serverConn)
	echo, err := serverNet.ListenTCPAddrPort(netip.MustParseAddrPort("192.0.2.3:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		c, e := echo.Accept()
		if e == nil {
			defer c.Close()
			io.Copy(c, c)
		}
	}()
	local, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := local.Addr().String()
	local.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- runSocks5Server(ctx, address, clientNet, true, "synthetic-user", "synthetic-local-password")
	}()
	defer func() {
		cancel()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("proxy did not stop")
		}
	}()
	deadline := time.NewTimer(3 * time.Second)
	defer deadline.Stop()
	poll := time.NewTicker(time.Millisecond * 10)
	defer poll.Stop()
	for {
		c, e := net.DialTimeout("tcp", address, time.Millisecond*50)
		if e == nil {
			c.Close()
			break
		}
		select {
		case <-deadline.C:
			t.Fatal("SOCKS did not bind")
		case <-poll.C:
		}
	}
	for _, password := range []string{"wrong-password", "synthetic-local-password"} {
		dialer, e := proxy.SOCKS5("tcp", address, &proxy.Auth{User: "synthetic-user", Password: password}, &net.Dialer{Timeout: time.Second})
		if e != nil {
			t.Fatal(e)
		}
		c, e := dialer.Dial("tcp", "192.0.2.3:443")
		if password == "wrong-password" {
			if e == nil {
				c.Close()
				t.Fatal("invalid proxy password accepted")
			}
			continue
		}
		if e != nil {
			t.Fatal(e)
		}
		c.SetDeadline(time.Now().Add(5 * time.Second))
		payload := bytes.Repeat([]byte("synthetic-text-voice-image"), 4096)
		go c.Write(payload)
		got := make([]byte, len(payload))
		_, e = io.ReadFull(c, got)
		c.Close()
		if e != nil || !bytes.Equal(payload, got) {
			t.Fatalf("encrypted Raw SOCKS transfer failed: %v", e)
		}
	}
}
