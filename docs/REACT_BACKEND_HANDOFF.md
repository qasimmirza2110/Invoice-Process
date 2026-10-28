# Actify Invoice Process — React ↔ backend handoff

Status: updated 2026-10-28 from the source (`controller/*`, `dto/*`, `security/*`, `exception/GlobalExceptionHandler`,
the services they call) and the tests named below, for the **Bearer-token** release (branch
`feature/bearer-jwt-real-uploads`). Every route and field here exists in that code. Examples use synthetic ids and values
only. If this file and the code disagree, **the code wins** — please report the difference.

> **Deployed or not?** The API URL stays **`https://invoice-process.onrender.com`** — this release replaced the code
> behind that same service. Observed on 2026-10-28 (anonymous requests only, no login): `GET /api/health` → 200
> `{"status":"UP"}` and `GET /api/auth/me` → 401 with `WWW-Authenticate: Bearer`, which only the Bearer build sends.
> So the Bearer build is **most likely live**; the exact deployed commit and the service's settings are **unverified**.
> Confirm with one login (section 1.4) before relying on it.
>
> | | Older cookie build (before this release) | This document (Bearer build) |
> |---|---|---|
> | Login answer | `Set-Cookie: INVOICE_PROCESS_AUTH`, no token in the body | JSON with `accessToken`, no cookie |
> | Every other call | cookie + `X-XSRF-TOKEN` header | `Authorization: Bearer <accessToken>` |
> | Uploads | only six fixture PDFs, `@example.test` accounts | any valid PDF ≤ 10 MB |
> | Stored PDFs | lost on every restart | kept in a private bucket (Neon Object Storage) |
>
> **CORS is not enabled on the live service** (2026-10-28: a preflight from `http://localhost:5173` got 401 without any
> `Access-Control-*` header). Use the dev proxy (section 1.2) until the owner allows your exact origin.

Machine-readable companion: [`openapi.yaml`](openapi.yaml) (OpenAPI 3.0, same routes and schemas).

---

## 0. What the backend is (and is not)

- **API-only Java backend** (Spring Boot 2.7, Java 8 source level, PostgreSQL). It serves **no HTML, CSS or JavaScript**.
  `GET /` and every other unlisted URL answer **401** (no token) or **403** (valid token). **That 401 on `/` is expected,
  not an outage** — the health check is `GET /api/health` → `200 {"status":"UP"}`. The old vanilla-JS pages were
  archived outside the repository; nothing in the backend depends on them.
- **It runs on free plans** (Render Free for the API, Neon Free for the database and the PDF bucket). Consequences for
  the UI: the first call after 15 idle minutes can take **about a minute** (show a patient loading state, do not retry
  in a loop); the database itself may also need a moment to wake; and if the monthly free quota runs out, calls that
  need the database fail with 500 until the next month. It is a **demo environment**: no uptime promise, and only
  synthetic or explicitly approved test PDFs — never a real client invoice.
- The React app is a **separate project**. It must not reimplement any business rule as the *only* check — the
  backend enforces all of them (section 8). React decides what to *show*; the backend decides what is *allowed*.
- Roles: `VENDOR` (uploads), `REVIEWER` (corrects and submits assigned invoices), `ADMIN` (users, categories,
  assignment, decisions, queue). One role per user, stored in the database.

| Item | Value |
|---|---|
| Base URL, local | `http://127.0.0.1:8088` (local demo, bound to loopback only) |
| Base URL, Render | `https://invoice-process.onrender.com` — the same URL before and after this release (see the table at the top for which contract it currently serves) |
| Format | JSON, except file upload (`multipart/form-data`) and the PDF stream (`application/pdf`) |
| Dates | `LocalDate` → `"2026-10-06"`; `LocalDateTime` → `"2026-10-16T11:00:00"` (no zone; stored as UTC wall-clock) |
| Money / quantities | JSON numbers from Java `BigDecimal` (e.g. `12390.00`). Never do money math with JS floats: keep the string form or use a decimal library; send amounts as **strings** in request bodies |
| Business days | dashboards use `Asia/Kolkata`; Gemini's daily quota resets at midnight `America/Los_Angeles` |

---

## 1. Login, Bearer token, CORS

### 1.1 The access token

`POST /api/auth/login` with `{"email": "...", "password": "..."}` returns (200):

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJ…", "tokenType": "Bearer", "expiresIn": 1800,
  "expiresAt": "2026-10-28T10:30:00Z",
  "user": { "id": 7, "email": "reviewer7@example.test", "name": "Reviewer Seven", "displayName": "Reviewer Seven", "role": "REVIEWER" } }
```

Send the token on **every other call** as a header:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJ…
```

| Property | Value | Source |
|---|---|---|
| Where it is read | **only** the `Authorization: Bearer …` header. Never a cookie, URL/query parameter or request body (tested) | `JwtBearerAuthenticationFilter` |
| Content | HS256-signed JWT: `iss` = `actify-invoice-process`, `aud` = `actify-invoice-process-web`, `sub` = user id, `tv` = token version, `iat`, `exp`, `jti` | `JwtTokenService` |
| Lifetime | 30 minutes (`invoice.jwt.ttl-minutes`), reported as `expiresIn` (seconds) and `expiresAt` (UTC) | |
| Refresh | none — after expiry the next call returns **401**; send the user to login | |
| Role | **not** in the token: read from the database on every request, so a role change or disabling takes effect on the next request | `JwtBearerAuthenticationFilter` |
| Cookies / session | none. Login sets **no cookie**; the server keeps no session | `ApiOnlySurfaceWebTest` |

Rejected with **401** `{"message":"Please log in."}` and header `WWW-Authenticate: Bearer` (the response never says
which check failed): missing token, bad signature, other key, `alg=none`, HS512, expired, wrong issuer/audience,
malformed header, unknown/disabled user, token version changed. Tests: `AuthSecurityWebTest`, `JwtTokenServiceTest`,
`JwtCompatibilitySpikeTest`.

**Keep the token in memory** (a React context / module variable) — **not** in `localStorage`/`sessionStorage`, where any
injected script could read it. Consequence: a full page reload logs the user out; that is intended. Never put the token
in a URL, never log it, never put any secret in a `VITE_*` variable (everything in `VITE_*` is public).

Token-lifecycle options (the backend contract is the same for all; only the first is recommended):

| Where React keeps the token | Survives reload | Readable by an injected script | Verdict |
|---|---|---|---|
| Memory (module variable / React context) | no — user logs in again | only while that script runs in the page | **use this** |
| `sessionStorage` | yes, per tab | yes, can be copied out and reused until `expiresAt` | do not use |
| `localStorage` | yes, all tabs, after browser restart | yes, same risk, longer exposure | do not use |
| Cookie | – | – | not supported: the backend reads only the `Authorization` header |

Lifecycle: keep `expiresAt`; optionally warn the user ~2 minutes before it; there is no refresh call, so on expiry (any
401) forget the token and show login. Logout = forget the token; "log out everywhere" = `POST /api/auth/logout-all`.

**Logout.** There is no server-side "log out this token" call: the frontend simply forgets its in-memory token.
`POST /api/auth/logout-all` is the real server-side revocation — it raises the user's token version, so **every**
token issued to that user so far (all tabs and devices) stops working on its next request.

**Why there is no CSRF token any more.** CSRF attacks need a credential the browser attaches *by itself* (a cookie).
Browsers never add an `Authorization` header on their own, so a forged cross-site request arrives without a token and
gets 401. The backend therefore switched CSRF off together with the cookie (`SecurityConfig`, tested: a write without a
token is 401, a write with a valid token needs no other header).

```js
// api.js - the single fetch wrapper every component uses
const BASE = import.meta.env.VITE_API_BASE_URL ?? '';   // EMPTY = same origin / dev proxy
let accessToken = null;                       // memory only
export const setToken = (t) => { accessToken = t; };
export const clearToken = () => { accessToken = null; };

export async function api(path, { method = 'GET', json, form, signal } = {}) {
  const headers = { Accept: 'application/json' };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (json !== undefined) headers['Content-Type'] = 'application/json';
  // For FormData do NOT set Content-Type: the browser adds it with the multipart boundary.
  const res = await fetch(BASE + path, { method, headers, signal,
    body: json !== undefined ? JSON.stringify(json) : form });
  if (res.status === 401 && accessToken) { clearToken(); onSessionEnded(); }
  return res;
}
```

