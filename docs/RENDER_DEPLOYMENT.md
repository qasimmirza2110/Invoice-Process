# Deploying the Actify Invoice Process backend to Render

Status: **prepared, not deployed** (2026-10-27). Nothing in this repository creates Render resources by itself; every
step below is a deliberate action by the owner. Facts about Render were checked against render.com/docs on 2026-10-27
(disks, health checks); re-check them when you deploy, because platform plans and limits change.

---

## 0. Release gates — read first

The container builds and starts the API, but that alone does **not** make it safe for real invoices.

| Gate | Status | What closes it |
|---|---|---|
| G1 Durable PDF storage | **BLOCKED** | A Render persistent disk mounted at `/var/data`, `INVOICE_STORAGE_DIR=/var/data/invoice-documents`, write test passed, backup procedure tested (section 6) |
| G2 Schema bootstrap for an empty database | **BLOCKED** | A reviewed, complete `CREATE` script for all 12 tables (section 5.3). Today `invoices` / `invoice_items` exist in no reviewed script |
| G3 Database backups | open | Render Postgres plan with backups chosen; one restore rehearsed into a scratch database (section 5.5) |
| G4 Secrets | open | `INVOICE_JWT_SECRET`, DB password, Gemini key set only as Render environment values; legacy Gemini key rotated (section 10) |
| G5 Frontend origin | open | React served from the **same origin** as the API (handoff, section 1.3) |
| G6 Proxy behaviour verified | open | Post-deploy checks in section 9 pass |

Until G1 and G2 are closed the service must not receive **real customer invoices**. A test deployment with synthetic
PDFs only is fine once G2 is closed (an empty schema-less database cannot even start: `ddl-auto=validate` fails).

---

## 1. What is deployed

| Item | Value |
|---|---|
| Image | `Dockerfile` (multi-stage): `maven:3.9.16-eclipse-temurin-17-noble` builds, `eclipse-temurin:17.0.20_8-jre-noble` runs, as the unprivileged user `invoice` |
| Artifact | `target/actify-invoice-process-0.0.1-SNAPSHOT.jar` — API only, contains no HTML/CSS/JS |
| Profile | `SPRING_PROFILES_ACTIVE=render` (set in the image) → `src/main/resources/application-render.properties` |
| Port / bind | `0.0.0.0:$PORT` (Render sets `PORT`, default 10000). Local runs keep `127.0.0.1:8088` |
| Build context | only `pom.xml` and `src/` (`.dockerignore`), so `.env*`, launch files, PDFs and `target/` never enter the image |
| Tests | not run during the image build; run `./mvnw -o test` (and `-Ppgtest` on the test database) before every release |

The image was **not built locally** (Docker is not installed on the development machine). The first Render build is
therefore also the first image build — watch its log.

## 2. Create the Web Service (Render dashboard)

1. New → **Web Service** → connect the GitHub repository, choose the release branch.
2. Runtime: **Docker** (Render finds `Dockerfile` at the repository root). No build or start command is needed.
3. Instance type: a **paid** instance (persistent disks are not available on free instances). Size: at least 512 MB
   RAM; the JVM uses up to 75 % of the container memory (`-XX:MaxRAMPercentage=75`).
4. Region: the same region as the PostgreSQL database (internal connection, lower latency).
5. Health check path: **`/api/health`** (section 7).
6. Add the persistent disk (section 6) **before** the first real start.
7. Add the environment variables (section 4). Mark secret values as secret; never commit them.
8. Instances: **exactly 1** (a disk-backed service cannot scale out; also the login limiter and Gemini pacing are
   per-instance).

`render.yaml` (Blueprint) is intentionally **not** included: it would have to guess plan names, region, database names
and disk size, which are the owner's decisions. If one is wanted later, write it after these decisions are made and
keep every secret as `sync: false`.

## 3. How the configuration is resolved

`application.properties` (shared defaults) → `application-render.properties` (overrides) → environment variables.
The `render` profile deliberately has **no fallback** for the database URL and the storage folder: if
`INVOICE_DB_URL` or `INVOICE_STORAGE_DIR` is missing, startup fails instead of silently using `localhost` or a
folder inside the container (test: `DefaultProfileBootLoadingTest.theRenderProfileHasNoLocalDatabaseOrStorageFallback`).

## 4. Environment variables (names only — values are set in Render, never in Git)

