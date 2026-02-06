#!/bin/bash

# ================================================
# Build All Services - Notification Hub
# ================================================
# Builds all microservices in correct order
# Usage: ./scripts/build-all.sh

set -e  # Exit on error

echo "Building Notification Hub Services..."
echo "============================================"

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Step 1: Format code
echo -e "${BLUE}Step 1/3: Formatting all code...${NC}"
./scripts/format-code.sh

# Step 2: Build foundation module
echo -e "${BLUE}Step 2/3: Building commons-shared (includes proto & avro)...${NC}"
mvn clean install -pl commons-shared

# Step 3: Build all services
echo -e "${BLUE}Step 3/3: Building all services...${NC}"
mvn clean install -Ptenant,notification,delivery,analytics,gateway -DskipTests

## Step 3: Run tests
#echo -e "${BLUE}🧪 Step 3/3: Running tests...${NC}"
#mvn test -Ptenant,notification,gateway
#
#echo -e "${GREEN}Build completed successfully!${NC}"
#echo ""
#echo "Build Summary:"
#mvn -q -pl commons-shared,proto-shared,gateway-service,tenant-service,notification-service,delivery-service,analytics-service help:evaluate -Dexpression=project.artifactId

echo ""
echo "Next steps:"
echo "  1. Start infrastructure: ./scripts/start-infra.sh"
echo "  2. Start services: ./scripts/start-services.sh"
echo "  3. Run tests: ./scripts/run-tests.sh"
