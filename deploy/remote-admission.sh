#!/usr/bin/env bash
# Shared by the CLI and Android preparers. No public transport listeners.
set -euo pipefail
binary_hash=$1
unit_hash=$2
. /etc/os-release
test "$ID" = debian
test "$(uname -m)" = x86_64
cd /opt/panchan/staging
exec 9>preparation.lock
flock -w 30 9
printf '%s  %s\n' "$binary_hash" "admission-$binary_hash" "$unit_hash" "unit-$unit_hash" | sha256sum -c - >/dev/null
if ! id panchan-admission >/dev/null 2>&1; then useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin panchan-admission; fi
install -d -m 755 /opt/panchan/admission/releases
install -d -m 755 "/opt/panchan/admission/releases/$binary_hash"
install -m 755 "admission-$binary_hash" "/opt/panchan/admission/releases/$binary_hash/panchan-admission"
previous=$(readlink /opt/panchan/admission/current || true)
unit_backup=$(mktemp /opt/panchan/staging/unit-backup.XXXXXX)
had_unit=no
if [[ -f /etc/systemd/system/panchan-admission.service ]]; then cp /etc/systemd/system/panchan-admission.service "$unit_backup"; had_unit=yes; fi
committed=no
rollback() {
 result=$?
 trap - EXIT HUP INT TERM
 set +e
 if [[ $committed != yes ]]; then
  systemctl stop panchan-admission.service
  if [[ -n $previous ]]; then
   ln -sfn "$previous" /opt/panchan/admission/rollback
   mv -Tf /opt/panchan/admission/rollback /opt/panchan/admission/current
  else rm -f /opt/panchan/admission/current; fi
  if [[ $had_unit == yes ]]; then cp "$unit_backup" /etc/systemd/system/panchan-admission.service
  else rm -f /etc/systemd/system/panchan-admission.service; fi
  systemctl daemon-reload
  if [[ -n $previous && $had_unit == yes ]]; then systemctl start panchan-admission.service; fi
 fi
 rm -f "$unit_backup"
 exit "$result"
}
trap rollback EXIT
trap 'exit 130' HUP INT TERM
ln -sfn "releases/$binary_hash" /opt/panchan/admission/next
mv -Tf /opt/panchan/admission/next /opt/panchan/admission/current
install -m 644 "unit-$unit_hash" /etc/systemd/system/panchan-admission.service
systemctl daemon-reload
healthy=no
if systemctl restart panchan-admission.service; then
 for attempt in {1..10}; do
  if /opt/panchan/admission/current/panchan-admission status >/dev/null 2>&1; then healthy=yes; break; fi
  sleep 0.5
 done
fi
if [[ $healthy != yes ]]; then
 echo 'Admission startup failed; restoring previous service.' >&2
 exit 1
fi
systemctl enable panchan-admission.service >/dev/null 2>&1
systemctl is-active --quiet panchan-admission.service
committed=yes
printf 'Local admission owner service installed. No forwarding enabled.\n'