| Name | Required | Secret | Meaning |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | preset `render` in the image | no | do not change |
| `INVOICE_DB_URL` | yes | no* | JDBC URL **without credentials**: `jdbc:postgresql://<internal-host>:5432/<database>` (add `?sslmode=require` when using the external hostname). *Treat as internal information |
| `INVOICE_DB_USERNAME` | yes | no | database user |
| `INVOICE_DB_PASSWORD` | yes | **yes** | database password |
| `INVOICE_LIVE_START_APPROVED` | yes | no | `true` — the owner's explicit approval to start against this database |
| `INVOICE_APPROVED_DATABASE` | yes | no | `<internal-host>:5432/<database>` — must equal the host:port/database in `INVOICE_DB_URL` exactly (same spelling) |
| `INVOICE_JWT_SECRET` | yes | **yes** | Base64 of ≥ 32 random bytes, generated fresh for this environment (never reuse a local value) |
| `INVOICE_STORAGE_DIR` | yes | no | `/var/data/invoice-documents` (on the persistent disk) |
| `INVOICE_BOOTSTRAP_ADMIN_EMAIL` / `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` | first start only | **yes** (password) | creates the first ADMIN if none exists; **remove both** after the first successful start |
| `GEMINI_API_KEY` | for extraction | **yes** | omit to run without extraction (uploads stay `QUEUED`) |
| `GEMINI_MODEL` | no | no | default `gemini-3.5-flash-lite` |
| `GEMINI_LIMIT_RPM`, `GEMINI_LIMIT_TPM`, `GEMINI_LIMIT_RPD`, `GEMINI_INITIAL_TOKEN_ESTIMATE` | for the worker | no | whole numbers from Google AI Studio for this project + model |
| `INVOICE_WORKER_ENABLED` | no | no | default `false`; `true` only after the limits and key are set |
| `INVOICE_LEGACY_EXTRACT_ENABLED` | no | no | default `false` in `render` (legacy single-file extraction off) |
| `INVOICE_GOOGLE_*`, `INVOICE_ONEDRIVE_*` | no | no (public browser values) | cloud picker configuration, see the provider guide |

Do **not** set `INVOICE_WORKER_ONLY_INVOICE_IDS` in production unless you deliberately want the worker restricted to
named invoices. Do not set any `spring.jpa.*`, `hibernate.*` or `spring.sql.init.*` variable: `SchemaSafetyGuard`
refuses schema-changing values.

How the guard decides (`config/SchemaSafetyGuard`, before any connection): profile `render` is a non-demo profile →
`INVOICE_LIVE_START_APPROVED` must be `true` → `INVOICE_APPROVED_DATABASE` must parse as `host:port/database` and equal
the endpoint parsed from `INVOICE_DB_URL` (credentials or multiple hosts in the URL are refused) → all schema settings
must be non-mutating. Tested in `DefaultProfileBootLoadingTest.theRenderProfileBindsToPortAndAllInterfacesAndStaysGuarded`.

## 5. PostgreSQL

### 5.1 Choose the target

- Create a **new, dedicated** PostgreSQL database for this deployment (Render Postgres or another hosted PostgreSQL).
- Never point the service at `localhost`, at a developer's Windows PostgreSQL, or at any of the local databases:
  `Actify-Internship-Invoice-Process_db` (primary), `invoice_process_local_demo_db` (demo),
  `invoice_process_migration_scratch_db` (scratch), `Actify-Internship-Invoice-Process_test_db` (tests).
- Do not copy local data into it. Existing local invoices are not migrated by this release.

### 5.2 Connection

The application reads only `INVOICE_DB_URL`, `INVOICE_DB_USERNAME`, `INVOICE_DB_PASSWORD` (`spring.datasource.*` in
`application.properties`). Render's `DATABASE_URL` (`postgres://user:pass@host/db`) is **not** read and is not a JDBC
URL — translate it by hand into the three variables (credentials never inside the URL; the guard refuses `user@host`).
Use the database's **internal** hostname when the service and the database are in the same Render region.

### 5.3 Schema bootstrap (gate G2) — backup-first, never automatic

The application never creates or alters tables (`ddl-auto=validate`, enforced by `SchemaSafetyGuard`). The reviewed
scripts in `docs/sql/` are *migrations* for an existing database; the base tables `invoices` and `invoice_items` (and
`invoice_documents`, `invoice_versions`, which exist only as commented reference DDL) were created by Hibernate during
development. A complete bootstrap script must be produced and reviewed first:

1. On the **isolated test database** only, run the pgtest suite once (`./mvnw -o test -Ppgtest`); Hibernate creates the
   full schema there (the one place `ddl-auto=update` is allowed).
2. Export it read-only: `pg_dump --schema-only --no-owner --no-privileges -d Actify-Internship-Invoice-Process_test_db`.
3. Compare it with `docs/sql/invoice-process-workflow-migration.sql`, which tightens constraints beyond what Hibernate
   generates; merge those constraints in. Review every statement (no `DROP`, no data).
4. Rehearse: create a scratch database, apply the script with `psql -v ON_ERROR_STOP=1`, start the application against
   it locally with the approval variables naming the scratch database → it must start without schema errors.
