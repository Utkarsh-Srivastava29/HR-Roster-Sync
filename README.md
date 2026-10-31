# HR Roster Sync — idempotent, out-of-order, self-healing

MoveInSync Backend Case Study 2026 — Case Study 5 (Advanced Track). Java 21 · Spring Boot 3.3 · Postgres 16.

Keeps MoveInSync's employee records correct while the client's HR system sends changes twice, out of order, with bad
data, or cut short:

* **Ingest** create / update / deactivate events with the source's version; reject bad records with a per-record
  report (field + code, never the value). One bad record never fails a batch.
* **Order and idempotency per employee:** older versions are ignored as stale, repeats are no-ops.
* **Field ownership:** HR owns name, email, phone, manager, office, shift; the transport admin owns pickup point and
  special assistance. HR can never overwrite a transport field.
* **Nightly reconciliation** of a 200,000-row export with a hash-bucket diff in bounded memory, and a **safety brake**
  that stops a broken export before it deactivates anyone.
* **Reliable change events** through a transactional outbox; deactivation cancels future bookings and frees seats.

**Proof (2 vCPU, 512 MB heap):** the 200,000-row diff takes **2.1–2.8 s** (limit 60 s) and holds at most **5,194
rows** in memory. A file cut off at 120,000 of 200,000 rows trips the brake: **"appears to be missing 80,000
records"**, nobody deactivated. Replaying 200,000 records changes **nothing**. Crash tests between the database write
and the event publish pass.

---

## Run it

```bash
docker compose up --build        # Postgres + app on :8080 + Prometheus on :9090
bash scripts/demo.sh             # walks through every flow with curl (on Windows: run from Git Bash)
```

Without Docker you only need **Java 21** — the Maven wrapper downloads Maven for you:

| | Windows (PowerShell / cmd) | macOS / Linux |
|---|---|---|
| run all tests | `.\mvnw.cmd verify` | `./mvnw verify` |
| start the app (in-memory store, no database) | `.\mvnw.cmd spring-boot:run` | `./mvnw spring-boot:run` |
| benchmark | `scripts\benchmark.cmd` | `bash scripts/benchmark.sh` |

Or, with Docker and no Java: `docker run --rm -v "${PWD}:/src" -w /src maven:3.9-eclipse-temurin-21 mvn -B verify`.

The safety-brake threshold is configurable: `ROSTER_BRAKE_MAX_MISSING_RATIO=0.25 docker compose up`.

### Demo users (password `demo123`)

| user | role | tenant | can |
|---|---|---|---|
| `acme-hr` | ADMIN | acme | push HR events, upload the nightly export, manage office/shift codes, read everything |
| `acme-ops` | OPERATOR | acme | read employees, edit pickup point / special assistance, book seats, approve or reject braked runs |
| `acme-emp` | EMPLOYEE | acme | read only their own record (`E0000001`) and bookings |
| `globex-hr`, `globex-ops` | ADMIN, OPERATOR | globex | same for another client — used to show isolation |

### API

| method & path | role | what |
|---|---|---|
| `POST /api/v1/auth/token` | — | `{username,password}` → bearer JWT (tenant + role inside) |
| `POST /api/v1/hr/events` | ADMIN | `{records:[{type, employeeId, version, sourceTime, name, email, phone, managerId, officeCode, shiftCode}]}` → per-record report |
| `POST /api/v1/reconciliations` | ADMIN | body = the CSV export (`Content-Type: text/csv`), streamed to disk → `202` with a run id |
| `GET /api/v1/reconciliations[/{id}]` | ADMIN, OPERATOR | run status, diff counts, brake decision, error sample |
| `POST /api/v1/reconciliations/{id}/approve` · `/reject` | OPERATOR | the human decision for a braked run |
| `GET /api/v1/employees[/{id}]` | ADMIN, OPERATOR | records (paged with `after`, `limit`) |
| `PATCH /api/v1/employees/{id}/transport` | OPERATOR | `{pickupPoint, specialAssistance, expectedVersion}` (409 if stale) |
| `PUT /api/v1/routes/{id}` · `POST /api/v1/bookings` | OPERATOR | routes with seats, bookings |
| `GET /api/v1/employees/{id}/bookings` | ADMIN, OPERATOR | an employee's bookings |
| `GET /api/v1/me` · `/me/bookings` | EMPLOYEE | own record only |
| `GET /api/v1/changes?after=&limit=` | ADMIN, OPERATOR | change feed (ids, field names, versions — no personal data) |
| `GET /api/v1/outbox/status` | OPERATOR | unpublished events and age of the oldest |
| `GET/PUT /api/v1/catalog` | ADMIN (PUT) | office and shift codes used by validation |

