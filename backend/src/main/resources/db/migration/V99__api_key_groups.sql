-- ---------------------------------------------------------------------------
-- API key groups (TPF-91, child of TPF-86): a permission set of one
-- organization whose members are FREEFORM API keys of that organization. A
-- member key's effective permissions are its own grants union every ACTIVE
-- group holding it (AuthorizationService.keyPermissions). API keys never join
-- user groups (operator decision 2026-10-20); they have their own entity.
--
-- Shape mirrors user_groups (V6) deliberately -- same JSONB record_data
-- envelope, same revision/audit columns -- so the entity, the audit path and
-- the org/name indexes behave identically to every other record here.
--
-- No foreign keys, per the house rule (V77): member key uuids live in
-- record_data, validated on write by ApiKeyGroupService and tolerated when
-- dangling on read.
-- ---------------------------------------------------------------------------

CREATE TABLE rearm.api_key_groups (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE INDEX api_key_groups_org on rearm.api_key_groups ((record_data->>'org'));
CREATE INDEX api_key_groups_name on rearm.api_key_groups ((record_data->>'name'));

-- Name uniqueness is enforced per org in the DB as well as in the service, for
-- the same reason user_groups and teams do it: the service check is a read
-- followed by a write, so two concurrent creates can both pass it. Covers
-- inactive groups too -- restoring one must not collide with a name taken since.
CREATE UNIQUE INDEX api_key_groups_org_name on rearm.api_key_groups ((record_data->>'org'), (record_data->>'name'));
