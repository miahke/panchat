#!/usr/bin/env bash
# Stage verified cores without changing routing or enabling forwarding services.
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: stage-core.sh user@host qwdtt-server' >&2; exit 2; }
source "$(dirname "$0")/ssh-common.sh" "$1"
if command -v sha256sum >/dev/null 2>&1; then local_hash_command=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then local_hash_command=(shasum -a 256)
else echo 'Install sha256sum or shasum' >&2; exit 2; fi
[[ -f $2 ]] || { echo 'Missing core binaries' >&2; exit 2; }
stage_tmp=$(mktemp -d)
trap 'rm -rf "$stage_tmp"' EXIT
install -m 600 "$2" "$stage_tmp/qwdtt-server"
(cd "$stage_tmp" && "${local_hash_command[@]}" qwdtt-server > SHA256SUMS)
panchan_ssh_root 'set -eu; . /etc/os-release; test "$ID" = debian; test "$(uname -m)" = x86_64; install -d -m 700 /opt/panchan/staging'
tar -cf - -C "$stage_tmp" qwdtt-server SHA256SUMS | panchan_ssh_root 'set -eu; tar --no-same-owner --no-same-permissions -xf - -C /opt/panchan/staging; cd /opt/panchan/staging; sha256sum -c SHA256SUMS; chmod 700 qwdtt-server; chmod 600 SHA256SUMS'
echo 'Core staging verified. Forwarding services have not been started.'
