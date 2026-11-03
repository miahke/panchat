package main

import "testing"

func TestProfileAPIIsDisabledUnlessExplicitlyEnabledOnLoopback(t *testing.T) {
	server, err := profileServer("")
	if err != nil || server != nil {
		t.Fatal("profile management must be disabled by default")
	}
	for _, address := range []string{"0.0.0.0:56001", "192.0.2.1:56001", "[::]:56001"} {
		if _, err := profileServer(address); err == nil {
			t.Fatal("public plaintext management listener accepted")
		}
	}
	server, err = profileServer("127.0.0.1:56010")
	if err != nil || server == nil {
		t.Fatal("explicit local management listener rejected")
	}
}

func TestDefaultInternalWireGuardPortDoesNotOverlapPublicClientPorts(t *testing.T) {
	for _, public := range []int{56001, 56002, 56003} {
		if defaultInternalWGPort == public {
			t.Fatal("internal WireGuard port overlaps a client listener")
		}
	}
}
