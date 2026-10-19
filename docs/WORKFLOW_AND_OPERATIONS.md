# Invoice Process – workflow and operations

Status (2026-10-19): implemented and tested against mocks and the isolated **test** database only.
No other database has been changed; the app refuses to start against one until the approval gates in section 7.

## 1. What was added

| Phase | What | Main classes |
|---|---|---|
| 1 | Login with a JWT in an HttpOnly cookie, CSRF, roles ADMIN / VENDOR / REVIEWER, per-object access (404 for other users' data), startup guard | `security/*`, `user/*`, `config/SchemaSafetyGuard` |
| 2 | Batch upload (one PDF per request, ≤100 per batch), PostgreSQL queue, worker, review reasons, workflow events | `intake/*`, `queue/*`, `workflow/*`, `validation/StorableValues` |
| 3 | One shared Gemini pacer (RPM / input TPM / RPD per model), one row per Gemini call, at most 3 attempts per job (1 initial + 2 retries, `invoice.worker.max-attempts`), daily-quota pause | `quota/*`, `entity/GeminiCallAttempt` |
| 4 | Categories, reviewer↔category mapping, admin assignment, reviewer corrections, submit gate | `review/ReviewAssignmentService`, `review/ReviewService` |
| 5 | Approve / reject / send back / reopen / requeue, pinned to the exact submitted version | `review/ApprovalService` |
| 6 | Vendor, reviewer and admin dashboards with documented metric definitions | `dashboard/*` |

Pages: `login.html`, `vendor.html`, `reviewer.html`, `admin.html`; `index.html` stays the legacy single-file workspace (ADMIN only).

## 2. Status flow

```
QUEUED -> PROCESSING -> NEEDS_REVIEW -> PENDING_APPROVAL -> APPROVED | REJECTED
          PROCESSING -> QUEUED (retry, stale claim, daily quota)   PROCESSING -> FAILED -> QUEUED (admin requeue)
          PENDING_APPROVAL -> NEEDS_REVIEW (send back)             APPROVED/REJECTED -> NEEDS_REVIEW (reopen)
```
The allowed moves are in `workflow/WorkflowTransitions`; every move writes one `invoice_workflow_events` row.
Legacy invoices (no owner) keep `EXTRACTED` / `FAILED`, are ADMIN-only and are never enrolled automatically.

## 3. Who may do what (enforced on the server)

| Action | VENDOR | REVIEWER | ADMIN |
|---|---|---|---|
| Upload a batch, see own batches/invoices/PDFs | own | – | all |
| Read an invoice / versions / PDF | own uploads | assigned to me | all |
| Correct data (`PUT /api/invoices/{id}`) | 403 | assigned + NEEDS_REVIEW | legacy invoices only |
| Submit a version | 403 | assigned | 403 |
| Approve / reject / send back / reopen / requeue, assign, users, categories | 403 | 403 | yes |

Someone else's object answers **404** (same as "does not exist"); a wrong role on a route answers **403**; no login **401**.

## 4. Launch-configuration variables (Eclipse → Run Configurations → Environment)

| Variable | Needed | Notes |
|---|---|---|
| `INVOICE_JWT_SECRET` | always | Base64 of ≥32 random bytes. The app refuses to start without it. |
| `INVOICE_BOOTSTRAP_ADMIN_EMAIL`, `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` | first start only | Creates the first ADMIN if none exists (password 12–72 bytes). Remove afterwards. |
| `INVOICE_LIVE_START_APPROVED` | inside an approved gate | `true` lets the app start against a non-test database… |
| `INVOICE_APPROVED_DATABASE` | with the line above | …but only the one named here as `host:port/database`; it must equal the endpoint in the JDBC URL. |
| `INVOICE_DB_URL`, `INVOICE_DB_USERNAME`, `INVOICE_DB_PASSWORD` | per database | Connection settings (the password may come from pgpass instead). |
| `INVOICE_WORKER_ENABLED` | to process queues | `true` starts the background worker… |
| `GEMINI_LIMIT_RPM`, `GEMINI_LIMIT_TPM`, `GEMINI_LIMIT_RPD`, `GEMINI_INITIAL_TOKEN_ESTIMATE` | for the worker | …but only when these real limits (from AI Studio, for the configured model) are set. No defaults on purpose. |
| `INVOICE_COOKIE_SECURE` | behind TLS | `true` adds the `Secure` flag on plain-HTTP proxies (HTTPS requests get it automatically). |

The server listens on **127.0.0.1:8088** only (local demo). Open `http://127.0.0.1:8088/login.html`.

## 5. Metrics – what they mean

- **Passed all automatic checks** = extracted invoices with zero review reasons / extracted invoices (period). Passing ≠ correct.
- **Review reasons** = counts by code (TOTAL_MISMATCH, INVALID_DATE, DUPLICATE_FILE, …).
- **Correction rate** = fields changed V1→submitted / fields compared (12 header + 5 per line item). Human editing, not accuracy.
- **Approved** = a person approved a saved version – not "Gemini was right".
- **Observed accuracy / reviewer agreement**: not shown – they need an independently verified, labelled sample.
- Business days are **Asia/Kolkata**; Gemini's daily quota resets at **midnight Pacific** (shown separately).
- Timestamps are stored as UTC wall-clock time by Hibernate (`hibernate.jdbc.time_zone=UTC`, proven by a pgtest).

## 6. Tests (all run on 2026-10-19)

| Command | Touches | Result |
|---|---|---|
| `./mvnw -o test` | no database | 170 tests, 0 failures |
| `./mvnw -o test -Ppgtest` | **test** database only (guarded) | 213 tests, 0 failures (incl. a real-browser workflow) |
| `node src/test/frontend/invoice-review-ui.e2e.mjs` | own mock server | 29 checks, 0 failed |

Postman (`postman/Invoice-Process.postman_collection.json`): run **"00 - Login"** first (set `adminEmail` /
`adminPassword` in the environment, test/demo admin only). The login page request stores the `XSRF-TOKEN` cookie in
the collection variable `xsrfToken`, and every write sends it as `X-XSRF-TOKEN`. **The collection has not been run
against a running app**, so that CSRF handling is unverified. Folder 11 is read-only. Folders 02 and 08 call live
Gemini and 10b writes – manual, with approval.

## 7. Approval gates – before any start against a non-test database (each needs separate approval)

The migration is `docs/sql/invoice-process-workflow-migration.sql`: a **one-time** script, **not executed** yet, run
only with `psql -v ON_ERROR_STOP=1`. Its header explains how to tell whether it has already committed.

| Gate | What |
|---|---|
| L-A | Fresh `pg_dump` (record exit code and size), **restore test** into a new scratch database, then the migration on the scratch copy; its PART 1 / PART 3 checks compare counts and content digests. |
| L-A (app) | Start against the scratch copy with `INVOICE_LIVE_START_APPROVED=true` and `INVOICE_APPROVED_DATABASE=host:port/<scratch database>`; `ddl-auto=validate` must pass. |
| L-B | Another fresh backup, then the migration on the real database (same checks). |
| L-C | First start against the real database, with `INVOICE_APPROVED_DATABASE` naming it. |

Rollback: stop the app / `INVOICE_WORKER_ENABLED=false`. Do not drop the new tables after workflow data exists.

## 8. Known limitations

- Local demo: no TLS; Spring Boot 2.7 is past open-source support; some Spring Security 5.7 fixes are enterprise-only
  (mitigated: 72-byte password limit, no `AuthenticatedVoter` use).
- Login limiter and pacing see only this JVM / this app (other users of the same Google project are invisible).
- In loopback-only mode all browsers share one client address: 5 failed logins lock all logins for 15 minutes.
- Google Drive / OneDrive (Phases 7–8) are not built; the buttons are disabled and labelled as later phases.
- Workflow events are append-only **in the application** only; the database itself does not block UPDATE/DELETE.
- Duplicate-file detection compares hashes and can race when the same PDF arrives in two batches at the same moment.
- A Gemini `Retry-After` header is not read; back-off follows this app's own schedule.
- A call that times out on this side may still have been processed and billed by Google.
- Vendors can see who edited their own invoices (`changedBy` = `user:<id>` in the version history).
- The startup guard compares configuration text: it proves the JDBC URL names the approved database, not which
  physical server answers there.
- The Postman collection has not been run against a running app (section 6).
