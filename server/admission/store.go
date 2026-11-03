// Package admission owns device authorization. Transport integrations must enforce its
// leases before forwarding; this package alone does not guard an existing exit.
package admission

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

type Scope uint8

const (
	VK   Scope = 1
	Cups Scope = 2
)

var ErrDenied = errors.New("device admission denied")
var ErrUnavailable = errors.New("device admission unavailable")

type invitation struct {
	Scope          Scope
	Expires        int64
	DeviceLifetime int64
}
type approvedDevice struct {
	Public  []byte
	Scope   Scope
	Expires int64
	Revoked bool
}
type snapshot struct {
	Version     int
	Issuer      []byte
	Invitations map[string]invitation
	Devices     map[string]approvedDevice
}
type Service struct {
	mu         sync.Mutex
	data       snapshot
	key        *ecdsa.PrivateKey
	public     []byte
	lock       *os.File
	now        func() time.Time
	persist    func(snapshot) error
	sealed     bool
	challenges map[string]pending
	leases     map[*Lease]struct{}
}

func fingerprint(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func validScope(s Scope) bool     { return s > 0 && s&^(VK|Cups) == 0 }
func publicKey(b []byte) (*ecdsa.PublicKey, error) {
	if len(b) > 256 {
		return nil, ErrDenied
	}
	k, e := x509.ParsePKIXPublicKey(b)
	if e != nil {
		return nil, ErrDenied
	}
	p, ok := k.(*ecdsa.PublicKey)
	if !ok || p.Curve != elliptic.P256() {
		return nil, ErrDenied
	}
	canonical, e := x509.MarshalPKIXPublicKey(p)
	if e != nil || !bytes.Equal(canonical, b) {
		return nil, ErrDenied
	}
	return p, nil
}
func Open(path string) (*Service, error) {
	dir := filepath.Dir(path)
	info, e := os.Lstat(dir)
	if e != nil || !info.IsDir() || info.Mode().Perm()&0077 != 0 {
		return nil, ErrUnavailable
	}
	fd, e := syscall.Open(path+".lock", syscall.O_CREAT|syscall.O_RDWR|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0600)
	if e != nil {
		return nil, ErrUnavailable
	}
	lock := os.NewFile(uintptr(fd), "admission-lock")
	if e = syscall.Flock(fd, syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		lock.Close()
		return nil, ErrUnavailable
	}
	s := &Service{lock: lock, now: time.Now, challenges: make(map[string]pending), leases: make(map[*Lease]struct{})}
	ok := false
	defer func() {
		if !ok {
			lock.Close()
		}
	}()
	s.persist = func(v snapshot) error { return saveSnapshot(path, v) }
	fd, e = syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0)
	if errors.Is(e, syscall.ENOENT) {
		s.key, e = ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if e != nil {
			return nil, ErrUnavailable
		}
		der, e := x509.MarshalPKCS8PrivateKey(s.key)
		if e != nil {
			return nil, ErrUnavailable
		}
		s.data = snapshot{1, der, map[string]invitation{}, map[string]approvedDevice{}}
		if e = s.persist(s.data); e != nil {
			return nil, ErrUnavailable
		}
	} else if e != nil {
		return nil, ErrUnavailable
	} else {
		f := os.NewFile(uintptr(fd), "admission-store")
		stat, e := f.Stat()
		if e != nil || !stat.Mode().IsRegular() || stat.Mode().Perm()&0077 != 0 || stat.Size() > 8<<20 {
			f.Close()
			return nil, ErrUnavailable
		}
		decoder := json.NewDecoder(io.LimitReader(f, 8<<20))
		decoder.DisallowUnknownFields()
		e = decoder.Decode(&s.data)
		if e == nil && decoder.Decode(new(any)) != io.EOF {
			e = ErrUnavailable
		}
		f.Close()
		if e != nil || s.data.Version != 1 || s.data.Invitations == nil || s.data.Devices == nil || len(s.data.Invitations) > 10000 || len(s.data.Devices) > 10000 {
			return nil, ErrUnavailable
		}
		parsed, e := x509.ParsePKCS8PrivateKey(s.data.Issuer)
		if e != nil {
			return nil, ErrUnavailable
		}
		var valid bool
		s.key, valid = parsed.(*ecdsa.PrivateKey)
		if !valid || s.key.Curve != elliptic.P256() {
			return nil, ErrUnavailable
		}
		for hash, v := range s.data.Invitations {
			if !validHash(hash) || !validScope(v.Scope) || v.Expires < 1 || v.DeviceLifetime < 1 || v.DeviceLifetime > int64((365*24*time.Hour)/time.Second) {
				return nil, ErrUnavailable
			}
		}
		for id, d := range s.data.Devices {
			if _, e = publicKey(d.Public); e != nil || fingerprint(d.Public) != id || !validScope(d.Scope) || d.Expires < 1 {
				return nil, ErrUnavailable
			}
		}
	}
	s.public, e = x509.MarshalPKIXPublicKey(&s.key.PublicKey)
	if e != nil {
		return nil, ErrUnavailable
	}
	ok = true
	return s, nil
}
func validHash(v string) bool {
	if len(v) != 64 {
		return false
	}
	b, e := hex.DecodeString(v)
	return e == nil && len(b) == 32 && hex.EncodeToString(b) == v
}
func saveSnapshot(path string, v snapshot) error {
	b, e := json.Marshal(v)
	if e != nil || len(b) > 8<<20 {
		return ErrUnavailable
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".admission-*")
	if e != nil {
		return e
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if _, e = f.Write(b); e == nil {
		e = f.Sync()
	}
	if closeErr := f.Close(); e == nil {
		e = closeErr
	}
	if e != nil {
		return e
	}
	if e = os.Rename(tmp, path); e != nil {
		return e
	}
	dir, e := os.Open(filepath.Dir(path))
	if e != nil {
		return e
	}
	defer dir.Close()
	return dir.Sync()
}
func (s *Service) copyState() snapshot {
	n := snapshot{Version: 1, Issuer: s.data.Issuer, Invitations: make(map[string]invitation), Devices: make(map[string]approvedDevice)}
	for k, v := range s.data.Invitations {
		n.Invitations[k] = v
	}
	for k, v := range s.data.Devices {
		n.Devices[k] = v
	}
	return n
}
func (s *Service) commit(n snapshot) error {
	if e := s.persist(n); e != nil {
		s.seal()
		return ErrUnavailable
	}
	s.data = n
	return nil
}
func (s *Service) seal() {
	s.sealed = true
	clear(s.challenges)
	for l := range s.leases {
		s.closeLease(l)
	}
}
func (s *Service) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.seal()
	if s.lock != nil {
		s.lock.Close()
		s.lock = nil
	}
}
func (s *Service) PublicKey() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]byte(nil), s.public...)
}
func (s *Service) Issue(scope Scope, ttl, deviceLifetime time.Duration) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sealed {
		return "", ErrUnavailable
	}
	if !validScope(scope) || ttl < time.Second || ttl > 7*24*time.Hour || deviceLifetime < time.Second || deviceLifetime > 365*24*time.Hour {
		return "", ErrDenied
	}
	n := s.copyState()
	now := s.now()
	for k, v := range n.Invitations {
		if v.Expires <= now.Unix() {
			delete(n.Invitations, k)
		}
	}
	if len(n.Invitations) >= 10000 {
		return "", ErrUnavailable
	}
	b := make([]byte, 32)
	if _, e := rand.Read(b); e != nil {
		return "", ErrUnavailable
	}
	n.Invitations[fingerprint(b)] = invitation{scope, now.Add(ttl).Unix(), int64(deviceLifetime / time.Second)}
	if e := s.commit(n); e != nil {
		return "", e
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
func (s *Service) Revoke(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sealed {
		return ErrUnavailable
	}
	n := s.copyState()
	d, ok := n.Devices[id]
	if !ok {
		return ErrDenied
	}
	d.Revoked = true
	n.Devices[id] = d
	if e := s.commit(n); e != nil {
		return e
	}
	for l := range s.leases {
		if l.deviceID == id {
			s.closeLease(l)
		}
	}
	return nil
}

func (s *Service) String() string   { return "AdmissionService([redacted])" }
func (s *Service) GoString() string { return s.String() }
