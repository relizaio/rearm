-- Add org column to metrics_audit table
ALTER TABLE rearm.metrics_audit ADD COLUMN IF NOT EXISTS org uuid;

-- Backfill org for RELEASE rows
UPDATE rearm.metrics_audit ma
SET org = (r.record_data->>'org')::uuid
FROM rearm.releases r
WHERE ma.entity_type = 'RELEASE'
  AND ma.entity_uuid = r.uuid;

-- Backfill org for ARTIFACT rows
UPDATE rearm.metrics_audit ma
SET org = (a.record_data->>'org')::uuid
FROM rearm.artifacts a
WHERE ma.entity_type = 'ARTIFACT'
  AND ma.entity_uuid = a.uuid;
