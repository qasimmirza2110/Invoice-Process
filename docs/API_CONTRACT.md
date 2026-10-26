# Invoice Process — API contract (moved)

This file was the API contract for the frontend developer until 2026-10-27. Its content was merged, re-checked against
the code and extended in:

- **[REACT_BACKEND_HANDOFF.md](REACT_BACKEND_HANDOFF.md)** — every route, JSON shape, error, cookie/CSRF rule, origin
  model and React flow (the single source for frontend work).
- **[openapi.yaml](openapi.yaml)** — the same contract in OpenAPI 3.0 form.

Changes since the last version of this file: the backend is API-only (the old HTML pages are gone, `GET /` answers
401/403), `GET /api/health` was added, and the login cookie is now `INVOICE_PROCESS_AUTH` with JWT issuer
`actify-invoice-process` / audience `actify-invoice-process-web` (users log in once more after the change).
The earlier text remains available in Git history.
