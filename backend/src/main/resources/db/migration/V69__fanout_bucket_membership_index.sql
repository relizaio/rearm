-- Partial composite index driving the server-side fan-out candidate join
-- (ArtifactSbomComponentRepository.findDistinctCanonicalArtifactUuids
-- CoveredByIngestedBuckets): INGESTED buckets -> their member components by
-- (org, synthetic_bucket_index) -> containing canonical artifacts. Replaces
-- the giant-JDBC-IN-list flow that timed out on large instances. Partial on
-- bucketed rows: unbucketed components can never be covered.
CREATE INDEX IF NOT EXISTS sbom_components_org_bucket_idx
    ON rearm.sbom_components (org, synthetic_bucket_index)
    WHERE synthetic_bucket_index IS NOT NULL;