Using it — login, a Bearer GET, a JSON write and a multipart upload (fake values):
```js
// 1. Login (public). The token comes back in the JSON body; no cookie is set.
const login = await api('/api/auth/login', { method: 'POST', json: { email: 'vendor.test@example.test', password } });
if (!login.ok) { /* 401 generic message, 429 locked */ }
const { accessToken, user } = await login.json();
setToken(accessToken);                         // route by user.role

// 2. Bearer GET
const me = await (await api('/api/auth/me')).json();

// 3. JSON write (VENDOR): create a batch -> 201 BatchSummary { id, status: "OPEN", ... }
const batch = await (await api('/api/batches', { method: 'POST', json: { label: 'October uploads' } })).json();

// 4. Multipart upload: FormData, NO Content-Type header -> 202 (accepted, QUEUED) or 422 (rejected, see `code`)
const form = new FormData();
form.append('file', fileInput.files[0]);       // any valid PDF <= 10 MB
const up = await api(`/api/batches/${batch.id}/files`, { method: 'POST', form });
const result = await up.json();                // { accepted, code, message, status, invoiceId, fileName }
```

### 1.2 Origin model and CORS

**Recommended: one origin.** The browser loads React and calls `/api/**` on the same scheme + host + port; no CORS is
involved.

| Environment | How |
|---|---|
| Local development | Vite dev server with a proxy: `/api` → the backend (`react-handoff/vite.config.example.ts`). The browser only talks to the dev server. |
| Deployed, same origin | One public hostname: a reverse proxy / CDN rule serves the React build and forwards `/api/*` to the backend. |
| Deployed, separate origin | Allowed with Bearer tokens: the owner sets `INVOICE_CORS_ALLOWED_ORIGINS=https://<exact frontend origin>` on the backend. |

CORS is **closed by default** (no `Access-Control-*` headers → browsers refuse cross-origin calls). When
`INVOICE_CORS_ALLOWED_ORIGINS` is set, only those exact origins (comma-separated, `scheme://host[:port]`, no `*`, no
path) get CORS headers: methods `GET, POST, PUT`, request headers `Authorization, Content-Type`, exposed header
`Content-Disposition`, **no credentials** (nothing to send — there are no cookies). An invalid entry stops the backend
from starting. Tests: `CorsWebTest`. React then calls the absolute API URL (e.g. `VITE_API_BASE_URL`, a public value).

| Mode | React `.env.local` | Backend setting needed |
|---|---|---|
| Dev proxy (recommended, works today) | `API_PROXY_TARGET=https://invoice-process.onrender.com` (or `http://127.0.0.1:8088` for a local backend), `VITE_API_BASE_URL=` (empty) | none |
| Direct calls from another origin | `VITE_API_BASE_URL=https://invoice-process.onrender.com` | owner sets `INVOICE_CORS_ALLOWED_ORIGINS=<that exact origin>`, e.g. `http://localhost:5173` or the real frontend URL — **not set on the live service as of 2026-10-28** |

The production frontend domain is not known yet; it must be added by the owner as an exact origin when it exists.
Never put a database URL, the JWT secret, storage keys or API keys in any React variable.

### 1.3 Migration from the cookie release

| Before (≤ 2026-10-27) | Now |
|---|---|
| login set cookie `INVOICE_PROCESS_AUTH` | login returns `accessToken` in the body; no cookie |
| every POST/PUT needed `X-XSRF-TOKEN` | no CSRF header; `Authorization: Bearer` on every call |
| `POST /api/auth/logout` | removed (forget the token; `POST /api/auth/logout-all` revokes server-side) |
| login response = the user object | login response = `{accessToken, tokenType, expiresIn, expiresAt, user}` — `user` has the old shape |
| anonymous write without CSRF header → 403 | anonymous write → 401 |
| no CORS at all | CORS off by default, exact-origin allow-list possible |

### 1.4 Which build am I talking to?

`POST /api/auth/login` with valid credentials: a body containing `"accessToken"` = this release. A body without it and a
`Set-Cookie: INVOICE_PROCESS_AUTH` header (or a 403 "Missing or invalid CSRF token" when no CSRF header was sent) = the
older cookie build — ask the backend owner to deploy this release first. Without credentials: an anonymous
`GET /api/auth/me` answering 401 **with** `WWW-Authenticate: Bearer` indicates this release (observed 2026-10-28).

---

## 2. Error model

Every 4xx/5xx from the API (from controllers **and** from the security filters) has the same JSON shape (`ErrorResponse`):

```json
{ "timestamp": "2026-10-23T10:15:00", "status": 409, "error": "Version conflict",
  "message": "Version 2 is not the submitted, current version (submitted: 2, current: 3). Reload before deciding.",
  "path": "/api/invoices/41/approve", "fieldErrors": null }
```

- `message` is always safe to show; it never contains a stack trace, file path, SQL, token or secret.
- `fieldErrors` (only some 400/422): `[{ "field": "items[0].quantity", "message": "..." }]` — show next to the field.
- `errorId` (only on unexpected 500s): random 8-hex reference, the same id is in the server log line. Show "reference <errorId>".

| Status | Meaning in this API | Typical cause | UI reaction |
|---|---|---|---|
| 400 | Malformed or invalid request | bad JSON, missing part/parameter, field validation (`fieldErrors`), file too large / not a PDF on the legacy endpoint, a whole upload request over 11 MB | show `message` / field errors; do not retry unchanged |
| 401 | Not authenticated | no/malformed `Authorization: Bearer` header, expired/revoked/invalid token, disabled user; **login**: wrong e-mail/password (one generic message) | forget the token, go to login (on the login form show the message) |
| 403 | Valid token, but not allowed | wrong role for the URL, wrong dashboard, admin editing a workflow invoice | "not allowed" — do not retry |
| 404 | Not found **or not yours** (same answer on purpose) | other vendor's batch/invoice/PDF, reviewer not assigned, unknown id | "not found" |
| 409 | Business-rule / state conflict | stale `baseVersion`/`version`, wrong status for the action, closed/full batch, duplicate e-mail, legacy extract switched off | reload the object, show `message`; never resend the same body blindly |
| 415 | Wrong `Content-Type` | developer error | fix the request |
| 422 | Rule rejected the content | batch file rejected (`FileIntakeResponse.code`), submit while current data fails a check or duplicates an earlier invoice of the same vendor, legacy extraction result failed validation | show `code`/`message`/`fieldErrors`; do not retry unchanged |
| 429 | Temporarily refused | login locked (5 failures → 15 min); Gemini quota (legacy extract only) | show message; retry later |
| 502 | Provider failure | Gemini call failed (legacy `POST /api/invoices/extract` only) | show message; retry later |
| 500 | Server or storage error | database unavailable (e.g. free quota used up), a stored PDF failing its SHA-256 check, or the PDF bucket unreachable during an upload (`message`: "Could not store the original PDF" — the file was **not** accepted, nothing was queued) | "something went wrong (reference …)"; an upload may be retried later — a duplicate is never created |

Duplicate invoices: a re-uploaded **identical file** of the same vendor is refused at intake (422 `DUPLICATE_FILE`); the
**same invoice number** from the same vendor in a different PDF is flagged after extraction as a review reason and blocks
submit (422). Duplicates are never computed across vendors. Database constraint failures that are not mapped to a
specific message surface as a generic 500 with `errorId`. Provider (Gemini) failures in the **batch workflow** never
produce an HTTP error: they appear as invoice status `QUEUED` (waiting / retry scheduled) or `FAILED` with a
`failureReason`.

---

## 3. Endpoint reference (46 routes)

Legend — **Auth**: `public` / `login` / role list (checked by `SecurityConfig`: 401 without a valid Bearer token, 403
wrong role). Every route except `GET /api/health` and `POST /api/auth/login` needs `Authorization: Bearer <token>`.
**Scope**: per-object rule (`InvoiceAccessPolicy`, services) — failing it gives **404**. **Writes**: the call changes
data (disable the button while it is pending). "Tests" lists the test classes that exercise the route over HTTP.
Numbers are kept from the previous edition; #3 (`POST /api/auth/logout`) was removed.

