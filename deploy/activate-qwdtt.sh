#!/bin/bash
set -euo pipefail
[[ $# == 2 && -f $2 ]] || exit 2
source "$(dirname "$0")/ssh-common.sh" "$1"
if command -v sha256sum >/dev/null; then hash=$(sha256sum "$2" | cut -d' ' -f1)
else hash=$(shasum -a 256 "$2" | cut -d' ' -f1); fi
panchan_ssh_root "bash -s -- $hash" < "$(dirname "$0")/remote-qwdtt.sh"
