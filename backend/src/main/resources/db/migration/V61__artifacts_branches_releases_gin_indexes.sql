-- GIN jsonb_path_ops indexes serving the whole-column containment (@>)
-- lookups converted from jsonb_contains() function form (which bypasses
-- operator-based index matching entirely). Lab-measured at prod-scale
-- cardinalities, per lookup:
--   artifacts by stored digest:        34ms seq scan -> 0.05ms
--   branches by dependency component:   7ms seq scan -> 0.06ms
--   releases by artifact:              10ms seq scan -> 0.02ms
--   releases by identifier:            15ms seq scan -> 0.05ms
-- record_data is the low-churn column on all three tables (high-frequency
-- scan metrics live in the separate metrics column), so index write
-- amplification is bounded to entity create/edit.
CREATE INDEX IF NOT EXISTS idx_artifacts_record_data_gin
    ON rearm.artifacts USING GIN (record_data jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_branches_record_data_gin
    ON rearm.branches USING GIN (record_data jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_releases_record_data_gin
    ON rearm.releases USING GIN (record_data jsonb_path_ops);
