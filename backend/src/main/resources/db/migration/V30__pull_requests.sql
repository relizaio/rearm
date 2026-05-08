-- First-class PullRequest entity. Until now PR data was denormalized onto
-- BranchData.pullRequestData (a Map<Integer, PullRequestData>) keyed by PR
-- number — which collapsed PR identity into "branch + integer" and made
-- it impossible to model GitLab MRs / Gerrit change-IDs (string-keyed),
-- monorepo PRs that span multiple components, or independent PR-level
-- validation / dispatch state. The new entity owns its own identity
-- ((targetVcsRepository, identity)), its own commits list (SCE UUIDs),
-- and two parallel event streams: pr_validation_events (outbound to SCM)
-- and release_validation_events (inbound from releases attributed to
-- this PR via SCE matching).
--
-- record_data jsonb pattern mirrors Release/Component etc. Event arrays
-- live in their own jsonb columns so writers can append with @Modifying
-- SQL without rewriting the whole record_data blob (and the @DynamicUpdate
-- aliasing problem documented on Release.java).
CREATE TABLE rearm.pull_requests (
    uuid uuid NOT NULL UNIQUE PRIMARY KEY default gen_random_uuid(),
    revision integer NOT NULL default 0,
    schema_version integer NOT NULL default 0,
    created_date timestamptz NOT NULL default now(),
    last_updated_date timestamptz NOT NULL default now(),
    record_data jsonb NOT NULL,
    pr_validation_events jsonb NOT NULL default '[]'::jsonb,
    release_validation_events jsonb NOT NULL default '[]'::jsonb,
    update_events jsonb NOT NULL default '[]'::jsonb
);

-- Identity scoped to target repo. GitLab MR iids and Gerrit change-IDs
-- are repo-scoped strings, so this is the natural uniqueness boundary.
-- Cross-repo PRs (sourceVcsRepository != targetVcsRepository) still
-- belong to the target repo for identity purposes.
CREATE UNIQUE INDEX pull_requests_target_repo_identity
    ON rearm.pull_requests ((record_data->>'targetVcsRepository'),
                            (record_data->>'identity'));

CREATE INDEX pull_requests_org ON rearm.pull_requests ((record_data->>'org'));
CREATE INDEX pull_requests_state ON rearm.pull_requests ((record_data->>'state'));
CREATE INDEX pull_requests_source_repo
    ON rearm.pull_requests ((record_data->>'sourceVcsRepository'));

-- The PR aggregator looks up "all releases whose head SCE is one of these
-- commit UUIDs" on every dispatch. Without this index that scan is a full
-- table walk over rearm.releases keyed by the jsonb expression, which is
-- the hot path for VALIDATE_PR fan-in.
CREATE INDEX IF NOT EXISTS releases_source_code_entry
    ON rearm.releases ((record_data->>'sourceCodeEntry'));
