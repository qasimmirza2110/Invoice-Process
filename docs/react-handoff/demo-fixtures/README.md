# Free-demo upload fixtures (synthetic)

The temporary free demo (`render-free-demo`) accepts **only these six files** for upload
(`POST /api/batches/{id}/files`). Any other file is rejected with HTTP 422 and code `NOT_A_DEMO_FIXTURE` before it is
stored, and it is recorded under the neutral name `file-not-accepted-by-demo.pdf` (its own name is never kept).

| File | Invoice number | Content |
|---|---|---|
| `synthetic-demo-invoice-01.pdf` … `-06.pdf` | `DEMO-0001` … `DEMO-0006` | one page, "SYNTHETIC DEMO INVOICE - NOT A REAL DOCUMENT", a synthetic seller/buyer, one line item, INR totals |

- Every vendor account can upload each file **once** (the normal per-vendor `DUPLICATE_FILE` rule still applies), so
  two demo vendors give twelve accepted uploads per database lifetime. The Free database expires after 30 days anyway.
- Do not edit these files: their SHA-256 values are the allowlist in `application-render-free-demo.properties`, and
  `FreeDemoFixtureTest` fails if a file is changed, added or removed. A change needs a backend change and review.
- Never upload a real invoice to the demo, and never add a real document here.
- Outside the free demo these files are ordinary PDFs; production upload rules do not know this list.
