# Networking correctness review

Date: 2026-11-02

This is the single requested GPT-6 Astra correctness pass. It covers the qWDTT and Cups/OpenFlux runtime, protected AUTO selection, configuration publication, WireGuard loopback binding/readiness, Raw v2, lifecycle/status propagation, profile persistence/tuning/import, and VK login/session handling. Production source was reviewed read-only; only this report was added. Findings describe the source observed during the pass, before the implementing agent's subsequent fixes. This report is not a second review or verification of those fixes.

## Confirmed findings

### P1 — A silent qWDTT peer remains ready indefinitely

Locations: `native/qwdtt/session.go:721`, `native/qwdtt/session.go:729`, `native/qwdtt/socks5.go:149`.

After initial readiness, the session reader resets a 30-minute deadline and continues on every read timeout. It never expires a worker for lack of authenticated peer traffic. The readiness monitor checks only whether a worker is registered. If the VPS stops responding while TURN remains reachable, UDP writes and TURN binding requests can continue succeeding; workers stay registered, no reconnect is triggered, and the application keeps reporting READY despite unavailable forwarding.

Reproduction: establish a synthetic session, then drop all VPS-to-client responses while leaving the client-to-TURN connection operational. The reader's timeout branch repeats instead of terminating the worker, and the monitor continues observing a nonempty worker list. This finding is established by the source control flow; a live outage experiment was not run.

Fix direction: use bounded authenticated peer liveness and retire unhealthy workers. The server currently discards WG keepalives without replying (`server/qwdtt/server/connections.go:359`), so merely shortening the read deadline would also disconnect healthy idle sessions. Add a compatible authenticated heartbeat response or another positive health signal. On reconnect, `native/qwdtt/session.go:583` sends AUTH and registers without awaiting a response; transport recovery must likewise depend on positive peer confirmation.

### P1 — Ordinary Raw packet reordering disconnects the session

Locations: `native/qwdtt/raw_v2.go:169`, `native/qwdtt/session.go:732`; matching server logic: `server/qwdtt/server/raw_v2.go:166`.

Raw v2 accepts only strictly increasing counters. Receiving fresh record 2 followed by fresh record 1 returns a fatal “replayed Raw record” error for record 1. The session reader then terminates the transport. These records travel over a datagram path, where reordering is normal, and the outer RTP replay filter already permits bounded reordering. TURN TCP does not make the TURN-to-peer UDP hop ordered.

Reproduction executed locally: copy the reviewed Raw v2 implementation into an isolated synthetic test, create matching client/server session keys from fixed synthetic nonces, encrypt two records, deliver the second followed by the first, and verify that the second read produces the fatal replay error. The reproduction confirmed the defect under the race detector. This is a test of the observed failure, not a passing regression test for a fix.

Fix direction: maintain a bounded replay window per session/direction, accept each fresh in-window record once, and skip duplicate or stale datagrams without killing a healthy transport. Preserve cross-session and cross-direction authentication rejection.

### P1 — A sibling Raw handshake timeout kills a healthy worker

Locations: `native/qwdtt/session.go:481`, `native/qwdtt/session.go:483`, `app/src/main/java/com/bitchat/android/net/route/NativeProcessBackend.kt:121`, `app/src/main/java/com/bitchat/android/net/route/NativeProcessBackend.kt:128`.

Every Raw handshake error emits process-level `ERROR RAW_PROTOCOL`, including an ordinary timeout on one worker. The mobile runtime starts two workers. If worker A reaches READY while worker B's relay loses handshake packets, B emits the fatal error at its deadline. The Kotlin status reader then forcibly terminates the entire native child, including healthy worker A. Before READY, the same record aborts startup even when another worker can complete it.

Reproduction recipe: permit worker A's authenticated Raw handshake/configuration and withhold worker B's handshake response until its configured deadline. The emitted ERROR is consumed as a fatal process error. This finding is established from both ends of the status protocol; no live relay experiment was run.

Fix direction: distinguish retryable per-worker timeout/cancellation from terminal protocol/authentication errors. Retry transient failures without emitting a process-fatal status for an otherwise viable channel; retain fail-closed handling of genuinely invalid credentials/protocols.

### P2 — The configured Cups startup timeout does not reach its handshake

Locations: `native/openflux/client.go:155`, `native/openflux/client.go:160`, `native/openflux/upstream/transport/session.go:216`, `native/openflux/upstream/transport/session.go:393`.

The client constructs a configurable startup deadline of 15–300 seconds but synchronously calls `Session.Start()`. Session independently uses its fixed 20-second handshake timeout and stops itself on expiry. The configurable outer deadline can shorten startup but cannot extend that handshake. The additional 90-second poll after Start cannot help because Start has already completed the handshake or returned an error. `STAGE:SESSION_HANDSHAKE` is also emitted only after that synchronous handshake has finished.

Reproduction recipe: use a synthetic carrier that starts successfully and delays its first authenticated peer handshake response for 25 seconds, with a configured 90-second startup budget. The internal 20-second wait aborts first. This finding is source-confirmed; a separate delayed-carrier integration run was not performed during this pass.

Fix direction: propagate the remaining startup budget into Session's handshake wait, retain cancellation during room joins, and emit the handshake stage while that phase is actually pending.

## Validation actually run

All commands used offline dependency resolution (`GOPROXY=off`, `GOSUMDB=off`, `GOTOOLCHAIN=local`), synthetic inputs, and no live-test options.

| Working directory | Command | Result |
| --- | --- | --- |
| `native/qwdtt` | `go test -race -p=2 -timeout=120s ./...` | PASS |
| `native/openflux` | `go test -race -p=2 -timeout=120s .` | PASS |
| `native/openflux/upstream` | `go test -race -p=2 -timeout=120s ./transport` | PASS |
| Isolated synthetic Raw test | `go test -race -p=2 -timeout=10s raw_v2.go audit_test.go` | Confirmed fresh-record reordering failure |

The existing passing suites do not cover the four scenarios above. No Gradle commands, physical-device operations, live provider/VPS requests, external uploads, or private credentials/logs were used. Kotlin changes were reviewed as source; their build/test results were not independently executed in this pass.

## Scope observations and limits

The reviewed mobile configuration fixes worker count at two and requires a nonempty password from which the RTP wrapping key is derived. AUTO candidates use protected DTLS/WRAP or protected RTP plus WireGuard; there is no intentional plaintext AUTO fallback. The WireGuard bind pins the exact loopback dispatcher endpoint, rejects endpoint hostnames/public addresses, filters incoming source addresses, and rejects sends to a changed endpoint. Initial WireGuard readiness waits for an actual handshake; SOCKS DNS goes through the tunnel stack.

Cups remains an authenticated encrypted session with only the Cups carrier installed; its imported original context is used for key derivation. The reviewed VK callback parser enforces the HTTPS redirect origin/path, rejects duplicate parameters, checks the state nonce, and does not use a JavaScript credential bridge. VK hash requests have cancellation and generation checks. No additional confirmed correctness finding arose from the profile/tuning/import or VK source inspection.

These observations are bounded source-review results, not proof of complete security or production connectivity. Hardware admission enforcement, the full vault/two-edition design, and production deployment activation were explicitly outside this pass. Fix implementation and regression verification belong to the main implementing agent; no second completed audit was performed.
