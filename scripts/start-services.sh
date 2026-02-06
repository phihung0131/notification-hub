#!/bin/bash

# ================================================
# Start All - Notification Hub
# ================================================
# Starts infrastructure and all microservices
# Usage: ./scripts/start-all.sh

set -e

echo "Starting Notification Hub - Full Stack"
echo "============================================"
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BLUE}Creating Docker network...${NC}"
docker network create notification-net 2>/dev/null || echo "Network already exists"

echo -e "${BLUE}Starting infrastructure containers...${NC}"
docker compose -f docker/docker-compose.kafka-dev.yml up -d
docker compose -f docker/docker-compose.monitoring-dev.yml up -d

echo ""
echo -e "${YELLOW}Waiting for infrastructure to be ready...${NC}"
sleep 5

# Start services in background
echo -e "${BLUE}Format code...${NC}"
echo ""
./scripts/format-code.sh

echo -e "${YELLOW}Building all services (this may take a few minutes)...${NC}"
echo ""

# Build all services first
echo -e "${BLUE}Building services...${NC}"
echo ""
docker compose \
  -f docker/docker-compose.tenant-dev.yml \
  -f docker/docker-compose.notification-dev.yml \
  -f docker/docker-compose.delivery-dev.yml \
  -f docker/docker-compose.analytics-dev.yml \
  -f docker/docker-compose.gateway-dev.yml \
  build

echo -e "${BLUE}Starting all services...${NC}"
echo -e "${YELLOW}Note: Services will run in foreground. Press Ctrl+C to stop all.${NC}"
echo ""
docker compose \
  -f docker/docker-compose.tenant-dev.yml \
  -f docker/docker-compose.notification-dev.yml \
  -f docker/docker-compose.delivery-dev.yml \
  -f docker/docker-compose.analytics-dev.yml \
  -f docker/docker-compose.gateway-dev.yml \
  up --watch

echo ""
echo -e "${GREEN}All services started successfully!${NC}"
echo ""

# ================================================
# Summary
# ================================================
echo "============================================"
echo -e "${GREEN}Notification Hub is Ready!${NC}"
echo "============================================"
echo ""
echo "Infrastructure:"
echo "  • Kafka UI:          http://localhost:8080"
echo "  • Schema Registry:   http://localhost:8081"
echo "  • Jaeger Tracing:    http://localhost:16686"
echo ""
echo "Service Endpoints:"
echo "  • Gateway:           http://localhost:9000"
echo "  • Tenant:            http://localhost:9001  (gRPC: 50001)"
echo "  • Notification:      http://localhost:9002  (gRPC: 50002)"
echo "  • Delivery:          http://localhost:9003"
echo "  • Analytics:         http://localhost:9004"
echo ""
echo "Useful Commands:"
echo "  • View all logs:     docker compose logs -f"
echo "  • View service log:  docker logs -f <service-name>"
echo "  • Stop all:          ./scripts/stop-all.sh"
echo ""