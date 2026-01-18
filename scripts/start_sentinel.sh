#!/bin/bash

# ANSI Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}========================================================${NC}"
echo -e "${BLUE}  SENTINEL INTELLIGENCE PLATFORM (v1.0.0)${NC}"
echo -e "${BLUE}  Turnkey Edition${NC}"
echo -e "${BLUE}========================================================${NC}"
echo ""

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# 1. CHECK JAVA
if ! command -v java &> /dev/null; then
    echo -e "${RED}[ERROR] Java not found. Please install Java JDK 21.${NC}"
    exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}')
echo -e "[INFO] Detected Java Version: $JAVA_VER"

# 2. MODE SELECTION
echo ""
echo "Select Deployment Mode:"
echo " [1] COMMERCIAL (Standard Mode)"
echo "     - Ideal for Banking, Legal, Medical"
echo "     - Permissive Security"
echo ""
echo " [2] GOVERNMENT (Secure Mode)"
echo "     - DoD/IC Compliant (IL4/IL5)"
echo "     - Requires CAC/PIV + HTTPS"
echo "     - Zero Trust Enforced"
echo ""
read -p "Enter Selection [1 or 2] (Default: 1): " selection

# 3. LAUNCH
cd "$ROOT_DIR"
if [ "$selection" == "2" ]; then
    echo ""
    echo -e "${GREEN}[STATUS] Initializing SECURE GOVERNMENT MODE...${NC}"
    echo -e "[INFO] Enforcing HTTPS, Mutual TLS, and RBAC."
    chmod +x "$ROOT_DIR/gradlew"
    "$ROOT_DIR/gradlew" bootRun --args='--spring.profiles.active=govcloud'
else
    echo ""
    echo -e "${GREEN}[STATUS] Initializing COMMERCIAL MODE...${NC}"
    echo -e "[INFO] Standard security enabled."
    chmod +x "$ROOT_DIR/gradlew"
    "$ROOT_DIR/gradlew" bootRun --args='--spring.profiles.active=dev'
fi
