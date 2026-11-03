package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func TestTrustBundleValidationRejectsMissingAndEmpty(t *testing.T) {
	if configureTrustedCAFile("relative.pem") == nil {
		t.Fatal("relative bundle accepted")
	}
	file := filepath.Join(t.TempDir(), "roots.pem")
	if configureTrustedCAFile(file) == nil {
		t.Fatal("missing bundle accepted")
	}
	os.WriteFile(file, []byte("not a certificate"), 0600)
	if configureTrustedCAFile(file) == nil {
		t.Fatal("empty trust accepted")
	}
}
func TestTrustBundleTLSHelper(t *testing.T) {
	if os.Getenv("PANCHAN_CA_HELPER") != "1" {
		return
	}
	if err := configureTrustedCAFile(os.Getenv("PANCHAN_CA_FILE")); err != nil {
		t.Fatal(err)
	}
	address := os.Getenv("PANCHAN_CA_ADDRESS")
	conn, err := tls.Dial("tcp", address, &tls.Config{ServerName: "bridge.example.invalid", MinVersion: tls.VersionTLS12})
	if err != nil {
		t.Fatal("trusted synthetic bridge rejected")
	}
	conn.Close()
	conn, err = tls.Dial("tcp", address, &tls.Config{ServerName: "wrong.example.invalid", MinVersion: tls.VersionTLS12})
	if err == nil {
		conn.Close()
		t.Fatal("wrong hostname accepted")
	}
}
func TestTrustBundleSupportsTLSWithoutSkippingHostnameVerification(t *testing.T) {
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "Synthetic Bridge CA"}, DNSNames: []string{"bridge.example.invalid"}, NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), BasicConstraintsValid: true, IsCA: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, pub, key)
	if err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(t.TempDir(), "roots.pem")
	os.WriteFile(file, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	server.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	server.StartTLS()
	defer server.Close()
	binary, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(binary, "-test.run=^TestTrustBundleTLSHelper$")
	cmd.Env = append(os.Environ(), "PANCHAN_CA_HELPER=1", "PANCHAN_CA_FILE="+file, "PANCHAN_CA_ADDRESS="+server.Listener.Addr().String())
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("TLS trust helper failed: %s", output)
	}
}
