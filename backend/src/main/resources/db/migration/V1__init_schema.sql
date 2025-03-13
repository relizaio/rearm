CREATE SCHEMA IF NOT EXISTS rearm;

CREATE TABLE rearm.organizations (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.users (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb,
    last_logout_date timestamptz NULL
);

CREATE TABLE rearm.components (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.branches (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.releases (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE UNIQUE INDEX releases_component_version on rearm.releases ((record_data->>'component'), (record_data->>'version'));
CREATE UNIQUE INDEX releases_component_marketing_version on rearm.releases ((record_data->>'component'), (record_data->>'marketingVersion'));

CREATE TABLE rearm.marketing_releases (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.variants (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.source_code_entries (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.vcs_repositories (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.artifacts (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.deliverables (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.version_assignments (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    component uuid NOT NULL,
    branch uuid NOT NULL,
    version text NOT NULL,
    version_schema text NULL,
    release uuid NULL,
    branch_schema text NULL,
    assignment_type text NOT NULL,
    version_type text NOT NULL,
    unique (branch, version)
);

ALTER TABLE rearm.version_assignments ADD CONSTRAINT version_assignments_component_version_and_type_key UNIQUE (component, version, version_type);

-- Table to store api key hashes
CREATE TABLE rearm.api_keys (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    object_uuid uuid NOT NULL,
    object_type text NOT NULL,
    api_key text NULL,
    org uuid NOT NULL,
    record_data jsonb NULL,
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    key_order text NULL
);

ALTER TABLE rearm.api_keys ADD CONSTRAINT api_keys_uuid_type UNIQUE (object_uuid, object_type, org, key_order);

CREATE TABLE rearm.api_key_access (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    access_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    notes text NULL,
    ip_address text NOT NULL,
    api_key_id text NULL,
    api_key_uuid uuid NOT NULL
);

CREATE INDEX api_last_access_idx ON rearm.api_key_access (api_key_uuid, access_date DESC);

-- Resource Groups are logical split of organization, they are essentially sub-organizations
CREATE TABLE rearm.resource_groups (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);


-- Audit table - keeps track of all revisions of all entities
CREATE TABLE rearm.audit (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    entity_name varchar(100) NOT NULL, --table name
    entity_uuid uuid NOT NULL,
    revision integer NOT NULL,
    schema_version integer NOT NULL,
    revision_created_date timestamptz NOT NULL,
    entity_created_date timestamptz NOT NULL,
    revision_record_data jsonb
);

ALTER TABLE rearm.audit ADD CONSTRAINT audit_revision_unique UNIQUE (entity_name, entity_uuid, revision);

CREATE TABLE rearm.system_info (
  id INTEGER PRIMARY KEY,
  data JSONB
);

CREATE TABLE rearm.integrations (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE UNIQUE INDEX integration_type_identifier_org_idx on rearm.integrations ( (record_data->>'org'), (record_data->>'type'), (record_data->>'identifier') );

CREATE TABLE rearm.approval_entries (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE TABLE rearm.approval_policies (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

-- some seed data
-- 1. Organization for external components
insert into rearm.organizations (uuid, record_data) values ('00000000-0000-0000-0000-000000000000', '{"name":"External Public Components"}');
insert into rearm.organizations (uuid, record_data) values ('00000000-0000-0000-0000-000000000001', '{"name":"User Organization"}');
