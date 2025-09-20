CREATE TABLE rearm.user_groups (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE INDEX user_groups_org on rearm.user_groups ((record_data->>'org'));
CREATE INDEX user_groups_name on rearm.user_groups ((record_data->>'name'));
CREATE UNIQUE INDEX user_groups_org_name on rearm.user_groups ((record_data->>'org'), (record_data->>'name'));
