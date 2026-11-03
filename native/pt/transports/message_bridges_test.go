package transports

import "testing"

func TestOnlyMessageBridgeTransports(t *testing.T) {
	if err := Init(); err != nil {
		t.Fatal(err)
	}
	if got := Transports(); len(got) != 2 {
		t.Fatalf("expected only obfs4 and webtunnel; got %v", got)
	}
	if Get("obfs4") == nil || Get("webtunnel") == nil {
		t.Fatal("required transport missing")
	}
}
