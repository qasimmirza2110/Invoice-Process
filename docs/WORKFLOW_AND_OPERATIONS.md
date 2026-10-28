# Invoice Process – workflow and operations

Status (2026-10-19): implemented and tested against mocks and the isolated **test** database only.
No other database has been changed; the app refuses to start against one until the approval gates in section 7.

## 1. What was added

| Phase | What | Main classes |
|---|---|---|
| 1 | Login with a JWT sent as `Authorization: Bearer` (no cookie, no CSRF), roles ADMIN / VENDOR / REVIEWER, per-object access (404 for other users' data), startup guard | `security/*`, `user/*`, `config/SchemaSafetyGuard` |
| 2 | Batch upload (one PDF per request, ≤100 per batch), PostgreSQL queue, worker, review reasons, workflow events | `intake/*`, `queue/*`, `workflow/*`, `validation/StorableValues` |
| 3 | One shared Gemini pacer (RPM / input TPM / RPD per model), one row per Gemini call, at most 3 attempts per job (1 initial + 2 retries, `invoice.worker.max-attempts`), daily-quota pause | `quota/*`, `entity/GeminiCallAttempt` |
| 4 | Categories, reviewer↔category mapping, admin assignment, reviewer corrections, submit gate | `review/ReviewAssignmentService`, `review/ReviewService` |
| 5 | Approve / reject / send back / reopen / requeue, pinned to the exact submitted version | `review/ApprovalService` |
| 6 | Vendor, reviewer and admin dashboards with documented metric definitions | `dashboard/*` |

Since 2026-10-27 the backend is **API-only**: the old HTML pages were archived outside the repository. The user
interface is a separate React frontend built against [REACT_BACKEND_HANDOFF.md](REACT_BACKEND_HANDOFF.md).

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
| `INVOICE_JWT_SECRET` | always, except the local demo | Base64 of ≥32 random bytes. The app refuses to start without it; only the local demo falls back to a random key for that run (everyone logs in again after a restart). |
| `INVOICE_BOOTSTRAP_ADMIN_EMAIL`, `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` | first start only | Creates the first ADMIN if none exists (password 12–72 bytes). Remove afterwards. |
| `INVOICE_LIVE_START_APPROVED` | inside an approved gate | `true` lets the app start against a non-test database… |
| `INVOICE_APPROVED_DATABASE` | with the line above | …but only the one named here as `host:port/database`; it must equal the endpoint in the JDBC URL. |
| `INVOICE_DB_URL`, `INVOICE_DB_USERNAME`, `INVOICE_DB_PASSWORD` | per database | Connection settings (the password may come from pgpass instead). |
| `INVOICE_WORKER_ENABLED` | to process queues | `true` starts the background worker… |
| `GEMINI_LIMIT_RPM`, `GEMINI_LIMIT_TPM`, `GEMINI_LIMIT_RPD`, `GEMINI_INITIAL_TOKEN_ESTIMATE` | for the worker | …but only when these real limits (from AI Studio, for the configured model) are set. No defaults on purpose. |
| `INVOICE_GEMINI_PAID_TIER_CONFIRMED` | extraction outside the local demo | `true` only after checking that the Gemini key's Google Cloud project has active billing; until then the worker sends nothing. |
| `INVOICE_CORS_ALLOWED_ORIGINS` | separately hosted frontend only | exact origins; empty = no CORS. |
| `INVOICE_GOOGLE_CLIENT_ID`, `INVOICE_GOOGLE_API_KEY`, `INVOICE_GOOGLE_APP_ID` | Google Drive picker | public browser values; setup in the runbook section 12. Empty = "Not configured". |
| `INVOICE_ONEDRIVE_ACCOUNT_TYPE`, `INVOICE_ONEDRIVE_CLIENT_ID` (+ `INVOICE_ONEDRIVE_TENANT_ID`, `INVOICE_ONEDRIVE_SHAREPOINT_BASE_URL` for work/school) | OneDrive picker | account type is the owner's decision (PERSONAL or WORK_OR_SCHOOL). |
| `INVOICE_GOOGLE_LIVE_TESTED`, `INVOICE_ONEDRIVE_LIVE_TESTED` | after an approved live test only | `true` changes the shown status to "tested with a real account". |

The server listens on **127.0.0.1:8088** only (local demo). Check it with `GET http://127.0.0.1:8088/api/health`; use
Postman or a React dev server that proxies `/api` to it (it serves no pages itself).

## 5. Metrics – what they mean

- **Passed all automatic checks** = extracted invoices with zero review reasons / extracted invoices (period). Passing ≠ correct.
- **Review reasons** = counts by code (TOTAL_MISMATCH, INVALID_DATE, DUPLICATE_FILE, …).
- **Correction rate** = fields changed V1→submitted / fields compared (12 header + 5 per line item). Human editing, not accuracy.
- **Approved** = a person approved a saved version – not "Gemini was right".
- **Observed accuracy / reviewer agreement**: not shown – they need an independently verified, labelled sample.
- Business days are **Asia/Kolkata**; Gemini's daily quota resets at **midnight Pacific** (shown separately).
- Timestamps are stored as UTC wall-clock time by Hibernate (`hibernate.jdbc.time_zone=UTC`, proven by a pgtest).

## 6. Tests

| Command | Touches | Last result |
|---|---|---|
| `./mvnw -o test` | no database, no network | 2026-10-23 09:27: 224 tests, 0 failures (after the provider hardening) |
| `./mvnw -o test -Ppgtest` | **test** database only (guarded) | 2026-10-23 09:30–09:32: 273 tests, 0 failures (after the errorId and provider changes; real-browser workflow 12/12) |

Count tests from the Maven summary line (`Tests run: N` at the end), not from `target/surefire-reports`: that folder
keeps old pgtest reports from earlier runs.

Postman (`postman/Invoice-Process.postman_collection.json`): run **"00 - Login"** first (set `adminEmail` /
`adminPassword` in the environment, test/demo admin only). Login stores the returned `accessToken` in a collection
variable for that run; a collection pre-request script sends it as `Authorization: Bearer` on every request. **The collection has not been run against a running app**, so this
is unverified. Folders 01, 03, 05, 07, 09 and 11 are read-only. Folders 02, 04 and 08 use the batch upload API and write
(batch, stored PDFs, rejection records) but never call Gemini; 10b writes a version of a legacy invoice – manual only.

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
- Google Drive / OneDrive: browser pickers built 2026-10-23, **mock-tested only**, never run against a real Google or
  Microsoft account (runbook section 12).
- Workflow events are append-only **in the application** only; the database itself does not block UPDATE/DELETE.
- Duplicate-file detection compares SHA-256 per vendor. Since 2026-10-23 all uploads of one vendor are serialised by a
  row lock on the vendor's `app_users` row, so the same PDF sent into two batches at the same moment is accepted once
  (pgtest passed 2026-10-23).
- Cross-vendor duplicate reasons (`DUPLICATE_FILE`, `DUPLICATE_INVOICE_NO`) are shown to reviewers and admins only,
  never to the vendor (since 2026-10-23).
- Upload validation checks extension, content type and the `%PDF-` signature; it does not parse the PDF.
- A Gemini `Retry-After` header is not read; back-off follows this app's own schedule.
- A call that times out on this side may still have been processed and billed by Google.
- Vendors can see who edited their own invoices: the version history shows the editor's name (the e-mail before the
  "@", e.g. `alex`), plus `changedBy` = `user:<id>`.
