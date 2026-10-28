# Actify Invoice Process — backend

An **API-only** Spring Boot backend that extracts structured data from invoice PDFs with Google Gemini, validates it in
Java, stores it in PostgreSQL, and takes each invoice through a multi-user review and approval workflow. It serves no
web pages: the user interface is a separate (React) frontend that calls this REST API.

```
Vendor uploads PDFs → PostgreSQL queue → worker → Gemini (one call per PDF) → JSON → Java validation
  → NEEDS_REVIEW → reviewer corrects and submits a version → PENDING_APPROVAL → admin approves / rejects
```

**Status:** tested with mocks and an isolated PostgreSQL test database. Container deployment to Render is *prepared*
(`Dockerfile`, `render` profile, [`docs/RENDER_DEPLOYMENT.md`](docs/RENDER_DEPLOYMENT.md)) but has **not** been
performed, and it is **not cleared for real customer invoices** until the release gates in that guide are met
(a private PDF bucket, the schema applied, backups). The free target is the existing Render service plus Neon
PostgreSQL and a private Neon Object Storage bucket.

| Document | For |
|---|---|
| [`docs/REACT_BACKEND_HANDOFF.md`](docs/REACT_BACKEND_HANDOFF.md) | frontend developers: every route, JSON shape, error, Bearer-token rule and React flow |
| [`docs/openapi.yaml`](docs/openapi.yaml) | the same contract, machine-readable (OpenAPI 3.0) |
| [`docs/RENDER_DEPLOYMENT.md`](docs/RENDER_DEPLOYMENT.md) | deploying the container to Render, environment variables, database, PDF storage, operations |
| [`docs/WORKFLOW_AND_OPERATIONS.md`](docs/WORKFLOW_AND_OPERATIONS.md) | workflow rules, approval gates, local demo |
| [`docs/BACKEND_REVERSE_ENGINEERING_RUNBOOK.md`](docs/BACKEND_REVERSE_ENGINEERING_RUNBOOK.md) | how the backend works inside, debugging |
| [`docs/PROVIDER_SETUP_GOOGLE_DRIVE_ONEDRIVE.md`](docs/PROVIDER_SETUP_GOOGLE_DRIVE_ONEDRIVE.md) | owner setup for the cloud pickers (frontend work) |

## 1. Features

- **Login and roles** – `POST /api/auth/login` returns a JWT access token; every other call sends
  `Authorization: Bearer <token>` (no cookies, no CSRF token, no server session). Roles `ADMIN`, `VENDOR`, `REVIEWER`;
  no sign-up — an ADMIN creates accounts. Access is checked on the server for every object: another user's invoice
  answers `404`.
- **Real invoice PDFs** – any valid PDF ≤ 10 MB is accepted (extension, content type, signature and a real PDF parse;
  damaged or password-protected files are refused). Originals are stored privately under random names and never
  changed.
- **Batch upload** – one PDF per request, up to 100 files per batch, a PostgreSQL job queue
  (`FOR UPDATE SKIP LOCKED`) and a background worker that is **off by default**.
- **Gemini pacing** – one shared pacer for requests per minute, input tokens per minute and requests per day, per
  model; every Gemini call is recorded; at most 3 attempts per job (1 initial + 2 retries); the queue pauses when
  the daily quota is used up.
- **Review** – categories, reviewer ↔ category mapping, automatic review reasons (total mismatch, invalid date,
  duplicate file, …), reviewer corrections.
- **Versioning** – every correction is a new, complete, read-only version (V1 = the extraction). The original PDF is
  stored privately and never changed; its SHA-256 is checked on every download.
- **Approval** – approve / reject / send back / reopen / requeue, pinned to the exact submitted version. Every status
  change writes a workflow event.
- **Dashboards** – vendor, reviewer and admin metrics with documented definitions.
- **Health** – `GET /api/health` (public, answers only `{"status":"UP"}`) for the hosting platform.

## 2. Technology

Java 8 source level (compiled with `--release 8`, built with JDK 17), Spring Boot 2.7.18, Spring Security 5.7, Spring
Data JPA / Hibernate 5.6, PostgreSQL, Jackson, RestTemplate, Gemini API (`gemini-3.5-flash-lite` by default). Maven
wrapper included. Container image: Maven 3.9.16 / Eclipse Temurin 17 JRE (see `Dockerfile`).