**Verification status (2026-10-28), the same for every route below:** verified against the **local code and its
automated tests** (`./mvnw -o test`, 333 tests green) and a local run against the Neon database and bucket (owner's
API smoke test: login, batch, upload 202, duplicate/damaged 422, PDF download, 401, cross-vendor 404). **Not verified
live** beyond the anonymous checks at the top of this document: no authenticated call has been made against
`https://invoice-process.onrender.com` yet. This section becomes "verified live" only after the post-deploy checks in
`RENDER_DEPLOYMENT.md` section 9 pass.

### 3.1 Health and session

| # | Method & path | Handler | Auth | Writes | Request | Success | Errors | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | `GET /api/health` | `HealthController.health` | public | – | – | 200 `{"status":"UP"}`, `Cache-Control: no-store` | – | `ApiOnlySurfaceWebTest` |
| 2 | `POST /api/auth/login` | `AuthController.login` | public (an `Authorization` header is ignored) | – | `LoginRequest {email, password}` | 200 `LoginResponse {accessToken, tokenType:"Bearer", expiresIn, expiresAt, user: Me}`, `Cache-Control: no-store`, **no cookie** | 401 generic (wrong/unknown/disabled, password > 72 bytes), 429 locked, 415 | `AuthSecurityWebTest`, `AuthPgIntegrationTest` |
| 4 | `POST /api/auth/logout-all` | `AuthController.logoutEverywhere` | login | yes | – | 204; token version +1 → **every** token of this user stops working | 401 | `AuthSecurityWebTest` |
| 5 | `GET /api/auth/me` | `AuthController.me` | login | – | – | 200 `Me` | 401 | `AuthSecurityWebTest`, `AuthPgIntegrationTest` |

Side effects: login records failures in an in-memory limiter (5 failures per e-mail **or** per client address → 15-min
lock; resets on restart); logout-all writes `app_users.token_version`.

### 3.2 Vendor batches and cloud sources (VENDOR, ADMIN)

| # | Method & path | Handler | Scope | Writes | Request | Success | Errors | Tests |
|---|---|---|---|---|---|---|---|---|
| 6 | `POST /api/batches` | `BatchController.create` | creator owns it | yes | `CreateBatchRequest {label?, defaultCategoryCode?, source?}` — `source` ∈ `LOCAL` (default) / `GOOGLE_DRIVE` / `ONEDRIVE`; label ≤ 255 | 201 `BatchSummary` | 400 unknown source / cloud source not configured / unknown category | `BatchQueuePgIntegrationTest`, `BatchRouteProtectionWebTest` |
| 7 | `GET /api/batches` | `BatchController.list` | vendor: own; admin: all | – | – | 200 `BatchSummary[]` newest first | – | same |
| 8 | `GET /api/batches/{id}` | `BatchController.get` | owner or admin | – | – | 200 `BatchDetail` | 404 | same |
| 9 | `POST /api/batches/{id}/files` | `BatchController.addFile` | owner or admin | yes | multipart: `file` (one PDF, required), `relativePath?` (folder uploads), `source?` | **202** accepted / **422** rejected — both `FileIntakeResponse` | 400 no file / > 10 MB, 404, 409 batch closed or 100 files reached | `BatchQueuePgIntegrationTest`, `IntakeAssignmentPgIntegrationTest`, `BatchRouteProtectionWebTest` |
| 10 | `POST /api/batches/{id}/close` | `BatchController.close` | owner or admin | yes | – | 200 `BatchSummary` (`CLOSED`) | 404, 409 | `BatchQueuePgIntegrationTest` |
| 11 | `GET /api/cloud-sources` | `CloudSourceController.list` | – | – | – | 200 one entry per provider (below) | – | `BatchRouteProtectionWebTest`, `CloudSourceSettingsTest` |

**Any real invoice PDF is accepted** when it passes these server-side rules (identical for every source, no list of
allowed files, no e-mail-domain rule): non-empty, ≤ 10 MB (a whole request over 11 MB is refused with **400** before it
reaches the rules), name ends with `.pdf`, content type `application/pdf` / `application/x-pdf` /
`application/octet-stream`, bytes start with `%PDF-`, **and the file really opens as a PDF with at least one page**
(Apache PDFBox; damaged/truncated files and password-protected PDFs are refused). These checks are not a virus scan.
Rejection codes (422, `FileIntakeResponse.code`): `INVALID_FILE` (with a message such as "The file could not be opened
as a PDF (damaged or incomplete)" or "Password-protected PDFs cannot be processed…"), `DUPLICATE_FILE` (same SHA-256
already uploaded **by this vendor**, any batch — another vendor's identical file is neither blocked nor revealed;
SHA-256 is a duplicate check, not malware detection), `NESTED_FOLDER` (file in a sub-folder of a folder upload),
`SOURCE_MISMATCH` (declared `source` ≠ batch source). Max **100 accepted files per batch**; one file per request.
Tests: `PdfFilesTest`, `RealPdfUploadIntakeTest`. Six synthetic sample PDFs for trying the screen are in
`react-handoff/sample-invoices/` — examples only.

Side effects of a 202: the PDF is written to private storage under a random UUID — on Render a **private bucket**,
locally a private folder; SHA-256 verified after writing — then `invoice_documents` + `invoices` (`QUEUED`) +
`extraction_jobs` rows are inserted in one transaction (the stored file is deleted again if that transaction fails),
and the batch counters change. If the storage write fails, no row is created and the request answers 500. **Nothing
calls Gemini during upload**, and `QUEUED` means "accepted and waiting for extraction", never "extracted". Tests:
`RealPdfUploadIntakeTest`, `S3DocumentStorageTest`.

Concurrency / idempotency: uploads into one batch are serialised by a row lock; the same vendor uploading the same
bytes into two batches at the same moment gets exactly one 202 and one 422 `DUPLICATE_FILE`. Therefore a file whose
request failed without a response can be **retried safely** — a second copy is never created.

`GET /api/cloud-sources` returns public browser configuration only, never a secret:
```json
[ { "source": "GOOGLE_DRIVE", "label": "Google Drive", "status": "NOT_CONFIGURED", "mockTested": true,
    "problems": ["INVOICE_GOOGLE_CLIENT_ID is not set"], "setupHint": "Set INVOICE_GOOGLE_CLIENT_ID ..." },
  { "source": "ONEDRIVE", "label": "Microsoft OneDrive", "status": "CONFIGURED_NOT_LIVE_TESTED", "mockTested": true,
    "problems": [], "setupHint": "...",
    "browserConfig": { "clientId": "11111111-2222-3333-4444-555555555555", "accountType": "PERSONAL",
                       "authority": "https://login.microsoftonline.com/consumers", "pickerBaseUrl": "https://onedrive.live.com/picker" } } ]
```
`status`: `NOT_CONFIGURED` | `CONFIGURED_NOT_LIVE_TESTED` | `LIVE_TESTED`. Google `browserConfig`: `clientId`, `apiKey`
(browser key, referrer-restricted), `appId`, `scope` (`drive.file`).

### 3.3 Invoices — read (any logged-in role; vendor = own uploads, reviewer = assigned to me, admin = all)

