#!/usr/bin/env bash
# Throwaway sshd for the Phase 02 transport tests (born in the SSH spike): loopback only, publickey only, test keys only, runs as the current user.
# The system sshd and ~/.ssh/authorized_keys are never touched. The emulator reaches it at 10.0.2.2:2222.
#   tools/test-sshd.sh start | stop | status | authorize <pubkey-file> | log
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="${TEST_SSHD_RUN:-$HERE/../build/test-sshd}"; mkdir -p "$RUN"
PORT="${TEST_SSHD_PORT:-2222}"
case "${1:-}" in
  start)
    [ -f "$RUN/host_ed25519" ] || ssh-keygen -q -t ed25519 -N '' -C paddock-test-sshd-host -f "$RUN/host_ed25519"
    touch "$RUN/authorized_keys"; : > "$RUN/sshd.log"
    cat > "$RUN/sshd_config" <<CFG
Port $PORT
ListenAddress 127.0.0.1
HostKey $RUN/host_ed25519
PidFile $RUN/sshd.pid
AuthorizedKeysFile $RUN/authorized_keys
AuthenticationMethods publickey
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
AllowUsers $USER
StrictModes no
LogLevel VERBOSE
AllowTcpForwarding no
AllowAgentForwarding no
X11Forwarding no
PermitTTY no
PrintMotd no
CFG
    # An isolated HOME for sessions (SetEnv is applied after sshd sets the default environment), so tests that install files
    # on the "host" write under a throwaway directory, not the real home.
    [ -z "${TEST_SSHD_HOME:-}" ] || echo "SetEnv HOME=$TEST_SSHD_HOME" >> "$RUN/sshd_config"
    /usr/bin/sshd -f "$RUN/sshd_config" -E "$RUN/sshd.log"
    sleep 0.5; echo "sshd on 127.0.0.1:$PORT pid $(cat "$RUN/sshd.pid")";;
  stop)  [ -f "$RUN/sshd.pid" ] && kill "$(cat "$RUN/sshd.pid")" 2>/dev/null || true; rm -f "$RUN/sshd.pid"; echo stopped;;
  status) [ -f "$RUN/sshd.pid" ] && kill -0 "$(cat "$RUN/sshd.pid")" 2>/dev/null && echo "running pid $(cat "$RUN/sshd.pid")" || { echo "not running"; exit 1; };;
  authorize) grep -qxF "$(cat "$2")" "$RUN/authorized_keys" 2>/dev/null || cat "$2" >> "$RUN/authorized_keys"; wc -l < "$RUN/authorized_keys";;
  log) cat "$RUN/sshd.log";;
  fingerprint) ssh-keygen -lf "$RUN/host_ed25519.pub";;
  *) echo "usage: $0 start|stop|status|authorize FILE|log|fingerprint" >&2; exit 2;;
esac
