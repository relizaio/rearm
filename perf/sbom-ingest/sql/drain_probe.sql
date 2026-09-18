-- T2/T3 completion probes for the SBOM ingest load test.
--
-- The GraphQL mutation returning is T1 (accept), not ingestion. Reconcile is
-- queued on the release's flow_control and drained by a per-minute scheduler in
-- batches of 50 (SchedulingService.java:251), so a run is only complete when the
-- backlog reaches zero. The backlog curve over time is the real output: it shows
-- whether the drain kept up with the offered load or fell behind.
--
-- IMPORTANT -- the probe must not perturb what it measures. Queries here are
-- split into a cheap sampler (safe to run every few seconds for the whole run)
-- and heavier end-of-run queries (run once, after the load stops). Note that
-- rearm.releases has NO org column: org lives in record_data->>'org', which is
-- not indexed. Only the sampler is index-backed; do not move the others into
-- the loop.
--
-- Usage (psql inside the postgres pod):
--   \set org '<test-org-uuid>'
--   \i drain_probe.sql

-- ===========================================================================
-- SAMPLER -- cheap, safe to poll on an interval during the run.
-- ===========================================================================

-- Backlog. There IS a partial index for this predicate
-- (releases_sbom_reconcile_pending_idx: btree on the flow_control expression,
-- WHERE NOT NULL, so it covers only pending rows). Whether the planner uses it
-- depends on table size: measured on a 5,453-row table it chose a Seq Scan
-- instead, because at that size scanning is cheaper. Do not assume the index is
-- being used -- re-run EXPLAIN at your actual run scale, and if it is still
-- seq-scanning, either lengthen the sampling interval or accept the probe cost
-- explicitly in the run record. A sampler that scans the whole releases table
-- every few seconds competes with the run for the same Hikari pool of 20.
--
-- Do NOT rewrite this as a count(*) FILTER over all of the org's releases:
-- that form can never use the partial index at any size.
SELECT
    count(*)                                                                 AS queued,
    count(*) FILTER (WHERE flow_control ->> 'sbomReconcileSkipUntil' IS NOT NULL) AS backing_off,
    min((flow_control ->> 'sbomReconcileRequestedAt')::timestamptz)          AS oldest_queued_at,
    now() - min((flow_control ->> 'sbomReconcileRequestedAt')::timestamptz)  AS oldest_age
FROM rearm.releases
WHERE flow_control ->> 'sbomReconcileRequestedAt' IS NOT NULL
  AND record_data ->> 'org' = :'org';

-- ===========================================================================
-- END OF RUN -- heavier. Run once after the load generator stops.
-- ===========================================================================

-- Releases created, and how many failed reconcile. A non-zero failure count
-- invalidates the throughput number: those releases left the queue via the
-- backoff path (BASE_BACKOFF 30s doubling to 3600s), not via completion.
SELECT
    count(*)                                                                        AS releases_total,
    count(*) FILTER (WHERE flow_control ->> 'sbomReconcileRequestedAt' IS NOT NULL)  AS still_queued,
    count(*) FILTER (WHERE flow_control ->> 'sbomReconcileFailureCount' IS NOT NULL) AS failed,
    min(created_date)                                                               AS first_created,
    max(created_date)                                                               AS last_created,
    max(created_date) - min(created_date)                                           AS wall_clock
FROM rearm.releases
WHERE record_data ->> 'org' = :'org';

-- Achieved drain rate in releases per minute, derived from when reconcile
-- markers cleared. Compare against the batch constant of 50/min: at or near 50
-- means the scheduler is the binding constraint; materially below it means
-- something upstream is (DB contention, or HeapPressureGuard aborting the loop).
SELECT
    date_trunc('minute', last_updated_date) AS minute,
    count(*)                                AS reconciled
FROM rearm.releases
WHERE record_data ->> 'org' = :'org'
  AND flow_control ->> 'sbomReconcileRequestedAt' IS NULL
GROUP BY 1
ORDER BY 1;

-- Read-side proof that T2 meant something. A run whose backlog hit zero but
-- whose component count did not grow was deduplicated, not ingested: expected
-- in warm mode once the pool is exhausted, a red flag in cold mode.
SELECT
    count(*)                       AS sbom_components,
    count(DISTINCT canonical_purl) AS distinct_canonical_purls
FROM rearm.sbom_components
WHERE org = :'org';

-- T3: synthetic DTrack fan-out. Buckets are content-hashed over the sorted
-- canonical-purl set, so content contributing no new purls submits nothing at
-- all -- zero buckets here in warm mode is a correct result, not a failure.
SELECT
    ingest_state,
    count(*)                   AS buckets,
    count(dtrack_project_uuid) AS with_dtrack_project,
    max(last_submitted)        AS last_submitted,
    max(last_ingested)         AS last_ingested
FROM rearm.synthetic_dtrack_bucket
WHERE org = :'org'
GROUP BY 1
ORDER BY 2 DESC;
