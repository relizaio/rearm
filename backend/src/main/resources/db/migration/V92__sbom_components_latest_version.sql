-- Finding detail, component latest version: the latest version of a
-- component's package, as Dependency-Track's repository metadata reports it.
-- A freshness signal only: it says nothing about end of support or end of life.
--
-- First-class columns, not record_data, for the V82 reason: a mutable fact the
-- scheduler filters on. Written only by one targeted UPDATE of these two
-- columns (the entity maps them read-only), so an entity save of a stale row
-- can never put an old value back.
--
-- latest_version: null when Dependency-Track has no repository for the purl
-- type (deb, apk, rpm, generic), does not know the package, or has not
-- analysed it yet. latest_version_checked: when ReARM last asked; null means
-- never, and the refresh picks the row up.
ALTER TABLE rearm.sbom_components
    ADD COLUMN IF NOT EXISTS latest_version text,
    ADD COLUMN IF NOT EXISTS latest_version_checked timestamptz;

-- The refresh's due probes, SbomComponentRepository.findLatestVersionDueBuckets
-- and findLatestVersionDueUnbucketed: components never checked or checked before
-- the freshness cutoff. One index each, so each stops at the cutoff or its
-- LIMIT, whichever comes first: the bucket probe is one lookup per bucket, keyed
-- (org, bucket, check); the unbucketed probe one range in check order, keyed
-- (org, check). IS NULL does not fix the sort order the way an equality does,
-- so one index over both would read an org's whole unbucketed backlog every run
-- to take its oldest 1000. On coalesce(..., '-infinity') rather than "IS NULL
-- OR <", which has no stop key (V80). Release roots are left out: the refresh
-- never looks them up, so indexed they would stay due forever and be read and
-- dropped every run. Both queries spell the pkg: and root conditions and the
-- coalesce exactly as here, and the bucket probe's plain = on the bucket implies
-- its IS NOT NULL (so it must stay a plain =), or the planner loses the match.
-- Local Postgres 17, 2026-09-30, an org of 200k components and 20k releases:
-- the bucket probe 1.1 ms against 66 ms keyed (org, check); the unbucketed one
-- 0.03 ms against 8.5 ms with the roots in, and 0.7 ms against 29 ms on a 60k
-- backlog in the one shared index.
--
-- Plain (blocking) builds, not CONCURRENTLY, for the reason V73 documents:
-- community Flyway holds its history lock connection idle-in-transaction while
-- a non-transactional script runs on a second one, and CONCURRENTLY waits on it
-- forever. Flyway runs this file as one transaction, so the ALTER's ACCESS
-- EXCLUSIVE lock is held through the index builds and the ANALYZE: reads and
-- writes of sbom_components both wait for the whole file (1.3 s for 1.3M rows
-- of this table's width, same setup). Adding the columns and building the
-- indexes by hand with CONCURRENTLY before upgrading shrinks that to the ANALYZE
-- (0.4 s): the ALTER still takes its lock when the columns exist. A cancelled
-- or failed CONCURRENTLY build leaves an invalid index behind, which IF NOT
-- EXISTS would adopt silently, so an invalid one is dropped and rebuilt (V80's
-- guard; a valid index of the name is trusted, for V80's reasons).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class c
               JOIN pg_namespace n ON n.oid = c.relnamespace
               JOIN pg_index i ON i.indexrelid = c.oid
               WHERE n.nspname = 'rearm'
                 AND c.relname = 'sbom_components_latest_version_due_bucketed_idx'
                 AND NOT i.indisvalid) THEN
        DROP INDEX rearm.sbom_components_latest_version_due_bucketed_idx;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c
               JOIN pg_namespace n ON n.oid = c.relnamespace
               JOIN pg_index i ON i.indexrelid = c.oid
               WHERE n.nspname = 'rearm'
                 AND c.relname = 'sbom_components_latest_version_due_unbucketed_idx'
                 AND NOT i.indisvalid) THEN
        DROP INDEX rearm.sbom_components_latest_version_due_unbucketed_idx;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS sbom_components_latest_version_due_bucketed_idx
    ON rearm.sbom_components (org, synthetic_bucket_index, (coalesce(latest_version_checked, '-infinity'::timestamptz)))
    WHERE canonical_purl LIKE 'pkg:%' AND (record_data->>'isRoot') IS DISTINCT FROM 'true'
      AND synthetic_bucket_index IS NOT NULL;

CREATE INDEX IF NOT EXISTS sbom_components_latest_version_due_unbucketed_idx
    ON rearm.sbom_components (org, (coalesce(latest_version_checked, '-infinity'::timestamptz)))
    WHERE canonical_purl LIKE 'pkg:%' AND (record_data->>'isRoot') IS DISTINCT FROM 'true'
      AND synthetic_bucket_index IS NULL;

-- An expression index has no statistics until the table is analysed (V80).
ANALYZE rearm.sbom_components;
