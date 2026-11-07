#!/usr/bin/env bash
# Inject / heal a single fault.  Usage: chaos/fault.sh inject|heal <fault>
#
# Faults:
#   kafka | redis-core | redis-edge | postgres | delivery | tenant | notification | gateway | analytics
#       FAULT_MODE=kill (default, docker kill + restart on heal) | pause (docker pause/unpause)
#   latency:<proxy> | loss:<proxy> | cut:<proxy>
#       Toxiproxy toxics (needs `--profile chaos` and the dependency routed through the proxy, see chaos/toxiproxy.sh).
#       LATENCY_MS (default 500), JITTER_MS (100), LOSS_PCT (30; implemented as toxicity of a 'timeout' toxic)
set -euo pipefail
cd "$(dirname "$0")/.."
DC=${DC:-docker compose -f compose.yaml -f compose.loadtest.yaml}
MODE=${FAULT_MODE:-kill}
action=${1:?inject|heal}; fault=${2:?fault name}

case "$fault" in
  kafka|redis-core|redis-edge|postgres|delivery|tenant|notification|gateway|analytics|schema-registry|mailpit)
    svc=$fault
    case "$fault" in delivery|tenant|notification|gateway|analytics) svc=$fault-service;; esac
    if [ "$MODE" = pause ]; then
      if [ "$action" = inject ]; then $DC pause "$svc"; else $DC unpause "$svc"; fi
    else
      if [ "$action" = inject ]; then $DC kill "$svc"; else $DC start "$svc"; fi
    fi ;;
  latency:*|loss:*|cut:*)
    kind=${fault%%:*}; proxy=${fault#*:}
    if [ "$action" = inject ]; then
      case "$kind" in
        latency) chaos/toxiproxy.sh toxic "$proxy" latency latency "${LATENCY_MS:-500}" jitter "${JITTER_MS:-100}" ;;
        loss)    chaos/toxiproxy.sh toxic "$proxy" timeout timeout 1 toxicity "$(awk "BEGIN{print ${LOSS_PCT:-30}/100}")" ;;
        cut)     chaos/toxiproxy.sh disable "$proxy" ;;
      esac
    else
      case "$kind" in cut) chaos/toxiproxy.sh enable "$proxy" ;; *) chaos/toxiproxy.sh clear "$proxy" ;; esac
    fi ;;
  *) echo "unknown fault $fault" >&2; exit 2 ;;
esac
echo "$(date -u +%T) $action $fault (mode=$MODE)"
