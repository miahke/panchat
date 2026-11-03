# Nymchat adaptations

Based on Nymchat by 21 Million LLC — https://github.com/Spl0itable/NYM.

Pinned source revision: `ead1401c81160043f0f8770d7d7fe3fa17601ca0`.
License: AGPL-3.0 with the additional attribution terms in NOTICE.
The full AGPL text is in the application root LICENSE.md.

`content_filter.dart` is the original reference implementation. The Kotlin
adaptation of `baseNym` and `keywordHit` lives in
`app/src/main/java/com/bitchat/android/content/ContentRules.kt`.
Unlike the original hidden-message policy, panchat preserves a revealable
placeholder and exempts local notices and outgoing messages. Rules are literal
case-insensitive substrings, not executable regular expressions. No NYM-hosted
service, account, analytics or remote filter pack is contacted.
