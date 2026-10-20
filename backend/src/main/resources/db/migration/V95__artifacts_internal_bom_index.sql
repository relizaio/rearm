-- SBOM serialNumber lineage (SCORE-47, child 45b of SCORE-45): every upload is checked against the
-- components that already hold its serialNumber (internalBom.id), and the one-shot backfill lists
-- every serialNumber of an organization. Both read artifacts by org and internalBom.id, which no
-- existing index covers (V67 covers type and created_date).
CREATE INDEX IF NOT EXISTS idx_artifacts_org_internal_bom
    ON rearm.artifacts ((record_data->>'org'), (record_data->'internalBom'->>'id'))
    WHERE record_data->>'type' = 'BOM';
