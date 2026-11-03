package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestMobileConfigRefusesOpenProxyAndMissingSecrets(t *testing.T) {
	for _, text := range []string{
		`{}`,
		`{"mode":"wg","peer":"192.0.2.1:56001","hash":"synthetic-call-hash","password":"synthetic-password","port":1080,"username":"","socksPassword":"test-password"}`,
		`{"mode":"shell","peer":"192.0.2.1:56001","hash":"synthetic-call-hash","password":"synthetic-password","port":1080,"username":"test-user","socksPassword":"test-password"}`,
	} {
		if _, err := readMobileConfig(strings.NewReader(text + "\n")); err == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
}

func TestMobileConfigProducesOnlyLoopbackAuthenticatedSocksWithTwoWorkers(t *testing.T) {
	cfg, err := readMobileConfig(strings.NewReader(`{"mode":"wg","peer":"192.0.2.1:56001","hash":"synthetic-call-hash","password":"synthetic-password","port":1080,"username":"test-user","socksPassword":"test-password"}` + "\n"))
	if err != nil {
		t.Fatal(err)
	}
	args := cfg.args()
	joined := strings.Join(args, " ")
	for _, want := range []string{"-mode socks", "-n 2", "-socks 127.0.0.1:1080", "-socks-auth=true"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("configuration missing %s", want)
		}
	}
}

func TestMobileConfigBoundsInputAndRejectsUnknownFields(t *testing.T) {
	for _, text := range []string{strings.Repeat("a", 65537), `{"arbitraryFlag":"notls"}`} {
		if _, err := readMobileConfig(strings.NewReader(text + "\n")); err == nil {
			t.Fatal("unexpected input accepted")
		}
	}
}

func TestMissingDeviceIdentityDoesNotShareAServerPeerAcrossClients(t *testing.T) {
	text := `{"mode":"wg","peer":"192.0.2.1:56001","hash":"synthetic-call-hash","password":"synthetic-password","port":1080,"username":"test-user","socksPassword":"test-password"}`
	a, err := readMobileConfig(strings.NewReader(text))
	if err != nil {
		t.Fatal(err)
	}
	b, err := readMobileConfig(strings.NewReader(text))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(a.args(), " ") == strings.Join(b.args(), " ") {
		t.Fatal("independent clients reuse server device identity")
	}
}

func TestMobileStatusOnlyEmitsKnownRedactedStages(t *testing.T) {
	previous, active := protocolOutput, mobileProtocolActive
	defer func() { protocolOutput = previous; mobileProtocolActive = active }()
	var output bytes.Buffer
	protocolOutput = &output
	mobileProtocolActive = true
	emitMobileStage("TURN_READY")
	emitMobileStage("synthetic-secret-not-a-stage")
	if output.String() != "STAGE:TURN_READY\n" {
		t.Fatal("unexpected status output")
	}
}
