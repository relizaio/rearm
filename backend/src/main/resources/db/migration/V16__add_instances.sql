CREATE TABLE rearm.instances (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    revision_plan integer NOT NULL default 0,
    revision_actual integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    last_updated_date_plan timestamptz NOT NULL default now(),
    last_updated_date_actual timestamptz NOT NULL default now(),
    record_data jsonb,
    record_data_plan jsonb,
    record_data_actual jsonb
);

CREATE TABLE rearm.instance_audit (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    entity_name varchar(100) NOT NULL,
    entity_uuid uuid NOT NULL,
    revision integer NOT NULL,
    schema_version integer NOT NULL,
    revision_created_date timestamptz NOT NULL,
    entity_created_date timestamptz NOT NULL,
    revision_record_data jsonb
);

ALTER TABLE rearm.instance_audit ADD CONSTRAINT instance_audit_revision_unique UNIQUE (entity_name, entity_uuid, revision);

CREATE TABLE rearm.properties (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.secrets (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE UNIQUE INDEX secrets_org_name on rearm.secrets ( (record_data->>'org'), (record_data->>'name') );
CREATE UNIQUE INDEX properties_org_name_target on rearm.properties ( (record_data->>'org'), (record_data->>'name'), (record_data->>'targetType') );