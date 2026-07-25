---
sidebarDepth: 2
---

# Monorepos

## Description

A *monorepo* keeps several independently buildable projects in a single Git repository. It is a comfortable place to develop in and an awkward place to build from: a push touches one directory, but the naive CI pipeline rebuilds everything, and every component gets a new version it did not earn.

ReARM's answer is to stop treating the repository as the unit of release. Each buildable directory becomes its own [Component](/concepts/#component), bound to the pair *(VCS repository, repository path)*. Every Component keeps its own versions, branches, releases and metadata, and CI decides what to rebuild by asking ReARM a single question per component: **what commit was this component last released from, and has anything under its path changed since?**

Because the answer comes from ReARM rather than from the repository, nothing about this scheme needs tags, marker files or any other release bookkeeping committed into git.

## One repository, many Components

Point several Components at the same VCS URI and give each one a distinct repository path:

| Component | VCS URI | Repo path |
|---|---|---|
| `myorg-ui` | `https://github.com/myorg/myrepo` | `ui` |
| `myorg-backend` | `https://github.com/myorg/myrepo` | `backend` |
| `myorg-docs` | `https://github.com/myorg/myrepo` | `documentation_site` |

The repository path is a first-class field on the Component, so `(vcsUri, repoPath)` resolves to exactly one Component. That pair is what CI passes when it has no Component UUID at hand, and what [Feature Set](/concepts/#feature-set) overrides use to name a constituent of a Product.

Everything downstream follows from this: each Component versions on its own schedule, carries its own [Branches](/concepts/#branch), and produces its own [Releases](/concepts/#release) with their own SBOMs, deliverables and findings. A change to `ui` never bumps `backend`.

Authentication can go either way. Give each Component its own Component API key — the key then identifies the Component and CI passes nothing else — or use one organization-wide key and identify the target with `--component <uuid>` (CLI) / the `component` input (actions).

## How ReARM decides what to build

The decision is two steps and no state:

1. Ask ReARM for the component's latest release and read its [Source Code Entry](/concepts/#source-code-entry) — the commit that release was built from:

   ```bash
   rearm getlatestrelease \
     -i "$REARM_API_ID" -k "$REARM_API_KEY" -u "$REARM_API_URL" \
     --vcsuri "$VCS_URI" --repo-path "$REPO_PATH" --branch "$BRANCH"
   ```

2. Diff that commit against the commit being built, scoped to the component's path:

   ```bash
   git diff "$LAST_COMMIT..$COMMIT" -- "$REPO_PATH"
   ```

If the diff is non-empty, build. If it is empty, skip — the component is already released from equivalent source.

The important property is *what* the diff is anchored to. It is not the push range, and not the previous CI run; it is the commit that produced the component's last **release**. That makes the check self-healing: if a build fails, is cancelled, or the runner dies, no release is recorded, so the anchor does not move and the next push to the repository still sees the component as dirty and rebuilds it. A range-based filter would have considered the work done and moved on.

The same property makes re-runs cheap and idempotent — a replayed pipeline over an unchanged path simply decides not to build — and it keeps the scheme portable, since every CI system that can run `git diff` can implement it.

## GitHub Actions

The check is built into [rearm-actions](https://github.com/relizaio/rearm-actions), so in the common case you configure it rather than script it. In the opinionated [rearm-docker-action](https://github.com/relizaio/rearm-docker-action), the `path` input is the component's repository path, and the action performs the `getlatestrelease` + `git diff` check before doing any work:

```yaml
on:
  push:
    branches:
      - '*'
    tags-ignore:
      - '*'

name: Build components and submit metadata to ReARM

# Least privilege by default; jobs opt into more where they need it.
permissions:
  contents: read

jobs:
  build-ui:
    permissions:
      contents: read
      id-token: write     # keyless Sigstore signing only
    name: Build And Push UI
    runs-on: ubuntu-latest
    steps:
      - name: ReARM Build And Submit UI Release
        uses: relizaio/rearm-docker-action@975e8b3d96b53d56d9c21a34b927b6d7b2a52720 # v1.13.4
        with:
          registry_username: ${{ secrets.DOCKER_LOGIN }}
          registry_password: ${{ secrets.DOCKER_TOKEN }}
          registry_host: registry.relizahub.com
          image_namespace: registry.relizahub.com/library
          image_name: myorg-ui
          rearm_api_id: ${{ secrets.REARM_UI_API_ID }}
          rearm_api_key: ${{ secrets.REARM_UI_API_KEY }}
          rearm_api_url: https://demo.rearmhq.com
          path: ui
          enable_sbom: true
          source_code_sbom_type: 'npm'
  build-backend:
    permissions:
      contents: read
      id-token: write
    name: Build And Push Backend
    runs-on: ubuntu-latest
    steps:
      - name: ReARM Build And Submit Backend Release
        uses: relizaio/rearm-docker-action@975e8b3d96b53d56d9c21a34b927b6d7b2a52720 # v1.13.4
        with:
          registry_username: ${{ secrets.DOCKER_LOGIN }}
          registry_password: ${{ secrets.DOCKER_TOKEN }}
          registry_host: registry.relizahub.com
          image_namespace: registry.relizahub.com/library
          image_name: myorg-backend
          rearm_api_id: ${{ secrets.REARM_BACKEND_API_ID }}
          rearm_api_key: ${{ secrets.REARM_BACKEND_API_KEY }}
          rearm_api_url: https://demo.rearmhq.com
          path: backend
          enable_sbom: true
          source_code_sbom_type: 'other'
```

One job per component, one `path` per job, one pair of credentials per component. Add a component to the monorepo by adding a job. Every job starts, and the ones whose path is unchanged exit early without building or releasing.

For the anatomy of these actions and the non-Docker variants, see [GitHub Actions for ReARM](/integrations/githubActionsBuild); for a single-component walkthrough, see the [GitHub Actions and Docker tutorial](/tutorials/github-actions-docker).

### Composing the individual actions

When you build something other than a container image, wire the four actions together yourself and pass `repo_path` to `initialize`. It emits `do_build` (plus the resolved version and SCE data) for the rest of the job to gate on:

```yaml
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
        with:
          fetch-depth: 0
          persist-credentials: false
      - uses: relizaio/rearm-actions/setup-cli@dac3e7752598c46746d9b98e0a4bed76535be9b3 # v1.7.0
      - id: init
        uses: relizaio/rearm-actions/initialize@dac3e7752598c46746d9b98e0a4bed76535be9b3 # v1.7.0
        with:
          rearm_api_id: ${{ secrets.REARM_BACKEND_API_ID }}
          rearm_api_key: ${{ secrets.REARM_BACKEND_API_KEY }}
          rearm_api_url: https://demo.rearmhq.com
          repo_path: backend
      - name: Build
        if: steps.init.outputs.do_build == 'true'
        run: ./gradlew build            # your build, versioned by steps.init.outputs.full_version
      - uses: relizaio/rearm-actions/sbom-sign-scan@dac3e7752598c46746d9b98e0a4bed76535be9b3 # v1.7.0
        if: steps.init.outputs.do_build == 'true'
        with: { ... }
      - uses: relizaio/rearm-actions/finalize@dac3e7752598c46746d9b98e0a4bed76535be9b3 # v1.7.0
        if: steps.init.outputs.do_build == 'true'
        with:
          rearm_full_version: ${{ steps.init.outputs.full_version }}
          rearm_short_version: ${{ steps.init.outputs.short_version }}
          rearm_build_start: ${{ steps.init.outputs.build_start }}
          commit_list: ${{ steps.init.outputs.commit_list }}
          rearm_build_lifecycle: ASSEMBLED
          # ...
```

`initialize` needs full history to resolve the last released commit, hence `fetch-depth: 0`. On a shallow checkout the commit is not present locally, and the action deliberately fails safe by assuming a build is needed.

### Optionally skipping the runner entirely

The `do_build` check runs *inside* the job, so an unchanged component still spins up a runner to discover it has nothing to do. On a large monorepo you can add a cheap pre-gate that never starts those jobs, using [dorny/paths-filter](https://github.com/dorny/paths-filter):

```yaml
jobs:
  detect:
    name: Detect changed components
    runs-on: ubuntu-latest
    permissions:
      contents: read
    outputs:
      ui:      ${{ steps.f.outputs.ui }}
      backend: ${{ steps.f.outputs.backend }}
    steps:
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
        with:
          persist-credentials: false
      - id: f
        uses: dorny/paths-filter@fbd0ab8f3e69293af611ebaee6363fc25e6d187d # v4.0.1
        with:
          filters: |
            ui:
              - 'ui/**'
            backend:
              - 'backend/**'
  build-ui:
    needs: detect
    if: needs.detect.outputs.ui == 'true' || github.ref == 'refs/heads/main'
    # ... as above
```

Treat this as an optimization layered on top of `do_build`, not a replacement for it. The filter compares the *push range*, so it is blind to everything the release history knows: a component whose previous build failed looks unchanged on the next unrelated push, and its job never starts to find out otherwise. That is why the example keeps an escape hatch (`|| github.ref == 'refs/heads/main'`) that lets every component evaluate itself on the main branch, where `do_build` makes the final call.

### Hardening the workflow

The examples above follow a few practices that are worth keeping when you adapt them, and one of them is specific to monorepos.

**Pin every action to a full commit SHA.** A tag such as `@v1` or `@v6` is a mutable pointer — whoever controls the action's repository can move it, and a compromised upstream then executes with your registry credentials and ReARM API keys on the next run. Pinning to a 40-character SHA makes the code you run immutable; the trailing `# v1.7.0` comment keeps it readable. This applies to every action in the chain, including first-party ReARM actions and popular third-party ones like `paths-filter`.

Pinned SHAs do not update themselves, so pair them with [Dependabot](https://docs.github.com/en/code-security/dependabot/working-with-dependabot/keeping-your-actions-up-to-date-with-dependabot) or Renovate — both understand the SHA-plus-comment convention and will raise PRs that bump the pin and the comment together. In a monorepo this matters more than usual: one workflow file typically drives every component, so a single stale or hijacked action reaches all of them at once.

**Grant the smallest set of permissions.** Declare `permissions: contents: read` at the workflow level so every job starts read-only, then have individual jobs opt into exactly what they need. `id-token: write` is required only for keyless [Cosign and Sigstore](/integrations/githubActionsCosign) signing, and `contents: write` only for jobs that push back to the repository (a Helm chart release, for example). Without a workflow-level default, jobs inherit whatever the repository default is, which is frequently far broader than a build needs.

**Do not leave the token in the checkout.** `actions/checkout` writes the `GITHUB_TOKEN` into `.git/config` unless you pass `persist-credentials: false`. Any later step — a build script, a test, a transitive dependency's postinstall hook — can read it from there. Monorepo builds run more third-party tooling per job than single-project ones, so turn it off unless a later step genuinely needs to push.

**Use one ReARM API key per component.** Beyond keeping releases attributed correctly, this is the blast radius argument: a Component API key can only create releases for its own Component, so a key leaked from the UI job cannot forge backend releases. A single organization-wide key shared across every job in the monorepo turns one compromised job into write access for the whole organization.

**Be careful what runs on pull requests from forks.** If the repository is public, keep secret-bearing build jobs on `push` (as the examples do) and avoid `pull_request_target`, which runs workflow code from the base branch with full secret access while checking out the contributor's code. For validating incoming changes, use the dedicated [pull request validation](/integrations/githubValidate) flow instead.

## Forcing a rebuild

Sooner or later you will want to rebuild a component whose source has not changed — CI failed for a reason that had nothing to do with the code, a base image moved underneath you, a registry push flaked, or you simply want fresh SBOMs and signatures.

An empty commit will not do it. It changes no paths, so a `paths-filter` pre-gate never starts the job, and even if the job does start, the path diff is empty and `do_build` correctly says no. This is the mechanism working as designed: both checks answer "did this component's source change", and the answer is genuinely no.

The idiomatic way to say "yes, rebuild it anyway" is to make a change under the component's path that means exactly that, and nothing else. Keep a trivial marker file per component and bump it:

```bash
echo 2 > backend/trigger_build
git commit -am "chore(backend): force rebuild - registry push flaked"
```

```
myrepo/
├── ui/
│   ├── trigger_build          # contains an integer
│   └── ...
└── backend/
    ├── trigger_build
    └── ...
```

The file's content is irrelevant — an integer you increment is just the easiest thing to diff and to read in history. What matters is that it lives under the component's path, so it moves that component and only that component: bumping `backend/trigger_build` rebuilds the backend and leaves the UI alone. The commit message becomes the record of why, which beats an empty commit or a hand-triggered pipeline run for auditability later.

Reserve this for cases where there is no code change to make. A normal source edit already triggers the build on its own, and routinely bumping the marker defeats the point of the whole scheme.

## Versioning

Monorepo components version independently, exactly as if each lived in its own repository — see [Using ReARM as Version Manager](/tutorials/using-rearm-as-version-manager) for the general model. Each Component carries its own version schema and its own feature-branch schema, so `ui` can be on `semver` while `backend` is on `CalVer`, and neither advances when the other ships.

Branches behave the same way. A git branch that touches two components produces a branch under each of those Components in ReARM, and each one versions on its own; branches are synchronized automatically by `initialize` (via `rearm syncbranches`), so no manual bookkeeping is required when feature branches come and go.

Building manually or from an unsupported CI? `rearm getversion` takes the same coordinates:

```bash
rearm getversion \
  -i "$REARM_API_ID" -k "$REARM_API_KEY" -u "$REARM_API_URL" \
  --branch "$BRANCH" --commit "$COMMIT" --onlyversion
```

## Source Code Entries in a monorepo

A release's [Source Code Entry](/concepts/#source-code-entry) is the commit it was built from, recorded against a specific VCS repository. In a single-project repository that is simply the head commit. In a monorepo it should not be: the head commit frequently touches a different component entirely, and recording it would credit each component with somebody else's change.

So the commit taken as a component's Source Code Entry is the last one that touched *that component's path*, with its message, author and date carried along as one consistent set. Two components built from the same push therefore record different Source Code Entries, each naming the change that is genuinely theirs, and the commit lists attached to each release are path-filtered the same way. Merge commits are the deliberate exception: on a base-branch build whose head is a true merge, that merge commit is recorded as-is, because it is the commit that landed on the branch.

The practical payoff is that per-component history stays honest — [commit signature and attribution](./committers) verify against the right commit, and [supply chain forensics](./supply-chain-forensics) answers "what source produced this artifact" without the noise of unrelated directories.

## Bundling components back into a Product

Splitting a monorepo into Components raises the obvious question: what represents "the whole thing" that gets deployed? A [Product](/concepts/#product) does. Auto-integrate assembles independently built Component releases into a single Product release that pins the exact version of each part.

This composes especially well with monorepos, since the components you just split apart usually share a naming convention. A [dependency pattern](./bundling#dependency-patterns) such as `^myorg-.*` picks up every component in the repository — including ones you add later — without editing the Feature Set each time. From there the Product release is what you deploy and track through [instances and delivery](./devops).

## Other CI systems

Nothing in the approach is GitHub-specific. Any pipeline that can run the CLI and `git diff` can gate a build the same way — the pattern below is what the actions do internally, and it drops into [GitLab CI](/integrations/gitlab), [Jenkins](/integrations/jenkins) or [Azure DevOps](/integrations/ado):

```bash
#!/bin/bash
set -euo pipefail

REPO_PATH=backend
COMMIT=$(git rev-parse HEAD)

LAST_COMMIT=$(rearm getlatestrelease \
    -i "$REARM_API_ID" -k "$REARM_API_KEY" -u "$REARM_API_URL" \
    --vcsuri "$VCS_URI" --repo-path "$REPO_PATH" --branch "$BRANCH" \
  | jq -r '.sourceCodeEntryDetails.commit // empty')

do_build=true
if [ -n "$LAST_COMMIT" ] && git cat-file -t "$LAST_COMMIT" > /dev/null 2>&1; then
  if git diff --quiet "$LAST_COMMIT..$COMMIT" -- "$REPO_PATH"; then
    do_build=false
  fi
fi

if [ "$do_build" = "false" ]; then
  echo "No changes under $REPO_PATH since $LAST_COMMIT - skipping build"
  exit 0
fi

# ... build, then register the release with rearm addrelease
```

An empty `LAST_COMMIT` means the component has never been released, and a first build is correct. See [ReARM CLI](/integrations/rearmcli) for the full command surface.

## Pitfalls

**Shallow checkouts break the diff.** `actions/checkout` defaults to depth 1, so the last released commit is usually absent locally. Always check out with `fetch-depth: 0` when composing the actions yourself. The check fails safe rather than silently skipping — it assumes a build is needed and logs why — but you lose the savings the setup exists for.

**Empty commits do not trigger builds.** They change no paths, so neither the pre-gate nor `do_build` sees anything to do. Use a per-component marker file instead — see [Forcing a rebuild](#forcing-a-rebuild).

**The first build of a new component looks like an error, and isn't.** Before a Component has any release, `getlatestrelease` legitimately reports that the VCS repository or the component-for-repo-path is not found. That is treated as "no prior release" and turns into `do_build=true`. If you use `create_component: true`, the Component is created on that first run with its repository path attached.

**Deleting a directory does not retire its Component.** Removing `ui/` stops producing new releases, but the Component and its history stay in ReARM by design — historical releases, SBOMs and findings remain queryable. Archive the Component when you want it out of the active surface, and remove it from any Feature Set that still lists it as a required dependency.

**Keep repository paths stable.** The path is half of the identity ReARM resolves a Component by. Renaming `ui` to `frontend` makes the pair `(vcsUri, ui)` stop resolving, and CI behaves as if it were building a brand-new component. When a move is unavoidable, update the Component's repository path in ReARM in the same change.

**One key per component, or one key plus an explicit component.** A Component API key implies its Component; an organization-wide key does not. If you reuse a single key across monorepo jobs, pass `--component` / the `component` input in every job, or the releases will pile up on whichever Component the key happens to resolve to.
