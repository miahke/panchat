package main

import (
	"errors"
	"io"
	"net/netip"
	"strconv"
	"strings"
	"sync"

	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// packetDevice adapts userspace IP packets to the existing Raw dispatcher, without a VPN fd.
type packetDevice struct {
	dev  tun.Device
	once sync.Once
}

func (p *packetDevice) Read(buf []byte) (int, error) {
	sizes := make([]int, 1)
	n, err := p.dev.Read([][]byte{buf}, sizes, 0)
	if err != nil {
		return 0, err
	}
	if n != 1 {
		return 0, io.ErrNoProgress
	}
	return sizes[0], nil
}
func (p *packetDevice) Write(buf []byte) (int, error) {
	if len(buf) == 0 || buf[0]>>4 != 4 {
		return 0, errors.New("invalid Raw packet")
	}
	n, err := p.dev.Write([][]byte{buf}, 0)
	if err != nil {
		return 0, err
	}
	if n != 1 {
		return 0, io.ErrShortWrite
	}
	return len(buf), nil
}
func (p *packetDevice) Close() (err error) { p.once.Do(func() { err = p.dev.Close() }); return }

func newRawStack(config string) (*packetDevice, *netstack.Net, error) {
	if !strings.HasPrefix(config, "RAWCONF:") || len(config) > 4096 {
		return nil, nil, errors.New("invalid Raw configuration")
	}
	parts := strings.Split(strings.TrimPrefix(config, "RAWCONF:"), "|")
	if len(parts) != 3 {
		return nil, nil, errors.New("invalid Raw configuration")
	}
	address, err := netip.ParseAddr(parts[0])
	if err != nil || !address.Is4() || address.IsUnspecified() || address.IsMulticast() {
		return nil, nil, errors.New("invalid Raw address")
	}
	mtu, err := strconv.Atoi(parts[2])
	if err != nil || mtu < 576 || mtu > 1500 {
		return nil, nil, errors.New("invalid Raw MTU")
	}
	var dns []netip.Addr
	for _, item := range strings.Split(parts[1], ",") {
		ip, err := netip.ParseAddr(strings.TrimSpace(item))
		if err != nil || !ip.Is4() || ip.IsUnspecified() || ip.IsMulticast() {
			return nil, nil, errors.New("invalid Raw DNS")
		}
		dns = append(dns, ip)
	}
	dev, stack, err := netstack.CreateNetTUN([]netip.Addr{address}, dns, mtu)
	if err != nil {
		return nil, nil, errors.New("could not start Raw stack")
	}
	return &packetDevice{dev: dev}, stack, nil
}
