# Invoice Process — API contract (moved)

This file was the API contract for the frontend developer until 2026-10-27. Its content was merged, re-checked against
the code and extended in:

- **[REACT_BACKEND_HANDOFF.md](REACT_BACKEND_HANDOFF.md)** — every route, JSON shape, error, Bearer-token rule, origin
  model and React flow (the single source for frontend work).
- **[openapi.yaml](openapi.yaml)** — the same contract in OpenAPI 3.0 form.

Changes since the last version of this file: the backend is API-only (the old HTML pages are gone, `GET /` answers
401/403), `GET /api/health` was added, and (2026-10-28) authentication is an `Authorization: Bearer` access token returned by
`POST /api/auth/login` — no cookie and no CSRF header any more (JWT issuer `actify-invoice-process`, audience
`actify-invoice-process-web`).
The earlier text remains available in Git history.
