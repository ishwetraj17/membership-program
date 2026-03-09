#!/usr/bin/env bash
# dev-up.sh — start the Docker Compose stack (Postgres + PgAdmin)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."

echo "Starting Docker Compose services..."
docker compose up -d

echo ""
echo "Waiting for Postgres to be healthy..."
until docker compose exec -T postgres pg_isready -U membership_user -d membershipdb > /dev/null 2>&1; do
  sleep 1
done

echo ""
echo "✓ Postgres is ready at  localhost:5432  (db=membershipdb, user=membership_user)"
echo "✓ PgAdmin is ready at   http://localhost:5050  (email=admin@firstclub.com, pass=admin)"
echo ""
echo "Run the application with:  scripts/run-local.sh"