Spring Boot 2.7 is past its open-source support window. An upgrade (Boot 3.x needs Java 17 and the `javax` → `jakarta`
migration) is a separate, planned project — see [`docs/RENDER_DEPLOYMENT.md`](docs/RENDER_DEPLOYMENT.md#14-planned-improvements).

## 3. Architecture

```
React frontend (separate project; same origin or allowed CORS origin)  /  Postman
  → Spring Security: JwtBearerAuthenticationFilter + role rules (default deny)
  → controller/  HTTP only
  → intake/      BatchIntakeService      file checks, private PDF storage, invoice + job in one transaction
  → queue/       ExtractionWorker        claims jobs with FOR UPDATE SKIP LOCKED
  → quota/       GeminiQuotaGuard        pacing per model, call log, daily-quota pause
  → llm/         GeminiInvoiceExtractor  one generateContent call, structured output
  → validation/  InvoiceExtractionValidator  business rules with BigDecimal
  → review/, service/, versioning/, workflow/   assignment, corrections, versions, approval, status machine
  → PostgreSQL  +  original PDFs: private folder (local) or private S3 bucket (render)
```

`config/SchemaSafetyGuard` runs before any database connection is created (section 6).

## 4. Configuration

All settings come from environment variables (an Eclipse run configuration, the terminal session, or the Render
service environment). Nothing secret is stored in the repository. [`.env.example`](.env.example) lists names only;
Spring Boot does not read `.env` files.

| Variable | Needed | Notes |
|---|---|---|
| `INVOICE_JWT_SECRET` | always (optional in the local demo) | Base64 of at least 32 random bytes. Outside the local demo the app refuses to start without it. |
| `INVOICE_DB_URL`, `INVOICE_DB_USERNAME`, `INVOICE_DB_PASSWORD` | yes | PostgreSQL connection; locally the password may come from `pgpass.conf`. |
| `INVOICE_LIVE_START_APPROVED`, `INVOICE_APPROVED_DATABASE` | any non-demo start | See section 6. |
| `INVOICE_BOOTSTRAP_ADMIN_EMAIL`, `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` | first start only | Creates the first ADMIN when none exists. Remove afterwards. |
| `INVOICE_STORAGE_DIR` | locally optional | Private folder for the original PDFs (local runs only); absolute path. |
| `INVOICE_S3_ENDPOINT`, `INVOICE_S3_REGION`, `INVOICE_S3_BUCKET`, `INVOICE_S3_ACCESS_KEY_ID`, `INVOICE_S3_SECRET_ACCESS_KEY` | **required in `render`** | Private S3-compatible bucket (Neon Object Storage) for the original PDFs. The `render` profile refuses to start without a working, private bucket - a Render container's disk is wiped on every restart. |
| `INVOICE_DB_POOL_SIZE` | no (`render` only) | Max database connections, default 3. |
| `GEMINI_API_KEY` | for extraction | Sent only in the `x-goog-api-key` header; never logged or returned. |
| `GEMINI_MODEL` | no | Default `gemini-3.5-flash-lite`. |
| `INVOICE_WORKER_ENABLED` | to process the queue | `true` starts the worker… |
| `GEMINI_LIMIT_RPM`, `GEMINI_LIMIT_TPM`, `GEMINI_LIMIT_RPD`, `GEMINI_INITIAL_TOKEN_ESTIMATE` | for the worker | …but only when these limits (from Google AI Studio, for your project and model) are set. No defaults on purpose. |
| `INVOICE_GEMINI_PAID_TIER_CONFIRMED` | for extraction outside the local demo | `true` only after checking that the key's Google Cloud project has active billing. Until then nothing is sent to Gemini. |
| `INVOICE_CORS_ALLOWED_ORIGINS` | only for a separately hosted frontend | Exact origins, comma-separated; empty = no CORS. |
| `INVOICE_TEST_DB_PASSWORD` | `-Ppgtest` only | Test database password, or `pgpass.conf`. |

Gemini and real invoices: on unpaid (free-tier) Gemini API services Google may use the content to improve its products
and human reviewers may read it (https://ai.google.dev/gemini-api/terms, checked 2026-10-28). Real invoices therefore
go only to a project with active Cloud Billing, confirmed with `INVOICE_GEMINI_PAID_TIER_CONFIRMED=true`. The local
demo (synthetic files only) is exempt.

## 5. Build and test

```powershell
./mvnw -o test             # no database, Gemini always mocked
./mvnw -o test -Ppgtest    # + PostgreSQL integration tests on the separate TEST database only
./mvnw -o -DskipTests package   # builds target/actify-invoice-process-0.0.1-SNAPSHOT.jar (API only, no static files)
```

`-Ppgtest` uses only `Actify-Internship-Invoice-Process_test_db`
(`src/test/resources/application-pgtest.properties`); `PgTestDatabaseGuard` checks the profile, the URL and
`SELECT current_database()` before Hibernate runs, and stops the run otherwise.

Last recorded run (2026-10-27): `./mvnw -o clean test` — 274 tests, 0 failures. The `-Ppgtest` suite was not re-run for this
change (see the release notes in the final report).

## 6. Database schema and the startup guard

The application never changes a database schema: `spring.jpa.hibernate.ddl-auto=validate`. Schema changes are
reviewed SQL scripts in [`docs/sql/`](docs/sql):

- [`invoice-process-workflow-migration.sql`](docs/sql/invoice-process-workflow-migration.sql) – one-time, additive
  migration for the workflow tables (executed once on the local primary database on 2026-10-23 after a rehearsal;
  see the header of the script). Any other database needs its own backup, rehearsal and approval.
- [`invoice-versioning.sql`](docs/sql/invoice-versioning.sql) – schema reference and read-only checks for versions.
- [`inspection-queries.sql`](docs/sql/inspection-queries.sql) – read-only diagnostic queries.

- [`invoice-process-bootstrap-schema.sql`](docs/sql/invoice-process-bootstrap-schema.sql) – the complete schema for an
  **empty** database (e.g. the new Neon database), applied once by hand; it refuses a database that already has tables.
  Procedure: [`docs/RENDER_DEPLOYMENT.md`](docs/RENDER_DEPLOYMENT.md#53-database-and-schema-gate-g2).

`SchemaSafetyGuard` (a `BeanFactoryPostProcessor`, so it runs before the DataSource exists) refuses every start
except the isolated test run and the local demo unless **both** are set:

- `INVOICE_LIVE_START_APPROVED=true`, and
- `INVOICE_APPROVED_DATABASE=host:port/database`, which must equal the endpoint in the JDBC URL.

Even then it refuses any setting that would let Hibernate or Spring change the schema, including bare `-D` system
properties such as `hibernate.hbm2ddl.auto`.

**Local demo.** Run `InvoiceApplication` without a profile (Eclipse: Run As › Java Application): the `localdemo`
profile is the default. It can only reach `localhost:5432/invoice_process_local_demo_db`, refuses the approval
variables, any Gemini key and the worker (unless the documented controlled opt-in is used), and listens on
`127.0.0.1:8088` only. Steps: [`docs/WORKFLOW_AND_OPERATIONS.md`](docs/WORKFLOW_AND_OPERATIONS.md#9-local-demo-this-computer-only).
Use Postman (section 8) or a React dev server with a `/api` proxy to `127.0.0.1:8088` to work with it.

## 7. API

47 routes under `/api/**`; the complete contract is [`docs/REACT_BACKEND_HANDOFF.md`](docs/REACT_BACKEND_HANDOFF.md).
Every URL that is not an API route — including `/` — answers `401`/`403`. Errors share one shape:

```json
{ "timestamp": "...", "status": 409, "error": "Version conflict",
  "message": "Version 2 is not the submitted, current version ...", "path": "/api/invoices/41/approve" }
```

## 8. Postman

`postman/Invoice-Process.postman_collection.json` with `postman/Invoice-Process-Local.postman_environment.json`.
Run **"00 - Login"** first after setting `adminEmail` / `adminPassword` in the environment (a test admin only). Login
stores the returned `accessToken` in a collection variable for that run, and a collection pre-request script sends it
as `Authorization: Bearer` on every request (the exported file never contains a token).
Uploads use the batch API (folders 02, 04, 08 — they **write** a batch, stored PDFs and rejection records; run them
manually against a demo or test database). No request calls Gemini: extraction happens only if the server's worker is
enabled. Folder 10 corrects an existing **legacy** invoice (set `legacyInvoiceId`); 10b writes a version. Folder 02
uploads `docs/react-handoff/sample-invoices/synthetic-sample-invoice-01.pdf`; the other fixture files are **not** in
this repository — use your own synthetic PDFs. The collection was updated from the source on 2026-10-28 and has
**not** been run against a live application yet.

## 9. Extraction and validation

- One PDF = one invoice, however many pages. The whole PDF goes to Gemini in one request (inline, max 10 MB); scanned
  pages are read by Gemini's PDF vision, so accuracy depends on scan quality.
- Structured output guarantees the JSON **shape**, not that the values are true. Before saving: `invoiceNumber` and
  `totalAmount` required; `invoiceDate` `YYYY-MM-DD`; 3-letter currency; amounts ≥ 0, quantity > 0;
  `sum(lineTotal) ≈ subtotal` and `subtotal − discount + shipping + tax ≈ total` (tolerance 0.01, `BigDecimal`).
  All errors are reported together. Every extraction waits for a human reviewer.

Model comparison plan: [`docs/GEMINI_MODEL_COMPARISON.md`](docs/GEMINI_MODEL_COMPARISON.md) (not run yet).

## 10. Limitations

- Pacing and the login limiter live in this single instance's memory/database view; other users of the same Google
  project are invisible to it, and a restart resets the login limiter.
- Workflow events and versions are append-only in the application, not enforced by the database.
- PDFs are sent inline, so uploads are capped at 10 MB. Upload checks are size, extension, content type and the
  `%PDF-` signature — not a full PDF parse or malware scan.
- Google Drive / OneDrive: the backend publishes the picker configuration; the picker UI must be rebuilt in the new
  frontend and has never been live-tested.
- The frontend must be served from the same origin as the API (no CORS) — see the handoff, section 1.3.

## 11. License

No license has been chosen yet, so all rights are reserved by default.
