package main

import (
	"errors"
	"golang.zx2c4.com/wireguard/conn"
	"net"
	"net/netip"
	"sync"
)

var dispatcherIP = netip.MustParseAddr("127.0.0.1")

func dispatcherEndpoint(text string) (netip.AddrPort, error) {
	endpoint, err := netip.ParseAddrPort(text)
	if err != nil || endpoint.Addr() != dispatcherIP || endpoint.Port() == 0 {
		return netip.AddrPort{}, errors.New("WireGuard endpoint must be the local TURN dispatcher")
	}
	return endpoint, nil
}

// A WireGuard peer cannot roam to a public endpoint or trigger external DNS.
// Only the exact dispatcher socket may exchange encrypted WG datagrams.
type loopbackBind struct {
	mu     sync.Mutex
	socket *net.UDPConn
	peer   netip.AddrPort
}

var _ conn.Bind = (*loopbackBind)(nil)

func (b *loopbackBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.socket != nil {
		return nil, 0, errors.New("WireGuard loopback bind is already open")
	}
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)})
	if err != nil {
		return nil, 0, err
	}
	b.socket = c
	receive := func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
		for {
			n, source, err := c.ReadFromUDPAddrPort(packets[0])
			if err != nil {
				return 0, err
			}
			if source.Addr().Unmap() != b.peer.Addr() || source.Port() != b.peer.Port() {
				continue
			}
			sizes[0] = n
			endpoints[0] = &conn.StdNetEndpoint{AddrPort: b.peer}
			return 1, nil
		}
	}
	return []conn.ReceiveFunc{receive}, uint16(c.LocalAddr().(*net.UDPAddr).Port), nil
}
func (b *loopbackBind) Close() error {
	b.mu.Lock()
	c := b.socket
	b.socket = nil
	b.mu.Unlock()
	if c != nil {
		return c.Close()
	}
	return nil
}
func (b *loopbackBind) SetMark(mark uint32) error {
	if mark != 0 {
		return errors.New("packet marks are not supported by the local dispatcher")
	}
	return nil
}
func (b *loopbackBind) BatchSize() int { return 1 }
func (b *loopbackBind) ParseEndpoint(text string) (conn.Endpoint, error) {
	endpoint, err := dispatcherEndpoint(text)
	if err != nil || endpoint != b.peer {
		return nil, errors.New("WireGuard endpoint cannot bypass the local TURN dispatcher")
	}
	return &conn.StdNetEndpoint{AddrPort: endpoint}, nil
}
func (b *loopbackBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	ep, ok := endpoint.(*conn.StdNetEndpoint)
	if !ok || ep.AddrPort != b.peer {
		return errors.New("WireGuard destination is not the local dispatcher")
	}
	b.mu.Lock()
	c := b.socket
	b.mu.Unlock()
	if c == nil {
		return net.ErrClosed
	}
	for _, packet := range packets {
		if _, err := c.WriteToUDPAddrPort(packet, b.peer); err != nil {
			return err
		}
	}
	return nil
}
