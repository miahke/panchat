package main

import (
	"bytes"
	"testing"
)

func TestMobileStatisticsContainOnlyBoundedNumericCounters(t *testing.T) {
	previous, active := protocolOutput, mobileProtocolActive
	defer func() { protocolOutput = previous; mobileProtocolActive = active }()
	var output bytes.Buffer
	protocolOutput = &output
	mobileProtocolActive = true
	emitMobileStatistics(2, 1234, 5678)
	if output.String() != "STATS:2:1234:5678\n" {
		t.Fatal("incorrect telemetry record")
	}
	output.Reset()
	emitMobileStatistics(-1, -1, -1)
	if output.Len() != 0 {
		t.Fatal("negative counters emitted")
	}
}
