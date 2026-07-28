-- GIN indexes driving the artifact-scan -> release touch
-- (SharedArtifactService.saveArtifactMetrics -> ReleaseRepository
-- .touchReleasesByScannedDeliverableArtifact): resolve which releases carry a
-- just-scanned artifact through variants/outboundDeliverables/deliverables
-- /artifacts jsonb arrays with two index probes (~2.5ms at 300k deliverables
-- / 150k variants, measured) instead of the per-minute finder's full jsonb
-- expansion (1.7s at the same scale, and timing out on large instances).
-- jsonb_path_ops: only @> is needed, and its keys are smaller/faster than
-- the default opclass.
CREATE INDEX IF NOT EXISTS deliverables_artifacts_gin_idx
    ON rearm.deliverables USING gin ((record_data->'artifacts') jsonb_path_ops);
CREATE INDEX IF NOT EXISTS variants_outbound_dels_gin_idx
    ON rearm.variants USING gin ((record_data->'outboundDeliverables') jsonb_path_ops);
