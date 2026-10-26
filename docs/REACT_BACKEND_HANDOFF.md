# Actify Invoice Process — React ↔ backend handoff

Status: written 2026-10-27 from the source (`controller/*`, `dto/*`, `security/*`, `exception/GlobalExceptionHandler`,
the services they call) and the tests named below. Every route and field here exists in the code. Examples use
synthetic ids and values only. If this file and the code disagree, **the code wins** — please report the difference.

Machine-readable companion: [`openapi.yaml`](openapi.yaml) (OpenAPI 3.0, same routes and schemas).

---

## 0. What the backend is (and is not)

- **API-only Java backend** (Spring Boot 2.7, Java 8 source level, PostgreSQL). It serves **no HTML, CSS or JavaScript**.
  `GET /` and every other unlisted URL answer **401** (no login) or **403** (logged in). The old vanilla-JS pages were
  archived outside the repository; nothing in the backend depends on them.
- The React app is a **separate project**. It must not reimplement any business rule as the *only* check — the
  backend enforces all of them (section 8). React decides what to *show*; the backend decides what is *allowed*.
- Roles: `VENDOR` (uploads), `REVIEWER` (corrects and submits assigned invoices), `ADMIN` (users, categories,
  assignment, decisions, queue). One role per user, stored in the database.

| Item | Value |
|---|---|
| Base URL, local | `http://127.0.0.1:8088` (local demo, bound to loopback only) |
| Base URL, Render | `https://<service>.onrender.com` (see [RENDER_DEPLOYMENT.md](RENDER_DEPLOYMENT.md)) |
| Format | JSON, except file upload (`multipart/form-data`) and the PDF stream (`application/pdf`) |
| Dates | `LocalDate` → `"2026-10-06"`; `LocalDateTime` → `"2026-10-16T11:00:00"` (no zone; stored as UTC wall-clock) |
| Money / quantities | JSON numbers from Java `BigDecimal` (e.g. `12390.00`). Never do money math with JS floats: keep the string form or use a decimal library; send amounts as **strings** in request bodies |
| Business days | dashboards use `Asia/Kolkata`; Gemini's daily quota resets at midnight `America/Los_Angeles` |

---

## 1. Session, cookies, CSRF, CORS

### 1.1 Authentication cookie

| Property | Value | Source |
|---|---|---|
| Name | **`INVOICE_PROCESS_AUTH`** | `AuthCookies.AUTH_COOKIE` |
| Content | HS256-signed JWT: `iss` = `actify-invoice-process`, `aud` = `actify-invoice-process-web`, `sub` = user id, `tv` = token version, `iat`, `exp`, `jti` | `JwtTokenService` |
| Attributes | `HttpOnly; SameSite=Strict; Path=/; Max-Age=1800` (30 min); `Secure` on HTTPS requests and always when `INVOICE_COOKIE_SECURE=true` (always in the `render` profile) | `AuthCookies` |
| Refresh | none — after 30 minutes the next call returns **401**; send the user to login | |
| Role | **not** in the token: read from the database on every request, so role changes / disabling take effect on the next request | `JwtCookieAuthenticationFilter` |

JavaScript can never read this cookie, and the token never appears in a response body. The browser attaches it
automatically on same-origin requests (`fetch` default `credentials: 'same-origin'`).

Rejected (→ **401** + `Set-Cookie: INVOICE_PROCESS_AUTH=; Max-Age=0`): bad signature, other key, `alg=none`, HS512,
expired, wrong issuer/audience, unknown/disabled user, token version changed. Tests: `AuthSecurityWebTest`
(`badCookiesAreRejectedWith401AndDeleted`, `disabledUserAndRevokedTokenVersionAreRejectedOnTheNextRequest`,
`legacyTask8CookieAndLegacyIssuerOrAudienceAreNoLongerAccepted`), `JwtTokenServiceTest`, `JwtCompatibilitySpikeTest`.

**Migration note (2026-10-27):** the earlier cookie `TASK8_AUTH` and tokens with issuer `task8-invoice` / audience
`task8-web` are no longer accepted. Every user logs in once more after this release. An old `TASK8_AUTH` cookie is
simply ignored and expires in the browser by itself (max. 30 minutes after it was issued).

