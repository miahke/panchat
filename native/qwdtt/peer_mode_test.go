package main

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestAutomaticPeerSelectionUsesTwoEncryptedCandidatesAndCancelsLoser(t *testing.T) {
	tp := &TurnParams{AutoPeer: true, FallbackPort: 56002}
	peer := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 56000}
	first, a, direct, mode, closeA := tp.peerContext(context.Background(), 1, peer)
	defer closeA()
	if direct || mode != peerDTLS || a.Port != 56000 {
		t.Fatal("wrong primary candidate")
	}
	second, b, direct, mode, closeB := tp.peerContext(context.Background(), 2, peer)
	defer closeB()
	if !direct || mode != peerRTP || b.Port != 56002 {
		t.Fatal("wrong masked candidate")
	}
	if !tp.selection.choose(peerRTP) {
		t.Fatal("selection failed")
	}
	select {
	case <-first.Done():
	case <-time.After(time.Second):
		t.Fatal("losing candidate not cancelled")
	}
	if second.Err() != nil {
		t.Fatal("winner cancelled")
	}
	if tp.selection.choose(peerDTLS) {
		t.Fatal("selection changed without new session")
	}
	_, next, direct, _, closeNext := tp.peerContext(context.Background(), 1, peer)
	defer closeNext()
	if !direct || next.Port != 56002 {
		t.Fatal("retry ignored selected mode")
	}
	if peer.Port != 56000 {
		t.Fatal("original endpoint mutated")
	}
}
