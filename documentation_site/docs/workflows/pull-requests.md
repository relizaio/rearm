# Pull Requests

::: tip Available in ReARM CE and ReARM Pro
The Pull Request entity itself — creation, attribution, and aggregation of releases — is part of the shared ReARM codebase and works on **both ReARM CE and ReARM Pro**.

Posting verdicts back to the upstream SCM (GitHub check-runs and PR comments) is **ReARM Pro only** — see [GitHub Pull Request Validation](../integrations/githubValidate) for that side.
:::

## What is a Pull Request in ReARM?

A Pull Request (PR) is a first-class entity in ReARM that represents a proposed change in your upstream Source Code Management system — a GitHub PR, GitLab MR, or Gerrit change. Each PR is uniquely identified by `(target VCS repository, SCM-side identity)`. The identity is whatever string the SCM uses:

- GitHub — PR number (e.g. `1234`)
- GitLab — MR `iid`
- Gerrit — change-id

A PR record stores:

- **Source and target branches**, and their VCS repositories.
- **Title**, **endpoint URL**, and **state** (`OPEN` / `CLOSED` / `MERGED`).
- An **append-only list of head commits** (Source Code Entry / SCE UUIDs). Every CI run on the PR appends to this list, so the *current* head is the last entry while older heads remain available for historical attribution.
- Aggregated **PR validation events** derived from the releases attributed to its commits.

### Why a first-class PR entity?

Earlier versions of ReARM tracked PR data on the source branch. The first-class PR entity adds:

- **Cross-component attribution.** A monorepo PR that builds three components produces three releases on the same head commit; the PR aggregates over all of them.
- **Stable identity across CI runs.** PRs are keyed by `(target VCS, identity)` — re-running CI is idempotent, no duplicate records.
- **Independence from branch lifecycle.** Closing a feature branch upstream no longer destroys the PR's attribution history.

## Release attribution

When CI runs on a PR, the `addrelease` (or `getversion`) call optionally upserts the PR entity (when `--pr-identity` is provided) and links the build's commit SHA to the PR's `commits` list via the SCE. ReARM then attributes a release to a PR if the release's head SCE UUID appears anywhere in that PR's `commits[]`.

For multi-component (monorepo) PRs, the aggregator picks the **per-component-latest** release: for each component with at least one release attributed to the PR, it selects the release on the most recent commit in the PR's commit list, with `createdDate desc` as tie-breaker. Older releases for the same component on earlier commits are not double-counted.

## Aggregation and verdicts

ReARM folds the per-component-latest releases into a single PR-level verdict:

| Verdict | Meaning |
|---|---|
| `SUCCESS` | Every per-component-latest release reports SUCCESS. |
| `FAILURE` | At least one per-component-latest release reports FAILURE, or its lifecycle is `CANCELLED` / `REJECTED`. |
| `PENDING` | At least one per-component-latest release has not yet been validated. The PR-level verdict is **not dispatched** while pending — the prior dispatched verdict (if any) remains visible on the SCM. |
| `NEUTRAL` | The PR has no commits, or no releases yet attributed. Terminal — dispatched if no prior verdict exists. |

