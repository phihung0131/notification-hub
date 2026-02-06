#!/bin/bash

# ================================================
# Stop All Services - Notification Hub
# ================================================
# Stops all running containers
# Usage: ./scripts/stop-all.sh [--clean]

set -e

echo "Stopping Notification Hub..."
echo "============================================"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Check for --clean flag
CLEAN_VOLUMES=false
DOWN_FLAGS=""

if [ "$1" == "--clean" ]; then
  CLEAN_VOLUMES=true
  DOWN_FLAGS="-v"
  echo -e "${RED}WARNING: Will remove all volumes (data will be lost)${NC}"
  read -p "Continue? (y/N): " -n 1 -r
  echo
  if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Cancelled."
    exit 0
  fi
fi

# Stop all services with appropriate flags
echo "Stopping services..."

docker compose -f docker/docker-compose.gateway-dev.yml down $DOWN_FLAGS
docker compose -f docker/docker-compose.analytics-dev.yml down $DOWN_FLAGS
docker compose -f docker/docker-compose.delivery-dev.yml down $DOWN_FLAGS
docker compose -f docker/docker-compose.notification-dev.yml down $DOWN_FLAGS
docker compose -f docker/docker-compose.tenant-dev.yml down $DOWN_FLAGS
docker compose -f docker/docker-compose.kafka-dev.yml down $DOWN_FLAGS
docker compose -f docker/docker-compose.monitoring-dev.yml down $DOWN_FLAGS

if [ "$CLEAN_VOLUMES" = true ]; then
  echo ""
  echo -e "${YELLOW}Removing orphaned volumes...${NC}"
  docker volume prune -f
fi

echo ""
echo -e "${GREEN}✓ All services stopped${NC}"

if [ "$CLEAN_VOLUMES" = true ]; then
  echo -e "${GREEN}✓ All volumes removed${NC}"
fi