Export format (`samples/export-nightly.csv`):

```
#EXPORT taken_at=2026-10-30T23:00:00Z
employee_id,version,source_time,name,email,phone,manager_id,office_code,shift_code
E0000001,7,2026-10-30T10:07:00Z,Ravi Patel,emp1@acme.example,+919800000001,,HYD1,NIGHT
...
#END rows=49
```

Errors are RFC 7807 JSON with a stable `code` and a `requestId`. Postman: [`docs/postman_collection.json`](docs/postman_collection.json).
Real responses: [`docs/sample-output.md`](docs/sample-output.md). Design note: [`docs/DESIGN.md`](docs/DESIGN.md).

---

## Key decisions

* **Idempotency key** = (tenant, employee id, HR version). **Version rule:** greater → apply; equal + same content →
  `DUPLICATE`; equal + different content → `CONFLICT`; smaller → `STALE`. Timestamps-only sources use epoch millis.
* **HR events are full snapshots** of the HR-owned fields, which is what makes "ignore older versions" correct. A
  deactivate that arrives before its create leaves a tombstone so the late create is stale.
* **Conflict rule:** each owner sets only its own fields and has its own version stream (HR: source versions;
  transport admin: optimistic concurrency). Foreign fields in a payload are ignored and listed as `ignoredFields`.
* **Outbox:** row + event in one transaction; relay publishes in commit order per employee; at-least-once delivery;
  consumers de-duplicate on `eventId` in the same transaction as their effects.
* **Diff:** 4,096 hash buckets with XOR digests; only buckets whose digests differ are opened; rows are partitioned
  into 64 spill files so at most ~1/64 of the file is in memory, and usually far less.
* **Safety brake:** missing ≥ 30% (configurable) or a missing/wrong trailer → `BRAKED`, nothing applied, ALERT log +
  metric, operator approves or rejects. Approval re-diffs against current data, so nothing stale is applied.

## Results

Full report: [`docs/benchmark-results.md`](docs/benchmark-results.md). Machine: Intel Xeon @ 2.10 GHz, **2 vCPU**,
`-Xmx512m`, OpenJDK 21, Linux. In-memory store (measures our code, not a database).

| | |
|---|---|
| live feed, 8 client threads, 1,000-record batches | **37,900 records/s**; per record p50 23 µs / p99 6.4 ms; per batch p50 180 ms / p99 729 ms |
| replay of the same 200,000 records | 200,000 `DUPLICATE`, **0** new change events, 1.8 s |
| outbox relay | 200,000 events in 0.35 s (~570k/s) to the in-process bus with the booking consumer |
| diff 200k, identical file | 2.1 s, **0 of 4,096 buckets opened** |
| diff 200k, cut off at 120,000 rows | 2.4 s, **BRAKED: appears to be missing 80,000 records**, nothing applied |
| diff 200k, 1% changed / 0.5% new / 1% missing | 2.8 s total incl. applying 5,000 changes; max **5,194 rows** in memory |

## Hard rules → tests

41 tests (`src/test/java`): 37 core and service tests plus 4 Spring end-to-end tests (`ApiTest`).

