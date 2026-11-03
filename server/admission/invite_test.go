package admission

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"
)

func TestPanchanIndependentVector(t *testing.T) {
	var vector map[string]string
	b, e := os.ReadFile("testdata/panchan-invite-v1.json")
	if e != nil {
		t.Fatal(e)
	}
	json.Unmarshal(b, &vector)
	v, e := VerifyInvite(vector["link"], vector["fingerprint"], 1800000000)
	if e != nil || v.Configuration != "openflux://v1/synthetic-vector" || v.Scope != Cups {
		t.Fatal("independent vector failed")
	}
	if _, e = VerifyInvite(vector["link"], strings.Repeat("0", 64), 1800000000); e == nil {
		t.Fatal("unknown issuer accepted")
	}
}
func TestIssuedLinkSharesOneUseIdentifierAndCannotExtendExpiry(t *testing.T) {
	s := openTest(t)
	token, _ := s.Issue(Cups, time.Hour, 24*time.Hour)
	link, e := s.InviteLink(token, Cups, "Synthetic exit", "openflux://v1/synthetic-opaque-config")
	if e != nil {
		t.Fatal(e)
	}
	v, e := VerifyInvite(link, fingerprint(s.PublicKey()), time.Now().Unix())
	if e != nil {
		t.Fatal(e)
	}
	if v.Token() != token {
		t.Fatal("different invitation identifier")
	}
	if _, e = s.InviteLink(token, VK, "Synthetic exit", "{}"); e == nil {
		t.Fatal("scope escalation")
	}
	k, p := device(t)
	c, _ := s.BeginEnrollment(v.Token(), p)
	if _, e = s.Enroll(c.ID, proof(t, k, c)); e != nil {
		t.Fatal(e)
	}
	if _, e = s.InviteLink(token, Cups, "Synthetic exit", "anything"); e == nil {
		t.Fatal("spent invitation reissued")
	}
	if _, e = VerifyInvite(link, fingerprint(s.PublicKey()), time.Now().Add(2*time.Hour).Unix()); e == nil {
		t.Fatal("expired link")
	}
}
