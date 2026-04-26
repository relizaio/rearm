-- Per-release SBOM-component aggregation feature.
--
-- Two persistent tables:
--   sbom_components            canonical identity per component, keyed by the
--                              canonical purl (qualifiers + subpath stripped).
--                              Surrogate uuid PK because purls can be long /
--                              awkward to use as a foreign key.
--   release_sbom_components    join of (release, canonical component) carrying
--                              every artifact in the release that referenced
--                              the component (with the exact qualifier-bearing
--                              purls each one used) and every in-edge (parent)
--                              declared by any contributing BOM. Edges are
--                              stored as in-edges so the impact-analysis
--                              "what depends on this component" query is a
--                              primary-key read; forward edges are
--                              reconstructed in memory in the API layer.
--
-- And a flow_control jsonb on releases:
--   flow_control               generic per-release scheduling scratchpad. The
--                              only consumer today is the SBOM reconcile queue
--                              drained by the every-minute Dependency-Track
--                              scheduler. Keys are kept flat (one level deep)
--                              so a single jsonb_set call can create or update
--                              them — Postgres jsonb_set with create_missing
--                              only creates the leaf, not intermediate path
--                              segments. Future flows pick their own
--                              prefixed key names (e.g. dtrackSync*).
--                                {
--                                  "sbomReconcileRequestedAt":   "<iso8601>",
--                                  "sbomReconcileSkipUntil":     "<iso8601>",
--                                  "sbomReconcileFailureCount":  <int>
--                                }
CREATE TABLE rearm.sbom_components (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    canonical_purl text NOT NULL,
    record_data jsonb,
    CONSTRAINT sbom_components_canonical_purl_unique UNIQUE (canonical_purl)
);

CREATE INDEX sbom_components_name_idx
    ON rearm.sbom_components ((record_data->>'name'));

CREATE TABLE rearm.release_sbom_components (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    release_uuid uuid NOT NULL,
    sbom_component_uuid uuid NOT NULL,
    artifact_participations jsonb NOT NULL default '[]'::jsonb,
    parents jsonb NOT NULL default '[]'::jsonb,
    record_data jsonb,
    CONSTRAINT release_sbom_components_release_component_unique UNIQUE (release_uuid, sbom_component_uuid),
    CONSTRAINT release_sbom_components_release_fk FOREIGN KEY (release_uuid)
        REFERENCES rearm.releases(uuid) ON DELETE CASCADE,
    CONSTRAINT release_sbom_components_component_fk FOREIGN KEY (sbom_component_uuid)
        REFERENCES rearm.sbom_components(uuid) ON DELETE CASCADE
);

CREATE INDEX release_sbom_components_release_idx
    ON rearm.release_sbom_components (release_uuid);
CREATE INDEX release_sbom_components_component_idx
    ON rearm.release_sbom_components (sbom_component_uuid);
-- GIN on the participations jsonb so we can filter / match by artifact
-- participations or exact purls without scanning every row.
CREATE INDEX release_sbom_components_participations_gin_idx
    ON rearm.release_sbom_components USING GIN (artifact_participations);

ALTER TABLE rearm.releases
    ADD COLUMN flow_control jsonb;

-- Partial index keeps the scheduler pickup query cheap regardless of total
-- release count; only rows currently queued for reconcile are scanned.
CREATE INDEX releases_sbom_reconcile_pending_idx
    ON rearm.releases ((flow_control->>'sbomReconcileRequestedAt'))
    WHERE flow_control->>'sbomReconcileRequestedAt' IS NOT NULL;

-- One-time backfill: seed the queue for releases that pre-date this feature
-- so they get the same aggregation new releases get on first artifact mutation.
-- The reconcile early-returns for releases without BOM artifacts (cheap),
-- so over-enqueuing is fine. Archived releases are skipped because their
-- aggregation will never change.
UPDATE rearm.releases
SET flow_control = jsonb_build_object('sbomReconcileRequestedAt', now())
WHERE flow_control IS NULL
  AND coalesce(record_data->>'status', 'ACTIVE') != 'ARCHIVED';