| # | Method & path | Handler | Request | Success | Errors | Tests |
|---|---|---|---|---|---|---|
| 12 | `GET /api/invoices` | `ReviewController.list` | query: `status?`, `category?`, `batchId?`, `unassigned?` (`true` = no reviewer), `legacy` (admin; default `false` = workflow invoices, `true` = old single-file invoices), `page` (0-based, default 0), `size` (default 50, clamped 1–200) | 200 `InvoiceSummary[]` sorted by `id` ascending | 400 bad enum/number | `BatchQueuePgIntegrationTest`, `ReviewApprovalPgIntegrationTest` |
| 13 | `GET /api/invoices/{id}` | `InvoiceController.getInvoice` | – | 200 `Invoice` | 404 | `InvoiceRouteProtectionWebTest`, `AuthPgIntegrationTest`, pgtests |
| 14 | `GET /api/invoices/{id}/review-reasons` | `ReviewController.reviewReasons` | – | 200 `ReviewReason[]` stored at extraction (V1) | 404 | `BatchQueuePgIntegrationTest`, `ReviewApprovalPgIntegrationTest` |
| 15 | `GET /api/invoices/{id}/checks` | `ReviewController.currentChecks` | – | 200 checks on the **current** data (`[]` = all pass) | 404 | `ReviewApprovalPgIntegrationTest` |
| 16 | `GET /api/invoices/{id}/events` | `ReviewController.events` | – | 200 `Event[]` oldest first (status changes and assignment changes) | 404 | `ReviewApprovalPgIntegrationTest` |
| 17 | `GET /api/invoices/{id}/versions` | `InvoiceVersionController.listVersions` | – | 200 `Version[]` newest first, without `snapshot` | 404 | `InvoiceVersionControllerTest`, `InvoiceVersioningPgIntegrationTest` |
| 18 | `GET /api/invoices/{id}/versions/{n}` | `InvoiceVersionController.getVersion` | `n` digits only | 200 `Version` with `snapshot` | 404 | same |
| 19 | `GET /api/invoices/{id}/versions/compare?from=&to=` | `InvoiceVersionController.compare` | `from`, `to` required ints | 200 `{invoiceId, fromVersion, toVersion, differences: FieldChange[]}` | 400 missing param, 404 | same |
| 20 | `GET /api/invoices/{id}/document` | `DocumentController.getInvoiceDocument` | – | 200 PDF bytes (section 6) | 404 missing/not yours, 500 integrity failure | `InvoiceRouteProtectionWebTest`, `IntakeAssignmentPgIntegrationTest` |
| 21 | `GET /api/documents/{documentId}` | `DocumentController.getDocument` | UUID from `sourceDocument.id` | 200 PDF bytes | 404 (also for non-UUID ids) | `InvoiceRouteProtectionWebTest`, `BatchQueuePgIntegrationTest` |

Vendors never see the reviewer's identity (`assignedReviewerName` is null for them) nor review reasons that would reveal
another vendor's uploads.

### 3.4 Reviewer actions

| # | Method & path | Handler | Auth / scope | Writes | Request | Success | Errors | Tests |
|---|---|---|---|---|---|---|---|---|
| 22 | `PUT /api/invoices/{id}` | `InvoiceVersionController.saveCorrection` | REVIEWER assigned to it (workflow invoices, status `NEEDS_REVIEW`) / ADMIN only for **legacy** invoices | yes | `InvoiceUpdate` (complete invoice, below) | 200 `{changed: true, invoice}` = new version; `{changed: false, invoice}` when identical (no version) | 400 `fieldErrors` (unknown/non-editable fields, formats, > max items), 403 admin on workflow invoice, 404, **409** stale `baseVersion` or not editable, 422 arithmetic check failed | `InvoiceVersionControllerTest`, `InvoiceVersioningPgIntegrationTest`, `AuthPgIntegrationTest` |
| 23 | `POST /api/invoices/{id}/submit` | `ReviewController.submit` | REVIEWER (assigned) | yes | `{"version": n}` | 200 `Invoice` (`PENDING_APPROVAL`, `submittedVersion: n`) | 404, 409 not current version / not `NEEDS_REVIEW`, 422 current data fails a check or duplicates an earlier invoice of the same vendor | `ReviewApprovalPgIntegrationTest`, `BatchQueuePgIntegrationTest` |

`InvoiceUpdate` — the **complete** corrected invoice, not a patch (unknown fields are rejected with 400):
```json
{ "baseVersion": 1, "changeReason": "Fixed tax", "invoiceNumber": "INV-001", "invoiceDate": "2026-10-06",
  "sellerName": "Synthetic Seller", "sellerTaxId": "6000000001", "buyerName": "Synthetic Buyer", "buyerTaxId": null,
  "currency": "INR", "subtotal": "10500.00", "discountAmount": null, "shippingAmount": null, "taxAmount": "1890.00",
  "totalAmount": "12390.00",
  "items": [ { "description": "Laptop Stand", "quantity": "2", "unitPrice": "5000.00", "taxRate": "18", "lineTotal": "10000.00" } ] }
```
Concurrency: every save locks the invoice row; `baseVersion` must equal the latest version, otherwise 409 (someone
saved in between — reload). Versions are immutable; history is never rewritten.

### 3.5 Admin

| # | Method & path | Handler | Writes | Request | Success | Errors / notes | Tests |
|---|---|---|---|---|---|---|---|
| 24 | `GET /api/admin/users` | `AdminUserController.list` | – | – | 200 `User[]` | – | `AuthSecurityWebTest` |
| 25 | `POST /api/admin/users` | `AdminUserController.create` | yes | `{email, displayName?, role, password}` — password 12–72 **bytes**, displayName ≤ 100 (blank → e-mail name) | 201 `User` | 400 `fieldErrors` (all problems together), 409 e-mail exists | `AuthSecurityWebTest`, `AuthPgIntegrationTest` |
| 26 | `PUT /api/admin/users/{id}/role` | `AdminUserController.changeRole` | yes | `{"role":"REVIEWER"}` | 200 `User`; token version +1 (user logs in again) | 404, 409 last enabled admin | `AuthSecurityWebTest` |
| 27 | `PUT /api/admin/users/{id}/display-name` | `AdminUserController.changeDisplayName` | yes | `{"displayName":"Sam"}` | 200 `User` (tokens stay valid) | 400 > 100 chars, 404 | `AuthSecurityWebTest` |
| 28 | `PUT /api/admin/users/{id}/enabled` | `AdminUserController.setEnabled` | yes | `{"enabled":false}` | 200 `User`; disabled → 401 on next request | 400 missing `enabled`, 404, 409 last enabled admin | `AuthPgIntegrationTest` |
| 29 | `GET /api/admin/users/{id}/categories` | `AdminReviewController.reviewerCategories` | – | – | 200 `["IT"]` | 404 | route rule only (`/api/admin/**`) |
| 30 | `PUT /api/admin/users/{id}/categories` | `AdminReviewController.setReviewerCategories` | yes | `{"categories":["IT","OFFICE"]}` (replaces the list; active categories only) | 200 list | 400, 404, 409 not a reviewer | `IntakeAssignmentPgIntegrationTest`, `DashboardPgIntegrationTest` |
| 31 | `GET /api/categories` | `CategoryController.listActive` | – | – | 200 active `Category[]` (any logged-in role) | – | `BatchRouteProtectionWebTest` |
| 32 | `GET /api/admin/categories` | `CategoryController.listAll` | – | – | 200 all `Category[]` | – | `BatchRouteProtectionWebTest` |
| 33 | `POST /api/admin/categories` | `CategoryController.create` | yes | `{"code":"IT","displayName":"IT equipment"}` | 201 `Category` | 400, 409 exists | `IntakeAssignmentPgIntegrationTest`, `ReviewApprovalPgIntegrationTest` |
| 34 | `PUT /api/admin/categories/{code}/active` | `CategoryController.setActive` | yes | `{"active":false}` | 200 `Category` | 400 missing `active`, 404 | **no HTTP-level test** (route rule only) |
| 35 | `PUT /api/admin/invoices/{id}/category` | `AdminReviewController.setInvoiceCategory` | yes | `{"categoryCode":"IT"}` or `null` | 200 `Invoice`; a reviewer without the new category is unassigned | 404, 409 after submit | `ReviewApprovalPgIntegrationTest`, `DashboardPgIntegrationTest` |
| 36 | `GET /api/admin/intake` | `AdminReviewController.intake` | – | – | 200 intake board (QUEUED/PROCESSING/NEEDS_REVIEW, split `needsAssignment` / `assigned`) | – | `AdminIntakeRouteProtectionWebTest`, `IntakeAssignmentPgIntegrationTest` |
| 37 | `POST /api/admin/assignments` | `AdminReviewController.assign` | yes | `{"invoiceIds":[41,42],"reviewerId":7,"reassign":false}` (1–200 ids) | 200 one `AssignmentResult` per invoice | 400, 409 disabled / not a reviewer. Without `reassign:true` an already-assigned invoice is **not** changed (`assigned:false`); unassign = `reviewerId:null` + `reassign:true` | `IntakeAssignmentPgIntegrationTest`, `AdminIntakeRouteProtectionWebTest` |
| 38 | `POST /api/invoices/{id}/approve` | `ApprovalController.approve` | yes | `{"version":2,"reason":"optional"}` | 200 `Invoice` (`APPROVED`) | 409 unless `version` == submitted == current | `ReviewApprovalPgIntegrationTest` |
| 39 | `POST /api/invoices/{id}/reject` | `ApprovalController.reject` | yes | `{"version":2,"reason":"required"}` | 200 (`REJECTED`) | 400 blank reason, 409 stale | same |
| 40 | `POST /api/invoices/{id}/send-back` | `ApprovalController.sendBack` | yes | `{"reason":"required"}` | 200 (`NEEDS_REVIEW`) | only from `PENDING_APPROVAL` (409) | same |
| 41 | `POST /api/invoices/{id}/reopen` | `ApprovalController.reopen` | yes | `{"reason":"required"}` | 200 (`NEEDS_REVIEW`) | only from `APPROVED`/`REJECTED` | same |
| 42 | `POST /api/invoices/{id}/requeue` | `ApprovalController.requeue` | yes | `{"reason":"required"}` | 200 (`QUEUED`) | only a `FAILED` batch invoice | same |
| 43 | `GET /api/admin/queue/status` | `AdminQueueController.status` | – | – | 200 `{workerEnabled, workerRunning, geminiMode, geminiKeyConfigured, onlyInvoiceIds, idleReason, quota}` | `geminiKeyConfigured` = presence only | `DailyQuotaPgIntegrationTest` |
| 44 | `POST /api/invoices/extract` | `InvoiceController.extract` | yes | multipart `file` | 201 `Invoice` — **legacy** single-file extraction, calls Gemini synchronously | 400 file rules, 409 switched off (`invoice.legacy-extract.enabled=false`: local demo and default in `render`), 422 validation, 429 quota, 502 provider | `InvoiceControllerTest`, `LegacyExtractSwitchTest`, `InvoiceRouteProtectionWebTest` |

