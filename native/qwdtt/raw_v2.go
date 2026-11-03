package main

// Raw v2 adds a fresh authenticated session above the outer RTP wrapper.
// Each direction has a separate key and packet counter. Legacy Raw is rejected.
import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net"
	"strings"
	"sync"
	"time"
)

func rawMAC(password string, parts ...[]byte) []byte {
	h := hmac.New(sha256.New, []byte(password))
	h.Write([]byte("bitchat-qwdtt-raw-v2\x00"))
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}
func rawRead(c net.Conn) ([]byte, error)  { b := make([]byte, 8192); n, e := c.Read(b); return b[:n], e }
func rawWrite(c net.Conn, b []byte) error { _, e := c.Write(b); return e }
func rawSession(c net.Conn, password string, clientNonce, serverNonce []byte, client bool) (net.Conn, error) {
	makeAEAD := func(label string) (cipher.AEAD, error) {
		k := rawMAC(password, []byte(label), clientNonce, serverNonce)
		b, e := aes.NewCipher(k)
		if e != nil {
			return nil, e
		}
		return cipher.NewGCM(b)
	}
	up, e := makeAEAD("client-to-server")
	if e != nil {
		return nil, e
	}
	down, e := makeAEAD("server-to-client")
	if e != nil {
		return nil, e
	}
	if client {
		return &rawV2Conn{Conn: c, send: up, recv: down}, nil
	}
	return &rawV2Conn{Conn: c, send: down, recv: up}, nil
}
func rawClientHandshake(c net.Conn, password string) (net.Conn, error) {
	return rawClientHandshakeWithTimeout(c, password, 30*time.Second)
}
func rawClientHandshakeWithTimeout(c net.Conn, password string, timeout time.Duration) (net.Conn, error) {
	c.SetDeadline(time.Now().Add(timeout))
	defer c.SetDeadline(time.Time{})
	cn := make([]byte, 32)
	if _, e := rand.Read(cn); e != nil {
		return nil, e
	}
	hello := []byte("RAW2:" + wrapKeyID(password) + ":" + hex.EncodeToString(cn))
	if e := rawWrite(c, hello); e != nil {
		return nil, e
	}
	challenge, e := rawRead(c)
	if e != nil {
		return nil, e
	}
	if len(challenge) != 64 {
		return nil, errors.New("Raw v2 challenge required")
	}
	sn := challenge[:32]
	if !hmac.Equal(challenge[32:], rawMAC(password, []byte("server"), cn, sn)) {
		return nil, errors.New("Raw v2 server authentication failed")
	}
	if e = rawWrite(c, rawMAC(password, []byte("client"), cn, sn)); e != nil {
		return nil, e
	}
	secure, e := rawSession(c, password, cn, sn, true)
	if e != nil {
		return nil, e
	}
	ack, e := rawRead(secure)
	if e != nil || string(ack) != "RAW2_OK" {
		return nil, errors.New("Raw v2 confirmation failed")
	}
	return secure, nil
}
func rawServerHandshake(c net.Conn, lookup func(string) (string, bool)) (net.Conn, error) {
	c.SetDeadline(time.Now().Add(30 * time.Second))
	defer c.SetDeadline(time.Time{})
	hello, e := rawRead(c)
	if e != nil {
		return nil, e
	}
	parts := strings.Split(string(hello), ":")
	if len(parts) != 3 || parts[0] != "RAW2" {
		return nil, errors.New("legacy Raw disabled")
	}
	password, ok := lookup(parts[1])
	if !ok {
		return nil, errors.New("unknown Raw credential")
	}
	cn, e := hex.DecodeString(parts[2])
	if e != nil || len(cn) != 32 {
		return nil, errors.New("invalid Raw nonce")
	}
	sn := make([]byte, 32)
	if _, e = rand.Read(sn); e != nil {
		return nil, e
	}
	challenge := append(append([]byte{}, sn...), rawMAC(password, []byte("server"), cn, sn)...)
	if e = rawWrite(c, challenge); e != nil {
		return nil, e
	}
	proof, e := rawRead(c)
	if e != nil {
		return nil, e
	}
	if !hmac.Equal(proof, rawMAC(password, []byte("client"), cn, sn)) {
		return nil, errors.New("Raw replay or authentication failure")
	}
	secure, e := rawSession(c, password, cn, sn, false)
	if e != nil {
		return nil, e
	}
	if e = rawWrite(secure, []byte("RAW2_OK")); e != nil {
		return nil, e
	}
	return secure, nil
}

type rawV2Conn struct {
	net.Conn
	send, recv      cipher.AEAD
	writeMu, readMu sync.Mutex
	next, highest   uint64
	received        uint64
}

func (c *rawV2Conn) Write(p []byte) (int, error) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.next++
	if c.next == 0 {
		return 0, errors.New("Raw counter exhausted")
	}
	header := make([]byte, 12)
	copy(header, []byte("RWV2"))
	binary.BigEndian.PutUint64(header[4:], c.next)
	nonce := make([]byte, 12)
	binary.BigEndian.PutUint64(nonce[4:], c.next)
	frame := c.send.Seal(header, nonce, p, header)
	if _, e := c.Conn.Write(frame); e != nil {
		return 0, e
	}
	return len(p), nil
}
func (c *rawV2Conn) Read(p []byte) (int, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()

	for {
		b, e := rawRead(c.Conn)
		if e != nil {
			return 0, e
		}
		if len(b) < 28 || string(b[:4]) != "RWV2" {
			return 0, errors.New("invalid Raw record")
		}
		seq := binary.BigEndian.Uint64(b[4:12])
		// The carrier is datagram based: accept fresh records within a bounded
		// window, while silently discarding duplicates and records too old to track.
		if seq == 0 {
			continue
		}
		if seq <= c.highest {
			delta := c.highest - seq
			if delta >= 64 || c.received&(uint64(1)<<delta) != 0 {
				continue
			}
		}
		nonce := make([]byte, 12)
		binary.BigEndian.PutUint64(nonce[4:], seq)
		plain, e := c.recv.Open(nil, nonce, b[12:], b[:12])
		if e != nil {
			return 0, e
		}
		if len(plain) > len(p) {
			return 0, errors.New("Raw buffer too small")
		}
		// Unauthenticated counters must never advance the replay window.
		if seq > c.highest {
			delta := seq - c.highest
			if delta >= 64 {
				c.received = 0
			} else {
				c.received <<= delta
			}
			c.highest = seq
			c.received |= 1
		} else {
			c.received |= uint64(1) << (c.highest - seq)
		}
		return copy(p, plain), nil
	}

}

func wrapKeyID(password string) string {
	sum := sha256.Sum256([]byte("WDTT-WRAP-ID-v1\x00" + password))
	return hex.EncodeToString(sum[:8])
}
