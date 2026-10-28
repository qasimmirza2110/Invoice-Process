# React API setup — Actify Invoice Process

Companion to `REACT_DEVELOPER_HANDOFF.html`. The full contract is `REACT_BACKEND_HANDOFF.md` and `openapi.yaml`;
if anything here disagrees with them, they win — and please report the difference.

## 1. Create the project (suggested default, not a backend requirement)

React + TypeScript + Vite is a practical default: fast dev server, a built-in proxy and types you can check against
`openapi.yaml`.

```bash
npm create vite@latest invoice-process-web -- --template react-ts
cd invoice-process-web
npm install
cp ../react-handoff/env.example .env.local        # then fill in API_PROXY_TARGET
cp ../react-handoff/vite.config.example.ts vite.config.ts
npm run dev
```

## 2. How authentication works

1. `POST /api/auth/login` with `{"email": "...", "password": "..."}` → `200`:
   `{"accessToken": "...", "tokenType": "Bearer", "expiresIn": 1800, "expiresAt": "...", "user": {id, email, name, displayName, role}}`.
2. Keep `accessToken` **in memory only** (a module variable or React context) — not `localStorage`/`sessionStorage`.
   A page reload therefore logs the user out; that is intended.
3. Send it on every other call: `Authorization: Bearer <accessToken>`. Nothing else is needed — **no cookie, no CSRF
   header**. The backend reads the token only from this header.
4. Any `401` while logged in = token expired (30 min), revoked or account disabled → forget the token, show login.
5. Logout = forget the token. "Log out everywhere" = `POST /api/auth/logout-all` (revokes every token of the user).

## 3. Why calls go to `/api/...` on your own origin (dev proxy)

In development the Vite dev server forwards `/api/*` to the API (`vite.config.example.ts`), and your code uses
relative paths. That avoids CORS entirely, and CORS is **closed by default** on the backend. If the React app is later
hosted on its own origin, the backend owner can allow that exact origin (`INVOICE_CORS_ALLOWED_ORIGINS`); then set
`VITE_API_BASE_URL` to the API URL. Never ask for `*`.

| Mode | `.env.local` | Needs from the backend owner |
|---|---|---|
| Dev proxy (use this) | `API_PROXY_TARGET=https://invoice-process.onrender.com`, `VITE_API_BASE_URL=` (empty) | nothing |
| Direct calls | `VITE_API_BASE_URL=https://invoice-process.onrender.com` | your exact origin in `INVOICE_CORS_ALLOWED_ORIGINS` — none was set on 2026-10-28 |

Dashboard cards and the exact JSON field behind each one: `REACT_DEVELOPER_HANDOFF.html`, section
"Dashboard cards → JSON field" (missing status keys mean 0).

## 4. One API client for the whole app

Use the client in `REACT_DEVELOPER_HANDOFF.html`, section **"The one API client"** — the only maintained copy, so it
cannot drift. It adds the Bearer header when a token exists, sends JSON or `FormData` (never set the multipart
`Content-Type` yourself — the browser adds the boundary), parses the one error format, returns PDFs as blobs, and on a
401 forgets the token and calls the session handler.

Upload rejections (422) return a `FileIntakeResponse`, not the usual error body: read `error.body.code`
(`INVALID_FILE`, `DUPLICATE_FILE`, `NESTED_FOLDER`, `SOURCE_MISMATCH`) and `error.body.message`.

**PDF preview:** the PDF route needs the token too, so fetch it with the client and show
`URL.createObjectURL(blob)` in an `<iframe>`/`<object>` (revoke the URL when leaving). A plain
`<iframe src="/api/...">` would get a 401.

## 5. Types

Generate or hand-write types from `openapi.yaml` (for example `npx openapi-typescript openapi.yaml -o src/api/schema.ts`).
Money and quantities arrive as JSON numbers from Java `BigDecimal`; send them back as **strings**, never do money
maths with JS floats.

## 6. Facts that affect the UI

- **Any valid invoice PDF** up to 10 MB is accepted, one file per request, up to 100 per batch. Damaged or
  password-protected PDFs → 422 `INVALID_FILE`. Six synthetic samples for trying the screen: `sample-invoices/`.
- An accepted upload is `QUEUED` ("accepted and waiting"), **not** extracted. Extraction runs only where the backend
  owner has enabled it with a paid Gemini project; otherwise invoices stay `QUEUED` and the admin's
  `GET /api/admin/queue/status` explains why. Never fake data or success.
- It runs on free plans (Render Free + Neon Free): the first request after idle can take about a minute - show a
  patient loading state. No uptime promise; demo data only. If the PDF bucket is unreachable an upload answers 500
  ("Could not store the original PDF") and nothing was accepted.
- `GET /` on the API answers 401 by design (API-only). The health check is `GET /api/health`.

## 7. Never

Store tokens or passwords in `localStorage`/`sessionStorage`, URLs or logs; put secrets in `VITE_*` variables; invent
endpoints or fields; fake success responses; or work around a 401/403/409 by weakening anything. Report a missing
endpoint or an unclear contract to the backend owner instead.
