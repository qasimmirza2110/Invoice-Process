# Deploying the Actify Invoice Process backend to Render

Status: **prepared, not deployed** (2026-10-27). Nothing in this repository creates Render resources by itself; every
step below is a deliberate action by the owner. Facts about Render were checked against render.com/docs on 2026-10-27
(disks, health checks); re-check them when you deploy, because platform plans and limits change.

**Two separate targets — never mix them:**

| | A. Temporary free demo | B. Production |
|---|---|---|
| Profile | `render-free-demo` (`application-render-free-demo.properties`) | `render` (`application-render.properties`) |
| Purpose | frontend integration with **synthetic data only** | real customer invoices |
| Render plans | Free web service + Free Postgres ($0) | paid web service with disk + paid Postgres with backups |
| PDFs | ephemeral `/tmp/invoice-demo-documents-EPHEMERAL` | persistent disk `/var/data/invoice-documents` |
| Gemini / worker | refused at startup | owner decision, gate G4 |
| Runbook | **section 13** | sections 0–12 |

The production `render` profile must **not** be run on a Free service: Free has no disk, so its storage probe would
pass on the image's ephemeral folder and PDFs would silently vanish on the next spin-down.

---

## 0. Release gates — read first

The container builds and starts the API, but that alone does **not** make it safe for real invoices.

| Gate | Status | What closes it |
|---|---|---|
| G1 Durable PDF storage | **BLOCKED** (code ready) | Code: atomic, fsynced writes and a startup write probe (`invoice.storage.verify-on-startup=true` in `render`). Still needed: a Render persistent disk mounted at `/var/data`, `INVOICE_STORAGE_DIR=/var/data/invoice-documents`, the post-deploy persistence test and a tested PDF backup (sections 6, 9) |
| G2 Schema bootstrap for an empty database | **REHEARSED** (2026-10-27) | `docs/sql/invoice-process-bootstrap-schema.sql` creates all 12 tables; matched to the JPA mappings by `SchemaBootstrapScriptTest`; rehearsed on a disposable scratch database: applied, second run refused with no change, app started with `validate` and passed a 37-check synthetic smoke test. Still needed per environment: apply once to the new hosted database (section 5.3, step 6) |
| G3 Database backups | open | A **paid** Render Postgres plan (Free has no backups and expires after 30 days); one point-in-time restore rehearsed into a new database (section 5.5) |
| G4 Secrets | open | `INVOICE_JWT_SECRET`, DB password, Gemini key set only as Render environment values; legacy Gemini key rotated (section 10) |
| G5 Frontend origin | open | React served from the **same origin** as the API (handoff, section 1.3) |
| G6 Proxy behaviour verified | open | Post-deploy checks in section 9 pass |

Until G1, G2 and G3 are closed the service must not receive **real customer invoices**. A staging deployment with
synthetic PDFs only is fine once the G2 rehearsal has passed (an empty schema-less database cannot even start:
`ddl-auto=validate` fails).

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

