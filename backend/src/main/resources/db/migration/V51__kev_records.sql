-- V51 — CISA KEV catalog mirror (Phase 6a of the notifications plan).
-- GLOBAL table, intentionally no org column: the Known Exploited
-- Vulnerabilities catalog is public, instance-wide data (~1300 entries,
-- refreshed daily from the CISA JSON feed). Org-scoped read surfaces
-- join against it by CVE id at read time; nothing tenant-specific is
-- ever written here, so per-org rows would only multiply identical data.
--
-- The UNIQUE constraint doubles as the lookup index for the two access
-- paths: single-CVE detail reads and bulk `cve_id IN (...)` probes from
-- the knownExploited GraphQL data loader.

CREATE TABLE rearm.kev_records (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    cve_id text NOT NULL,
    record_data jsonb NOT NULL,
    CONSTRAINT kev_records_cve_id_unique UNIQUE (cve_id)
);
