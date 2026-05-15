-- Drop and recreate the SBOM-component aggregation tables.
--
-- This is the third in-place rewrite of V37. Previous variants:
--   - normalized child tables (release_sbom_component_artifacts /
--     release_sbom_edges / purl_qualifiers) — landed in #79, came out
--     ~10% larger than the JSONB original at prod scale.
--   - JSONB shape keyed per (release, sbom_component) with canonical
--     artifact UUIDs threaded into the JSONB at write time — landed in
--     #82. The canonical-artifact dedup didn't buy meaningful row-level
--     savings because the table was still keyed per release.
--
-- This variant keys the SBOM aggregation per CANONICAL ARTIFACT instead
-- of per (release, canonical component). Storage savings now scale with
-- how often the same BOM content is reused across releases — a release
-- references its artifacts (via deliverables / SCE / release.artifacts),
-- those artifacts resolve to canonical, and the canonical's parsed
-- component graph is stored ONCE. Two releases sharing a BOM artifact =
-- one set of artifact_sbom_components rows, not two release-keyed
-- copies of the same JSONB.
--
-- New layout:
--   sbom_components            Canonical (org, canonical_purl)
--                              component identity. JSONB record_data
--                              for name / group / version / type /
--                              isRoot. UNCHANGED from V25/V28.
--
--   artifact_sbom_components   Per (canonical_artifact, sbom_component)
--                              row. Stores exact_purl (the artifact's
--                              actual purl for the component, qualifier-
--                              bearing) and parents jsonb (the in-edges
--                              this artifact declared for the target
--                              component). Replaces release_sbom_components
--                              for the per-artifact parse output.
--
--   release_artifact_index     Many-to-many release ↔ canonical_artifact
--                              reverse-index, rebuilt by the release
--                              reconcile. Makes
--                              "which releases contain this sbom_component"
--                              a 1-join query instead of scanning JSONB
--                              across deliverables / SCE / releases.
--                              Also makes "what artifacts does this
--                              release reference" a single index probe.
--
--   artifact_canonical_map     Lazy per-org artifact → canonical pointer.
--                              UNCHANGED. Lifetime persists across V37
--                              checksum rewrites (CREATE IF NOT EXISTS).
--
--   releases.sbom_schema_version
--                              Generation cookie. Bumped to 3 on success.
--                              Preserved across V37 checksum rewrites
--                              (ADD COLUMN IF NOT EXISTS).
--
-- Tables are dropped and recreated; every non-archived release rebuilds
-- from its BOM artifacts on the next reconcile tick. No FK constraints
-- anywhere per coding_principles.md.

-- ---------------------------------------------------------------------------
-- 1. Tear down whatever shape was previously present. The IF EXISTS list
--    covers V25/V28 JSONB, the normalized intermediate, and the V82 JSONB
--    forms — all known prior V37 checksums.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS rearm.release_sbom_edges                CASCADE;
DROP TABLE IF EXISTS rearm.release_sbom_component_artifacts  CASCADE;
DROP TABLE IF EXISTS rearm.release_sbom_components           CASCADE;
DROP TABLE IF EXISTS rearm.sbom_components                   CASCADE;
DROP TABLE IF EXISTS rearm.purl_qualifiers                   CASCADE;
DROP TABLE IF EXISTS rearm.artifact_sbom_components          CASCADE;
DROP TABLE IF EXISTS rearm.release_artifact_index            CASCADE;

-- ---------------------------------------------------------------------------
-- 2. Schema-version cookie. Bumped to 3 on this rewrite. ADD IF NOT EXISTS
--    so values from prior V37 runs stay intact; the reconcile will stamp
--    them to 3 on next run.
-- ---------------------------------------------------------------------------
ALTER TABLE rearm.releases
    ADD COLUMN IF NOT EXISTS sbom_schema_version integer NOT NULL DEFAULT 0;

DROP INDEX IF EXISTS rearm.releases_sbom_schema_version_idx;
CREATE INDEX releases_sbom_schema_version_idx
    ON rearm.releases (sbom_schema_version)
    WHERE sbom_schema_version < 3;

-- ---------------------------------------------------------------------------
-- 3. artifact_canonical_map (preserved across V37 rewrites). Lazy
--    population during reconcile. No FK to artifacts; orphans surface as
--    "no data found" + log.warn in the service.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rearm.artifact_canonical_map (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    artifact_uuid uuid NOT NULL,
    canonical_artifact_uuid uuid NOT NULL,
    created_date timestamptz NOT NULL default now(),
    CONSTRAINT artifact_canonical_map_artifact_unique UNIQUE (artifact_uuid)
);
CREATE INDEX IF NOT EXISTS artifact_canonical_map_org_idx
    ON rearm.artifact_canonical_map (org);
