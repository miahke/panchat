# NYM feature port

Source: Spl0itable/NYM, revision ead1401c81160043f0f8770d7d7fe3fa17601ca0.
Requested: AGPLv3 and the messaging features discussed in the comparison.
Implementation remains native Kotlin/Compose, preserving Android 10+, stock
Bitchat message compatibility, Tor/qWDTT and the existing deployment system.
Previously removed Cups and mandatory password-manager unlock stay removed.

## Approach

Port protocol and behavior with source attribution into the existing client.
Embedding the Flutter application would duplicate identity, storage and routing;
copying its UI directly would not compile in Compose. Those alternatives are
rejected. New capabilities must be explicit and negotiated; never silently
replace stock encryption or bypass the configured proxy.

## Implementation sequence and acceptance

- [x] AGPL text, preservation of original component licenses and NYM notices.
- [x] First slice: literal keyword/phrase filtering in text and sender names;
  revealable placeholders, persistent settings, no matching-content notification;
  hidden incoming images with explicit reveal before decoding/display.
- [x] Reading settings: message font sizes 85/100/115/130/150 percent of the
  device font scale, and persistent 12/24-hour message timestamps.
- [ ] Threads/replies: preserve ordinary-message fallback; NIP-10 public roots,
  conversation-local private references, reject cross-conversation references.
- [ ] Channels: favorites, invitations/share links, named topics; validate links
  without automatic publishing or geolocation, retain stock geohash channels.
- [ ] Presentation: font size, wallpapers, notification sounds, time format,
  auto-scroll controls, rich text and custom emoji with bounded safe rendering.
- [ ] Reactions and polls: standards-based Nostr events, deduplicate votes,
  authenticate authors, show compatibility limitations for stock peers.
- [ ] Private groups: encrypted per-member fanout, roles, revocable invitations,
  optional history, offline key resync, bounded membership and abuse limits.
- [ ] Ghost Mode: complete mesh identity rotation and retained late-reply keys;
  physical interop and reconnect tests before enabling.
- [ ] Calls and files: explicit transport integration; no direct WebRTC/STUN
  fallback when Tor/qWDTT is selected. Test egress before exposing controls.
- [ ] Post-quantum extension: independent protocol/cryptographic review,
  authenticated capabilities, cross-device migration and downgrade tests.
- [ ] Presence/read receipts, disappearing messages and away replies: opt-in,
  bounded TTL, no promise to erase other people's copies or public relay data.
- [ ] Optional external features (translation, bot, payments/shop): awaiting
  scope clarification; no external integrations enabled by default.

For each slice: behavior tests first, implementation, focused regression tests,
build and local review. Publish only verified functionality, identify remaining
device/network checks. Preserve actual source attribution rather than claiming
all NYM features have been implemented after adding files or buttons.
