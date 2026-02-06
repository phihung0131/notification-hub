#!/bin/bash

# ================================================
# Format Code - Notification Hub
# ================================================
# Formats all Java code using Google Java Format (AOSP style)
# Usage:
#   ./scripts/format-code.sh          # Format all services
#   ./scripts/format-code.sh check    # Check formatting only (no changes)

set -e  # Exit on error

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Parse arguments
MODE="apply"
if [ "$1" = "check" ]; then
    MODE="check"
fi

echo "Code Formatting - Notification Hub"
echo "============================================"

if [ "$MODE" = "check" ]; then
    echo -e "${YELLOW}Mode: CHECK (no changes will be made)${NC}"
else
    echo -e "${BLUE}Mode: APPLY (will format files)${NC}"
fi
echo ""

# Format commons-shared and gateway-service (always included)
echo -e "${BLUE}Formatting commons-shared & gateway-service...${NC}"
mvn spotless:$MODE -DskipTests

echo ""

# Format all services with profiles
echo -e "${BLUE}Formatting all services...${NC}"
mvn spotless:$MODE -Ptenant,notification,delivery,analytics -DskipTests

echo ""

if [ "$MODE" = "apply" ]; then
    echo -e "${GREEN}Code formatting completed successfully!${NC}"
    echo ""
    echo "All Java files have been formatted with Google Java Format (AOSP style):"
    echo "  • 4-space indentation"
    echo "  • Import order: java → javax → jakarta → org → com"
    echo "  • Unused imports removed"
    echo ""
    echo "Changes have been applied to your files. Review with: git diff"
else
    echo -e "${GREEN}Code formatting check completed!${NC}"
    echo ""
    echo "If violations were found, run:"
    echo "  ./scripts/format-code.sh"
    echo ""
    echo "Or manually: mvn spotless:apply"
fi
