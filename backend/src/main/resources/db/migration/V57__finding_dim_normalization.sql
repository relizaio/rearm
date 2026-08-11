-- finding_change_events normalization (board task #38 follow-up) -- phase 1: ADDITIVE.
--
-- Splits the 148x-duplicated intrinsic finding identity out of finding_change_events
-- into a small finding_dim dimension (stored once per distinct finding), and adds a
-- slim fact table finding_change_events_v2 that references it. Measured on a real
-- instance: 605 MB -> ~304 MB (~2x), dimension ~3 MB / ~6k rows.
--
-- This migration is METADATA-ONLY (empty CREATE TABLEs + indexes) so it is safe at
-- boot: it never touches the ~900k-row live table. Population, dual-write, read-flip
-- and the eventual drop of the old table are all app-owned, post-boot, per-org-gated
-- steps (later phases) -- NOT Flyway, so an incomplete migration can never strand a
-- customer instance.
--
-- KEY CHOICE (validated empirically): the dimension's natural key is EXACTLY
-- (finding_kind, finding_key) -- v1's finding identity. All other intrinsic columns
-- (purl / vuln_id / cwe_id / rule_id / location / violation_type / aliases) are NON-KEY
-- payload (representative variant). Keying on (kind, finding_key) makes
-- distinct-dim == distinct-finding_key a STRUCTURAL invariant (not an empirical accident)
-- and gives EXACT v1 dedup parity: the v1 unique index already deduped on finding_key, so
-- the dimension collapses precisely what v1 collapsed. On real data this is 6,032 dims for
-- 895k rows (605 MB -> 304 MB). Aliases (the sole historically-drifting field) render the
-- representative variant on ~1.2% of rows -- alternate IDs for the same CVE, cosmetic.
--
-- FROZEN KEY: dim_hash = FindingDimKey.hash(finding_kind, finding_key) = SHA-256 of
-- (KEY_VERSION + NUL + kind + NUL + finding_key), truncated to 16 bytes. key_version stores
-- the canonical-form version so a future re-key is detectable and old/new keys can coexist.
--
-- NO FOREIGN KEYs (per coding_principles.md). finding_change_events_v2.finding_dim is
-- a bare uuid reference; referential integrity is the service layer's job; reads
-- tolerate a dangling dim exactly as they tolerate a dangling release ref today.
--
-- NAMING: finding_change_events_v2 is a transitional name. At the operator-gated cutover
-- (a later phase) the old table is dropped and this one is renamed to finding_change_events
-- (a cheap metadata rename); the _v2 suffix does not survive to steady state.

-- Dimension: one row per distinct finding identity, stored once.
CREATE TABLE rearm.finding_dim (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    dim_hash bytea NOT NULL,        -- 16-byte FindingDimKey digest of (finding_kind, finding_key)
    key_version smallint NOT NULL default 1,  -- FindingDimKey.KEY_VERSION the dim_hash was computed at
    finding_kind text NOT NULL,     -- VULNERABILITY / VIOLATION / WEAKNESS (in the key)
    finding_key text NOT NULL,      -- v1's finding identity (in the key)
    purl text,                      -- payload (representative)
    vuln_id text,                   -- payload
    cwe_id text,                    -- payload
    rule_id text,                   -- payload
    location text,                  -- payload
    violation_type text,            -- payload
    aliases jsonb,                  -- payload (representative variant; sole drift source)
    created_date timestamptz NOT NULL default now()  -- mint time (forensic signal for drift diagnosis)
);

-- Natural key: the content hash, org-scoped (finding_key is not globally unique).
-- The write path resolves finding_dim via ON CONFLICT (org, dim_hash) DO NOTHING.
CREATE UNIQUE INDEX finding_dim_org_hash_idx
    ON rearm.finding_dim (org, dim_hash);

-- Slim fact: one row per transition (unchanged count), referencing the dimension.
-- Keeps only event-specific state + the denormalized release attribution
-- (component_name / version survive dangling release refs, documented intent).
CREATE TABLE rearm.finding_change_events_v2 (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    org uuid NOT NULL,
    entity_type text NOT NULL default 'RELEASE',
    release_uuid uuid NOT NULL,
    version text NOT NULL,
    component_uuid uuid NOT NULL,
    component_name text NOT NULL,
    finding_dim uuid NOT NULL,      -- reference to finding_dim.uuid (bare, no FK)
    finding_kind text NOT NULL,     -- kept on the fact for kind-filtered reads without a dim lookup
    change_date timestamptz NOT NULL,
    to_metrics_revision integer NOT NULL,
    change_kind text NOT NULL,      -- APPEARED / RESOLVED / SEVERITY_* / KEV_*
    severity text,
    previous_severity text,         -- populated only for SEVERITY_INCREASED / SEVERITY_DECREASED
    known_exploited boolean,
    analysis_state text,
    created_date timestamptz NOT NULL default now()
);

-- Org-level changelog read: all changes for an org over a date window.
CREATE INDEX finding_change_events_v2_org_date_idx
    ON rearm.finding_change_events_v2 (org, change_date);

-- Per-release changelog read: a single release's change timeline.
CREATE INDEX finding_change_events_v2_release_date_idx
    ON rearm.finding_change_events_v2 (release_uuid, change_date);

-- Idempotency key: mirrors the v1 dedup index but references finding_dim (uuid, 16 B)
-- instead of the finding_key text. finding_kind is omitted here (unlike v1): finding_dim is
-- keyed on (finding_kind, finding_key), so kind is functionally determined by finding_dim and
-- would be redundant width in the index. Because the dimension key is EXACTLY v1's finding_key
-- identity, a given (release, revision, change_kind, finding) maps to one finding_dim, so this
-- dedups exactly as the v1 (release, revision, change_kind, finding_kind, finding_key) index did.
CREATE UNIQUE INDEX finding_change_events_v2_dedup_idx
    ON rearm.finding_change_events_v2 (release_uuid, to_metrics_revision, change_kind, finding_dim);
