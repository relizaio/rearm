-- Drop & recreate the SBOM-component aggregation tables with a normalized
-- shape. The old layout stored everything per row as JSONB arrays
-- (artifactParticipations, parents), with full PURLs and UUID-as-string
-- repeated across every artifact and every in-edge. Profiling showed the
-- per-release storage was dominated by (a) string-form UUIDs inside JSONB
-- (~41 B each vs 16 B native), (b) duplicated exact PURLs across the
-- artifact_participations and parents arrays, and (c) the GIN index on
-- artifact_participations needed to make any non-trivial filter remotely
-- cheap. At AI-scale release velocity these stack into tens of GB very
-- quickly.
--
-- New layout — same public GraphQL surface, normalized underneath:
--
--   sbom_components               Canonical per-(org, canonical_purl)
--                                 component identity. Frequently-read
--                                 metadata (type / group / name /
--                                 version / is_root) promoted out of the
--                                 prior record_data JSONB into typed
--                                 columns so the search query is a
--                                 plain b-tree probe instead of a
--                                 JSONB ->> expression. record_data
--                                 column dropped entirely.
--
--   purl_qualifiers               Interning table for exact PURLs (the
--                                 qualifier-/subpath-bearing form). Same
--                                 exact PURL appears repeatedly across
--                                 artifacts, edges and releases — store
--                                 the string once per (org, full_purl)
--                                 and reference by uuid from the leaf
--                                 tables. NULL exact_purl_uuid in those
--                                 tables means the exact PURL equals
--                                 the canonical PURL on the referenced
--                                 sbom_components row (saves intern
--                                 rows for the ~80% of components that
--                                 carry no qualifiers).
--
--   release_sbom_components       Existence anchor only — keys
--                                 (release, canonical component) and
--                                 carries timestamps + the uuid the UI
--                                 navigates by. No JSONB columns left.
--
--   release_sbom_component_artifacts
--                                 Replaces the artifact_participations
--                                 JSONB array. One row per
--                                 (release, canonical component,
--                                 declaring artifact, exact PURL). Keyed
--                                 by canonical component (not by
--                                 release_sbom_components.uuid) so
--                                 product-release read-time merging is
--                                 a simple UNION over the product's own
--                                 release_uuid + every dep's release_uuid
--                                 grouped by sbom_component_uuid.
--
--   release_sbom_edges            Replaces the parents JSONB array. One
--                                 row per (release, source canonical,
--                                 target canonical, relationship,
--                                 declaring artifact, source exact PURL,
--                                 target exact PURL). Edges between
--                                 canonical components — same semantics
--                                 as the prior in-edge model, with the
--                                 read-time inversion to forward edges
--                                 still happening in the API layer.
--
-- Tables are dropped and recreated rather than backfilled — every release
-- in the queue rebuilds from its BOM artifacts on the next reconcile tick.
-- This matches the V28 cutover pattern and is safe because the only data
-- in these tables is derived from artifact BOMs that we still hold.
--
-- The reconcile queue itself (releases.flow_control->sbomReconcileRequestedAt)
-- is re-seeded for every non-archived release at the tail of this migration,
-- as in V25 / V27 / V28.
--
-- Additionally introduce a generation cookie on releases:
--
--   releases.sbom_schema_version  Bumped by the reconciler on success.
--                                 Future migrations of this area can
--                                 increment a Java-side constant and the
--                                 catch-up scheduler will rediscover rows
--                                 whose stored version is below current —
--                                 no Flyway re-enqueue UPDATE needed,
--                                 and stuck / partial reconciles are
--                                 self-healing. Default 0 here; service
--                                 stamps the new current value (1) on
--                                 each successful reconcile.

-- ---------------------------------------------------------------------------
-- Foreign-key policy for this migration.
--
-- Per the no-FK convention in rearm-core/ai-agents/coding_principles.md,
-- none of the new tables below declare FOREIGN KEY constraints. App-layer
-- code (SbomComponentService) writes child rows transactionally and clears
-- them on the same transactional boundary; orphan-cleanup races are
-- absorbed in the read path (log.warn + skip). The drop here uses CASCADE
-- only to dispose of the V25/V28-era FKs that referenced the old shape —
-- the new tables that follow do not introduce any new ones.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 1. Tear down the old shape. CASCADE handles the V25/V28-era FK chain
--    pointing at the dropped tables.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS rearm.release_sbom_components CASCADE;
DROP TABLE IF EXISTS rearm.sbom_components CASCADE;

