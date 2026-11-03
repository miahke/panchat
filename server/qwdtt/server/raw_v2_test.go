package main

import (
	"bytes"
	"io"
	"net"
	"testing"
	"time"
)

type recordConn struct {
	net.Conn
	frames [][]byte
	input  []byte
	queue  [][]byte
}

func (c *recordConn) Write(b []byte) (int, error) {
	c.frames = append(c.frames, append([]byte{}, b...))
	return len(b), nil
}
func (c *recordConn) Read(b []byte) (int, error) {
	if len(c.queue) > 0 {
		c.input = c.queue[0]
		c.queue = c.queue[1:]
	}
	if c.input == nil {
		return 0, io.EOF
	}
	n := copy(b, c.input)
	c.input = nil
	return n, nil
}
func TestRawV2RejectsReplayAcrossSessionsAndDirections(t *testing.T) {
	p := "synthetic-high-entropy-password"
	cn := bytes.Repeat([]byte{1}, 32)
	sn := bytes.Repeat([]byte{2}, 32)
	wire := &recordConn{}
	client, _ := rawSession(wire, p, cn, sn, true)
	client.Write([]byte("AUTH:synthetic|credential"))
	frame := wire.frames[0]
	receiver := &recordConn{input: frame}
	server, _ := rawSession(receiver, p, cn, sn, false)
	buf := make([]byte, 100)
	if _, err := server.Read(buf); err != nil {
		t.Fatal(err)
	}
	receiver.input = frame
	if _, err := server.Read(buf); err == nil {
		t.Fatal("duplicate accepted")
	}
	receiver.input = frame
	other, _ := rawSession(receiver, p, cn, bytes.Repeat([]byte{3}, 32), false)
	if _, err := other.Read(buf); err == nil {
		t.Fatal("previous session accepted")
	}
	receiver.input = frame
	reflected, _ := rawSession(receiver, p, cn, sn, true)
	if _, err := reflected.Read(buf); err == nil {
		t.Fatal("reflected frame accepted")
	}
}
func TestRawV2HandshakeAndEncryptedRecords(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	done := make(chan error, 1)
	go func() {
		c, e := rawServerHandshake(b, func(string) (string, bool) { return "synthetic-password", true })
		if e == nil {
			v, e2 := rawRead(c)
			e = e2
			if e == nil && string(v) != "synthetic-data" {
				e = io.ErrUnexpectedEOF
			}
		}
		done <- e
	}()
	c, e := rawClientHandshake(a, "synthetic-password")
	if e != nil {
		t.Fatal(e)
	}
	if _, e = c.Write([]byte("synthetic-data")); e != nil {
		t.Fatal(e)
	}
	if e = <-done; e != nil {
		t.Fatal(e)
	}
}
func TestRawV2CapturedAuthenticationCannotCompleteFreshChallenge(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	done := make(chan error, 1)
	go func() {
		_, e := rawServerHandshake(b, func(string) (string, bool) { return "synthetic-password", true })
		done <- e
	}()
	a.SetDeadline(time.Now().Add(time.Second))
	cn := bytes.Repeat([]byte{1}, 32)
	rawWrite(a, []byte("RAW2:"+wrapKeyID("synthetic-password")+":"+hexNonce(cn)))
	challenge, e := rawRead(a)
	if e != nil {
		t.Fatal(e)
	}
	oldSN := bytes.Repeat([]byte{2}, 32)
	if bytes.Equal(challenge[:32], oldSN) {
		t.Fatal("nonrandom challenge")
	}
	rawWrite(a, rawMAC("synthetic-password", []byte("client"), cn, oldSN))
	if e = <-done; e == nil {
		t.Fatal("captured proof accepted")
	}
}
func hexNonce(b []byte) string {
	const digits = "0123456789abcdef"
	s := make([]byte, len(b)*2)
	for i, v := range b {
		s[2*i] = digits[v>>4]
		s[2*i+1] = digits[v&15]
	}
	return string(s)
}

func TestRawV2AcceptsReorderingAndDropsDuplicates(t *testing.T) {
	wire := &recordConn{}
	cn, sn := bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32)
	sender, _ := rawSession(wire, "synthetic-password", cn, sn, true)
	for _, p := range []string{"one", "two", "three"} {
		if _, err := sender.Write([]byte(p)); err != nil {
			t.Fatal(err)
		}
	}
	incoming := &recordConn{queue: [][]byte{wire.frames[1], wire.frames[0], wire.frames[1], wire.frames[2]}}
	receiver, _ := rawSession(incoming, "synthetic-password", cn, sn, false)
	for _, want := range []string{"two", "one", "three"} {
		b := make([]byte, 100)
		n, err := receiver.Read(b)
		if err != nil || string(b[:n]) != want {
			t.Fatalf("got %q, %v; want %q", b[:n], err, want)
		}
	}
}

func TestRawV2DropsStaleRecordsWithoutLosingFreshTraffic(t *testing.T) {
	wire := &recordConn{}
	cn, sn := bytes.Repeat([]byte{1}, 32), bytes.Repeat([]byte{2}, 32)
	sender, _ := rawSession(wire, "synthetic-password", cn, sn, true)
	for i := 0; i < 71; i++ {
		if _, err := sender.Write([]byte{byte(i + 1)}); err != nil {
			t.Fatal(err)
		}
	}
	incoming := &recordConn{queue: [][]byte{wire.frames[69], wire.frames[0], wire.frames[70]}}
	receiver, _ := rawSession(incoming, "synthetic-password", cn, sn, false)
	for _, want := range []byte{70, 71} {
		b := make([]byte, 100)
		n, err := receiver.Read(b)
		if err != nil || n != 1 || b[0] != want {
			t.Fatal("stale record interrupted fresh traffic")
		}
	}
}
