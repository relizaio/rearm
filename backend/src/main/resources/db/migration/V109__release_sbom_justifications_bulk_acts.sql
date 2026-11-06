-- FDAW-14 (FDAW-1 B2, F-14 item 2, F-6): hash justifications per release SBOM identity head, their
-- insert-only audit, and the insert-only receipt of every bulk support act. The end-of-support
-- justified unknown lives in sbom_component_support.record_data (SupportData.unknowns), an
-- additive JSONB key under the V83 evolution rule, so that table is not altered here.
-- Rerunnable; no foreign keys, as the V83, V97 and V108 tables.

-- One row per identity head (release_sboms.uuid, the CycloneDX serial): the current state.
-- Mutable; revision moves on every write. org and release are copied from the head at the first
-- write so the row reads alone; the head stays the authority at every write.
CREATE TABLE IF NOT EXISTS rearm.release_sbom_justifications (
    release_sbom UUID PRIMARY KEY,
    org UUID NOT NULL,
    release UUID NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT release_sbom_justifications_record_object CHECK (jsonb_typeof(record_data) = 'object')
);
CREATE INDEX IF NOT EXISTS release_sbom_justifications_org ON rearm.release_sbom_justifications (org);
CREATE INDEX IF NOT EXISTS release_sbom_justifications_release ON rearm.release_sbom_justifications (release);

-- One row per justification write: the after-image. Insert-only.
CREATE TABLE IF NOT EXISTS rearm.release_sbom_justification_audit (
    uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    release_sbom UUID NOT NULL,
    org UUID NOT NULL,
    revision INTEGER NOT NULL,
    record_data JSONB NOT NULL,
    asserted_by UUID,
    asserted_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    reason TEXT,
    bulk_act UUID
);
CREATE INDEX IF NOT EXISTS release_sbom_justification_audit_head
    ON rearm.release_sbom_justification_audit (release_sbom, asserted_date);

CREATE OR REPLACE FUNCTION rearm.release_sbom_justification_audit_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'release_sbom_justification_audit is immutable: delete of % refused', OLD.uuid
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'release_sbom_justification_audit is immutable: update of % refused', OLD.uuid
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS release_sbom_justification_audit_immutable ON rearm.release_sbom_justification_audit;
CREATE TRIGGER release_sbom_justification_audit_immutable
    BEFORE UPDATE OR DELETE ON rearm.release_sbom_justification_audit
    FOR EACH ROW EXECUTE FUNCTION rearm.release_sbom_justification_audit_immutable();

-- The receipt of one bulk support call, recorded once after its loop (WRITEUP 6.6, parent F-6).
-- uuid is minted by the server before the loop; every SupportData.bulkAct and
-- JustifiedUnknown.bulkAct the call wrote names it, and a selector sweep's per-row audit rows
-- carry it as batch_id. Insert-only.
CREATE TABLE IF NOT EXISTS rearm.support_bulk_acts (
    uuid UUID PRIMARY KEY,
    org UUID NOT NULL,
    kind TEXT NOT NULL,
    release UUID,
    release_sbom UUID,
    selector JSONB NOT NULL,
    matched_count INTEGER NOT NULL,
    applied_count INTEGER NOT NULL,
    list_hash TEXT NOT NULL,
    asserted_by UUID,
    asserted_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    record_data JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS support_bulk_acts_org ON rearm.support_bulk_acts (org, asserted_date);

CREATE OR REPLACE FUNCTION rearm.support_bulk_acts_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'support_bulk_acts is immutable: delete of % refused', OLD.uuid
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'support_bulk_acts is immutable: update of % refused', OLD.uuid
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS support_bulk_acts_immutable ON rearm.support_bulk_acts;
CREATE TRIGGER support_bulk_acts_immutable BEFORE UPDATE OR DELETE ON rearm.support_bulk_acts
    FOR EACH ROW EXECUTE FUNCTION rearm.support_bulk_acts_immutable();
