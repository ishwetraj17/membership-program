#!/usr/bin/env bash
# =============================================================================
# FirstClub Membership Platform — Interview Reset & Launch Script
# Usage: ./start.sh
# Drops and recreates the database, applies migrations, starts the app.
# Safe to run repeatedly. Every run produces a clean demo environment.
# =============================================================================
set -euo pipefail

# ── Colour codes ──────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

ok()   { echo -e "  ${GREEN}✅ $*${RESET}"; }
info() { echo -e "  ${CYAN}➜  $*${RESET}"; }
warn() { echo -e "  ${YELLOW}⚠  $*${RESET}"; }
fail() { echo -e "  ${RED}❌ $*${RESET}"; exit 1; }
header() {
  echo ""
  echo -e "${BOLD}${CYAN}══════════════════════════════════════════════════════════${RESET}"
  echo -e "${BOLD}${CYAN}  $*${RESET}"
  echo -e "${BOLD}${CYAN}══════════════════════════════════════════════════════════${RESET}"
}

# ── Config ────────────────────────────────────────────────────────────────────
PG_BIN="/opt/homebrew/opt/postgresql@16/bin"
export PATH="$PG_BIN:$PATH"

PGHOST="localhost"
PGPORT="5432"
PGUSER="postgres"
PGPASSWORD="postgres"
export PGPASSWORD

DB_NAME="membershipdb"
APP_PORT="8080"
HEALTH_URL="http://localhost:${APP_PORT}/api/v1/membership/health"
SWAGGER_URL="http://localhost:${APP_PORT}/swagger-ui/index.html"
LOG_FILE="/tmp/membership_startup.log"
STARTUP_TIMEOUT=60   # seconds to wait for "Started MembershipApplication"

# =============================================================================
header "STEP 1 — Pre-flight checks"
# =============================================================================

# Java
info "Java version..."
JAVA_VER=$(java -version 2>&1 | head -1)
ok "$JAVA_VER"

# Maven
info "Maven version..."
MVN_VER=$(mvn -version 2>&1 | head -1)
ok "$MVN_VER"

# pg_ctl / psql present
if ! command -v psql &>/dev/null; then
  fail "psql not found. Expected at $PG_BIN"
fi
ok "psql found at $(command -v psql)"

# =============================================================================
header "STEP 2 — Kill existing application process"
# =============================================================================

# Kill by port 8080
if lsof -ti tcp:"$APP_PORT" &>/dev/null; then
  info "Port $APP_PORT is occupied — terminating..."
  lsof -ti tcp:"$APP_PORT" | xargs kill -9 2>/dev/null || true
  sleep 1
  ok "Port $APP_PORT cleared"
else
  ok "Port $APP_PORT is free"
fi

# Also kill any lingering mvn spring-boot:run processes
if pgrep -f "spring-boot:run" &>/dev/null; then
  info "Killing leftover spring-boot:run process..."
  pkill -f "spring-boot:run" 2>/dev/null || true
  sleep 1
fi
ok "No stale application process"

# =============================================================================
header "STEP 3 — Ensure PostgreSQL is running"
# =============================================================================

if ! pg_isready -h "$PGHOST" -p "$PGPORT" -q 2>/dev/null; then
  info "PostgreSQL not running — starting via brew services..."
  brew services start postgresql@16
  info "Waiting for PostgreSQL to accept connections..."
  for i in {1..15}; do
    pg_isready -h "$PGHOST" -p "$PGPORT" -q 2>/dev/null && break
    sleep 1
  done
fi

pg_isready -h "$PGHOST" -p "$PGPORT" -q || fail "PostgreSQL did not come up on $PGHOST:$PGPORT"
ok "PostgreSQL is ready on $PGHOST:$PGPORT"

# =============================================================================
header "STEP 4 — Terminate active DB sessions and reset database"
# =============================================================================

# Terminate all active connections to membershipdb (required before DROP DATABASE)
info "Terminating active connections to '$DB_NAME'..."
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -q -c \
  "SELECT pg_terminate_backend(pid)
   FROM pg_stat_activity
   WHERE datname = '${DB_NAME}'
     AND pid <> pg_backend_pid();" 2>/dev/null || true
ok "Active sessions terminated"

# Drop database if it exists
info "Dropping database '$DB_NAME'..."
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -q -c \
  "DROP DATABASE IF EXISTS ${DB_NAME};" 2>/dev/null || fail "Failed to drop database"
ok "Database '$DB_NAME' dropped"

# Recreate database
info "Recreating database '$DB_NAME'..."
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -q -c \
  "CREATE DATABASE ${DB_NAME} OWNER ${PGUSER};" || fail "Failed to create database"
ok "Database '$DB_NAME' created (empty)"

# Verify connectivity
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -q -c "SELECT 1;" &>/dev/null \
  || fail "Cannot connect to fresh '$DB_NAME'"
ok "Connection to '$DB_NAME' verified"

# =============================================================================
header "STEP 5 — Start application (profile: dev)"
# =============================================================================

info "Launching Spring Boot (logs → $LOG_FILE)..."
> "$LOG_FILE"   # truncate log file

