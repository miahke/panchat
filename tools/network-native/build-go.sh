#!/usr/bin/env bash
set -euo pipefail
network_root="$(cd "$(dirname "$0")/../.." && pwd)"
network_ndk="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r27d}"
network_abi="${1:?Expected Android ABI}"
network_component="${2:?Expected pt or qwdtt}"
case "$network_abi" in
  arm64-v8a) network_arch=arm64; network_target=aarch64-linux-android ;;
  armeabi-v7a) network_arch=arm; network_target=armv7a-linux-androideabi ;;
  x86_64) network_arch=amd64; network_target=x86_64-linux-android ;;
  x86) network_arch=386; network_target=i686-linux-android ;;
  *) echo "Unsupported ABI" >&2; exit 2 ;;
esac
case "$network_component" in
  pt) network_binary=libbitchat_pt.so; network_package=./cmd/lyrebird ;;
  qwdtt) network_binary=libbitchat_vk.so; network_package=. ;;
  *) echo "Unsupported component" >&2; exit 2 ;;
esac
export GOOS=android GOARCH="$network_arch" GOARM=7 CGO_ENABLED=1
export CC="$network_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${network_target}29-clang"
export CXX="$network_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${network_target}29-clang++"
export CGO_CFLAGS="-O2 -g0 -ffile-prefix-map=$network_root=/usr/src/bitchat"
export CGO_CXXFLAGS="$CGO_CFLAGS"
mkdir -p "$network_root/app/src/main/jniLibs/$network_abi"
cd "$network_root/native/$network_component"
go build -mod=readonly -trimpath -buildmode=pie \
  -ldflags='-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' \
  -o "$network_root/app/src/main/jniLibs/$network_abi/$network_binary" "$network_package"
