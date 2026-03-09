#!/usr/bin/env bash
# run-local.sh — build and run the Spring Boot app with the 'local' profile
#
# Prerequisites:
#   - Java 17+ on PATH
#   - Docker Compose stack running:  scripts/dev-up.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

echo "Starting FirstClub Membership Program (profile=local)..."
echo ""
mvn spring-boot:run -Dspring-boot.run.profiles=local
