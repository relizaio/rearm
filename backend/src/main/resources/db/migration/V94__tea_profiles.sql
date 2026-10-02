-- TEA profiles (task TEA-2, part 1 of the TEA 1.0.0 publication work).
--
-- One wholesale set of Transparency Exchange publication settings per scope: the organization
-- (object NULL), a perspective (object = perspective uuid) or a component (object = component
-- uuid). The key columns are written from the record on every save and never edited on their own;
-- everything else lives in record_data. NULLS NOT DISTINCT makes the organization row unique too
-- (precedent V24). No FOREIGN KEY constraints per repo convention: the service layer deletes a
-- perspective's rows with the perspective, and reads tolerate dangling references.

CREATE TABLE rearm.tea_profiles (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    scope TEXT NOT NULL,
    object UUID,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL,
    CONSTRAINT tea_profiles_org_scope_object UNIQUE NULLS NOT DISTINCT (org, scope, object)
);
CREATE INDEX tea_profiles_org ON rearm.tea_profiles (org);

-- The TEA-facing ids. Minted once by the server (the organization on its first ENABLED profile
-- save, a component on its first publish) and never edited; the TEA request path looks orgs and
-- components up by them, and nothing on TEA carries an internal uuid.
CREATE UNIQUE INDEX organizations_tea_uuid ON rearm.organizations ((record_data->>'teaUuid'))
    WHERE record_data->>'teaUuid' IS NOT NULL;
CREATE UNIQUE INDEX components_tea_uuid ON rearm.components ((record_data->>'teaUuid'))
    WHERE record_data->>'teaUuid' IS NOT NULL;
