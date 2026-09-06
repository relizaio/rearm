-- ---------------------------------------------------------------------------
-- FDA-Readiness-1: per-component support attestation, third shape.
--
-- Consolidates and replaces everything the feature has stored so far:
--   * V82  flat support columns on sbom_components + sbom_component_support_audit
--   * V900 support_policies (the CLE support-policy catalog, withdrawn in #475)
--   * V901 sbom_component_support_milestones + _audit
--
-- V900/V901 were scratch-range files on the fda-readiness-1-dev branch and are
-- deleted alongside this migration.
--
-- THE VERSION NUMBER 83 HAS BEEN USED BEFORE. `main` carried a DIFFERENT
-- V83__support_policies.sql (and a V84__sbom_component_support_milestones.sql)
-- from bf266ddd (2026-08-31, PR #465) until 1e387492 (2026-09-01, PR #473)
-- removed them -- a window of roughly 23 hours. Any database that ran `main`
-- inside that window holds flyway_schema_history rows for 83 and 84 with a
-- different description and checksum.
--
-- Such a database needs a manual repair BEFORE this migration reaches it, and the
-- repair is required whatever version number we pick. Flyway here runs on stock
-- defaults (validateOnMigrate=true, ignoreMigrationPatterns unset, which means
-- `*:future`), and that default is what makes the timing counter-intuitive:
--   * While `main` topped out at 82, rows 83 and 84 were ABOVE the highest
--     resolved version, i.e. FUTURE migrations, which Flyway IGNORES. So such a
--     database has been validating fine ever since #473 -- it is NOT already
--     broken, and nothing warns you.
--   * The moment ANY migration >= 83 is added, that stops. With this file, 83
--     resolves to different content -> "Migration checksum mismatch". Had we
--     numbered it 85 instead, 83 and 84 would fall BELOW the max and become
--     "Detected applied migration not resolved locally" -- equally fatal.
-- Both behaviours verified empirically against localhost:5440 on 2026-09-02, not
-- assumed. So the number chosen changes the error message and nothing else.
--
-- *** DO NOT RUN `flyway repair` TO CLEAR THAT CHECKSUM MISMATCH. ***
--
-- It is the obvious reflex and it CORRUPTS THE SCHEMA SILENTLY. `repair` rewrites
-- the stored checksum to match this file and marks version 83 as resolved WITHOUT
-- EXECUTING IT. Startup then succeeds, rearm.sbom_component_support is never
-- created, and the failure surfaces later as "relation does not exist" on the first
-- attestation write and on the coverage query -- far from the cause.
--
-- The correct repair is by hand: DELETE the 83 and 84 rows from
-- public.flyway_schema_history and DROP the objects those old migrations created
-- (support_policies, sbom_component_support_milestones,
-- sbom_component_support_milestone_audit, and the sbom_components.support_party
-- column). Then start normally and this migration applies from scratch.
-- See ai-plans/fda-readiness-1-plan.md section 7d steps 0 and 0b.
--
-- Environments checked and repaired: the shared test DB (localhost:5440) and the
-- claude2 sandbox. Every affected table was empty in both, so nothing was lost.
-- No release carries the old pair: relizaio/rearm-saas has no git tags at all, and
-- CE main and every CE tag top out at V82. ANY OTHER ENVIRONMENT DEPLOYED FROM
-- `main` DURING THAT WINDOW NEEDS THE SAME REPAIR and should be checked before this
-- reaches it -- and because of the future-migration rule above, such an environment
-- looks perfectly healthy until this migration lands. Do not read a green deploy
-- today as evidence that it is unaffected.
--
-- The DROP ... IF EXISTS statements below are the safety net for a box whose
-- history rows were deleted but whose objects were left behind.
--
-- NO BACKFILL. V82 reached one CE tag (26.08.95) but has no consumers and no data
-- anywhere; V900/V901 never left this host.
-- ---------------------------------------------------------------------------

-- --- 1. Remove the withdrawn storage ---------------------------------------

DROP TABLE IF EXISTS rearm.support_policies;                        -- V900
DROP TABLE IF EXISTS rearm.sbom_component_support_milestone_audit;  -- V901
DROP TABLE IF EXISTS rearm.sbom_component_support_milestones;       -- V901
DROP TABLE IF EXISTS rearm.sbom_component_support_audit;            -- V82
DROP INDEX IF EXISTS rearm.sbom_components_eos_idx;                 -- V82

ALTER TABLE rearm.sbom_components
    DROP COLUMN IF EXISTS end_of_support_date,     -- V82
    DROP COLUMN IF EXISTS end_of_life_date,        -- V82
    DROP COLUMN IF EXISTS support_source,          -- V82
    DROP COLUMN IF EXISTS support_last_assessed,   -- V82
    DROP COLUMN IF EXISTS support_asserted_by,     -- V82
    DROP COLUMN IF EXISTS support_notes,           -- V82
    DROP COLUMN IF EXISTS support_party;           -- V901

-- --- 2. The attestation record ----------------------------------------------

-- One row per component that has been ASSESSED. The row's existence means "a human
-- looked"; it does NOT mean any date was found. That is the case V901 could not
-- represent -- its milestone rows required a NOT NULL date, so "assessed, nothing
-- published" (diligence evidence in its own right) had nowhere to live. Here it is
-- a row with an empty `milestones` object.
--
-- Keyed on sbom_component_uuid, NOT (org, canonical_purl). canonical_purl is
-- recomputed by sweepStaleCanonicalQualifiers against CANONICAL_FORM_VERSION;
-- keying a regulatory record on a value a scheduled job rewrites would turn a
-- detectable dangling uuid into an attestation that silently stops matching its
-- component. The purl is kept on the row as a re-link hint for a recovery pass
-- that IS NOT YET WRITTEN -- no code reads this column today.
CREATE TABLE rearm.sbom_component_support (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    -- Named, not an auto-generated "..._key": the write path matches on this name to
    -- tell "another writer inserted the first attestation for this component
    -- concurrently" (retryable) from any other integrity error (not retryable).
    sbom_component_uuid uuid NOT NULL
        CONSTRAINT sbom_component_support_component_unique UNIQUE,
    org uuid NOT NULL,
    canonical_purl text NOT NULL,
    support_data jsonb NOT NULL,
    revision integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    CONSTRAINT sbom_component_support_data_is_object
        CHECK (jsonb_typeof(support_data) = 'object'),
    -- Payload evolution is by ADDITIVE JSONB FIELDS ONLY; there is deliberately no
    -- schema_version column. Most *Data classes in this codebase pair one with a read
    -- guard that throws on an unsupported version, and this payload has no such guard --
    -- a declared-but-never-read column reads as "versioning is handled" to the next
    -- person to reshape the payload, when it is not. Half-declaring is the one option
    -- that costs something.
    --
    -- A malformed milestone date must not be storable, because it cannot be made safe on
    -- read: a date that fails to parse is skipped by the injector, which leaves the
    -- component emitted with its other properties and no date -- indistinguishable from
    -- the deliberate "assessed, upstream publishes nothing" claim. A stored 2020 EOL
    -- would export as an affirmative statement that no EOL exists. That is fabrication,
    -- not degradation, so the loudness belongs on the write that causes it, on that row
    -- alone, and BEFORE the value can be read back.
    --
    -- All three predicates are load-bearing:
    --   * the milestones type check rejects an array, which jsonb_path_exists ignores;
    --   * the per-value object check rejects {"END_OF_SUPPORT": "notanobject"}, which is
    --     well-formed JSON that fails Jackson binding and would make the whole row
    --     unreadable -- and an unreadable row disappears from the export entirely;
    --   * the date predicate needs BOTH halves: like_regex alone accepts a JSON number
    --     (20270101 coerces to a string on read), and the type check alone accepts
    --     "31/01/2027".
    --
    -- The month/day ranges are deliberate, not decoration: a bare [0-9]{2} accepts
    -- "9999-99-99", which matches the shape, passes this constraint, and then throws in
    -- LocalDate.parse on read -- reproducing the exact fabrication above. What remains
    -- possible is a well-formed impossible date ("2026-02-31"); that is caught on the
    -- write path by the mutation's own parse, which is where a calendar check belongs.
    -- The fixed 10-character shape also preserves lexicographic ordering, which any
    -- future approaching-EOS filter will depend on.
    CONSTRAINT sbom_component_support_milestone_shape CHECK (
        (support_data->'milestones' IS NULL
         OR jsonb_typeof(support_data->'milestones') IN ('object','null'))
        AND NOT jsonb_path_exists(support_data,
            '$.milestones.* ? (@.type() != "object")')
        AND NOT jsonb_path_exists(support_data,
            '$.milestones.*.date ? (@.type() != "string"
                || !(@ like_regex "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$"))')
    )
);

-- Backs existsSupportByOrg, the cheap probe that lets a zero-support org skip
-- per-component resolution on every BOM download. org is denormalized onto this
-- table so the probe needs no join.
CREATE INDEX sbom_component_support_org_idx ON rearm.sbom_component_support (org);

-- No index on canonical_purl and none on any milestone date. The date indexes that
-- V82 and V901 created backed an "approaching / past EOS" filter that no query in
-- this codebase performs -- the only reads are the org probe above and a coverage
-- count. Adding one when a filtered view is actually built is a one-line migration;
-- carrying an unused index that has to be kept correct is not free.

-- --- 3. Append-only attestation history -------------------------------------

-- ALCOA input-side record of record: one row per accepted write, storing the whole
-- after-image. asserted_date is the SYSTEM's contemporaneous record of when the
-- assertion was filed and is deliberately default now(). It is NOT the same fact as
-- support_data->>'assessedAt', which is CALLER-SUPPLIED and states when the human
-- actually did the assessment -- those differ whenever someone records earlier work,
-- and conflating them would misstate the evidence.
CREATE TABLE rearm.sbom_component_support_audit (
    uuid uuid NOT NULL PRIMARY KEY default gen_random_uuid(),
    sbom_component_uuid uuid NOT NULL,
    org uuid NOT NULL,
    support_revision integer NOT NULL,
    support_data jsonb NOT NULL,
    asserted_by uuid,
    asserted_date timestamptz NOT NULL default now(),
    -- Why THIS write happened, as distinct from support_data.justification, which is the
    -- basis for the level-of-support CLAIM. They are different facts and sharing one field
    -- corrupts the more important one: a milestone removal carries its own reason, and if
    -- that reason were written into the payload it would overwrite the basis for an
    -- ABANDONED attestation -- so an export would ship "removed a typo'd EOL" as the
    -- justification for calling someone's project abandoned. Required on a removal, optional
    -- otherwise, and never exported: this is the internal record of an edit.
    reason text,
    CONSTRAINT sbom_component_support_audit_data_is_object
        CHECK (jsonb_typeof(support_data) = 'object')
);

-- The audit table deliberately carries NO date-shape constraint. It records what was
-- actually asserted, including shapes a later constraint would reject; a history table
-- that refuses to record history is worse than one holding an ugly row.

CREATE INDEX sbom_component_support_audit_component_idx
    ON rearm.sbom_component_support_audit (sbom_component_uuid);
