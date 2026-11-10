-- FDAW-15 (FDAW-1 B6, F-6, F-18 item 2): the manual supplier, licence and vendored description of a
-- component key, and their insert-only audit. C2's rule rows join as a second provenance of the same
-- key without altering this table. Rerunnable; no foreign keys, as the V83, V97, V108 and V109 tables.

-- One row per (org, key, provenance): the current state. Mutable; revision moves on every change and
-- is monotonic per key across the row's lifetimes: a created row starts at the audit's largest revision.
CREATE TABLE IF NOT EXISTS rearm.sbom_component_overlays (
    uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org UUID NOT NULL,
    canonical_purl TEXT NOT NULL,
    kind TEXT NOT NULL,
    provenance TEXT NOT NULL DEFAULT 'MANUAL',
    revision INTEGER NOT NULL DEFAULT 0,
    created_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_updated_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    schema_version INTEGER NOT NULL DEFAULT 0,
    record_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT sbom_component_overlays_key UNIQUE (org, canonical_purl, provenance),
    CONSTRAINT sbom_component_overlays_record_object CHECK (jsonb_typeof(record_data) = 'object')
);
CREATE INDEX IF NOT EXISTS sbom_component_overlays_org ON rearm.sbom_component_overlays (org);

-- One row per overlay write: the after-image. Insert-only. removed marks the row of a removed description.
CREATE TABLE IF NOT EXISTS rearm.sbom_component_overlay_audit (
    uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    overlay UUID NOT NULL,
    org UUID NOT NULL,
    canonical_purl TEXT NOT NULL,
    kind TEXT NOT NULL,
    revision INTEGER NOT NULL,
    record_data JSONB NOT NULL,
    asserted_by UUID,
    asserted_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    reason TEXT,
    bulk_act UUID,
    removed BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS sbom_component_overlay_audit_key
    ON rearm.sbom_component_overlay_audit (org, canonical_purl, asserted_date);

CREATE OR REPLACE FUNCTION rearm.sbom_component_overlay_audit_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'sbom_component_overlay_audit is immutable: delete of % refused', OLD.uuid
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RAISE EXCEPTION 'sbom_component_overlay_audit is immutable: update of % refused', OLD.uuid
        USING ERRCODE = 'integrity_constraint_violation';
END $$;
DROP TRIGGER IF EXISTS sbom_component_overlay_audit_immutable ON rearm.sbom_component_overlay_audit;
CREATE TRIGGER sbom_component_overlay_audit_immutable
    BEFORE UPDATE OR DELETE ON rearm.sbom_component_overlay_audit
    FOR EACH ROW EXECUTE FUNCTION rearm.sbom_component_overlay_audit_immutable();
