CREATE INDEX IF NOT EXISTS idx_releases_branch_created_date ON rearm.releases ((record_data->>'branch'), created_date DESC);

CREATE INDEX IF NOT EXISTS idx_analytics_metrics_org_created_date ON rearm.analytics_metrics ((record_data->>'org'), created_date);