-- ============================================================================
-- MIGRATION VALIDATION SCRIPT
-- Test migration logic against production backup data
-- ============================================================================

-- Create temporary test database from backup
-- Run this after restoring backup to a test database

-- Step 1: Analyze current production data patterns
SELECT 
    'Production Data Analysis' as analysis_type,
    COUNT(*) as total_boms,
    COUNT(*) FILTER (WHERE meta IS NOT NULL) as has_metadata,
    COUNT(*) FILTER (WHERE meta->>'serialNumber' IS NOT NULL) as has_serial_number,
    COUNT(*) FILTER (WHERE meta->>'name' IS NOT NULL) as has_name,
    COUNT(*) FILTER (WHERE meta->>'group' IS NOT NULL) as has_group,
    COUNT(*) FILTER (WHERE meta->>'bomDigest' IS NOT NULL) as has_bom_digest,
    COUNT(*) FILTER (WHERE bom IS NOT NULL) as has_bom_column,
    COUNT(*) FILTER (WHERE bom->>'digest' IS NOT NULL) as has_oci_digest,
    COUNT(*) FILTER (WHERE bom->>'size' IS NOT NULL) as has_oci_size
FROM rebom.boms;

-- Step 2: Check stripBom field variations
SELECT 
    'stripBom Field Analysis' as analysis_type,
    meta->>'stripBom' as strip_bom_value,
    COUNT(*) as count
FROM rebom.boms 
WHERE meta->>'stripBom' IS NOT NULL
GROUP BY meta->>'stripBom'
ORDER BY count DESC;

-- Step 3: Check hash field variations  
SELECT 
    'Hash Field Analysis' as analysis_type,
    CASE 
        WHEN meta->>'hash' LIKE 'sha256:%' THEN 'with_sha256_prefix'
        WHEN meta->>'hash' IS NOT NULL THEN 'without_sha256_prefix'
        ELSE 'null_hash'
    END as hash_type,
    COUNT(*) as count
FROM rebom.boms
GROUP BY 
    CASE 
        WHEN meta->>'hash' LIKE 'sha256:%' THEN 'with_sha256_prefix'
        WHEN meta->>'hash' IS NOT NULL THEN 'without_sha256_prefix'
        ELSE 'null_hash'
    END
ORDER BY count DESC;

-- Step 4: Validate OCI reference generation logic
SELECT 
    'OCI Reference Generation Test' as test_type,
    uuid,
    meta->>'serialNumber' as original_serial,
    CASE 
        WHEN meta->>'serialNumber' IS NOT NULL 
        THEN 'registry.test.relizahub.com/1ba15cdf-edc1-4c34-abf3-6c886c33ff77-private/bom:' || REPLACE(meta->>'serialNumber', 'urn:uuid:', '')
        ELSE 'registry.test.relizahub.com/1ba15cdf-edc1-4c34-abf3-6c886c33ff77-private/bom:' || uuid::text
    END as generated_oci_reference
FROM rebom.boms 
LIMIT 5;

-- Step 5: Test stripBom conversion logic
SELECT 
    'stripBom Conversion Test' as test_type,
    meta->>'stripBom' as original_value,
    COALESCE(
        CASE 
            WHEN meta->>'stripBom' = 'TRUE' THEN true
            WHEN meta->>'stripBom' = 'FALSE' THEN false
            WHEN (meta->>'stripBom')::boolean IS NOT NULL THEN (meta->>'stripBom')::boolean
            ELSE true
        END, 
        true
    ) as converted_value,
    COUNT(*) as count
FROM rebom.boms
GROUP BY 
    meta->>'stripBom',
    COALESCE(
        CASE 
            WHEN meta->>'stripBom' = 'TRUE' THEN true
            WHEN meta->>'stripBom' = 'FALSE' THEN false
            WHEN (meta->>'stripBom')::boolean IS NOT NULL THEN (meta->>'stripBom')::boolean
            ELSE true
        END, 
        true
    )
ORDER BY count DESC;

-- Step 6: Check for any problematic records
SELECT 
    'Problematic Records Check' as check_type,
    uuid,
    CASE 
        WHEN meta IS NULL THEN 'null_metadata'
        WHEN meta->>'serialNumber' IS NULL THEN 'missing_serial_number'
        WHEN meta->>'name' IS NULL THEN 'missing_name'
        WHEN meta->>'group' IS NULL THEN 'missing_group'
        WHEN meta->>'bomDigest' IS NULL THEN 'missing_bom_digest'
        ELSE 'ok'
    END as issue_type
FROM rebom.boms
WHERE meta IS NULL 
   OR meta->>'serialNumber' IS NULL 
   OR meta->>'name' IS NULL 
   OR meta->>'group' IS NULL 
   OR meta->>'bomDigest' IS NULL
LIMIT 10;

-- Step 7: Validate OCI data extraction
SELECT 
    'OCI Data Extraction Test' as test_type,
    uuid,
    bom->>'digest' as extracted_oci_digest,
    (bom->>'size')::integer as extracted_oci_size,
    bom->>'mediaType' as extracted_media_type,
    meta->>'bomDigest' as bom_digest_fallback
FROM rebom.boms 
WHERE bom IS NOT NULL
LIMIT 5;

-- Step 8: Final migration readiness check
SELECT 
    'Migration Readiness Summary' as summary_type,
    COUNT(*) as total_records,
    COUNT(*) FILTER (
        WHERE meta IS NOT NULL 
        AND meta->>'serialNumber' IS NOT NULL 
        AND meta->>'name' IS NOT NULL 
        AND meta->>'group' IS NOT NULL 
        AND meta->>'bomDigest' IS NOT NULL
    ) as migration_ready_records,
    COUNT(*) - COUNT(*) FILTER (
        WHERE meta IS NOT NULL 
        AND meta->>'serialNumber' IS NOT NULL 
        AND meta->>'name' IS NOT NULL 
        AND meta->>'group' IS NOT NULL 
        AND meta->>'bomDigest' IS NOT NULL
    ) as problematic_records
FROM rebom.boms;