Every decision writes an append-only `invoice_workflow_events` row (who, when, from/to status, version, reason).

### 3.6 Dashboards (each role only its own; wrong role → 403)

| # | Route | Handler | Param |
|---|---|---|---|
| 45 | `GET /api/dashboard/vendor` | `DashboardController.vendor` | `days` (default 7, clamped 1–90) |
| 46 | `GET /api/dashboard/reviewer` | `DashboardController.reviewer` | `days` |
| 47 | `GET /api/dashboard/admin` | `DashboardController.admin` | `days` |

Each response is a JSON object of metrics already scoped to the caller; every rate comes with numerator, denominator
and a `definition` text. Accuracy is never inferred (`observedFieldMatchRate` / `reviewerAgreement` stay unavailable
until an audit sample exists). Tests: `DashboardPgIntegrationTest`, `DashboardMetricsTest`. Source:
`dashboard/DashboardService`. Which card shows which field: section 11a.

**Missing keys mean 0.** `statusCounts` / `assignedStatusCounts` contain only the statuses that occur (an empty object
when there are none), and the last `reviewerWorkload` row ("(not assigned)") has no `PENDING_APPROVAL` key. Always read
counts as `value ?? 0`. Rates are fractions 0–1 (multiply by 100 to display) or `null` when there is nothing to divide.

```jsonc
// Common header of all three
{ "timeZone": "Asia/Kolkata", "periodFirstDay": "2026-10-22", "periodLastDay": "2026-10-28", ... }

// GET /api/dashboard/vendor (own invoices only)
{ ...header,
  "statusCounts": { "QUEUED": 2, "APPROVED": 1 },  // InvoiceStatus -> count, own invoices
  "receivedToday": 1, "receivedInPeriod": 3,        // invoices created today / in the period
  "batches": 4,                                     // own batches, all time
  "recentFailures": [ { "invoiceId": 41, "fileName": "inv-001.pdf", "reason": "...", "at": "2026-10-28T09:00:00" } ], // max 10
  "geminiUsage": { "calls": 0, "reportedTotalTokens": 0, "callsWithoutReportedTokens": 0,
                   "byDayAndModel": [ { "day": "2026-10-28", "model": "...", "calls": 0, "errors": 0,
                                        "promptTokens": 0, "outputTokens": 0, "totalTokens": 0 } ],
                   "definition": "..." } }

// GET /api/dashboard/reviewer (invoices assigned to me)
{ ...header,
  "assignedStatusCounts": { "NEEDS_REVIEW": 2 },
  "oldestWaitingSince": "2026-10-27T10:00:00",       // null when nothing waits
  "submittedInPeriod": 1, "correctionsSavedInPeriod": 3,
  "myCorrectionRate": { "submissions": 1, "fieldsCompared": 22, "fieldsChanged": 1, "rate": 0.045,
                        "changesByField": { "taxAmount": 1 }, "definition": "..." } }

// GET /api/dashboard/admin (all workflow invoices)
{ ...header,
  "statusCounts": { "QUEUED": 3 }, "legacyInvoices": 0, "receivedToday": 2,
  "queue": { "workerEnabled": false, "waitingForRetryOrDailyReset": 0, "oldestQueuedSince": "2026-10-28T09:12:00",
             "quota": { /* QuotaStatus, as in GET /api/admin/queue/status */ } },
  "reviewerWorkload": [ { "reviewerId": 7, "name": "Reviewer Seven", "displayName": "Reviewer Seven", "NEEDS_REVIEW": 2, "PENDING_APPROVAL": 1 },
                        { "reviewerId": null, "name": "(not assigned)", "displayName": "(not assigned)", "NEEDS_REVIEW": 0 } ],
  "decisionsInPeriod": { "PENDING_APPROVAL": 0, "APPROVED": 0, "REJECTED": 0, "definition": "..." },
  "quality": {
    "automaticChecksPassed": { "extracted": 0, "passedAllChecks": 0, "rate": null, "definition": "..." },
    "reviewReasons": { "counts": { "TOTAL_MISMATCH": 1 }, "definition": "..." },
    "correctionRate": { /* same shape as myCorrectionRate */ },
    "accuracy": { "observedFieldMatchRate": null, "reviewerAgreement": null, "definition": "..." },
    "medianExtractionSeconds": { "samples": 0, "value": null, "definition": "..." } },  // value null below 20 samples
  "geminiUsage": { /* same shape as vendor */ },
  "usageByVendor": [ { "vendorUserId": 3, "vendorName": "Vendor Three", "calls": 2, "reportedTotalTokens": 4100 } ] }

// QuotaStatus (queue.quota above, and "quota" in GET /api/admin/queue/status)
{ "configured": false, "configurationProblem": "...", "model": "...", "rpmLimit": null, "tpmLimit": null, "rpdLimit": null,
  "safetyMargin": 0.8, "requestsLastMinute": 0, "inputTokensLastMinute": 0, "requestsSinceDailyReset": 0,
  "pausedUntil": null, "nextDailyReset": "2026-10-29T12:30:00", "estimatedInputTokensPerCall": 0 }
// GET /api/admin/queue/status
{ "workerEnabled": false, "workerRunning": false, "geminiMode": "NOT_CONFIRMED", "geminiKeyConfigured": false,
  "onlyInvoiceIds": [],
  "idleReason": "...", "quota": { /* QuotaStatus */ } }
```

With extraction switched off (the default), nothing leaves `QUEUED`: the review, approval and quality numbers stay 0 or
`null`. That is correct data, not an error.

---

## 4. Schemas (response shapes)

