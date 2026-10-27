# Gemini Free Tier model comparison — Invoice Process extraction

**Status: PLAN READY — live comparison NOT executed (awaiting explicit approval).**
No measured numbers exist yet. Every result cell below says `not run`. Nothing on this page is a
measured token count, latency or accuracy figure until the "Observed results" table is filled in from a
real, approved run.

Documentation checked: **2026-10-12**. Production default model: `gemini-3.5-flash-lite` (unchanged).

---

## 1. Official documentation facts (checked 2026-10-12)

Sources: [Models](https://ai.google.dev/gemini-api/docs/models) (updated 2026-10-09),
[Pricing](https://ai.google.dev/gemini-api/docs/pricing) (2026-10-09),
[Rate limits](https://ai.google.dev/gemini-api/docs/rate-limits) (2026-10-09),
[Document processing](https://ai.google.dev/gemini-api/docs/document-processing) (2026-10-08),
[GenerateContent API](https://ai.google.dev/api/generate-content), and the three model pages.

| | `gemini-3.5-flash-lite` | `gemini-3.1-flash-lite` | `gemini-3.8-flash` |
|---|---|---|---|
| Status on Models page | Stable | Stable | New Stable |
| Google's description | "optimized for high-throughput, low-cost execution for subagent tasks and document parsing" | "low-latency, cost-effective multimodal model optimized for high-frequency, lightweight tasks" | "our most intelligent Flash model, engineered for long-horizon software engineering" |
| PDF input | Yes | Yes | Yes |
| Structured outputs | Supported | Supported | Supported |
| Thinking | Supported | Supported | Supported (low/medium/high; `minimal` returns an error) |
| Input / output token limit | 1,048,576 / 65,536 | 1,048,576 / 65,536 | 1,048,576 / 65,536 |
| Free Tier input / output price | Free of charge | Free of charge | Free of charge |
| Paid price per 1M tokens (in / out) — for reference only, billing is NOT enabled | $0.30 / $2.50 | $0.25 / $1.50 | $0.75 / $3.75 (through 2026-12-31) |
| Model page last updated | 2026-07-30 | 2026-07-21 | 2026-09-02 |

Other documented facts relevant to this app:

- **Rate limits** apply per *project* (not per key) along RPM, input TPM and RPD; RPD resets at
  midnight Pacific. The rate-limit page publishes **no** Free Tier numbers for these models — the
  active values must be read in AI Studio for this project. We do not quote any number here.
- **"Free of charge" is not "unlimited"**, and a price listing does **not** prove the model is enabled
  for this particular project.
- **Free Tier data use:** the pricing page says Free Tier content is "used to improve our products".
  Only the synthetic fixture is used for this comparison.
- **PDF processing:** up to 50 MB / 1,000 pages (inline or Files API). Native text is extracted; pages
  are also seen as images (~258 tokens per page, counted under the `IMAGE` modality); native-text tokens
  are not charged. Google advises correct page rotation and avoiding blurry pages. The 258 figure is
  documentation, **not** the measured cost of our request.
- **Usage metadata:** `GenerateContentResponse.usageMetadata` has `promptTokenCount`,
  `candidatesTokenCount`, `thoughtsTokenCount`, `totalTokenCount` ("prompt + thoughts + response
  candidates"), plus cached/tool-use counts and per-modality details; `modelVersion` is a top-level
  response field. `countTokens` is a separate call that counts **input only**.
- Model pages do **not** list the REST methods a model supports. This app calls
  `POST /v1beta/models/{model}:generateContent`.

## 2. Eligibility for THIS project

| Model | Documented as Free Tier | Enabled for this AI Studio project | Works with our request shape | Call it? |
|---|---|---|---|---|
| `gemini-3.5-flash-lite` | Yes | **Indirect evidence only:** earlier invoices were EXTRACTED by this app, whose default model is `gemini-3.5-flash-lite`; the model used was not recorded before this feature, so this is not proof | Likely (same request shape succeeded) | Candidate (baseline) |
| `gemini-3.1-flash-lite` | Yes | **Unverified** — check AI Studio > Rate limits for this project | Expected (same API, structured outputs supported) — unverified | Candidate, if AI Studio shows quota |
| `gemini-3.8-flash` | Yes | **Unverified** — check AI Studio > Rate limits for this project | Expected — unverified; thinking is on by default and cannot be `minimal`, so `thoughtsTokenCount` will likely be non-null | Candidate, if AI Studio shows quota |

I could not verify project access from here: AI Studio is behind the user's Google login, and a
`models.get` / test `generateContent` call would itself be a provider request, which is not yet approved.

## 3. Method (identical for every model)

- **Fixture:** `samples/sample-invoice-multipage-mixed.pdf` (synthetic, 2 pages, 170,565 bytes; kept locally,
  not part of the published repository).
  - Page 1 — selectable PDF text: header, seller/buyer, **Laptop Stand**, invoice summary.
  - Page 2 — a single full-page JPEG image (scan-style, no text layer): repeated header +
    **USB-C Cable**, which appears nowhere on page 1.
- **Request:** the production code path — `GeminiInvoiceExtractor` — unchanged: one
  `generateContent` call, prompt `prompts/invoice-extraction.txt`, schema `prompts/invoice-schema.json`,
  the PDF as one inline `application/pdf` part. No `thinkingConfig` is set (each model's default).
- **How each model is selected:** set `GEMINI_MODEL=<model id>` in the environment that launches the
  app, restart, `POST /api/invoices/extract` with the fixture **once**, then `GET /api/invoices/{id}`.
- **Budget:** at most **3 live requests in total**, one per model. No retries, no loops. Stop on
  401/403/404/429 or any "paid only" response — that model is then recorded as unavailable.
- **Database effect:** each call inserts **one `invoices` row** (EXTRACTED or FAILED) and, if
  EXTRACTED, its `invoice_items` rows (expected 2). Up to 3 invoice rows + up to 6 item rows.
  These rows are kept (not deleted) and are identifiable by `source_file_name` and `gemini_model_id`.
- **Measured values** come only from `tokenUsage` in the API response / the new DB columns
  (`usageMetadata` of that same response; duration measured by the app around the call).

### Oracle (expected values, compared with BigDecimal semantics: 2 == 2.000)

| Field | Expected |
|---|---|
| invoiceNumber | `INV-2026-0042` |
| invoiceDate | `2026-10-06` |
| sellerName | `Demo Tech Supplies Pvt Ltd` |
| sellerTaxId | `29ABCDE1234F1Z5` |
| buyerName | `Sample Retail Solutions LLP` |
| buyerTaxId | `27PQRSX5678K1Z2` |
| currency | `INR` |
| item 1 (page 1, text) | Laptop Stand — qty 2, unit 5000.00, tax 18, line 10000.00 |
| item 2 (page 2, image only) | USB-C Cable — qty 1, unit 500.00, tax 18, line 500.00 |
| item count | exactly 2 (no duplicated header/continuation rows) |
| subtotal / tax / total | 10500.00 / 1890.00 / 12390.00 |

If the page-2 item is missed, the validator rejects the result (sum of lines 10000.00 ≠ subtotal
10500.00) → HTTP 422, row stored as FAILED with its token usage. That outcome counts as a failed
extraction for the model, not as a pass.

## 4. Observed results

**Not run — awaiting approval.** Date of run: —

| Metric | `gemini-3.5-flash-lite` | `gemini-3.1-flash-lite` | `gemini-3.8-flash` |
|---|---|---|---|
| HTTP status / outcome | not run | not run | not run |
| Invoice id created | not run | not run | not run |
| Returned `modelVersion` | not run | not run | not run |
| `promptTokenCount` | not run | not run | not run |
| `candidatesTokenCount` | not run | not run | not run |
| `thoughtsTokenCount` | not run | not run | not run |
| `totalTokenCount` | not run | not run | not run |
| `extractionDurationMs` | not run | not run | not run |
| Header fields correct (of 7) | not run | not run | not run |
| Page-1 item correct | not run | not run | not run |
| Page-2 (image) item correct | not run | not run | not run |
| Totals correct | not run | not run | not run |
| Extra / duplicate items | not run | not run | not run |
| Schema parse + Java validation | not run | not run | not run |

## 5. Recommendation

**None yet.** A recommendation needs the measured results above. Selection rule agreed in advance:
correctness first (all 7 header fields, both items including the page-2 image item, totals, passes
validation), then lower `totalTokenCount`, then lower latency. A cheaper model that misses the page-2
item is not preferred. The production default stays `gemini-3.5-flash-lite` until the user approves a
change.

## 6. Limitations

- One synthetic document, one call per model: this can show that a model **can** read a scanned
  continuation page, not how accurately it reads real scans in general. Latency from a single call is
  noisy (network, provider load).
- The fixture's scan is clean (150 dpi, light noise, 0.4° skew). Blurry, rotated or faded real scans
  may fail where this passes.
- Token counts are what Gemini reported for that one request; they will differ for other documents.
- Free Tier: content may be used by Google to improve products — never send real customer invoices.
