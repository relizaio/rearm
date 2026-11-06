-- Submission acts and drafts, and insert-only triggers on two audit tables (2026-11-FDA-WORKFLOW
-- board, FDAW-8 = FDAW-1 A2: parent decision D2, resolutions F-4, F-8 items 1 and 2, F-16).
-- Rerunnable like V97 (IF NOT EXISTS, CREATE OR REPLACE, DROP TRIGGER IF EXISTS); no data step.
--
-- 1. rearm.submission_acts: the append-only record of every submission act. A draft-scoped act
--    carries draft (submission is null until the first version exists, F-4); an act on a version
--    carries submission. Only a USER act names a person (actor); a SYSTEM or MIGRATION act never
--    does. No foreign keys, as the V97 tables: a discarded draft's acts outlive the draft row.
-- 2. Its trigger refuses every UPDATE and every DELETE.
-- 3. rearm.submission_drafts: one mutable working copy per release and kind; no trigger. Nothing
--    writes it until the draft acts exist.
-- 4. rearm.sbom_component_support_audit becomes insert-only (its one writer inserts).
-- 5. The rows of rearm.audit whose entity_name is 'submissions' (the stored TableName value of
--    the submission heads) become insert-only; every other entity's audit rows are untouched.

CREATE TABLE IF NOT EXISTS rearm.submission_acts (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    submission UUID NULL,
    draft UUID NULL,
    release UUID NOT NULL,
    version INTEGER NULL,
    kind TEXT NOT NULL,
    actor UUID NULL,
    cause TEXT NOT NULL,
    caused_by UUID NULL,
    acted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT submission_acts_target CHECK (submission IS NOT NULL OR draft IS NOT NULL),
    CONSTRAINT submission_acts_actor CHECK ((cause = 'USER') = (actor IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS submission_acts_submission ON rearm.submission_acts (submission);
CREATE INDEX IF NOT EXISTS submission_acts_draft ON rearm.submission_acts (draft);
CREATE INDEX IF NOT EXISTS submission_acts_release ON rearm.submission_acts (release);
CREATE INDEX IF NOT EXISTS submission_acts_org_actor ON rearm.submission_acts (org, actor);

CREATE OR REPLACE FUNCTION rearm.submission_acts_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'submission_acts is immutable: delete of % refused', OLD.uuid
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'submission_acts is immutable: update of % refused', OLD.uuid
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS submission_acts_immutable ON rearm.submission_acts;
CREATE TRIGGER submission_acts_immutable BEFORE UPDATE OR DELETE ON rearm.submission_acts
    FOR EACH ROW EXECUTE FUNCTION rearm.submission_acts_immutable();

CREATE TABLE IF NOT EXISTS rearm.submission_drafts (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    release UUID NOT NULL,
    component UUID NOT NULL,
    kind TEXT NOT NULL,
    submission UUID NULL,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL,
    CONSTRAINT submission_drafts_release_kind UNIQUE (release, kind)
);
CREATE INDEX IF NOT EXISTS submission_drafts_org ON rearm.submission_drafts (org);
CREATE INDEX IF NOT EXISTS submission_drafts_submission ON rearm.submission_drafts (submission);

CREATE OR REPLACE FUNCTION rearm.sbom_component_support_audit_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'sbom_component_support_audit is immutable: delete of % refused', OLD.uuid
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'sbom_component_support_audit is immutable: update of % refused', OLD.uuid
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS sbom_component_support_audit_immutable ON rearm.sbom_component_support_audit;
CREATE TRIGGER sbom_component_support_audit_immutable BEFORE UPDATE OR DELETE ON rearm.sbom_component_support_audit
    FOR EACH ROW EXECUTE FUNCTION rearm.sbom_component_support_audit_immutable();

-- The predicate names the stored value: Audit.setEntityName stores TableName.toString(), which is
-- "submissions" for TableName.SUBMISSION. Rows of every other entity name keep their ordinary
-- audit history (package_settings rows included: they are inputs, not package records).
CREATE OR REPLACE FUNCTION rearm.audit_submissions_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit rows of submissions are immutable: delete of % refused', OLD.uuid
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'audit rows of submissions are immutable: update of % refused', OLD.uuid
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS audit_submissions_immutable ON rearm.audit;
CREATE TRIGGER audit_submissions_immutable BEFORE UPDATE OR DELETE ON rearm.audit
    FOR EACH ROW WHEN (OLD.entity_name = 'submissions') EXECUTE FUNCTION rearm.audit_submissions_immutable();