```jsonc
// LoginResponse (POST /api/auth/login) - section 1.1
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 1800, "expiresAt": "2026-10-28T10:30:00Z", "user": { /* Me */ } }
// Me (/api/auth/me, and LoginResponse.user)
{ "id": 7, "email": "reviewer7@example.test", "name": "Reviewer Seven", "displayName": "Reviewer Seven", "role": "REVIEWER" }
// User (admin) = Me + "enabled", "createdAt", "updatedAt" (never a password hash)
// name = displayName when filled in, otherwise the e-mail before "@". Display "name" everywhere.

// BatchSummary
{ "id": 12, "label": "October", "source": "LOCAL", "status": "OPEN", "defaultCategoryCode": "IT", "ownerUserId": 3,
  "acceptedCount": 2, "rejectedCount": 1, "createdAt": "2026-10-23T10:00:00", "closedAt": null }
// BatchDetail
{ "batch": { /* BatchSummary */ }, "statusCounts": { "QUEUED": 2 },
  "invoices": [ { "id": 41, "fileName": "inv-001.pdf", "status": "QUEUED", "queuePosition": 1, "failureReason": null,
                  "createdAt": "2026-10-23T10:00:05" } ],
  "rejections": [ { "fileName": "notes.pdf", "code": "INVALID_FILE", "message": "File content is not a valid PDF" } ] }
// FileIntakeResponse (202 / 422)
{ "fileName": "inv-001.pdf", "accepted": true, "invoiceId": 41, "status": "QUEUED", "code": null,
  "message": "Accepted and queued for extraction." }

// InvoiceSummary (list rows)
{ "id": 41, "status": "NEEDS_REVIEW", "fileName": "inv-001.pdf", "invoiceNumber": "INV-001", "sellerName": "Synthetic Seller",
  "totalAmount": 12390.00, "currency": "INR", "categoryCode": "IT", "ownerUserId": 3, "batchId": 12,
  "assignedReviewerId": 7, "assignedReviewerName": "Reviewer Seven", "submittedVersion": null, "reviewReasonCount": 1,
  "createdAt": "2026-10-23T10:00:05", "updatedAt": "2026-10-23T10:02:00" }

// Invoice (detail; also returned by decisions and submit)
{ "id": 41, "status": "NEEDS_REVIEW", "invoiceNumber": "INV-001", "invoiceDate": "2026-10-06",
  "sellerName": "Synthetic Seller", "sellerTaxId": "6000000001", "buyerName": "Synthetic Buyer", "buyerTaxId": null,
  "currency": "INR", "subtotal": 10500.00, "discountAmount": null, "shippingAmount": null, "taxAmount": 1890.00,
  "totalAmount": 12390.00, "sourceFileName": "inv-001.pdf", "sourceFileSize": 48211, "failureReason": null,
  "createdAt": "...", "updatedAt": "...",
  "items": [ { "description": "Laptop Stand", "quantity": 2.000, "unitPrice": 5000.00, "taxRate": 18.00, "lineTotal": 10000.00 } ],
  "tokenUsage": { "modelId": null, "modelVersion": null, "promptTokenCount": null, "candidatesTokenCount": null,
                  "thoughtsTokenCount": null, "totalTokenCount": null, "extractionDurationMs": null },
  "currentVersion": 2,
  "sourceDocument": { "id": "8c0e…uuid", "sha256": "ab12…", "sizeBytes": 48211, "contentType": "application/pdf",
                      "originalFileName": "inv-001.pdf", "createdAt": "...", "url": "/api/documents/8c0e…uuid" },
  "workflow": { "legacy": false, "ownerUserId": 3, "batchId": 12, "categoryCode": "IT", "assignedReviewerId": 7, "submittedVersion": null } }

// ReviewReason (/review-reasons adds "versionNumber"; /checks has code, field, message)
{ "versionNumber": 1, "code": "TOTAL_MISMATCH", "field": "totalAmount", "message": "..." }
// Event
{ "id": 90, "fromStatus": "NEEDS_REVIEW", "toStatus": "PENDING_APPROVAL", "actorUserId": 7, "actorName": "Reviewer Seven",
  "versionNumber": 2, "reason": "Submitted for approval", "createdAt": "..." }
// fromStatus == toStatus => an assignment change ("Assigned to …", "Reassigned from … to …")
// Version
{ "invoiceId": 41, "versionNumber": 2, "changeSource": "MANUAL_EDIT", "changeReason": "Fixed tax",
  "changedBy": "user:7", "changedByName": "Reviewer Seven", "createdAt": "...", "sourceDocument": { },
  "changedFields": [ { "field": "taxAmount", "changeType": "CHANGED", "from": "1800.00", "to": "1890.00" } ],
  "snapshot": { /* only on GET /versions/{n} */ } }
// changeSource: LLM_EXTRACTION ("LLM extraction"), MANUAL_EDIT (show changedByName), LEGACY_BASELINE ("Original data")
// AssignmentResult
{ "invoiceId": 41, "assigned": true, "message": "Assigned.", "status": "QUEUED", "reviewerId": 7, "reviewerName": "Reviewer Seven" }
// Category
{ "code": "IT", "displayName": "IT equipment", "active": true, "createdAt": "..." }
```
Names of deleted accounts are returned as `"Unknown user"`, never as a raw id.

## 5. Invoice status machine (source of truth: `workflow/WorkflowTransitions`)

| From | To | Who / how |
|---|---|---|
| (new) | `QUEUED` | vendor upload accepted |
| `QUEUED` | `PROCESSING` | worker claims the job |
| `PROCESSING` | `NEEDS_REVIEW` | extraction stored (every extraction waits for a human) |
| `PROCESSING` | `QUEUED` | retry / stale claim / daily quota pause |
| `PROCESSING` | `FAILED` | no attempts left (max 3 = 1 + 2 retries) |
| `NEEDS_REVIEW` | `PENDING_APPROVAL` | reviewer submits an exact version |
| `PENDING_APPROVAL` | `APPROVED` / `REJECTED` | admin decides on that exact version |
| `PENDING_APPROVAL` | `NEEDS_REVIEW` | admin sends back (reason) |
| `APPROVED` / `REJECTED` | `NEEDS_REVIEW` | admin reopens (reason) |
| `FAILED` | `QUEUED` | admin requeues (reason) |

Editable: `NEEDS_REVIEW` only (plus legacy `EXTRACTED`, admin). Intake-board `stage`: `WAITING_FOR_EXTRACTION`
(QUEUED), `EXTRACTING` (PROCESSING), `READY_FOR_REVIEW` (NEEDS_REVIEW). `idleReason`/`blockedReason` explain why
nothing moves (worker off, no key, limits missing, daily/minute pacing, scheduled retry, controlled-test allowlist) —
none of these is a failure.

## 6. Original PDF display

