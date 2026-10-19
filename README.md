# Invoice Process

A Spring Boot application that extracts structured data from invoice PDFs with Google Gemini, validates it in Java,
stores it in PostgreSQL, and takes each invoice through a multi-user review and approval workflow.

```
Vendor uploads PDFs → PostgreSQL queue → worker → Gemini (one call per PDF) → JSON → Java validation
  → NEEDS_REVIEW → reviewer corrects and submits a version → PENDING_APPROVAL → admin approves / rejects
```

**Status:** a local demo application. It has been tested with mocks and an isolated test database only; it has not
been deployed. It listens on `127.0.0.1:8088` only and has no TLS. See [Limitations](#11-limitations).

## 1. Features

- **Login and roles** – JWT in an HttpOnly cookie, CSRF protection, roles `ADMIN`, `VENDOR`, `REVIEWER`.
  Access is checked on the server for every object: another user's invoice answers `404`.
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
- **Dashboards** – vendor, reviewer and admin pages with documented metric definitions.

Details: [`docs/WORKFLOW_AND_OPERATIONS.md`](docs/WORKFLOW_AND_OPERATIONS.md).

## 2. Technology

Java 8 (compiled with `--release 8`, built with JDK 17), Spring Boot 2.7.18, Spring Security 5.7, Spring Data JPA /
Hibernate 5.6, PostgreSQL 17, Jackson, RestTemplate, Gemini API (`gemini-3.5-flash-lite` by default), plain
HTML/CSS/JavaScript for the pages. Maven wrapper included.

## 3. Architecture

```
Browser pages (login, vendor, reviewer, admin)  /  Postman
  → Spring Security: JwtCookieAuthenticationFilter + CSRF + role rules
  → controllers (HTTP only)
  → intake/     BatchIntakeService      file checks, private PDF storage, invoice + job in one transaction
  → queue/      ExtractionWorker        claims jobs with FOR UPDATE SKIP LOCKED
  → quota/      GeminiQuotaGuard        pacing per model, call log, daily-quota pause
  → llm/        GeminiInvoiceExtractor  one generateContent call, structured output
  → validation/ InvoiceExtractionValidator  business rules with BigDecimal
  → review/     ReviewService, ApprovalService   corrections, versions, approval
  → PostgreSQL
```

`config/SchemaSafetyGuard` runs before any database connection is created (see section 6).

## 4. Configuration

All settings come from environment variables (for example an Eclipse run configuration or the current terminal
session). Nothing secret is stored in the repository. See [`.env.example`](.env.example); `.env` is git-ignored.

| Variable | Needed | Notes |
|---|---|---|
| `INVOICE_JWT_SECRET` | always | Base64 of at least 32 random bytes. The app refuses to start without it. |
| `INVOICE_DB_URL`, `INVOICE_DB_USERNAME`, `INVOICE_DB_PASSWORD` | yes | PostgreSQL connection; the password may come from `pgpass.conf` instead. |
| `INVOICE_LIVE_START_APPROVED`, `INVOICE_APPROVED_DATABASE` | to start at all | See section 6. |
| `INVOICE_BOOTSTRAP_ADMIN_EMAIL`, `INVOICE_BOOTSTRAP_ADMIN_PASSWORD` | first start only | Creates the first ADMIN when none exists. Remove afterwards. |
| `INVOICE_STORAGE_DIR` | no | Private folder for the original PDFs; must be an absolute path. |
| `GEMINI_API_KEY` | for extraction | Sent only in the `x-goog-api-key` header; never logged or returned. |
| `GEMINI_MODEL` | no | Default `gemini-3.5-flash-lite`. |
| `INVOICE_WORKER_ENABLED` | to process the queue | `true` starts the worker… |
| `GEMINI_LIMIT_RPM`, `GEMINI_LIMIT_TPM`, `GEMINI_LIMIT_RPD`, `GEMINI_INITIAL_TOKEN_ESTIMATE` | for the worker | …but only when these limits (from Google AI Studio, for your project and model) are set. No defaults on purpose. |
| `INVOICE_COOKIE_SECURE` | behind TLS | Adds the `Secure` cookie flag. |
| `INVOICE_TEST_DB_PASSWORD` | `-Ppgtest` only | Test database password, or `pgpass.conf`. |

Free-tier Gemini: create the key in Google AI Studio without enabling billing. Free-tier content may be used by
Google to improve its products – use synthetic invoices only, never real customer documents.

## 5. Build and test

```powershell
./mvnw -o test                                    # no database, Gemini always mocked
./mvnw -o test -Ppgtest                           # + PostgreSQL integration tests on the separate TEST database
node src/test/frontend/invoice-review-ui.e2e.mjs  # headless Chrome/Edge against a mock API, no npm packages
```

`-Ppgtest` uses only `Actify-Internship-Task-8-Invoice-LLM-Extraction_test_db`
(`src/test/resources/application-pgtest.properties`); `PgTestDatabaseGuard` checks the profile, the URL and
`SELECT current_database()` before Hibernate runs, and stops the run otherwise.

Last recorded run (2026-10-19): offline 170 tests, `-Ppgtest` 213 tests (including a real-browser workflow against
the real backend), browser UI checks 29 – all passing.

## 6. Database schema and the startup guard

The application never changes a database schema: `spring.jpa.hibernate.ddl-auto=validate`. Schema changes are
reviewed SQL scripts in [`docs/sql/`](docs/sql):

- [`invoice-process-workflow-migration.sql`](docs/sql/invoice-process-workflow-migration.sql) – one-time, additive
  migration for the workflow tables. **Not executed** against any non-test database; run only after a restore-tested
  backup and a rehearsal on a scratch copy, with `psql -v ON_ERROR_STOP=1`.
- [`invoice-versioning.sql`](docs/sql/invoice-versioning.sql) – schema reference and read-only checks for versions.

`SchemaSafetyGuard` (a `BeanFactoryPostProcessor`, so it runs before the DataSource exists) refuses every start
except the isolated test run unless **both** are set:

- `INVOICE_LIVE_START_APPROVED=true`, and
- `INVOICE_APPROVED_DATABASE=host:port/database`, which must equal the endpoint in the JDBC URL.

Even then it refuses any setting that would let Hibernate or Spring change the schema, including bare `-D` system
properties such as `hibernate.hbm2ddl.auto`. The approval gates are described in
[`docs/WORKFLOW_AND_OPERATIONS.md`](docs/WORKFLOW_AND_OPERATIONS.md#7-approval-gates--before-any-start-against-a-non-test-database-each-needs-separate-approval).

## 7. Pages and API

Open `http://127.0.0.1:8088/login.html`. Pages: `vendor.html`, `reviewer.html`, `admin.html`; `index.html` is the
original single-file workspace (ADMIN only).

| Method | URL | Purpose |
|---|---|---|
| POST | `/api/auth/login`, `/api/auth/logout` | login (sets the cookie) / logout |
| POST | `/api/batches`, `/api/batches/{id}/files` | create a batch, add one PDF per request |
| GET | `/api/invoices/{id}` | current invoice with line items, `currentVersion`, `sourceDocument` |
| PUT | `/api/invoices/{id}` | save a correction as a new version |
| GET | `/api/invoices/{id}/versions`, `/versions/{n}`, `/versions/compare?from=1&to=2` | version history and differences |
| GET | `/api/documents/{documentId}` | the stored original PDF (read-only, SHA-256 checked) |
| POST | `/api/invoices/extract` | original single-file extraction (ADMIN only) |

Further routes (submit, approve/reject/send back/reopen/requeue, assignments, categories, users, dashboards) and the
role rules are in [`docs/WORKFLOW_AND_OPERATIONS.md`](docs/WORKFLOW_AND_OPERATIONS.md) and the controllers.
Errors share one shape:

```json
{ "timestamp": "...", "status": 422, "error": "Invoice validation failed",
  "message": "subtotal + taxAmount (11800.00) does not match totalAmount (13000.00)",
  "path": "/api/invoices/extract" }
```

Unauthenticated `401`, wrong role `403`, someone else's or unknown object `404`, stale version `409`, failed
validation `422`, Gemini failure or quota `502`, anything unexpected `500` (generic message, no stack trace).

## 8. Postman

`postman/Invoice-Process.postman_collection.json` with `postman/Invoice-Process-Local.postman_environment.json`.
Run **"00 - Login"** first after setting `adminEmail` / `adminPassword` in the environment (a test admin only).
Folders 02 and 08 make a live Gemini call each and folder 10b writes data – run them manually and deliberately.
The PDF fixture files the collection refers to are **not** included in this repository; use your own synthetic PDFs.
The collection has not yet been run against a running application.

## 9. Extraction and validation

- One PDF = one invoice, however many pages. The whole PDF goes to Gemini in one request (inline, max 10 MB); scanned
  pages are read by Gemini's PDF vision, so accuracy depends on scan quality.
- Structured output guarantees the JSON **shape**, not that the values are true. Before saving: `invoiceNumber` and
  `totalAmount` required; `invoiceDate` `YYYY-MM-DD`; 3-letter currency; amounts ≥ 0, quantity > 0;
  `sum(lineTotal) ≈ subtotal` and `subtotal − discount + shipping + tax ≈ total` (tolerance 0.01, `BigDecimal`).
  All errors are reported together.
- Token usage (`promptTokenCount`, `candidatesTokenCount`, `thoughtsTokenCount`, `totalTokenCount`, `modelVersion`)
  is read from the same Gemini response and stored as reported; `null` means "not reported", never 0.

Model comparison plan: [`docs/GEMINI_MODEL_COMPARISON.md`](docs/GEMINI_MODEL_COMPARISON.md) (not run yet).

## 10. Versions

| Version | `changeSource` | Content |
|---|---|---|
| V1 | `LLM_EXTRACTION` | the validated extraction as first stored |
| V2, V3, … | `MANUAL_EDIT` | each saved correction: complete invoice, reason, changed fields |
| V1 of an older invoice | `LEGACY_BASELINE` | invoices extracted before versioning get their pre-edit state saved as V1 on the first correction, labelled as *not* a verified extraction |

A correction is one transaction: `SELECT … FOR UPDATE` on the invoice, `baseVersion` check, validation, update of the
current copy, insert of the version. A concurrent save gets `409`. Versions have no update or delete path in the
application. `changedBy` is `user:<id>` of the editor.

## 11. Limitations

- Local demo: no TLS, loopback only. Spring Boot 2.7 is past open-source support.
- Pacing and the login limiter see only this application instance; other users of the same Google project are
  invisible to it.
- Workflow events and versions are append-only in the application, not enforced by the database.
- PDFs are sent inline, so uploads are capped at 10 MB. The validator assumes line totals before tax.
- Google Drive / OneDrive import is not built.
- Further limitations: [`docs/WORKFLOW_AND_OPERATIONS.md`](docs/WORKFLOW_AND_OPERATIONS.md#8-known-limitations).

## 12. License

No license has been chosen yet, so all rights are reserved by default.
