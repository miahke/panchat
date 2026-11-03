package main

import (
	"errors"
	"net/netip"
	"strconv"
	"strings"
)

type tunnelOptions struct {
	DNS       string
	MTU       int
	Keepalive int
}

func (o tunnelOptions) dns() ([]netip.Addr, error) {
	if o.DNS == "" {
		return nil, nil
	}
	parts := strings.Split(o.DNS, ",")
	if len(parts) > 4 {
		return nil, errors.New("too many tunnel DNS addresses")
	}
	var result []netip.Addr
	for _, part := range parts {
		ip, err := netip.ParseAddr(strings.TrimSpace(part))
		if err != nil || !ip.Is4() || ip.IsUnspecified() || ip.IsMulticast() {
			return nil, errors.New("invalid tunnel DNS")
		}
		result = append(result, ip)
	}
	return result, nil
}
func (o tunnelOptions) validate() error {
	if o.MTU != 0 && (o.MTU < 576 || o.MTU > 1500) {
		return errors.New("invalid tunnel MTU")
	}
	if o.Keepalive < -1 || o.Keepalive > 120 {
		return errors.New("invalid WG keepalive")
	}
	_, err := o.dns()
	return err
}
func (o tunnelOptions) applyWg(c *wgQuickConfig) error {
	if err := o.validate(); err != nil {
		return err
	}
	dns, _ := o.dns()
	if len(dns) > 0 {
		c.dns = dns
	}
	if o.MTU != 0 {
		c.mtu = o.MTU
	}
	if o.Keepalive >= 0 {
		c.keepalive = uint16(o.Keepalive)
	}
	return nil
}
func (o tunnelOptions) rawConfig(config string) (string, error) {
	if err := o.validate(); err != nil {
		return "", err
	}
	parts := strings.Split(config, "|")
	if len(parts) != 3 {
		return "", errors.New("invalid Raw configuration")
	}
	if o.DNS != "" {
		parts[1] = o.DNS
	}
	if o.MTU != 0 {
		parts[2] = strconv.Itoa(o.MTU)
	}
	return strings.Join(parts, "|"), nil
}