- The startup guard compares configuration text: it proves the JDBC URL names the approved database, not which
  physical server answers there.
- The Postman collection has not been run against a running app (section 6).

## 9. Local demo (this computer only)

The `localdemo` profile (`src/main/resources/application-localdemo.properties`) runs the app against a separate,
disposable database. It is a local demo, not a deployment.

What `SchemaSafetyGuard` enforces before any database connection (all must hold, otherwise the start is refused):

- the active profile is exactly `localdemo`;
- the datasource is exactly `localhost:5432/invoice_process_local_demo_db` – any other host, port or database is
  refused, and the live and test database names are refused by name, even if `INVOICE_DB_URL` or
  `SPRING_DATASOURCE_URL` tries to override the profile;
- `INVOICE_LIVE_START_APPROVED` and `INVOICE_APPROVED_DATABASE` are **not** set;
- no schema-changing setting in any spelling (`ddl-auto`, `ddlAuto`, `-Dhibernate.hbm2ddl.auto`, environment
  variables, …); `ddl-auto` stays `validate`;
- by default no Gemini API key and the worker off, so no real Gemini call can happen – uploaded PDFs stay `QUEUED`
  ("waiting for extraction"; the admin board says why). The only exception is the explicit opt-in below.

The guard compares configuration, not the physical server: the demo database lives on the same local PostgreSQL
server as other databases, separated by database name only.

Before the first start (each step is a separate, deliberate decision):

1. Create the empty database `invoice_process_local_demo_db` on the local server.
2. Give it the schema the application expects (for example a reviewed schema-only copy of the test database).
   `validate` means a missing table stops the start; the app never creates tables itself.
3. Optional: load **synthetic** demo data. Never copy real invoices into it.

