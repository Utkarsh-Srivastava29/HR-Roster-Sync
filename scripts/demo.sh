#!/usr/bin/env bash
# Walks through every flow against a running instance (docker compose up --build).
# Usage: bash scripts/demo.sh [base-url]        (on Windows run it from Git Bash)
set -euo pipefail
BASE=${1:-http://localhost:8080}
cd "$(dirname "$0")/.."
say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
token() {
  curl -s -X POST "$BASE/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"${DEMO_PASSWORD:-demo123}\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'
}
HR=$(token acme-hr); OPS=$(token acme-ops); EMP=$(token acme-emp); GLOBEX=$(token globex-ops)
hr()  { curl -s -X POST "$BASE/api/v1/hr/events" -H "Authorization: Bearer $HR" -H 'Content-Type: application/json' --data-binary "@$1"; echo; }
ops() { curl -s "$@" -H "Authorization: Bearer $OPS"; echo; }

say "1. HR pushes 50 new joiners"
hr samples/01-initial-batch.json | head -c 300; echo

say "2. Case-study example: office transfer v7 arrives BEFORE shift change v6"
hr samples/02-transfer-v7.json | head -c 400; echo
hr samples/03-shift-change-v6-late.json
ops "$BASE/api/v1/employees/E0000001"

say "3. The same feed replayed (network retry): nothing changes"
hr samples/01-initial-batch.json | head -c 200; echo

say "4. A batch with bad data: one bad record never fails the batch; errors carry field + code, never values"
hr samples/04-mixed-batch.json

say "5. Transport admin owns the pickup point; HR can never overwrite it"
ops -X PATCH "$BASE/api/v1/employees/E0000005/transport" -H 'Content-Type: application/json' \
  -d '{"pickupPoint":"Gate 4, Tech Park","specialAssistance":["WHEELCHAIR"],"expectedVersion":0}'
ops -X PATCH "$BASE/api/v1/employees/E0000005/transport" -H 'Content-Type: application/json' \
  -d '{"pickupPoint":"Somewhere","expectedVersion":0}'

say "6. Deactivation side effects: E0000002 has two future bookings on route R1"
ops -X POST "$BASE/api/v1/bookings" -H 'Content-Type: application/json' -d '{"employeeId":"E0000002","routeId":"R1","pickupAt":"2030-01-02T08:00:00Z"}' > /dev/null
ops -X POST "$BASE/api/v1/bookings" -H 'Content-Type: application/json' -d '{"employeeId":"E0000002","routeId":"R1","pickupAt":"2030-01-03T08:00:00Z"}' > /dev/null
ops "$BASE/api/v1/routes/R1"
hr samples/05-deactivate-E0000002.json
sleep 1   # the outbox relay publishes the change event; the booking consumer reacts
ops "$BASE/api/v1/employees/E0000002/bookings"
ops "$BASE/api/v1/routes/R1"

say "7. Failure scenario: the nightly file is cut off -> safety brake, nobody deactivated"
RUN=$(curl -s -X POST "$BASE/api/v1/reconciliations" -H "Authorization: Bearer $HR" -H 'Content-Type: text/csv' \
  --data-binary @samples/export-cut-off.csv | sed -E 's/.*"runId":"([^"]+)".*/\1/')
sleep 2
ops "$BASE/api/v1/reconciliations/$RUN" | head -c 1500; echo
say "   operator rejects it"
ops -X POST "$BASE/api/v1/reconciliations/$RUN/reject" | head -c 200; echo

say "8. A good nightly file: 1 changed, 1 new, 2 missing (4%) -> applied"
RUN=$(curl -s -X POST "$BASE/api/v1/reconciliations" -H "Authorization: Bearer $HR" -H 'Content-Type: text/csv' \
  --data-binary @samples/export-nightly.csv | sed -E 's/.*"runId":"([^"]+)".*/\1/')
sleep 2
ops "$BASE/api/v1/reconciliations/$RUN" | head -c 1500; echo

say "9. Change feed (ids, field names, versions - no personal data) and outbox health"
ops "$BASE/api/v1/changes?after=0&limit=3"
ops "$BASE/api/v1/outbox/status"

say "10. Isolation: an employee sees only themselves; another client sees nothing"
curl -s "$BASE/api/v1/me" -H "Authorization: Bearer $EMP"; echo
curl -s -o /dev/null -w "employee reading someone else -> HTTP %{http_code}\n" "$BASE/api/v1/employees/E0000003" -H "Authorization: Bearer $EMP"
curl -s -o /dev/null -w "globex operator reading acme's employee -> HTTP %{http_code}\n" "$BASE/api/v1/employees/E0000001" -H "Authorization: Bearer $GLOBEX"
