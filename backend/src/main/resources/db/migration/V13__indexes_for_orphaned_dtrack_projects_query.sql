-- Indexes to optimize the findOrphanedDtrackProjects query
-- This query checks for artifacts with DTrack projects that are not referenced by active branches

-- Index for artifacts table: org lookup and DTrack project extraction
CREATE INDEX IF NOT EXISTS idx_artifacts_org_dtrack_project 
ON rearm.artifacts ((record_data->>'org'), (record_data->'metrics'->>'dependencyTrackProject'))
WHERE record_data->'metrics'->>'dependencyTrackProject' IS NOT NULL 
AND record_data->'metrics'->>'dependencyTrackProject' != '';

-- GIN index for releases.record_data to speed up jsonb_contains on artifacts array
CREATE INDEX IF NOT EXISTS idx_releases_record_data_gin 
ON rearm.releases USING GIN (record_data jsonb_path_ops);

-- Index for releases: branch lookup (used in all three NOT EXISTS subqueries)
CREATE INDEX IF NOT EXISTS idx_releases_branch 
ON rearm.releases ((record_data->>'branch'));

-- Index for releases: sourceCodeEntry lookup (used in Path 2)
CREATE INDEX IF NOT EXISTS idx_releases_source_code_entry 
ON rearm.releases ((record_data->>'sourceCodeEntry'));

-- GIN index for source_code_entries.record_data to speed up jsonb_contains on artifacts array
CREATE INDEX IF NOT EXISTS idx_source_code_entries_record_data_gin 
ON rearm.source_code_entries USING GIN (record_data jsonb_path_ops);

-- Index for branches: status lookup (used in all three NOT EXISTS subqueries)
CREATE INDEX IF NOT EXISTS idx_branches_status 
ON rearm.branches ((record_data->>'status'));

-- GIN index for deliverables.record_data to speed up jsonb_contains on artifacts array
CREATE INDEX IF NOT EXISTS idx_deliverables_record_data_gin 
ON rearm.deliverables USING GIN (record_data jsonb_path_ops);

-- GIN index for variants.record_data to speed up jsonb_contains on outboundDeliverables array
CREATE INDEX IF NOT EXISTS idx_variants_record_data_gin 
ON rearm.variants USING GIN (record_data jsonb_path_ops);

-- Index for variants: release lookup (used in Path 3)
CREATE INDEX IF NOT EXISTS idx_variants_release 
ON rearm.variants ((record_data->>'release'));
