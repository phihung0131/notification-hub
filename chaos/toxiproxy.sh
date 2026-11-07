#!/usr/bin/env bash
# Thin wrapper around the Toxiproxy admin API (default http://localhost:8474).
#   chaos/toxiproxy.sh create <name> <listen host:port> <upstream host:port>
#   chaos/toxiproxy.sh toxic  <name> <type> <attr> <value> [<attr> <value>...]   (type: latency|timeout|bandwidth|slicer|...; attr "toxicity" is the 0..1 probability)
#   chaos/toxiproxy.sh clear|disable|enable <name> | list | reset
#
# Routing a dependency through Toxiproxy (it is NOT in the data path by default):
#   1. docker compose -f compose.yaml -f compose.loadtest.yaml --profile chaos up -d toxiproxy
#   2. chaos/toxiproxy.sh create redis-core 0.0.0.0:6379 redis-core:6379
#   3. Point the client at toxiproxy, e.g. in a local override file:
#        services: { tenant-service: { environment: { SPRING_DATA_REDIS_HOST: toxiproxy } } }
#      Postgres: proxy 'postgres' 0.0.0.0:5432 -> postgres:5432, client host toxiproxy.
#      gRPC: proxy 'tenant-grpc' 0.0.0.0:50001 -> tenant-service:50001, point the gateway/notification gRPC host at toxiproxy.
#      Kafka needs advertised listeners that resolve back to the proxy, so prefer kill/pause for Kafka.
#   4. FAULT=latency:redis-core make chaos
set -euo pipefail
API=${TOXIPROXY_URL:-http://localhost:8474}
cmd=${1:?cmd}; shift || true
case "$cmd" in
  create)
    n=$1; listen=$2; up=$3
    curl -sf -X POST "$API/proxies" -d "{\"name\":\"$n\",\"listen\":\"$listen\",\"upstream\":\"$up\",\"enabled\":true}" >/dev/null \
      || echo "(proxy $n may already exist)" ;;
  toxic)
    n=$1; type=$2; shift 2
    attrs='{}'; tox=1
    while [ $# -gt 1 ]; do
      if [ "$1" = toxicity ]; then tox=$2; else attrs=$(jq -c --arg k "$1" --arg v "$2" '.[$k]=($v|tonumber)' <<<"$attrs"); fi
      shift 2
    done
    curl -sf -X POST "$API/proxies/$n/toxics" -H 'Content-Type: application/json' \
      -d "$(jq -nc --arg t "$type" --argjson a "$attrs" --argjson x "$tox" '{name:("nh_"+$t),type:$t,stream:"downstream",toxicity:$x,attributes:$a}')" >/dev/null ;;
  clear)
    for t in $(curl -sf "$API/proxies/$1/toxics" | jq -r '.[].name'); do curl -sf -X DELETE "$API/proxies/$1/toxics/$t" >/dev/null; done ;;
  disable) curl -sf -X POST "$API/proxies/$1" -d '{"enabled":false}' >/dev/null ;;
  enable)  curl -sf -X POST "$API/proxies/$1" -d '{"enabled":true}' >/dev/null ;;
  list)    curl -sf "$API/proxies" | jq . ;;
  reset)   curl -sf -X POST "$API/reset" >/dev/null ;;
  *) echo "unknown cmd $cmd"; exit 2 ;;
esac
