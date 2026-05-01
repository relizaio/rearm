# GitHub Pull Request Validation (GitHub Validate)

::: warning ReARM Pro only
This functionality is part of ReARM Pro and is not available in ReARM Community Edition.
:::

::: info Private repositories
Posting check-runs against **private** repositories requires a GitHub Enterprise subscription on the customer side, since branch protection on private repos is gated behind GitHub's Enterprise tier.
:::

GitHub Validate lets ReARM block a Pull Request from being merged until ReARM has verified the release that the PR's head commit produced. ReARM posts a [GitHub check-run](https://docs.github.com/en/rest/checks/runs) on the PR's head SHA, and a branch-protection rule on the target branch makes that check required for merge.

The end-to-end flow is:

1. CI builds the PR head commit and calls ReARM (`getversion` / `addrelease`) with the PR head SHA, the source branch (`head_ref`), and the new `--pr-*` flags so ReARM tracks the PR and updates `pullRequestData` on the source branch.
2. The component (or its policy) has an **External Validation** output event configured. When the release transitions through the configured lifecycle / approval state, ReARM mints an installation token from your GitHub App and posts a check-run with the configured conclusion (e.g. `success`, `failure`, `neutral`).
3. The branch-protection rule on `main` (or your target branch) lists the ReARM check-run name as a required status check, so the PR cannot be merged until the check posts a passing conclusion.

## GitHub Part

### 1. Register a dedicated GitHub App

You need a GitHub App distinct from any "Trigger Workflows" app you may already have, because the permission sets are different.

Follow the upstream guide for [registering a GitHub App](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app#registering-a-github-app). Defaults are fine, except:

- **Webhook**: uncheck **Active** (ReARM is CI-driven, no inbound webhook).
- **Repository permissions**:
  - **Checks** → **Read and write** (required to post the check-run).
  - **Pull requests** → **Read** (required so the App can resolve PR head SHA when needed).
  - **Metadata** → **Read** (mandatory for any App that touches a repo).

Choose whether to allow installation only on your account or on any account based on your needs.

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
2. Click **Add CI Integration**.
3. **Description**: anything memorable, e.g. `GitHub Validate (acme-org)`.
4. **CI Type**: choose **GitHub Validate**.
5. **GitHub Private Key**:
   - Toggle **Upload .pem** and select the `.pem` file from step 3 above, **or**
   - Toggle **Paste** and paste the contents of the `.pem` file directly.
6. **GitHub Application ID**: paste the App ID from step 2.
7. Click **Save**.

The integration is now stored, with the private key encrypted at rest.

### 2. Make sure the VCS repository is registered

In ReARM, register the GitHub repository whose PRs you want to gate (either via Component creation, or via the **VCS** menu item and the plus-circle icon). The repository's `vcsuri` must contain `github.com/<org>/<repo>` — ReARM uses this to build the check-run URL.

### 3. Configure the External Validation output event (component-level)

External Validation can be configured per-component (described here) or at the policy-wide level (next section).

1. Open the component you want to gate. Click the tool icon to toggle component settings.
2. Open the **Output Events** tab. Click the plus-circle icon (Add Output Trigger).
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
9. **Optional Output JSON** (free-form `title` / `summary` / `text` for the check-run): can be left empty — ReARM provides sensible defaults. See [Customising the check-run output](#customising-the-check-run-output) below for samples.
10. **Dynamic output (CEL)**: optional CEL expression to compute the output JSON at fire time. Same section covers the available CEL bindings.
11. Click **Save**.

Repeat for each conclusion you want to drive — typically one trigger that posts `success` on approval and one that posts `failure` on rejection — and wire each into the appropriate input trigger / approval state.

### 3 (alternative). Configure as a policy-wide global event

If you want every component bound to a given Approval Policy to post check-runs the same way, define the External Validation event on the **policy** instead of on each component:

1. Open **Approval Policies** → select your policy.
2. Find **Policy-Wide Output Events** → click the plus-circle icon.
3. Fill in the same fields described above. (The global form does not expose **VCS Repository** — the repo is resolved from each component's own VCS at fire time.)

## Wire up GitHub branch protection

Posting a check-run on its own does not block a merge — you have to tell GitHub the check is required.

1. In your GitHub repository go to **Settings** → **Branches** (or **Settings** → **Rules** if your org uses Rulesets).
2. Add a branch protection rule (or ruleset) for `main` (or whichever branch you want gated).
3. Enable **Require status checks to pass before merging**.
4. In the search box, find and select the ReARM check-run name. By default ReARM names the check `rearm/<componentName>`.

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
- It forwards `--pr-number`, `--pr-state`, `--pr-title`, `--pr-target-branch`, and `--pr-endpoint` to `addrelease` / `getversion`, which causes ReARM to update `pullRequestData` on the source branch without an inbound SCM webhook.

If you build your own workflow with the bare ReARM CLI, mirror those rules — the check-run will land on the wrong SHA otherwise and branch protection will treat it as missing.

For reference, both rearm-actions and the CLI are released:

- `relizaio/rearm-actions` ≥ commit with [`fix(initialize): proper env var for branch resolution`](https://github.com/relizaio/rearm-actions/commit/6604005)
- `rearm-cli` ≥ `26.04.9` (adds the `--pr-*` flags).

## Closing the loop on PR close / merge

When a PR closes or merges, ReARM's `syncbranches` job archives the corresponding source branch (if it has been deleted upstream) and flips any open `pullRequestData` entries on that branch from `OPEN` to `CLOSED`. Re-runs of CI on a closed PR also explicitly set `--pr-state CLOSED` if the workflow detects a closed event.

No additional configuration is needed — just make sure your CI pipeline runs on `pull_request` close events if you want closure events to be recorded in real time (otherwise `syncbranches` catches up on its own schedule).

## Customising the check-run output

The check-run that ReARM posts to GitHub has an `output` block with three fields — `title`, `summary`, and `text` (markdown). The trigger form exposes two ways to control it: **Optional Output JSON** (`clientPayload`) and **Dynamic output** (`celClientPayload`).

### Precedence

```
celClientPayload  (CEL expression evaluated against the release)
    │ result string overwrites clientPayload for this dispatch
    ▼
clientPayload     (static JSON: {"title": ..., "summary": ..., "text": ...})
    │ if non-empty AND parses as JSON, replaces the entire default output
    ▼
default output    (computed server-side at fire time)
```

Either field, when set, **replaces the entire default output** — there is no field-level merge. If you set `clientPayload` to `{"text": "my note"}` you lose the default title and summary too. To customise just one field, copy the full default JSON into the form (use the **Use template** button next to each field) and edit from there.

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

CEL expression evaluated server-side at fire time. The result must be a string that itself parses as JSON with `title` / `summary` / `text` keys. Useful when you want live release values in the output:

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
- **Check lands on the wrong SHA.** Your CI is sending `github.sha` (the merge commit) instead of `github.event.pull_request.head.sha`. Either upgrade `rearm-actions` to the version above, or fix your inline workflow.
- **Release lands on `pull/N/merge` instead of your feature branch.** Same root cause — your CI is using `github.ref` instead of `github.head_ref`.
- **Token errors on the ReARM side ("could not obtain installation token").** Most often the App is not installed on the repository, or the Installation ID in the trigger config is wrong. Double-check the Installation ID in the GitHub URL after install.
- **`GITHUB_VALIDATE` integration shows up in the regular "External Integration" picker.** Upgrade to the UI release that filters integration pickers by type — INTEGRATION_TRIGGER excludes GITHUB_VALIDATE; EXTERNAL_VALIDATION only lists GITHUB_VALIDATE.
