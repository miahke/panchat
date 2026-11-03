package admission

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"time"
)

type Challenge struct {
	ID        string
	Payload   []byte
	Signature []byte
}

func (c Challenge) String() string { return "Challenge([redacted])" }

const challengeDomain = "PANCHAN-DEVICE-v1\x00"

// Verify authenticates the canonical server challenge shape, signer and nonce.
// Clients must additionally use VerifyFor to bind their expected channel/key/scope.
func (c Challenge) Verify(serverPublic []byte) bool {
	p, e := publicKey(serverPublic)
	n := len(challengeDomain)
	if e != nil || len(c.Payload) != n+170 || len(c.Signature) > 80 || !validHash(c.ID) || !bytes.HasPrefix(c.Payload, []byte(challengeDomain)) {
		return false
	}
	serverID := sha256.Sum256(serverPublic)
	nonce, _ := hex.DecodeString(c.ID)
	action := c.Payload[n+64]
	scope := Scope(c.Payload[n+97])
	if !bytes.Equal(c.Payload[n:n+32], serverID[:]) || !bytes.Equal(c.Payload[n+32:n+64], nonce) || (action != 1 && action != 2) || !validScope(scope) || (action == 2 && scope != VK && scope != Cups) {
		return false
	}
	h := sha256.Sum256(c.Payload)
	return ecdsa.VerifyASN1(p, h[:], c.Signature)
}

// VerifyFor checks the independent server pin and expected device/channel before
// the client signs. Empty token means session authentication, otherwise enrollment.
func (c Challenge) VerifyFor(serverPublic, devicePublic []byte, scope Scope, transcript [32]byte, token string, now time.Time) bool {
	if !c.Verify(serverPublic) {
		return false
	}
	if _, e := publicKey(devicePublic); e != nil {
		return false
	}
	n := len(challengeDomain)
	deviceID := sha256.Sum256(devicePublic)
	if !bytes.Equal(c.Payload[n+65:n+97], deviceID[:]) || c.Payload[n+97] != byte(scope) || !bytes.Equal(c.Payload[n+106:n+138], transcript[:]) {
		return false
	}
	expires := int64(binary.BigEndian.Uint64(c.Payload[n+98 : n+106]))
	if now.Unix() < 0 || expires <= now.Unix() || time.Unix(expires, 0).Sub(now) > 30*time.Second {
		return false
	}
	if token == "" {
		return c.Payload[n+64] == 2 && transcript != ([32]byte{}) && bytes.Equal(c.Payload[n+138:], make([]byte, 32))
	}
	if len(token) != 43 || c.Payload[n+64] != 1 || transcript != ([32]byte{}) {
		return false
	}
	value, e := base64.RawURLEncoding.DecodeString(token)
	if e != nil || len(value) != 32 || base64.RawURLEncoding.EncodeToString(value) != token {
		return false
	}
	invitationID := sha256.Sum256(value)
	return bytes.Equal(c.Payload[n+138:], invitationID[:])
}

type pending struct {
	action  byte
	invite  string
	public  []byte
	scope   Scope
	expires time.Time
	payload []byte
}
type Lease struct {
	deviceID string
	scope    Scope
	expires  time.Time
	done     chan struct{}
	timer    *time.Timer
}

