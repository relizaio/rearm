-- FDAW-34 (FDAW-25 D2a): the living VEX identity. One head per (release, SBOM profile name) whose uuid
-- is the CycloneDX serialNumber of the TEA VEX, and one immutable row per content change of it, naming
-- the release SBOM version it describes. A copy of V108's shape for a second document kind; no data
-- migration: a release gets its VEX at its next publish. Rerunnable; no foreign keys, as the V97 tables.

CREATE TABLE IF NOT EXISTS rearm.release_vexes (
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
    CONSTRAINT release_vexes_release_profile UNIQUE (release, profile_name)
);
CREATE INDEX IF NOT EXISTS release_vexes_org ON rearm.release_vexes (org);

CREATE TABLE IF NOT EXISTS rearm.release_vex_versions (
    uuid UUID NOT NULL,
    version INTEGER NOT NULL,
    org UUID NOT NULL,
    release UUID NOT NULL,
    profile_name TEXT NOT NULL,
    artifact UUID NOT NULL,
    sha256 TEXT NOT NULL,
    content_digest TEXT NOT NULL,
    sbom_serial UUID NOT NULL,
    sbom_version INTEGER NOT NULL,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by UUID,
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT release_vex_versions_pk PRIMARY KEY (uuid, version)
);
CREATE INDEX IF NOT EXISTS release_vex_versions_release ON rearm.release_vex_versions (release);

-- Release VEX versions are immutable: no UPDATE and no DELETE on rearm.release_vex_versions.
-- Application code only inserts (the entity is insert-only, the repository has no update or
-- delete); this trigger binds every other SQL path too.
CREATE OR REPLACE FUNCTION rearm.release_vex_versions_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'release_vex_versions is immutable: delete of % version % refused', OLD.uuid, OLD.version
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'release_vex_versions is immutable: update of % version % refused', OLD.uuid, OLD.version
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS release_vex_versions_immutable ON rearm.release_vex_versions;
CREATE TRIGGER release_vex_versions_immutable BEFORE UPDATE OR DELETE ON rearm.release_vex_versions
    FOR EACH ROW EXECUTE FUNCTION rearm.release_vex_versions_immutable();
