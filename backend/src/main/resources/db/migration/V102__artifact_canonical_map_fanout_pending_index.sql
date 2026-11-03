-- Fan-out catch-up and new-upload probe (TPF-114): the map rows no fan-out pass has stamped,
-- per org, newest first. Partial on the ABSENCE of flow_control.fanOutAt, so the index holds
-- every row once (the catch-up after this deploy), then only rows inserted since the last pass
-- (new uploads, the unmapped-BOM sweep), and is empty in steady state: the per-tick probe of
-- ArtifactCanonicalMapRepository.findNeverFannedOutCanonicals is then an empty index scan.
-- The predicate spelling must stay identical to that query's WHERE or the planner loses the
-- match. No index on the VALUE of fanOutAt: the change-driven query compares it per row after
-- an indexed join from the changed buckets (findCanonicalsNeedingFanOutForBucket), and
-- a text-to-timestamptz cast is not immutable anyway.
--
-- Plain (blocking) build, as V72's index on this table and for V73's reason (CONCURRENTLY
-- self-deadlocks under community Flyway): the table holds one row per mapped BOM artifact
-- (57k on the largest known instance), so the build is sub-second.
CREATE INDEX IF NOT EXISTS artifact_canonical_map_fanout_pending_idx
    ON rearm.artifact_canonical_map (org, created_date DESC)
    WHERE (flow_control->>'fanOutAt') IS NULL;

-- V80's artifacts_org_fanout_pool_idx served only ArtifactRepository.findFanOutPoolSlice, which
-- this change removes; the index cost a write per artifact scan stamp on a 500k-row table for
-- nothing. V74's artifacts_org_last_scanned_idx is KEPT: FIND_MAX_LAST_SCANNED_EPOCH_FOR_ORG
-- (the notification affected-release guard) and the two fan-out stall diagnostics still key the
-- bare cast(metrics->>'lastScanned' as float), as V80's closing comment records.
-- DROP INDEX takes a momentary ACCESS EXCLUSIVE lock on rearm.artifacts; on a rolling deploy a
-- replica still running the old code falls back to the Filter+Sort plan for findFanOutPoolSlice
-- until it is replaced (per-org try/catch, one logged error per org per tick at worst).
DROP INDEX IF EXISTS rearm.artifacts_org_fanout_pool_idx;

ANALYZE rearm.artifact_canonical_map;
