package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/google/uuid"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
)

type mobileConfig struct {
	AutoPeer             bool   `json:"autoPeer"`
	FallbackPort         int    `json:"fallbackPort"`
	DTLSTimeoutSeconds   int    `json:"dtlsTimeoutSeconds"`
	ConfigTimeoutSeconds int    `json:"configTimeoutSeconds"`
	TunnelDNS            string `json:"tunnelDns"`
	TunnelMTU            int    `json:"tunnelMtu"`
	WGKeepalive          int    `json:"wgKeepalive"`
	DeviceID             string `json:"deviceId"`
	Mode                 string `json:"mode"`
	Peer                 string `json:"peer"`
	Hash                 string `json:"hash"`
	Password             string `json:"password"`
	Port                 int    `json:"port"`
	Username             string `json:"username"`
	SocksPassword        string `json:"socksPassword"`
	DNS                  string `json:"dns"`
	TurnHost             string `json:"turnHost"`
	TurnPort             int    `json:"turnPort"`
	TurnTCP              bool   `json:"turnTcp"`
	NoDTLS               bool   `json:"noDtls"`
	Obfuscation          string `json:"obfuscation"`
}

var controlInput io.Reader = os.Stdin
var protocolOutput io.Writer = os.Stdout
var mobileProtocolActive bool

func emitMobileStage(stage string) {
	if !mobileProtocolActive {
		return
	}
	switch stage {
	case "RAW_HANDSHAKE_RETRY", "PEER_TIMEOUT", "VK_CREDENTIALS", "TURN_ALLOCATING", "TURN_READY", "AUTHENTICATING", "SESSION_HANDSHAKE", "DTLS_HANDSHAKE", "DTLS_READY", "DTLS_TIMEOUT", "DTLS_ERROR", "PEER_PACKET", "CONFIG_REQUESTED", "CONFIG_RECEIVED", "CONFIG_PENDING", "CONFIG_TIMEOUT", "WIREGUARD_STARTING", "WIREGUARD_HANDSHAKE", "WIREGUARD_READY", "RAW_HANDSHAKE", "RAW_READY", "RECONNECTING", "TRANSPORT_READY", "MODE_DTLS", "MODE_RTP":
		fmt.Fprintln(protocolOutput, "STAGE:"+stage)
	}
}

func readMobileConfig(r io.Reader) (mobileConfig, error) {
	c := mobileConfig{WGKeepalive: -1}
	data, err := io.ReadAll(io.LimitReader(r, 65537))
	if err != nil || len(data) > 65536 {
		return c, errors.New("configuration too large")
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&c); err != nil {
		return c, errors.New("invalid configuration")
	}
	if err := dec.Decode(new(any)); err != io.EOF {
		return c, errors.New("trailing configuration data")
	}
	if c.Mode != "wg" && c.Mode != "raw" {
		return c, errors.New("invalid mode")
	}
	host, port, err := net.SplitHostPort(c.Peer)
	pn, _ := strconv.Atoi(port)
	if err != nil || host == "" || pn < 1 || pn > 65535 {
		return c, errors.New("invalid server endpoint")
	}
	if c.Port < 1024 || c.Port > 65535 || len(c.Username) < 1 || len(c.Username) > 255 || len(c.SocksPassword) < 1 || len(c.SocksPassword) > 255 {
		return c, errors.New("proxy requires valid port and authentication")
	}
	if len(c.Password) < 1 || len(c.Password) > 4096 || len(c.Hash) < 16 || len(c.Hash) > 4096 || strings.ContainsAny(c.Hash, "\r\n\x00,") {
		return c, errors.New("invalid connection credentials")
	}
	if c.TurnPort < 0 || c.TurnPort > 65535 || len(c.TurnHost) > 253 {
		return c, errors.New("invalid TURN endpoint")
	}
	if c.DeviceID == "" {
		c.DeviceID = uuid.NewString()
	}
	if len(c.DeviceID) > 64 {
		return c, errors.New("invalid device identity")
	}
	if c.DNS == "" {
		c.DNS = "doh-cloudflare"
	}
	if c.Obfuscation == "" {
		c.Obfuscation = "audio"
	}
	if c.Obfuscation != "audio" && c.Obfuscation != "video" {
		return c, errors.New("invalid obfuscation")
	}
	if c.FallbackPort == 0 {
		c.FallbackPort = 56002
	}
	if c.FallbackPort < 1 || c.FallbackPort > 65535 {
		return c, errors.New("invalid fallback port")
	}
	if c.DTLSTimeoutSeconds == 0 {
		c.DTLSTimeoutSeconds = 20
	}
	if c.ConfigTimeoutSeconds == 0 {
		c.ConfigTimeoutSeconds = 15
	}
	if c.DTLSTimeoutSeconds < 5 || c.DTLSTimeoutSeconds > 60 || c.ConfigTimeoutSeconds < 5 || c.ConfigTimeoutSeconds > 60 {
		return c, errors.New("invalid phase timeout")
	}
	if err := (tunnelOptions{DNS: c.TunnelDNS, MTU: c.TunnelMTU, Keepalive: c.WGKeepalive}).validate(); err != nil {
		return c, err
	}
	return c, nil
}