-- ---------------------------------------------------------------------------
-- 2. Schema-version cookie on releases. Stays at 0 on every existing
--    release; bumped to CURRENT_SBOM_SCHEMA_VERSION by SbomComponentService
--    on successful reconcile.
-- ---------------------------------------------------------------------------
ALTER TABLE rearm.releases
    ADD COLUMN IF NOT EXISTS sbom_schema_version integer NOT NULL DEFAULT 0;

-- Partial index lets the catch-up scheduler find rows below the current
-- version without scanning the full releases table.
CREATE INDEX IF NOT EXISTS releases_sbom_schema_version_idx
    ON rearm.releases (sbom_schema_version)
    WHERE sbom_schema_version < 1;

-- ---------------------------------------------------------------------------
-- 3. Recreate canonical sbom_components with promoted columns.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.sbom_components (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    canonical_purl text NOT NULL,
    purl_type text,
    pkg_group text,
    name text,
    version text,
    is_root boolean NOT NULL default false,
    CONSTRAINT sbom_components_org_canonical_purl_unique
        UNIQUE (org, canonical_purl)
);

CREATE INDEX sbom_components_org_idx
    ON rearm.sbom_components (org);

-- Replaces the old expression index on record_data->>'name'. Most reads
-- carry org + name (or org + name + version), so a single composite
-- covers both the listing and the optional-version search.
CREATE INDEX sbom_components_org_name_idx
    ON rearm.sbom_components (org, name);

