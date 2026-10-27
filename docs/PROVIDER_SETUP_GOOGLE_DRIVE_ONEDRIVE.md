# Google Drive and OneDrive — owner setup guide

**Status (2026-10-23): IMPLEMENTED, MOCK-TESTED ONLY — OWNER CONFIGURATION REQUIRED.** No Google Cloud project,
Google OAuth client, Microsoft Entra app registration or real account has been used with this app yet. Until you
finish the steps below *and* an approved live test passes, the vendor screen correctly says **Not configured** (or
**Configured – not yet tested with a real account**). Uploading from **This computer** works either way.

This is the one setup guide for both providers. The backend design is in
[BACKEND_REVERSE_ENGINEERING_RUNBOOK.md §12](BACKEND_REVERSE_ENGINEERING_RUNBOOK.md); the API used by every source is in
[REACT_BACKEND_HANDOFF.md §3.2 and §7](REACT_BACKEND_HANDOFF.md).

> **Backend-only repository (2026-10-27).** The browser-side picker code described below (`cloud-pickers.js`, the
> self-hosted MSAL bundle, `cloud-auth-redirect.html`, the vanilla-JS test harness and `VendoredScriptIntegrityTest`) was
> archived outside this repository together with the old UI. The provider setup steps, the backend settings
> (`INVOICE_GOOGLE_*`, `INVOICE_ONEDRIVE_*`, `GET /api/cloud-sources`) and the security design remain valid; the picker
> itself must be re-implemented in the React frontend, and redirect URIs must point at the frontend's own redirect page.

---

## 1. How it works (same for both providers)

1. The vendor starts a batch and picks its source (`POST /api/batches` with `source` = `LOCAL`, `GOOGLE_DRIVE` or
   `ONEDRIVE`). One batch has one source.
2. The browser signs the vendor in to **their own** Google / Microsoft account (popup, delegated, read-only) and opens
   the provider's own file picker (PDFs only, several at once).
3. The browser downloads each chosen file with that short-lived token, **one at a time**, then sends the bytes to
   `POST /api/batches/{id}/files` — the same request a local file uses.
4. The server treats the bytes like any upload: PDF signature check, 10 MB per file, 100 files per batch, its own
   SHA-256, the vendor's duplicate check (all of that vendor's batches, under a per-vendor lock), private storage
   under a random name, then the extraction queue. File names, ids and sizes from the provider are only shown to the
   user; they prove nothing.
5. The provider token stays in a JavaScript variable (Google) or MSAL `memoryStorage` (Microsoft). It is never put in
   `localStorage`/`sessionStorage`, a URL, a log, our database or a request to our server, and is dropped when the
   batch is finished. There is **no client secret** anywhere: both are public browser clients.

Setting up a provider does **not** enable Gemini. Extraction stays off in the local demo; uploads wait as
"Waiting for extraction" until Gemini is separately configured and approved.

## 2. Where the values go

All values are read from environment variables of the **process that runs the app** (Eclipse: *Run Configurations →
Invoice Process - localdemo → Environment*). Do **not** set them as Windows user/machine variables, and never commit
them. `.env.example` lists the names only.

| Variable | Provider | What it is | Secret? |
|---|---|---|---|
| `INVOICE_GOOGLE_CLIENT_ID` | Google | OAuth 2.0 **Web application** client id, ends with `.apps.googleusercontent.com` | no (public, but restrict it) |
| `INVOICE_GOOGLE_API_KEY` | Google | browser API key | no (visible in the page) — **must be restricted** |
| `INVOICE_GOOGLE_APP_ID` | Google | Cloud **project number** (digits only) | no |
| `INVOICE_ONEDRIVE_ACCOUNT_TYPE` | Microsoft | `PERSONAL` or `WORK_OR_SCHOOL` — your decision, see §4.1 | no |
| `INVOICE_ONEDRIVE_CLIENT_ID` | Microsoft | Application (client) id GUID | no |
| `INVOICE_ONEDRIVE_TENANT_ID` | Microsoft | Directory (tenant) id GUID — `WORK_OR_SCHOOL` only | no |
| `INVOICE_ONEDRIVE_SHAREPOINT_BASE_URL` | Microsoft | `https://<tenant>-my.sharepoint.com` — `WORK_OR_SCHOOL` only | no |
| `INVOICE_GOOGLE_LIVE_TESTED`, `INVOICE_ONEDRIVE_LIVE_TESTED` | both | set to `true` **only** after an approved live test passed | no |

