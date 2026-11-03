package main

import (
	"crypto/x509"
	"errors"
	"io"
	"os"
	"path/filepath"
)

// Use only the app's Android trust-manager export, not an independent stale Go root store.
func configureTrustedCAFile(file string) error {
	if !filepath.IsAbs(file) {
		return errors.New("absolute certificate bundle required")
	}
	input, err := os.Open(file)
	if err != nil {
		return errors.New("trusted certificate bundle unavailable")
	}
	defer input.Close()
	pem, err := io.ReadAll(io.LimitReader(input, 2*1024*1024+1))
	if err != nil || len(pem) > 2*1024*1024 || !x509.NewCertPool().AppendCertsFromPEM(pem) {
		return errors.New("invalid certificate bundle")
	}
	empty := filepath.Join(filepath.Dir(file), "no-extra-roots")
	if err = os.MkdirAll(empty, 0700); err != nil {
		return err
	}
	entries, err := os.ReadDir(empty)
	if err != nil || len(entries) != 0 {
		return errors.New("additional certificate directory must be empty")
	}
	if err = os.Setenv("SSL_CERT_FILE", file); err != nil {
		return err
	}
	return os.Setenv("SSL_CERT_DIR", empty)
}
