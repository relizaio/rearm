-- Drop the old unique index that only considers org and dateKey
DROP INDEX IF EXISTS rearm.analytics_metrics_org_datekey_idx;

-- Create new unique index that includes perspective in the composite key
-- Using COALESCE to handle null perspective values (org-wide analytics have null perspective)
-- This allows both org-wide analytics (perspective = null) and perspective-specific analytics
-- to coexist with the same dateKey
CREATE UNIQUE INDEX analytics_metrics_org_perspective_datekey_idx 
    ON rearm.analytics_metrics ( 
        (record_data->>'org'), 
        COALESCE((record_data->>'perspective'), ''), 
        (record_data->>'dateKey') 
    );
