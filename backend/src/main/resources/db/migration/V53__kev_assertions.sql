-- V53 — KEV assertions: per-source, soft-deletable model that supersedes
-- the single-source kev_records table (V51).
--
-- Why the reshape:
--   * Multiple sources can report a CVE as known-exploited (CISA today;
--     VulnCheck / ENISA EUVD later). One row per (source, cve_id) instead
--     of one row per CVE.
--   * Soft delete via revoked_date: when a source stops reporting a CVE we
--     stamp revoked_date instead of deleting the row. A CVE that was ever
--     asserted stays KEV-listed on read surfaces (the revocation shows as a
--     note), so a truncated/poisoned feed can never blank the KEV list, and
--     genuine history is preserved.
--
-- Still GLOBAL (no org column): the catalogs are public, instance-wide data
-- joined by cve_id at read time.
--
-- The 2-day-old kev_records table carries only re-fetchable public data, so
-- it is dropped rather than migrated; the next sync repopulates from source.

DROP TABLE IF EXISTS rearm.kev_records;

CREATE TABLE rearm.kev_assertions (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    source text NOT NULL,
    cve_id text NOT NULL,
    revoked_date timestamptz,
    record_data jsonb NOT NULL,
    CONSTRAINT kev_assertions_source_cve_unique UNIQUE (source, cve_id)
);

-- The read-time membership probe is `cve_id IN (...)` across all sources;
-- the (source, cve_id) unique index leads with source, so it can't serve
-- that lookup. Dedicated cve_id index for the knownExploited data loader
-- and the single-CVE detail read.
CREATE INDEX kev_assertions_cve_id_idx ON rearm.kev_assertions (cve_id);
