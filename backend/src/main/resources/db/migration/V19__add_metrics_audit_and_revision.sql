-- Add metrics_revision column to releases and artifacts, initialized from current revision
ALTER TABLE rearm.releases ADD COLUMN IF NOT EXISTS metrics_revision integer NOT NULL DEFAULT 0;
UPDATE rearm.releases SET metrics_revision = revision WHERE metrics IS NOT NULL;

ALTER TABLE rearm.artifacts ADD COLUMN IF NOT EXISTS metrics_revision integer NOT NULL DEFAULT 0;
UPDATE rearm.artifacts SET metrics_revision = revision WHERE metrics IS NOT NULL;

-- Metrics audit table: records previous metrics before each update
CREATE TABLE rearm.metrics_audit (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    entity_type text NOT NULL,  -- 'RELEASE' or 'ARTIFACT'
    entity_uuid uuid NOT NULL,
    metrics_revision integer NOT NULL,
    revision_created_date timestamptz NOT NULL default now(),
    entity_created_date timestamptz NOT NULL,
    metrics jsonb
);

CREATE UNIQUE INDEX metrics_audit_entity_revision_idx ON rearm.metrics_audit (entity_type, entity_uuid, metrics_revision);
CREATE INDEX metrics_audit_entity_uuid_idx ON rearm.metrics_audit (entity_uuid);
