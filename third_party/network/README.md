# Network source provenance

- Bitchat Android base: v2.2.0 / 20501fdfe0d56c7705c377d4a263b01d8f38eeeb; public-domain LICENSE.md retained.
- qWDTT: SpaceNeuroX/proxy-turn-vk-android a296c57eaba69bb9479a24f9157856490890e47d; GPL-3.0. Sources adapted in native/qwdtt and server/qwdtt; individual source notices retained. OAuth flow/client ID follows that project with tighter origin/state checks.
- OpenFlux: p1neappleXpress/OpenFlux edcf77a1848a72370cfba9f346d3b897b61e7e7d; GPL-3.0-or-later. Corresponding snapshot in native/openflux/upstream, including LICENSE and NOTICE. Only Cups/core dependencies are imported by the Android facade; other upstream tools are source context, not app features.
- Lyrebird: Tor Project 362a60bb4e3fa434130ef81a9990d83d6d8eaea5. Sources in native/pt; original source notices and LICENSE files retained. Registration is limited to obfs4/WebTunnel. WebTunnel module version is pinned by native/pt/go.mod/go.sum.
- Arti: arti-v1.9.0 / ba421ece1acb47e5c554c2bfa043cc9156451bab. Original pinned-source build instructions remain under tools/arti-build, with bridge/PT configuration and bounded authenticated SOCKS changes. Cargo.lock and native checksums updated.

This combined fork is distributed with GPL source obligations for the incorporated GPL components. Preserve all upstream licenses/notices and these modified corresponding sources when redistributing binaries. Tool versions and dependency graphs are recorded in the lockfiles and build instructions; no production credentials belong in source archives.

The panchat qWDTT connection pipeline and pinned statistics bar are adapted
from qWDTT's ConnectionPipeline.kt and LogsTab.kt (GPL-3.0). Counters come
from the original Stats worker/traffic counters through a bounded numeric
local pipe; no raw logs or credentials are exposed by this adaptation.