### 1.2 CSRF (every POST / PUT / DELETE, including login)

- Spring Security keeps a readable cookie **`XSRF-TOKEN`** (not HttpOnly, `Path=/`). It is set on the first response
  to a client that has none — **including anonymous calls** such as `GET /api/health` or a `401` from
  `GET /api/auth/me` (test: `ApiOnlySurfaceWebTest.anAnonymousApiCallHandsOutTheReadableCsrfCookie`).
- Every unsafe request must send header **`X-XSRF-TOKEN: <current value of the XSRF-TOKEN cookie>`**.
- Missing / wrong header → **403** `"Missing or invalid CSRF token. Reload the page and try again."` This check runs
  **before** authentication, so an anonymous POST without the header is also 403, not 401.
- The token is **replaced at login and at logout** (both responses carry a new `XSRF-TOKEN`) and otherwise stays
  stable (tests: `AuthSecurityWebTest.loginAndLogoutIssueAFreshCsrfToken`, `CsrfTokenStabilityWebTest`).
  → Always read the cookie **at the moment you send** a request; never cache it in React state across login/logout.
- CSRF is never disabled. Do not ask for that.

```js
// api.js - the single fetch wrapper every component should use
function xsrf() {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : '';
}
export async function api(path, { method = 'GET', json, form, signal } = {}) {
  const headers = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = xsrf();
  if (json !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, { method, headers, signal, credentials: 'same-origin',
    body: json !== undefined ? JSON.stringify(json) : form });
  if (res.status === 401 && path !== '/api/auth/login') onSessionExpired();   // go to the login view
  return res;
}
```

### 1.3 Origin model, CORS and cookies — read before choosing hosting

The backend sends **no CORS headers** and has no CORS configuration (fail-closed). Two reasons:

1. The auth cookie is `SameSite=Strict`: a browser does not send it on requests from another *site*.
   Note that `*.onrender.com` is on the Public Suffix List, so `frontend.onrender.com` and `api.onrender.com` are
   **different sites** — the cookie would not be sent.
2. The CSRF pattern needs JavaScript to **read** the `XSRF-TOKEN` cookie. A page on another origin cannot read a cookie
   of the API's domain, so it could never build the header.

**Supported setup: one origin.** The browser must load React and call `/api/**` on the same scheme + host + port.

| Environment | How |
|---|---|
| Local development | Vite/CRA dev server with a proxy: `/api` → `http://127.0.0.1:8088` (e.g. Vite `server.proxy = { '/api': 'http://127.0.0.1:8088' }`). The browser only ever talks to the dev server's origin. |
| Deployed | Put React and the API behind **one public hostname**: a reverse proxy / CDN rule that serves the React build and forwards `/api/*` to the backend (e.g. a custom domain with path-based routing, or a Render static site whose rewrite rule forwards `/api/*` to the backend service — verify that Render rewrite supports proxying to an external URL with cookies before relying on it). |

**Not supported without a backend change (do not do these):** `Access-Control-Allow-Origin: *`, switching the cookie
to `SameSite=None`, disabling CSRF, allowing all origins. If the product later *requires* a separate API origin, that
is a deliberate backend change: an explicit allow-list of the one frontend origin with credentials, `SameSite=None;
Secure` cookies, and a CSRF token delivered in a response body (because the cookie becomes unreadable). That change
needs its own security review and tests. Until the frontend origin is decided, production stays same-origin only.

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
| 400 | Malformed or invalid request | bad JSON, missing part/parameter, field validation (`fieldErrors`), file too large / not a PDF on the legacy endpoint | show `message` / field errors; do not retry unchanged |
| 401 | Not authenticated | no cookie, expired/revoked/invalid token, disabled user; **login**: wrong e-mail/password (one generic message) | go to login (on the login form show the message) |
| 403 | Authenticated but not allowed, **or CSRF failure** | wrong role for the URL, wrong dashboard, admin editing a workflow invoice, missing `X-XSRF-TOKEN` | if `message` mentions CSRF: re-read the cookie (a GET refreshes it) and let the user retry once; otherwise "not allowed" |
| 404 | Not found **or not yours** (same answer on purpose) | other vendor's batch/invoice/PDF, reviewer not assigned, unknown id | "not found" |
| 409 | Business-rule / state conflict | stale `baseVersion`/`version`, wrong status for the action, closed/full batch, duplicate e-mail, legacy extract switched off | reload the object, show `message`; never resend the same body blindly |
| 415 | Wrong `Content-Type` | developer error | fix the request |
| 422 | Rule rejected the content | batch file rejected (`FileIntakeResponse.code`), submit while current data fails a check or duplicates an earlier invoice of the same vendor, legacy extraction result failed validation | show `code`/`message`/`fieldErrors`; do not retry unchanged |
| 429 | Temporarily refused | login locked (5 failures → 15 min); Gemini quota (legacy extract only) | show message; retry later |
| 502 | Provider failure | Gemini call failed (legacy `POST /api/invoices/extract` only) | show message; retry later |
| 500 | Unexpected server error | includes database failures and a stored PDF failing its SHA-256 check | "something went wrong (reference …)" |

