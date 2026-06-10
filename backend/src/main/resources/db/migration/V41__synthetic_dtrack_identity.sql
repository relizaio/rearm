-- Synthetic Dependency-Track submission on a converged identity model.
--
-- Two parts:
--   1. Generalize sbom_components into the dedup driver for synthetic SBOMs:
--      add identities (flat {scheme,value} union), licenses (exact CycloneDX
--      shape, re-emitted to DTrack), and enriched_at (BEAR-enrichment cursor
--      gating DTrack submission to enriched licenses).
--      canonical_purl keeps its name but now holds a generalized primary
--      identity (purl > cpe > swid > swhid > omniborid > cdx:synth).
--   2. synthetic_dtrack_bucket — submission-state bookkeeping for the
--      one-DTrack-project-per-bucket-per-org model. NOT a component registry;
--      bucket membership is DERIVED from a deterministic ordering of the
--      matchable sbom_components for the org. The row records the content_hash
--      (sha256 over the sorted set of canonical_purls in the bucket) so a
--      membership change is detected and re-submitted; ref_map translates the
--      generated bom-refs (c0,c1,... — many-to-one for CPE companions) back to
--      canonical_purl; findings holds the last-ingested vuln/violation payload
--      keyed by canonical_purl, stored as plain JSON (never DTO records).
--
-- No FK constraints anywhere, per coding_principles.md.

-- ---------------------------------------------------------------------------
-- 1. Generalize sbom_components.
-- ---------------------------------------------------------------------------
ALTER TABLE rearm.sbom_components
    ADD COLUMN IF NOT EXISTS identities  jsonb,
    ADD COLUMN IF NOT EXISTS licenses    jsonb,
    ADD COLUMN IF NOT EXISTS enriched_at timestamptz;

-- enriched_at records when BEAR-enriched licenses were pulled for this canonical
-- component (NULL = not yet enriched). The synthetic Dependency-Track gate ships
-- a BEAR-configured org's component only once enriched_at is set, so DTrack
-- always receives enriched licenses. The lightweight enrichment puller drains
-- NULL rows. No backfill: existing rows stay NULL and are drained gradually —
-- marking them enriched would ship raw/missing-license SBOMs (wrong findings).

-- Partial index over the matchable population (purl- or cpe-canonical rows):
-- the bucketing query selects exactly these and orders them deterministically.
CREATE INDEX IF NOT EXISTS sbom_components_matchable_idx
    ON rearm.sbom_components (org, canonical_purl)
    WHERE canonical_purl LIKE 'pkg:%' OR canonical_purl LIKE 'cpe:%';

-- Drives the enrichment puller's candidate pick: un-enriched matchable rows for
-- an org, oldest first. Partial (enriched_at IS NULL) so it stays tiny as the
-- backlog drains.
CREATE INDEX IF NOT EXISTS sbom_components_unenriched_idx
    ON rearm.sbom_components (org, created_date)
    WHERE enriched_at IS NULL
      AND (canonical_purl LIKE 'pkg:%' OR canonical_purl LIKE 'cpe:%');

-- ---------------------------------------------------------------------------
-- 2. synthetic_dtrack_bucket — submission-state bookkeeping.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rearm.synthetic_dtrack_bucket (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    org uuid NOT NULL,
    bucket_index integer NOT NULL,
    dtrack_project_uuid uuid,
    content_hash text,
    ingest_state text NOT NULL default 'PENDING',
    ref_map jsonb NOT NULL default '{}'::jsonb,
    findings jsonb NOT NULL default '{}'::jsonb,
    last_submitted timestamptz,
    last_ingested timestamptz,
    CONSTRAINT synthetic_dtrack_bucket_org_index_unique UNIQUE (org, bucket_index)
);

CREATE INDEX IF NOT EXISTS synthetic_dtrack_bucket_org_idx
    ON rearm.synthetic_dtrack_bucket (org);
CREATE INDEX IF NOT EXISTS synthetic_dtrack_bucket_state_idx
    ON rearm.synthetic_dtrack_bucket (ingest_state);
