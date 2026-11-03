#!/usr/bin/env bash
# Shared pinned SSH settings; callers provide a validated user@host target.
panchan_target=${1:?Expected user@host}
[[ $panchan_target =~ ^[a-zA-Z_][a-zA-Z0-9_.-]{0,63}@[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || { echo 'Invalid SSH target' >&2; exit 2; }
PANCHAN_SSH_OPTIONS=(-o StrictHostKeyChecking=yes -o ConnectTimeout=15)
if [[ -n ${PANCHAN_SSH_PORT:-} ]]; then
 [[ $PANCHAN_SSH_PORT =~ ^[0-9]{1,5}$ ]] && ((10#$PANCHAN_SSH_PORT>=1 && 10#$PANCHAN_SSH_PORT<=65535)) || { echo 'Invalid SSH port' >&2; exit 2; }
 PANCHAN_SSH_OPTIONS+=(-o "Port=$PANCHAN_SSH_PORT")
fi
if [[ -n ${PANCHAN_KNOWN_HOSTS:-} ]]; then PANCHAN_SSH_OPTIONS+=(-o "UserKnownHostsFile=$PANCHAN_KNOWN_HOSTS"); fi
if [[ -n ${PANCHAN_SSH_IDENTITY:-} ]]; then
 [[ -f $PANCHAN_SSH_IDENTITY ]] || { echo 'Missing SSH identity' >&2; exit 2; }
 PANCHAN_SSH_OPTIONS+=(-o IdentitiesOnly=yes -i "$PANCHAN_SSH_IDENTITY")
fi
if [[ -n ${PANCHAN_SSH_AUTH:-} ]]; then
 [[ $PANCHAN_SSH_AUTH == password || $PANCHAN_SSH_AUTH == publickey ]] || { echo 'Invalid SSH authentication' >&2; exit 2; }
 PANCHAN_SSH_OPTIONS+=(-o "PreferredAuthentications=$PANCHAN_SSH_AUTH")
fi
panchan_ssh_root() {
 local command=$1 escaped quoted
 escaped=${command//\'/\'\\\'\'}
 quoted="'$escaped'"
 ssh "${PANCHAN_SSH_OPTIONS[@]}" -- "$panchan_target" "if [ \"\$(id -u)\" = 0 ]; then exec sh -c $quoted; else exec sudo -n -- sh -c $quoted; fi"
}
