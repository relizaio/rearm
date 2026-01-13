-- Indexes to optimize the query for finding releases with unprocessed artifacts
-- Query: VariableQueries.GET_RELEASES_WITH_UNPROCESSED_ARTIFACTS

-- Index for releases: metrics->lastScanned for MAX aggregation in lastComputedRlz CTE
CREATE INDEX IF NOT EXISTS idx_releases_metrics_last_scanned 
ON rearm.releases ((cast(record_data->'metrics'->>'lastScanned' as float)));

-- Index for artifacts: metrics->lastScanned for filtering in unprocessedArts CTE
CREATE INDEX IF NOT EXISTS idx_artifacts_metrics_last_scanned 
ON rearm.artifacts ((cast(record_data->'metrics'->>'lastScanned' as float)));

-- Index for releases: parentReleases filter in FIND_RELEASES_FOR_METRICS_COMPUTE_BY_PARENT query
CREATE INDEX IF NOT EXISTS idx_releases_parent_releases 
ON rearm.releases ((record_data->>'parentReleases'))
WHERE record_data->>'parentReleases' != '[]';

-- Index for releases: last_updated_date for FIND_RELEASES_FOR_METRICS_COMPUTE_BY_LAST_UPDATED query
CREATE INDEX IF NOT EXISTS idx_releases_last_updated_date 
ON rearm.releases (last_updated_date);
