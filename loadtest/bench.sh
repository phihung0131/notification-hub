#!/usr/bin/env bash
# make bench SCENARIO=load  ->  clears Mailpit, runs k6 in a container, then runs the reconciliation verifier.
# All scenario knobs are env vars (RATE, DURATION, ...), see docs/BENCHMARKING.md.
set -uo pipefail
cd "$(dirname "$0")/.."
DC=${DC:-docker compose -f compose.yaml -f compose.loadtest.yaml}
export SCENARIO=${SCENARIO:-load}
export RUN_ID=${RUN_ID:-$(date +%Y%m%d-%H%M%S)-$SCENARIO}
mkdir -p "reports/$RUN_ID"
echo "run id: $RUN_ID"
[ "${KEEP_MAIL:-0}" = 1 ] || python3 verifier/verify.py --clear-mailpit || echo "(could not clear mailpit; mail checks may be noisy)"
# capture stack resource usage once per 5s during the run (cheap; one docker stats call)
( while :; do docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' >> "reports/$RUN_ID/docker-stats.csv" 2>/dev/null; sleep 5; done ) &
STATS=$!
$DC --profile loadtest run --rm --no-deps k6 | tee "reports/$RUN_ID/k6.log"
k6rc=${PIPESTATUS[0]}
kill $STATS 2>/dev/null
echo "$RUN_ID" > reports/.last
echo "k6 exit code: $k6rc (99 = thresholds crossed)"
if [ "${VERIFY:-1}" = 1 ]; then
  python3 verifier/verify.py --run-id "$RUN_ID" --reports reports; vrc=$?
else vrc=0; fi
[ $k6rc -eq 0 ] && [ $vrc -eq 0 ]
