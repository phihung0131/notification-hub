#!/bin/bash

# ================================================
# Health Check - Notification Hub
# ================================================
# Checks health of all services
# Usage: ./scripts/check-health.sh

echo "Notification Hub Health Check"
echo "============================================"
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

check_service() {
  local name=$1
  local url=$2

  if curl -sf "$url" > /dev/null 2>&1; then
    local status=$(curl -s "$url" | jq -r '.status // "UP"')
    echo -e "${GREEN}✅ $name${NC} - Status: $status"
    return 0
  else
    echo -e "${RED}❌ $name${NC} - NOT RESPONDING"
    return 1
  fi
}

# Check infrastructure
echo "Infrastructure:"
check_service "Kafka UI      " "http://localhost:8080"
check_service "Schema Registry" "http://localhost:8081/subjects"

echo ""
echo "Services:"
check_service "Gateway       " "http://localhost:9000/actuator/health"
check_service "Tenant        " "http://localhost:9001/api/v1/tenants/actuator/health"
check_service "Notification  " "http://localhost:9002/api/v1/notifications/actuator/health"
check_service "Delivery      " "http://localhost:9003/api/v1/delivery/actuator/health"
check_service "Analytics     " "http://localhost:9004/api/v1/analytics/actuator/health"

echo ""
echo "Docker Containers:"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "kafka|postgres|redis|tenant|notification|delivery|analytics|gateway" || echo "No containers running"

echo ""
echo "============================================"
echo "Health check completed!"
