# GitHub Pull Request Validation (GitHub Validate)

::: warning ReARM Pro only
The integrations on this page (posting check-runs and PR comments to GitHub) are part of ReARM Pro and are not available in ReARM Community Edition.

The underlying [Pull Request entity, release attribution, and PR-level aggregation](../workflows/pull-requests) are part of the shared ReARM codebase and run on **both editions** — only the *push back to the upstream SCM* is gated to Pro.
:::

::: info Private repositories
Posting check-runs against **private** repositories requires a GitHub Enterprise subscription on the customer side, since branch protection on private repos is gated behind GitHub's Enterprise tier.
:::

GitHub Validate lets ReARM block a Pull Request from being merged until ReARM has verified the release(s) attributed to the PR. ReARM posts a [GitHub check-run](https://docs.github.com/en/rest/checks/runs) on the PR's head SHA, and a branch-protection rule on the target branch makes that check required for merge. Optionally, ReARM also posts a markdown comment on the PR when a release fires a `PR_COMMENT` event.

The end-to-end flow is:

1. **CI** builds the PR head commit and calls ReARM (`getversion` / `addrelease`) with the PR head SHA, the source branch (`head_ref`), and the `--pr-*` flags so ReARM upserts the [first-class Pull Request entity](../workflows/pull-requests) and links the build's commit to it. *Optionally* — the [inbound webhook](#inbound-webhook--real-time-pr-state-sync) covers `pull_request: opened/closed/merged` events, so the CI run on those triggers becomes optional.
2. **The PR aggregator** folds release-level outcomes into a single PR-level verdict (SUCCESS / FAILURE / PENDING / NEUTRAL) — see [Aggregation and verdicts](../workflows/pull-requests#aggregation-and-verdicts).
3. **ReARM mints an installation token** from your GitHub App and dispatches one of the configured event types:
   - `EXTERNAL_VALIDATION` → posts a GitHub check-run (PR-level after aggregation, or per-release).
   - `PR_COMMENT` → posts a markdown comment on every open PR whose commits include the firing release's SCE.
   - `VALIDATE_PR` → internal signal that contributes SUCCESS to PR aggregation; the actual SCM push happens via the aggregator's `EXTERNAL_VALIDATION` trigger.
   - `INVALIDATE_PR` → internal signal that contributes FAILURE to PR aggregation; the actual SCM push happens via the aggregator's `EXTERNAL_VALIDATION` trigger.
4. **A branch-protection rule** on `main` (or your target branch) lists the ReARM check-run name as a required status check, so the PR cannot be merged until the check posts a passing conclusion.

## GitHub Part

### 1. Register a GitHub App

It is recommended to use a GitHub App distinct from any "Trigger Workflows" app you may already have, because the permission sets are different.

Follow the upstream guide for [registering a GitHub App](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app#registering-a-github-app). Defaults are fine, except:

- **Webhook**: optional. Always leave **Active** unchecked when initially creating the application. Generally, Webhook is not required but recommended if you want to receive inbound `pull_request` events into ReARM, in which case you should configure it after the App is installed (See [Inbound webhook](#inbound-webhook--real-time-pr-state-sync) below).
- **Repository permissions**:
  - **Checks** → **Read and write** (required to post the check-run).
  - **Pull requests** → **Read and write** (Read is required so the App can resolve PR head SHA; Write is required to post `PR_COMMENT` comments via the issues/comments endpoint).
  - **Metadata** → **Read** (mandatory for any App that touches a repo).

Choose whether to allow installation only on your account or on any account based on your needs. Note, that it is generally recommended to install for your account only, otherwise your application will be globally visible in GitHub.

### 2. Note the App ID

Once the App is created, on its home page note the **App ID** (a small integer). You will paste it into the ReARM integration form.

### 3. Generate the App Private Key

On the App home page scroll down to **Private keys** → **Generate a private key**. GitHub downloads a `.pem` file to your machine — keep it safe.

::: tip PEM is accepted directly
You **no longer need** to convert the `.pem` to DER base64 with `openssl pkcs8 ...`. ReARM accepts the raw `.pem` (PKCS#1 or PKCS#8) and normalizes it server-side. The legacy DER-base64 shape is also still accepted for backward compatibility.
:::

### 4. Install the App on the target repository / repositories

From the App home page click **Install App** and select the repositories you want ReARM to be able to post check-runs against.

After install, GitHub takes you to a settings page whose URL contains the **Installation ID** — for example, `https://github.com/settings/installations/12345678`. Note this number; you will paste it into ReARM's output event form.

## ReARM Part

### 1. Register the integration (Org Admin)

1. In ReARM, open **Organization Settings** → **Integrations** tab → **CI Integrations** sub-section.
2. Under **GitHub**, click **Add** or **Add another GitHub** depending on your existing integrations.
3. **Description**: anything memorable, e.g. `GitHub Validate (acme-org)`.
4. **Capabilities**: check **PR Validate**, if you're planning to configure Webhook, also check **Webhook (inbound)**, if you're also using same app for workflow dispatch also select **Workflow Dispatch** (note that mixing Workflow Dispatch and PR capabilities is not recommended due to mixing permissions).
5. **GitHub Private Key**:
   - Toggle **Upload .pem** and select the `.pem` file from step 3 above, **or**
   - Toggle **Paste** and paste the contents of the `.pem` file directly.
6. **GitHub Application ID**: paste the App ID from step 2.
7. Click **Save**.

The integration is now stored, with the private key encrypted at rest.

### 2. Make sure the VCS repository is registered

In ReARM, register the GitHub repository whose PRs you want to gate (either via Component creation, or via the **VCS** menu item and the plus-circle icon), or it may be self-registered via CI - in any case, make sure that VCS repository is available in ReARM. The repository's `vcsuri` must contain `github.com/<org>/<repo>` — ReARM uses this to build the check-run and comment URLs.

### 3. Choose where to attach the trigger

Four event types drive different SCM outcomes. They live in different places in the UI:

| Event type | Where configured | What it does |
|---|---|---|
| [`EXTERNAL_VALIDATION` (Global PR Validation rule)](#external_validation--global-pr-validation-rule-recommended) | **Organization Settings → Integrations → PR Validation** | Org-wide ordered list of rules; each rule has a VCS URI regex and a single integration + installation id. The first rule whose regex matches a given repo contributes its trigger to every PR in that repo, automatically across as many repos as the regex covers. **Recommended starting point.** |
| [`EXTERNAL_VALIDATION` (per-release, component-level)](#external_validation--per-release-component-level) | **Component → Actions** | Posts a check-run for one specific release on the configured component. Suitable when CI builds a single component per PR. |
| [`EXTERNAL_VALIDATION` (per-release, policy-wide)](#external_validation--per-release-policy-wide) | **Approval Policy → Policy-Wide Actions** | Same as component-level but defined once on the Approval Policy so every component bound to that policy inherits it. |
| [`EXTERNAL_VALIDATION` (per-VCS override)](#external_validation--per-vcs-override-advanced) | **VCS Repository → Output Triggers** | Advanced: a per-repo override that wins over the matching Global PR Validation rule. Useful when one repo needs a different integration / installation id / check-name from the org-wide rule. |
| [`PR_COMMENT`](#pr_comment--comment-on-the-pr) | **Component → Actions** or **Approval Policy → Policy-Wide Actions** | Posts a markdown comment to every open PR whose commits include the firing release's SCE (commit). |
| [`INVALIDATE_PR`](#invalidate_pr-and-validate_pr--feeding-the-aggregator) / `VALIDATE_PR` | **Component → Actions** or **Approval Policy → Policy-Wide Actions** | Internal signals that feed the PR aggregator on approval / rejection. No direct SCM call — the SCM push happens via the matching `EXTERNAL_VALIDATION` trigger. |

::: info PR-aggregated vs per-release `EXTERNAL_VALIDATION`
A given PR is gated by either the PR-aggregated path (Global PR Validation rule or the per-VCS override) **or** the per-release path (component-level / policy-wide), not both. The PR-aggregated path is the recommended starting point because it handles monorepos correctly and keeps the GitHub UI clean (one ReARM check per PR, not one per component).
:::

### EXTERNAL_VALIDATION — Global PR Validation rule (recommended)

The Global PR Validation rule is **org-scoped** and **regex-matched across multiple VCS repos** — one rule can cover an entire org's repos. ReARM posts one PR-aggregated check-run; its conclusion follows the [aggregated verdict](../workflows/pull-requests#aggregation-and-verdicts) computed across every release attributed to the PR's commits.

1. In ReARM, open **Organization Settings → Integrations → PR Validation** sub-tab.
2. Click **+ Add rule**. Fill in:
   - **Name**: e.g. `Default GitHub PR validation`. Display label; not sent to GitHub.
   - **VCS URI regex**: a Java regex matched against the repo's `vcsuri`, fully anchored by the engine. Write `github.com/myorg/.*` (no leading `^`, no trailing `$`); the engine anchors automatically.
   - **GitHub Validate Integration**: pick the integration you registered in [ReARM Part step 1](#1-register-the-integration-org-admin). The dropdown is filtered to GitHub CI integrations that have the **PR Validate** capability enabled.
   - **GitHub Installation ID**: paste the Installation ID from [step 4 of the GitHub part](#4-install-the-app-on-the-target-repository--repositories).
   - **Check name override**: optional. Defaults to `rearm/pr/<identity>` where `<identity>` is derived from the PR identity. **Recommended** to set a stable string (e.g. `rearm/pr-validation`) when you intend to wire branch protection — branch protection requires an exact match, and a stable name avoids breakage as repos / components churn.
3. Click **Save**.

The new rule appears at the bottom of the list. **Rule order is priority order** — the first rule whose URI regex matches a given repo wins. Use the up/down arrows on a row to reorder.

::: tip One rule, many repos
You don't need one rule per repo. A regex like `github.com/acme/.*` covers every repo under the `acme` org, and a single rule is enough for most setups. Add a second rule only when you genuinely need different settings (different integration, different installation id, different check name) for a subset of repos — and put the more specific rule **above** the catch-all.
:::

::: info Customising the check-run output
The Global PR Validation rule UI does not expose `clientPayload` / `celClientPayload` directly in v1 — the check-run uses the server-side default output (see [Customising the check-run output](#customising-the-check-run-output) for the default shape). To customise per-release, drop down to the per-component or policy-wide path below.
:::

### EXTERNAL_VALIDATION — per-release, component-level

Use this when CI builds exactly one component per PR and you want a check-run that maps directly to that release's lifecycle (rather than to the PR-level aggregate). The Global PR Validation rule above is the better default for everything else.

1. Open the component you want to gate. Click the tool icon to toggle component settings.
2. Open the **Actions** tab. Click the plus-circle icon (Add Action).
3. **Name**: e.g. `Block PR until release is approved`.
4. **Type**: choose **External Validation**.
5. **Choose Validation Integration**: select the GitHub Validate integration you registered.
6. **Installation ID**: paste the Installation ID from step 4 of the GitHub part.
7. **VCS Repository**: select the repository registered above.
8. **Conclusion**: pick the [GitHub check-run conclusion](https://docs.github.com/en/rest/checks/runs#about-check-runs) ReARM should post:
   - `success` — the PR is good to merge.
   - `failure` — block the merge.
   - `neutral` — informational, doesn't block by itself.
   - `skipped` / `cancelled` — same as the GitHub semantics.
9. **Optional Output JSON** (free-form `title` / `summary` / `text` for the check-run): can be left empty — ReARM provides sensible defaults. See [Customising the check-run output](#customising-the-check-run-output).
10. **Dynamic output (CEL)**: optional CEL expression to compute the output JSON at fire time.
11. Click **Save**.

Repeat for each conclusion you want to drive — typically one action that posts `success` on approval and one that posts `failure` on rejection — and wire each into the appropriate rule / approval state.

### EXTERNAL_VALIDATION — per-release, policy-wide

If you want every component bound to a given Approval Policy to post check-runs the same way, define the per-release `EXTERNAL_VALIDATION` event on the **policy** instead of on each component:

1. Open **Organization Settings → Policies → Approval Policies** and select your policy.
2. Find **Policy-Wide Actions** → click the plus-circle icon.
3. Fill in the same fields described above. (The policy-wide form does not expose **VCS Repository** — the repo is resolved from each bound component's own VCS at fire time.)

### EXTERNAL_VALIDATION — per-VCS override (advanced)

`VcsRepository.outputTriggers` is a per-repo override that **wins over the matching Global PR Validation rule for that one repo**. Use it sparingly — usually only when:

- One repo legitimately needs a different integration or installation id from the rest of the org (e.g. a contractor's repo under a different GitHub App).
- You want to roll out a new check-name or output template on a single repo before flipping it for the whole org.

Resolution priority is **per-VCS override → Global PR Validation rule list (first regex match wins) → no PR-aggregated check-run** for repos none of the above covers (per-release `EXTERNAL_VALIDATION` still works for those).

1. Open the VCS repository under the **VCS** menu item.
2. Open the **Output Triggers** section. Click the plus-circle icon (Add Output Trigger).
3. Fill in **Name**, **Type: External Validation**, **Choose Validation Integration**, **Installation ID**, optional **Output JSON** / **Dynamic output (CEL)**.
4. Click **Save**.

The repo's `effectivePrValidationTrigger` resolver immediately switches to this override; no change needed to the org-wide rule list.

::: tip One override per VCS repo
The VCS-level outputTriggers list accepts at most one `EXTERNAL_VALIDATION` entry. The conclusion is derived from the aggregated verdict, so you don't need separate triggers for "success" and "failure".
:::

### PR_COMMENT — comment on the PR

`PR_COMMENT` posts a per-release markdown comment to every open PR whose `commits[]` includes the firing release's SCE. New comment per fire — never edited in place. Independent from the PR-level check-run aggregation; you can use one, the other, or both.

Configure it as an action on a component (or policy-wide):

1. Open the component. Click the tool icon → **Actions** → plus-circle.
2. **Type**: choose **PR Comment**.
3. **Choose Validation Integration**: same `GITHUB_VALIDATE` integration you used for `EXTERNAL_VALIDATION`.
4. **Optional Output JSON** (`clientPayload`): a JSON object with a `body` string (markdown). When set, ReARM **appends** it to the auto-generated body — contrast with `EXTERNAL_VALIDATION`, where `clientPayload` *replaces* the default output.
5. **Dynamic output (CEL)** (`celClientPayload`): optional CEL expression. The result is appended to the auto-generated body just like `clientPayload`. Useful when you want live release values in the comment (e.g. critical vuln count, license posture).
6. Click **Save**.

::: warning Pull Requests permission must be Read and write
Posting comments uses the issues/comments endpoint, which requires the GitHub App's **Pull requests** permission to be **Read and write** (not just Read). If your App was registered before `PR_COMMENT` existed, regenerate it or update the permission and re-install on the affected repositories.
:::

### INVALIDATE_PR and VALIDATE_PR — feeding the aggregator

Both are **internal signals** with no direct SCM call. They tell the PR aggregator how the firing release should contribute to the PR-level verdict:

- `VALIDATE_PR` — fires when a release transitions to a successful validation state (e.g. approval granted). Asks the aggregator to fold this release's outcome into any open PR whose commits include its SCE.
- `INVALIDATE_PR` — the failure-side companion. Fires on disapproval / rejection input events. Always contributes `FAILURE` to PR aggregation regardless of the release's lifecycle.

The actual GitHub check-run is posted by the matching PR-aggregated `EXTERNAL_VALIDATION` trigger — that is, the [Global PR Validation rule](#external_validation--global-pr-validation-rule-recommended) (or a [per-VCS override](#external_validation--per-vcs-override-advanced) when one is configured) — when the aggregated verdict reaches a terminal state.

Configuration is the same as any other action (component-level or policy-wide). They take no `clientPayload` — they're pure signals. You typically wire:

- `VALIDATE_PR` to your "approval granted" or "lifecycle = GENERAL_AVAILABILITY" rule.
- `INVALIDATE_PR` to your "disapproved" or "rejected" rule.

If you stay on the per-release `EXTERNAL_VALIDATION` path only, you can ignore `VALIDATE_PR` / `INVALIDATE_PR` entirely — they exist purely to drive the PR-aggregated path.

## Inbound webhook - real-time PR state sync

Everything above pushes *outbound* signals (check-runs, PR comments) from ReARM to GitHub. The inbound webhook closes the loop the other way: GitHub notifies ReARM when a PR is opened, edited, closed, or merged, and ReARM updates the corresponding [first-class PR entity](../workflows/pull-requests) without waiting for a CI run.

Why bother:

- This is the only way how GitHub can communicate PR`MERGED` status to ReARM.
- PR updates in ReARM with Webhook happen almost instantly, while without it you have to wait for the next CI run
- Sets `mergedDate` / `closedDate` from the canonical GitHub timestamps rather than CI's wall clock.

::: warning ReARM Pro only
The inbound webhook receiver is part of ReARM Pro. On ReARM CE, drive PR state via the CLI `pullrequest upsert` path described in [Pull Requests › Standalone PR upsert](../workflows/pull-requests#standalone-pr-upsert-no-release).
:::

### 1. Create the Webhook in ReARM

1. In ReARM, open the **Integrations** tab on your Org Settings (the same place the `GITHUB_VALIDATE` integration was registered).
2. Find the integration you wired up earlier and open its detail panel — webhooks are **one-per-integration** (1:1).
3. Click **Add Webhook**. Fill in:
   - **Slug** — a short lowercase identifier, 4–63 ASCII characters, no leading/trailing hyphen. It becomes part of the public webhook URL: `https://<your-rearm>/api/programmatic/v1/webhook/<orgUuid>/<slug>`. Reserved names are rejected. Keep it stable — changing the slug invalidates the GitHub-side webhook URL until you re-register.
   - **Secret** — a strong random string. ReARM stores it encrypted at rest and uses it as the HMAC-SHA256 key when verifying inbound deliveries.
   - **Installation ID** — the GitHub App's installation ID for this repo / org. Same value as in step 4 of the GitHub part above.
   - **Note** *(optional)* — free-form, surfaces in the UI for ops.
4. Save. ReARM displays the public webhook URL and confirms the secret is set.

### 2. Configure the webhook on the GitHub App side

1. On the GitHub App's settings page, scroll to **Webhook**:
   - Check **Active**.
   - **Webhook URL** — paste the URL from the ReARM Webhook detail panel.
   - **Webhook secret** — paste the same secret you set in ReARM (don't reuse one from another integration).
2. Save the App.
3. On the App's **Permissions & events** page, under **Subscribe to events**, enable **Pull request** (the only event ReARM currently consumes; other events such as push / delete / check_run / etc. are recorded as `UNKNOWN_EVENT` and discarded).

### 3. Verify delivery

Open or close a test PR. In ReARM, the Webhook detail panel shows:

| Field | Meaning |
|---|---|
| **lastDeliveryAt** | Wall-clock of the most recent inbound delivery. |
| **lastDeliveryStatus** | One of `SUCCESS` / `SIGNATURE_INVALID` / `HANDLER_ERROR` / `UNKNOWN_EVENT` / `DUPLICATE` / `WEBHOOK_DISABLED`. |
| **lastDeliveryId** | GitHub's `X-GitHub-Delivery` UUID — copy-paste-able into the App's delivery log. |
| **consecutiveFailureCount** | Resets to 0 on the next `SUCCESS`; non-zero means something's wrong (signature mismatch, malformed payload, etc.). |

A successful first delivery on a PR opens the corresponding `PullRequest` row in ReARM (or updates the existing one) and you should see it on the **Pull Requests** view immediately.

### Troubleshooting the webhook

- **`SIGNATURE_INVALID`** — the secret in ReARM doesn't match the one on the GitHub App. Rotate via the Webhook detail panel's **Rotate secret** action and paste the new value into the App.
- **`UNKNOWN_EVENT`** — GitHub delivered an event ReARM doesn't yet handle (e.g. `push`, `check_run`). Recorded for visibility; no PR mutation. Subscribe only to `Pull request` events on the App side to keep the deliveries table clean.
- **`HANDLER_ERROR`** — server-side exception during processing. ReARM still acks GitHub so the App doesn't retry forever; check `lastDeliveryId` against the backend logs for the stack.
- **`DUPLICATE`** — GitHub retried a delivery ReARM already processed. Dedupe is by `X-GitHub-Delivery`; no-op.
- **`WEBHOOK_DISABLED`** — the Webhook row's status is `DISABLED`. Re-enable it from the detail panel.
- **Delivery never arrives** — check the App's "Recent Deliveries" tab on GitHub. Common causes: webhook URL points at an unreachable host, ReARM ingress missing the `/api/programmatic/v1/webhook/...` path (it's a public route, no auth header required — auth is via the HMAC signature), or the App isn't installed on the repo whose PR you tested.

## Wire up GitHub branch protection

Posting a check-run on its own does not block a merge — you have to tell GitHub the check is required.

1. In your GitHub repository go to **Settings** → **Branches** (or **Settings** → **Rules** if your org uses Rulesets).
2. Add a branch protection rule (or ruleset) for `main` (or whichever branch you want gated).
3. Enable **Require status checks to pass before merging**.
4. In the search box, find and select the ReARM check-run name. For the PR-aggregated path the default is `rearm/pr/<identity>` (from the Global PR Validation rule); for the per-release path it's `rearm/<componentName>`. **Override the Check Name to a stable string** (e.g. `rearm/pr-validation`) on the rule that drives this branch — branch protection requires an exact match, and a stable name avoids breakage when components or repos churn.

   ::: tip Check name must have run once first
   GitHub only autocompletes status check names that have already appeared on at least one commit. Open a throwaway PR first so the check posts once, then come back here and add it as required.
   :::
5. (Optional) Pin the required check to a specific GitHub App in the dropdown — useful if there's any chance another tool posts a check with the same name.
6. Save.

From now on, GitHub will refuse to merge any PR whose head SHA does not have a passing ReARM check.

## CI side: feeding ReARM the PR head SHA

For GitHub branch protection to enforce ReARM's check, ReARM must post the check-run on the **PR head SHA**, not on the synthetic `pull/N/merge` commit GitHub creates. ReARM relies on the commit you pass with `addrelease --commit` (or `getversion --commit`) to know which SHA to post against.

The official [`relizaio/rearm-actions`](https://github.com/relizaio/rearm-actions) handles this for you on `pull_request` events:

- The commit it sends to ReARM is the PR head SHA on `pull_request` events, falling back to `github.sha` on push events. Conceptually:

  ```yaml
  COMMIT: ${{ github.event.pull_request.head.sha || github.sha }}
  ```

- The source branch it sends is `github.head_ref` on `pull_request` events, falling back to `github.ref` on push events, so the release lands on the PR's source branch instead of the synthetic `pull/N/merge` ref.
- It forwards `--pr-identity`, `--pr-state`, `--pr-title`, `--pr-source-branch-name`, `--pr-target-branch-name`, and `--pr-endpoint` to `addrelease` / `getversion`, which causes ReARM to upsert the [first-class Pull Request entity](../workflows/pull-requests) and link the build's commit to it.

If you build your own workflow with the bare ReARM CLI, mirror those rules — the check-run will land on the wrong SHA otherwise and branch protection will treat it as missing.

For pipelines that need to record a PR independently of a release (e.g. on `pull_request: opened` before any build runs, or to record `MERGED` on `pull_request: closed`), use the standalone `rearm-cli pullrequest upsert` command. See [Pull Requests › Standalone PR upsert](../workflows/pull-requests#standalone-pr-upsert-no-release).

## Closing the loop on PR close / merge

When a PR closes or merges, three paths converge:

- The **[inbound GitHub webhook](#inbound-webhook--real-time-pr-state-sync)** delivers the `pull_request: closed` event to ReARM directly. Recommended when configured — sets `mergedDate` / `closedDate` from GitHub's canonical timestamps and doesn't require a CI run on closure.
- Your CI (or `rearm-cli pullrequest upsert`) sends `--pr-state CLOSED` or `--pr-state MERGED`, transitioning the PR entity in real time. Same end state as the webhook path; useful when you don't want to wire up the webhook or you're on ReARM CE.
- The `syncbranches` job conservatively marks any open PR `CLOSED` when its upstream source branch is deleted. It does not infer `MERGED` — to record a merge, either the webhook or the CI workflow must send the explicit signal.

All three are idempotent and converge on the same row (keyed by `(target VCS, identity)`); pick whichever fits your CI shape. If you've wired the inbound webhook, the CI-side `pull_request: closed` job becomes optional.

## Customising the check-run output

The check-run that ReARM posts to GitHub has an `output` block with three fields — `title`, `summary`, and `text` (markdown). The trigger form exposes two ways to control it: **Optional Output JSON** (`clientPayload`) and **Dynamic output** (`celClientPayload`).

### Precedence

```
celClientPayload  (CEL expression evaluated against the release / PR)
    │ result string overwrites clientPayload for this dispatch
    ▼
clientPayload     (static JSON: {"title": ..., "summary": ..., "text": ...})
    │ if non-empty AND parses as JSON, replaces the entire default output
    ▼
default output    (computed server-side at fire time)
```

Either field, when set, **replaces the entire default output** for `EXTERNAL_VALIDATION` — there is no field-level merge. To customise just one field, copy the full default JSON into the form (use the **Use template** button next to each field) and edit from there.

For `PR_COMMENT` the semantics are different — `clientPayload.body` (or the CEL result) is **appended** to the auto-generated comment body, not used as a replacement.

### Default output

When both `clientPayload` and `celClientPayload` are empty, ReARM posts an `output` block roughly shaped like this — the `text` body is a markdown summary of the release's pre-aggregated metrics, so the developer reading the blocked PR sees what's wrong without leaving GitHub:

```json
{
  "title": "ReARM verdict: failure",
  "summary": "Release version: 1.2.3.",
  "text": "### Vulnerabilities (47)\n\n| Severity | Count |\n|---|---|\n| Critical | 3 |\n| High | 9 |\n| Medium | 27 |\n| Low | 6 |\n| Unassigned | 2 |\n\n### Policy Violations (5)\n\n| Type | Count |\n|---|---|\n| Security | 4 |\n| License | 1 |\n| Operational | 0 |\n\n_Last scanned: 2026-04-30T17:14:09Z_"
}
```

The empty-state path (release not yet scanned) returns a single line so the absence of a table doesn't read as "all clear":

```
_No release metrics available — release has not been scanned yet._
```

### Sample `clientPayload` (static)

A minimal static override. Useful when every fired check-run should carry the same custom message — e.g. linking to your own internal runbook:

```json
{
  "title": "ReARM verdict: blocked",
  "summary": "This release does not meet the org-wide policy gate.",
  "text": "Open the [release page](https://your-rearm-instance.example.com/release/show/<release-uuid>) for full details, or see the [internal runbook](https://wiki.example.com/runbooks/rearm-blocked-pr).\n\n_To override per release, switch to the Dynamic output (CEL) field._"
}
```

Notes:
- The string has to be valid JSON; the form accepts a multi-line textarea but the value is parsed as a single JSON document.
- `text` accepts GitHub-flavored markdown — tables, links, code fences, lists.

### Sample `celClientPayload` (CEL expression)

CEL expression evaluated server-side at fire time. The result must be a string that itself parses as JSON with `title` / `summary` / `text` keys (for `EXTERNAL_VALIDATION`) or a string with a `body` field (for `PR_COMMENT`). Useful when you want live release values in the output:

```cel
'{"title":"ReARM verdict: ' + release.lifecycle + '","summary":"Release ' + release.version + ' — ' + string(release.criticalVulns) + ' critical, ' + string(release.highVulns) + ' high","text":"Edit this CEL to customise per-release output. See bindings table below."}'
```

#### CEL bindings available on `release`

| Binding | Type | Description |
|---|---|---|
| `release.version` | string | Release version |
| `release.lifecycle` | string | `DRAFT` / `GENERAL_AVAILABILITY` / `END_OF_*` etc. |
| `release.component` | string | Component UUID |
| `release.branchType` | string | Branch type (`MAIN`, `FEATURE`, etc.) |
| `release.firstScanned` | bool | True if the release has been scanned at least once |
| `release.criticalVulns` | int | Count of CRITICAL-severity vulnerabilities |
| `release.highVulns` | int | Count of HIGH-severity vulnerabilities |
| `release.mediumVulns` | int | Count of MEDIUM-severity vulnerabilities |
| `release.lowVulns` | int | Count of LOW-severity vulnerabilities |
| `release.unassignedVulns` | int | Count of UNASSIGNED-severity vulnerabilities |
| `release.securityViolations` | int | Count of policy violations of type SECURITY |
| `release.licenseViolations` | int | Count of policy violations of type LICENSE |
| `release.operationalViolations` | int | Count of policy violations of type OPERATIONAL |
| `release.approvals[uuid]` | string | `APPROVED` / `DISAPPROVED` / `UNSET` per approval entry |

CEL integers are 64-bit, so use `string(release.criticalVulns)` to interpolate counts into a JSON string.

::: tip Use template buttons
The trigger form has a **Use template** button next to each field that pre-fills a starting template you can edit. Clicking it overwrites whatever is currently in the field — clear the field first if you want to start fresh, or copy your work elsewhere before refilling.
:::

## Troubleshooting

- **Check posts but doesn't block merge.** Branch protection rule isn't requiring it. See "Wire up GitHub branch protection" above; especially the "must have run once first" note.
- **Check lands on the wrong SHA.** Your CI is sending `github.sha` (the merge commit) instead of `github.event.pull_request.head.sha`. Either upgrade `rearm-actions` to a recent version, or fix your inline workflow.
- **Release lands on `pull/N/merge` instead of your feature branch.** Same root cause — your CI is using `github.ref` instead of `github.head_ref`.
- **PR-level check-run conclusion stays PENDING and never dispatches.** PR aggregation is `PENDING` while at least one per-component-latest release has not been validated. See [PR aggregation verdicts](../workflows/pull-requests#aggregation-and-verdicts).
- **`PR_COMMENT` returns 403 from GitHub.** The App's **Pull requests** permission is `Read` only. Update it to `Read and write` and re-install on the affected repositories.
- **Token errors on the ReARM side ("could not obtain installation token").** Most often the App is not installed on the repository, or the Installation ID in the trigger config is wrong. Double-check the Installation ID in the GitHub URL after install.
- **`GITHUB_VALIDATE` integration shows up in the regular "External Integration" picker.** Upgrade to the UI release that filters integration pickers by type — `INTEGRATION_TRIGGER` excludes `GITHUB_VALIDATE`; `EXTERNAL_VALIDATION` and `PR_COMMENT` only list `GITHUB_VALIDATE`.

## See also

- [Pull Requests](../workflows/pull-requests) — first-class PR entity, attribution, and aggregation rules (CE + Pro).
