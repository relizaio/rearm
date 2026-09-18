-- FDA-Readiness-1 PR1: per-component support-status + EOS/EOL storage and attestation.
-- Support facts are mutable and queried (the approaching/past-EOS filter), so they are
-- first-class columns (mirrors licenses / enriched_at), NOT record_data JSONB. The support
-- STATUS is derived at read time from these dates and is never stored.
ALTER TABLE rearm.sbom_components
    ADD COLUMN IF NOT EXISTS end_of_support_date date,
    ADD COLUMN IF NOT EXISTS end_of_life_date date,
    ADD COLUMN IF NOT EXISTS support_source text,
    ADD COLUMN IF NOT EXISTS support_last_assessed timestamptz,
    ADD COLUMN IF NOT EXISTS support_asserted_by uuid,
    ADD COLUMN IF NOT EXISTS support_notes text;

-- Partial btree for the "approaching / past EOS" filter. Partial because most rows are null
-- pre-attestation, keeping the index small (mirrors the V25 / V73 partial-index pattern).
CREATE INDEX IF NOT EXISTS sbom_components_eos_idx
    ON rearm.sbom_components (org, end_of_support_date)
    WHERE end_of_support_date IS NOT NULL;

-- Append-only attestation history (ALCOA input-side record of record). Shaped after
-- metrics_audit: surrogate uuid PK, entity_uuid + revision + org, no FK. One row per
-- setSbomComponentSupport edit, capturing the asserted (after-image) values + attester.
CREATE TABLE IF NOT EXISTS rearm.sbom_component_support_audit (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    sbom_component_uuid uuid NOT NULL,
    org uuid NOT NULL,
    support_revision integer NOT NULL,
    end_of_support_date date,
    end_of_life_date date,
    support_source text NOT NULL,
    support_notes text,
    support_asserted_by uuid,
    asserted_date timestamptz NOT NULL default now()
);

CREATE INDEX IF NOT EXISTS sbom_component_support_audit_component_idx
    ON rearm.sbom_component_support_audit (sbom_component_uuid);
