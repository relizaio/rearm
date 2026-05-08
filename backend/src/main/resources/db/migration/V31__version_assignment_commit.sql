-- Commit-keyed dedup at version-assignment time. When two CI flows fire
-- getversion concurrently for the same git commit (typical: push +
-- pull_request events on the same head), each flow currently gets a
-- distinct version slot and downstream addrelease creates a separate
-- Release with a separate ASSEMBLED lifecycle on the same SCE — the
-- branch lists two near-identical releases for the same commit.
--
-- Storing the commit SHA on version_assignments and enforcing
-- (component, branch, commit) uniqueness moves the serialization point
-- from the addrelease/SCE-merge layer (where dedup-by-content is hard)
-- down to the version-allocation layer (where the natural key is
-- well-defined). The loser flow's getversion call fails with a clear
-- exception; CI exits non-zero and skips addrelease entirely. A new
-- --rebuild flag (next step in this change set) lets a caller opt in
-- to receiving the existing version instead of failing — for the
-- intentional re-issuance case.
ALTER TABLE rearm.version_assignments
    ADD COLUMN commit text NULL;

-- Partial unique index: only enforced when commit IS NOT NULL so existing
-- VAs (no commit recorded) and any future commit-less callers (manual
-- version mints, marketing-version assignments) remain unaffected. NULL
-- values do not collide under btree partial uniqueness.
CREATE UNIQUE INDEX version_assignments_component_branch_commit_unique
    ON rearm.version_assignments (component, branch, commit)
    WHERE commit IS NOT NULL;