mvn spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  --no-transfer-progress \
  > "$LOG_FILE" 2>&1 &
APP_PID=$!

info "Waiting for 'Started MembershipApplication' (timeout: ${STARTUP_TIMEOUT}s)..."

ELAPSED=0
STARTED=false
while [[ $ELAPSED -lt $STARTUP_TIMEOUT ]]; do
  if grep -q "Started MembershipApplication" "$LOG_FILE" 2>/dev/null; then
    STARTED=true
    break
  fi
  if grep -qE "APPLICATION FAILED TO START|BUILD FAILURE|BeanCreationException" "$LOG_FILE" 2>/dev/null; then
    echo ""
    warn "Startup failure detected. Last 30 lines of log:"
    tail -30 "$LOG_FILE"
    fail "Application failed to start. See $LOG_FILE for full log."
  fi
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

if [[ "$STARTED" != "true" ]]; then
  warn "Timeout reached. Last 20 lines of log:"
  tail -20 "$LOG_FILE"
  fail "Application did not start within ${STARTUP_TIMEOUT}s."
fi

ok "Application started (PID $APP_PID)"

# =============================================================================
header "STEP 6 — Verify health and Swagger"
# =============================================================================

# Health check
info "Checking health endpoint..."
HEALTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HEALTH_URL" 2>/dev/null || echo "000")
if [[ "$HEALTH_STATUS" != "200" ]]; then
  fail "Health endpoint returned HTTP $HEALTH_STATUS (expected 200). See $LOG_FILE"
fi
HEALTH_BODY=$(curl -s "$HEALTH_URL" 2>/dev/null)
APP_STATUS=$(echo "$HEALTH_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
[[ "$APP_STATUS" == "UP" ]] || fail "Health status is '$APP_STATUS', expected UP"
ok "Health endpoint: UP (HTTP 200)"

# Swagger check
info "Checking Swagger UI..."
SWAGGER_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$SWAGGER_URL" 2>/dev/null || echo "000")
if [[ "$SWAGGER_STATUS" != "200" ]]; then
  fail "Swagger UI returned HTTP $SWAGGER_STATUS (expected 200)"
fi
ok "Swagger UI: HTTP 200"

# Seed data verification
PLAN_COUNT=$(curl -s "http://localhost:${APP_PORT}/api/v1/membership/plans" 2>/dev/null \
  | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
TIER_COUNT=$(curl -s "http://localhost:${APP_PORT}/api/v1/membership/tiers" 2>/dev/null \
  | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "0")
# GET /users is paginated — read totalElements from the Page envelope
USER_COUNT=$(curl -s "http://localhost:${APP_PORT}/api/v1/users" 2>/dev/null \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('totalElements', 0))" 2>/dev/null || echo "0")

[[ "$PLAN_COUNT" -eq 9 ]] || fail "Expected 9 plans, got $PLAN_COUNT"
[[ "$TIER_COUNT" -eq 3 ]] || fail "Expected 3 tiers, got $TIER_COUNT"
ok "Seed data: $TIER_COUNT tiers, $PLAN_COUNT plans, $USER_COUNT demo users"

# =============================================================================
header "READY"
# =============================================================================

echo ""
echo -e "${BOLD}${GREEN}══════════════════════════════════════════════════════════${RESET}"
echo -e "${BOLD}${GREEN}  FirstClub Membership Platform  —  DEMO READY${RESET}"
echo -e "${BOLD}${GREEN}══════════════════════════════════════════════════════════${RESET}"
echo ""
echo -e "  ${BOLD}Swagger UI${RESET}"
echo -e "  ${CYAN}http://localhost:${APP_PORT}/swagger-ui/index.html${RESET}"
echo ""
echo -e "  ${BOLD}Health${RESET}"
echo -e "  ${CYAN}http://localhost:${APP_PORT}/api/v1/membership/health${RESET}"
echo ""
echo -e "  ${BOLD}Analytics${RESET}"
echo -e "  ${CYAN}http://localhost:${APP_PORT}/api/v1/membership/analytics${RESET}"
echo ""
echo -e "  ${BOLD}Database${RESET}"
echo -e "  ${CYAN}$DB_NAME  (freshly recreated — zero stale data)${RESET}"
echo ""
echo -e "  ${GREEN}✅ PostgreSQL running${RESET}"
echo -e "  ${GREEN}✅ Flyway migrations applied (V1 V2 V3 V4)${RESET}"
echo -e "  ${GREEN}✅ Application started${RESET}"
echo -e "  ${GREEN}✅ Health endpoint verified${RESET}"
echo -e "  ${GREEN}✅ Swagger verified${RESET}"
echo -e "  ${GREEN}✅ Seed data: $TIER_COUNT tiers · $PLAN_COUNT plans · $USER_COUNT users${RESET}"
echo -e "  ${GREEN}✅ Demo environment reset successfully${RESET}"
echo ""
echo -e "  ${YELLOW}Logs → $LOG_FILE${RESET}"
echo -e "  ${YELLOW}Stop  → kill $APP_PID  (or: pkill -f spring-boot:run)${RESET}"
echo ""

# Keep script running so logs stream to terminal
wait $APP_PID