**Simplest way (since 2026-10-23):** right-click `InvoiceApplication.java` → Run As → Java Application. With no profile,
`localdemo` is the default profile (`spring.profiles.default` in `application.properties`), the guard applies all rules
below, and without `INVOICE_JWT_SECRET` a random login key is used for that run. The project was renamed to
`Invoice-Process` in Eclipse after the folder rename — see the runbook section 15 for the clean-up steps.

**Configuration with a fixed login key** (logins survive restarts). Eclipse: **Run → Run Configurations → Java
Application → New** (keep any existing configuration unchanged).

| Field | Value |
|---|---|
| Name | `Invoice Process - localdemo` |
| Main class | `com.actify.invoice.InvoiceApplication` |
| Project | `Invoice-Process` |
| Arguments → VM arguments | `-Dspring.profiles.active=localdemo` (optional now: it is the default) |
| Environment | `INVOICE_JWT_SECRET`; first start only: `INVOICE_BOOTSTRAP_ADMIN_EMAIL`, `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` (12–72 bytes); optional `INVOICE_DB_USERNAME` / `INVOICE_DB_PASSWORD` (or pgpass.conf) |
| Must NOT be present | `INVOICE_LIVE_START_APPROVED`, `INVOICE_APPROVED_DATABASE`, `INVOICE_DB_URL`, `SPRING_DATASOURCE_URL`, `GEMINI_BASE_URL`, any `-D…hbm2ddl…` / `…schema-generation…` option; and – unless you are doing the controlled test below – `GEMINI_API_KEY`, `INVOICE_WORKER_ENABLED` |

Create the JWT secret without displaying it (PowerShell; it goes to the clipboard, paste it into Eclipse):

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) | Set-Clipboard
```

Then log in through the API (Postman "00 - Login" or the React frontend). Remove the bootstrap admin variables after the first successful start.

### 9.1 Optional: ONE controlled Gemini extraction in the local demo

Default = no Gemini. To prove extraction end-to-end on **one synthetic PDF**, without touching the live/test databases
and without processing the queued backlog. Each numbered step that calls Google or restarts the app needs its own
approval ("APPROVE LOCALDEMO START", "APPROVE GEMINI SYNTHETIC SMOKE TEST").

1. With the app running normally (no Gemini), log in as a vendor and upload one **synthetic** PDF (for example a file
   from `samples/` whose footer says it is synthetic). Note its invoice id **N** in "My batches".
2. Stop the app (red square in Eclipse). Never start a second instance while port 8088 is bound.
3. In a **copy** of the localdemo run configuration (Run Configurations → right-click "Invoice Process - localdemo" →
   Duplicate → name it e.g. "Invoice Process - localdemo + Gemini test"), Environment tab, add the values below.
   **Only here.** Spring Boot does not read `.env` files; a `.env` / `.env.txt` next to the project has no effect.

   | Variable | Value |
   |---|---|
   | `INVOICE_LOCALDEMO_GEMINI_ENABLED` | `true` (the explicit opt-in) |
   | `GEMINI_API_KEY` | your key from Google AI Studio (never in a file, chat or screenshot) |
   | `GEMINI_MODEL` | **required**, e.g. `gemini-3.5-flash-lite` – the model whose limits you copy below |
   | `GEMINI_LIMIT_RPM`, `GEMINI_LIMIT_TPM`, `GEMINI_LIMIT_RPD` | that model's limits from AI Studio, **whole numbers** (`250000`, not `250k`) |
   | `GEMINI_INITIAL_TOKEN_ESTIMATE` | `3000` (input tokens to assume before real usage exists) |
   | `INVOICE_WORKER_ENABLED` | `true` |
   | `INVOICE_WORKER_ONLY_INVOICE_IDS` | **N** (only this invoice is ever claimed) |

4. Start it. `SchemaSafetyGuard` refuses the start – naming the missing setting, never a value – unless: profile is
   exactly `localdemo`, the database is exactly `localhost:5432/invoice_process_local_demo_db`, `ddl-auto=validate`, the
   key is set, the endpoint is Google's official `https://generativelanguage.googleapis.com/v1beta` (no override),
   `GEMINI_MODEL` is set explicitly, all limits are valid whole numbers, the legacy single-file extraction stays off, and
   – with the worker on – `INVOICE_WORKER_ONLY_INVOICE_IDS` is a list of ids. If a listed id does not exist or is not
   QUEUED, the admin "Intake & assignment" panel says so in red.
5. Watch the admin page: invoice N goes `QUEUED` → `PROCESSING` → `NEEDS_REVIEW` (or `FAILED` with a safe reason). Every
   other queued invoice stays `QUEUED` and says "this run's worker is limited to invoice(s) [N]". Gemini usage shows
   the tokens **Gemini reported** (never estimated values as usage).