1. New → **Web Service** → connect the GitHub repository, choose the release branch (`main`).
2. Runtime: **Docker** (Render finds `Dockerfile` at the repository root; leave Dockerfile Path and Docker Command
   empty — the image's `ENTRYPOINT` starts the JAR). Do **not** add build-time secrets: Render passes environment
   variables to the Docker build as build arguments too, and this Dockerfile needs none.
   **Auto-Deploy: Off** (Settings → Build & Deploy) for a manual, controlled release; deploy with Manual Deploy →
   *Deploy latest commit* (or *Deploy a specific commit*). "After CI Checks Pass" would never deploy: the repository
   has no CI checks.
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
named invoices. Do not set any `spring.jpa.*`, `hibernate.*`, `spring.sql.init.*` or `spring.datasource.*` override (other than via `INVOICE_DB_*`): `SchemaSafetyGuard`
refuses schema-changing values.

How the guard decides (`config/SchemaSafetyGuard`, before any connection): first, no setting may redirect the connection
away from `spring.datasource.url` (`SPRING_DATASOURCE_HIKARI_*`, `SPRING_DATASOURCE_JNDINAME`, `SPRING_DATASOURCE_TYPE` are
refused - added 2026-10-27, `DataSourceRedirectGuardTest`); then profile `render` is a non-demo profile →
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

The application never creates or alters tables (`ddl-auto=validate`, enforced by `SchemaSafetyGuard`); there is no
Flyway/Liquibase on purpose (a migration tool would change the hosted schema automatically at every start).
The complete schema for an **empty** database is **`docs/sql/invoice-process-bootstrap-schema.sql`** (schema version 1,
12 tables, 15 foreign keys, 4 unique constraints, 13 secondary indexes). The two older scripts in `docs/sql/` remain
*migrations* for databases that already existed; never run them on a fresh database.

How the script was built and is kept honest:

- Every definition is copied from a reviewed source (workflow migration, versioning reference DDL) or, for `invoices`
  and `invoice_items`, from the schema Hibernate generates for the current mappings (identical in the test and
  local-demo databases, read-only `pg_dump --schema-only`, 2026-10-27). The only addition is the
  `ix_invoice_items_invoice` index. The header lists the source of each table.
- `SchemaBootstrapScriptTest` (runs in every `./mvnw test`, no database) fails when any mapped table, column, SQL type,
  varchar length, NOT NULL, named index, unique constraint or foreign key differs from the script, or when the script
  contains `DROP`/`TRUNCATE`/`DELETE`/`UPDATE`/`INSERT`/`ALTER TABLE`. Mutation-checked: changing one length or
  removing one NOT NULL makes it fail.
- Fail-fast and repeat-safe: one transaction; a guard refuses (before any change) a database whose schema `public`
  already has a table, and the four local development databases by name. A second run changes nothing.

Rehearsal still required (owner approval, a **new disposable** scratch database — never the primary, test or demo
database):

1. `createdb` an empty scratch database, e.g. `invoice_process_bootstrap_scratch_db`.
2. `psql -v ON_ERROR_STOP=1 -X -d invoice_process_bootstrap_scratch_db -f docs/sql/invoice-process-bootstrap-schema.sql`
   → exit 0; the read-only checks at the end show 12 tables, 15 `f`, 4 `u`, 13 indexes.
3. Run it a second time → exit ≠ 0 with `bootstrap refused: schema public already contains 21 table/view/sequence object(s)` (12 tables + 9 identity sequences); take a
   `pg_dump --schema-only` before and after this run → identical.
4. Start the application against it with ddl-auto unchanged (`validate`): `INVOICE_DB_URL` = the scratch URL,
   `INVOICE_LIVE_START_APPROVED=true`, `INVOICE_APPROVED_DATABASE=localhost:5432/invoice_process_bootstrap_scratch_db`,
   `INVOICE_JWT_SECRET` = a throw-away value, `INVOICE_BOOTSTRAP_ADMIN_EMAIL/PASSWORD` = a synthetic admin
   (`@example.test`), worker off, no Gemini key → it must start without `Schema-validation` errors.
5. Synthetic smoke test against it (login with CSRF, create a category, upload one synthetic PDF as a vendor, read it
   back as admin), then stop the application and `dropdb` the scratch database.
6. On Render: apply the same script once to the new, empty database (section 5.3 is its only schema step), from a
   machine allowed to connect (Render's external URL with `sslmode=require`, or a Render shell).

### 5.4 First administrator

Set `INVOICE_BOOTSTRAP_ADMIN_EMAIL` and `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` (12–72 bytes) for the first start only.
`AdminBootstrap` creates the ADMIN when no admin exists and never logs the password. Remove both variables after the
first successful login, then redeploy.

### 5.5 Backup and restore

What Render provides (render.com/docs/postgresql-backups and /docs/free, checked 2026-10-27):

| Plan | Point-in-time recovery (PITR) | Logical exports |
|---|---|---|
| Free | **none** — and Free databases expire 30 days after creation (14-day grace, then deleted), 1 GB | none |
| Paid, Hobby workspace | past **3 days** | on demand ("Create export"), kept 7 days, `.dir.tar.gz` |
| Paid, Pro workspace or higher | past **7 days** | same |

A PITR restore always creates a **new** database instance; the original is untouched until you switch the service's
`INVOICE_DB_URL` to it. A restore cannot target the last 10 minutes. → Real invoices need a **paid** database.

Decisions for the owner (write them down before go-live): recovery-point objective (how many hours of invoices may
be lost — PITR window and export frequency), recovery-time objective (how long the service may be down), retention
beyond Render's 3/7 days (e.g. a weekly export downloaded to encrypted storage you control), and who may access
backups (Render workspace members with database access can create and download exports — keep that list short).

Procedure:

- **Before every schema change and before go-live:** "Create export" (or `pg_dump -Fc` over the external URL), record
  time and size.
- **Restore rehearsal (once before go-live, then quarterly):** PITR into a *new* database → connect read-only → compare
  row counts of `invoices`, `invoice_items`, `invoice_documents`, `invoice_versions`, `app_users` with the source at the
  same time; run `docs/sql/invoice-versioning.sql` Part B (B4, B5, B7 expect no rows). Optionally point a *staging*
  service at it and log in. Record how long it took. Delete the rehearsal database afterwards.
- **Real recovery:** stop uploads (suspend the service or set it to maintenance), PITR to a time just before the
  incident, validate as above, switch `INVOICE_DB_URL`/`INVOICE_APPROVED_DATABASE` to the new instance, deploy, keep
  the old instance until the switch is confirmed.
- Never restore an export into a database that has data (the documented restore commands drop and recreate objects).
- Credentials: exports contain every invoice; download only to an encrypted disk, never to a shared folder, never to
  Git. Connection strings with passwords go in a password file or environment variable, never on a command line.
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
state the ownership of a freshly mounted disk (re-checked 2026-10-27). The `render` profile therefore sets
`invoice.storage.verify-on-startup=true`: at startup, before the web server accepts traffic, `StorageStartupCheck`
creates `INVOICE_STORAGE_DIR` if needed, writes, fsyncs, reads back and deletes a probe file. If the mount is not
writable the start fails with `invoice.storage.dir /var/data/invoice-documents is not writable by this process`, the
health check never passes and Render keeps the previous version — no upload can fail half-way. Fixing it is a
deliberate Dockerfile change (user/UID matching the mount) after checking Render's current guidance; do not run the
container as root to work around it.

The probe proves *writable*, not *persistent*: the same folder exists (empty, ephemeral) in the image when no disk is
attached. Persistence is proven only by the redeploy test in section 9.

**Write path:** each PDF is written to `.<uuid>.pdf.tmp`, forced to the device, renamed atomically to `<uuid>.pdf` and
read back against its SHA-256 before the database row is committed. Leftover `.*.tmp` files only come from a crash
mid-write; they are never served and may be deleted while the service is stopped.

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
configuration or user data (`HealthController`, test `ApiOnlySurfaceWebTest`). Render (docs/health-checks, checked
2026-10-27): 2xx/3xx within 5 s is healthy; a new deploy receives traffic only after it passes, and if it does not pass
within 15 minutes Render cancels the deploy and keeps the existing instance. A running instance is taken out of
traffic after 15 s of failures and restarted after 60 s. With a disk attached the old instance is stopped *before* the
new one starts (no zero-downtime deploys). The docs do not say how long the service is down when the new start then
fails; assume an outage until a rollback (section 12, step 14) or a fixed deploy succeeds — deploy at
quiet times.
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
| `... is set. It would redirect the database connection away from the approved spring.datasource.url` | a `SPRING_DATASOURCE_HIKARI_*`, `SPRING_DATASOURCE_JNDINAME` or `SPRING_DATASOURCE_TYPE` variable is set; remove it (`DataSourceRedirectGuardTest`) |
| `Could not resolve placeholder 'INVOICE_DB_URL'` / `'INVOICE_STORAGE_DIR'` | variable missing in the render profile |
| `INVOICE_JWT_SECRET is not set` / `too short` / `not valid Base64` | signing key missing or invalid (the value is never printed) |
| `Schema-validation: missing table` | gate G2 not done: the database has no (complete) schema |
| `Connection refused` / `password authentication failed` | network, hostname, SSL mode or credentials |
| `invoice.storage.dir must be an absolute path` | relative `INVOICE_STORAGE_DIR` |
| `invoice.storage.dir ... is not writable by this process` | disk not mounted where `INVOICE_STORAGE_DIR` points, mounted read-only, or owned by another user (section 6.1) |
| `bootstrap refused: ...` (when running the SQL script, not the app) | the target database is not empty / is a local database — nothing was changed |

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

## 12. Owner runbook — first deployment, in order

Every step is manual. **Staging** = synthetic data only (`@example.test` users, generated PDFs). **Production** =
real customer invoices; it needs every gate in section 0 closed. Stop at the first step that fails.

1. **Gates.** G2 rehearsal (5.3) passed locally; `./mvnw -o clean package` green on the commit you deploy; the
   commit is on `main`. Production additionally: G1, G3 decisions written down, G4 key rotation done.
2. **Database.** Render → New → Postgres, same region as the service, a **paid** instance type (Free: no backups,
   expires after 30 days — acceptable only for a throw-away staging test). Note the RPO/RTO decision (5.5).
3. **Schema.** From the Info page take hostname, port, database, user (password stays in your password manager).
   Apply `docs/sql/invoice-process-bootstrap-schema.sql` once with `psql -v ON_ERROR_STOP=1` over the external URL
   (`sslmode=require`); expect the 12/15/4/13 check result. Never let the application create tables.
4. **Service.** New → Web Service → GitHub `qasimmirza2110/Invoice-Process` → branch `main`.
5. **Runtime.** Docker; Dockerfile at the root; build context = repository (`.dockerignore` keeps only `pom.xml` and
   `src/`); Docker Command empty. Instance type: **paid** (disks are not available on Free); 1 instance.
6. **Auto-Deploy: Off.**
7. **Environment** (section 4; names only here, values typed into the dashboard):
   `INVOICE_DB_URL` = `jdbc:postgresql://<internal-host>:5432/<database>` (no credentials in it; Render's own
   `postgresql://user:password@host/db` URL is not read), `INVOICE_DB_USERNAME`, `INVOICE_DB_PASSWORD`,
   `INVOICE_LIVE_START_APPROVED=true`, `INVOICE_APPROVED_DATABASE=<internal-host>:5432/<database>` (same spelling as
   the URL), `INVOICE_JWT_SECRET` (new, Base64 of ≥ 32 random bytes, e.g. `openssl rand -base64 48` on your own
   machine), `INVOICE_STORAGE_DIR=/var/data/invoice-documents`, `INVOICE_BOOTSTRAP_ADMIN_EMAIL` /
   `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` (12–72 bytes, first start only). Leave **unset**: `GEMINI_API_KEY`, all
   `GEMINI_LIMIT_*`, `INVOICE_WORKER_ENABLED`, `INVOICE_LEGACY_EXTRACT_ENABLED`, `INVOICE_GOOGLE_*`,
   `INVOICE_ONEDRIVE_*`, any `SPRING_*`/`spring.jpa.*` override. `SPRING_PROFILES_ACTIVE=render` comes from the image.
8. **Disk.** Add a persistent disk, mount path `/var/data`, smallest size that fits (it can grow, never shrink).
9. **Health check path:** `/api/health`.
10. **First deploy.** Manual Deploy → Deploy latest commit. Watch the log for, in order: Maven `BUILD SUCCESS` in the
    image build; `Invoice PDF storage folder verified`; `Started InvoiceApplication`; `Tomcat started on port(s): 10000`.
    Failure signals are listed in section 8 (`SchemaSafetyGuard: REFUSING`, `Could not resolve placeholder`,
    `INVOICE_JWT_SECRET ...`, `Schema-validation: missing ...`, `is not writable by this process`,
    `password authentication failed`).
11. **Smoke tests** (section 9, synthetic only): health 200; `/` 401 JSON; XSRF cookie `Secure`; login sets
    `INVOICE_PROCESS_AUTH` `HttpOnly; Secure; SameSite=Strict`; a POST without `X-XSRF-TOKEN` → 403; a vendor cannot
    call `/api/admin/**` (403) and cannot read another vendor's invoice (404); unexpected errors show only an
    `errorId`; the login-limiter check (9.4) passes.
12. **PDF persistence:** upload one synthetic PDF as a test vendor, download it as admin and compare its SHA-256 with
    the local file; Manual Deploy again (and once Restart service); download again → same SHA-256. Then remove the
    bootstrap admin variables and redeploy.
13. **Backup rehearsal** (5.5): wait > 10 minutes, PITR into a new database, compare counts, delete it. Record times.
14. **Rollback / disable if anything fails:** Dashboard → the service → Rollback to the previous successful deploy
    (this also switches Auto-Deploy off), or **Suspend** the service to stop all traffic. Rollbacks never touch the
    disk or the database; a schema problem is fixed only by a reviewed script on a scratch copy first, never by
    switching the app to `ddl-auto=update`.
15. **Must stay unconfigured until every gate is closed:** Gemini key and limits (worker off), cloud pickers,
    customer accounts and real invoices, a custom domain announced to users, any second instance, any separate
    frontend origin (G5 — same origin only).

Production go-live = steps 1–15 green on staging, plus: paid database with backups, disk snapshot + off-platform
PDF copy procedure tested (6.2), new Gemini key (old one revoked in Google AI Studio) only when extraction is switched
on, and the RPO/RTO decision recorded.

## 13. Part A — temporary free demo (`render-free-demo`), $0, synthetic data only

For a frontend developer to integrate against the real API. **Not production**, no customer data, no SLA.
Render facts (checked 2026-10-27: [free](https://render.com/docs/free), [disks](https://render.com/docs/disks),
[postgresql-backups](https://render.com/docs/postgresql-backups), [health-checks](https://render.com/docs/health-checks),
[your-first-deploy](https://render.com/docs/your-first-deploy)):

- Free web service: spins down after 15 minutes without inbound traffic, about one minute to start again, may be
  restarted at any time; 750 free instance hours per workspace per month (then suspended until the next month).
- Ephemeral filesystem, **no persistent disks** on Free: files are lost on every redeploy, restart and spin-down.
- Free Postgres: 1 GB, **expires 30 days after creation**, 14-day grace period, then deleted; **no backups** of any kind.
- Render: "Do not use them for production applications."

### 13.1 What the profile enforces (`RenderFreeDemoGuard`, before any connection)

The start is refused unless `SPRING_PROFILES_ACTIVE` is exactly `render-free-demo` and the **effective**
configuration (environment variables override the profile file) has: no `GEMINI_API_KEY`; `INVOICE_WORKER_ENABLED`
and `INVOICE_LEGACY_EXTRACT_ENABLED` false; no `INVOICE_GOOGLE_*` / `INVOICE_ONEDRIVE_*` picker ids; storage exactly
under `/tmp/invoice-demo-documents-EPHEMERAL` (never `/var/data`, no `..`); Secure cookies; a real
`INVOICE_JWT_SECRET` (no random-key fallback); a bootstrap admin ending in `@example.test`. Every account created in
the demo must end in `@example.test` (`AppUserService`, 400 otherwise). Every response carries
`X-Invoice-Demo: ephemeral-synthetic-only`. `SchemaSafetyGuard` applies in full (database approval, no connection
redirects, `validate`).
**Uploads:** only the six synthetic fixtures in `docs/react-handoff/demo-fixtures/` are accepted (exact SHA-256,
`invoice.free-demo.allowed-upload-sha256`; the guard refuses an empty or malformed list). Every other file is rejected
with 422 `NOT_A_DEMO_FIXTURE` **before any other check** - never stored, and recorded under the neutral name
`file-not-accepted-by-demo.pdf`, because a real file's name can itself be customer data (`FreeDemoUploadPolicy`).
Synthetic e-mail addresses alone would prove nothing about a file's content.
Any other profile that points its storage at the demo folder is refused. Tests: `RenderFreeDemoGuardTest`,
`DefaultProfileBootLoadingTest.theFreeDemoProfileIsSeparateGuardedAndRefusesOverrides`, `FreeDemoSurfaceWebTest`,
`AppUserServiceTest`, `FreeDemoUploadIntakeTest`, `FreeDemoFixtureTest`.

**Consequence:** with no extraction, uploaded invoices stay `QUEUED`; review, submit and approval are **unavailable**
in the demo (the React handoff is contract-only for them). A deterministic synthetic extraction adapter would need
changes to the production worker and Gemini accounting and is a separate decision.

### 13.2 Manual steps, in order

1. **Code on `main`:** the demo profile, guard and bootstrap script are merged and `./mvnw -o clean package` is green
   on that commit. Do not deploy a branch.
2. **Schema rehearsal:** done 2026-10-27 on a disposable scratch database (see the script header); repeat the section 5.3
   rehearsal only if the script changes (owner approval naming `invoice_process_bootstrap_scratch_db`).
3. **Free Postgres:** Render → New → Postgres → instance type **Free**, same region you will use for the service.
   Write down: creation date, **expiry date = creation + 30 days**, "synthetic data only".
4. **Schema:** apply the bootstrap script once over the database's external URL with `sslmode=require`
   (`psql -v ON_ERROR_STOP=1 -X -f docs/sql/invoice-process-bootstrap-schema.sql`, connection via `PG*` variables,
   never a password on the command line). Expect 12 tables / 15 `f` / 4 `u` / 13 indexes.
5. **Web service:** Render → New → Web Service → `qasimmirza2110/Invoice-Process` → branch `main` → Language
   **Docker** (Dockerfile at the root, Docker Command empty) → Compute **Free**.
6. **Auto-Deploy:** right after creation, Settings → Build & Deploy → **Auto-Deploy: Off** (the creation form may not
   show it); deploy only with Manual Deploy.
7. **Environment** (names below; values typed into the dashboard, never committed):

   | Name | Value |
   |---|---|
   | `SPRING_PROFILES_ACTIVE` | `render-free-demo` (overrides the image's `render`) |
   | `INVOICE_DB_URL` | `jdbc:postgresql://<internal-host>:5432/<database>` (no credentials in it) |
   | `INVOICE_DB_USERNAME`, `INVOICE_DB_PASSWORD` | from the Free database's Info page |
   | `INVOICE_LIVE_START_APPROVED` | `true` |
   | `INVOICE_APPROVED_DATABASE` | `<internal-host>:5432/<database>` (same spelling as the URL) |
   | `INVOICE_JWT_SECRET` | a **new, throw-away** Base64 value of ≥ 32 random bytes, only for this demo |
   | `INVOICE_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | the planned admin `sanjay.admin@example.test` (section 13.3) and a new password; first start only |

   Do **not** set: `GEMINI_API_KEY` or any `GEMINI_*`, `INVOICE_WORKER_*`, `INVOICE_LEGACY_EXTRACT_ENABLED`,
   `INVOICE_GOOGLE_*`, `INVOICE_ONEDRIVE_*`, `INVOICE_STORAGE_DIR`, `INVOICE_COOKIE_SECURE`,
   `INVOICE_FREE_DEMO_ALLOWED_UPLOAD_SHA256`, any `SPRING_JPA_*` or `SPRING_DATASOURCE_*` (the guards refuse
   Gemini, worker, picker, storage, schema-changing and connection-redirecting values).
8. **Health check path:** `/api/health`.
9. **Deploy:** Manual Deploy → Deploy latest commit. In the log expect `The following 1 profile is active:
   "render-free-demo"`, `FREE DEMO MODE`, `Invoice PDF storage folder verified`, `Started InvoiceApplication`.
   A refusal starts with `RenderFreeDemoGuard: REFUSING` or `SchemaSafetyGuard: REFUSING` and names the setting.
10. **Synthetic smoke test** (browser or Postman with the demo environment, synthetic data only): health 200 with the
    `X-Invoice-Demo` header; `GET /api/auth/me` 401 with an `XSRF-TOKEN` cookie; login as the bootstrap admin with
    `X-XSRF-TOKEN`; create the section 13.3 accounts (a non-`@example.test` address must answer 400); create the
    categories; as a vendor create a batch and upload `synthetic-demo-invoice-01.pdf` (202, `QUEUED`), any other PDF
    (422 `NOT_A_DEMO_FIXTURE`) and the same fixture again (422 `DUPLICATE_FILE`); download the accepted file as admin
    and compare its SHA-256. Then remove the two bootstrap variables and redeploy.
11. **Handoff package:** `.\scripts\prepare-react-handoff.ps1 -ApiBaseUrl "https://<exact-url-from-dashboard>"` →
    `target/React-Developer-Handoff.zip` (refuses to build unless the health check passes **and** the demo header is
    present). Check it once with `-SelfTest` beforehand if you like (that ZIP is marked NOT VERIFIED; never send it).
12. **Send** the ZIP and the base URL to the React developer. Share the synthetic demo credentials **separately**
    over a private channel — never in the ZIP, a ticket, a repository or a chat with others.
13. **Tell them:** the URL may sleep (≈ 1 minute cold start), PDFs disappear on restart/redeploy/spin-down, the
    database (and every demo account) disappears on the expiry date, review/approval data does not exist in the demo.
14. **Never** use a real customer's name, e-mail, invoice, document or credential in the demo — not even "just to test".

**Rollback / stop:** Dashboard → Rollback (also turns Auto-Deploy off) or Suspend the service. When the demo ends,
delete the Free database and the service; nothing in them may be needed later.

### 13.3 Planned synthetic demo accounts (no passwords here, ever)

Recreations of the team's demo personas for the free demo only. They are **not** copies of any account in another
database: no real e-mail address or password hash is used, and the primary database is never read for them.

| Person | Demo login | Display name | Role | Reviewer categories |
|---|---|---|---|---|
| Sanjay | `sanjay.admin@example.test` | Sanjay | ADMIN (bootstrap admin) | – |
| Omkar | `omkar.vendor@example.test` | Omkar | VENDOR | – |
| Sakshi | `sakshi.vendor@example.test` | Sakshi | VENDOR | – |
| Akash | `akash.reviewer@example.test` | Akash | REVIEWER | `IT` |
| Sahil | `sahil.reviewer@example.test` | Sahil | REVIEWER | `OFFICE` |

The two categories (`IT` "IT equipment", `OFFICE` "Office supplies") are a suggestion so that category-based
assignment can be shown; adjust before deploying if wanted.

After the first deploy (as Sanjay, through the API, every unsafe call with `X-XSRF-TOKEN`):
1. `PUT /api/admin/users/{sanjayId}/display-name` `{"displayName":"Sanjay"}` (the bootstrap admin starts as "Administrator").
2. `POST /api/admin/categories` for `IT` and `OFFICE`.
3. `POST /api/admin/users` for Omkar, Sakshi, Akash and Sahil (`role` as above, a new 12–72-byte password each).
4. `PUT /api/admin/users/{id}/categories` `{"categories":["IT"]}` for Akash, `{"categories":["OFFICE"]}` for Sahil.
5. Remove `INVOICE_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` and redeploy.

Each password is chosen by the owner, never reused from another system, and given only to that person over a private
channel - never in the handoff ZIP, a ticket, a chat with others or the repository. When the Free database expires
(30 days after creation) the accounts disappear with it; repeat these steps on the next database.