Everything the browser receives is public by nature (anyone can read it in DevTools), which is why every value is
restricted on the provider side. `GET /api/cloud-sources` (VENDOR/ADMIN only) returns these public values only when
they are complete and valid; otherwise it returns `NOT_CONFIGURED` with the list of what is missing.

After changing variables, restart the app (from Eclipse — see runbook §15/§16a) and open the vendor screen: the status
line under "Files from" must change from *Not configured* to *Configured – not yet tested with a real account*.

## 3. Google Drive

### 3.1 Steps in the Google Cloud console

1. Create or choose a Google Cloud project. Note its **project number** (Dashboard → Project info) →
   `INVOICE_GOOGLE_APP_ID`.
2. *APIs & Services → Library*: enable **Google Picker API** and **Google Drive API**.
3. *Google Auth platform* (OAuth consent screen): user type **External** (or Internal for a Workspace org), app name,
   support e-mail. While the app is in **Testing**, only the Google accounts you add under **Test users** can sign in.
   The only scope the app requests is `https://www.googleapis.com/auth/drive.file` (non-sensitive), so no Google
   verification is needed for testing.
4. *Clients → Create client → Web application*. **Authorized JavaScript origins:** `http://127.0.0.1:8088` exactly
   (scheme + host + port, no path; Google allows plain `http` for loopback addresses). Do not add `localhost` unless
   you also open the app as `http://localhost:8088`. No redirect URI is needed (token popup flow). Copy the client id
   → `INVOICE_GOOGLE_CLIENT_ID`. **Do not create or download a client secret for this app.**
5. *Credentials → Create credentials → API key* → `INVOICE_GOOGLE_API_KEY`. Then **restrict it**:
   - Application restriction **Websites**: `http://127.0.0.1:8088/*` **and** `https://docs.google.com/*` (the Picker
     runs in an iframe on docs.google.com; without it the Picker says "API developer key is invalid").
   - API restriction: **Google Picker API** and **Google Drive API** only (Google's guide: Picker, plus Drive when
     the page calls Drive directly — the download does, with the OAuth token).
6. Later, for a deployed HTTPS address, add that exact origin to steps 4 and 5 in a separately approved change.

### 3.2 What `drive.file` means for the user

`drive.file` is the least-privilege Drive scope: the app can read **only the files the user picks in the Picker**
(the Picker grants them to the app id from step 1). It cannot list or crawl the rest of the Drive, and folders cannot
be picked (folder import is not implemented). A file the user cannot open in Drive cannot be picked either.

### 3.3 What the browser does

`apis.google.com/js/api.js` (Picker) and `accounts.google.com/gsi/client` (Google Identity Services, token model) are
loaded from Google when the vendor first clicks "Choose from Google Drive" — Google serves these only from its own
hosts and does not publish fixed versions, so they cannot carry an integrity hash. Picker: `DocsView`, PDF only,
list mode, multi-select up to the batch's remaining capacity. Download per file:
`GET https://www.googleapis.com/drive/v3/files/{id}?alt=media` with the token, 2-minute timeout.

## 4. Microsoft OneDrive (File Picker v8)

### 4.1 Decide the account type first (your decision — nothing is guessed)

| `INVOICE_ONEDRIVE_ACCOUNT_TYPE` | Who can sign in | Entra "Supported account types" | Picker address |
|---|---|---|---|
| `PERSONAL` | personal Microsoft accounts (outlook.com, hotmail, OneDrive personal) | **Personal Microsoft accounts only** | `https://onedrive.live.com/picker` (authority `https://login.microsoftonline.com/consumers`) |
| `WORK_OR_SCHOOL` | accounts of **one** organisation (OneDrive for Business) | **Accounts in this organizational directory only** | `https://<tenant>-my.sharepoint.com/_layouts/15/FilePicker.aspx` (authority `https://login.microsoftonline.com/<tenant id>`) |

