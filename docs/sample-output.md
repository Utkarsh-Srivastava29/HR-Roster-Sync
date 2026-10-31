# Sample output

Real output of the service code (in-memory store). Regenerate with `scripts/demo.sh` against a running instance.

## Batch with out-of-order, duplicate and bad records — `POST /api/v1/hr/events`

Five records: v7 (office transfer to HYD1, night shift), the late v6 shift change, a resend of v7, a malformed phone, an unknown office that also tries to set a transport-owned pickup point.

```json
{
  "received" : 5,
  "totals" : {
    "APPLIED" : 1,
    "DUPLICATE" : 1,
    "REJECTED" : 2,
    "STALE" : 1
  },
  "results" : [ {
    "index" : 0,
    "employeeId" : "E0000042",
    "outcome" : "APPLIED",
    "errors" : [ ],
    "changedFields" : [ "EMAIL", "MANAGER_ID", "NAME", "OFFICE_CODE", "PHONE", "SHIFT_CODE", "STATUS" ],
    "ignoredFields" : [ ]
  }, {
    "index" : 1,
    "employeeId" : "E0000042",
    "outcome" : "STALE",
    "errors" : [ ],
    "changedFields" : [ ],
    "ignoredFields" : [ ],
    "reason" : "version 6 is older than stored 7"
  }, {
    "index" : 2,
    "employeeId" : "E0000042",
    "outcome" : "DUPLICATE",
    "errors" : [ ],
    "changedFields" : [ ],
    "ignoredFields" : [ ],
    "reason" : "already applied"
  }, {
    "index" : 3,
    "employeeId" : "E0000043",
    "outcome" : "REJECTED",
    "errors" : [ {
      "field" : "phone",
      "code" : "MALFORMED"
    } ],
    "changedFields" : [ ],
    "ignoredFields" : [ ],
    "reason" : "validation failed"
  }, {
    "index" : 4,
    "employeeId" : "E0000044",
    "outcome" : "REJECTED",
    "errors" : [ {
      "field" : "officeCode",
      "code" : "UNKNOWN_OFFICE"
    } ],
    "changedFields" : [ ],
    "ignoredFields" : [ ],
    "reason" : "validation failed"
  } ]
}
```

## Nightly file cut off at 1,200 of 2,000 rows — `GET /api/v1/reconciliations/{runId}`

```json
{
  "runId" : "030145c0-562d-4292-b864-f82e70f32f51",
  "tenantId" : "acme",
  "status" : "BRAKED",
  "createdAt" : "2026-10-31T07:16:30.766592926Z",
  "updatedAt" : "2026-10-31T07:16:30.995817564Z",
  "fileBytes" : 115316,
  "stats" : {
    "rowsRead" : 1200,
    "validRows" : 1200,
    "invalidRows" : 0,
    "errorsByCode" : { },
    "newEmployees" : 0,
    "changed" : 1,
    "unchanged" : 1199,
    "missing" : 800,
    "activeInStore" : 2000,
    "duplicateRowsInFile" : 0,
    "bucketsCompared" : 4096,
    "bucketsOpened" : 773,
    "maxRowsInMemory" : 33,
    "takenAt" : "2026-10-30T23:00:00Z",
    "diffMillis" : 213
  },
  "brake" : {
    "tripped" : true,
    "reasons" : [ "TRUNCATED_FILE", "TOO_MANY_MISSING" ],
    "appearsMissing" : 800,
    "missingRatio" : 0.4,
    "message" : "BRAKE: the file appears to be missing 800 records (40.0% of 2,000 active employees; threshold 30%); trailer missing or row count mismatch. Nothing was applied; approve or reject this run."
  },
  "applied" : { },
  "errorSample" : [ ],
  "note" : "BRAKE: the file appears to be missing 800 records (40.0% of 2,000 active employees; threshold 30%); trailer missing or row count mismatch. Nothing was applied; approve or reject this run."
}
```

## Change feed — `GET /api/v1/changes?after=0&limit=3`

Change events carry ids, field names and versions only (no personal data).

```json
[ {
  "seq" : 1,
  "draft" : {
    "eventId" : "183ce3c4-6d64-4a06-b527-bf266e7e4a6e",
    "tenantId" : "acme",
    "employeeId" : "E0000042",
    "type" : "CREATED",
    "changedFields" : [ "EMAIL", "MANAGER_ID", "NAME", "OFFICE_CODE", "PHONE", "SHIFT_CODE", "STATUS" ],
    "hrVersion" : 7,
    "transportVersion" : 0,
    "source" : "HR_API",
    "occurredAt" : "2026-10-31T07:16:30.362070641Z"
  },
  "attempts" : 0
}, {
  "seq" : 2,
  "draft" : {
    "eventId" : "64a0c82a-e523-4bab-b82b-c277e5561545",
    "tenantId" : "acme",
    "employeeId" : "E0000000",
    "type" : "CREATED",
    "changedFields" : [ "EMAIL", "MANAGER_ID", "NAME", "OFFICE_CODE", "PHONE", "SHIFT_CODE", "STATUS" ],
    "hrVersion" : 1,
    "transportVersion" : 0,
    "source" : "HR_API",
    "occurredAt" : "2026-10-31T07:16:30.494524371Z"
  },
  "attempts" : 0
}, {
  "seq" : 3,
  "draft" : {
    "eventId" : "6f0c8b14-feb5-455a-9f64-3c7f135d6cf1",
    "tenantId" : "acme",
    "employeeId" : "E0000001",
    "type" : "CREATED",
    "changedFields" : [ "EMAIL", "MANAGER_ID", "NAME", "OFFICE_CODE", "PHONE", "SHIFT_CODE", "STATUS" ],
    "hrVersion" : 1,
    "transportVersion" : 0,
    "source" : "HR_API",
    "occurredAt" : "2026-10-31T07:16:30.495170316Z"
  },
  "attempts" : 0
} ]
```
