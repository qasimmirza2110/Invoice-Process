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

```ts
// src/api/client.ts — every request goes through here (never raw fetch in components)
export type ApiError = {
  status: number; error: string; message: string; path: string;
  fieldErrors?: { field: string; message: string }[] | null; errorId?: string;
};

const BASE = import.meta.env.VITE_API_BASE_URL ?? '';   // keep EMPTY (same origin)

function xsrfToken(): string {
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : '';            // read at send time - it changes at login/logout
}

export class HttpError extends Error {
  constructor(public readonly body: ApiError) { super(body.message); }
}

let onSessionExpired: () => void = () => {};
export function setSessionExpiredHandler(handler: () => void) { onSessionExpired = handler; }

export async function api<T>(path: string, init: { method?: string; json?: unknown; form?: FormData;
                                                   signal?: AbortSignal } = {}): Promise<T> {
  const method = init.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = xsrfToken();
  if (init.json !== undefined) headers['Content-Type'] = 'application/json';
  // multipart: do NOT set Content-Type yourself - the browser adds the boundary.
  const res = await fetch(BASE + path, {
    method, headers, signal: init.signal, credentials: 'same-origin',
    body: init.json !== undefined ? JSON.stringify(init.json) : init.form,
  });
  if (res.status === 204) return undefined as T;
  const isJson = (res.headers.get('Content-Type') ?? '').includes('application/json');
  if (!res.ok) {
    const body: ApiError = isJson ? await res.json()
      : { status: res.status, error: res.statusText, message: 'Unexpected response', path };
    if (res.status === 401 && path !== '/api/auth/login') onSessionExpired();
    throw new HttpError(body);
  }
  return (isJson ? res.json() : res.blob()) as Promise<T>;
}
```

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