5. Commit the reviewed script as `docs/sql/invoice-process-bootstrap-schema.sql`.
6. Apply it once to the new hosted database (empty, so no backup is lost — still take Render's snapshot/backup first).

### 5.4 First administrator

Set `INVOICE_BOOTSTRAP_ADMIN_EMAIL` and `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` (12–72 bytes) for the first start only.
`AdminBootstrap` creates the ADMIN when no admin exists and never logs the password. Remove both variables after the
first successful login, then redeploy.

### 5.5 Backup and restore

- Use a Render Postgres plan with automatic backups / point-in-time recovery, or schedule `pg_dump -Fc` to storage
  you control. Record the retention period.
- Before every schema change: fresh dump → restore into a scratch database → run the change there → validate the app.
- Rehearse one full restore before go-live and write down the time it took.
- Database backups do **not** contain the PDFs; they live on the disk (section 6). Restore both to the same point in
  time, otherwise `invoice_documents` rows point at missing files (download → 404) or files have no row (orphans).

## 6. Uploaded PDFs — persistent storage (gate G1)

`InvoiceDocumentStorage` writes each accepted PDF to `invoice.storage.dir/<uuid>.pdf`, records its SHA-256 in
`invoice_documents`, and re-checks the hash on every read. Render's normal container filesystem is **ephemeral**: a
deploy or restart deletes everything written outside a persistent disk. Without a disk, every stored original PDF
would be lost on the next deploy while its database row remains.

### 6.1 Chosen approach: Render persistent disk

| Item | Value / consequence |
|---|---|
| Mount path | `/var/data` (any absolute path except `/`, `/opt`, `/home`, `/etc` and a few others; `/var/data` is allowed) |
| `INVOICE_STORAGE_DIR` | `/var/data/invoice-documents` |
| Plan | paid web service required; size can be increased later but never decreased |
| Deploys | a disk **disables zero-downtime deploys**: Render stops the old instance before starting the new one (short outage per deploy) |
| Scaling | **one instance only**; the disk is not available to other services, build or pre-deploy commands |
| Snapshots | automatic every 24 h, kept 7 days; restore replaces the **whole** disk (later changes are lost) |

**Ownership check (risk to validate):** the image runs as the non-root user `invoice`. Render's documentation does not
state the ownership of a freshly mounted disk. After the first deploy, verify through the Render shell:
`touch /var/data/invoice-documents/.write-test && rm /var/data/invoice-documents/.write-test`. If it fails with
"permission denied", the first upload would fail with a 500 — fix the ownership before accepting uploads (for example
by adjusting the Dockerfile's user/UID to match the mount, after checking Render's current guidance for Docker disks).

### 6.2 Backup and recovery of the PDFs

- The 7-day snapshots are the minimum. For longer retention, copy `/var/data/invoice-documents` regularly (e.g. a
  `tar` stream over the Render shell or SSH) to storage you control, together with the matching database dump.
- After any restore, verify integrity: for a sample of `invoice_documents` rows, download
  `GET /api/invoices/{id}/document` as an admin — a mismatch answers 500 "Stored PDF failed its integrity check", a
  missing file 404 (see `docs/sql/inspection-queries.sql`, query on documents, for the read-only listing).

### 6.3 Alternative not implemented: object storage

An S3-compatible bucket (with server-side encryption, private ACLs, short-lived signed reads) would remove the
single-instance and downtime limits. It is **not implemented**: `InvoiceDocumentStorage` only knows the filesystem.
Adding it is a separate change (new storage implementation behind the same interface, credentials as secrets, a
migration that copies files and compares SHA-256 per document). No invoice is uploaded to any cloud storage by this
release.

## 7. Health check

`GET /api/health` → `200 {"status":"UP"}` (public, no cookie needed, `Cache-Control: no-store`). It confirms the JVM,
Spring context and web server are up. It deliberately does **not** check the database or Gemini and reveals no version,
configuration or user data (`HealthController`, test `ApiOnlySurfaceWebTest`). Render routes traffic only after the
check passes, stops routing after ~15 s of failures and restarts after ~60 s.
No Spring Boot Actuator endpoints are included or exposed.

## 8. Operations

**Logs.** Application logs go to stdout (Render log stream). By design they never contain passwords, JWTs, the Gemini
key or request bodies: login failures log no e-mail, CSRF failures log only presence/match flags, unexpected errors
log a stack trace with a random `errorId` (the client receives only the id), RestTemplate body logging is kept off.
Logs do contain invoice ids, document UUIDs and file-level events — treat the log stream as confidential and restrict
who can read it.

**Startup failures** (look for the first `ERROR` / `Caused by` in the deploy log):

| Message contains | Cause |
|---|---|
| `SchemaSafetyGuard: REFUSING to start - starting against a non-test database is not approved` | `INVOICE_LIVE_START_APPROVED` missing / not `true` |
| `... the datasource points at X but only Y is approved` | `INVOICE_APPROVED_DATABASE` ≠ host:port/database in `INVOICE_DB_URL` |
| `Could not resolve placeholder 'INVOICE_DB_URL'` / `'INVOICE_STORAGE_DIR'` | variable missing in the render profile |
| `INVOICE_JWT_SECRET is not set` / `too short` / `not valid Base64` | signing key missing or invalid (the value is never printed) |
| `Schema-validation: missing table` | gate G2 not done: the database has no (complete) schema |
| `Connection refused` / `password authentication failed` | network, hostname, SSL mode or credentials |
| `invoice.storage.dir must be an absolute path` | relative `INVOICE_STORAGE_DIR` |

**Shutdown and the worker.** On deploy/restart Render sends SIGTERM; the JVM is PID 1 and `server.shutdown=graceful`
lets in-flight HTTP requests finish (up to 25 s). The extraction worker runs on one scheduler thread; a Gemini call in
progress at that moment is cut off. Nothing is lost: on the next start the claim is recovered after 10 minutes
(`invoice.worker.stale-claim-minutes`) — back to `QUEUED` with one attempt used, or `FAILED` if none are left — and the
unfinished call is recorded as `UNKNOWN` and still counts toward the Gemini quota. Prefer deploying when the queue is
idle (`GET /api/admin/queue/status`).

**Worker safety.** The worker processes nothing unless `INVOICE_WORKER_ENABLED=true` **and** a Gemini key **and** all
four limits are set. It never exceeds `min(AI Studio RPM, 15) × 0.8` requests per minute, spaces calls evenly, pauses
for the day when the daily quota is reached and makes at most 3 attempts per invoice. It processes the whole queue of
*this* database — which is why the deployment must never point at a database with a backlog you did not intend to
process.

## 9. Post-deploy verification (synthetic data only)

1. `GET https://<service>/api/health` → 200 `{"status":"UP"}`.
2. `GET /` → 401 JSON (no HTML page). `GET /api/auth/me` → 401 and a `Set-Cookie: XSRF-TOKEN=…; Secure` header
   (`Secure` proves `X-Forwarded-Proto` is honoured — `server.forward-headers-strategy=native`).
3. Log in as the bootstrap admin with the XSRF header → `Set-Cookie: INVOICE_PROCESS_AUTH=…; HttpOnly; Secure;
   SameSite=Strict`.
4. Login limiter / client address: make 5 failed logins with a throw-away e-mail from one network, then log in from a
   **different** network with another account. If the second login is refused with 429, Render's proxy address is
   being used as the client address for everyone (`X-Forwarded-For` not trusted) — the per-address lock would then
   lock all users for 15 minutes after any 5 failures. Report this before go-live (fix: configure Tomcat's trusted
   internal proxies for Render's proxy range, or drop the per-address key behind the proxy).
5. Disk write test (section 6.1), then upload one **synthetic** PDF as a test vendor and download it as admin; redeploy;
   download it again — it must still be served (proves persistence).
6. Remove the bootstrap admin variables; rotate nothing into Git.

## 10. Security notes for this deployment

- TLS ends at Render's proxy; the app only speaks HTTP inside the platform network. Cookies are always `Secure` in the
  `render` profile.
- No CORS headers are sent; the frontend must be same-origin (handoff, section 1.3).
- A Gemini key was previously stored in plain text in a local Eclipse launch file outside this repository (reported in
  the project notes). It was never committed (history scan, 2026-10-27), but **generate a new key** for this deployment
  and revoke the old one.
- Each environment gets its own `INVOICE_JWT_SECRET`; changing it logs everyone out (intended).
- The PDF endpoint serves user-uploaded files inline. Same-origin framing is allowed; see planned improvements for a
  sandboxing header.

## 11. Planned improvements

1. **Spring Boot upgrade** — 2.7.x is past open-source support. Phase 1: stay on 2.7, keep dependencies patched
   (already overriding `nimbus-jose-jwt`). Phase 2: move the build and runtime to Java 17 (`release 17`). Phase 3:
   Spring Boot 3.x (`javax.*` → `jakarta.*`, Spring Security 6 config DSL changes — the CSRF token handling changes
   and must be re-tested with `CsrfTokenStabilityWebTest`), Hibernate 6 query review. Each phase: full `test` +
   `-Ppgtest` run before merge.
2. Object storage for PDFs (section 6.3).
3. `Content-Security-Policy: sandbox` on PDF responses, and evaluate PDF structure validation / malware scanning of
   uploads (today: size, extension, content type and `%PDF-` signature only).
4. Explicit JSON request-size limit (multipart is capped at 11 MB; JSON bodies rely on Tomcat defaults and item-count
   validation).
5. Database-level append-only protection for versions and workflow events (today enforced by the application).
6. A readiness check that includes the database, if Render routing should depend on it.
