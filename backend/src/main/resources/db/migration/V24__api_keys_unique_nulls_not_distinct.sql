-- Fix duplicate api_keys rows for the same (object_uuid, object_type, org) when key_order IS NULL.
-- Two issues addressed:
-- 1. Postgres UNIQUE treats NULLs as distinct by default, so duplicates with key_order=NULL slipped through.
-- 2. App-side bug in setObjectApiKey caused new rows instead of updates when supplied org was null.

-- Step 1: Dedupe - keep the newest row per (object_uuid, object_type, org, COALESCE(key_order,''))
WITH ranked AS (
    SELECT uuid,
           ROW_NUMBER() OVER (
               PARTITION BY object_uuid, object_type, org, COALESCE(key_order, '')
               ORDER BY created_date DESC, uuid DESC
           ) AS rn
    FROM rearm.api_keys
)
DELETE FROM rearm.api_keys
WHERE uuid IN (SELECT uuid FROM ranked WHERE rn > 1);

-- Step 2: Replace the existing UNIQUE constraint with NULLS NOT DISTINCT (Postgres 15+).
ALTER TABLE rearm.api_keys DROP CONSTRAINT IF EXISTS api_keys_uuid_type;
ALTER TABLE rearm.api_keys
    ADD CONSTRAINT api_keys_uuid_type
    UNIQUE NULLS NOT DISTINCT (object_uuid, object_type, org, key_order);
