-- FDAW-7 (FDAW-1 D1): the release SBOM identity. One head per (release, SBOM profile name) whose
-- uuid is the CycloneDX serialNumber, and one immutable row per content change of it. The package
-- pins (serial, version, sha256, artifact) of a version row; TEA publishes the same artifact row.
-- Rerunnable; no foreign keys, as the V97 tables.

CREATE TABLE IF NOT EXISTS rearm.release_sboms (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    release UUID NOT NULL,
    profile_name TEXT NOT NULL,
    artifact UUID NOT NULL,
    latest_version INTEGER NOT NULL,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT release_sboms_release_profile UNIQUE (release, profile_name)
);
CREATE INDEX IF NOT EXISTS release_sboms_org ON rearm.release_sboms (org);

CREATE TABLE IF NOT EXISTS rearm.release_sbom_versions (
    uuid UUID NOT NULL,
    version INTEGER NOT NULL,
    org UUID NOT NULL,
    release UUID NOT NULL,
    profile_name TEXT NOT NULL,
    artifact UUID NOT NULL,
    sha256 TEXT NOT NULL,
    content_digest TEXT NOT NULL,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by UUID,
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT release_sbom_versions_pk PRIMARY KEY (uuid, version)
);
CREATE INDEX IF NOT EXISTS release_sbom_versions_release ON rearm.release_sbom_versions (release);

-- Release SBOM versions are immutable: no UPDATE and no DELETE on rearm.release_sbom_versions.
-- Application code only inserts (the entity is insert-only, the repository has no update or
-- delete); this trigger binds every other SQL path too.
CREATE OR REPLACE FUNCTION rearm.release_sbom_versions_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'release_sbom_versions is immutable: delete of % version % refused', OLD.uuid, OLD.version
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'release_sbom_versions is immutable: update of % version % refused', OLD.uuid, OLD.version
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS release_sbom_versions_immutable ON rearm.release_sbom_versions;
CREATE TRIGGER release_sbom_versions_immutable BEFORE UPDATE OR DELETE ON rearm.release_sbom_versions
    FOR EACH ROW EXECUTE FUNCTION rearm.release_sbom_versions_immutable();
