#!/usr/bin/env bash
# =============================================================================
# demo.sh — FirstClub Fintech Platform  2-Minute Walkthrough
# =============================================================================
# Prerequisites:
#   - Docker + Docker Compose running
#   - Java 17+ and Maven on PATH
#   - jq installed  (brew install jq)
#   - curl installed
#
# Usage:
#   chmod +x scripts/demo.sh
#   scripts/demo.sh
# =============================================================================
set -euo pipefail

BASE="http://localhost:8080"
TODAY=$(date +%Y-%m-%d)

# ── ANSI colours ─────────────────────────────────────────────────────────────
BOLD="\033[1m"
GREEN="\033[1;32m"
CYAN="\033[1;36m"
YELLOW="\033[1;33m"
RESET="\033[0m"

step() { echo -e "\n${CYAN}${BOLD}▶ STEP $1: $2${RESET}"; }
ok()   { echo -e "${GREEN}   ✓ $*${RESET}"; }
info() { echo -e "${YELLOW}   → $*${RESET}"; }

# =============================================================================
# STEP 0 — Start infrastructure
# =============================================================================
step 0 "Start Docker Compose (Postgres + PgAdmin)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}/.."
docker compose up -d
info "Waiting for Postgres..."
until docker compose exec -T postgres pg_isready -U membership_user -d membershipdb > /dev/null 2>&1; do sleep 1; done
ok "Postgres ready"

