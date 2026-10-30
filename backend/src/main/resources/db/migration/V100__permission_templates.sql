-- ---------------------------------------------------------------------------
-- Permission templates (TPF-87, child TPF-112): a user's named, saved
-- permission set in one organization, which the UI applies by value into the
-- device-login approval and Free Form key request editors. Owned per user per
-- organization; only the owner reads or writes it.
--
-- A template grants nothing by itself: what is approved from it goes through
-- approveCliLogin and is clamped to the approver's own permissions at that
-- moment (AuthorizationService.clampToOwner), which is the control. The
-- template is never read server-side at approval.
--
-- Shape mirrors api_key_groups (V99) deliberately -- same JSONB record_data
-- envelope, same revision/audit columns -- so the entity, the audit path and
-- the indexes behave identically to every other record here.
--
-- No foreign keys, per the house rule (V77): the user, the organization and
-- the grant object uuids live in record_data.
-- ---------------------------------------------------------------------------

CREATE TABLE rearm.permission_templates (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE INDEX permission_templates_user_org on rearm.permission_templates ((record_data->>'user'), (record_data->>'org'));

-- Name uniqueness is enforced per (user, org) in the DB as well as in the
-- service: the service check is a read followed by a write, so two concurrent
-- creates can both pass it.
CREATE UNIQUE INDEX permission_templates_user_org_name on rearm.permission_templates ((record_data->>'user'), (record_data->>'org'), (record_data->>'name'));