| rule | test |
|---|---|
| per-employee version order; stale ignored; duplicate no-op | `ChangePolicyTest`, `RosterServiceTest.caseStudyExample_transferV7BeforeShiftChangeV6` |
| replaying the same feed twice changes nothing | `RosterServiceTest.replayingTheSameFeedTwiceChangesNothing`, `ReconciliationTest.replayingTheSameFileTwice…` |
| reject invalid records, per-record report, batch never fails, no values in errors | `ValidationTest`, `RosterServiceTest.oneBadRecordNeverFailsTheBatch`, `ApiTest.batchReportIsPerRecord…` |
| HR never overwrites transport fields | `ChangePolicyTest.hrUpdateNeverTouchesTransportFields`, `RosterServiceTest.hrNeverOverwrites…`, `ApiTest.transportFields…` |
| 200k diff < 60 s, bounded memory | `LargeReconciliationTest` (runs with the normal test suite) |
| safety brake on a cut-off file; nobody deactivated; configurable | `ReconciliationTest.caseStudyExample_fileCutOff…`, `thresholdIsConfigurable`, `rejectedRunAppliesNothing` |
| every applied change has exactly one event | `RosterServiceTest.everyAppliedChangeHasExactlyOneEvent…`, `ConcurrencyTest` |
| **crash between database write and event publish** | `OutboxCrashTest.crashBetweenDatabaseWriteAndPublish_eventIsPublishedAfterRestart`, `crashAfterPublishBeforeMark_redeliveryIsHarmless`, `crashInsideTheTransaction…` |
| deactivation cancels future bookings and frees seats, exactly once | `OutboxCrashTest` |
| **concurrency** | `ConcurrencyTest` — 16 threads, 200 employees × 20 versions, shuffled and duplicated |
| **failure injection** | `OutboxCrashTest` (4 cases), `ReconciliationTest.crashMidRunIsRecoveredByRunningAgain`, `publishFailureIsRetried…` |
| auth, roles, tenant isolation | `ApiTest.rolesAndTenantIsolation`, `RosterServiceTest.tenantsAreIsolated` |

## Assumptions

* HR events and export rows carry the full HR-owned profile for the employee (snapshot, not a field-level patch).
* Phone numbers must be E.164 after removing spaces, dashes and brackets (`+91 98000 00001` is fine; `98000 00001` is not).
* Office and shift codes must exist in the tenant's catalog (`PUT /api/v1/catalog`); the demo seeds them.
* An export row that fails validation still proves the employee exists: they are never deactivated because of it.
* An employee missing from the export is deactivated only if nothing changed after the export was taken.
* Deactivation keeps the HR version, so any later real HR event (e.g. a rehire) still applies.
* One relay instance per deployment publishes events (see the design note for multi-instance).

## Trade-offs (details in the design note)

* **Consistency vs availability vs latency:** writes need the row and its event committed together, so a database outage
  returns 503 rather than accepting changes we might lose (HR re-sends safely). One transaction per record costs some
  throughput but keeps one bad record or lock wait from slowing its neighbours. The brake prefers "a human decides
  tomorrow morning" over "80,000 employees without a cab".
* **Left out on purpose:** a real broker (in-process bus behind an interface; Kafka is a drop-in), field-level HR patches,
  rate limiting, SSO, Flyway (idempotent `schema.sql` instead), encryption at rest, multi-instance relay election.

## Project layout

```
src/main/java/com/moveinsync/roster
├── core/                plain Java: the rules
│   ├── domain/          Employee, HrProfile, TransportProfile, EmployeeField (+ owner), HrEvent
│   ├── validation/      RecordValidator, FieldRule (Matches, InCatalog, NotSelf), FieldError
│   ├── ingest/          ChangePolicy, Decision, Outcome
│   ├── reconcile/       ExportReader, ReconciliationEngine, SafetyBrake, DiffAction
│   ├── outbox/          OutboxRelay, InProcessEventBus, OutboxDraft/Event
│   ├── booking/         Booking, Route, DeactivationSideEffects
│   └── store/           RosterStore, CatalogStore, Buckets
├── persistence/         jdbc (Postgres) and memory implementations
├── service/             RosterService, ReconciliationService, RelayWorker, BookingService, CatalogService
├── api/                 controllers, DTOs, RFC 7807 errors
├── security/            JWT, roles, CurrentUser (tenant from the token only)
├── observability/       Micrometer metrics, request-id logging
└── bench/               Benchmark, SyntheticRoster
samples/                 HR batches and exports used by the demo
```

## Demo video

See [`docs/demo-video-script.md`](docs/demo-video-script.md): a 5-minute run through `scripts/demo.sh`, including the
cut-off export being braked live, a crash-and-restart of the app, and Prometheus counters.
