# React API setup — Actify Invoice Process (free demo backend)

Companion to `REACT_DEVELOPER_HANDOFF.html`. The full contract is `REACT_BACKEND_HANDOFF.md` and `openapi.yaml`;
if anything here disagrees with them, they win — and please report the difference.

## 1. Create the project (suggested default, not a backend requirement)

React + TypeScript + Vite is a practical default: fast dev server, a built-in proxy (needed, see below) and types
you can check against `openapi.yaml`.

```bash
npm create vite@latest invoice-process-web -- --template react-ts
cd invoice-process-web
npm install
cp ../react-handoff/env.example .env.local        # then fill in API_PROXY_TARGET
cp ../react-handoff/vite.config.example.ts vite.config.ts
npm run dev
```

## 2. Why every call goes to `/api/...` on your own origin

The backend is deliberately **same-origin only**:

- the login cookie `INVOICE_PROCESS_AUTH` is `HttpOnly; Secure; SameSite=Strict; Path=/` — a browser does not send it
  to another site (`*.onrender.com` is on the Public Suffix List, so two onrender.com services are two sites);
- CSRF needs your JavaScript to **read** the `XSRF-TOKEN` cookie — impossible for a page on another origin;
- the backend sends **no CORS headers** (tested). Do not ask for `Access-Control-Allow-Origin: *` or
  `SameSite=None`: that would require a reviewed backend change.

So in development the Vite dev server proxies `/api/*` to the deployed API (`vite.config.example.ts`) and your code
uses relative paths. Postman working is **not** proof that a browser works — always test login in the browser.

**Browser note:** the API marks its cookies `Secure`. Chromium-based browsers accept Secure cookies on
`http://localhost`; other browsers may not. If login returns 200 but the next `GET /api/auth/me` returns 401, run the
dev server over HTTPS (for example with `@vitejs/plugin-basic-ssl`) and open `https://localhost:5173`.

## 3. One API client for the whole app

Use the client in `REACT_DEVELOPER_HANDOFF.html`, section **"The one API client"** — the only maintained copy, so
it cannot drift. It was syntax-checked and its behaviour tested (CSRF header on POST/PUT only, JSON vs `FormData`,
query strings, 204, PDFs as blobs, and a 422 upload rejection keeping its real status and `code`).

Two rules it encodes:

- A 401 is reported to the session handler, which acts **only when the user was logged in**. The 401 from
  `GET /api/auth/me` at app start simply means "show the login screen" — never a redirect loop.
- Upload rejections (422) return a `FileIntakeResponse`, not the usual error body: read `error.body.code`
  (for example `NOT_A_DEMO_FIXTURE`) and `error.body.message`.

Start-up order: `GET /api/auth/me` (200 → route by `role`; 401 → login screen — this call also hands out the
`XSRF-TOKEN` cookie), then `POST /api/auth/login` with `X-XSRF-TOKEN`.

## 4. Types

Generate or hand-write types from `openapi.yaml` (for example `npx openapi-typescript openapi.yaml -o src/api/schema.ts`).
Money and quantities arrive as JSON numbers from Java `BigDecimal`; send them back as **strings**, never do money
maths with JS floats.

## 5. Free-demo facts that affect the UI

- The demo is **synthetic data only**; accounts must end in `@example.test` (the API refuses others with 400).
- Every response carries `X-Invoice-Demo: ephemeral-synthetic-only` — show a small "Demo environment" badge.
- **Uploads accept only the six synthetic fixture PDFs** in `demo-fixtures/`. Any other file gets 422
  `NOT_A_DEMO_FIXTURE` with the neutral `fileName` `file-not-accepted-by-demo.pdf` (match rows by request order).
  Each vendor can upload each fixture once (`DUPLICATE_FILE` after that). Never try a real invoice.
- **No extraction runs in the demo** (no Gemini), so review, submit and approval are **unavailable**: uploaded invoices
  stay `QUEUED` and never reach `NEEDS_REVIEW`. Build those screens against the contract only, label them "not
  available in the demo", and never fake data or success.
- Uploaded PDFs are **ephemeral**: after a restart, redeploy or 15-minute idle spin-down, `GET /api/invoices/{id}/document`
  answers 404. Show "original file no longer available in the demo", not a crash.
- The first request after idle can take about a minute (cold start): show a patient loading state, do not retry in a loop.

## 6. Never

Store credentials or tokens in `localStorage`/`sessionStorage` (the session is the HttpOnly cookie), put secrets in
`VITE_*` variables, invent endpoints or fields, fake success responses, or work around a 401/403/409 by weakening
anything. Report a missing endpoint or an unclear contract to the backend owner instead.
