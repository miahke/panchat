package main

import (
	"bytes"
	"context"
	"io"
	"net/netip"
	"testing"
	"time"
)

func TestRawUserspacePacketAdapterTransfersMessageAndMediaBytes(t *testing.T) {
	client, clientNet, err := newRawStack("RAWCONF:192.0.2.2|1.1.1.1|1280")
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	server, serverNet, err := newRawStack("RAWCONF:192.0.2.3|1.1.1.1|1280")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	pump := func(from, to *packetDevice) {
		buf := make([]byte, 2048)
		for {
			n, e := from.Read(buf)
			if e != nil {
				return
			}
			if _, e = to.Write(buf[:n]); e != nil {
				return
			}
		}
	}
	go pump(client, server)
	go pump(server, client)
	listener, err := serverNet.ListenTCPAddrPort(netip.MustParseAddrPort("192.0.2.3:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, e := listener.Accept()
		if e == nil {
			defer conn.Close()
			io.Copy(conn, conn)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := clientNet.DialContext(ctx, "tcp", "192.0.2.3:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	payload := bytes.Repeat([]byte("synthetic-voice-image-data"), 4096)
	go conn.Write(payload)
	received := make([]byte, len(payload))
	if _, err = io.ReadFull(conn, received); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(payload, received) {
		t.Fatal("media changed in Raw adapter")
	}
}

func TestRawStackRefusesInvalidServerAssignedConfig(t *testing.T) {
	for _, line := range []string{"RAWCONF:invalid|1.1.1.1|1280", "RAWCONF:192.0.2.2|invalid|1280", "RAWCONF:192.0.2.2|1.1.1.1|0"} {
		if _, _, err := newRawStack(line); err == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
}

func TestRawStackProducesIPPacketsWithoutAndroidTun(t *testing.T) {
	device, stack, err := newRawStack("RAWCONF:192.0.2.2|1.1.1.1|1280")
	if err != nil {
		t.Fatal(err)
	}
	defer device.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	completed := make(chan struct{})
	go func() {
		defer close(completed)
		conn, _ := stack.DialContext(ctx, "tcp", "192.0.2.3:443")
		if conn != nil {
			conn.Close()
		}
	}()
	packet := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 2048)
		n, err := device.Read(buf)
		if err == nil {
			packet <- buf[:n]
		}
	}()
	select {
	case p := <-packet:
		if len(p) < 40 || p[0]>>4 != 4 || p[9] != 6 {
			t.Fatal("expected IPv4 TCP packet")
		}
		if p[12] != 192 || p[15] != 2 || p[16] != 192 || p[19] != 3 {
			t.Fatal("wrong IP endpoints")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("no packet emitted")
	}
	cancel()
	device.Close()
	select {
	case <-completed:
	case <-time.After(3 * time.Second):
		t.Fatal("dial did not cancel")
	}
	if _, err := device.Read(make([]byte, 2048)); err != io.EOF && err == nil {
		t.Fatal("closed packet device still readable")
	}
}
