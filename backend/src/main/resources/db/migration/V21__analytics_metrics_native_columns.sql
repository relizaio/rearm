-- Add new native columns (nullable initially for backfill)
ALTER TABLE rearm.analytics_metrics ADD COLUMN org UUID;
ALTER TABLE rearm.analytics_metrics ADD COLUMN date_key TEXT;
ALTER TABLE rearm.analytics_metrics ADD COLUMN perspective UUID;
ALTER TABLE rearm.analytics_metrics ADD COLUMN numeric_metrics JSONB;

-- Backfill from record_data
UPDATE rearm.analytics_metrics SET
    org = (record_data->>'org')::uuid,
    date_key = record_data->>'dateKey',
    perspective = NULLIF(NULLIF(NULLIF(record_data->>'perspective', ''), '00000000-0000-0000-0000-000000000000'), 'null')::uuid,
    numeric_metrics = jsonb_strip_nulls(jsonb_build_object(
        'critical',                         (record_data->'metrics'->>'critical')::int,
        'high',                             (record_data->'metrics'->>'high')::int,
        'medium',                           (record_data->'metrics'->>'medium')::int,
        'low',                              (record_data->'metrics'->>'low')::int,
        'unassigned',                       (record_data->'metrics'->>'unassigned')::int,
        'policyViolationsLicenseTotal',     (record_data->'metrics'->>'policyViolationsLicenseTotal')::int,
        'policyViolationsOperationalTotal', (record_data->'metrics'->>'policyViolationsOperationalTotal')::int,
        'policyViolationsSecurityTotal',    (record_data->'metrics'->>'policyViolationsSecurityTotal')::int
    ));

-- Enforce NOT NULL after backfill
ALTER TABLE rearm.analytics_metrics ALTER COLUMN org SET NOT NULL;
ALTER TABLE rearm.analytics_metrics ALTER COLUMN date_key SET NOT NULL;

-- Drop old JSONB-expression indexes
DROP INDEX IF EXISTS rearm.analytics_metrics_org_perspective_datekey_idx;
DROP INDEX IF EXISTS rearm.idx_analytics_metrics_org_created_date;

-- New native-column unique index (same null-perspective semantics as before)
CREATE UNIQUE INDEX analytics_metrics_org_perspective_datekey_idx
    ON rearm.analytics_metrics (org, date_key, COALESCE(perspective::text, ''));

-- Supporting range index
CREATE INDEX idx_analytics_metrics_org_date_key
    ON rearm.analytics_metrics (org, date_key);
