# Panchan device admission (server component)

GPL-3.0-only. The included Go runtime uses its own BSD license; see licenses/Go-LICENSE. This Linux-only component has no nonstandard Go dependencies. Build using Go1.27.3:

```sh
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o panchan-admission ./cmd/panchan-admission
go test -race ./...
go vet ./...
```

## Current boundary

This component registers signing public keys, authenticates fresh domain-separated proofs, creates expiring leases and revokes them. It does not yet enforce authorization in the existing qWDTT or Cups forwarding paths. Those exits must not be advertised as protected by this component until every forwarding/configuration/worker path consumes and observes its leases. Existing Android test APKs do not yet enroll with this service. Device key possession is verified; hardware backing is **not** verified by these APIs. Do not describe a software signing key as hardware attested.

An owner-only Unix socket provides status, invitation issuance, approved-device listing and revocation. Kernel SO_PEERCRED must report uid0; a header or claimed role grants no authority. The supplied systemd unit runs as a dedicated account, allows only AF_UNIX, denies IP networking and limits resources. No TCP/UDP enrollment or admin listener is opened.

## Owner commands (on the server over authenticated SSH)

```sh
sudo /opt/panchan/admission/current/panchan-admission status
sudo /opt/panchan/admission/current/panchan-admission devices
sudo /opt/panchan/admission/current/panchan-admission issue -scope 3 -ttl 3600 -device-lifetime 2592000
sudo /opt/panchan/admission/current/panchan-admission share -scope 1 -ttl 3600 -device-lifetime 2592000 -name 'Connection profile' -profile-file /root/private-qwdtt-profile
sudo /opt/panchan/admission/current/panchan-admission revoke -device <approved-public-key-fingerprint>
```

New owner invitations allow only scope1: VK (WG and Raw). Legacy wire values2/3 remain readable for stored cryptographic records, with no active Cups transport. Share chooses one transport. The profile file must be private (no group/other permissions), bounded to32KiB; its contents never go on the command line. Share creates a signed panchan://v1 link containing the one-use identifier and profile. Profile-specific validation remains mandatory in the importing app. The matching issuer fingerprint must be obtained independently through the owner; a key embedded in a link does not authenticate itself. Command output can contain sensitive invitations/profiles; keep it private and out of logs. Do not send private keys or the local app password in a link.

An invitation is random256bits, valid at most7days, stored only as SHA-256, and atomically redeemed for exactly one public key. Device authorization lasts at most365days. Completed enrollments and revocations survive restarts. Each authentication challenge has a fresh nonce and30second deadline and binds server key, device key, operation, route scope and a32byte transport transcript digest. Signed server challenge and signed device proof use different domains. Wrong/expired/replayed proofs are denied. Clients must use Challenge.VerifyFor with independently trusted server identity and expected key/scope/transcript/expiry; signature verification alone is insufficient. The caller must supply a real channel transcript, not a placeholder; registration alone does not authenticate an unrelated channel.

Leases last at most15minutes or until the device grant expires. Revoke, close and storage failure close outstanding lease channels. Transport code must close the actual connection when Done closes and call Release on disconnect. In-memory challenges and leases are never restored after restart. Storage errors seal the service; no success is returned before atomic state persistence. Directory0700, file0600, no symlink store, exclusive owner flock. Keep protected backups of the state before upgrades; it includes the private issuer key. Loss of that state loses server trust and device approvals; never silently recreate it when an existing file is corrupt.

## Install and rollback

From the repository root, pin the SSH host key independently, then:

```sh
./deploy/install-admission.sh root@server.example.invalid ./panchan-admission
```

The installer checks hashes, installs versioned releases, probes the owner socket and restores the previous release/unit when startup fails. Repeated installs preserve the issuer and state. It never opens transport ports or changes firewall/routing. Failed release files may remain in the private staging directory for local investigation. Do not remove state as part of rollback. Schema migrations, owner key rotation, export/restore and full transport admission are still pending. No additional Astra review covered this component.