**One mode per running app.** Supporting both at once (and multi-tenant) is not implemented and not claimed.

### 4.2 Steps in the Microsoft Entra admin center

1. *App registrations → New registration*: name, supported account type from §4.1. Copy **Application (client) ID**
   → `INVOICE_ONEDRIVE_CLIENT_ID`; for `WORK_OR_SCHOOL` also **Directory (tenant) ID** → `INVOICE_ONEDRIVE_TENANT_ID`.
2. *Authentication → Add a platform → **Single-page application***. Redirect URI:
   `http://127.0.0.1:8088/cloud-auth-redirect.html`. The portal's text box refuses `http` with `127.0.0.1`
   (Microsoft: "Prefer 127.0.0.1 over localhost", but plain-http loopback must be added in the **Manifest**): open
   *Manifest* and add to `replyUrlsWithType`
   `{ "url": "http://127.0.0.1:8088/cloud-auth-redirect.html", "type": "Spa" }`. Leave *implicit grant* (access and
   ID tokens) **unchecked**: MSAL.js v3 uses the authorization-code flow with PKCE for SPAs.
3. **No client secret / certificate.** It is a public client; a secret in a browser would be public.
4. *API permissions* — delegated, read only:
   - `PERSONAL`: Microsoft Graph **Files.Read** (the app asks for `OneDrive.ReadOnly`, as Microsoft's consumer
     configuration says).
   - `WORK_OR_SCHOOL`: SharePoint **MyFiles.Read** (the minimum for the user's own OneDrive). Keep Graph
     `User.Read`. Add nothing with *Write*/*ReadWrite* — the picker would then show upload buttons.
   - Organisations often require **admin consent**: a tenant administrator presses *Grant admin consent*. Without it the
     sign-in shows "Need admin approval".
5. `WORK_OR_SCHOOL` only: `INVOICE_ONEDRIVE_SHAREPOINT_BASE_URL=https://<tenant>-my.sharepoint.com` (your OneDrive
   for Business address, no path).

### 4.3 What the browser does

- MSAL: `@azure/msal-browser` 3.27.0, **served by this app** from `js/vendor/msal-browser-3.27.0/` (Microsoft no longer
  hosts v3+ on a CDN and recommends self-hosting the npm build). The file is the unchanged npm build; its provenance,
  registry checksum and licence are in that folder, and the page loads it with Subresource Integrity
  (`VendoredScriptIntegrityTest` fails if file and hash disagree). `cacheLocation: memoryStorage`.
- Two clicks by design: click 1 signs in (popup), click 2 opens the picker popup — browsers block a second popup
  that was not opened directly by a click.
- Picker v8: the app POSTs the picker configuration (PDF filter, multi-select, our origin and a random channel id) to
  the picker address in §4.1 and talks to it over `postMessage` + a `MessagePort`. It accepts the `initialize` message
  only from the window it opened, only from an `https` Microsoft host and only with its own channel id; only
  well-formed `command` messages are answered (`authenticate` → a token for the requested resource, `pick`, `close`;
  anything else → `unsupportedCommand`). Listeners, the port and the popup are closed when the picker finishes.
- Download per picked item: `{@sharePoint.endpoint}/drives/{driveId}/items/{id}/content` (Microsoft picker docs),
  `https` only, with a token for that endpoint, 2-minute timeout.

## 5. Testing it — one gate at a time

1. **Offline (already done):** `./mvnw -o test` and `node src/test/frontend/invoice-review-ui.e2e.mjs` — mocks only,
   no Google/Microsoft call.
2. **Live Google test:** after §3 is done, reply `APPROVE GOOGLE PICKER LIVE TEST`. Use **one harmless synthetic
   PDF** in your Drive. Expected: the row says "Accepted as invoice N - waiting for extraction"; the stored SHA-256
   (`GET /api/invoices/N` → `sourceDocument.sha256`) equals `certutil -hashfile <the file> SHA256` of the original.
   Afterwards remove the app's access: <https://myaccount.google.com/connections>.
3. **Live OneDrive test:** separately, `APPROVE ONEDRIVE PICKER LIVE TEST`, same checks. Afterwards remove consent:
   <https://account.live.com/consent/Manage> (personal) or *My Apps* (work/school).
4. Only after a passing live test set `INVOICE_*_LIVE_TESTED=true`. Never paste tokens, keys or secrets into chat.

## 6. Troubleshooting

| What you see | Likely cause | What to do |
|---|---|---|
| "Not configured" (hover shows what is missing) | a variable missing or malformed | fix the variable in the Eclipse launch, restart |
| Google: "API developer key is invalid" | `https://docs.google.com/*` missing from the key's website list, or Picker API not allowed | §3.1 step 5 |
| Google: `idpiframe_initialization_failed` / origin mismatch | the page is opened at a different origin than registered | open exactly `http://127.0.0.1:8088` |
| Google: "Access blocked: app has not completed verification" | your account is not a test user | add it under Test users |
| Microsoft: `AADSTS50011` reply URL mismatch | redirect URI missing or not type `Spa` | §4.2 step 2 (manifest) |
| Microsoft: "Need admin approval" | tenant requires admin consent | §4.2 step 4 |
| Microsoft: `AADSTS50020` / wrong account type | e.g. a personal account against a `WORK_OR_SCHOOL` app | sign in with the right account, or change §4.1 |
| "The … window was blocked" | browser blocked the popup | allow popups for `127.0.0.1:8088`, click again |
| "No files chosen." | picker cancelled | nothing to do |
| Row "Could not download …: sign-in expired" | token expired or revoked (HTTP 401) | choose the files again |
| Row "… no permission to read this file" (403) / "file no longer exists" (404) | the file is not readable by that account | pick another file |
| Row "… too many requests" (429) / "timed out" / "network error" | provider rate limit or network | **Retry failed files** (accepted files are never resent) |
| Row "Rejected: File content is not a valid PDF" (422 `INVALID_FILE`) / "larger than 10 MB" | server checks | expected |
| Row "Rejected: You already uploaded this exact file." (422 `DUPLICATE_FILE`) | the same bytes are already in one of **your** batches | expected; another vendor's file never causes this |
| Row "Not saved: … (reference 1a2b3c4d in the server log)" | unexpected server error | search the server log for the reference; then Retry |

## 7. References (checked 2026-10-23; official documentation only)

| Source | Used for |
|---|---|
| Google Picker web guide — developers.google.com/workspace/drive/picker/guides/web-picker | scripts, App ID, token, `drive.file`, API-key website restriction incl. `docs.google.com`, list mode without thumbnails |
| Google Drive API downloads — developers.google.com/workspace/drive/api/guides/manage-downloads | `files.get` with `alt=media` |
| Google Cloud "Manage OAuth clients" — support.google.com/cloud/answer/15549257 | JavaScript origins: https required except loopback, no path |
| Microsoft OneDrive File Picker v8 — learn.microsoft.com/onedrive/developer/controls/file-pickers/ | POST form, messaging protocol, commands, permissions table, consumer configuration, download URL |
| OneDrive samples `javascript-basic-consumer` — github.com/OneDrive/samples | consumer picker address `https://onedrive.live.com/picker` with nothing appended |
| MSAL browser CDN usage — github.com/AzureAD/microsoft-authentication-library-for-js (lib/msal-browser/docs/cdn-usage.md) | v3+ not on the CDN; self-host `lib/msal-browser.min.js` |
| Entra redirect URI rules — learn.microsoft.com/entra/identity-platform/reply-url | http only for loopback; 127.0.0.1 via manifest; SPA platform |
| OWASP File Upload / Secrets Management cheat sheets | server-side checks, no secrets in browser code or Git |
