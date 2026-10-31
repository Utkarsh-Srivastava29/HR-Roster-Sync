# Demo video script (about 5 minutes)

Terminal on the left, browser on the right. Everything below is `scripts/demo.sh` plus a few extra commands.

1. **Start (20 s).** `docker compose up --build`. Show Postgres becoming healthy, the app log line
   `using the Postgres store`, and that logs are JSON with `requestId` / `tenant`.
2. **Ingest (60 s).** Demo steps 1–4: 50 joiners; v7 transfer then the late v6 (`STALE`) and the employee ending in
   HYD1 on the night shift; the same batch replayed (`DUPLICATE`, nothing changes); a mixed batch where only the bad
   records are `REJECTED`, with `{field, code}` and no values.
3. **Ownership (30 s).** Step 5: operator sets the pickup point; a stale edit gets 409; the mixed batch's attempt
   to set a pickup point shows up as `ignoredFields`.
4. **Side effects (40 s).** Step 6: E0000002 has two bookings on R1 (2 of 4 seats); HR deactivates; a second later
   both bookings are `CANCELLED` and R1 has 0 seats taken.
5. **Failure scenario, live (60 s).** Step 7: upload the cut-off export → `BRAKED`, "appears to be missing 30
   records", nobody deactivated; point at the ALERT log line; operator rejects. Step 8: the good export completes
   (1 changed, 1 new, 2 deactivated).
6. **Crash and restart (40 s).** `docker compose restart app` while showing `GET /api/v1/outbox/status`; after
   the restart the backlog is 0 and the data is intact (it lives in Postgres).
7. **Security (30 s).** Step 10: `/me` for the employee; another employee's record → 403; another tenant → 404.
8. **Monitoring (30 s).** Prometheus at http://localhost:9090: `roster_records_total`, `roster_outbox_backlog`,
   `roster_reconciliation_brake_total`, `roster_rejected_fields_total`.
9. **Tests and benchmark (30 s).** `.\mvnw.cmd verify` summary; `docs/benchmark-results.md` (200k diff in ~2.5 s,
   brake at 80,000 missing).