-- ---------------------------------------------------------------------------
-- 3b. artifact_canonical_map — per-org dedup pointers from any BOM artifact
--     to its canonical (content-identical) counterpart. Mapping is populated
--     LAZILY during SBOM reconcile, only for BOM-bearing artifacts that
--     participate in a release's aggregation. Non-BOM artifacts are never
--     mapped (and won't appear here).
--
--     The existing org-scoped digest lookup
--     (VariableQueries.FIND_ARTIFACTS_BY_STORED_DIGEST) decides the
--     canonical: first artifact seen with a given (org, digest) wins; all
--     subsequent artifacts with the same digest map to it. An artifact
--     that is the first occurrence maps to itself (canonical_artifact_uuid
--     = artifact_uuid) so the table is the single authoritative source —
--     no "missing row means canonical" implicit rule.
--
--     DELIBERATELY NO FOREIGN KEYS. If the referenced artifact is later
--     deleted, the SBOM service surfaces a "no data found" result and
--     log.warn's the dangling row instead of cascading. Keeps this map
--     orthogonal to the artifacts table lifecycle.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.artifact_canonical_map (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    artifact_uuid uuid NOT NULL,
    canonical_artifact_uuid uuid NOT NULL,
    created_date timestamptz NOT NULL default now(),
    CONSTRAINT artifact_canonical_map_artifact_unique UNIQUE (artifact_uuid)
);

CREATE INDEX artifact_canonical_map_org_idx
    ON rearm.artifact_canonical_map (org);
CREATE INDEX artifact_canonical_map_canonical_idx
    ON rearm.artifact_canonical_map (canonical_artifact_uuid);

-- ---------------------------------------------------------------------------
-- 4. PURL interning table. Stores each full (qualifier-bearing) PURL once
--    per org. Leaf tables reference by uuid; NULL means exact == canonical.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.purl_qualifiers (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    full_purl text NOT NULL,
    created_date timestamptz NOT NULL default now(),
    CONSTRAINT purl_qualifiers_org_full_purl_unique UNIQUE (org, full_purl)
);

CREATE INDEX purl_qualifiers_org_idx
    ON rearm.purl_qualifiers (org);

-- ---------------------------------------------------------------------------
-- 5. release_sbom_components — the existence anchor. No JSONB.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.release_sbom_components (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    release_uuid uuid NOT NULL,
    sbom_component_uuid uuid NOT NULL,
    CONSTRAINT release_sbom_components_release_component_unique
        UNIQUE (release_uuid, sbom_component_uuid)
);

CREATE INDEX release_sbom_components_release_idx
    ON rearm.release_sbom_components (release_uuid);
CREATE INDEX release_sbom_components_component_idx
    ON rearm.release_sbom_components (sbom_component_uuid);
CREATE INDEX release_sbom_components_org_idx
    ON rearm.release_sbom_components (org);

-- ---------------------------------------------------------------------------
-- 6. release_sbom_component_artifacts — normalized participations.
--    Keyed by canonical sbom_component_uuid + release_uuid (not by
--    release_sbom_components.uuid) so that PRODUCT-release read-time
--    aggregation across many dep releases collapses to a single
--    SELECT ... WHERE release_uuid IN (...) GROUP BY sbom_component_uuid.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.release_sbom_component_artifacts (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    release_uuid uuid NOT NULL,
    sbom_component_uuid uuid NOT NULL,
    artifact_uuid uuid NOT NULL,
    exact_purl_uuid uuid,
    -- NULLS NOT DISTINCT (PG15+) makes the (rel, comp, artifact, NULL) shape
    -- unique too, so the upsert path doesn't insert duplicates when
    -- exact_purl_uuid is NULL (i.e. exact == canonical).
    CONSTRAINT release_sbom_component_artifacts_unique
        UNIQUE NULLS NOT DISTINCT (release_uuid, sbom_component_uuid, artifact_uuid, exact_purl_uuid)
);

CREATE INDEX release_sbom_component_artifacts_release_component_idx
    ON rearm.release_sbom_component_artifacts (release_uuid, sbom_component_uuid);
CREATE INDEX release_sbom_component_artifacts_artifact_idx
    ON rearm.release_sbom_component_artifacts (artifact_uuid);

-- ---------------------------------------------------------------------------
-- 7. release_sbom_edges — normalized in-edges. Source / target reference
--    canonical sbom_components (not release_sbom_components) for the same
--    reason as #6: product-release merging is a UNION over release_uuids
--    keyed by (source canonical, target canonical) without any UUID remapping.
-- ---------------------------------------------------------------------------
CREATE TABLE rearm.release_sbom_edges (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    release_uuid uuid NOT NULL,
    target_sbom_component_uuid uuid NOT NULL,
    source_sbom_component_uuid uuid NOT NULL,
    relationship_type text NOT NULL,
    declaring_artifact_uuid uuid NOT NULL,
    source_exact_purl_uuid uuid,
    target_exact_purl_uuid uuid,
    CONSTRAINT release_sbom_edges_unique
        UNIQUE NULLS NOT DISTINCT (
            release_uuid,
            target_sbom_component_uuid,
            source_sbom_component_uuid,
            relationship_type,
            declaring_artifact_uuid,
            source_exact_purl_uuid,
            target_exact_purl_uuid)
);

-- Two read patterns dominate: "in-edges for one target" (impact / reverse)
-- and "out-edges for one source" (forward dependency reconstruction). Both
-- get a composite that includes release_uuid as the lead so product-release
-- merging across many release_uuids stays cheap.
CREATE INDEX release_sbom_edges_release_target_idx
    ON rearm.release_sbom_edges (release_uuid, target_sbom_component_uuid);
CREATE INDEX release_sbom_edges_release_source_idx
    ON rearm.release_sbom_edges (release_uuid, source_sbom_component_uuid);
CREATE INDEX release_sbom_edges_org_idx
    ON rearm.release_sbom_edges (org);

-- ---------------------------------------------------------------------------
-- 8. Re-enqueue every non-archived release so the reconcile scheduler /
--    operator force-reconcile rebuilds the now-empty tables. Mirrors the
--    V25 / V27 / V28 tail UPDATEs. Archived releases are deliberately
--    excluded — their aggregation will never change.
-- ---------------------------------------------------------------------------
UPDATE rearm.releases
SET flow_control = jsonb_set(
        coalesce(flow_control, '{}'::jsonb),
        '{sbomReconcileRequestedAt}',
        to_jsonb(now()),
        true)
WHERE coalesce(record_data->>'status', 'ACTIVE') != 'ARCHIVED';
