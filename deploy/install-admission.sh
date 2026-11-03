#!/usr/bin/env bash
# Transactional installation of the local-only owner service.
set -euo pipefail
[[ $# == 2 && -f $2 ]] || { echo 'Usage: install-admission.sh user@host admission-binary' >&2; exit 2; }
source "$(dirname "$0")/ssh-common.sh" "$1"
if command -v sha256sum >/dev/null 2>&1; then local_hash_command=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then local_hash_command=(shasum -a 256)
else echo 'Install sha256sum or shasum' >&2; exit 2; fi
admission_unit=$(cd "$(dirname "$0")" && pwd)/panchan-admission.service
admission_hash=$("${local_hash_command[@]}" "$2" | cut -d' ' -f1)
admission_unit_hash=$("${local_hash_command[@]}" "$admission_unit" | cut -d' ' -f1)
[[ $admission_hash =~ ^[0-9a-f]{64}$ && $admission_unit_hash =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid checksum' >&2; exit 2; }
admission_tmp=$(mktemp -d)
trap 'rm -rf "$admission_tmp"' EXIT
install -m 600 "$2" "$admission_tmp/admission-$admission_hash"
install -m 600 "$admission_unit" "$admission_tmp/unit-$admission_unit_hash"
panchan_ssh_root 'install -d -m 700 /opt/panchan/staging'
tar -cf - -C "$admission_tmp" "admission-$admission_hash" "unit-$admission_unit_hash" | panchan_ssh_root 'tar --no-same-owner --no-same-permissions -xf - -C /opt/panchan/staging'
panchan_ssh_root "bash -s -- $admission_hash $admission_unit_hash" < "$(dirname "$0")/remote-admission.sh"
