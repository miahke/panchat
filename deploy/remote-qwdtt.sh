#!/bin/bash
# Run through pinned SSH as root. Never download executable code.
set -euo pipefail
umask 077
hash=${1:-}
[[ "$hash" =~ ^[a-f0-9]{64}$ ]] || exit 2
[[ $(id -u) == 0 && $(uname -m) == x86_64 ]] || exit 2
. /etc/os-release
[[ "$ID" == debian && -c /dev/net/tun ]] || exit 2
cd /opt/panchan/staging
printf '%s  qwdtt-server\n' "$hash" | sha256sum -c - >/dev/null
# Dependencies come from the administrator's configured Debian repositories.
if ! command -v ip >/dev/null || ! command -v iptables >/dev/null || ! command -v openssl >/dev/null || ! command -v python3 >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get -q update >/dev/null
  apt-get -qy install iproute2 iptables openssl python3 ca-certificates >/dev/null
fi
install -d -m 700 /opt/panchan/qwdtt /etc/panchan/qwdtt /var/lib/panchan/qwdtt
exec 9>/opt/panchan/qwdtt/deploy.lock
flock -n 9 || exit 3
root=/opt/panchan/qwdtt
config=/etc/panchan/qwdtt
unit=/etc/systemd/system/panchan-qwdtt.service
had_unit=no
was_active=no
systemctl is-active --quiet panchan-qwdtt.service && was_active=yes
backup=$(mktemp -d "$root/rollback.XXXXXXXX")
if [[ -f "$unit" ]]; then cp -p "$unit" "$backup/service"; had_unit=yes; fi
if [[ -f "$root/server" ]]; then cp -p "$root/server" "$backup/server"; fi
committed=no
rollback() {
  result=$?
  if [[ "$committed" != yes ]]; then
    systemctl stop panchan-qwdtt.service >/dev/null 2>&1 || true
    if [[ "$had_unit" == yes ]]; then cp -p "$backup/service" "$unit"; else rm -f "$unit"; fi
    if [[ -f "$backup/server" ]]; then cp -p "$backup/server" "$root/server"; else rm -f "$root/server"; fi
    systemctl daemon-reload
    if [[ "$was_active" == yes ]]; then systemctl start panchan-qwdtt.service; fi
  fi
  rm -rf "$backup"
  exit "$result"
}
trap rollback EXIT
# Never replace a previously deployed owner's secrets or password database.
for name in owner-password admin-token; do
  if [[ ! -f "$config/$name" ]]; then openssl rand -hex 32 > "$config/$name"; fi
  chmod 600 "$config/$name"
done
if [[ ! -f "$config/admin.crt" && ! -f "$config/admin.key" ]]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
    -subj /CN=localhost -addext subjectAltName=DNS:localhost \
    -keyout "$config/admin.key" -out "$config/admin.crt" >/dev/null 2>&1
fi
[[ -s "$config/admin.crt" && -s "$config/admin.key" ]]
chmod 600 "$config/admin.key" "$config/admin.crt"
if [[ "$had_unit" == no ]]; then
  # Refuse to displace another daemon using our interfaces or listeners.
  ! ip link show wdtt0 >/dev/null 2>&1
  ! ip link show wdttraw0 >/dev/null 2>&1
  [[ -z $(ss -H -lun '( sport = :56000 or sport = :56002 or sport = :56003 or sport = :56004 )') ]]
  [[ -z $(ss -H -ltn 'sport = :56443') ]]
fi
systemctl stop panchan-qwdtt.service >/dev/null 2>&1 || true
install -m 700 qwdtt-server "$root/server"
cat > "$unit" <<'UNIT'
[Unit]
Description=Panchan qWDTT transport
Wants=network-online.target
After=network-online.target
[Service]
Type=simple
ExecStart=/opt/panchan/qwdtt/server -config-dir /var/lib/panchan/qwdtt -password-file /etc/panchan/qwdtt/owner-password -listen 0.0.0.0:56000 -listen-direct 0.0.0.0:56002 -listen-raw 0.0.0.0:56003 -admin-listen 127.0.0.1:56443 -admin-token-file /etc/panchan/qwdtt/admin-token -admin-cert /etc/panchan/qwdtt/admin.crt -admin-key /etc/panchan/qwdtt/admin.key
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=yes
PrivateTmp=yes
ProtectHome=yes
ProtectSystem=full
LimitNOFILE=65536
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl start panchan-qwdtt.service
# API readiness plus all public transport listeners, not just process existence.
python3 - <<'PY'
import json, pathlib, ssl, time, urllib.request
config=pathlib.Path('/etc/panchan/qwdtt')
ctx=ssl.create_default_context(cafile=str(config/'admin.crt'))
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=ctx))
request=urllib.request.Request('https://localhost:56443/admin/passwords',headers={'Authorization':'Bearer '+(config/'admin-token').read_text().strip()})
for attempt in range(30):
    try:
        with opener.open(request,timeout=2) as response:
            assert isinstance(json.load(response)['passwords'],list)
        break
    except Exception:
        if attempt==29: raise SystemExit('qWDTT readiness failed')
        time.sleep(1)
PY
systemctl is-active --quiet panchan-qwdtt.service
for port in 56000 56002 56003; do [[ -n $(ss -H -lun "sport = :$port") ]]; done
systemctl enable panchan-qwdtt.service >/dev/null 2>&1
committed=yes
printf 'QWDTT_READY\n'