# =============================================================================
# STEP 1 — Build & start the application
# =============================================================================
step 1 "Build and start Spring Boot (profile=dev) in background"
mvn -q package -DskipTests
nohup java -jar target/*.jar --spring.profiles.active=dev > /tmp/demo-app.log 2>&1 &
APP_PID=$!
info "App PID=$APP_PID — waiting for readiness..."
for i in $(seq 1 60); do
  if curl -sf "${BASE}/actuator/health" | jq -e '.status == "UP"' > /dev/null 2>&1; then
    ok "Application is UP"
    break
  fi
  sleep 2
done

# =============================================================================
# STEP 2 — Authenticate as admin
# =============================================================================
step 2 "Authenticate as admin"
ADMIN_TOKEN=$(curl -sf -X POST "${BASE}/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.accessToken')
info "Admin token: ${ADMIN_TOKEN:0:30}..."

# =============================================================================
# STEP 3 — Create a user
# =============================================================================
step 3 "Create a new user"
USER_RESP=$(curl -sf -X POST "${BASE}/api/v1/users" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d '{
    "name":"Demo User",
    "email":"demo@firstclub.com",
    "phoneNumber":"9876543210",
    "address":"123 Demo Street, Mumbai"
  }')
USER_ID=$(echo "$USER_RESP" | jq -r '.id')
ok "Created user ID=$USER_ID"

# =============================================================================
# STEP 4 — Create subscription via v2 API
# =============================================================================
step 4 "Create subscription (plan SILVER) via /api/v2/subscriptions"
SUB_RESP=$(curl -sf -X POST "${BASE}/api/v2/subscriptions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d "{
    \"userId\": ${USER_ID},
    \"planId\": 1
  }")
SUB_ID=$(echo "$SUB_RESP" | jq -r '.subscriptionId // .id')
INV_ID=$(echo "$SUB_RESP" | jq -r '.invoiceId // .invoice.id // ""')
ok "Subscription ID=$SUB_ID"
info "Invoice ID=${INV_ID:-<not in response — fetching>}"

# Fetch invoice if not in the subscription response
if [ -z "$INV_ID" ] || [ "$INV_ID" = "null" ]; then
  INV_ID=$(curl -sf "${BASE}/api/v1/billing/subscriptions/${SUB_ID}/invoices" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.[0].id')
  info "Invoice ID=$INV_ID (fetched)"
fi

# =============================================================================
# STEP 5 — Create a PaymentIntent and simulate SUCCEEDED payment
# =============================================================================
step 5 "Create PaymentIntent and simulate gateway SUCCEEDED"
PI_RESP=$(curl -sf -X POST "${BASE}/api/v1/payments/intents" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d "{\"invoiceId\": ${INV_ID}}")
PI_ID=$(echo "$PI_RESP" | jq -r '.id')
ok "PaymentIntent ID=$PI_ID"

GW_RESP=$(curl -sf -X POST "${BASE}/gateway/pay" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: demo-pay-$(date +%s)" \
  -H "X-Device-Id: demo-device-001" \
  -d "{\"paymentIntentId\": ${PI_ID}, \"outcome\": \"SUCCEEDED\"}")
ok "Gateway response: $(echo "$GW_RESP" | jq -c '.')"

# =============================================================================
# STEP 6 — Poll until subscription ACTIVE + invoice PAID
# =============================================================================
step 6 "Poll until subscription=ACTIVE and invoice=PAID"
for i in $(seq 1 15); do
  STATUS=$(curl -sf "${BASE}/api/v1/subscriptions/${SUB_ID}" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.status')
  INV_STATUS=$(curl -sf "${BASE}/api/v1/billing/invoices/${INV_ID}" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.status')
  info "Attempt $i: subscription=$STATUS  invoice=$INV_STATUS"
  if [ "$STATUS" = "ACTIVE" ] && [ "$INV_STATUS" = "PAID" ]; then
    ok "Subscription ACTIVE + Invoice PAID"
    break
  fi
  sleep 2
done

# =============================================================================
# STEP 7 — Ledger balances
# =============================================================================
step 7 "Query ledger balances"
BALANCES=$(curl -sf "${BASE}/api/v1/ledger/balances" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")
echo "$BALANCES" | jq '.'
ok "Ledger balances retrieved"

# =============================================================================
# STEP 8 — Trigger settlement
# =============================================================================
step 8 "Trigger nightly settlement for today ($TODAY)"
SETTLE=$(curl -sf -X POST "${BASE}/api/v1/admin/recon/settle?date=${TODAY}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")
echo "$SETTLE" | jq '.'
ok "Settlement: amount=$(echo "$SETTLE" | jq -r '.totalAmount') INR"

# =============================================================================
# STEP 9 — Generate recon report and download CSV
# =============================================================================
step 9 "Generate reconciliation report for today and download CSV"
RECON=$(curl -sf "${BASE}/api/v1/admin/recon/daily?date=${TODAY}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")
echo "$RECON" | jq '{reportDate, expectedTotal, actualTotal, variance, mismatchCount}'

curl -sf "${BASE}/api/v1/admin/recon/daily.csv?date=${TODAY}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -o "/tmp/recon-${TODAY}.csv"
ok "CSV saved to /tmp/recon-${TODAY}.csv"
cat "/tmp/recon-${TODAY}.csv"

# =============================================================================
# STEP 10 — Replay domain event log (VALIDATE_ONLY)
# =============================================================================
step 10 "Replay domain events (VALIDATE_ONLY) for today"
FROM="${TODAY}T00:00:00"
TO="${TODAY}T23:59:59"
REPLAY=$(curl -sf -X POST \
  "${BASE}/api/v1/admin/replay?from=${FROM}&to=${TO}&mode=VALIDATE_ONLY" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")
echo "$REPLAY" | jq '.'
ok "Replay complete: eventsScanned=$(echo "$REPLAY" | jq '.eventsScanned')  valid=$(echo "$REPLAY" | jq '.valid')"

# =============================================================================
# Done
# =============================================================================
echo -e "\n${GREEN}${BOLD}╔══════════════════════════════════════════════════╗${RESET}"
echo -e "${GREEN}${BOLD}║  Demo complete!  Application still running.      ║${RESET}"
echo -e "${GREEN}${BOLD}║  Swagger UI : ${BASE}/swagger-ui.html           ║${RESET}"
echo -e "${GREEN}${BOLD}║  Stop app   : kill ${APP_PID}                        ║${RESET}"
echo -e "${GREEN}${BOLD}╚══════════════════════════════════════════════════╝${RESET}"
