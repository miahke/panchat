package main

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
)

const (
	peerDTLS int32 = 1
	peerRTP  int32 = 2
)

type peerSelection struct {
	once sync.Once
	done chan struct{}
	mode atomic.Int32
}

func (s *peerSelection) signal() <-chan struct{} {
	s.once.Do(func() { s.done = make(chan struct{}) })
	return s.done
}
func (s *peerSelection) choose(mode int32) bool {
	s.signal()
	if s.mode.CompareAndSwap(0, mode) {
		close(s.done)
	}
	return s.mode.Load() == mode
}
func (tp *TurnParams) peerContext(parent context.Context, id int, peer *net.UDPAddr) (context.Context, *net.UDPAddr, bool, int32, func()) {
	if !tp.AutoPeer || tp.RawMode || tp.NoDTLS {
		return parent, peer, tp.NoDTLS, 0, func() {}
	}
	signal := tp.selection.signal()
	mode := tp.selection.mode.Load()
	if mode == 0 {
		mode = peerDTLS
		if id%2 == 0 {
			mode = peerRTP
		}
	}
	chosen := *peer
	if mode == peerRTP {
		chosen.Port = tp.FallbackPort
		if chosen.Port == 0 {
			chosen.Port = 56002
		}
	}
	ctx, cancel := context.WithCancel(parent)
	go func() {
		select {
		case <-ctx.Done():
		case <-signal:
			if tp.selection.mode.Load() != mode {
				cancel()
			}
		}
	}()
	return ctx, &chosen, mode == peerRTP, mode, cancel
}
