package main

import (
	"net"
	"testing"
	"time"
)

func TestSilentPeerReadIsBounded(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	_, err := readPeerPacket(a, make([]byte, 100), 20*time.Millisecond)
	if ne, ok := err.(net.Error); !ok || !ne.Timeout() {
		t.Fatalf("expected peer silence timeout; got %v", err)
	}
}
func TestAuthenticatedPeerPacketRefreshesRead(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	go b.Write([]byte{0xff})
	packet := make([]byte, 100)
	n, err := readPeerPacket(a, packet, time.Second)
	if err != nil || n != 1 || packet[0] != 0xff {
		t.Fatalf("peer reply lost: %v", err)
	}
}
