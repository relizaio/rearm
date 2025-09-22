-- V9: Migrate existing BOM data from JSONB to normalized tables

-- Step 0: Validate existing data before migration
DO $$
DECLARE
    total_boms INTEGER;
    null_meta_count INTEGER;
    missing_required_fields INTEGER;
    problematic_records TEXT[];
BEGIN
    -- Count total BOMs
    SELECT COUNT(*) INTO total_boms FROM rebom.boms;
    
    -- Check for NULL metadata
    SELECT COUNT(*) INTO null_meta_count FROM rebom.boms WHERE meta IS NULL OR meta = '{}'::jsonb;
    
    -- Check for missing required fields
    SELECT COUNT(*) INTO missing_required_fields 
    FROM rebom.boms 
    WHERE meta->>'serialNumber' IS NULL 
       OR meta->>'name' IS NULL 
       OR meta->>'group' IS NULL 
       OR meta->>'version' IS NULL;
    
    -- Collect problematic UUIDs
    SELECT array_agg(uuid::text) INTO problematic_records
    FROM rebom.boms 
    WHERE meta IS NULL 
       OR meta = '{}'::jsonb
       OR meta->>'serialNumber' IS NULL 
       OR meta->>'name' IS NULL 
       OR meta->>'group' IS NULL 
       OR meta->>'version' IS NULL;
    
    RAISE NOTICE 'Migration Validation Report:';
    RAISE NOTICE '  Total BOMs: %', total_boms;
    RAISE NOTICE '  BOMs with NULL/empty metadata: %', null_meta_count;
    RAISE NOTICE '  BOMs missing required fields: %', missing_required_fields;
    
    IF missing_required_fields > 0 THEN
        RAISE NOTICE '  Problematic BOM UUIDs: %', problematic_records;
        RAISE EXCEPTION 'Cannot proceed with migration: % BOMs have missing required fields', missing_required_fields;
    END IF;
    
    RAISE NOTICE 'Validation passed! Proceeding with migration...';
END $$;

-- Step 1: Migrate metadata from old boms table to new bom_metadata table
INSERT INTO rebom.bom_metadata (
    uuid, 
    serial_number, 
    name, 
    group_name, 
    version, 
    bom_version,
    belongs_to, 
    hash, 
    tld_only, 
    ignore_dev, 
    structure, 
    strip_bom,
    purl, 
    bom_digest, 
    notes, 
    mod, 
    bom_state,
    root_component_merge_mode, 
    organization, 
    created_date,
    last_updated_date, 
    source_format, 
    source_spdx_uuid, 
    public, 
    duplicate
)
SELECT 
    uuid,
    meta->>'serialNumber' as serial_number,
    meta->>'name' as name,
    meta->>'group' as group_name,
    meta->>'version' as version,
    COALESCE(meta->>'bomVersion', '1') as bom_version,
    meta->>'belongsTo' as belongs_to,
    meta->>'hash' as hash,
    COALESCE((meta->>'tldOnly')::boolean, false) as tld_only,
    COALESCE((meta->>'ignoreDev')::boolean, false) as ignore_dev,
    COALESCE(meta->>'structure', 'FLAT') as structure,
    COALESCE(
        CASE 
            WHEN meta->>'stripBom' = 'TRUE' THEN true
            WHEN meta->>'stripBom' = 'FALSE' THEN false
            WHEN (meta->>'stripBom')::boolean IS NOT NULL THEN (meta->>'stripBom')::boolean
            ELSE true
        END, 
        true
    ) as strip_bom,
    meta->>'purl' as purl,
    meta->>'bomDigest' as bom_digest,
    COALESCE(meta->>'notes', 'sent from ReArm') as notes,
    COALESCE(meta->>'mod', 'raw') as mod,
    COALESCE(meta->>'bomState', 'raw') as bom_state,
    meta->>'rootComponentMergeMode' as root_component_merge_mode,
    organization,
    created_date,
    last_updated_date,
    COALESCE(source_format, 'CYCLONEDX') as source_format,
    source_spdx_uuid,
    public,
    false as duplicate  -- Default value since original table doesn't have this column
FROM rebom.boms b
WHERE meta IS NOT NULL 
  AND meta->>'serialNumber' IS NOT NULL
  AND meta->>'name' IS NOT NULL
  AND meta->>'group' IS NOT NULL
  AND meta->>'version' IS NOT NULL
  AND meta->>'bomDigest' IS NOT NULL
  AND organization IS NOT NULL
  -- Handle duplicates by keeping only the most recent record per serial number
  AND b.uuid = (
    SELECT b2.uuid 
    FROM rebom.boms b2 
    WHERE b2.meta->>'serialNumber' = b.meta->>'serialNumber'
      AND b2.organization IS NOT NULL
    ORDER BY b2.created_date DESC 
    LIMIT 1
  );

