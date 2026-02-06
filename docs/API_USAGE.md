# API Usage Guide

Complete guide for using Notification Hub APIs với practical examples.

---

## Table of Contents

1. [Authentication Flow](#authentication-flow)
2. [Sending Notifications](#sending-notifications)
3. [Checking Message Status](#checking-message-status)
4. [Managing API Keys](#managing-api-keys)
5. [Error Handling](#error-handling)
6. [Rate Limiting](#rate-limiting)

---

## Authentication Flow

### Step 1: Register Tenant

```bash
curl -X POST http://localhost:9000/api/v1/tenants/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Acme Corp",
    "email": "admin@acme.com",
    "password": "SecurePassword123!"
  }'
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": "tenant-uuid-123",
    "name": "Acme Corp",
    "email": "admin@acme.com",
    "plan": "FREE",
    "quotaLimit": 1000,
    "quotaUsed": 0,
    "status": "ACTIVE"
  },
  "traceId": "...",
  "ts": "2024-01-15T10:00:00Z"
}
```

### Step 2: Login (Get JWT Token)

```bash
curl -X POST http://localhost:9000/api/v1/tenants/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@acme.com",
    "password": "SecurePassword123!"
  }'
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "type": "Bearer",
    "expiresIn": 86400000
  }
}
```

**Save this JWT token** - required for tenant management operations.

### Step 3: Create API Key

```bash
# Use JWT token from Step 2
curl -X POST http://localhost:9000/api/v1/tenants/apikeys \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Production API Key",
    "permissions": ["SEND_NOTIFICATION", "VIEW_ANALYTICS"]
  }'
```

**Response (201 Created):**
```json
{
  "success": true,
  "data": {
    "id": "apikey-uuid-456",
    "key": "sk_live_abc123def456ghi789...",
    "name": "Production API Key",
    "permissions": ["SEND_NOTIFICATION", "VIEW_ANALYTICS"],
    "revoked": false,
    "createdAt": "2024-01-15T10:05:00Z"
  }
}
```

**⚠️ IMPORTANT:** Save the `key` value - it's only shown once!

---

## Sending Notifications

### Email Notification

```bash
curl -X POST http://localhost:9000/api/v1/notifications/send \
  -H "Authorization: Bearer sk_live_abc123def456..." \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "subject": "Welcome to Notification Hub!",
    "content": "Thank you for signing up. Click here to get started: https://app.example.com"
  }'
```

**Response (202 ACCEPTED):**
```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "tenantId": "tenant-uuid-123",
    "notificationStatus": "PENDING",
    "message": "Notification request accepted and queued for processing"
  },
  "traceId": "a1b2c3d4e5f6...",
  "ts": "2024-01-15T10:10:00Z"
}
```

**📝 Note:** Response is **202 ACCEPTED** (not 200 OK) because processing is asynchronous.

### SMS Notification

```bash
curl -X POST http://localhost:9000/api/v1/notifications/send \
  -H "Authorization: Bearer sk_live_abc123def456..." \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "SMS",
    "recipient": "+12345678901",
    "subject": "",
    "content": "Your verification code is: 123456"
  }'
```

**Note:** SMS không cần subject field (set to empty string).

### Telegram Notification

```bash
curl -X POST http://localhost:9000/api/v1/notifications/send \
  -H "Authorization: Bearer sk_live_abc123def456..." \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "TELEGRAM",
    "recipient": "@username",
    "subject": "Alert",
    "content": "Your server is down! Please check immediately."
  }'
```

---

## Checking Message Status

### Get Single Message

```bash
curl -X GET http://localhost:9000/api/v1/analytics/messages/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer sk_live_abc123def456..."
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "messageId": "550e8400-e29b-41d4-a716-446655440000",
    "tenantId": "tenant-uuid-123",
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "subject": "Welcome to Notification Hub!",
    "content": "Thank you for signing up...",
    "status": "SENT",
    "createdAt": "2024-01-15T10:10:00Z",
    "updatedAt": "2024-01-15T10:10:15Z"
  }
}
```

**Possible Status Values:**
- `PENDING` - Queued for delivery
- `SENT` - Successfully delivered
- `FAILED` - Delivery failed after retries

### List All Messages for Tenant

```bash
curl -X GET http://localhost:9000/api/v1/analytics/messages \
  -H "Authorization: Bearer sk_live_abc123def456..."
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "messageId": "550e8400-e29b-41d4-a716-446655440000",
      "status": "SENT",
      "createdAt": "2024-01-15T10:10:00Z"
    },
    {
      "messageId": "660f9511-f30c-52e5-b827-557766551111",
      "status": "PENDING",
      "createdAt": "2024-01-15T10:09:00Z"
    }
  ]
}
```

Results are ordered by creation time (newest first).

---

## Managing API Keys

### List All API Keys

```bash
curl -X GET http://localhost:9000/api/v1/tenants/apikeys \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### Revoke API Key

```bash
curl -X DELETE http://localhost:9000/api/v1/tenants/apikeys/{keyId} \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "id": "apikey-uuid-456",
    "revoked": true,
    "revokedAt": "2024-01-15T11:00:00Z"
  }
}
```

---

## Error Handling

### Common Error Responses

#### 400 Bad Request - Invalid Input

```json
{
  "success": false,
  "error": {
    "code": 400,
    "message": "Validation failed",
    "details": [
      {"field": "recipient", "message": "must be a valid email"},
      {"field": "content", "message": "must not be empty"}
    ]
  },
  "traceId": "...",
  "ts": "2024-01-15T10:15:00Z"
}
```

#### 401 Unauthorized - Invalid API Key

```json
{
  "success": false,
  "error": {
    "code": 1000002,
    "message": "Invalid API Key.",
    "details": null
  },
  "traceId": "...",
  "ts": "2024-01-15T10:15:00Z"
}
```

#### 404 Not Found - Channel Not Found

```json
{
  "success": false,
  "error": {
    "code": 2000001,
    "message": "Channel not found.",
    "details": null
  }
}
```

#### 429 Too Many Requests - Quota Exceeded

```json
{
  "success": false,
  "error": {
    "code": 2000003,
    "message": "Notification quota exceeded.",
    "details": {
      "remaining": 0,
      "limit": 1000,
      "resetAt": "2024-02-01T00:00:00Z"
    }
  }
}
```

### Error Code Ranges

| Range | Service | Examples |
|-------|---------|----------|
| 1000xxx | Tenant Service | AUTH, API_KEY, QUOTA |
| 2000xxx | Notification Service | VALIDATION, CHANNEL |
| 3000xxx | Delivery Service | DELIVERY_FAILURE |
| 4000xxx | Analytics Service | NOT_FOUND |
| 5000xxx | Gateway Service | RATE_LIMIT |

---

## Rate Limiting

Gateway Service implements rate limiting:
- **Replenish Rate:** 50 requests/second
- **Burst Capacity:** 100 requests

**Rate Limit Response (429):**
```json
{
  "success": false,
  "error": {
    "code": 5000001,
    "message": "Too many requests. Please try again later.",
    "details": {
      "retryAfter": 5
    }
  }
}
```

**Header:** `Retry-After: 5` (seconds)

---

## Complete Workflow Example

### Shell Script Example

```bash
#!/bin/bash

API_URL="http://localhost:9000"

echo "1. Registering tenant..."
REGISTER_RESPONSE=$(curl -s -X POST $API_URL/api/v1/tenants/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Corp",
    "email": "test@corp.com",
    "password": "TestPass123!"
  }')

echo "$REGISTER_RESPONSE" | jq .

echo -e "\n2. Logging in..."
LOGIN_RESPONSE=$(curl -s -X POST $API_URL/api/v1/tenants/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@corp.com",
    "password": "TestPass123!"
  }')

JWT_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.data.token')
echo "JWT Token: $JWT_TOKEN"

echo -e "\n3. Creating API key..."
APIKEY_RESPONSE=$(curl -s -X POST $API_URL/api/v1/tenants/apikeys \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Test Key",
    "permissions": ["SEND_NOTIFICATION", "VIEW_ANALYTICS"]
  }')

API_KEY=$(echo "$APIKEY_RESPONSE" | jq -r '.data.key')
echo "API Key: $API_KEY"

echo -e "\n4. Sending notification..."
SEND_RESPONSE=$(curl -s -X POST $API_URL/api/v1/notifications/send \
  -H "Authorization: Bearer $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "EMAIL",
    "recipient": "user@example.com",
    "subject": "Test Notification",
    "content": "This is a test notification from the API."
  }')

MESSAGE_ID=$(echo "$SEND_RESPONSE" | jq -r '.data.id')
echo "Message ID: $MESSAGE_ID"

echo -e "\n5. Waiting 2 seconds for delivery..."
sleep 2

echo -e "\n6. Checking status..."
curl -s -X GET $API_URL/api/v1/analytics/messages/$MESSAGE_ID \
  -H "Authorization: Bearer $API_KEY" | jq .

echo -e "\n✅ Workflow completed successfully!"
```

**Save as:** `examples/curl/complete-workflow.sh`

**Run:**
```bash
chmod +x examples/curl/complete-workflow.sh
./examples/curl/complete-workflow.sh
```

---

## Postman Collection

Import the Postman collection for easy testing:

**File:** `examples/postman/notification-hub.postman_collection.json`

**Collection includes:**
- Pre-request scripts for token management
- Environment variables
- Complete workflow (register → login → create key → send → check)
- All error scenarios

**Import to Postman:**
1. Open Postman
2. File → Import
3. Select `notification-hub.postman_collection.json`
4. Update environment variables (base URL, etc.)

---

## Best Practices

### 1. Always Check Quotas

Before sending bulk notifications, check remaining quota:

```bash
curl -X GET http://localhost:9000/api/v1/tenants/quota \
  -H "Authorization: Bearer <API_KEY>"
```

### 2. Handle Async Processing

Notifications are processed asynchronously:

```javascript
// Send notification
const sendResponse = await fetch('/api/v1/notifications/send', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${apiKey}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify(notification)
});

const { data } = await sendResponse.json();
const messageId = data.id;

// Poll for status (every 2 seconds)
const checkStatus = async () => {
  const statusResponse = await fetch(`/api/v1/analytics/messages/${messageId}`, {
    headers: { 'Authorization': `Bearer ${apiKey}` }
  });

  const { data } = await statusResponse.json();

  if (data.status === 'SENT') {
    console.log('Notification delivered successfully!');
  } else if (data.status === 'FAILED') {
    console.error('Notification delivery failed');
  } else {
    setTimeout(checkStatus, 2000); // Check again in 2 seconds
  }
};

checkStatus();
```

### 3. Use Distributed Tracing

All responses include `traceId` - use it for debugging:

```bash
# Search in Jaeger
http://localhost:16686/search?service=notification-service&tags={"traceId":"a1b2c3d4e5f6..."}
```

### 4. Handle Errors Gracefully

```javascript
try {
  const response = await sendNotification(data);
  if (!response.success) {
    const { code, message } = response.error;

    if (code === 2000003) {
      // Quota exceeded
      alert('Monthly quota exceeded. Upgrade plan or wait for reset.');
    } else if (code === 2000001) {
      // Channel not found
      alert(`Invalid channel: ${data.channel}`);
    } else {
      alert(`Error: ${message}`);
    }
  }
} catch (error) {
  console.error('Network error:', error);
}
```

---

## Advanced Examples

### Bulk Notification Send

```bash
#!/bin/bash

API_KEY="sk_live_abc123..."
API_URL="http://localhost:9000/api/v1/notifications/send"

# Read recipients from file
while IFS= read -r email; do
  curl -s -X POST $API_URL \
    -H "Authorization: Bearer $API_KEY" \
    -H "Content-Type: application/json" \
    -d "{
      \"channel\": \"EMAIL\",
      \"recipient\": \"$email\",
      \"subject\": \"Bulk Notification\",
      \"content\": \"This is a bulk notification.\"
    }" | jq -r '.data.id'

  # Respect rate limits
  sleep 0.05
done < recipients.txt
```

### Webhook Integration

```javascript
// Express.js webhook endpoint
app.post('/webhooks/notification-status', async (req, res) => {
  const { messageId, status, updatedAt } = req.body;

  console.log(`Message ${messageId} status: ${status}`);

  if (status === 'SENT') {
    await database.updateCampaignMetrics(messageId, 'delivered');
  } else if (status === 'FAILED') {
    await database.updateCampaignMetrics(messageId, 'failed');
    await retryOrAlert(messageId);
  }

  res.status(200).send('OK');
});
```

---

## Testing in Different Environments

### Development

```bash
export API_URL="http://localhost:9000"
```

### Staging

```bash
export API_URL="https://staging-api.notification-hub.com"
```

### Production

```bash
export API_URL="https://api.notification-hub.com"
# Use production API keys (sk_live_...)
```

---

## Monitoring API Health

### Health Check

```bash
curl http://localhost:9000/actuator/health
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "kafka": {"status": "UP"},
    "redis": {"status": "UP"},
    "db": {"status": "UP"}
  }
}
```

### Metrics

```bash
curl http://localhost:9000/actuator/metrics
curl http://localhost:9000/actuator/prometheus
```

---

## Need Help?

- **Troubleshooting:** [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
- **Architecture:** [ARCHITECTURE.md](ARCHITECTURE.md)
- **Development:** [DEVELOPMENT.md](DEVELOPMENT.md)
- **Issues:** [GitHub Issues](https://github.com/your-org/notification-hub/issues)

---

**Happy Sending! 🚀**
