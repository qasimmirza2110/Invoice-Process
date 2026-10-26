-- Invoice Process - read-only inspection queries (SELECT only; nothing here changes data).
--
-- Run all of them safely (the transaction is READ ONLY, so even a typo cannot write):
--   psql -h localhost -U postgres -d Actify-Internship-Invoice-Process_db -v ON_ERROR_STOP=1 -f docs/sql/inspection-queries.sql
-- Or copy one block into pgAdmin's Query Tool. The password comes from %APPDATA%\postgresql\pgpass.conf.
--
-- Timestamps are stored in UTC (hibernate.jdbc.time_zone=UTC). "ist(x)" below = x shown in India time:
--   x AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata'
-- Replace :invoice_id / :email style placeholders with a value when you run a single query by hand.

BEGIN TRANSACTION READ ONLY;

-- Q1. Which users exist? (Is a new user created? Which role? Enabled?)  password_hash is never selected.
SELECT id, email, display_name, role, enabled, token_version,
       created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS created_ist
FROM app_users ORDER BY id;

-- Q2. Users per role.
SELECT role, count(*) AS users, count(*) FILTER (WHERE enabled) AS enabled FROM app_users GROUP BY role ORDER BY role;

-- Q3. Categories and which reviewer handles which category (assignment needs this match).
SELECT c.code, c.display_name, c.active, string_agg(u.display_name || ' (id ' || u.id || ')', ', ') AS reviewers
FROM categories c
LEFT JOIN reviewer_categories rc ON rc.category_code = c.code
LEFT JOIN app_users u ON u.id = rc.user_id
GROUP BY c.code, c.display_name, c.active ORDER BY c.code;

-- Q4. Upload batches: who uploaded, from where (LOCAL / GOOGLE_DRIVE / ONEDRIVE), open or finished, accepted/rejected.
SELECT b.id, u.display_name AS vendor, b.source, b.status, b.default_category_code AS category,
       b.accepted_count, b.rejected_count, b.rejections,
       b.created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS created_ist,
       b.closed_at  AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS finished_ist
FROM upload_batches b JOIN app_users u ON u.id = b.owner_user_id ORDER BY b.id DESC;

-- Q5. Invoices per status, split into workflow invoices (have an owner) and legacy single-file invoices.
SELECT CASE WHEN owner_user_id IS NULL THEN 'legacy' ELSE 'workflow' END AS kind, status, count(*)
FROM invoices GROUP BY 1, 2 ORDER BY 1, 2;

-- Q6. Workflow invoices with vendor, reviewer, category and stored PDF (newest first).
SELECT i.id, i.status, i.category_code AS category, v.display_name AS vendor, r.display_name AS reviewer,
       i.invoice_number, i.seller_name, i.total_amount, i.currency, i.submitted_version,
       d.original_file_name, d.size_bytes,
       i.created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS uploaded_ist
FROM invoices i
JOIN app_users v ON v.id = i.owner_user_id
LEFT JOIN app_users r ON r.id = i.assigned_reviewer_id
LEFT JOIN invoice_documents d ON d.id = i.source_document_id
ORDER BY i.id DESC;

-- Q7. Admin's "needs a reviewer" list: extracted or waiting invoices without a reviewer.
SELECT id, status, category_code, created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS uploaded_ist
FROM invoices
WHERE owner_user_id IS NOT NULL AND assigned_reviewer_id IS NULL
  AND status IN ('QUEUED', 'PROCESSING', 'NEEDS_REVIEW')
ORDER BY created_at;

-- Q8. Waiting for the admin's decision.
SELECT id, assigned_reviewer_id, submitted_version, invoice_number, total_amount, currency
FROM invoices WHERE status = 'PENDING_APPROVAL' ORDER BY updated_at;

-- Q9. Was the PDF stored? Every document row; the file on disk is <storage dir>\<id>.pdf
--     (primary DB: %USERPROFILE%\actify-task8-invoice-documents). Compare sha256 with: certutil -hashfile <file> SHA256
SELECT d.id || '.pdf' AS file_on_disk, d.original_file_name, d.size_bytes, d.sha256, d.content_type,
       d.created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS stored_ist,
       (SELECT string_agg(i.id::text, ',') FROM invoices i WHERE i.source_document_id = d.id) AS invoice_ids
FROM invoice_documents d ORDER BY d.created_at DESC;

-- Q10. Workflow invoices whose PDF row is missing (should return 0 rows).
SELECT id, status FROM invoices WHERE owner_user_id IS NOT NULL AND source_document_id IS NULL;

-- Q11. Queue: one job per uploaded invoice. finished_at NULL = still waiting or being processed.
SELECT j.invoice_id, i.status, j.attempt_count, j.max_attempts, j.claimed_by, j.last_error_code,
       j.queued_at       AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS queued_ist,
       j.next_attempt_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS next_attempt_ist,
       j.finished_at     AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS finished_ist
FROM extraction_jobs j JOIN invoices i ON i.id = j.invoice_id ORDER BY j.queued_at DESC;

-- Q12. Open jobs only (what the worker still has to do).
SELECT count(*) AS open_jobs FROM extraction_jobs WHERE finished_at IS NULL;

