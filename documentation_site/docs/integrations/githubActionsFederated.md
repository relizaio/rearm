---
sidebarDepth: 2
---

# Federated (Keyless) Login from GitHub Actions

A GitHub Actions job can authenticate to ReARM with the **identity token GitHub issues to the job
itself**, instead of a ReARM API key stored as a repository secret. There is no ReARM secret
anywhere in the repository or the workflow: nothing to rotate and nothing to leak.

This page takes you from nothing to a working keyless pipeline: the trust rule in ReARM, the
workflow settings on the GitHub side, sample pipelines for the ReARM actions and for the plain CLI,
and the common failures. For the wider picture of programmatic access (API keys, access tokens,
CLI browser login) see [Programmatic Access](../configure/programmatic-access).

::: tip Available in both ReARM Community Edition and ReARM Pro
Federated identity is part of the shared ReARM codebase and works on both editions. The only
differences are in the optional extra permissions of a trust rule, see
[Edition differences](#edition-differences).
:::

## How it works

1. The job asks GitHub for an OIDC identity token, with the **audience set to your ReARM URL**.
   GitHub hands this out only to jobs that have `permissions: id-token: write`.
2. The ReARM CLI posts that token to the ReARM token endpoint
   (`/api/programmatic/token`, OAuth 2.0 JWT bearer grant).
3. ReARM verifies the token (signature against GitHub's published keys, issuer, expiry, audience
   equal to the ReARM instance's own base URI, single use) and looks for a **trust rule** in an
   organization that matches the identity: owner, repository, ref, environment, workflow, event.
4. If a rule matches, ReARM answers with the usual **one-hour access token**, and the CLI uses it
   for its calls. The CLI renews it by itself when it runs out, with a fresh identity token.

What the job may do is decided by the trust rule, not by anything in the workflow.

## Requirements

- ReARM **26.10.52** or later, with its base URI configured (the audience of the identity token
  must equal it).
- ReARM CLI **26.10.2** or later. This is the default CLI that `relizaio/rearm-actions/setup-cli`
  installs as of rearm-actions **1.9.0**, and the version every sample on this page gets.
- An organization administrator in ReARM, to create the trust rule.

## Part 1: ReARM configuration

### Where the trust rules live

Trust rules are managed in **Organization Settings -> Programmatic Access -> Federated
Identities**. The Programmatic Access tab is only shown to organization administrators, and only
organization administrators can list, create, edit, disable or delete trust rules.

The Federated Identities tab has two tables:

- **Trust Rules**: the rules themselves. Click the **Add Trust Rule** (plus) icon under the table to
  create one.
- **Federated Identities**: one row per repository that has authenticated through a rule (see
  [Identities, pinning and revocation](#identities-pinning-and-revocation)).

### Create a trust rule

The **New trust rule** form has three parts.

**General**

| Field | Purpose |
|---|---|
| **Name** | Required. A label for the rule, e.g. `myorg GitHub Actions`. |
| **Provider** | `GitHub Actions`, the only provider today. Cannot be changed after creation. |
| **Issuer** | Leave empty for github.com (the default issuer is `https://token.actions.githubusercontent.com`). Only for GitHub Enterprise Server: `https://HOST/_services/token`. Must be https on a public host name. Cannot be changed after creation. |

**Who is trusted**

| Field | Matches the token claim | Notes |
|---|---|---|
| **Owner on the provider** | `repository_owner` | Required. Your GitHub organization or user, e.g. `myorg`. Case-insensitive. |
| **Repositories** | `repository` | Globs over the repository name (`myrepo`) or `owner/name` (`myorg/myrepo`). Empty means every repository of the owner. |
| **Excluded repositories** | `repository` | Carved back out of the repositories above. |
| **Refs** | `ref` | `main`, `release/*`, `refs/tags/v*`. A bare pattern matches both the branch and the tag of that name (`refs/heads/...` and `refs/tags/...`); a pattern starting with `refs/` is matched as written. |
| **Environments** | `environment` | GitHub deployment environment names, exact (case-insensitive). |
| **Workflow files** | `workflow_ref` | A workflow file name (`release.yml`), its path, or a glob over the full `workflow_ref`. |
| **Events** | `event_name` | `push`, `workflow_dispatch`, `release` and so on, exact (case-insensitive). |
| **Subject globs** | `sub` | Advanced: globs over the raw subject claim, e.g. `repo:myorg/*:environment:prod`. |

Every list that is left empty constrains nothing; every list that is filled must match. Within one
list, any entry matching is enough. Globs are case-insensitive, `*` is any run of characters and
`?` is one character.

::: warning `*` spans separators
In repository and subject globs `*` also matches `/` and `:`. Write inclusions no broader than you
mean, and write exclusions as precisely as the inclusions.
:::

::: tip Refs on pull request runs
The `ref` claim is the ref the workflow ran on. On `pull_request` events GitHub sets it to the pull
request merge ref (`refs/pull/<number>/merge`), not the source branch, so a rule limited to
`main` does not admit pull request builds. Add `refs/pull/*` to **Refs** if those builds should
authenticate too.
:::

**What it may do**

A rule grants either a **template** evaluated against the calling repository, or one fixed key.

- **Scope by repository (template)**, the usual choice:

  | Field | Options | Meaning |
  |---|---|---|
  | **On the calling repository's components, branches and releases** | `NONE`, `READ_ONLY`, `READ_WRITE` (default `READ_WRITE`) | The level on every component of the organization whose VCS repository is the calling repository (`github.com/owner/name`, compared case-insensitively). In a monorepo this is every component built from that repository. |
  | **Organization-wide read (optional)** | `NONE`, `ESSENTIAL_READ`, `READ_ONLY` (default `NONE`) | Read access to everything else in the organization: releases of other components, products, anything the repository does not own. `ESSENTIAL_READ` is minimal read access to core organization data, `READ_ONLY` is read access to every object of the organization (see [permission types](../configure/user-and-user-group-permissions)). Leave it at `NONE` for an ordinary build: `getversion`, `addrelease` and the other build calls authorize against the component they resolve, which the repository level already covers. |
  | **Creation** | checkbox (default on) | **May create components (and the VCS repository record) bound to the calling repository.** Only available when the repository level is `READ_WRITE`. See [Component creation](#component-creation). |
  | **Functions on those permissions** | permission functions | Added to the repository level, the organization-wide level and nothing else. `RESOURCE` is always included. The build flow on this page needs no other function. |
  | **Extra static permissions (optional)** | rows of Scope, Object, Type, Functions | Fixed grants on objects the repository cannot express by itself. **Scope** is `Component / Product`, `Perspective` or `Instance / Cluster`; **Type** is `READ_ONLY` or `READ_WRITE`. Use it, for example, to let the workflow read a product or a component of another repository. |

  Each job gets only what its own repository maps to, computed at every call: nothing is stored on
  the identity, so a component bound to the repository later is covered from then on.

- **Act as a Free Form key**: the job takes the permissions and the attribution of one Free Form
  key of the organization, exactly. When several rules match an identity, key-bound rules take
  precedence over templates, and they must all bind the same key.

**Expires** is optional. After that time the rule stops matching, and tokens minted through the
rule end at that time at the latest.

Several matching template rules of one organization add up. Saving an edit to a rule invalidates
the access tokens minted through it at once, and the next exchange mints new ones; disabling a rule
invalidates them as well.

### Component creation

The ReARM actions take a `create_component` input (CLI: `getversion --createcomponent`) that creates
the component on the first run when it does not exist yet. With federated login:

- **Template rule**: the rule must have **On the calling repository's components, branches and
  releases** set to `READ_WRITE` and **Creation** checked. The identity can then create components
  **only bound to its own repository**: the VCS URI of the new component must be the calling
  repository. The organization-wide level plays no part in this, so it can stay `NONE`.
- **Key-bound rule**: creation follows the Free Form key's own permissions, which means an
  organization-wide `READ_WRITE` level on that key, as for any other key.
- **Into a perspective** (`perspective` input, CLI `--perspective`, ReARM Pro): the identity needs
  `READ_WRITE` on that perspective, which a template rule grants through an **Extra static
  permissions** row with Scope `Perspective`.

### A rule for a single repository

For the sample pipelines below, a rule like this is all that is needed:

| Field | Value |
|---|---|
| Name | `myorg/myrepo builds` |
| Provider | GitHub Actions |
| Owner on the provider | `myorg` |
| Repositories | `myrepo` |
| Refs | empty (every branch and tag), or e.g. `main` and `release/*` |
| What it may do | Scope by repository (template) |
| On the calling repository's components, branches and releases | `READ_WRITE` |
| Organization-wide read | `NONE` |
| Creation | checked if the workflow uses `create_component`, otherwise unchecked |

### Edition differences

The trust rule form is the same on both editions, with these exceptions:

- The **Instance / Cluster** scope of the extra static permissions is ReARM Pro only; it is hidden on
  ReARM Community Edition.
- **Perspectives** are a ReARM Pro feature, so a `Perspective` extra permission, and creating a
  component into a perspective, only apply on ReARM Pro.

## Part 2: GitHub configuration

1. **Grant the job `id-token: write`.** Set it on the job that talks to ReARM, not on the whole
   workflow, and give every job only what it uses. A common pattern is `permissions: {}` at the top
   of the workflow and an explicit `permissions` block per job:

   ```yaml
   permissions: {}

   jobs:
     build:
       permissions:
         contents: read
         id-token: write
   ```

2. **Select the federated mode.** With the ReARM actions set `rearm_auth: github-oidc`; with the CLI
   pass `--auth github-oidc` or set `REARM_AUTH=github-oidc`. The CLI also selects this mode by
   itself in a job that has `id-token: write` and no API key, but setting it explicitly makes a
   missing permission fail with a clear message.
3. **Leave the key out.** Do not set `rearm_api_id` or `rearm_api_key` (CLI: `-i`/`-k`,
   `REARM_APIKEYID`/`REARM_APIKEY`). There is nothing to store in the repository secrets for ReARM.
4. **Point at the right URL.** `rearm_api_url` (CLI `-u`) is also the audience of the identity
   token, so it must be the base URI the ReARM instance is configured with (a trailing slash makes
   no difference).
5. **Name the organization only if needed.** Set `rearm_org` (CLI `--org` or `REARM_ORG`) to the
   ReARM organization uuid **only when several organizations trust the same repository**. Otherwise
   leave it out: the matching rule decides the organization.
6. **Use CLI 26.10.2 or later.** `relizaio/rearm-actions/setup-cli` 1.9.0 installs 26.10.2 by
   default, and the rearm-docker-action and rearm-helm-action releases below use it.

## Part 3: Sample pipelines

The samples pin every action to a full commit sha with the version as a comment, as the ReARM
action repositories themselves do. Replace `https://rearm.example.com` with your ReARM URL.

| Action | Version | Commit |
|---|---|---|
| relizaio/rearm-actions | 1.9.0 | `441a3590e8df05dd0836703602995b04ad2701b1` |
| relizaio/rearm-docker-action | 1.15.0 | `b249b27746693af41622b20f74c1124bdcaf7d07` |
| relizaio/rearm-helm-action | 1.12.0 | `03de39109d0e138c77c92d4a24683d2325390ff6` |

These are the first releases of rearm-docker-action and rearm-helm-action with the `rearm_auth`
input; older releases only accept an API key.

### a. Container image with rearm-docker-action

[rearm-docker-action](https://github.com/relizaio/rearm-docker-action) builds and pushes the image
and records the release in ReARM. Its federated inputs are `rearm_auth` and `rearm_org`. The
registry credentials are still secrets: they are for the image registry, not for ReARM.

```yaml
name: Build Docker Image And Record Release In ReARM

on: [push]

permissions: {}

jobs:
  build:
    name: Build And Push Docker Image With Metadata
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write
    steps:
      - name: Build, push and record in ReARM
        uses: relizaio/rearm-docker-action@b249b27746693af41622b20f74c1124bdcaf7d07 # v1.15.0
        with:
          rearm_auth: github-oidc
          rearm_api_url: https://rearm.example.com
          # rearm_org: <organization uuid>   only when several organizations trust this repository
          registry_username: ${{ secrets.DOCKER_LOGIN }}
          registry_password: ${{ secrets.DOCKER_TOKEN }}
          registry_host: registry.example.com
          image_namespace: registry.example.com/myteam
          image_name: myapp
          enable_sbom: 'true'
          create_component: 'true'   # first run only; needs Creation in the trust rule
```

To also record the release on a **ReARM Mirror**, the mirror takes the same settings through
`rearm_mirror_auth` and `rearm_mirror_org`. The identity token is then requested for the mirror's
URL, so the mirror instance needs its own trust rule for the repository:

```yaml
          push_to_rearm_mirror: 'true'
          rearm_mirror_api_url: https://mirror.example.com
          rearm_mirror_auth: github-oidc
          # rearm_mirror_org: <organization uuid on the mirror>   only when several organizations there trust this repository
```

### b. Helm chart with rearm-helm-action

[rearm-helm-action](https://github.com/relizaio/rearm-helm-action) versions the chart, publishes it
and records the release. It commits the bumped `version:` in `Chart.yaml` and pushes that commit
back to the repository, so the job needs **`contents: write`** besides `id-token: write`, and branch
protection must allow the workflow token to push.

```yaml
name: Publish Helm Chart And Record Release In ReARM

on:
  push:
    branches: [ main ]

permissions: {}

jobs:
  helm:
    name: Publish Helm Chart
    runs-on: ubuntu-latest
    permissions:
      contents: write   # pushes the bumped Chart.yaml
      id-token: write
    steps:
      - name: Version, publish and record in ReARM
        uses: relizaio/rearm-helm-action@03de39109d0e138c77c92d4a24683d2325390ff6 # v1.12.0
        with:
          rearm_auth: github-oidc
          rearm_api_url: https://rearm.example.com
          registry_username: ${{ secrets.HELM_REGISTRY_LOGIN }}
          registry_password: ${{ secrets.HELM_REGISTRY_TOKEN }}
          registry_host: registry.example.com
          helm_chart_name: mychart
          path: charts/mychart
          enable_sbom: 'true'
```

The mirror inputs `rearm_mirror_auth` and `rearm_mirror_org` work as in the docker action.

### c. A library with no deliverable, with the rearm-actions building blocks

When a component ships no deliverable, for example a Go library that is consumed as source, use
the individual [rearm-actions](https://github.com/relizaio/rearm-actions) directly:
`setup-cli`, `initialize`, `sbom-sign-scan` and `finalize`. The release then records the version,
the source code entry, the source SBOM and the scan results. This sample follows the workflow that
[rearm-client-go](https://github.com/relizaio/rearm-client-go/blob/main/.github/workflows/rearm-submit.yaml)
uses to record its own releases.

```yaml
name: Record Release In ReARM

on: [push]

# Nothing at the workflow level: each job asks for exactly what it uses.
permissions: {}

concurrency:
  group: rearm-release-${{ github.ref }}
  cancel-in-progress: false

env:
  REARM_API_URL: https://rearm.example.com

jobs:
  test:
    name: Test
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - name: Check out
        uses: actions/checkout@8e8c483db84b4bee98b60c0593521ed34d9990e8 # v6.0.1
        with:
          persist-credentials: false

      - name: Set up Go
        uses: actions/setup-go@b7ad1dad31e06c5925ef5d2fc7ad053ef454303e # v7.0.0
        with:
          go-version-file: go.mod

      - name: Test
        run: go test ./...

  record-release:
    name: Record Release
    needs: test
    runs-on: ubuntu-latest
    # id-token: write is what github-oidc needs; there is no ReARM secret anywhere in this workflow.
    # CodeQL runs with upload:false and its SARIF is attached to the ReARM release rather than
    # GitHub code scanning, so security-events is not requested.
    permissions:
      contents: read
      id-token: write
      # actions: read   # uncomment if this repository is private (CodeQL needs it there)
    steps:
      - name: Check out
        uses: actions/checkout@8e8c483db84b4bee98b60c0593521ed34d9990e8 # v6.0.1
        with:
          fetch-depth: 0   # initialize walks history to build the commit list
          persist-credentials: false

      # cdxgen resolves Go modules and CodeQL builds the code, so both need Go here.
      - name: Set up Go
        uses: actions/setup-go@b7ad1dad31e06c5925ef5d2fc7ad053ef454303e # v7.0.0
        with:
          go-version-file: go.mod

      # Must precede initialize, which runs the rearm binary but does not install it.
      # setup-cli 1.9.0 installs CLI 26.10.2 by default, which supports github-oidc.
      - name: Set up ReARM CLI
        uses: relizaio/rearm-actions/setup-cli@441a3590e8df05dd0836703602995b04ad2701b1 # v1.9.0

      - name: Resolve version from ReARM
        id: rearm
        uses: relizaio/rearm-actions/initialize@441a3590e8df05dd0836703602995b04ad2701b1 # v1.9.0
        with:
          rearm_api_url: ${{ env.REARM_API_URL }}
          rearm_auth: github-oidc
          create_component: 'true'   # creates on the first run only; needs Creation in the trust rule

      # deliverable_type FILE (not the CONTAINER default) skips the registry login, the image
      # pull, the container SBOM and the purl lookup. The Go source SBOM and the CodeQL SARIF
      # both land in scearts, attached to the source code entry.
      - name: SBOM and CodeQL
        id: sbom
        if: steps.rearm.outputs.do_build == 'true'
        uses: relizaio/rearm-actions/sbom-sign-scan@441a3590e8df05dd0836703602995b04ad2701b1 # v1.9.0
        with:
          deliverable_type: FILE
          rearm_short_version: ${{ steps.rearm.outputs.short_version }}
          rearm_full_version: ${{ steps.rearm.outputs.full_version }}
          enable_sbom: 'true'
          source_code_sbom_type: go
          enable_codeql: 'true'
          codeql_language: go

      - name: Record release
        if: steps.rearm.outputs.do_build == 'true'
        uses: relizaio/rearm-actions/finalize@441a3590e8df05dd0836703602995b04ad2701b1 # v1.9.0
        with:
          rearm_api_url: ${{ env.REARM_API_URL }}
          rearm_auth: github-oidc
          rearm_full_version: ${{ steps.rearm.outputs.full_version }}
          rearm_short_version: ${{ steps.rearm.outputs.short_version }}
          rearm_build_lifecycle: ASSEMBLED
          # No image_full_name: this component ships no deliverable, so the release points to its
          # version, source code entry, SBOM and scan results.
          scearts: ${{ steps.sbom.outputs.scearts }}
          commit_list_file: ${{ steps.rearm.outputs.commit_list_file }}
          sce_commit: ${{ steps.rearm.outputs.sce_commit }}
          sce_commit_message: ${{ steps.rearm.outputs.sce_commit_message }}
          sce_commit_date: ${{ steps.rearm.outputs.sce_commit_date }}
```

Only the `record-release` job holds `id-token: write`; the `test` job, which runs the code under
test, cannot obtain a ReARM token at all.

### d. Plain CLI in a custom pipeline

Any ReARM CLI command accepts `--auth github-oidc`. Install the CLI with `setup-cli` (or any other
way, as long as it is 26.10.2 or later) and pass the ReARM URL with `-u`:

```yaml
name: Custom ReARM Steps

on: [push]

permissions: {}

jobs:
  rearm:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write
    env:
      REARM_URI: https://rearm.example.com
    steps:
      - name: Set up ReARM CLI
        uses: relizaio/rearm-actions/setup-cli@441a3590e8df05dd0836703602995b04ad2701b1 # v1.9.0

      - name: Show which identity the job acts as
        run: rearm whoami --auth github-oidc -u "$REARM_URI"

      - name: Get the next version
        id: version
        run: |
          out=$(rearm getversion --auth github-oidc -u "$REARM_URI" \
            --vcsuri "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY" \
            -b "$GITHUB_REF_NAME" --commit "$GITHUB_SHA")
          echo "$out"
          echo "version=$(echo "$out" | jq -r '.version')" >> "$GITHUB_OUTPUT"

      # ... build and publish ...

      - name: Record the release
        env:
          VERSION: ${{ steps.version.outputs.version }}
        run: |
          rearm addrelease --auth github-oidc -u "$REARM_URI" \
            --vcsuri "$GITHUB_SERVER_URL/$GITHUB_REPOSITORY" \
            -b "$GITHUB_REF_NAME" -v "$VERSION" \
            --commit "$GITHUB_SHA" --lifecycle ASSEMBLED
```

Instead of the flag, `REARM_AUTH: github-oidc` in the environment does the same; `--org` (or
`REARM_ORG`) names the organization when several trust the repository.

`rearm whoami --auth github-oidc` performs one exchange and prints the key id, the organization and
the repository the job acts as, or the reason the exchange was refused. It is a quick first step
when setting up a new rule.

## Identities, pinning and revocation

The first successful exchange through a template rule records a row for the repository in the
**Federated Identities** table. It holds no secret and no permissions; it shows the repository,
its VCS URI, the pinned ids, the last run and when it was last used.

- **Pinning.** On first use a rule pins the numeric id of the GitHub owner, and the identity row
  pins the numeric ids of the repository and the owner. A repository that was renamed or recreated
  is refused until an administrator **resets the pin** (the refresh icon in the pinned id column).
- **Deactivating** an identity row refuses that repository alone, until an administrator activates
  it again. **Deleting** a row only forgets its history: the next run re-creates it as long as a
  rule still trusts the repository.
- **To cut access off**, disable or delete the trust rule. Tokens minted through it stop working at
  once.

## Troubleshooting

When an exchange is refused, the CLI prints the reason from ReARM in this form:

```
Error: identity token refused (<code>: <reason>); check the trust rule in ReARM and, if several organizations trust this repository, pass --org
```

The common cases:

**The job lacks `id-token: write`**

GitHub does not give the job an identity token, and the CLI stops before contacting ReARM:

```
Error: Post "https://rearm.example.com/api/programmatic/graphql": rearm: assertion: not running in GitHub Actions with id-token: write (ACTIONS_ID_TOKEN_REQUEST_URL/TOKEN are unset)
```

Add `id-token: write` to the `permissions` of the job that runs the ReARM step. If a workflow-level
`permissions` block exists and the job has none, the job gets the workflow's permissions; if the
job has its own block, only that block counts. Without an explicit `rearm_auth: github-oidc` (or
`--auth github-oidc`) the CLI does not pick the federated mode in such a job and falls back to an
API key, which is not there:

```
Error: rearm: API key id and secret are required
```

That message says nothing about the identity token, which is why setting the mode explicitly is
recommended.

**No matching trust rule**

```
identity token refused (invalid_grant: no trust rule matches this identity)
```

ReARM gives this one answer for every "not trusted here" outcome. Check that a rule is active and
not expired, and that its owner, repositories, refs, environments, workflow files, events and
subject globs all admit this run (note the [pull request ref](#create-a-trust-rule)). The same
answer comes when `rearm_org` / `--org` names an organization whose rules do not match.

**Several organizations trust the repository**

```
identity token refused (invalid_request: this identity is trusted by several organizations; pass client_id=<organization uuid>)
```

Set `rearm_org` (CLI `--org`, or `REARM_ORG`) to the uuid of the organization the workflow should
act in. On the docker and helm actions, the mirror uses `rearm_mirror_org`.

**Wrong ReARM URL (audience)**

```
identity token refused (invalid_grant: audience must be https://rearm.example.com)
```

The identity token is requested for the URL given in `rearm_api_url` / `-u`, and ReARM accepts only
its own configured base URI. Use exactly that URL, not an alias host name.

**An old CLI without `--auth github-oidc`**

A CLI older than 26.10.2 rejects the flag:

```
Error: unknown flag: --auth
```

Through the actions, where the mode is passed as the `REARM_AUTH` environment variable, an old CLI
ignores it and sends the calls with the empty API key, so ReARM refuses them as unauthenticated,
with no mention of the identity token.
Use `setup-cli` from rearm-actions 1.9.0 or later without a `version` override, or override it with
26.10.2 or later.

**Component creation not allowed**

When `create_component` (CLI `--createcomponent`) runs for a component that does not exist yet and
the identity may not create it, the call is refused with `Not authorized`. The ReARM server log
records the detailed reason: the identity may only create components bound to its own repository,
and only when a trust rule allows it. Check that the rule is a template with
**On the calling repository's components, branches and releases** at `READ_WRITE` and **Creation**
checked, and that the component's VCS URI is the calling repository. For a key-bound rule the Free
Form key needs organization-wide `READ_WRITE`.

**Other refusals**

| Reason in the refusal | What to do |
|---|---|
| `the owner behind this identity changed since it was pinned; an administrator must reset the pin on rule <name>` | The GitHub owner was recreated or renamed. Reset the pinned owner id on the rule. |
| `the repository behind this identity changed since it was pinned; an administrator must reset the pin` | The repository was recreated or renamed. Reset the pin on its identity row. |
| `this repository's identity is disabled in the organization` | An administrator deactivated the identity row; activate it again. |
| `several key-bound rules match this identity and bind different keys` | Narrow the key-bound rules so that one key applies. |
