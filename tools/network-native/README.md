# Native network components

The APK embeds Arti, the obfs4/WebTunnel PT executable and the qWDTT mobile
executable for arm64-v8a, armeabi-v7a, x86_64 and x86. Corresponding source,
locked dependencies, build scripts and redistribution notices are included.

Use JDK 21, Go 1.27.3 and Android NDK r27d. Set ANDROID_NDK_HOME to the NDK.

```sh
tools/arti-build/rebuild-in-container.sh
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  tools/network-native/build-go.sh "$abi" pt
  tools/network-native/build-go.sh "$abi" qwdtt
done
python3 tools/network-native/prepare-deployer.py --rebuild
./gradlew :app:assembleRelease
python3 tools/network-native/verify-package.py app/build/outputs/apk/release/app-universal-release-unsigned.apk
```

The checked-in native binaries are verified release inputs, not downloaded at
application startup. Rebuilding Arti refreshes tools/arti-build/SHA256SUMS.
The server ZIP includes source and licenses and is integrity-checked before
installation. A checksum alone does not authenticate where a ZIP came from.

The qWDTT stdin protocol carries configuration; process arguments do not
contain credentials. Its stdout contains allowlisted stages and numeric
STATS:workers:upBytes:downBytes records. Raw upstream logs are suppressed.
Network failure never enables a direct Nostr fallback.
