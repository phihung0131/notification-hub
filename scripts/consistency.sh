#!/bin/bash
# Correctness checks under concurrency (needs running stack + docker compose exec postgres).
#  A) 50 concurrent sends with the SAME Idempotency-Key => exactly 1 notification row
#  B) same key, different payload => 422
#  C) quota limit 25, 100 concurrent distinct sends => exactly 25 accepted, quota_used == 25
set -uo pipefail
GW=${GW:-http://localhost:9000}; TENANT=${TENANT:-http://localhost:9001/api/v1/tenants}; NOTI=${NOTI:-http://localhost:9002/api/v1/notifications}
TS=$(date +%s); EXPIRES=$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
psqlq() { docker compose exec -T postgres psql -U postgres -d "$1" -tAc "$2"; }
FAILS=0; check() { if [ "$2" = "$3" ]; then echo "PASS $1 ($2)"; else echo "FAIL $1: expected $3 got $2"; FAILS=$((FAILS+1)); fi; }

curl -sf -X POST "$TENANT/auth/register" -H 'Content-Type: application/json' -d "{\"name\":\"C$TS\",\"email\":\"c$TS@example.com\",\"password\":\"password\"}" >/dev/null
TOKEN=$(curl -sf -X POST "$TENANT/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"c$TS@example.com\",\"password\":\"password\"}" | field token)
PID=$(curl -sf -X POST "$TENANT/permissions" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "{\"name\":\"c-$TS\",\"type\":\"API\",\"description\":\"c\"}" | field id)
KEY=$(curl -sf -X POST "$TENANT/api-keys" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "{\"expiredAt\":\"$EXPIRES\",\"permissionIds\":[\"$PID\"]}" | field key)
curl -sf -X POST "$NOTI/channels" -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d "{\"name\":\"C$TS\",\"code\":\"C_$TS\"}" >/dev/null
TID=$(psqlq tenant_db "select id from tenants where email='c$TS@example.com'")
send() { # $1 idem-key (may be empty) $2 recipient
  local h=(); [ -n "$1" ] && h=(-H "Idempotency-Key: $1")
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GW/api/v1/notifications/send" -H 'Content-Type: application/json' -H "X-Api-Key: $KEY" "${h[@]}" \
    -d "{\"channel\":\"C_$TS\",\"recipient\":\"$2\",\"subject\":\"s\",\"content\":\"c\"}"
}
export -f send; export GW KEY TS

# warm-up (gRPC channels)
send "" warm@example.com >/dev/null; send "" warm2@example.com >/dev/null

# A) same key concurrently
CODES=$(seq 50 | xargs -P25 -I{} bash -c 'send "idem-'$TS'" same@example.com' | sort | uniq -c | tr '\n' ' ')
echo "A) status codes: $CODES"
sleep 3
check "A idempotent: rows for key" "$(psqlq notification_db "select count(*) from notifications where tenant_id='$TID' and idempotency_key='idem-$TS'")" 1

# B) same key different payload
check "B different payload => 422" "$(send "idem-$TS" other@example.com)" 422

# C) quota
psqlq tenant_db "update tenants set quota_limit=25, quota_used=0 where id='$TID'" >/dev/null
docker compose exec -T redis-core redis-cli --scan --pattern "*$TID*" | xargs -r docker compose exec -T redis-core redis-cli del >/dev/null
CODES=$(seq 100 | xargs -P25 -I{} bash -c 'send "" "q{}-'$TS'@example.com"' | sort | uniq -c | tr '\n' ' ')
echo "C) status codes: $CODES"
sleep 15
ACC=$(psqlq notification_db "select count(*) from notifications where tenant_id='$TID' and recipient like 'q%-$TS@example.com'")
check "C accepted rows <= limit (25)" "$ACC" 25
check "C quota_used == 25" "$(psqlq tenant_db "select quota_used from tenants where id='$TID'")" 25
[ $FAILS -eq 0 ] && echo "CONSISTENCY OK" || { echo "CONSISTENCY FAILED ($FAILS)"; exit 1; }
