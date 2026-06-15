-- ============================================================================
-- V44__synthetic_bucket_index.sql — sticky synthetic-DTrack bucket assignment.
--
-- The synthetic-DTrack submitter previously sliced an org's matchable
-- sbom_components into buckets purely by POSITION in a purl-sorted list. That
-- made bucket membership unstable: inserting/enriching one component shifted
-- every later component's position, so a single change re-hashed (and re-sent
-- to Dependency-Track) every downstream bucket.
--
-- This column records each component's STICKY bucket: assigned once when the
-- component first becomes submittable, never changed afterward. submitOrg now
-- groups by this column, so a new/enriched component only re-submits its own
-- bucket. No backfill — first submitOrg pass assigns NULLs first-fit in
-- purl order, which reproduces the existing positional grouping (so the
-- migration causes no DTrack re-submission churn).
--
-- No FK constraints anywhere, per coding_principles.md.
-- ============================================================================

ALTER TABLE rearm.sbom_components
    ADD COLUMN IF NOT EXISTS synthetic_bucket_index integer;

-- Drives the scheduler's per-org idle-skip ("dirty") check: matchable rows not
-- yet assigned to a bucket (new or just-enriched). Partial on the NULL case so
-- the index is empty in steady state and the EXISTS probe is instant; it only
-- carries the handful of unassigned rows during an ingest/enrichment ramp.
CREATE INDEX IF NOT EXISTS sbom_components_unbucketed_idx
    ON rearm.sbom_components (org)
    WHERE synthetic_bucket_index IS NULL
      AND (canonical_purl LIKE 'pkg:%' OR canonical_purl LIKE 'cpe:%');