Duplicate invoices: a re-uploaded **identical file** of the same vendor is refused at intake (422 `DUPLICATE_FILE`); the
**same invoice number** from the same vendor in a different PDF is flagged after extraction as a review reason and blocks
submit (422). Duplicates are never computed across vendors. Database constraint failures that are not mapped to a
specific message surface as a generic 500 with `errorId`. Provider (Gemini) failures in the **batch workflow** never
produce an HTTP error: they appear as invoice status `QUEUED` (waiting / retry scheduled) or `FAILED` with a
`failureReason`.

---

## 3. Endpoint reference (47 routes)

Legend — **Auth**: `public` / `login` / role list (checked by `SecurityConfig`: 401 without login, 403 wrong role).
**Scope**: per-object rule (`InvoiceAccessPolicy`, services) — failing it gives **404**. **CSRF**: header required.
"Tests" lists the test classes that exercise the route over HTTP.

### 3.1 Health and session

| # | Method & path | Handler | Auth | CSRF | Request | Success | Errors | Tests |
|---|---|---|---|---|---|---|---|---|
| 1 | `GET /api/health` | `HealthController.health` | public | – | – | 200 `{"status":"UP"}`, `Cache-Control: no-store` | – | `ApiOnlySurfaceWebTest` |
| 2 | `POST /api/auth/login` | `AuthController.login` | public | yes | `LoginRequest {email, password}` | 200 `Me` + `Set-Cookie INVOICE_PROCESS_AUTH` + new `XSRF-TOKEN` | 401 generic (wrong/unknown/disabled, password > 72 bytes), 429 locked, 403 CSRF, 415 | `AuthSecurityWebTest`, `AuthPgIntegrationTest` |
| 3 | `POST /api/auth/logout` | `AuthController.logout` | public (works with an expired session) | yes | – | 204, auth cookie deleted, new `XSRF-TOKEN` | 403 CSRF | `AuthSecurityWebTest` |
| 4 | `POST /api/auth/logout-all` | `AuthController.logoutEverywhere` | login | yes | – | 204; token version +1 → **every** token of this user stops working | 401, 403 CSRF | `AuthSecurityWebTest` |
| 5 | `GET /api/auth/me` | `AuthController.me` | login | – | – | 200 `Me` | 401 | `AuthSecurityWebTest`, `AuthPgIntegrationTest` |

Side effects: login records failures in an in-memory limiter (5 failures per e-mail **or** per client address → 15-min
lock; resets on restart); logout-all writes `app_users.token_version`.

### 3.2 Vendor batches and cloud sources (VENDOR, ADMIN)