CREATE INDEX IF NOT EXISTS artifact_canonical_map_canonical_idx
    ON rearm.artifact_canonical_map (canonical_artifact_uuid);

-- ---------------------------------------------------------------------------
-- 4. Canonical sbom_components — per-(org, canonical_purl) identity with
--    JSONB record_data carrying name / group / version / type / isRoot.
--    Same shape V25/V28 introduced.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.sbom_components (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    canonical_purl text NOT NULL,
    record_data jsonb,
    CONSTRAINT sbom_components_org_canonical_purl_unique UNIQUE (org, canonical_purl)
);

CREATE INDEX sbom_components_name_idx
    ON rearm.sbom_components ((record_data->>'name'));
CREATE INDEX sbom_components_org_idx
    ON rearm.sbom_components (org);

-- ---------------------------------------------------------------------------
-- 5. artifact_sbom_components — per (canonical_artifact, sbom_component) row.
--
--    exact_purl       The full (qualifier-bearing) PURL this canonical
--                     artifact's BOM used to reference the component. Kept
--                     as text; comparing against sbom_components.canonical_purl
--                     at read time gives us "is this exact == canonical?".
--
--    parents          JSONB array of in-edges this artifact declared for
--                     the target component. Each entry:
--                       { "sourceSbomComponentUuid": "<canonical>",
--                         "sourceCanonicalPurl": "<canonical>",
--                         "relationshipType": "DEPENDS_ON",
--                         "sourceExactPurl": "<as declared>",
--                         "targetExactPurl": "<as declared>" }
--                     The declaringArtifacts wrapper from the old per-release
--                     shape collapses — the row IS the declaration. Read-time
--                     merge across releases synthesizes declaringArtifacts
--                     back into the public GraphQL shape.
--
--    parsed_at        When this artifact's BOM was parsed. Useful for future
--                     "re-parse stale rows" invalidation logic.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.artifact_sbom_components (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    canonical_artifact_uuid uuid NOT NULL,
    sbom_component_uuid uuid NOT NULL,
    exact_purl text NOT NULL,
    parents jsonb NOT NULL default '[]'::jsonb,
    parsed_at timestamptz NOT NULL default now(),
    CONSTRAINT artifact_sbom_components_unique
        UNIQUE (canonical_artifact_uuid, sbom_component_uuid)
);

CREATE INDEX artifact_sbom_components_canonical_idx
    ON rearm.artifact_sbom_components (canonical_artifact_uuid);
CREATE INDEX artifact_sbom_components_component_idx
    ON rearm.artifact_sbom_components (sbom_component_uuid);
CREATE INDEX artifact_sbom_components_org_idx
    ON rearm.artifact_sbom_components (org);

-- ---------------------------------------------------------------------------
-- 6. release_artifact_index — many-to-many reverse index from release to
--    canonical artifact. Rebuilt by reconcileReleaseSbomComponents per
--    release. Lets impact analysis stay a 1-join query
--      (artifact_sbom_components JOIN release_artifact_index ON canonical)
--    instead of having to walk JSONB inside deliverables / SCE / releases.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.release_artifact_index (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    release_uuid uuid NOT NULL,
    canonical_artifact_uuid uuid NOT NULL,
    created_date timestamptz NOT NULL default now(),
    CONSTRAINT release_artifact_index_unique
        UNIQUE (release_uuid, canonical_artifact_uuid)
);

CREATE INDEX release_artifact_index_release_idx
    ON rearm.release_artifact_index (release_uuid);
CREATE INDEX release_artifact_index_canonical_idx
    ON rearm.release_artifact_index (canonical_artifact_uuid);
CREATE INDEX release_artifact_index_org_idx
    ON rearm.release_artifact_index (org);

-- ---------------------------------------------------------------------------
-- 7. Re-enqueue every non-archived release so the scheduler / operator
--    force-reconcile rebuilds artifact_sbom_components +
--    release_artifact_index. Mirrors V25 / V27 / V28 tail UPDATEs.
-- ---------------------------------------------------------------------------
UPDATE rearm.releases
SET flow_control = jsonb_set(
        coalesce(flow_control, '{}'::jsonb),
        '{sbomReconcileRequestedAt}',
        to_jsonb(now()),
        true)
WHERE coalesce(record_data->>'status', 'ACTIVE') != 'ARCHIVED';