func (l *Lease) Done() <-chan struct{} { return l.done }
func (l *Lease) DeviceID() string      { return l.deviceID }
func (l *Lease) Scope() Scope          { return l.scope }
func (l *Lease) Expires() time.Time    { return l.expires }
func (l *Lease) String() string        { return "AdmissionLease([redacted])" }
func (s *Service) closeLease(l *Lease) {
	if _, ok := s.leases[l]; !ok {
		return
	}
	delete(s.leases, l)
	if l.timer != nil {
		l.timer.Stop()
	}
	close(l.done)
}
func (s *Service) begin(action byte, invite string, pub []byte, scope Scope, transcript [32]byte) (Challenge, error) {
	now := s.now()
	for id, p := range s.challenges {
		if !now.Before(p.expires) {
			delete(s.challenges, id)
		}
	}
	if len(s.challenges) >= 4096 {
		return Challenge{}, ErrUnavailable
	}
	nonce := make([]byte, 32)
	if _, e := rand.Read(nonce); e != nil {
		return Challenge{}, ErrUnavailable
	}
	id := hex.EncodeToString(nonce)
	expires := time.Unix(now.Add(30*time.Second).Unix(), 0)
	b := new(bytes.Buffer)
	b.WriteString(challengeDomain)
	serverID := sha256.Sum256(s.public)
	b.Write(serverID[:])
	b.Write(nonce)
	b.WriteByte(action)
	deviceID := sha256.Sum256(pub)
	b.Write(deviceID[:])
	b.WriteByte(byte(scope))
	binary.Write(b, binary.BigEndian, expires.Unix())
	b.Write(transcript[:])
	hash, _ := hex.DecodeString(invite)
	if len(hash) != 32 {
		hash = make([]byte, 32)
	}
	b.Write(hash)
	payload := b.Bytes()
	h := sha256.Sum256(payload)
	sig, e := ecdsa.SignASN1(rand.Reader, s.key, h[:])
	if e != nil {
		return Challenge{}, ErrUnavailable
	}
	s.challenges[id] = pending{action, invite, append([]byte(nil), pub...), scope, expires, append([]byte(nil), payload...)}
	return Challenge{id, append([]byte(nil), payload...), sig}, nil
}
func (s *Service) BeginEnrollment(token string, public []byte) (Challenge, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sealed {
		return Challenge{}, ErrUnavailable
	}
	if len(token) != 43 {
		return Challenge{}, ErrDenied
	}
	b, e := base64.RawURLEncoding.DecodeString(token)
	if e != nil || len(b) != 32 || base64.RawURLEncoding.EncodeToString(b) != token {
		return Challenge{}, ErrDenied
	}
	hash := fingerprint(b)
	inv, ok := s.data.Invitations[hash]
	if !ok || inv.Expires <= s.now().Unix() {
		return Challenge{}, ErrDenied
	}
	if _, e = publicKey(public); e != nil {
		return Challenge{}, ErrDenied
	}
	return s.begin(1, hash, public, inv.Scope, [32]byte{})
}
func (s *Service) BeginSession(id string, scope Scope, transcript [32]byte) (Challenge, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sealed {
		return Challenge{}, ErrUnavailable
	}
	d, ok := s.data.Devices[id]
	if !ok || d.Revoked || d.Expires <= s.now().Unix() || (scope != VK && scope != Cups) || d.Scope&scope != scope || transcript == ([32]byte{}) {
		return Challenge{}, ErrDenied
	}
	return s.begin(2, "", d.Public, scope, transcript)
}
func (s *Service) consume(id string, action byte, signature []byte) (pending, error) {
	if s.sealed {
		return pending{}, ErrUnavailable
	}
	p, ok := s.challenges[id]
	delete(s.challenges, id)
	if !ok || p.action != action || !s.now().Before(p.expires) || len(signature) > 80 {
		return pending{}, ErrDenied
	}
	pub, e := publicKey(p.public)
	if e != nil {
		return pending{}, ErrDenied
	}
	h := sha256.Sum256(append([]byte("PANCHAN-DEVICE-PROOF-v1\x00"), p.payload...))
	if !ecdsa.VerifyASN1(pub, h[:], signature) {
		return pending{}, ErrDenied
	}
	return p, nil
}
func (s *Service) Enroll(id string, signature []byte) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, e := s.consume(id, 1, signature)
	if e != nil {
		return "", e
	}
	inv, ok := s.data.Invitations[p.invite]
	if !ok || inv.Expires <= s.now().Unix() {
		return "", ErrDenied
	}
	deviceID := fingerprint(p.public)
	if _, exists := s.data.Devices[deviceID]; exists || len(s.data.Devices) >= 10000 {
		return "", ErrDenied
	}
	n := s.copyState()
	delete(n.Invitations, p.invite)
	n.Devices[deviceID] = approvedDevice{Public: p.public, Scope: inv.Scope, Expires: s.now().Unix() + inv.DeviceLifetime}
	if e = s.commit(n); e != nil {
		return "", e
	}
	return deviceID, nil
}
func (s *Service) Authenticate(id string, signature []byte) (*Lease, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, e := s.consume(id, 2, signature)
	if e != nil {
		return nil, e
	}
	deviceID := fingerprint(p.public)
	d, ok := s.data.Devices[deviceID]
	now := s.now()
	if !ok || d.Revoked || d.Expires <= now.Unix() || d.Scope&p.scope != p.scope {
		return nil, ErrDenied
	}
	if len(s.leases) >= 4096 {
		return nil, ErrUnavailable
	}
	expires := now.Add(15 * time.Minute)
	if end := time.Unix(d.Expires, 0); end.Before(expires) {
		expires = end
	}
	l := &Lease{deviceID: deviceID, scope: p.scope, expires: expires, done: make(chan struct{})}
	s.leases[l] = struct{}{}
	l.timer = time.AfterFunc(expires.Sub(now), func() { s.mu.Lock(); defer s.mu.Unlock(); s.closeLease(l) })
	return l, nil
}
func (s *Service) Release(l *Lease) { s.mu.Lock(); defer s.mu.Unlock(); s.closeLease(l) }

func (c Challenge) GoString() string { return c.String() }
func (l *Lease) GoString() string    { return l.String() }
