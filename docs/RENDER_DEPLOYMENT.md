# Deploying the Actify Invoice Process backend (Render Free + Neon Free)

Status: **prepared, not deployed** (updated 2026-11-09: free-tier-only Gemini queue, section 4). Nothing in this repository creates Render or Neon resources by
itself; every step below is a deliberate action by the owner. Re-check the provider facts in section 1 when you
deploy - free plans change.

**Target: the SAME backend at the SAME URL.** This release replaces the code behind the existing Render Web Service
`https://invoice-process.onrender.com` in place. Do **not** create a second Render Web Service, a second repository or
a second backend. The only new infrastructure is a Neon project that holds the PostgreSQL database and a private
bucket for the uploaded PDFs.

**One hosted profile: `render`.** The old `render-free-demo` profile (synthetic accounts, six allowed fixture PDFs,
cookie + CSRF login) was **removed**. The existing service still has `SPRING_PROFILES_ACTIVE=render-free-demo` and must
be switched to `render` before it runs this code (section 11). Otherwise the app starts without its profile file,
binds to `127.0.0.1`, fails Render's health check, and Render keeps the old version running.

---

## 0. What this deployment is - and is not

| | |
|---|---|
| **Is** | A free integration/demo environment for the React developer: the real API, a real PostgreSQL database, PDFs that survive restarts, Bearer-token login |
| **Is not** | A production system for confidential invoices. Free plans have no SLA, sleep when idle, have small quotas and minimal backups |
| **Data rule** | Only **synthetic or explicitly approved, non-confidential** PDFs and test accounts, until the owner formally approves real business records (gates below) |

| Gate | Status | What closes it |
|---|---|---|
| G1 Durable PDF storage | **code ready, provider not yet set up** | Neon private bucket + `INVOICE_S3_*` variables (section 5.2). The `render` profile refuses to start without a working, private bucket. Proof: the restart test in section 9 |
| G2 Schema in the new database | rehearsed script, not applied | `docs/sql/invoice-process-bootstrap-schema.sql` once on the Neon database (5.3) |
| G3 Backups | **open** | Neon Free: 6-hour restore window for the database, no object versioning (section 6). Real invoices need a paid plan with real backups, rehearsed |
| G4 Secrets | open | JWT secret, DB password, S3 secret, Gemini key only as Render secret values; the old local Gemini key rotated |
| G5 Gemini data terms | open | Free tier only: synthetic, sanitized or otherwise permitted non-confidential PDFs, sent only for batches created with `freeTierProcessingConsent=true` and only after the operator confirmed the key's project has no active billing (section 4). No paid mode exists |
| G6 Frontend origin | open | React same-origin / dev proxy, **or** its exact origin in `INVOICE_CORS_ALLOWED_ORIGINS` |
| G7 Post-deploy checks | open | Section 9 passes on the live URL |
| G8 Retention & access | open | Owner/legal decision (6.3) |

---

## 1. Provider facts (verified 2026-10-28 - re-check before deploying)

