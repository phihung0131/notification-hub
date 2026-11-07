#!/bin/bash
# End-to-end smoke test through the gateway. Only needs curl on the host.
# Usage: ./scripts/smoke.sh   (stack must be up: make up)
set -euo pipefail

GW=${GW:-http://localhost:9000}
TENANT=${TENANT:-http://localhost:9001/api/v1/tenants}
NOTI=${NOTI:-http://localhost:9002/api/v1/notifications}
TS=$(date +%s)
EXPIRES=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
fail() { echo "SMOKE FAIL: $*"; exit 1; }

curl -sf -X POST "$TENANT/auth/register" -H 'Content-Type: application/json' \
  -d "{\"name\":\"T$TS\",\"email\":\"t$TS@example.com\",\"password\":\"password\"}" >/dev/null || fail register
TOKEN=$(curl -sf -X POST "$TENANT/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"t$TS@example.com\",\"password\":\"password\"}" | field token)
[ -n "$TOKEN" ] || fail login

PID=$(curl -sf -X POST "$TENANT/permissions" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d "{\"name\":\"send-$TS\",\"type\":\"API\",\"description\":\"smoke\"}" | field id)
[ -n "$PID" ] || fail permission
KEY=$(curl -sf -X POST "$TENANT/api-keys" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d "{\"expiredAt\":\"$EXPIRES\",\"permissionIds\":[\"$PID\"]}" | field key)
[ -n "$KEY" ] || fail api-key
curl -sf -X POST "$NOTI/channels" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d "{\"name\":\"Smoke$TS\",\"code\":\"SMOKE_$TS\"}" >/dev/null || fail channel

CODE=$(curl -s -o /tmp/smoke_resp.json -w '%{http_code}' -X POST "$GW/api/v1/notifications/send" \
  -H 'Content-Type: application/json' -H "X-Api-Key: $KEY" \
  -d "{\"channel\":\"SMOKE_$TS\",\"recipient\":\"r@example.com\",\"subject\":\"s\",\"content\":\"c\"}")
[ "$CODE" = 200 ] || [ "$CODE" = 202 ] || fail "send returned HTTP $CODE: $(cat /tmp/smoke_resp.json)"
echo "SMOKE OK (send HTTP $CODE)"