`GET /api/invoices/{id}/document` (preferred; `GET /api/documents/{sourceDocument.id}` still works) streams the exact
uploaded bytes. Headers: `Content-Type: application/pdf`, `Content-Disposition: inline; filename="…"`,
`X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `X-Frame-Options: DENY`. Same 404 rule as the invoice.

The request needs the Bearer header, so an `<iframe src="/api/invoices/41/document">` cannot load it (the browser
would not send the token). Fetch the bytes with the API client and show a local object URL instead:

```js
const res = await api(`/api/invoices/${id}/document`);
if (!res.ok) { /* 404 = not yours / missing; 500 = integrity failure */ }
const url = URL.createObjectURL(await res.blob());   // blob: URL, lives only in this tab
iframe.src = url;                                     // or <object data={url} type="application/pdf">
// when the reviewer leaves the invoice: URL.revokeObjectURL(url);
```

`X-Frame-Options: DENY` does not affect a `blob:` URL (it is not a response from the API). "Open in new tab" =
`window.open(url)` with the same object URL. Bytes are re-checked against the stored SHA-256 on every read
(mismatch → 500, never served). The PDF never changes when a reviewer saves a correction.

## 7. Implementing the React app

1. **Start-up:** no token in memory → show login (a page reload always starts here, by design).
2. **Login:** `POST /api/auth/login` `{email, password}` → 200: keep `accessToken` in memory, route by `user.role`.
   401 → generic "wrong e-mail or password". 429 → show the lock message. Never store the password. Optionally start a
   timer from `expiresIn` to warn the user shortly before the session ends.
3. **Logout:** forget the token and clear all cached data (no server call needed). "Log out everywhere":
   `POST /api/auth/logout-all` with the token, then forget it.
4. **Stale session:** any 401 outside login → forget the token, clear state, show login with "your session ended", then
   return the user to where they were.
5. **Permissions in the UI:** derive visible screens from `Me.role` only. Hide what a role cannot do, but expect the
   server to refuse anyway (403/404) and show its message.
6. **Vendor upload:** `GET /api/cloud-sources` + `GET /api/categories` → create the batch **only on a user click**
   (`POST /api/batches`, disable the button while pending) → send files **one at a time**, in order
   (`POST /api/batches/{id}/files`, field `file`; for folder uploads add `relativePath` = `webkitRelativePath`) → show
   202/422 per row → `POST /api/batches/{id}/close` → poll `GET /api/batches/{id}` (~10 s) while any invoice is
   `QUEUED`/`PROCESSING`. Offer "Retry failed files" only for rows that got no response, 429 or 5xx.
7. **Queue/status refresh:** poll, don't hammer: batch detail every ~10 s; admin intake / queue status on demand or
   every ~15–30 s; stop polling when the tab is hidden.
8. **Reviewer:** `GET /api/invoices?status=NEEDS_REVIEW&size=200` → open: `GET /api/invoices/{id}`, `/checks`,
   `/review-reasons`, `/versions`, PDF frame → save: `PUT /api/invoices/{id}` with `baseVersion = currentVersion` and the
   complete item list → submit: `POST /api/invoices/{id}/submit {version: currentVersion}`. Show checks as warnings
   while editing; the server is the judge at submit (422 with `fieldErrors`).
9. **Admin:** `GET /api/admin/intake` → set a category if missing (`PUT /api/admin/invoices/{id}/category`) →
   `POST /api/admin/assignments` (`reassign:true` only for an explicit reassign) → re-fetch the board → approval list
   `GET /api/invoices?status=PENDING_APPROVAL` → approve/reject with `version = submittedVersion`; send-back / reopen /
   requeue need a reason. Users and categories: section 3.5.
10. **On 409:** reload the object and show the server message; never resend the same body automatically.
11. **On 500:** show `message` + "reference `errorId`".
12. **Google Drive / OneDrive (if still required):** the old picker code is not part of the backend any more. A React
    implementation must: read `GET /api/cloud-sources`; treat `NOT_CONFIGURED` as normal (local upload stays available);
    run the provider's picker and OAuth sign-in in the browser with the public `browserConfig` (Google Picker with the
    `drive.file` scope; MSAL for OneDrive with a frontend-hosted redirect page registered in Entra); download the chosen
    file's bytes in the browser with the user's short-lived token; and send them through the **same**
    `POST /api/batches/{id}/files` with `source=GOOGLE_DRIVE|ONEDRIVE`. Provider tokens stay in browser memory and are
    never sent to this API. Owner setup: [PROVIDER_SETUP_GOOGLE_DRIVE_ONEDRIVE.md](PROVIDER_SETUP_GOOGLE_DRIVE_ONEDRIVE.md).
    Status today: mock-tested only, never live-tested against Google or Microsoft.

## 8. Rules that stay in the Java backend (never only in React)

Authentication and token revocation; role checks per URL; per-object visibility (vendor = own, reviewer = assigned,
404 otherwise); file type/size/signature/PDF-structure checks, SHA-256 and per-vendor duplicate detection; batch limits (100
files) and ownership; category ↔ reviewer matching for assignment; the status machine and who may move which status;
exact-version rules for save/submit/approve/reject (409); the arithmetic and format checks on invoice data
(`InvoiceExtractionValidator`) and the duplicate-invoice block at submit; immutable version history and audit events;
password rules (12–72 bytes, BCrypt) and "last enabled admin" protection; login lockout; Gemini quota, pacing and
retries. React may *repeat* simple checks for convenience (e.g. "reason is required"), but must still handle the
server's answer.

## 9. Known gaps for the frontend team

- No refresh token: sessions end after 30 minutes regardless of activity.
- No total count in list responses: page until a page comes back shorter than `size`.
- No server-sent events / websockets: status changes are discovered by polling.
- `PUT /api/admin/categories/{code}/active` has no HTTP-level test yet.
- Cross-origin hosting needs the backend owner to set `INVOICE_CORS_ALLOWED_ORIGINS` (section 1.2).
- Extraction runs only where the owner has enabled the worker **and** confirmed a paid Gemini project
  (`INVOICE_GEMINI_PAID_TIER_CONFIRMED=true`), or for a free-tier test of one allowlisted, authorized, non-confidential invoice
  (`geminiMode=FREE_TIER_TEST`, see `docs/RENDER_DEPLOYMENT.md` 4.2). Otherwise uploads stay `QUEUED` and `GET /api/admin/queue/status`
  explains why in `idleReason` — that is not a bug.
- The Bearer flow is tested in the repository (MockMvc + PostgreSQL integration tests), **not yet against a deployed
  service** (see the note at the top).
- Cloud pickers: frontend work, not live-tested.
- Not supported by the API (do not build these as working features; ask the backend owner first): change or reset
  password, self-registration, deleting users / batches / invoices (disable a user instead), renaming a category,
  filtering invoices by vendor, date range or free text, several statuses in one list call (call once per status),
  a total count for lists, a server-side "retry" for a vendor (vendor "Retry failed files" = re-send the rows that got
  no response, 429 or 5xx; requeuing a `FAILED` invoice is ADMIN-only), a separate download route (download = fetch
  the PDF blob and use `<a download>`).
- While extraction is off, the reviewer queue, approvals, failed list and quality cards stay empty on the live
  service: build those screens against the documented shapes.

## 10. Compatibility history (still relevant to existing clients)

| Change | Effect |
|---|---|
| 2026-10-28: cookie + CSRF replaced by `Authorization: Bearer`; login returns `{accessToken, tokenType, expiresIn, expiresAt, user}`; `POST /api/auth/logout` removed; optional exact-origin CORS | section 1.3 |
| 2026-10-28: temporary free-demo profile removed (no fixture allow-list, no `@example.test` rule); upload also checks that the PDF really opens | any valid invoice PDF is accepted; broken/password-protected PDFs → 422 `INVALID_FILE` |
| 2026-10-27: backend API-only; old pages removed; `GET /api/health` added | `GET /`, `*.html`, `/css/**`, `/js/**` now 401/403 |
| 2026-10-27: cookie `TASK8_AUTH` → `INVOICE_PROCESS_AUTH`; JWT `iss`/`aud` renamed | everyone logs in once more |
| 2026-10-23: `name` on `Me`/`User`, `changedByName`, `actorName`, workload `name` | additive |
| optional `source` on batch create and file upload; `GET /api/cloud-sources` | default `LOCAL` |
| same file by the same vendor into two batches at the same moment → exactly one 202 | before: both could be accepted |
| `GET /api/admin/intake`, `GET /api/invoices/{id}/document` | new routes |
| assignments: optional `reassign`; results gain `status`, `reviewerId`, `reviewerName` | assigning an already-assigned invoice now returns `assigned:false` unless `reassign:true` |
| unexpected 500s gain `errorId` | additive |
| names of deleted accounts → `"Unknown user"` | display as-is |
| `PUT /api/admin/users/{id}/display-name` | new route |
| duplicates computed **only within the same vendor**; submit → 422 while the data duplicates the vendor's earlier invoice | behaviour change |
| assignment changes appear in `/events` with `fromStatus == toStatus` | display as assignment history |

## 11. Reference screens → endpoints

The owner has screenshots of the retired HTML pages (login, vendor, reviewer, admin). They are **layout references
only** — they are not in this repository (they show real accounts) and the React app is built from scratch. What each
screen part calls:

| Screen part (reference) | Calls |
|---|---|
| **Login** — e-mail, password, "Sign in"; "session ends after 30 minutes" | `POST /api/auth/login` → keep `accessToken`, route by `user.role`; show `expiresIn` |
| Header — name, role badge, "Log out" | `user.name`, `user.role` from login (or `GET /api/auth/me`); log out = forget the token |
| **Vendor** "This week" tiles (received, queued, being reviewed, approved, failed, Gemini calls, tokens) | `GET /api/dashboard/vendor?days=7` |
| Vendor "New batch" — label, category, "Files from", "Start batch" | `GET /api/categories`, `GET /api/cloud-sources`, `POST /api/batches` |
| Vendor file picker / upload rows | `POST /api/batches/{id}/files` (one file per request) → 202 / 422 per row; then `POST /api/batches/{id}/close` |
| Vendor "My batches" table (accepted, rejected, status) and batch detail | `GET /api/batches`, `GET /api/batches/{id}` (poll ~10 s while something is `QUEUED`/`PROCESSING`) |
| **Reviewer** "Today" tiles | `GET /api/dashboard/reviewer?days=1` |
| Reviewer "Assigned to me" — To review / History | `GET /api/invoices?status=NEEDS_REVIEW`, and other statuses for history |
| Reviewer left: "Original PDF (as uploaded by the vendor)" + SHA-256 note | `GET /api/invoices/{id}/document` → blob URL (section 6); `sourceDocument.sha256` from `GET /api/invoices/{id}` |
| Reviewer right: "Why it needs attention", "Automatic checks" | `GET /api/invoices/{id}/review-reasons`, `GET /api/invoices/{id}/checks` |
| Reviewer editable invoice data + line items + totals + "Reason for the correction" | `GET /api/invoices/{id}` to fill; **Save correction** = `PUT /api/invoices/{id}` (complete body, `baseVersion`) |
| Reviewer "Submit this version for approval" | `POST /api/invoices/{id}/submit {"version": currentVersion}` |
| Reviewer "Versions" list (V2 · name · date · changes) | `GET /api/invoices/{id}/versions`, compare: `GET …/versions/compare?from=1&to=2` |
| **Admin** Dashboard tiles, queue & Gemini pacing, reviewer workload, quality, Gemini usage | `GET /api/dashboard/admin?days=7`, `GET /api/admin/queue/status` |
| Admin "Intake & assignment" — set category, choose reviewer, assign / reassign | `GET /api/admin/intake`, `PUT /api/admin/invoices/{id}/category`, `POST /api/admin/assignments` |
| Admin "Waiting for approval" — approve / reject / send back | `GET /api/invoices?status=PENDING_APPROVAL`, `POST /api/invoices/{id}/approve` · `/reject` · `/send-back` |
| Admin "Failed extractions" — requeue | `GET /api/invoices?status=FAILED`, `POST /api/invoices/{id}/requeue` |
| Admin "Approved and rejected" — reopen with reason | `GET /api/invoices?status=APPROVED` / `REJECTED`, `POST /api/invoices/{id}/reopen` |
| Admin "Users" — create, rename, role, disable, reviewer categories | `GET/POST /api/admin/users`, `PUT …/{id}/display-name`, `…/role`, `…/enabled`, `GET/PUT …/{id}/categories` |
| Admin "Categories" — add, deactivate | `GET/POST /api/admin/categories`, `PUT /api/admin/categories/{code}/active` |

## 11a. Dashboard cards → endpoint → JSON field

Every card below exists on the reference screens; the field is the exact JSON path in the response (section 3.6).
`?? 0` = the key may be missing (section 3.6). Each dashboard answers **403** to the other two roles.

**Vendor "This week" — `GET /api/dashboard/vendor?days=7` (VENDOR)**

| Card | JSON field |
|---|---|
| Received today | `receivedToday` |
| Received this week | `receivedInPeriod` |
| Queued | `statusCounts.QUEUED ?? 0` |
| Being reviewed | `(statusCounts.NEEDS_REVIEW ?? 0) + (statusCounts.PENDING_APPROVAL ?? 0)` |
| Approved | `statusCounts.APPROVED ?? 0` |
| Failed | `statusCounts.FAILED ?? 0` |
| Gemini calls | `geminiUsage.calls` |
| Tokens reported | `geminiUsage.reportedTotalTokens` |
| "Business days in Asia/Kolkata" note | `timeZone`, `periodFirstDay`, `periodLastDay` |

Available but not on a reference card: `statusCounts.PROCESSING`, `statusCounts.REJECTED`, `batches`,
`recentFailures[]`.

**Reviewer "Today" — `GET /api/dashboard/reviewer?days=1` (REVIEWER)**

| Card | JSON field |
|---|---|
| Needs my review | `assignedStatusCounts.NEEDS_REVIEW ?? 0` (note: `assignedStatusCounts`, not `statusCounts`) |
| Waiting for approval | `assignedStatusCounts.PENDING_APPROVAL ?? 0` |
| Submitted today | `submittedInPeriod` ("today" only because `days=1`) |
| Corrections saved today | `correctionsSavedInPeriod` |
| Oldest waiting since | `oldestWaitingSince` (date-time or `null` → show "—") |

**Admin "Dashboard" — `GET /api/dashboard/admin?days=7` (ADMIN; period selector = `days` 1–90)**

| Card / block | JSON field |
|---|---|
| Received today | `receivedToday` |
| Queued | `statusCounts.QUEUED ?? 0` |
| Extracting | `statusCounts.PROCESSING ?? 0` |
| Waiting (retry / daily reset) | `queue.waitingForRetryOrDailyReset` |
| Failed | `statusCounts.FAILED ?? 0` |
| Needs review | `statusCounts.NEEDS_REVIEW ?? 0` |
| Waiting for approval | `statusCounts.PENDING_APPROVAL ?? 0` |
| Approved | `statusCounts.APPROVED ?? 0` |
| Rejected | `statusCounts.REJECTED ?? 0` |
| Queue and Gemini pacing: Model | `queue.quota.model` |
| Requests last minute | `queue.quota.requestsLastMinute` + `" / " + queue.quota.rpmLimit` when the limit is not `null` |
| Input tokens last minute | `queue.quota.inputTokensLastMinute` / `queue.quota.tpmLimit` |
| Requests since daily reset | `queue.quota.requestsSinceDailyReset` / `queue.quota.rpdLimit` |
| Next daily reset (server time) | `queue.quota.nextDailyReset` |
| Oldest queued since | `queue.oldestQueuedSince` (`null` → "—") |
| Reviewer workload table | `reviewerWorkload[]`: `name`, `NEEDS_REVIEW ?? 0`, `PENDING_APPROVAL ?? 0`; last row is "(not assigned)" |
| Passed all automatic checks | `quality.automaticChecksPassed.rate` (%), "of" `quality.automaticChecksPassed.extracted` |
| Correction rate (fields edited) | `quality.correctionRate.rate` (%), `fieldsChanged` of `fieldsCompared` |
| Median extraction time | `quality.medianExtractionSeconds.value` (`null` → "— (needs 20 calls)") |
| Approved in period | `decisionsInPeriod.APPROVED` |
| Rejected in period | `decisionsInPeriod.REJECTED` |
| Definition bullets under the quality cards | each block's `definition` |
| Gemini usage per day | `geminiUsage.byDayAndModel[]`: `day`, `model`, `calls`, `errors`, `promptTokens`, `outputTokens`, `totalTokens` |
| Gemini usage per vendor | `usageByVendor[]`: `vendorName`, `calls`, `reportedTotalTokens` |
| Worker on/off and why idle (optional status line) | `GET /api/admin/queue/status` → `workerEnabled`, `workerRunning`, `idleReason` |

**Admin "Intake & assignment" — `GET /api/admin/intake` (ADMIN)**

| Card / block | JSON field |
|---|---|
| Needs a reviewer | `counts.needsAssignment` |
| …of which waiting for extraction | `counts.needsAssignmentWaitingForExtraction` |
| Assigned, waiting for extraction | `counts.assignedWaitingForExtraction` |
| Assigned, ready for review | `counts.assignedReadyForReview` |
| "Needs a reviewer" / "Assigned" tables | `needsAssignment[]` / `assigned[]`: `invoiceId`, `fileName`, `batchId`, `vendorName`, `categoryCode`, `status`, `stage` (`WAITING_FOR_EXTRACTION` / `EXTRACTING` / `READY_FOR_REVIEW`), `uploadedAt`, `assignedReviewerId`, `assignedReviewerName`, `extractionAttempts`, `blockedReason` |
| Worker note above the tables | `worker.enabled`, `worker.running`, `worker.idleReason` |

Lists that are not dashboard cards (approvals, failed, decided, reviewer queue) come from `GET /api/invoices?status=…`
(section 11). Never compute a card yourself from a list: lists are paged and have no total.
