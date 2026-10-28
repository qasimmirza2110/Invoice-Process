# Deploying the Actify Invoice Process backend (Render Free + Neon Free)

Status: **prepared, not deployed** (updated 2026-10-28). Nothing in this repository creates Render or Neon resources by
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
| G5 Gemini data terms | open | Extraction of real invoices only with a **paid** Gemini API project (section 4.1); until then nothing is sent |
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
| `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_LIMIT_RPM/TPM/RPD`, `GEMINI_INITIAL_TOKEN_ESTIMATE`, `INVOICE_GEMINI_PAID_TIER_CONFIRMED`, `INVOICE_WORKER_ENABLED` | **leave unset** for this release | key = yes | extraction stays off (4.1) |

**Remove** (no longer read): `INVOICE_STORAGE_DIR`, `INVOICE_STORAGE_EPHEMERAL_ACCEPTED`, `INVOICE_COOKIE_SECURE`,
`INVOICE_FREE_DEMO_ALLOWED_UPLOAD_SHA256` - but only **after** the new release is verified, so a rollback still has
them (section 12). Never set `INVOICE_STORAGE_TYPE`: the `render` profile requires `s3` and refuses anything else.
Never set `SPRING_DATASOURCE_HIKARI_JDBCURL` or similar: `SchemaSafetyGuard` refuses connection redirects.

### 4.1 Gemini (gate G5) - stays OFF

Checked 2026-10-28 against the [Gemini API Additional Terms](https://ai.google.dev/gemini-api/terms) (last updated
2026-04-28): content sent to **Unpaid Services** may be used to improve Google products and read by human reviewers,
and the terms say *"Do not submit sensitive, confidential, or personal information to the Unpaid Services."* Paid
Services require a Google Cloud project with active billing.

The application cannot see billing. `INVOICE_GEMINI_PAID_TIER_CONFIRMED=true` is the **operator's statement**, not
proof. Until it is set (and the worker variables), uploads stay `QUEUED`, `GET /api/admin/queue/status` shows the
`idleReason`, and the Gemini client refuses to send anything (`GeminiDataPolicyTest`). To enable later: in Google Cloud
Console open the project that owns the key → Billing → confirm an active billing account is linked; re-read the terms;
confirm your organisation allows sending these invoices to Google; copy RPM/TPM/RPD for the model from AI Studio.

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
