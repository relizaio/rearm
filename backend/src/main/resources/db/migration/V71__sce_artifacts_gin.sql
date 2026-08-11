-- GIN index backing the SCE half of the artifact-scan -> release touch
-- (ReleaseRepository.touchReleasesByScannedSceArtifact). Mirrors V68, which did
-- the same for the deliverable path. Both touches replace the per-minute
-- BY_SCE / BY_OUTBOUND_DELIVERABLES metrics finders, whose full jsonb
-- expansions grew with total instance data and timed out on large instances.
--
-- Default jsonb_ops (not jsonb_path_ops): the SCE artifact array holds OBJECTS
-- ({artifactUuid, componentUuid}), and the containment probe matches on a
-- single key within those objects.
CREATE INDEX IF NOT EXISTS source_code_entries_artifacts_gin_idx
    ON rearm.source_code_entries USING gin ((record_data->'artifacts'));
