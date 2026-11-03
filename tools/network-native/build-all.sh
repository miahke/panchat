#!/usr/bin/env bash
set -euo pipefail
network_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$network_root"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 27.3.13750724}"
tools/arti-build/build-arti.sh
for network_abi in arm64-v8a armeabi-v7a x86_64 x86; do
  for network_component in pt qwdtt; do
    tools/network-native/build-go.sh "$network_abi" "$network_component"
  done
done
tools/arti-build/verify-checksums.sh
