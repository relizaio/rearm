CREATE TABLE rearm.acollections (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE UNIQUE INDEX acollection_release_version on rearm.acollections ( (record_data->>'version'), (record_data->>'release'));