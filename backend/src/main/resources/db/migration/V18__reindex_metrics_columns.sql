-- Drop old indexes that referenced record_data->'metrics' (now a dedicated column)

-- From V15: releases metrics lastScanned
DROP INDEX IF EXISTS rearm.idx_releases_metrics_last_scanned;
-- From V15: artifacts metrics lastScanned
DROP INDEX IF EXISTS rearm.idx_artifacts_metrics_last_scanned;
-- From V13: artifacts org + dependencyTrackProject
DROP INDEX IF EXISTS rearm.idx_artifacts_org_dtrack_project;

-- Recreate indexes pointing to the new dedicated metrics column

-- releases: metrics->lastScanned for MAX aggregation and filtering
CREATE INDEX IF NOT EXISTS idx_releases_metrics_last_scanned
ON rearm.releases ((cast(metrics->>'lastScanned' as float)));

-- artifacts: metrics->lastScanned for filtering in unprocessedArts CTE
CREATE INDEX IF NOT EXISTS idx_artifacts_metrics_last_scanned
ON rearm.artifacts ((cast(metrics->>'lastScanned' as float)));

-- artifacts: org + dependencyTrackProject lookup
CREATE INDEX IF NOT EXISTS idx_artifacts_org_dtrack_project
ON rearm.artifacts ((record_data->>'org'), (metrics->>'dependencyTrackProject'))
WHERE metrics->>'dependencyTrackProject' IS NOT NULL
AND metrics->>'dependencyTrackProject' != '';
