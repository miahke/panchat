package admission

import (
	"fmt"
	"testing"
)

func TestSensitiveValuesHaveRedactedDebugRepresentations(t *testing.T) {
	s := openTest(t)
	values := []struct {
		v        any
		expected string
	}{{s, "AdmissionService([redacted])"}, {Invite{Configuration: "synthetic-secret"}, "PanchanInvite([redacted])"}, {Challenge{Payload: []byte("synthetic-secret")}, "Challenge([redacted])"}, {&Lease{}, "AdmissionLease([redacted])"}}
	for _, v := range values {
		if fmt.Sprint(v.v) != v.expected || fmt.Sprintf("%#v", v.v) != v.expected {
			t.Fatal("debug representation exposes fields")
		}
	}
}
