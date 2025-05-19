-- Step 1: Add a new metadata column of type jsonb
ALTER TABLE rebom.boms ADD COLUMN metadata jsonb;

-- Step 2: Update the temporary column with the existing meta data
UPDATE rebom.boms SET metadata = jsonb_build_object('notes', meta);

-- Step 3: Drop the old meta column
ALTER TABLE rebom.boms DROP COLUMN meta;

ALTER TABLE rebom.boms RENAME COLUMN metadata TO meta;