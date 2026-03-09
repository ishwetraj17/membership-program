#!/usr/bin/env bash
# dev-down.sh — stop the Docker Compose stack
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

echo "Stopping Docker Compose services..."
docker compose down

echo "Done.  Postgres data volume is preserved."
echo "To also remove the volume: docker compose down -v"
