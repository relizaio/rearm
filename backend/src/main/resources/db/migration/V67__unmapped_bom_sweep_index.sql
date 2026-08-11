-- Partial index driving the unmapped-BOM sweep
-- (SbomComponentService.sweepUnmappedBomArtifacts): the sweep anti-joins
-- BOM-typed artifacts against artifact_canonical_map every scheduler tick.
-- Restricting the index to the BOM subset keeps the steady-state (no
-- orphans) probe an index-only scan instead of a full artifacts walk.
CREATE INDEX IF NOT EXISTS artifacts_bom_type_created_idx
    ON rearm.artifacts (created_date)
    WHERE record_data->>'type' = 'BOM';
