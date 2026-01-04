-- Additional indexes to optimize the CTE-based orphaned DTrack projects query
-- This migration adds indexes to support the inverted query logic that collects active artifacts first
-- Note: V13 already created GIN indexes on full record_data with jsonb_path_ops for jsonb_contains operations
-- V14 focuses on specific array extraction and composite filtering for the CTE approach

-- Composite index for branches: org + status filtering
-- This is used in all three UNION branches to filter active branches by org and status
-- The composite index is more efficient than the separate status index from V13 when both conditions are used
CREATE INDEX IF NOT EXISTS idx_branches_org_status 
ON rearm.branches ((record_data->>'org'), (record_data->>'status'))
WHERE record_data->>'status' != 'ARCHIVED';

-- GIN index on releases.record_data->'artifacts' for array element extraction
-- Supports: jsonb_array_elements_text(r.record_data->'artifacts')
-- Different from V13's idx_releases_record_data_gin which indexes the full record_data
CREATE INDEX IF NOT EXISTS idx_releases_artifacts_gin 
ON rearm.releases USING GIN ((record_data->'artifacts'));

-- GIN index on source_code_entries.record_data->'artifacts' for array element extraction
-- Supports: jsonb_array_elements_text(sce.record_data->'artifacts')
-- Different from V13's idx_source_code_entries_record_data_gin which indexes the full record_data
CREATE INDEX IF NOT EXISTS idx_source_code_entries_artifacts_gin 
ON rearm.source_code_entries USING GIN ((record_data->'artifacts'));

-- GIN index on deliverables.record_data->'artifacts' for array element extraction
-- Supports: jsonb_array_elements_text(d.record_data->'artifacts')
-- Different from V13's idx_deliverables_record_data_gin which indexes the full record_data
CREATE INDEX IF NOT EXISTS idx_deliverables_artifacts_gin 
ON rearm.deliverables USING GIN ((record_data->'artifacts'));
