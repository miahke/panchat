package main

import (
	"golang.zx2c4.com/wireguard/conn"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestLoopbackBindRejectsRoamingAndOnlyReceivesFromDispatcher(t *testing.T) {
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	target := peer.LocalAddr().(*net.UDPAddr).AddrPort()
	b := &loopbackBind{peer: target}
	readers, port, err := b.Open(0)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if b.socket.LocalAddr().(*net.UDPAddr).IP.String() != "127.0.0.1" {
		t.Fatal("public bind")
	}
	ep, err := b.ParseEndpoint(target.String())
	if err != nil {
		t.Fatal(err)
	}
	if err = b.Send([][]byte{[]byte("synthetic")}, ep); err != nil {
		t.Fatal(err)
	}
	peer.SetReadDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 100)
	n, source, err := peer.ReadFromUDP(buf)
	if err != nil || string(buf[:n]) != "synthetic" {
		t.Fatal("send failed", err)
	}
	if source.Port != int(port) {
		t.Fatal("wrong bind port")
	}
	for _, address := range []string{"192.0.2.1:9000", "outside.example.invalid:9000", "127.0.0.1:1"} {
		if _, err = b.ParseEndpoint(address); err == nil {
			t.Fatal("roaming accepted")
		}
	}
	if err = b.Send([][]byte{[]byte("forbidden")}, &conn.StdNetEndpoint{AddrPort: netip.MustParseAddrPort("192.0.2.1:9000")}); err == nil {
		t.Fatal("public send accepted")
	}
	peer.WriteToUDP([]byte("reply"), source)
	packets := [][]byte{make([]byte, 100)}
	sizes := make([]int, 1)
	eps := make([]conn.Endpoint, 1)
	if n, err = readers[0](packets, sizes, eps); err != nil || n != 1 || string(packets[0][:sizes[0]]) != "reply" {
		t.Fatal("receive failed", err)
	}
	b.Close()
	if _, err = readers[0](packets, sizes, eps); err == nil {
		t.Fatal("read did not close")
	}
}