6. Stop the app and go back to the normal configuration (no key, worker off).

Cost/quota: one request per attempt. A transient error (network/timeout/5xx/per-minute 429) is retried at most
`invoice.worker.max-attempts` (3) times with back-off (30 s, 2 min, 8 min); when Google sends a wait hint
(`Retry-After` header or `RetryInfo.retryDelay`), the next try waits at least that long (capped at 1 hour). A timeout
may still have used quota. A daily-quota 429 pauses until the Pacific-time reset instead of failing.
Malformed/blocked/empty answers and HTTP 400/401/403/404 (bad request, wrong key, no access, unknown model) fail at once
(no retry). In the local demo the legacy single-file extraction (`POST /api/invoices/extract`) is switched off (HTTP 409), so the
worker's allowlist is the only way an invoice reaches Gemini.

**Pacing (since 2026-10-23):** the effective requests-per-minute limit is the **lower** of `GEMINI_LIMIT_RPM` and the
project ceiling `gemini.limits.business-max-rpm` (default **15**). The safety margin (0.8) leaves 12 calls a minute, and
they are **spread evenly** – at most one call start every 60 s ÷ 12 = **5 s** (`gemini.limits.even-pacing`, default on),
never 12 at once. Every attempt, including retries, counts against the minute, token and day budgets. The admin
"Queue and Gemini pacing" note shows "Pacing calls evenly over the minute; next call at …" while it waits.

### 9.1b Normal development against the primary database

The primary database `Actify-Internship-Invoice-Process_db` was migrated to the workflow schema on 2026-10-23 (see the
header of `docs/sql/invoice-process-workflow-migration.sql`). Eclipse configuration **"Invoice Process - Development"**
(git-ignored `*.launch` in the project root): profile `development`, `INVOICE_DB_URL` and `INVOICE_APPROVED_DATABASE` both
pinned to that database, `INVOICE_LIVE_START_APPROVED=true`, `ddl-auto=validate`. This is the one configuration for
everyday work: start it once and do the whole flow (vendor upload → automatic extraction → admin assigns → reviewer
corrects and submits → admin approves) without switching configurations.

Secrets are never written into the `.launch` file; it references Windows **User** environment variables, which Eclipse
reads when it starts (restart Eclipse after creating or changing one):

| Launch variable | Value in the launch file |
|---|---|
| `INVOICE_JWT_SECRET` | `${env_var:INVOICE_DEV_JWT_SECRET}` |
| `GEMINI_API_KEY` | `${env_var:INVOICE_GEMINI_API_KEY}` |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` (the model whose limits were copied from AI Studio) |
| `GEMINI_LIMIT_RPM` / `_TPM` / `_RPD` | `15` / `250000` / `500`; `GEMINI_INITIAL_TOKEN_ESTIMATE=3000` |
| `INVOICE_WORKER_ENABLED` | `true` – extracts every **new** queued upload, paced (one call start per 5 s at most), max 3 attempts, pauses when the daily budget is used up |
| `INVOICE_LEGACY_EXTRACT_ENABLED` | `false` – Gemini is only called through the queue |

The 50 legacy invoices (owner empty) have no extraction job, so the worker never touches them; it does not need
`INVOICE_WORKER_ONLY_INVOICE_IDS` (that allowlist is only for controlled test runs). Users are created on the admin page.
Only one instance may use port 8088 – stop any other first. `pgtest` stays for Maven tests only; the local demo stays
available as "Invoice Process - localdemo" (its login key: `${env_var:INVOICE_LOCALDEMO_JWT_SECRET}`).

### 9.2 What each status means (and where to look)

| You see | Meaning | Not |
|---|---|---|
| `QUEUED` "worker is switched off" / "no Gemini API key" | extraction is not set up in this run (normal for the local demo without the Gemini opt-in) | not a failure |
| `QUEUED` "Gemini limits are not configured" | worker on, but a `GEMINI_LIMIT_*` value is missing | not a failure |
| `QUEUED` "Daily Gemini quota reached; paused until …" / "Next extraction attempt at …" | waiting for quota / a scheduled retry | not a failure |
| `PROCESSING` | a Gemini call is in progress (one at a time) | |
| `NEEDS_REVIEW` | extracted; a reviewer can correct it (review reasons = failed automatic checks) | not "correct" |
| `FAILED` + reason | the provider rejected/blocked the request, the answer was unusable, or retries ran out | |

Diagnose in this order: admin "Queue and Gemini pacing" panel (or `GET /api/admin/queue/status`) → the row's "Why it
waits" → the server log line for that invoice (provider HTTP status and safe error code only; never the key or PDF text).