| # | Method & path | Handler | Scope | CSRF | Request | Success | Errors | Tests |
|---|---|---|---|---|---|---|---|---|
| 6 | `POST /api/batches` | `BatchController.create` | creator owns it | yes | `CreateBatchRequest {label?, defaultCategoryCode?, source?}` — `source` ∈ `LOCAL` (default) / `GOOGLE_DRIVE` / `ONEDRIVE`; label ≤ 255 | 201 `BatchSummary` | 400 unknown source / cloud source not configured / unknown category | `BatchQueuePgIntegrationTest`, `BatchRouteProtectionWebTest` |
| 7 | `GET /api/batches` | `BatchController.list` | vendor: own; admin: all | – | – | 200 `BatchSummary[]` newest first | – | same |
| 8 | `GET /api/batches/{id}` | `BatchController.get` | owner or admin | – | – | 200 `BatchDetail` | 404 | same |
| 9 | `POST /api/batches/{id}/files` | `BatchController.addFile` | owner or admin | yes | multipart: `file` (one PDF, required), `relativePath?` (folder uploads), `source?` | **202** accepted / **422** rejected — both `FileIntakeResponse` | 400 no file / > 10 MB, 404, 409 batch closed or 100 files reached | `BatchQueuePgIntegrationTest`, `IntakeAssignmentPgIntegrationTest`, `BatchRouteProtectionWebTest` |
| 10 | `POST /api/batches/{id}/close` | `BatchController.close` | owner or admin | yes | – | 200 `BatchSummary` (`CLOSED`) | 404, 409 | `BatchQueuePgIntegrationTest` |
| 11 | `GET /api/cloud-sources` | `CloudSourceController.list` | – | – | – | 200 one entry per provider (below) | – | `BatchRouteProtectionWebTest`, `CloudSourceSettingsTest` |

File rules (server-side, identical for every source): non-empty, ≤ 10 MB (`multipart` max request 11 MB), name ends
with `.pdf`, content type `application/pdf` / `application/x-pdf` / `application/octet-stream`, bytes start with
`%PDF-`. Rejection codes (422): `INVALID_FILE`, `DUPLICATE_FILE` (same SHA-256 already uploaded **by this vendor**, any
batch — another vendor's identical file is neither blocked nor revealed), `NESTED_FOLDER` (file in a sub-folder of a
folder upload), `SOURCE_MISMATCH` (declared `source` ≠ batch source). Max **100 accepted files per batch**.

Side effects of a 202: the PDF is written to private storage under a random UUID (SHA-256 verified after writing),
`invoice_documents` + `invoices` (`QUEUED`) + `extraction_jobs` rows are inserted in one transaction (the file is deleted
again if that transaction fails), and the batch counters change. **Nothing calls Gemini during upload.**

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

| # | Method & path | Handler | Auth / scope | CSRF | Request | Success | Errors | Tests |
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

| # | Method & path | Handler | CSRF | Request | Success | Errors / notes | Tests |
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
| 43 | `GET /api/admin/queue/status` | `AdminQueueController.status` | – | – | 200 `{workerEnabled, workerRunning, geminiKeyConfigured, onlyInvoiceIds, idleReason, quota}` | `geminiKeyConfigured` = presence only | `DailyQuotaPgIntegrationTest` |
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
until an audit sample exists). Tests: `DashboardPgIntegrationTest`, `DashboardMetricsTest`.

---

## 4. Schemas (response shapes)

```jsonc
// Me (login, /api/auth/me)
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
`X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `X-Frame-Options: SAMEORIGIN`. Same 404 rule as the
invoice. Because the frame rule is `SAMEORIGIN`, embed it in an `<iframe>`/`<object>` **only from the same origin**
(section 1.3); offer "Open in new tab" as a fallback. Bytes are re-checked against the stored SHA-256 on every read
(mismatch → 500, never served). The PDF never changes when a reviewer saves a correction.

## 7. Implementing the React app

1. **Start-up:** `GET /api/auth/me` → 200: route by `role`; 401: show login (this response also sets `XSRF-TOKEN`).
2. **Login:** `POST /api/auth/login` `{email, password}` with `X-XSRF-TOKEN` → 200 `Me` → route by `role`. 401 →
   generic "wrong e-mail or password". 429 → show the lock message. Never store the password; the token is never visible.
3. **Logout:** `POST /api/auth/logout` (CSRF). "Log out everywhere": `POST /api/auth/logout-all`. Clear all cached data.
4. **Stale session:** any 401 outside login → clear state, show login with "your session ended", then return the user to
   where they were. A 403 with the CSRF message → do one `GET /api/auth/me` and let the user retry the action.
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
404 otherwise); CSRF; file type/size/signature checks, SHA-256 and per-vendor duplicate detection; batch limits (100
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
- Cross-origin hosting is not supported (section 1.3).
- Cloud pickers: frontend work, not live-tested.

## 10. Compatibility history (still relevant to existing clients)

| Change | Effect |
|---|---|
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
