-- ---------------------------------------------------------------------------
-- Teams: the org's addressable unit, split out of UserGroup.
--
-- Until now a "notification team" WAS a permission UserGroup -- notification
-- channels, member roles and external contacts were bolted onto an entity whose
-- real job is permissions and resourceGroup. See
-- ai-plans/team-entity-design.md for the full argument; the short version is
-- that four different structures already answered "who is attached to this
-- thing", and UserGroup accumulated team fields precisely because there was no
-- team concept to put them in.
--
-- This migration only CREATES the table. Nothing reads it yet: the fan-out's
-- route targets, owner-team channel resolution and ComponentOwnerType.TEAM all
-- still point at user_groups, and are switched in a later phase. Keeping the
-- two apart makes that switch a single reviewable change rather than a
-- create-and-cut-over in one go.
--
-- Shape mirrors user_groups (V6) deliberately -- same JSONB record_data
-- envelope, same revision/audit columns -- so the entity, the audit path and
-- the org/name indexes all behave identically to every other record here. A
-- team holds: members (individual users), userGroups (CONTAINED permission
-- groups), notificationChannels, and leads.
--
-- No foreign keys, per the house rule: every reference above is a uuid inside
-- record_data, validated on write by TeamService and tolerated when dangling on
-- read.
-- ---------------------------------------------------------------------------

CREATE TABLE rearm.teams (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb
);

CREATE INDEX teams_org on rearm.teams ((record_data->>'org'));
CREATE INDEX teams_name on rearm.teams ((record_data->>'name'));

-- Name uniqueness is enforced per org in the DB as well as in the service, for
-- the same reason user_groups does it: the service check is a read followed by
-- a write, so two concurrent creates can both pass it. Covers archived teams
-- too -- restoring one must not collide with a name taken since.
CREATE UNIQUE INDEX teams_org_name on rearm.teams ((record_data->>'org'), (record_data->>'name'));