A `pr_validation_event` is recorded any time the verdict differs from the latest event for the current head SCE. SCM dispatch only fires on **terminal** (non-`PENDING`) verdicts — see [GitHub Pull Request Validation](../integrations/githubValidate#external_validation--per-pr-aggregated-check-run) for how the dispatch is configured.

## Sending PR data to ReARM

ReARM is CI-driven — no inbound SCM webhook is required. Three ways to upsert a PR.

### From CI together with a release

The official [`relizaio/rearm-actions`](https://github.com/relizaio/rearm-actions) handles this for you on `pull_request` events. If you build your own pipeline, pass the `--pr-*` flags to `addrelease` (or `getversion`):

```sh
rearm-cli addrelease \
  --commit "$PR_HEAD_SHA" \
  --branch "$SOURCE_BRANCH" \
  --pr-identity "$PR_NUMBER" \
  --pr-state OPEN \
  --pr-title "$PR_TITLE" \
  --pr-source-branch-name "$SOURCE_BRANCH" \
  --pr-target-branch-name "$TARGET_BRANCH" \
  --pr-endpoint "$PR_URL"
  # ...other addrelease flags
```

| Flag | Required | Description |
|---|---|---|
| `--pr-identity` | with `--pr-state` | SCM-side PR identity (string). GitHub PR number, GitLab MR iid, or Gerrit change-id. |
| `--pr-state` | with `--pr-identity` | `OPEN` / `CLOSED` / `MERGED`. |
| `--pr-title` | optional | Human-readable title. |
| `--pr-source-branch-name` | optional | Source branch (the branch the PR is being merged from). |
| `--pr-target-branch-name` | optional | Target branch (e.g. `main`). |
| `--pr-endpoint` | optional | URL of the PR in the upstream SCM. |

The PR is keyed by `(target VCS, --pr-identity)`, so re-running CI on the same PR is idempotent.

::: tip CI must send the PR head SHA, not the merge commit
On GitHub Actions, the values to pass to ReARM are:

```yaml
COMMIT: ${{ github.event.pull_request.head.sha || github.sha }}
BRANCH: ${{ github.head_ref || github.ref }}
```

`relizaio/rearm-actions` already does this for you on `pull_request` events. Inline workflows must mirror it — otherwise the check-run lands on `pull/N/merge` and branch protection treats it as missing.
:::

### Standalone PR upsert (no release)

For pipelines where you want to record the PR independently of a release — for example, on `pull_request: opened` before any build has finished, or to record `MERGED` state on `pull_request: closed` — use the standalone `pullrequest upsert` command:

```sh
rearm-cli pullrequest upsert \
  --identity "$PR_NUMBER" \
  --state OPEN \
  --title "$PR_TITLE" \
  --source-branch-name "$SOURCE_BRANCH" \
  --target-branch-name "$TARGET_BRANCH" \
  --endpoint "$PR_URL" \
  --vcsuri "$VCS_URI" \
  --repo-path "$REPO_PATH" \
  --commit "$PR_HEAD_SHA"   # optional — advances PR head when SCE exists
```

VCS resolution: pass either `--component <UUID>` or `--vcsuri` plus `--repo-path` (with optional `--vcs-display-name`). The `--commit` flag is optional — when set and an SCE already exists for `(target VCS, commit)`, the PR head is advanced in the same call without needing a separate `addrelease`.

### Programmatic GraphQL

For systems integrating directly with the API, the mutation is `upsertPullRequestProgrammatic`:

```graphql
mutation {
  upsertPullRequestProgrammatic(input: {
    identity: "1234",
    state: OPEN,
    title: "Fix login flow",
    sourceBranchName: "feat/login-fix",
    targetBranchName: "main",
    endpoint: "https://github.com/acme/app/pull/1234",
    vcsUri: "https://github.com/acme/app",
    repoPath: "."
  }) {
    uuid
    state
    attributedReleases { uuid version }
  }
}
```

Required fields: `identity` and `state`. Provide either `component` (UUID) or `vcsUri` + `repoPath` so ReARM can resolve the target VCS. An optional `commit` field on the input advances the PR head in the same call when an SCE already exists.

## PR closure

When a PR closes or merges upstream, ReARM transitions its `state` from `OPEN` to `CLOSED` or `MERGED`. Two paths converge:

- **Real-time.** Your CI workflow runs on `pull_request: closed` and sends `--pr-state CLOSED` (or `MERGED`) to ReARM via `addrelease`, `getversion`, or `pullrequest upsert`.
- **Scheduled catch-up.** The `syncbranches` job conservatively marks PRs `CLOSED` when their upstream source branch is deleted. This path never sets `MERGED` on its own — to record a merge, the CI workflow has to send the explicit signal.

Once a PR is `CLOSED` or `MERGED`, the aggregator stops re-evaluating it on incoming releases.

## Driving SCM-side outcomes

For ReARM to actually post a status to GitHub when the PR-level verdict changes, you need to wire up the [GitHub Pull Request Validation](../integrations/githubValidate) integration. That doc covers:

- App registration and the `GITHUB_VALIDATE` integration record (Org-level).
- The **VCS-level `EXTERNAL_VALIDATION`** trigger that fires on the aggregated PR verdict.
- The **release-level `PR_COMMENT`** trigger that posts a markdown comment on every open PR whose commits include the firing release's SCE.
- The **release-level `INVALIDATE_PR`** input event that contributes a FAILURE to PR aggregation on disapproval / rejection.
- Customising the check-run output via `clientPayload` and CEL expressions.

Both `EXTERNAL_VALIDATION` and `PR_COMMENT` are ReARM Pro–only because they push to the upstream SCM. The PR entity, attribution, and aggregator described on this page are part of the shared codebase and run on ReARM CE as well — you can use them to drive your own dashboards, scripts, or third-party integrations regardless of edition.
