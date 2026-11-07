#!/usr/bin/env bash
# Chaos scenario runner: load -> inject fault at T+FAULT_AT -> heal at T+HEAL_AT -> wait for drain -> verify -> matrix row.
#   FAULT=kafka chaos/run.sh            (FAULT may be a comma list: FAULT=kafka,redis-core,postgres runs them one by one)
# Env: FAULT_AT=30 HEAL_AT=60 (seconds after load start) RATE=30 WARMUP=10s TAIL=60 (seconds of load after heal)
#      FAULT_MODE=kill|pause  DRAIN_TIMEOUT=180  MATRIX=reports/chaos-matrix.md  SCENARIO=load
set -uo pipefail
cd "$(dirname "$0")/.."
DC=${DC:-docker compose -f compose.yaml -f compose.loadtest.yaml}
FAULT_AT=${FAULT_AT:-30}; HEAL_AT=${HEAL_AT:-60}; TAIL=${TAIL:-60}
RATE=${RATE:-30}; WARMUP=${WARMUP:-10s}; SCENARIO=${SCENARIO:-load}
MATRIX=${MATRIX:-reports/chaos-matrix.md}
export DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-180}
mkdir -p "$(dirname "$MATRIX")"
[ -f "$MATRIX" ] || printf '| date | fault | mode | inject@ | heal@ | accepted | k6 thresholds | verifier | run |\n|---|---|---|---|---|---|---|---|---|\n' > "$MATRIX"

run_one() {
  local fault=$1 rid="chaos-${1//[^a-zA-Z0-9]/_}-$(date +%H%M%S)"
  local dur=$((HEAL_AT + TAIL))s
  mkdir -p "reports/$rid"
  echo "== $fault run=$rid: inject T+${FAULT_AT}s heal T+${HEAL_AT}s load ${dur}"
  python3 verifier/verify.py --clear-mailpit >/dev/null 2>&1 || true
  # inject/heal timeline relative to the end of warm-up, started in the background
  ( sleep $(( ${WARMUP%s} + FAULT_AT )); chaos/fault.sh inject "$fault"
    sleep $(( HEAL_AT - FAULT_AT )); chaos/fault.sh heal "$fault" ) &
  local tl=$!
  RUN_ID=$rid SCENARIO=$SCENARIO RATE=$RATE WARMUP=$WARMUP DURATION=$dur \
    ERR_RATE=1 P95_MS=60000 P99_MS=60000 \
    $DC --profile loadtest run --rm --no-deps k6 > "reports/$rid/k6.log" 2>&1
  local k6rc=$?
  wait $tl
  chaos/fault.sh heal "$fault" >/dev/null 2>&1 || true   # idempotent safety net
  echo "   healed; waiting for services and drain (verifier timeout ${DRAIN_TIMEOUT}s)"
  sleep "${SETTLE:-15}"
  RUN_ID=$rid REPORTS_DIR=reports python3 verifier/verify.py > "reports/$rid/verify.log" 2>&1
  local vrc=$?
  local acc; acc=$(jq '[.tenants[].accepted]|add' "reports/$rid/manifest.json" 2>/dev/null || echo "?")
  local v=PASS; [ $vrc -ne 0 ] && v=FAIL
  local k=OK; [ $k6rc -ne 0 ] && k="rc=$k6rc"
  printf '| %s | %s | %s | %ss | %ss | %s | %s | **%s** | %s |\n' "$(date +%F)" "$fault" "${FAULT_MODE:-kill}" "$FAULT_AT" "$HEAL_AT" "$acc" "$k" "$v" "$rid" >> "$MATRIX"
  echo "   verifier: $v (details reports/$rid/verify.md)"
  return $vrc
}

rc=0
IFS=',' read -ra FAULTS <<< "${FAULT:?set FAULT=kafka|redis-core|postgres|delivery|tenant|latency:<proxy>|...}"
for f in "${FAULTS[@]}"; do run_one "$f" || rc=1; done
echo "matrix: $MATRIX"; tail -n "${#FAULTS[@]}" "$MATRIX"
exit $rc
