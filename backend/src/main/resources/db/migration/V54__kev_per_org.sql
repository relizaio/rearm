-- V54 — KEV per-org refactor.
--
-- Reshapes the KEV model so that both the assertion catalog AND the
-- credential to fetch it are per-org rather than instance-global. Drivers:
--   * SaaS multi-tenant correctness — each customer has their own VulnCheck
--     contract; the token IS that customer's credential and shouldn't be
--     shared via SystemInfo with the platform owner.
--   * Permission alignment — the operator who configures a KEV source is
--     typically the org admin, not a global super-admin.
--   * UI clarity — KEV sources show up as per-org Integration catalog cards
--     ("✓ Configured" / "Available"), so an org admin can see at a glance
--     which sources their findings are matched against.
--
-- The previous global model (V53) is dropped, not migrated:
--   * Old VULNCHECK rows came from a single shared token; preserving them
--     would re-leak the data the refactor partitions.
--   * CISA rows are public + cheap to re-sync per org (~10s, ~1600 entries).
--   * Per-org first sync fires at pod-start + scheduler initialDelay (~2min).
--
-- Related: [[kev-per-org-refactor]] memory has the full design + PR plan.

-- 1. Empty the table so the NOT NULL org_id add doesn't need backfill.
DELETE FROM rearm.kev_assertions;

-- 2. Add org_id and re-key on (org_id, source, cve_id).
--    No FOREIGN KEY to organizations (per coding_principles.md — FKs break
--    backup/restore). Referential integrity is the application's job: every
--    kev_assertions read is org-scoped, so rows for a removed org are never
--    read; orgs are archived rather than hard-deleted, so there is no
--    parent-delete path that needs to cascade these children.
ALTER TABLE rearm.kev_assertions
    ADD COLUMN org_id uuid NOT NULL,
    DROP CONSTRAINT IF EXISTS kev_assertions_source_cve_unique,
    ADD CONSTRAINT kev_assertions_org_source_cve_unique UNIQUE (org_id, source, cve_id);

-- 3. The cve_id index from V53 still serves the per-org knownExploited probe
--    (the WHERE adds org_id = :org); replace it with a compound index that
--    leads with org_id so the planner picks an index-only scan.
DROP INDEX IF EXISTS rearm.kev_assertions_cve_id_idx;
CREATE INDEX kev_assertions_org_cve_idx ON rearm.kev_assertions (org_id, cve_id);

-- 4. Bootstrap the CISA_KEV Integration row for every existing real org.
--    Excludes only the system "External Public Components" pseudo-org
--    (00000000-…000); the CE singleton "User Organization" (00000000-…001)
--    is a real working org on CE installs and gets CISA enabled too.
--    identifier='base' matches CommonVariables.BASE_INTEGRATION_IDENTIFIER.
INSERT INTO rearm.integrations (uuid, revision, schema_version, created_date, last_updated_date, record_data)
SELECT
    gen_random_uuid(),
    0,
    0,
    now(),
    now(),
    jsonb_build_object(
        'uuid',       gen_random_uuid()::text,
        'identifier', 'base',
        'isEnabled',  true,
        'org',        o.uuid::text,
        'type',       'CISA_KEV'
    )
FROM rearm.organizations o
WHERE o.uuid <> '00000000-0000-0000-0000-000000000000'
  AND NOT EXISTS (
    SELECT 1 FROM rearm.integrations i
    WHERE i.record_data->>'org' = o.uuid::text
      AND i.record_data->>'type' = 'CISA_KEV'
      AND i.record_data->>'identifier' = 'base'
  );

-- 5. Strip the instance-global vulncheckKevToken from SystemInfo.data.
--    The new model holds the token on a per-org VULNCHECK_KEV integration's
--    `secret` field, encrypted by the same EncryptionService.
UPDATE rearm.system_info
SET data = data - 'vulncheckKevToken'
WHERE data ? 'vulncheckKevToken';
