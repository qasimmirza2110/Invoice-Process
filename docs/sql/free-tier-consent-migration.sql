-- =====================================================================================================
-- Invoice Process - per-upload free-tier consent: ONE-TIME, ADDITIVE schema migration.
--
-- STATUS (2026-11-09): reused unchanged for the free-tier-only release. Applied to the isolated test database
-- (Actify-Internship-Invoice-Process_test_db) on 2026-10-30; NOT yet applied to Neon. The test database also gets the
-- same columns from Hibernate (profile pgtest, ddl-auto=update) when the PostgreSQL tests run.
--
-- What it adds (nothing else):
--   upload_batches.free_tier_consent     boolean NOT NULL DEFAULT false
--   upload_batches.free_tier_consent_at  timestamp without time zone (NULL unless consent was given)
--   ck_upload_batches_free_tier_consent  consent true <=> consent time present
-- Every existing batch gets free_tier_consent = false: NOTHING uploaded before this migration can ever be sent in the
-- free-tier auto queue. No row is updated, deleted or rewritten beyond the column default; no table is dropped.
--
-- ORDER: apply this BEFORE deploying the application version that maps these columns. That version starts with
-- ddl-auto=validate and refuses to start without them. The CURRENT live version ignores the new columns, and its
-- inserts get the default false, so applying the migration first is safe while the old version keeps running.
--
-- Run it only with psql and ON_ERROR_STOP, as the database owner:
--     psql -v ON_ERROR_STOP=1 -X -d "<connection>" -f free-tier-consent-migration.sql
-- (pass the connection through PGHOST/PGPORT/PGDATABASE/PGUSER and PGPASSWORD or a password file - never type a
--  password into a command line that is saved in shell history.)
-- There is deliberately no IF NOT EXISTS: if a column already exists the script stops and changes nothing.
--
-- Before: take a fresh backup (Neon: a branch or a restore point of the production branch) and run PART 1.
-- Did it commit? SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
--                AND table_name = 'upload_batches' AND column_name = 'free_tier_consent';  -- 1 = applied, 0 = not.
-- Rollback: not needed for the old application (it ignores the columns). Do NOT drop the columns once consented
-- batches exist: that would erase the record of who consented and when.
-- =====================================================================================================

-- ---------------------------------------------------------------- PART 1: READ-ONLY checks BEFORE (save the output)
SELECT current_database();
SELECT count(*) AS upload_batches FROM public.upload_batches;

-- ---------------------------------------------------------------- PART 2: the migration (one transaction)
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE public.upload_batches
    ADD COLUMN free_tier_consent    boolean NOT NULL DEFAULT false,
    ADD COLUMN free_tier_consent_at timestamp without time zone,
    ADD CONSTRAINT ck_upload_batches_free_tier_consent CHECK (free_tier_consent = (free_tier_consent_at IS NOT NULL));

COMMIT;

-- ---------------------------------------------------------------- PART 3: READ-ONLY checks AFTER
SELECT count(*) AS upload_batches,
       count(*) FILTER (WHERE free_tier_consent) AS with_free_tier_consent   -- expected: 0
FROM public.upload_batches;
