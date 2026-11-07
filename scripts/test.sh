#!/bin/bash

# =======================
# ===== CONFIG TEST =====
# =======================

HOST_TENANT="localhost:9001/api/v1/tenants"
HOST_NOTIFICATION="localhost:9002/api/v1/notifications"
NOTI_SEND_HOST="localhost:9000/api/v1/notifications/send"

# Benchmark config (dễ chỉnh)
AB_REQUESTS=1500
AB_CONCURRENCY=100

# =======================
# ===== DYNAMIC DATA ====
# =======================

TS=$(date +%s)  # timestamp để tránh trùng lặp dữ liệu

TENANT_EMAIL="test_${TS}@example.com"
TENANT_NAME="Tenant_${TS}"

PERMISSION_NAME="discord:send-messages-${TS}"

CHANNEL_CODE="TEST_${TS}"
CHANNEL_NAME="TestChannel_${TS}"

echo "=========================================="
echo "  API TEST + BENCHMARK (CLEAN VERSION)"
echo "=========================================="
echo ""
echo "Using dynamic data:"
echo "  Email: $TENANT_EMAIL"
echo "  Permission: $PERMISSION_NAME"
echo "  Channel: $CHANNEL_CODE / $CHANNEL_NAME"
echo ""

# -------- STEP 1: Register --------
echo "[1] Registering tenant..."

REGISTER_RESPONSE=$(curl -s -X POST "http://${HOST_TENANT}/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"${TENANT_NAME}\",
    \"email\": \"${TENANT_EMAIL}\",
    \"password\": \"password\"
  }")

echo "Register Response: $REGISTER_RESPONSE"
echo ""

# -------- STEP 2: Login --------
echo "[2] Logging in..."

LOGIN_RESPONSE=$(curl -s -X POST "http://${HOST_TENANT}/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"email\": \"${TENANT_EMAIL}\",
    \"password\": \"password\"
  }")

TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"token":"[^"]*"' | sed 's/"token":"\([^"]*\)"/\1/')

if [ -z "$TOKEN" ]; then
  echo "ERROR: Failed to extract token"
  exit 1
fi

echo "Token extracted."
echo ""

# -------- STEP 3: Create Permission --------
echo "[3] Creating permission..."

PERMISSION_RESPONSE=$(curl -s -X POST "http://${HOST_TENANT}/permissions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"name\": \"${PERMISSION_NAME}\",
    \"type\": \"API\",
    \"description\": \"Permission to send notifications\"
  }")

PERMISSION_ID=$(echo "$PERMISSION_RESPONSE" | grep -o '"id":"[^"]*"' | sed 's/"id":"\([^"]*\)"/\1/')

if [ -z "$PERMISSION_ID" ]; then
  echo "ERROR: Failed to extract permission ID"
  exit 1
fi

echo "Permission ID: $PERMISSION_ID"
echo ""

# -------- STEP 4: Create API Key --------
echo "[4] Creating API key..."

API_KEY_RESPONSE=$(curl -s -X POST "http://${HOST_TENANT}/api-keys" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"expiredAt\": \"2026-12-31T23:59:59Z\",
    \"permissionIds\": [\"$PERMISSION_ID\"]
  }")

API_KEY=$(echo "$API_KEY_RESPONSE" | grep -o '"key":"[^"]*"' | sed 's/"key":"\([^"]*\)"/\1/')

if [ -z "$API_KEY" ]; then
  echo "ERROR: Failed to extract API key"
  exit 1
fi

echo "API Key created."
echo ""

# -------- STEP 5: Create Channel --------
echo "[5] Creating notification channel..."

curl -s -X POST "http://${HOST_NOTIFICATION}/channels" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"name\": \"${CHANNEL_NAME}\",
    \"code\": \"${CHANNEL_CODE}\"
  }"

echo "Channel created."
echo ""

# -------- STEP 6: Single request smoke test --------
echo "[6] Smoke test (single request)..."

TEST_RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" \
  -X POST "http://${NOTI_SEND_HOST}" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d "{
    \"channel\": \"${CHANNEL_CODE}\",
    \"recipient\": \"recipient\",
    \"subject\": \"subject\",
    \"content\": \"content\"
  }")

HTTP_CODE=$(echo "$TEST_RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)

if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "202" ]; then
  echo "ERROR: Single request failed with HTTP $HTTP_CODE"
  echo "$TEST_RESPONSE"
  exit 1
fi

echo "Single request OK (HTTP $HTTP_CODE)"
echo ""

# -------- STEP 7: Run Benchmark --------
echo "[7] Running benchmark: ${AB_REQUESTS} requests, ${AB_CONCURRENCY} concurrency"

cat > /tmp/notification.json << EOF
{
  "channel": "${CHANNEL_CODE}",
  "recipient": "recipient",
  "subject": "subject",
  "content": "content"
}
EOF

ab -n ${AB_REQUESTS} -c ${AB_CONCURRENCY} \
  -p /tmp/notification.json \
  -T "application/json" \
  -H "X-Api-Key: $API_KEY" \
  http://${NOTI_SEND_HOST}

rm -f /tmp/notification.json

echo ""
echo "=========================================="
echo "TEST COMPLETED"
echo "=========================================="