-- Q13. Every Gemini call: result, HTTP status, tokens, time. One row per attempt (written BEFORE the call).
SELECT a.id, a.invoice_id, a.attempt_number, a.caller, a.outcome, a.http_status, a.error_code, a.model_id,
       a.prompt_token_count, a.candidates_token_count, a.total_token_count, a.duration_ms,
       a.started_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS started_ist
FROM gemini_call_attempts a ORDER BY a.id DESC;

-- Q14. Gemini usage per day (compare with the daily limit GEMINI_LIMIT_RPD, e.g. 500).
SELECT (started_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata')::date AS day_ist, count(*) AS calls,
       count(*) FILTER (WHERE outcome = 'SUCCESS') AS ok, sum(total_token_count) AS tokens
FROM gemini_call_attempts GROUP BY 1 ORDER BY 1 DESC;

-- Q15. Versions of one invoice: V1 = Gemini, V2+ = reviewer corrections (changed_by = user:<id>).
SELECT invoice_id, version_number, change_source, changed_by, changed_fields,
       created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS saved_ist
FROM invoice_versions WHERE invoice_id = (SELECT max(id) FROM invoices) ORDER BY version_number;
-- by hand: ... WHERE invoice_id = 52

-- Q16. Line items of one invoice.
SELECT invoice_id, id, description, quantity, unit_price, tax_rate, line_total
FROM invoice_items WHERE invoice_id = (SELECT max(id) FROM invoices) ORDER BY id;

-- Q17. Why an invoice needs attention (validator / duplicate reasons).
SELECT invoice_id, version_number, code, field, message FROM invoice_review_reasons ORDER BY invoice_id DESC, id;

-- Q18. Full history of one invoice: who moved it, from what to what, when, and why.
SELECT e.invoice_id, e.from_status, e.to_status, coalesce(u.display_name, '(worker)') AS actor, e.version_number,
       e.reason, e.created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS at_ist
FROM invoice_workflow_events e LEFT JOIN app_users u ON u.id = e.actor_user_id
WHERE e.invoice_id = (SELECT max(id) FROM invoices) ORDER BY e.id;

-- Q19. Approved invoices: who reviewed and who approved which exact version.
SELECT i.id, i.invoice_number, i.total_amount, i.currency, r.display_name AS reviewer,
       a.display_name AS approved_by, e.version_number AS approved_version,
       e.created_at AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata' AS approved_ist
FROM invoices i
JOIN invoice_workflow_events e ON e.invoice_id = i.id AND e.to_status = 'APPROVED'
LEFT JOIN app_users r ON r.id = i.assigned_reviewer_id
LEFT JOIN app_users a ON a.id = e.actor_user_id
WHERE i.status = 'APPROVED' ORDER BY e.created_at DESC;

-- Q20. Same vendor uploaded the same bytes twice? (The app blocks this; should return 0 rows.)
SELECT i.owner_user_id, d.sha256, count(*)
FROM invoices i JOIN invoice_documents d ON d.id = i.source_document_id
WHERE i.owner_user_id IS NOT NULL GROUP BY 1, 2 HAVING count(*) > 1;

-- Q21. Sanity checks (each should return 0).
SELECT 'status QUEUED/PROCESSING without open job' AS check_name, count(*) FROM invoices i
  WHERE i.owner_user_id IS NOT NULL AND i.status IN ('QUEUED', 'PROCESSING')
    AND NOT EXISTS (SELECT 1 FROM extraction_jobs j WHERE j.invoice_id = i.id AND j.finished_at IS NULL)
UNION ALL
SELECT 'PENDING_APPROVAL without submitted_version', count(*) FROM invoices
  WHERE status = 'PENDING_APPROVAL' AND submitted_version IS NULL
UNION ALL
SELECT 'Gemini attempt still IN_FLIGHT', count(*) FROM gemini_call_attempts WHERE outcome = 'IN_FLIGHT'
UNION ALL
SELECT 'assigned reviewer is not a REVIEWER', count(*) FROM invoices i JOIN app_users u ON u.id = i.assigned_reviewer_id
  WHERE u.role <> 'REVIEWER';

-- Q22. Schema check: the 12 tables and their row counts.
SELECT 'app_users' AS t, count(*) FROM app_users UNION ALL SELECT 'categories', count(*) FROM categories
UNION ALL SELECT 'reviewer_categories', count(*) FROM reviewer_categories
UNION ALL SELECT 'upload_batches', count(*) FROM upload_batches UNION ALL SELECT 'invoices', count(*) FROM invoices
UNION ALL SELECT 'invoice_items', count(*) FROM invoice_items UNION ALL SELECT 'invoice_documents', count(*) FROM invoice_documents
UNION ALL SELECT 'invoice_versions', count(*) FROM invoice_versions
UNION ALL SELECT 'invoice_review_reasons', count(*) FROM invoice_review_reasons
UNION ALL SELECT 'invoice_workflow_events', count(*) FROM invoice_workflow_events
UNION ALL SELECT 'extraction_jobs', count(*) FROM extraction_jobs
UNION ALL SELECT 'gemini_call_attempts', count(*) FROM gemini_call_attempts;

ROLLBACK;
