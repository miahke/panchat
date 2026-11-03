package admission

import (
	"encoding/base64"
	"strings"
	"testing"
	"time"
)

func TestChallengeRejectsSignatureFromInvitationDomain(t *testing.T) {
	s := openTest(t)
	token, _ := s.Issue(Cups, time.Hour, time.Hour)
	link, _ := s.InviteLink(token, Cups, "Synthetic exit", "opaque")
	parts := strings.Split(strings.TrimPrefix(link, invitePrefix), ".")
	payload, _ := base64.RawURLEncoding.DecodeString(parts[0])
	sig, _ := base64.RawURLEncoding.DecodeString(parts[2])
	forged := Challenge{ID: strings.Repeat("0", 64), Payload: append(append([]byte(nil), inviteDomain...), payload...), Signature: sig}
	if forged.Verify(s.PublicKey()) {
		t.Fatal("invitation signature accepted as device challenge")
	}
}
func TestClientVerifiesExpectedDeviceChannelScopeAndFreshness(t *testing.T) {
	s := openTest(t)
	token, _ := s.Issue(VK, time.Hour, time.Hour)
	k, p := device(t)
	c, _ := s.BeginEnrollment(token, p)
	if !c.VerifyFor(s.PublicKey(), p, VK, [32]byte{}, token, time.Now()) {
		t.Fatal("enrollment binding")
	}
	if c.VerifyFor(s.PublicKey(), p, Cups, [32]byte{}, token, time.Now()) {
		t.Fatal("wrong scope")
	}
	if c.VerifyFor(s.PublicKey(), p, VK, [32]byte{}, token, time.Now().Add(time.Minute)) {
		t.Fatal("stale challenge")
	}
	id, e := s.Enroll(c.ID, proof(t, k, c))
	if e != nil {
		t.Fatal(e)
	}
	c, _ = s.BeginSession(id, VK, [32]byte{1})
	if !c.VerifyFor(s.PublicKey(), p, VK, [32]byte{1}, "", time.Now()) {
		t.Fatal("session binding")
	}
	if c.VerifyFor(s.PublicKey(), p, VK, [32]byte{2}, "", time.Now()) {
		t.Fatal("wrong channel")
	}
	_, other := device(t)
	if c.VerifyFor(s.PublicKey(), other, VK, [32]byte{1}, "", time.Now()) {
		t.Fatal("wrong device")
	}
	c.ID = strings.Repeat("0", 64)
	if c.Verify(s.PublicKey()) {
		t.Fatal("nonce substitution")
	}
}
