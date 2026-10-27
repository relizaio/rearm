-- TEA publication, SBOM profiles and regulatory submissions (2026-10-TEA board: TEA-2, TEA-5, TEA-7,
-- TEA-27, TEA-28, TEA-33, TEA-35). One net-schema migration, written as the final schema the board's
-- V97 to V103 chain arrived at; rerunnable (IF NOT EXISTS, CREATE OR REPLACE, DROP TRIGGER IF EXISTS),
-- no data step: no database holds TEA rows before this migration.
--
-- Scoped rows (tea_profiles, sbom_profiles, package_settings) belong to the organization (object
-- NULL), a perspective (object = perspective uuid) or a component (object = component uuid). Their
-- key columns are written from the record on every save and never edited on their own; everything
-- else lives in record_data. NULLS NOT DISTINCT makes the organization row unique too (precedent
-- V24). No FOREIGN KEY constraints per repo convention: the service layer deletes a perspective's
-- rows with the perspective, reads tolerate dangling references, and the composer is the only
-- writer of the two submission tables.

-- TEA profiles (TEA-2): one wholesale set of Transparency Exchange publication settings per scope;
-- the SBOM recipe lives in the named SBOM profiles below, the TEA row names them.
CREATE TABLE IF NOT EXISTS rearm.tea_profiles (
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
CREATE INDEX IF NOT EXISTS tea_profiles_org ON rearm.tea_profiles (org);

-- The TEA-facing ids. Minted once by the server (the organization on its first ENABLED profile
-- save, a component on its first publish, an artifact when a publication first names its row) and
-- never edited; the TEA request path looks orgs, components and artifacts up by them, and nothing
-- on TEA carries an internal uuid.
CREATE UNIQUE INDEX IF NOT EXISTS organizations_tea_uuid ON rearm.organizations ((record_data->>'teaUuid'))
    WHERE record_data->>'teaUuid' IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS components_tea_uuid ON rearm.components ((record_data->>'teaUuid'))
    WHERE record_data->>'teaUuid' IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS artifacts_tea_uuid ON rearm.artifacts ((record_data->>'teaUuid'))
    WHERE record_data->>'teaUuid' IS NOT NULL;

-- TEA publications (TEA-5): tea_publications is the mutable head, one row per published release:
-- its uuid is the TEA release id and the TEA collection id. tea_collections holds one immutable row
-- per collection version, with the canonical collection document exactly as TEA serves it and its
-- sha256; the signature slot is reserved and stays NULL.
CREATE TABLE IF NOT EXISTS rearm.tea_publications (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    release UUID NOT NULL,
    component UUID NOT NULL,
    state TEXT NOT NULL,
    latest_version INTEGER NOT NULL DEFAULT 0,
    profile UUID NOT NULL,
    profile_revision INTEGER NOT NULL DEFAULT 0,
    cascade_of UUID,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL,
    CONSTRAINT tea_publications_release UNIQUE (release)
);
CREATE INDEX IF NOT EXISTS tea_publications_org_state ON rearm.tea_publications (org, state);
CREATE INDEX IF NOT EXISTS tea_publications_component ON rearm.tea_publications (component);
CREATE INDEX IF NOT EXISTS tea_publications_cascade_of ON rearm.tea_publications (cascade_of)
    WHERE cascade_of IS NOT NULL;

CREATE TABLE IF NOT EXISTS rearm.tea_collections (
    uuid UUID NOT NULL,
    version INTEGER NOT NULL,
    org UUID NOT NULL,
    release UUID NOT NULL,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by UUID,
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL,
    document TEXT NOT NULL,
    document_sha256 TEXT NOT NULL,
    signature TEXT,
    CONSTRAINT tea_collections_pk PRIMARY KEY (uuid, version)
);
CREATE INDEX IF NOT EXISTS tea_collections_release ON rearm.tea_collections (release);

-- The TEA act queue (TEA-7): its drain finder (ReleaseRepository.findUuidsOfReleasesPendingTeaAct)
-- reads only releases with a pending TEA act; a partial index keeps it an index range scan on an
-- instance with many releases and an empty queue (the shape of V25's reconcile index).
CREATE INDEX IF NOT EXISTS releases_tea_act_requested
    ON rearm.releases ((flow_control->>'teaActRequestedAt'))
    WHERE flow_control->>'teaActRequestedAt' IS NOT NULL;

-- SBOM profiles (TEA-27, TEA-28): a named SBOM profile is the recipe of an aggregated SBOM at a
-- scope. Channels (TEA publishing, regulatory packages) name profiles; a lower scope overrides a
-- higher one by defining the same name.
CREATE TABLE IF NOT EXISTS rearm.sbom_profiles (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    scope TEXT NOT NULL,
    object UUID,
    name TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL,
    CONSTRAINT sbom_profiles_org_scope_object_name UNIQUE NULLS NOT DISTINCT (org, scope, object, name)
);
CREATE INDEX IF NOT EXISTS sbom_profiles_org ON rearm.sbom_profiles (org);

-- Regulatory packages (TEA-33): package_settings holds one row per (org, scope, object, kind),
-- resolved by the scoped-settings engine of TEA-27: the SBOM profile name feeding the aggregate and
-- per-part options.
CREATE TABLE IF NOT EXISTS rearm.package_settings (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    scope TEXT NOT NULL,
    object UUID,
    kind TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL,
    CONSTRAINT package_settings_key UNIQUE NULLS NOT DISTINCT (org, scope, object, kind)
);
CREATE INDEX IF NOT EXISTS package_settings_org ON rearm.package_settings (org);

-- Submissions (TEA-33, TEA-35): a head per release and kind with immutable versions.
CREATE TABLE IF NOT EXISTS rearm.submissions (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    release UUID NOT NULL,
    component UUID NOT NULL,
    kind TEXT NOT NULL,
    state TEXT NOT NULL,
    latest_version INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL,
    CONSTRAINT submissions_release_kind UNIQUE (release, kind)
);
CREATE INDEX IF NOT EXISTS submissions_component_kind ON rearm.submissions (component, kind);
CREATE INDEX IF NOT EXISTS submissions_org_state ON rearm.submissions (org, state);

CREATE TABLE IF NOT EXISTS rearm.submission_versions (
    uuid UUID NOT NULL,
    version INTEGER NOT NULL,
    org UUID NOT NULL,
    release UUID NOT NULL,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by UUID,
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL,
    manifest TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    signature TEXT,
    CONSTRAINT submission_versions_pk PRIMARY KEY (uuid, version)
);
CREATE INDEX IF NOT EXISTS submission_versions_release ON rearm.submission_versions (release);

-- Submission versions are immutable (TEA-35, operator decision 2026-10-20): no UPDATE and no DELETE
-- on rearm.submission_versions, except the one-time fill of a null signature (the reserved slot)
-- that changes nothing else. Application code never updates or deletes a version (the entity is
-- insert-only, the repository has no update or delete); this trigger binds every other SQL path
-- too. A future data migration of record_data disables the trigger for its own statement
-- (ALTER TABLE ... DISABLE TRIGGER) and re-enables it.
CREATE OR REPLACE FUNCTION rearm.submission_versions_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'submission_versions is immutable: delete of % version % refused', OLD.uuid, OLD.version
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.signature IS NULL AND NEW.signature IS NOT NULL
        AND NEW.uuid = OLD.uuid AND NEW.version = OLD.version AND NEW.org = OLD.org AND NEW.release = OLD.release
        AND NEW.created_date = OLD.created_date AND NEW.created_by IS NOT DISTINCT FROM OLD.created_by
        AND NEW.schema_version = OLD.schema_version AND NEW.record_data = OLD.record_data
        AND NEW.manifest = OLD.manifest AND NEW.manifest_sha256 = OLD.manifest_sha256 THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'submission_versions is immutable: update of % version % refused', OLD.uuid, OLD.version
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS submission_versions_immutable ON rearm.submission_versions;
CREATE TRIGGER submission_versions_immutable BEFORE UPDATE OR DELETE ON rearm.submission_versions
    FOR EACH ROW EXECUTE FUNCTION rearm.submission_versions_immutable();
