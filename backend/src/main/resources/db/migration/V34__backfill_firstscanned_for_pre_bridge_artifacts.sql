-- Backfill firstScanned on artifacts (and trigger re-aggregation on releases)
-- that were first scanned before commit 7fe729c7 (2026-04-09,
-- "track firstScanned timestamp for artifact and release metrics") introduced
-- the firstScanned = lastScanned bridge in SharedArtifactService.updateArtifactDti.
--
-- Pre-bridge artifacts had lastScanned written by the DTrack ingest path
-- (resolveDependencyTrackProcessingStatus → setLastScanned) but firstScanned
-- was never set. Two things conspire to keep them stuck:
--
--   1. LIST_INITIAL_ARTIFACTS_PENDING_DEPENDENCY_TRACK gates on
--      `metrics->>'lastScanned' IS NULL` — once lastScanned is set, the
--      scheduler never re-picks the artifact, so the bridge never re-fires.
--   2. FIND_RELEASES_FOR_METRICS_COMPUTE_BY_ARTIFACT_DIRECT gates on
--      `artifact.lastScanned > release.lastScanned` — stale artifact
--      lastScanned vs stale release lastScanned, predicate never satisfies,
--      release metrics recompute never re-runs.
--
-- Net effect on the UI: badge shows "Scanning…" (firstScanned null) while
-- circles render zeros (lastScanned not null), and the release-level scan
-- pill stays at "Scan pending" indefinitely.
--
-- Mitigation here is a one-shot:
--   1. Backfill artifact.metrics.firstScanned = artifact.metrics.lastScanned
--      for every artifact missing firstScanned but with lastScanned set.
--      Safe because lastScanned was always set together with at least one
--      DTrack poll that returned !isProcessing — i.e. a real scan completion,
--      just one that pre-dated the bridge.
--   2. Bump last_updated_date on every release with the same (lastScanned-set,
--      firstScanned-null) shape so the metrics-compute scheduler picks them
--      up on the next tick. computeReleaseMetricsOnRescan will then read the
--      now-backfilled artifact firstScanned values and either anchor the
--      release firstScanned (no-BOM anchor path) or aggregate from artifacts
--      (BOM-bearing path). Direct INSERT-style backfill on releases is the
--      wrong shape because product releases compute firstScanned from their
--      transitive deps' firstScanned (all-or-nothing rollup); the scheduler
--      run handles that correctly.

UPDATE rearm.artifacts
SET metrics = jsonb_set(metrics, '{firstScanned}', metrics->'lastScanned')
WHERE metrics->>'lastScanned' IS NOT NULL
  AND metrics->>'firstScanned' IS NULL;

UPDATE rearm.releases
SET last_updated_date = now()
WHERE metrics->>'lastScanned' IS NOT NULL
  AND metrics->>'firstScanned' IS NULL;
