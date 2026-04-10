ALTER TABLE rearm.api_keys ADD COLUMN IF NOT EXISTS created_by uuid NULL;
UPDATE rearm.api_keys SET created_by = (record_data->>'lastUpdatedBy')::uuid WHERE record_data->>'lastUpdatedBy' IS NOT NULL;
