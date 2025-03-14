CREATE TABLE boms (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    meta text NULL,
    bom jsonb NULL,
    tags jsonb NULL,
    organization uuid NULL,
    public boolean NOT NULL default false
);

CREATE UNIQUE INDEX bom_urn_unique_index on rebom.boms ( (bom->>'serialNumber') );

CREATE UNIQUE INDEX bom_version_component_unique_index on 
    rebom.boms ( (bom->>'version'), (bom->'metadata'->'component'->>'group'), 
    (bom->'metadata'->'component'->>'name'), (bom->'metadata'->'component'->>'version') );
