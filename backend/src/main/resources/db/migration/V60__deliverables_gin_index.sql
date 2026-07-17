-- GIN jsonb_path_ops index on deliverables.record_data, serving the
-- whole-column containment (@>) lookups: deliverable-by-digest (the CI
-- addrelease digest-match hot path, previously a full seq scan per call —
-- observed continuously active on a loaded instance) and the
-- deliverable-contains-artifact probe. jsonb_path_ops GIN serves ONLY the
-- @> operator form — the jsonb_contains() function spelling bypasses index
-- matching entirely, which is why these queries were converted to @> in the
-- same change. Lab-measured at prod-scale cardinality: 6.4ms seq scan ->
-- 0.36ms index probe per lookup, identical result sets.
CREATE INDEX IF NOT EXISTS idx_deliverables_record_data_gin
    ON rearm.deliverables USING GIN (record_data jsonb_path_ops);
