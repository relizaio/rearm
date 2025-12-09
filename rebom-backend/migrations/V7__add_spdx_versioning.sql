-- Add versioning field to spdx_boms table
ALTER TABLE spdx_boms 
ADD COLUMN bom_version integer DEFAULT 1;

-- Ensure documentNamespace is unique per organization
CREATE UNIQUE INDEX spdx_bom_namespace_org_unique_index 
ON spdx_boms((spdx_metadata->>'documentNamespace'), organization);

-- Backfill existing records
UPDATE spdx_boms SET bom_version = 1 WHERE bom_version IS NULL;
