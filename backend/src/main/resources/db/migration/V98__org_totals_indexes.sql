-- TPF-70: org-scoped btree expression indexes for the Statistics totals statement
-- (VariableQueries.GET_NUMERIC_ANALYTICS_FOR_ORG) and the other org-filtered reads of these
-- tables. Plain builds (SHARE lock: reads continue, writes wait), see V73 and V80 for why not
-- CONCURRENTLY inside Flyway. Statistics of an expression index live with the index (V80).
CREATE INDEX IF NOT EXISTS releases_org_status_idx
    ON rearm.releases ((record_data->>'org'), (coalesce(record_data->>'status', 'ACTIVE')));
CREATE INDEX IF NOT EXISTS deliverables_org_status_idx
    ON rearm.deliverables ((record_data->>'org'), (coalesce(record_data->>'status', 'ACTIVE')));
CREATE INDEX IF NOT EXISTS source_code_entries_org_idx
    ON rearm.source_code_entries ((record_data->>'org'));
CREATE INDEX IF NOT EXISTS components_org_idx
    ON rearm.components ((record_data->>'org'));
CREATE INDEX IF NOT EXISTS branches_org_idx
    ON rearm.branches ((record_data->>'org'));
ANALYZE rearm.releases, rearm.deliverables, rearm.source_code_entries, rearm.components, rearm.branches;
