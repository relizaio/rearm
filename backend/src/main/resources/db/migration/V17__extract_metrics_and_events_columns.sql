-- releases: add dedicated columns
ALTER TABLE rearm.releases ADD COLUMN IF NOT EXISTS metrics jsonb;
ALTER TABLE rearm.releases ADD COLUMN IF NOT EXISTS approval_events jsonb;
ALTER TABLE rearm.releases ADD COLUMN IF NOT EXISTS update_events jsonb;

-- releases: backfill from record_data
UPDATE rearm.releases
SET
    metrics         = record_data->'metrics',
    approval_events = record_data->'approvalEvents',
    update_events   = record_data->'updateEvents';

-- releases: strip extracted keys from record_data
UPDATE rearm.releases
SET record_data = record_data - 'metrics' - 'approvalEvents' - 'updateEvents';

-- artifacts: add dedicated column
ALTER TABLE rearm.artifacts ADD COLUMN IF NOT EXISTS metrics jsonb;

-- artifacts: backfill from record_data
UPDATE rearm.artifacts
SET metrics = record_data->'metrics'
WHERE record_data ? 'metrics';

-- artifacts: strip extracted key from record_data
UPDATE rearm.artifacts
SET record_data = record_data - 'metrics'
WHERE record_data ? 'metrics';
