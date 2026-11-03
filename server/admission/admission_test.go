package admission

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func device(t *testing.T) (*ecdsa.PrivateKey, []byte) {
	t.Helper()
	k, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	p, e := x509.MarshalPKIXPublicKey(&k.PublicKey)
	if e != nil {
		t.Fatal(e)
	}
	return k, p
}
func proof(t *testing.T, k *ecdsa.PrivateKey, c Challenge) []byte {
	t.Helper()
	h := sha256.Sum256(append([]byte("PANCHAN-DEVICE-PROOF-v1\x00"), c.Payload...))
	s, e := ecdsa.SignASN1(rand.Reader, k, h[:])
	if e != nil {
		t.Fatal(e)
	}
	return s
}
func openTest(t *testing.T) *Service {
	t.Helper()
	s, e := Open(filepath.Join(secureDir(t), "state.json"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func enroll(t *testing.T, s *Service, scope Scope) (string, *ecdsa.PrivateKey) {
	t.Helper()
	token, e := s.Issue(scope, time.Hour, 24*time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	k, p := device(t)
	c, e := s.BeginEnrollment(token, p)
	if e != nil {
		t.Fatal(e)
	}
	id, e := s.Enroll(c.ID, proof(t, k, c))
	if e != nil {
		t.Fatal(e)
	}
	return id, k
}
func TestInvitationConsumedAtomically(t *testing.T) {
	s := openTest(t)
	token, e := s.Issue(VK|Cups, time.Hour, 24*time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	var wg sync.WaitGroup
	var n atomic.Int32
	start := make(chan struct{})
	for i := 0; i < 12; i++ {
		k, p := device(t)
		c, e := s.BeginEnrollment(token, p)
		if e != nil {
			t.Fatal(e)
		}
		sig := proof(t, k, c)
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if _, e := s.Enroll(c.ID, sig); e == nil {
				n.Add(1)
			}
		}()
	}
	close(start)
	wg.Wait()
	if n.Load() != 1 {
		t.Fatalf("approved=%d", n.Load())
	}
}
func TestFreshProofScopeReplayAndRevocation(t *testing.T) {
	s := openTest(t)
	id, k := enroll(t, s, VK)
	c, e := s.BeginSession(id, VK, sha256.Sum256([]byte("synthetic transcript")))
	if e != nil {
		t.Fatal(e)
	}
	if !c.Verify(s.PublicKey()) || c.Verify(openTest(t).PublicKey()) {
		t.Fatal("server proof")
	}
	l, e := s.Authenticate(c.ID, proof(t, k, c))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Authenticate(c.ID, proof(t, k, c)); e == nil {
		t.Fatal("replay")
	}
	if _, e = s.BeginSession(id, Cups, [32]byte{1}); e == nil {
		t.Fatal("scope escalation")
	}
	if e = s.Revoke(id); e != nil {
		t.Fatal(e)
	}
	select {
	case <-l.Done():
	default:
		t.Fatal("live lease survived")
	}
	if _, e = s.BeginSession(id, VK, [32]byte{1}); e == nil {
		t.Fatal("revoked reconnect")
	}
}
func TestWrongKeyTranscriptAndExpiry(t *testing.T) {
	s := openTest(t)
	id, k := enroll(t, s, VK)
	c, _ := s.BeginSession(id, VK, [32]byte{1})
	c.Payload[len(c.Payload)-1] ^= 1
	if _, e := s.Authenticate(c.ID, proof(t, k, c)); e == nil {
		t.Fatal("tampered transcript")
	}
	other, _ := device(t)
	c, _ = s.BeginSession(id, VK, [32]byte{2})
	if _, e := s.Authenticate(c.ID, proof(t, other, c)); e == nil {
		t.Fatal("wrong key")
	}
	c, _ = s.BeginSession(id, VK, [32]byte{3})
	s.now = func() time.Time { return time.Now().Add(time.Minute) }
	if _, e := s.Authenticate(c.ID, proof(t, k, c)); e == nil {
		t.Fatal("expired challenge")
	}
}
func TestRestartAndExclusiveStore(t *testing.T) {
	path := filepath.Join(secureDir(t), "state.json")
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	id, k := enroll(t, s, Cups)
	pin := s.PublicKey()
	if _, e = Open(path); e == nil {
		t.Fatal("two owners")
	}
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if string(pin) != string(s.PublicKey()) {
		t.Fatal("issuer changed")
	}
	c, e := s.BeginSession(id, Cups, [32]byte{4})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Authenticate(c.ID, proof(t, k, c)); e != nil {
		t.Fatal(e)
	}
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0600 {
		t.Fatal("file permissions")
	}
}
func TestFailedPersistenceSealsAndCloses(t *testing.T) {
	s := openTest(t)
	id, k := enroll(t, s, VK)
	c, _ := s.BeginSession(id, VK, [32]byte{5})
	l, e := s.Authenticate(c.ID, proof(t, k, c))
	if e != nil {
		t.Fatal(e)
	}
	s.persist = func(snapshot) error { return errors.New("synthetic disk failure") }
	if e = s.Revoke(id); e == nil {
		t.Fatal("commit failure ignored")
	}
	select {
	case <-l.Done():
	default:
		t.Fatal("lease survives")
	}
	if _, e = s.BeginSession(id, VK, [32]byte{6}); e == nil {
		t.Fatal("store not sealed")
	}
}
func TestExpiredInviteCorruptStore(t *testing.T) {
	s := openTest(t)
	token, _ := s.Issue(Cups, time.Minute, time.Hour)
	_, p := device(t)
	s.now = func() time.Time { return time.Now().Add(2 * time.Minute) }
	if _, e := s.BeginEnrollment(token, p); e == nil {
		t.Fatal("expired invite")
	}
	path := filepath.Join(secureDir(t), "bad.json")
	os.WriteFile(path, []byte(`{"version":1,"unknown":true}`), 0600)
	if _, e := Open(path); e == nil {
		t.Fatal("corrupt store")
	}
}

func secureDir(t *testing.T) string {
	t.Helper()
	p := t.TempDir()
	if e := os.Chmod(p, 0700); e != nil {
		t.Fatal(e)
	}
	return p
}

func TestInvitationHashAndConsumedStateSurviveRestart(t *testing.T) {
	path := filepath.Join(secureDir(t), "state.json")
	s, e := Open(path)
	if e != nil {
		t.Fatal(e)
	}
	token, e := s.Issue(VK, time.Hour, time.Hour)
	if e != nil {
		t.Fatal(e)
	}
	raw, e := os.ReadFile(path)
	if e != nil {
		t.Fatal(e)
	}
	if bytes.Contains(raw, []byte(token)) {
		t.Fatal("raw bearer stored")
	}
	k, p := device(t)
	c, _ := s.BeginEnrollment(token, p)
	id, e := s.Enroll(c.ID, proof(t, k, c))
	if e != nil {
		t.Fatal(e)
	}
	if e = s.Revoke(id); e != nil {
		t.Fatal(e)
	}
	s.Close()
	s, e = Open(path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	if _, e = s.BeginEnrollment(token, p); e == nil {
		t.Fatal("spent invite after restart")
	}
	if _, e = s.BeginSession(id, VK, [32]byte{7}); e == nil {
		t.Fatal("revocation lost after restart")
	}
}
func TestLeaseBoundsAndPendingRevocation(t *testing.T) {
	s := openTest(t)
	id, k := enroll(t, s, VK|Cups)
	c, _ := s.BeginSession(id, Cups, [32]byte{8})
	l, e := s.Authenticate(c.ID, proof(t, k, c))
	if e != nil {
		t.Fatal(e)
	}
	if l.DeviceID() != id || l.Scope() != Cups || l.Expires().Sub(time.Now()) > 15*time.Minute {
		t.Fatal("invalid lease")
	}
	s.Release(l)
	s.Release(l)
	select {
	case <-l.Done():
	default:
		t.Fatal("release did not close")
	}
	c, _ = s.BeginSession(id, VK, [32]byte{9})
	s.Revoke(id)
	if _, e = s.Authenticate(c.ID, proof(t, k, c)); e == nil {
		t.Fatal("pending proof survived revoke")
	}
}
func TestInsecureDirectoryAndSymlinkStoreRejected(t *testing.T) {
	dir := secureDir(t)
	os.Chmod(dir, 0755)
	if _, e := Open(filepath.Join(dir, "state.json")); e == nil {
		t.Fatal("unsafe directory accepted")
	}
	dir = secureDir(t)
	target := filepath.Join(dir, "target")
	os.WriteFile(target, []byte("synthetic"), 0600)
	path := filepath.Join(dir, "state.json")
	os.Symlink(target, path)
	if _, e := Open(path); e == nil {
		t.Fatal("symlink accepted")
	}
	b, _ := os.ReadFile(target)
	if string(b) != "synthetic" {
		t.Fatal("symlink target changed")
	}
}
