-- Durable per-branch completion marker for the RESUMABLE v3 (events-lite) backfill DRAIN (board task #38
-- follow-on). The former one-shot boot backfill (backfillFindingChangeV3) re-walked every uncertified
-- org's branches from ZERO on each boot -- certification is all-or-nothing at org granularity, so any
-- interruption discarded all progress and a large instance never certified. The drain instead processes a
-- bounded batch of branches per tick and marks each cleanly-completed branch HERE, so it RESUMES instead
-- of restarting.
--
-- GRAIN. The v3 backfill is branch-chained: a branch needs its OWN full release chain but never reads
-- sibling branches, so a branch is a self-contained, correctly-resumable unit. One marker row per branch.
--
-- key_version is the SAME stamp the org watermark carries (FindingDimKey.KEY_VERSION). A vocabulary bump
-- makes every prior marker stale (the drain queries markers at the CURRENT key_version, so old rows are
-- invisible) and the drain re-processes per-branch and resumably -- not a one-shot org re-walk.
--
-- NO FOREIGN KEYs (per coding_principles.md): branch_uuid / org_uuid are bare uuids; a dangling ref (a
-- deleted branch) is harmless -- an orphan marker is simply never queried. Forward-only. ASCII-only.
CREATE TABLE rearm.finding_change_v3_branch_seed (
    branch_uuid  uuid NOT NULL,
    org_uuid     uuid NOT NULL,
    key_version  int  NOT NULL,
    completed_at text NOT NULL,          -- RFC-3339 UTC instant string (matches the org watermark's stamp)
    PRIMARY KEY (branch_uuid, key_version)
);

-- Drives the per-org drain query "which of this org's branches are already marked at the current version".
CREATE INDEX finding_change_v3_branch_seed_org_ver_idx
    ON rearm.finding_change_v3_branch_seed (org_uuid, key_version);