func (c mobileConfig) args() []string {
	mode := "socks"
	if c.Mode == "raw" {
		mode = "rawtun"
	}
	args := []string{"-mode", mode, "-n", "2", "-listen", "127.0.0.1:0", "-peer", c.Peer,
		"-vk", c.Hash, "-password", c.Password, "-socks", net.JoinHostPort("127.0.0.1", strconv.Itoa(c.Port)),
		"-socks-auth=true", "-socks-user", c.Username, "-socks-pass", c.SocksPassword,
		"-dtls-timeout", strconv.Itoa(c.DTLSTimeoutSeconds), "-config-timeout", strconv.Itoa(c.ConfigTimeoutSeconds), "-tunnel-dns", c.TunnelDNS, "-tunnel-mtu", strconv.Itoa(c.TunnelMTU), "-wg-keepalive", strconv.Itoa(c.WGKeepalive),
		"-go-dns", c.DNS, "-obfs", c.Obfuscation, "-device-id", c.DeviceID}
	if c.TurnHost != "" {
		args = append(args, "-turn", c.TurnHost)
	}
	if c.TurnPort != 0 {
		args = append(args, "-port", strconv.Itoa(c.TurnPort))
	}
	if c.TurnTCP {
		args = append(args, "-turn-tcp")
	}
	if c.AutoPeer && c.Mode == "wg" && !c.NoDTLS {
		args = append(args, "-auto-peer", "-fallback-port", strconv.Itoa(c.FallbackPort))
	}
	if c.NoDTLS {
		args = append(args, "-notls")
	}
	return args
}

// Only a bounded first stdin line carries configuration; no credentials in /proc command line.
func main() {
	reader := bufio.NewReaderSize(os.Stdin, 65538)
	line, err := reader.ReadSlice('\n')
	if err != nil || len(line) > 65536 {
		fmt.Fprintln(protocolOutput, "ERROR configuration")
		os.Exit(2)
	}
	cfg, err := readMobileConfig(bytes.NewReader(line))
	if err != nil {
		fmt.Fprintln(protocolOutput, "ERROR configuration")
		os.Exit(2)
	}
	mobileProtocolActive = true
	controlInput = reader
	log.SetOutput(io.Discard)
	null, err := os.OpenFile(os.DevNull, os.O_WRONLY, 0)
	if err != nil {
		os.Exit(2)
	}
	defer null.Close()
	os.Stdout = null
	// Go's in-memory argv is separate from the kernel's process command line.
	os.Args = append([]string{"qwdtt-mobile"}, cfg.args()...)
	runCli()
}

func emitMobileError(kind string) {
	if !mobileProtocolActive {
		return
	}
	switch kind {
	case "AUTH", "VK_CAPTCHA", "WG_CONFIG", "WG_HANDSHAKE", "RAW_CONFIG", "RAW_PROTOCOL":
		fmt.Fprintln(protocolOutput, "ERROR "+kind)
	}
}
