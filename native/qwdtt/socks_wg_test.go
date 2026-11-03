package main

import (
	"encoding/base64"
	"strings"
	"testing"
)

func syntheticWgConfig(endpoint string) string {
	key := base64.StdEncoding.EncodeToString([]byte(strings.Repeat("s", 32)))
	return "[Interface]\nPrivateKey = " + key + "\nAddress = 10.31.0.2/32\nDNS = 1.1.1.1\n[Peer]\nPublicKey = " + key + "\nAllowedIPs = 0.0.0.0/0\nEndpoint = " + endpoint + "\n"
}
func TestWgRejectsEndpointsThatCouldBypassTurn(t *testing.T) {
	for _, endpoint := range []string{"192.0.2.1:56001", "server.example.invalid:56001", "[::1]:9000", "127.0.0.2:9000", "127.0.0.1:0"} {
		if _, err := parseWgQuick(syntheticWgConfig(endpoint)); err == nil {
			t.Errorf("non-dispatcher endpoint accepted: %s", endpoint)
		}
	}
	if _, err := parseWgQuick(syntheticWgConfig("127.0.0.1:19000")); err != nil {
		t.Fatal(err)
	}
}
