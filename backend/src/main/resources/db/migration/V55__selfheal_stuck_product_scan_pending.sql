-- V55 — un-stick product/aggregate releases stranded in "Scan pending".
--
-- Background. A release's "Scan pending" badge is driven by
-- metrics.firstScanned being null. The every-minute metrics sweep
-- (ReleaseService.computeMetricsForAllUnprocessedReleases) re-derives a
-- release only while it stays "dirty" for one of its finders; the catch-all is
-- FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE, which matches while
--     last_updated_date > to_timestamp(metrics->>'lastScanned').
--
-- The bug (fixed in the same change that ships this migration): a PRODUCT
-- release that computed its metrics while at least one child release was still
-- unscanned wrote firstScanned=null (correct, all-or-nothing) but ALSO stamped
-- metrics.lastScanned = now(). Because a metrics write does not touch
-- last_updated_date, lastScanned immediately overtook last_updated_date and the
-- release fell out of the BY_UPDATE finder. When the lagging child finished
-- scanning, its scan-completion recomputed the CHILD, not the parent — so the
-- product was never re-derived and sat in "Scan pending" indefinitely.
--
-- The code fix stops stamping lastScanned while a scan is incomplete, so new
-- releases stay dirty until firstScanned can be set. But it only runs for
-- releases a finder still picks up — and the already-stranded rows are exactly
-- the ones no finder picks up. This migration re-admits them.
--
-- What it does. Strip metrics.lastScanned from product releases that are:
--   * scan-pending            (metrics.firstScanned is null),
--   * evicted from the finder (last_updated_date <= lastScanned), and
--   * actually recoverable    (every direct child release is now scanned).
-- Stripping lastScanned makes coalesce(...,0)=0, so last_updated_date > 0 and
-- the release re-enters BY_UPDATE. On the next sweep tick the roll-up sees all
-- children scanned, sets firstScanned, and the release settles out cleanly —
-- no perpetual churn.
--
-- Scope notes.
--   * Restricted to releases that HAVE children (parentReleases non-empty):
--     the eviction-while-pending path this fixes is the product/aggregate
--     roll-up. Single releases whose own BOM lagged are covered going forward
--     by the code fix.
--   * The "every direct child scanned" guard (NOT EXISTS below) is deliberate:
--     re-admitting a product whose child is genuinely never going to scan would
--     make it recompute every tick forever. We only touch rows that heal and
--     then leave the finder on the very next tick.
--   * Multi-level nesting: a product whose child is ITSELF a still-pending
--     product is skipped this pass (its child is unscanned). It self-heals on a
--     later operation once the inner product is un-stuck; deeper backlogs can be
--     cleared by re-running this UPDATE. Kept single-pass here for predictable
--     migration cost.
--   * firstScanned / lastScanned are stored as epoch-seconds numbers in the
--     metrics JSONB; ->> yields SQL NULL both when the key is absent and when it
--     is JSON null, which is the match we want.
--   * No FOREIGN KEY / new constraint — data-only backfill (coding_principles.md).

UPDATE rearm.releases p
SET metrics = p.metrics - 'lastScanned'
WHERE p.metrics ? 'lastScanned'
  AND p.metrics->>'firstScanned' IS NULL
  AND p.last_updated_date <= to_timestamp((p.metrics->>'lastScanned')::double precision)
  AND jsonb_array_length(COALESCE(p.record_data->'parentReleases', '[]'::jsonb)) > 0
  AND NOT EXISTS (
        SELECT 1
        FROM jsonb_array_elements(p.record_data->'parentReleases') AS pr
        LEFT JOIN rearm.releases c ON c.uuid = (pr->>'release')::uuid
        WHERE c.uuid IS NULL                       -- dangling child ref → treat as unscanned
           OR c.metrics->>'firstScanned' IS NULL   -- child still scan-pending
      );
