package admission

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"io"
	"strings"
	"unicode"
	"unicode/utf8"
)

const invitePrefix = "panchan://v1/"

var inviteDomain = []byte("PANCHAN-INVITE-v1\x00")

type Invite struct {
	IssuedAt      int64
	ExpiresAt     int64
	Identifier    [32]byte
	Scope         Scope
	Name          string
	Configuration string
}

func (v Invite) String() string { return "PanchanInvite([redacted])" }
func (v Invite) Token() string  { return base64.RawURLEncoding.EncodeToString(v.Identifier[:]) }
func validProfile(scope Scope, name, configuration string) bool {
	return (scope == VK || scope == Cups) && len(name) > 0 && len(name) <= 128 && utf8.ValidString(name) && strings.IndexFunc(name, unicode.IsControl) < 0 && len(configuration) > 0 && len(configuration) <= 32768 && utf8.ValidString(configuration)
}
func (s *Service) InviteLink(token string, scope Scope, name, configuration string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sealed {
		return "", ErrUnavailable
	}
	if len(token) != 43 || !validProfile(scope, name, configuration) {
		return "", ErrDenied
	}
	b, e := base64.RawURLEncoding.DecodeString(token)
	if e != nil || len(b) != 32 || base64.RawURLEncoding.EncodeToString(b) != token {
		return "", ErrDenied
	}
	inv, ok := s.data.Invitations[fingerprint(b)]
	now := s.now().Unix()
	if !ok || inv.Expires <= now || inv.Scope&scope != scope {
		return "", ErrDenied
	}
	payload := new(bytes.Buffer)
	binary.Write(payload, binary.BigEndian, uint32(0x50434831))
	binary.Write(payload, binary.BigEndian, now)
	binary.Write(payload, binary.BigEndian, inv.Expires)
	payload.Write(b)
	payload.WriteByte(byte(scope))
	binary.Write(payload, binary.BigEndian, uint16(len(name)))
	payload.WriteString(name)
	binary.Write(payload, binary.BigEndian, uint32(len(configuration)))
	payload.WriteString(configuration)
	h := sha256.Sum256(append(append([]byte(nil), inviteDomain...), payload.Bytes()...))
	signature, e := ecdsa.SignASN1(rand.Reader, s.key, h[:])
	if e != nil {
		return "", ErrUnavailable
	}
	encode := base64.RawURLEncoding.EncodeToString
	return invitePrefix + encode(payload.Bytes()) + "." + encode(s.public) + "." + encode(signature), nil
}
func VerifyInvite(link, pin string, now int64) (Invite, error) {
	var v Invite
	if now < 0 || len(link) > 45000 || !strings.HasPrefix(link, invitePrefix) || !validHash(pin) {
		return v, ErrDenied
	}
	parts := strings.Split(strings.TrimPrefix(link, invitePrefix), ".")
	if len(parts) != 3 {
		return v, ErrDenied
	}
	decode := func(text string, maximum int) ([]byte, error) {
		b, e := base64.RawURLEncoding.DecodeString(text)
		if e != nil || len(b) == 0 || len(b) > maximum || base64.RawURLEncoding.EncodeToString(b) != text {
			return nil, ErrDenied
		}
		return b, nil
	}
	payload, e := decode(parts[0], 33000)
	if e != nil {
		return v, e
	}
	spki, e := decode(parts[1], 256)
	if e != nil {
		return v, e
	}
	signature, e := decode(parts[2], 80)
	if e != nil {
		return v, e
	}
	key, e := publicKey(spki)
	if e != nil || subtle.ConstantTimeCompare([]byte(fingerprint(spki)), []byte(pin)) != 1 {
		return v, ErrDenied
	}
	h := sha256.Sum256(append(append([]byte(nil), inviteDomain...), payload...))
	if !ecdsa.VerifyASN1(key, h[:], signature) {
		return v, ErrDenied
	}
	reader := bytes.NewReader(payload)
	var magic uint32
	if binary.Read(reader, binary.BigEndian, &magic) != nil || magic != 0x50434831 || binary.Read(reader, binary.BigEndian, &v.IssuedAt) != nil || binary.Read(reader, binary.BigEndian, &v.ExpiresAt) != nil {
		return Invite{}, ErrDenied
	}
	if _, e = io.ReadFull(reader, v.Identifier[:]); e != nil {
		return Invite{}, ErrDenied
	}
	scope, e := reader.ReadByte()
	if e != nil {
		return Invite{}, ErrDenied
	}
	v.Scope = Scope(scope)
	var nameSize uint16
	var configSize uint32
	if binary.Read(reader, binary.BigEndian, &nameSize) != nil || nameSize < 1 || nameSize > 128 {
		return Invite{}, ErrDenied
	}
	name := make([]byte, int(nameSize))
	if _, e = io.ReadFull(reader, name); e != nil {
		return Invite{}, ErrDenied
	}
	v.Name = string(name)
	if binary.Read(reader, binary.BigEndian, &configSize) != nil || configSize < 1 || configSize > 32768 {
		return Invite{}, ErrDenied
	}
	config := make([]byte, int(configSize))
	if _, e = io.ReadFull(reader, config); e != nil {
		return Invite{}, ErrDenied
	}
	v.Configuration = string(config)
	if reader.Len() != 0 || !validProfile(v.Scope, v.Name, v.Configuration) || v.IssuedAt < 0 || v.ExpiresAt <= v.IssuedAt || v.ExpiresAt-v.IssuedAt > 604800 || (v.IssuedAt > now && v.IssuedAt-now > 300) || now >= v.ExpiresAt {
		return Invite{}, ErrDenied
	}
	return v, nil
}

// IssuerFingerprint is safe to compare through an independently authenticated owner channel.
func IssuerFingerprint(spki []byte) (string, error) {
	if _, e := publicKey(spki); e != nil {
		return "", e
	}
	h := sha256.Sum256(spki)
	return hex.EncodeToString(h[:]), nil
}

func (v Invite) GoString() string { return v.String() }