**Neon Free** ([pricing](https://neon.com/pricing), [Free plan](https://neon.com/blog/neon-free-plan-1-gb-per-project),
[quota FAQ](https://github.com/neondatabase/website/blob/main/content/faqs/free-plan-limits-and-quotas.md)):

| Item | Free plan |
|---|---|
| Price / card | $0, "permanent (not a trial); no credit card required" |
| PostgreSQL storage | 1 GB per project (20 GB across all projects) |
| Compute | 100 CU-hours per project per month (≈ 400 h at 0.25 CU) |
| Scale to zero | after 5 minutes of inactivity, cannot be turned off; wakes "within a few hundred milliseconds" on the next query |
| Object Storage | 5 GB per project; S3-compatible; buckets private by default |
| Network transfer | 5 GB per project per month - **shared** by database traffic and object downloads |
| Restore | 6-hour instant-restore window (max 1 GB of change history), 1 manual snapshot |
| Over the limit | CU-hours or transfer used up → compute **suspended** until next month (connections drop). Storage full → writes fail. No overage charges, "none of these limits delete your data" |

**Neon Object Storage** ([overview](https://neon.com/docs/storage/overview),
[S3 compatibility](https://neon.com/docs/storage/s3-compatibility),
[authentication](https://neon.com/docs/storage/authentication),
[troubleshooting](https://neon.com/docs/storage/troubleshooting); GA announced 2026-08-06): SigV4 only, **path-style
addressing only**, PutObject/GetObject/HeadObject/DeleteObject supported, single PUT up to 5 GiB. Regions:
`aws-us-east-1`, `aws-us-east-2`, `aws-eu-central-1`, `aws-ap-southeast-1`. Access level (private / public_read) is set
in Neon, not via S3 ACLs. Credentials are scoped to a branch (and its children), with scopes `storage:read` /
`storage:write`; **`expires_at` is not enforced - revoke credentials explicitly**. Not available yet: object versioning
and lifecycle rules ("stored but not enforced"). `DeleteObject` soft-deletes on the current branch.

**Render Free web service** ([free](https://render.com/docs/free)): spins down after 15 idle minutes (the next request
waits for a cold start, about a minute); the container filesystem is wiped on every deploy, restart and spin-down; no
persistent disks. **Render Free Postgres expires 30 days after creation and has no backups** - this release does not
use it for the long-term database.

**Why Neon for both:** one free account gives a database that does not expire and a private bucket, both reachable
from Render over TLS, with no card. Cloudflare R2 and Supabase Storage were not needed as fallbacks because Neon's
documented capabilities cover everything this API uses. What has **not** been verified: the owner's actual Neon account,
region availability in it, and a live upload (section 9).

---

## 2. Architecture

```
React (separate project) ──HTTPS + Authorization: Bearer──▶ Render Free web service (this repo, Docker, profile render)
                                                              │                         │
                                         JDBC over TLS (sslmode=require)      S3 API over HTTPS (SigV4, path-style)
                                                              ▼                         ▼
                                                  Neon PostgreSQL (Free)      Neon Object Storage: PRIVATE bucket
                                                  users, batches, invoices,   one object per accepted PDF:
                                                  versions, events, jobs      invoice-documents/<uuid>.pdf
```

- The database keeps all metadata, including each document's id (`invoice_documents.id` = the object's UUID), SHA-256,
  size and original file name (as text only). The PDF bytes are only in the bucket. **No schema change** was needed.
- Downloads go through `GET /api/invoices/{id}/document` / `GET /api/documents/{uuid}` after the role and ownership
  check. The bucket is never exposed: no bucket URL or signed URL is ever returned to a client.
- Render keeps nothing. A restart, redeploy or spin-down loses no data.

## 3. What is deployed

| Item | Value |
|---|---|
| Image | `Dockerfile`: `maven:3.9.16-eclipse-temurin-17-noble` builds, `eclipse-temurin:17.0.20_8-jre-noble` runs as the unprivileged user `invoice` |
| Code level | Java 8 (`maven.compiler.release=8`), Spring Boot 2.7.18 / Spring Security 5.7; runs on the Java 17 JRE |
| Profile | `SPRING_PROFILES_ACTIVE=render` → `application-render.properties` |
| Port | `0.0.0.0:$PORT` (Render sets `PORT`) |
| Health check | `GET /api/health` |
| Auth | `POST /api/auth/login` → JSON access token; every other call `Authorization: Bearer <token>`. No cookies, no CSRF, no session |
| New libraries | Apache PDFBox 3.0.8 (PDF parse check), AWS SDK for Java 2.55.16 `s3` + `url-connection-client` (Java 8 bytecode verified) |

The image has not been built locally (no Docker on the development machine): the first Render build is the first
image build - watch its log.

## 4. Environment variables (names only - set values in Render, never in Git)

| Name | Required | Secret | Value / meaning |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | yes | no | **`render`** (replace `render-free-demo`) |
| `INVOICE_DB_URL` | yes | treat as internal | `jdbc:postgresql://<neon-direct-host>/<database>?sslmode=require` - **direct** host (no `-pooler`), no credentials in the URL |
| `INVOICE_DB_USERNAME` | yes | no | Neon role |
| `INVOICE_DB_PASSWORD` | yes | **yes** | Neon role password |
| `INVOICE_LIVE_START_APPROVED` | yes | no | `true` |
| `INVOICE_APPROVED_DATABASE` | yes | no | `<neon-direct-host>:5432/<database>` - must equal the host/database in `INVOICE_DB_URL` |
| `INVOICE_DB_POOL_SIZE` | no | no | default `3` |
| `INVOICE_JWT_SECRET` | yes | **yes** | Base64 of ≥ 32 random bytes, new for this environment. Changing it logs everyone out |
| `INVOICE_S3_ENDPOINT` | yes | no | Neon storage endpoint, `https://...` host only (Neon Console → Connect → Storage) |
| `INVOICE_S3_REGION` | yes | no | e.g. `us-east-2` (as shown by Neon) |
| `INVOICE_S3_BUCKET` | yes | no | the private bucket's name |
| `INVOICE_S3_ACCESS_KEY_ID` | yes | **yes** | Neon credential token id |
| `INVOICE_S3_SECRET_ACCESS_KEY` | yes | **yes** | Neon credential secret (shown once) |
| `INVOICE_CORS_ALLOWED_ORIGINS` | no | no | empty = no CORS. Otherwise exact origins, comma-separated, no `*`, no path |
| `INVOICE_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | first start on an empty DB only | **yes** (password) | creates the first ADMIN; remove both after the first login |
| `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_LIMIT_RPM/TPM/RPD`, `GEMINI_INITIAL_TOKEN_ESTIMATE`, `INVOICE_WORKER_ENABLED`, `INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED` | **leave unset** until the free-tier queue is switched on | key = yes | extraction stays off until all are set (4.4). Obsolete variables to remove: 4.4 |

**Remove** (no longer read): `INVOICE_STORAGE_DIR`, `INVOICE_STORAGE_EPHEMERAL_ACCEPTED`, `INVOICE_COOKIE_SECURE`,
`INVOICE_FREE_DEMO_ALLOWED_UPLOAD_SHA256` - but only **after** the new release is verified, so a rollback still has
them (section 12). Never set `INVOICE_STORAGE_TYPE`: the `render` profile requires `s3` and refuses anything else.
Never set `SPRING_DATASOURCE_HIKARI_JDBCURL` or similar: `SchemaSafetyGuard` refuses connection redirects.

### 4.1 Gemini - ONE provider, ONE mode: free tier, consented uploads only (gate G5)

**Architecture (2026-11-09).** The backend has exactly one LLM integration: Google Gemini `gemini-3.5-flash-lite` via
the official endpoint `https://generativelanguage.googleapis.com/v1beta`, called only by the background queue worker.
There is **no paid mode, no paid fallback, no second provider, no configurable endpoint, no direct extraction endpoint**
(the former `POST /api/invoices/extract` was removed - it is now refused with 401/403), and **no invoice-id allowlist**.
The previous paid mode, the one-invoice free-tier test mode and `INVOICE_WORKER_ONLY_INVOICE_IDS` no longer exist.

**What "free tier" means - and what the code cannot prove.** Free vs. paid is decided by the Google Cloud project
that owns the API key: a project with an **active Cloud Billing account** is billed, one without is on the free tier.
The URL is the same in both cases. Deleting the paid code from this application therefore does **not** prove that the
key's project cannot incur charges. The operator must check it (below). This application never creates, links,
enables or changes billing.

**Data terms.** Checked 2026-10-28 against the [Gemini API Additional Terms](https://ai.google.dev/gemini-api/terms):
content sent to **Unpaid Services** may be used to improve Google products and may be read by human reviewers, and the
terms say *"Do not submit sensitive, confidential, or personal information to the Unpaid Services."* This mode is for
**synthetic, sanitized or otherwise permitted non-confidential documents only.**

**Two gates, both required, both enforced by the backend (not by the React checkbox):**

1. **Deployment - the operator.** `INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED=true` is the operator's statement:
   "this key's project has no active Cloud Billing account, and I accept the free-tier data terms for uploads whose
   uploader gave free-tier consent". Without it `geminiMode=NOT_CONFIRMED`, nothing is sent, uploads stay `QUEUED`,
   and the Gemini client itself refuses to send (`GeminiDataPolicyTest`).
2. **Record - the uploader.** An invoice is sent **only if** its batch was created with
   `"freeTierProcessingConsent": true` (`POST /api/batches`; default `false`). The consent is stored with its time
   (`upload_batches.free_tier_consent`, `free_tier_consent_at`), shown back on the batch, and **cannot be changed**
   afterwards (the columns are not updatable; no endpoint changes them). It is an **attestation** by the uploader -
   the application does not inspect the PDF, and the checkbox does **not** make a sensitive document safe to upload.
   The worker's SQL claim query only returns consented batches, the claimed entity is checked again in the claim
   transaction, and the consent is read once more immediately before the PDF is sent.

Everything else is **never** sent and stays `QUEUED`: batches created without consent, every batch that existed before
the consent migration (all have `false`), and legacy invoices without a batch. The admin intake board shows such rows
with `extractionState=MISSING_FREE_TIER_CONSENT` and a plain-language `blockedReason`; the queue status counts them in
`queuedWithoutFreeTierConsent`.

**Verifying the Google project (outside the app - record date, project id and limits in your notes):**
Google Cloud Console → select the project that owns the key → **Billing** → the project shows **no linked billing
account** (if one is linked, calls may be billed: unlink it or use another project). Google AI Studio → API keys → the
key belongs to that project; the rate-limit page shows the free tier and the **RPM, TPM and RPD** for
`gemini-3.5-flash-lite`. Google counts these limits per **project** (all keys of the project together).

### 4.2 Bulk upload and the automatic queue

1. The vendor (React) creates a batch: `POST /api/batches` `{"label": "...", "freeTierProcessingConsent": true}`.
2. React uploads **each selected PDF as its own request**: `POST /api/batches/{batchId}/files`, multipart field
   `file` (the controller accepts one file per request; there is no multi-file endpoint). Each accepted file returns
   `202` with its generated `invoiceId` (status `QUEUED`); each rejected file returns its own error (not a PDF, too
   large, duplicate, batch full, ...). All existing limits, PDF parsing, duplicate detection and ownership checks apply
   unchanged.
3. React closes the batch: `POST /api/batches/{batchId}/close`, and shows every file's result.
4. The worker discovers eligible invoices in PostgreSQL by itself - **nobody configures invoice ids** - and processes
   them **oldest first** (`queued_at`, then id), one at a time, with `FOR UPDATE SKIP LOCKED` (two worker executions can
   never claim the same invoice). `QUEUED → PROCESSING → NEEDS_REVIEW`, then the normal assignment / review / approval
   workflow. Extraction history (`gemini_call_attempts`, workflow events, versions) is kept.

### 4.3 Free-tier quota: RPM, TPM, RPD

Configure the limits **you read in Google AI Studio** for this project and model. The owner's target figures are
**15 RPM** and **250,000 input TPM**; they are not a Google guarantee and must be checked in AI Studio. **RPD must be
read from AI Studio - the application assumes no value.** The worker uses at most `limit × 0.8`
(`gemini.limits.safety-margin`, capped at 0.8 by the startup guard) and never more than 15 requests per minute
(`gemini.limits.business-max-rpm`, a business cap), with calls spaced evenly over the minute.

| Budget | How it is enforced (PostgreSQL ledger `gemini_call_attempts`, survives restarts) |
|---|---|
| RPM | calls started in the last 60 s < `floor(RPM × 0.8)`, plus even spacing (`60 s / budget` between call starts) |
| TPM | input tokens of the last 60 s (Gemini's reported `promptTokenCount`, or this app's reservation while unreported) **plus a reservation for the PDF about to be sent** must fit `TPM × 0.8`. The reservation starts at `GEMINI_INITIAL_TOKEN_ESTIMATE` (PDF **and** extraction prompt); once Gemini has reported counts it is the average of the last 20 calls, raised for larger PDFs by the tokens-per-byte rate of those calls × this PDF's size |
| RPD | calls since the last **midnight Pacific time** (`America/Los_Angeles`, daylight saving handled by the zone rules) < `floor(RPD × 0.8)`; or Google reported a per-day 429 |
| Concurrency | the check and the reservation run in one transaction under a PostgreSQL advisory lock, so two workers cannot both spend the last unit |
| Tokens | prompt, output (candidates + thoughts) and total tokens Gemini reports are stored per call and on the invoice; `GET /api/admin/queue/status` shows today's sums |

| Event | What happens | Invoice |
|---|---|---|
| RPM / TPM budget used, or pacing interval not over | no call; looked at again on the next poll (every 2 s - no Gemini call while waiting) | stays `QUEUED` |
| Daily budget used, or Google's per-day 429 | **whole queue paused** until midnight Pacific time (`workerState=DAILY_QUOTA_PAUSED`, `resumeAt`); resumes by itself after the reset **while the service runs** | stays `QUEUED`, attempts not used |
| Google 429 per minute (`RESOURCE_EXHAUSTED`) | **whole queue cools down 60 s** (or longer if Google sends Retry-After, max 1 h) - never a tight retry loop; `workerState=WAITING_FOR_QUOTA`, `resumeAt` | back to `QUEUED` with `nextAttemptAt`, **attempt not used** |
| Network error / timeout, 408, 5xx | bounded back-off: ~30 s, ~2 min, then `FAILED` after 3 attempts | `QUEUED` → … → `FAILED` with reason |
| 400 / 401 / 403 / 404, blocked or unusable answer | no retry | `FAILED` with reason |
| Restart / crash during a call | on startup and on every poll, claims older than 10 min are re-queued (if attempts are left); the interrupted call stays counted (`UNKNOWN`) | `QUEUED` |

No quota event ever marks the remaining invoices `FAILED`, discards them, or switches to another model, provider or
project. A `FAILED` invoice is re-queued only by an ADMIN: `POST /api/invoices/{id}/requeue` `{"reason": "..."}`.

**Render Free sleeps.** A Render Free web service spins down after 15 minutes without inbound traffic, and a stopped
process runs no background worker. The queue is durable in PostgreSQL, so nothing is lost, but **exact-time processing
while the service sleeps is not guaranteed**: after a quota pause or the daily reset, processing resumes only once the
service is running again. On startup (also when a request wakes the service) the worker recovers interrupted work,
logs the waiting queue (`event=queue_startup`) and continues with the oldest eligible invoice on its next poll. While
the service is awake, the scheduler resumes on its own when quota becomes available. This project uses no uptime pings
and no paid infrastructure to keep the service awake.

### 4.4 Render environment - the complete free-tier configuration

| Name | Value |
|---|---|
| `GEMINI_API_KEY` | *(secret - the free-tier key; entered in Render only, never logged or returned)* |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` (required explicitly) |
| `GEMINI_LIMIT_RPM` | RPM from AI Studio (owner target 15 - verify) |
| `GEMINI_LIMIT_TPM` | input TPM from AI Studio (owner target 250000 - verify; a whole number, not `250k`) |
| `GEMINI_LIMIT_RPD` | RPD **read from AI Studio** |
| `GEMINI_INITIAL_TOKEN_ESTIMATE` | first-call reservation for PDF + prompt, e.g. `5000` (conservative for multi-page PDFs) |
| `INVOICE_WORKER_ENABLED` | `true` |
| `INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED` | `true` - the operator's billing check and data-terms acknowledgement (4.1) |

**Remove these obsolete variables** (Render → Environment). Nothing reads them any more. If one is still present, the
startup log shows a warning naming it (`Obsolete environment variable(s) [...]`, never the value), and it switches
nothing on:

`INVOICE_GEMINI_PAID_TIER_CONFIRMED`, `INVOICE_GEMINI_REQUIRE_PAID_TIER`, `INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED`,
`INVOICE_GEMINI_FREE_TIER_DATA_USE_ACKNOWLEDGED`, `INVOICE_WORKER_ONLY_INVOICE_IDS`, `INVOICE_LEGACY_EXTRACT_ENABLED`
(and, in local launch configurations, `INVOICE_LOCALDEMO_GEMINI_ENABLED`). Never set `GEMINI_BASE_URL`: any endpoint
other than the official one refuses the start.

With the confirmation set, `SchemaSafetyGuard` refuses to start unless: the key is set, the endpoint is the official
one, `GEMINI_MODEL=gemini-3.5-flash-lite` (and nothing else overrides `gemini.model`), the `GEMINI_LIMIT_*` and
`GEMINI_INITIAL_TOKEN_ESTIMATE` values are whole positive numbers, the safety margin is ≤ 0.8 and even pacing is on
(`FreeTierStartupGuardTest`). Every variable is read once at startup: a change needs *Save, rebuild and deploy*.

### 4.5 Deploy order (Neon migration first)

1. **Migration first:** `docs/sql/free-tier-consent-migration.sql` on the Neon database (psql, `ON_ERROR_STOP`, after a
   Neon branch / restore point). It only adds `free_tier_consent` (default `false`), `free_tier_consent_at` and a check
   constraint; every existing batch becomes "no consent". The currently running version ignores the new columns, so
   this is safe while it runs. The new version starts with `ddl-auto=validate` and **refuses to start without them**.
   Check: `SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='upload_batches'
   AND column_name='free_tier_consent';` → `1`.
2. Deploy the new commit with the Gemini variables still unset (extraction off). Check `GET /api/admin/queue/status`
   (`geminiMode=NOT_CONFIRMED`, `queuedWithoutFreeTierConsent` present) and `GET /api/batches/{id}`
   (`freeTierProcessingConsent`).
3. Do the Google project check (4.1) and write it down.
4. Set the variables of 4.4, remove the obsolete ones → *Save, rebuild and deploy*. If the log shows
   `SchemaSafetyGuard: REFUSING to start`, the previous version keeps serving: read the message (it names the
   variable, never its value), fix, save again.

**Expected status** (ADMIN, `GET /api/admin/queue/status`; numbers are placeholders):

```json
{ "workerEnabled": true, "workerRunning": true, "geminiMode": "FREE_TIER_AUTO_QUEUE", "geminiKeyConfigured": true,
  "idleReason": null,
  "quota": { "configured": true, "model": "gemini-3.5-flash-lite", "rpmLimit": 15, "tpmLimit": 250000,
             "rpdLimit": 0, "rpmBudget": 12, "tpmBudget": 200000, "rpdBudget": 0, "requestsLastMinute": 0,
             "inputTokensLastMinute": 0, "requestsSinceDailyReset": 0, "inputTokensSinceDailyReset": 0,
             "outputTokensSinceDailyReset": 0, "totalTokensSinceDailyReset": 0,
             "minuteRequestBudgetExhausted": false, "minuteTokenBudgetExhausted": false,
             "dailyBudgetExhausted": false, "rateLimitCooldownUntil": null, "pausedUntil": null,
             "nextDailyReset": "...", "estimatedInputTokensPerCall": 5000, "...": "..." },
  "workerState": "IDLE_NO_ELIGIBLE_INVOICE", "eligibleQueuedInvoices": 0, "deferredQueuedInvoices": 0,
  "resumeAt": null, "queuedWithoutFreeTierConsent": 0, "lastExtractionError": null }
```

(`rpdLimit` / `rpdBudget` show your AI Studio value.)

| `workerState` | Meaning | Fix |
|---|---|---|
| `DISABLED` / `GEMINI_KEY_MISSING` / `LIMITS_NOT_CONFIGURED` | a variable is missing | set it (4.4) |
| `MODE_NOT_CONFIRMED` | `INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED` is not true | do the project check, then set it |
| `DAILY_QUOTA_PAUSED` | RPD used; `resumeAt` = midnight Pacific time | wait - nothing is lost |
| `WAITING_FOR_QUOTA` | RPM/TPM pacing or a 429 cool-down; `resumeAt` | wait |
| `WORKER_ERROR` | the last poll threw (class and time in `idleReason`, details in the log) | read the log |
| `IDLE_NO_ELIGIBLE_INVOICE` / `PROCESSING_AVAILABLE` | running; `eligibleQueuedInvoices` = consented invoices that can start now | expected work missing? was the batch created with consent? |

**Synthetic end-to-end check:** as a test vendor create a batch with `{"label": "free-tier check",
"freeTierProcessingConsent": true}` and upload two synthetic PDFs from `docs/react-handoff/sample-invoices/` (two
requests → two `invoiceId`s, both `QUEUED`). Poll `GET /api/batches/{id}`: `QUEUED → PROCESSING → NEEDS_REVIEW`, the
older first; `requestsSinceDailyReset` goes up by 2. Negative check: a PDF in a batch created **without** consent stays
`QUEUED` and `queuedWithoutFreeTierConsent` goes up by 1.

### 4.6 Rollback

Nothing here deletes an invoice, batch, event or PDF:

1. **Stop sending:** remove `INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED` and `INVOICE_WORKER_ENABLED` (removing
   `GEMINI_API_KEY` is safest) → *Save, rebuild and deploy*. `geminiMode=NOT_CONFIRMED`; new uploads stay `QUEUED`.
2. **Emergency stop without a deploy:** delete or rotate the key in Google AI Studio; claimed invoices then fail with
   401/403 (visible, requeue-able later), the rest stay `QUEUED`.
3. **Code rollback** (previous image/commit) is safe with the migration in place: older versions ignore the two new
   columns, and their inserts get the default `false`. The previous version still contains the removed paid and
   test-mode switches - keep their variables unset. Do **not** drop the consent columns once consented batches exist:
   they are the record of who consented and when. Do not edit the database by hand.

## 5. Neon setup (owner, in the Neon Console)

### 5.1 Project and region

Create **one** Neon project (Free). Choose a region that supports Object Storage **and** is close to the Render
service's region (Render Dashboard → the service → region): Ohio ↔ `aws-us-east-2`, Virginia ↔ `aws-us-east-1`,
Frankfurt ↔ `aws-eu-central-1`, Singapore ↔ `aws-ap-southeast-1`. Use the project's default (main) branch for both the
database and the bucket.

### 5.2 Private bucket and credential

1. Storage → create a bucket (e.g. `invoice-documents`). Access level: **private** (the default). Never `public_read`.
2. Settings → Credentials → create a credential on the **same branch** with scopes `storage:read` and `storage:write`.
   Copy the token id and the secret straight into Render's environment (`INVOICE_S3_ACCESS_KEY_ID`,
   `INVOICE_S3_SECRET_ACCESS_KEY`, marked secret). Do not paste them anywhere else.
3. Copy the endpoint and region into `INVOICE_S3_ENDPOINT` / `INVOICE_S3_REGION`, the bucket name into
   `INVOICE_S3_BUCKET`.
4. To rotate: `neon credentials rotate <token_id>` (or Console), update Render, redeploy. To revoke: Console or
   `neon credentials revoke <token_id>`.

At every start the app writes, checks, reads and deletes a small probe object under
`invoice-documents/_startup-probe/`, then makes sure an **unauthenticated** GET of it is refused. A public bucket,
wrong credentials, a missing scope or a wrong endpoint stop the start with a message naming the variable to check -
never a secret.

### 5.3 Database and schema (gate G2)

1. Use the default database or create one (e.g. `invoice_process`). Use the **direct** connection details (Connection
   pooling toggle **off**): this app keeps a small pool of its own (max 3) and does not need PgBouncer; the direct host
   also suits the schema script ("use a direct connection for migrations").
2. Translate Neon's string `postgresql://<ROLE>:<PASSWORD>@<HOST>/<DB>?sslmode=require&channel_binding=require` (placeholder) by hand into
   `INVOICE_DB_URL=jdbc:postgresql://<HOST>/<DB>?sslmode=require`, `INVOICE_DB_USERNAME=<ROLE>`, `INVOICE_DB_PASSWORD=<PASSWORD>`.
   Never put the password into the URL (the guard refuses `user@host`).
3. **Option A only** (fresh database; for a migration use `pg_restore` instead, section 13.3). Apply the schema **once**, from your own machine:
   `psql -v ON_ERROR_STOP=1 -X -f docs/sql/invoice-process-bootstrap-schema.sql` with the connection in `PG*`
   environment variables (`PGHOST`, `PGDATABASE`, `PGUSER`, `PGSSLMODE=require`, password via `PGPASSWORD` in that
   shell only or a `pgpass` file - never on the command line). Expect 12 tables / 15 foreign keys / 4 unique / 13
   indexes. The script refuses a database that already has tables.
4. If a JDBC connection ever fails with "endpoint ID is not specified" (SNI), add
   `&options=endpoint%3D<endpoint-id>` to `INVOICE_DB_URL` ([Neon connection errors](https://neon.com/docs/connect/connection-errors)).
   Neon lists JDBC as SNI-capable; this was not tested live.

The app never creates or alters tables (`ddl-auto=validate`, `SchemaSafetyGuard`).

**Pool behaviour (`application-render.properties`):** max 3 connections, **no idle connections kept** (closed after 2
idle minutes, so the pool never keeps Neon awake), each connection renewed after 5 minutes, 15 s to obtain a connection
from a waking database, every JPA query cancelled after 30 s. No endless reconnect loop: a request that cannot get a
connection fails with 500 and the next request tries again. With extraction off, nothing polls the database.

## 6. Data safety on the free stack

### 6.1 Upload write path and failure handling

`PdfFiles` checks (size, `.pdf`, content type, `%PDF-`, full PDFBox parse) → per-vendor SHA-256 duplicate check →
`S3DocumentStorage.store`: new UUID → HEAD (never overwrite) → PUT with `x-amz-meta-sha256` → HEAD verifies size and
checksum → **then** the database transaction (`invoice_documents`, `invoices` = `QUEUED`, `extraction_jobs`, workflow
event).

| Failure | Result |
|---|---|
| Bucket unreachable / PUT refused | 500 `"Could not store the original PDF"`; **no** database row, no `QUEUED` (`RealPdfUploadIntakeTest`) |
| Size/checksum mismatch after PUT | object deleted, 500 `"Stored PDF failed its integrity check"` |
| Database step fails after PUT | object deleted again (compensation), error returned |
| Process dies between PUT and commit | an orphan object without a row can remain (never a row without an object). Harmless, counts toward the 5 GB |
| Object later missing on download | 404; bytes changed → 500 integrity error |

### 6.2 Backups and recovery objectives (gate G3)

| | Database (Neon Free) | PDFs (Neon Object Storage) |
|---|---|---|
| Built-in protection | restore to any point in the last **6 hours** (≤ 1 GB change history), 1 manual snapshot | none documented: versioning and lifecycle are "stored but not enforced" |
| Effective RPO | up to 6 h of history, then nothing | **no backup** unless you copy the bucket yourself |

For this demo that is acceptable only because the data is synthetic. Before real invoices: a paid plan with proper
backups, a regular encrypted off-platform copy of the bucket together with a database export of the same moment, and a
rehearsed restore (row counts + SHA-256 of a sample of `invoice_documents`). Whether a Neon branch restore also restores
bucket contents was **not verified** - do not rely on it.

### 6.3 Retention and access (gate G8)

The application never deletes invoices, versions, workflow events or PDFs (no delete route). Retention is an owner /
legal decision. Who can download an original: the vendor who uploaded it, the reviewer assigned to it, every ADMIN
(`InvoiceAccessPolicy`; others get 404).

### 6.4 Quota planning

Each PDF ≤ 10 MB. 5 GB of object storage ≈ 500 maximum-size PDFs (far more typical ones). The **5 GB monthly
transfer** is the tighter limit: every PDF opened in the React app is downloaded from the bucket through the API and
counts, as does database traffic. When it runs out, Neon suspends the project until next month and the API answers 500
on everything that needs the database. Watch usage in the Neon Console.

## 7. Health check

`GET /api/health` → `200 {"status":"UP"}`, public, no secrets, no version. It proves the app started - and because
the storage check runs before the server accepts traffic, a passing health check also means the bucket worked at
start. It does not query the database. `GET /` → 401 is expected (API-only).

## 8. Operations

Logs never contain passwords, tokens, S3 credentials, the Gemini key, request bodies or invoice contents. Startup
storage messages show only the HTTP status and S3 error code; a storage error during a request is logged with its
stack trace (the client only sees the generic message). Logs do contain invoice ids, document UUIDs and the bucket
name - treat them as confidential.

| Startup log message contains | Cause / fix |
|---|---|
| `Tomcat started on port 8088` and the health check never passes | `SPRING_PROFILES_ACTIVE` is not `render` |
| `invoice.storage.type=s3 needs these environment variables: ...` | the listed `INVOICE_S3_*` are missing |
| `Document storage is not usable: HTTP 403 InvalidAccessKeyId` / `SignatureDoesNotMatch` | wrong token id / wrong secret |
| `... HTTP 403 AccessDenied` | credential lacks `storage:write`, or was created on another branch |
| `... HTTP 404 NoSuchBucket` | wrong `INVOICE_S3_BUCKET`, or endpoint of another branch |
| `... SdkClientException caused by UnknownHostException` | wrong `INVOICE_S3_ENDPOINT` |
| `INVOICE_S3_ENDPOINT must be an https:// URL with only a host` | endpoint has a path, credentials or `http://` |
| `can be read WITHOUT credentials` | bucket is `public_read` - set it to private |
| `REFUSING to start: this profile needs remote document storage` | someone set `INVOICE_STORAGE_TYPE` |
| `SchemaSafetyGuard: REFUSING to start ... not approved` | `INVOICE_LIVE_START_APPROVED` missing |
| `... the datasource points at X but only Y is approved` | `INVOICE_APPROVED_DATABASE` ≠ host/database in `INVOICE_DB_URL` |
| `Schema-validation: missing table` | schema not applied (5.3) |
| `INVOICE_JWT_SECRET is not set` / `too short` | signing key missing or invalid |
| `Connection is not available, request timed out after 15000ms` (at runtime) | Neon suspended (quota) or unreachable |

## 9. Post-deploy verification (same URL, test data only)

Run only after the owner approved the deployment. Use a **dedicated test vendor account** and a synthetic PDF from
`docs/react-handoff/sample-invoices/`. No other state-changing calls.

1. `GET https://invoice-process.onrender.com/api/health` → 200 `{"status":"UP"}`, and **no** `X-Invoice-Demo` header,
   **no** `Set-Cookie` (proves the new build).
2. `GET /api/auth/me` without a token → 401 JSON with `WWW-Authenticate: Bearer`.
3. `POST /api/auth/login` → 200 with `accessToken`, `tokenType: "Bearer"`, `expiresIn`, `expiresAt`, `user`; no
   `Set-Cookie`.
4. `GET /api/auth/me` with `Authorization: Bearer <token>` → 200.
5. Test vendor token on `GET /api/admin/users` → 403.
6. As the test vendor: create a batch, upload `synthetic-sample-invoice-01.pdf` → `accepted: true`, status `QUEUED`;
   `GET /api/invoices/{id}/document` → the same SHA-256 as the local file.
7. **Persistence:** Render → Manual Deploy (or Restart) → download again → same SHA-256. Then wait > 15 minutes so the
   service spins down, call `/api/health` (cold start) → download again → same SHA-256.
8. The invoice stays `QUEUED` (extraction off) - nothing claims it was extracted.

Record date, commit and results. If step 1 still shows the old header, the new build is **not** live.

## 10. Security notes

- TLS ends at Render's proxy; database (`sslmode=require`) and bucket (`https://` enforced in code) traffic is TLS.
- CSRF is off **because** authentication is header-only. If cookies ever come back, CSRF must too.
- Tokens: 30 minutes, no refresh token. `POST /api/auth/logout-all`, a role change or disabling a user revokes all
  earlier tokens. A single-device "logout" only forgets the token in the browser.
- Upload checks are **not a virus scan**; no malware scanner is configured.
- The S3 credential can read and delete every PDF: keep it only in Render, rotate it if exposed, revoke unused ones
  (expiry dates are not enforced by Neon).
- Generate a new Gemini key before enabling extraction and revoke the one that was once stored in a local launch file.

## 11. Owner runbook - in-place replacement of the existing service

Every step is manual. Stop at the first failure.

1. **Code:** review the diff; `./mvnw -o -B clean package` green; (recommended) `-Ppgtest` green on the isolated test
   database; commit and merge to `main` with approval. Note the current live commit in Render → Events.
2. **Inspect the current Render database read-only** (section 13.2) and decide: fresh start (A) or migration (B).
   Nothing about its contents is assumed.
3. **Neon:** project + private bucket + credential (5.1-5.2); database (5.3). Then EITHER the bootstrap schema on the
   empty database (option A) OR `pg_restore` of the export (option B) - never both (13.3).
3a. **Local integration checks against Neon** (13.4) pass before any Render setting changes.
4. **Render environment of the EXISTING service** (do not create a new service): set `SPRING_PROFILES_ACTIVE=render`,
   the new `INVOICE_DB_*` / `INVOICE_APPROVED_DATABASE`, all five `INVOICE_S3_*`, a new `INVOICE_JWT_SECRET` (on your
   own machine, e.g. `openssl rand -base64 48`), and the bootstrap admin pair if the database is empty. Health check
   path `/api/health`. Keep the old variables for now. Before overwriting, write the old values of
   `SPRING_PROFILES_ACTIVE` and `INVOICE_DB_*` into your password manager (for rollback).
5. **Deploy:** Manual Deploy of the merged commit. Expect `Invoice PDF storage verified (remote bucket)`,
   `Started InvoiceApplication`, `Tomcat started on port(s): 10000`. Render switches traffic only after `/api/health`
   passes; otherwise the old version keeps serving.
6. **Verify:** section 9.
7. **Clean up:** remove the variables listed in section 4 ("Remove"), remove the bootstrap admin pair after the first
   login; create the team's accounts through `POST /api/admin/users`; redeploy once.
8. **Hand off:** `.\scripts\prepare-react-handoff.ps1 -ApiBaseUrl "https://invoice-process.onrender.com"` (it refuses
   while the old demo build answers). Credentials go to the React developer separately.

## 12. Rollback

Render's Rollback restores an earlier **image** but not earlier **environment variables**. To go back to the old
cookie demo build: restore `SPRING_PROFILES_ACTIVE=render-free-demo` and the old `INVOICE_DB_*` values (from step 4),
then Dashboard → Rollback to the previous deploy. Neon data is untouched by a rollback. Do not delete the old Render
Postgres until the new release is verified and the owner has decided about its data (section 13).

To stop Gemini extraction, change **variables**, not the image: section 4.6.

If the new deploy fails its health check, nothing needs rolling back: the old version is still serving. Read the
first `ERROR` / `Caused by` line in the deploy log (section 8), fix the variable, deploy again.

## 13. Existing data in the current Render database

### 13.1 Which database the service uses

The application reads **only** `INVOICE_DB_URL` (host + database), `INVOICE_DB_USERNAME` and `INVOICE_DB_PASSWORD`
(`spring.datasource.*` in `application.properties` / `application-render.properties`), and `SchemaSafetyGuard` refuses
to start unless `INVOICE_LIVE_START_APPROVED=true` and `INVOICE_APPROVED_DATABASE` equals the host:port/database in
that URL. Render's own `DATABASE_URL` is not read. So: changing these three-plus-two variables on the existing service
**is** the database switch. The old Render database is not touched, deleted or emptied by it - the service simply
stops reading it, and switching the variables back points the service at it again (section 12).

### 13.2 What is known - and not known - about the current Render database

Known from the code that ran there (`render-free-demo`, commit `397a59e`): new accounts could only use
`@example.test` addresses, uploads only matched the six fixture files, PDFs were written to `/tmp` (lost on every
restart), extraction was off. That is evidence about what went in **through the application**, not proof of what is in
the database: rows could have been added by `psql`, by another build, or before the profile was active. **Not known**
(no one has looked): plan, expiry date, row counts, whether anything must be kept.

Read-only checklist for the owner (do not change anything):

1. Render Dashboard → the PostgreSQL instance → **Info**: instance type (Free or paid), **creation date / expiry
   notice** (Free expires 30 days after creation), PostgreSQL version, region.
2. Render Dashboard → the web service → **Environment**: confirm `INVOICE_DB_URL` points at that instance (copy it only
   into your password manager).
3. Connect read-only from your own machine (Render → database → Connect → External, `psql` with the password typed at
   the prompt) and run, inside `BEGIN READ ONLY; ... ROLLBACK;`:
   ```sql
   SELECT count(*) FROM app_users;
   SELECT count(*) FILTER (WHERE email NOT LIKE '%@example.test') AS non_synthetic FROM app_users;
   SELECT role, count(*) FROM app_users GROUP BY role;
   SELECT count(*) FROM upload_batches;  SELECT status, count(*) FROM invoices GROUP BY status;
   SELECT count(*) FROM invoice_workflow_events;  SELECT count(*) FROM invoice_versions;
   SELECT count(*) FROM invoice_documents;   -- every one of these points at a PDF that is GONE (/tmp was wiped)
   SELECT count(*) FROM categories;  SELECT count(*) FROM reviewer_categories;
   ```
   Do not paste results containing e-mail addresses into chat or Git; counts are enough.
4. Decide and write down: **is anything here worth keeping?** (accounts the team already uses, categories, review
   history). If unsure, treat it as worth keeping and export it (13.3 B, step 1) - an export is cheap and reversible.

### 13.3 Strategy

| | A. Fresh Neon database | B. Migrate with `pg_dump` / `pg_restore` |
|---|---|---|
| When | the owner **confirms** the old data is disposable | anything must be kept, or the owner is unsure |
| Schema | `docs/sql/invoice-process-bootstrap-schema.sql` once on the empty database | comes from the dump; the bootstrap script is **not** run |
| Accounts | first ADMIN via `INVOICE_BOOTSTRAP_ADMIN_*`, then the API | existing accounts and passwords keep working |
| Old PDFs | n/a | already lost; old `invoice_documents` rows answer **404** on download |
| Risk | old records no longer appear | dump/restore mistakes; version mismatch |

**Recommendation: B unless the owner explicitly confirms the data is disposable; then A.** This release needs no schema
change (`397a59e` and this release map the same 12 tables), so a restored database passes `ddl-auto=validate`.

**Why the bootstrap script must not follow a restore:** it is written for an *empty* database. Its guard refuses any
database whose `public` schema already has a table (so on a restored database it would only fail), and if forced it
would try to create tables, constraints and indexes that the dump already created. The dump is the schema in option B.

Option B, owner-run (from your own machine; client tools at least as new as the Render server version):
1. `pg_dump -Fc --no-owner --no-privileges -f invoice-render-<date>.dump` against the Render database (connection
   via `PG*` variables, password at the prompt). Keep the file only in encrypted local storage; never commit or share it.
   Record per-table row counts (13.2 step 3) as the reference. **Do not delete or reset the Render database.**
2. In Neon: an **empty** database. `pg_restore --no-owner --no-privileges -d <neon-db> --exit-on-error invoice-render-<date>.dump`.
3. Verify in Neon (read-only): 12 tables, 15 foreign keys, 4 unique constraints, the same row counts as step 1, and the
   identity sequences are past the highest ids, e.g. `SELECT max(id) FROM invoices` vs
   `SELECT last_value FROM pg_sequences WHERE sequencename = split_part(pg_get_serial_sequence('public.invoices','id'),'.',2)`.
4. Old documents: their bytes no longer exist anywhere; leave the rows (history stays intact, download says 404).
   Do not invent replacement files.
5. The Render database stays as it is until the owner decides otherwise (it expires on its own if Free).

### 13.4 Local integration checks before switching the service

With the Neon values in local environment variables (never in Git): (1) read-only connection check to the intended
Neon database; (2) schema present and matching (the app starts with `ddl-auto=validate`); (3) the bucket probe passes;
(4) one generated PDF stored, read back with identical SHA-256, deleted; (5) the app started locally against Neon:
Bearer login, vendor upload → `QUEUED`, authorised download with identical SHA-256, cross-vendor download → 404;
(6) `-Ppgtest` on the local test database only. Every step needs the owner's go-ahead; none of them touches Render.

## 14. Planned improvements

1. Spring Boot 3 / Java 17 upgrade path (2.7 is past open-source support; `nimbus-jose-jwt` is already patched).
2. A database-aware readiness check.
3. `Content-Security-Policy: sandbox` on PDF responses; malware scanning with an approved scanner.
4. A tool that lists orphan objects (bucket keys without an `invoice_documents` row).
5. Paid plans with real backups before any confidential invoice is processed.
