-- ============================================================================
-- V10: Drop old boms table - POINT OF NO RETURN
-- HIGH RISK: Ensure backup is taken and migration validated before running!
-- ============================================================================

-- Final validation before dropping
DO $$
DECLARE
    old_count INTEGER;
    new_metadata_count INTEGER;
    new_oci_count INTEGER;
    sample_uuid UUID;
    sample_metadata RECORD;
    sample_oci RECORD;
BEGIN
    -- Count records
    SELECT COUNT(*) INTO old_count FROM rebom.boms;
    SELECT COUNT(*) INTO new_metadata_count FROM rebom.bom_metadata;
    SELECT COUNT(*) INTO new_oci_count FROM rebom.bom_oci_references;
    
    RAISE NOTICE 'Final validation before dropping old table:';
    RAISE NOTICE '  Old boms table: % records', old_count;
    RAISE NOTICE '  New metadata table: % records', new_metadata_count;
    RAISE NOTICE '  New OCI references table: % records', new_oci_count;
    
    -- Validate counts match
    IF new_metadata_count != old_count OR new_oci_count != old_count THEN
        RAISE EXCEPTION 'Record counts do not match! Aborting table drop.';
    END IF;
    
    -- Sample validation - check a few records
    SELECT uuid INTO sample_uuid FROM rebom.boms LIMIT 1;
    
    SELECT * INTO sample_metadata FROM rebom.bom_metadata WHERE uuid = sample_uuid;
    SELECT * INTO sample_oci FROM rebom.bom_oci_references WHERE uuid = sample_uuid;
    
    IF sample_metadata.uuid IS NULL THEN
        RAISE EXCEPTION 'Sample metadata record not found! Aborting table drop.';
    END IF;
    
    IF sample_oci.uuid IS NULL THEN
        RAISE EXCEPTION 'Sample OCI reference not found! Aborting table drop.';
    END IF;
    
    RAISE NOTICE 'Validation passed. Proceeding with table drop.';
END $$;

-- Drop old indexes first
DROP INDEX IF EXISTS rebom.bom_urn_unique_index;
DROP INDEX IF EXISTS rebom.bom_version_component_unique_index;

-- Rename old table for safety (in case we need to rollback quickly)
ALTER TABLE rebom.boms RENAME TO boms_backup_v10;

-- Add comment to backup table
COMMENT ON TABLE rebom.boms_backup_v10 IS 'Backup of original boms table from V10 migration. Safe to drop after validation period.';

RAISE NOTICE 'Old boms table renamed to boms_backup_v10';
RAISE NOTICE 'Migration to normalized schema completed successfully!';
RAISE NOTICE 'IMPORTANT: Validate application functionality before dropping boms_backup_v10';
