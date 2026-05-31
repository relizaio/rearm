-- Materialize a "totals only" view of the metrics jsonb into a STORED generated
-- column on releases and artifacts: everything except the large per-finding
-- detail arrays (violationDetails / vulnerabilityDetails / weaknessDetails).
--
-- Read paths that only need the severity / policy / scan totals can select
-- metrics_totals instead of metrics, avoiding the read + JSON deserialization of
-- the heavy detail arrays (the main heap pressure when listing many releases /
-- artifacts). The column is small (not TOASTed), so reads also skip detoasting
-- the big blob, and it stays in sync with metrics automatically — no
-- application-side dual write.
--
-- NOTE: adding a STORED generated column rewrites the table (it computes the
-- value for every existing row under an AccessExclusiveLock). On large
-- releases / artifacts tables this can run for a while and briefly blocks
-- concurrent writes; schedule the deploy accordingly.
ALTER TABLE rearm.releases
    ADD COLUMN metrics_totals jsonb
    GENERATED ALWAYS AS (metrics - 'violationDetails' - 'vulnerabilityDetails' - 'weaknessDetails') STORED;

ALTER TABLE rearm.artifacts
    ADD COLUMN metrics_totals jsonb
    GENERATED ALWAYS AS (metrics - 'violationDetails' - 'vulnerabilityDetails' - 'weaknessDetails') STORED;