-- Step 2: Create OCI references for migrated BOMs
-- IMPORTANT: The 'bom' column contains OCI push response metadata (NOT actual BOM content)
-- Actual BOM content is stored in OCI and fetched via ociService.fetchFromOci(tag)
-- The migration extracts OCI metadata from the push response to populate oci_references table
INSERT INTO rebom.bom_oci_references (
    uuid,
    oci_reference,
    oci_digest,
    oci_size_bytes,
    oci_media_type,
    content_hash,
    oci_pushed_date,
    created_date
)
SELECT 
    b.uuid,
    -- Generate OCI reference based on actual OCI service pattern
    -- Format: {REGISTRY_HOST}/{REGISTRY_NAMESPACE}/rebom-artifacts:rebom-{tag}
    -- Extract registry from PURL when available, otherwise use environment fallback
    CASE 
        -- Extract registry from PURL if available (production pattern)
        WHEN b.meta->>'purl' LIKE '%registry.relizahub.com%' AND b.meta->>'serialNumber' IS NOT NULL THEN 
            'registry.relizahub.com/library/rebom-artifacts:rebom-' || REPLACE(b.meta->>'serialNumber', 'urn:uuid:', '')
        -- Use environment fallback for test/dev records
        WHEN b.meta->>'serialNumber' IS NOT NULL THEN 
            'registry.test.relizahub.com/1ba15cdf-edc1-4c34-abf3-6c886c33ff77-private/rebom-artifacts:rebom-' || REPLACE(b.meta->>'serialNumber', 'urn:uuid:', '')
        -- Final fallback using UUID
        ELSE 'registry.test.relizahub.com/1ba15cdf-edc1-4c34-abf3-6c886c33ff77-private/rebom-artifacts:rebom-' || b.uuid::text
    END as oci_reference,
    
    -- Extract OCI digest - handle both ociResponse wrapper and direct manifest
    COALESCE(
        b.bom->'ociResponse'->>'digest',  -- Newer format with ociResponse wrapper
        b.bom->>'digest',                 -- Older format with direct manifest
        CASE 
            WHEN b.meta->>'bomDigest' IS NOT NULL AND NOT (b.meta->>'bomDigest' LIKE 'sha256:%')
            THEN CONCAT('sha256:', b.meta->>'bomDigest')
            ELSE b.meta->>'bomDigest'
        END                               -- Final fallback to bomDigest (add sha256: prefix if needed)
    ) as oci_digest,
    
    -- Extract size - handle both formats
    COALESCE(
        (b.bom->'ociResponse'->>'size')::integer,  -- Newer format
        (b.bom->>'size')::integer,                 -- Older format
        LENGTH(b.bom::text),                       -- Fallback to JSON length
        1024                                       -- Final fallback
    ) as oci_size_bytes,
    
    -- Extract media type - handle both formats
    COALESCE(
        b.bom->'ociResponse'->>'mediaType',        -- Newer format
        b.bom->>'mediaType',                       -- Older format
        'application/vnd.cyclonedx+json'           -- Default fallback
    ) as oci_media_type,
    
    -- Content hash (bomDigest - identifies unique BOM content after stripping metadata)
    COALESCE(b.meta->>'bomDigest', encode(sha256(b.uuid::text::bytea), 'hex')) as content_hash,
    
    -- Use creation date as push date
    b.created_date as oci_pushed_date,
    b.created_date as created_date
    
FROM rebom.boms b
WHERE EXISTS (
    SELECT 1 FROM rebom.bom_metadata m WHERE m.uuid = b.uuid
);

-- Step 3: Migration Summary and Analysis
DO $$
DECLARE
    old_count INTEGER;
    new_metadata_count INTEGER;
    new_oci_count INTEGER;
    excluded_null_org INTEGER;
    excluded_missing_meta INTEGER;
    excluded_duplicates INTEGER;
    migration_success_rate NUMERIC;
BEGIN
    -- Count original and migrated records
    SELECT COUNT(*) INTO old_count FROM rebom.boms;
    SELECT COUNT(*) INTO new_metadata_count FROM rebom.bom_metadata;
    SELECT COUNT(*) INTO new_oci_count FROM rebom.bom_oci_references;
    
    -- Analyze what was excluded from migration
    SELECT COUNT(*) INTO excluded_null_org 
    FROM rebom.boms WHERE organization IS NULL;
    
    SELECT COUNT(*) INTO excluded_missing_meta 
    FROM rebom.boms 
    WHERE meta IS NULL 
       OR meta->>'serialNumber' IS NULL
       OR meta->>'name' IS NULL
       OR meta->>'group' IS NULL
       OR meta->>'version' IS NULL
       OR meta->>'bomDigest' IS NULL;
    
    -- Calculate duplicate count (records with same serial number)
    SELECT COUNT(*) - COUNT(DISTINCT meta->>'serialNumber') INTO excluded_duplicates
    FROM rebom.boms 
    WHERE meta->>'serialNumber' IS NOT NULL AND organization IS NOT NULL;
    
    -- Calculate migration success rate
    migration_success_rate := ROUND((new_metadata_count::NUMERIC / old_count::NUMERIC) * 100, 2);
    
    RAISE NOTICE 'Migration Summary: %/% records migrated (%%)', new_metadata_count, old_count, migration_success_rate;
    RAISE NOTICE 'Excluded: % NULL orgs, % missing metadata, % duplicates', excluded_null_org, excluded_missing_meta, excluded_duplicates;
    
    IF new_oci_count != new_metadata_count THEN
        RAISE WARNING 'Mismatch: metadata=%, oci=%', new_metadata_count, new_oci_count;
    END IF;
    
    IF migration_success_rate < 90 THEN
        RAISE NOTICE 'Note: Some records excluded due to data quality issues';
    END IF;
    
    RAISE NOTICE 'Migration completed successfully - original table preserved';
END $$;
