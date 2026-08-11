---
sidebarDepth: 2
---

# Self-Registering Components from CI

::: tip Available in both ReARM Community Edition and ReARM Pro
Self-registration itself works in **both editions** — identity by VCS URI and repository path, creation on first build, the CLI flags and action inputs below.

**Perspectives are ReARM Pro only.** On Pro you can confine a registering key to one perspective, so it can create components there and nowhere else. On CE, grant the FREEFORM key organization-scoped permission and skip the `--perspective` flag; everything else is identical.
:::

## Description

The usual order is to create a [Component](/concepts/#component) in the ReARM UI, mint its API key, and then point CI at it. Self-registration inverts that: CI describes what it is building, and ReARM creates the Component on the first build if it does not exist yet.

This is worth setting up when creating components by hand becomes the bottleneck — a [monorepo](./monorepos) where every new directory would otherwise need a console visit, an onboarding wave where dozens of repositories arrive at once, or ephemeral demo and sandbox organizations that are rebuilt from scratch regularly.

## Identity: VCS URI plus repository path

A self-registering build does not know a Component UUID, so it identifies itself the same way a monorepo component does — by the pair *(VCS repository URI, repository path)*:

- If the pair resolves to an existing Component, the build proceeds against it. Nothing is created.
- If it does not resolve and the build asked for creation, ReARM registers the VCS repository (when the URI is new to the organization) and creates a Component bound to that path.

Resolution happens on every build, so self-registration is naturally idempotent: only the first build of a given path creates anything, and there is no "already exists" error to handle.

For a single-project repository the repository path is simply `.`; in a monorepo each component passes its own subdirectory.

## Permissions

Component-scoped API keys cannot self-register, since such a key exists only *because* its Component already does. Creation needs a **FREEFORM key with permissions wide enough to cover the new Component**, which in practice means one of:

| Intended blast radius | Permission on the FREEFORM key | How the component is filed | Editions |
|---|---|---|---|
| Anywhere in the organization | `RESOURCE` / `WRITE` at `ORGANIZATION` scope | Organization root | CE and Pro |
| One perspective | `RESOURCE` / `WRITE` at `PERSPECTIVE` scope | Assigned to that perspective | Pro only |

Mint FREEFORM keys from **Org Settings → API Keys**, then attach the permission from the permissions panel. The plaintext secret is shown only once, at creation.

On ReARM Pro, perspective-scoped keys are the better default whenever the organization is subdivided: the key can register new components inside its own perspective and nowhere else, which keeps an automated registration path from becoming an organization-wide write capability. Only real perspectives can receive new components — product-derived perspectives are rejected. On ReARM Community Edition perspectives are not available, so an organization-scoped key is the only option; weigh that when deciding whether to leave a self-registering key in CI permanently or mint it for an onboarding push and remove it afterwards.

Once a Component exists, day-to-day builds do not need the registering key at all. A per-Component key (or a FREEFORM key whose permissions cover that Component) is enough, and is the better thing to leave sitting in CI long term.

## Registering from GitHub Actions

`create_component` on the [initialize action](https://github.com/relizaio/rearm-actions) turns it on, with the version schemas the new Component should adopt:

```yaml
      - uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
        with:
          fetch-depth: 0
          persist-credentials: false
      - uses: relizaio/rearm-actions/setup-cli@dac3e7752598c46746d9b98e0a4bed76535be9b3 # v1.7.0
      - id: init
        uses: relizaio/rearm-actions/initialize@dac3e7752598c46746d9b98e0a4bed76535be9b3 # v1.7.0
        with:
          rearm_api_id: ${{ secrets.REARM_FREEFORM_API_ID }}
          rearm_api_key: ${{ secrets.REARM_FREEFORM_API_KEY }}
          rearm_api_url: https://demo.rearmhq.com
          repo_path: backend
          create_component: 'true'
          create_component_version_schema: semver
          create_component_branch_version_schema: semver
```

The VCS URI is taken from the repository the workflow runs in, so only the path needs stating. The action registers components at organization scope; to file new components under a specific perspective, drive the CLI directly as shown below.

## Registering from the CLI

`getversion` and `addrelease` both accept the creation flags, so any CI system can self-register:

```bash
rearm getversion \
  -i "$REARM_API_ID" -k "$REARM_API_KEY" -u "$REARM_API_URL" \
  --branch "$BRANCH" --commit "$COMMIT" \
  --createcomponent \
  --createcomponent-name "backend" \
  --createcomponent-version-schema semver \
  --createcomponent-branch-version-schema semver \
  --perspective "$PERSPECTIVE_UUID"
```

`--perspective` is honoured only when the Component is actually being created; it is ignored when the pair already resolves.

## Naming

Unless you pass a name, ReARM derives one from the coordinates: the last segment of the VCS URI, plus the last segment of the repository path when there is one.

| VCS URI | Repo path | Component name |
|---|---|---|
| `https://github.com/myorg/myrepo` | `.` | `myrepo` |
| `https://github.com/myorg/myrepo` | `ui` | `myrepo-ui` |
| `https://github.com/myorg/myrepo` | `services/backend` | `myrepo-backend` |

The shared prefix this produces for monorepo components is convenient later: a [dependency pattern](./bundling#dependency-patterns) such as `^myrepo-.*` picks up every component from the repository, including ones registered after the Feature Set was configured.

Override with `--createcomponent-name` when you want something else. Renaming afterwards in the UI is safe — identity is the VCS URI and path, not the display name.

## When to create components by hand instead

Self-registration gives a new Component only what CI can describe: name, version schemas, VCS binding, and optionally a perspective. Anything else — custom approval policies and actions, component-level API keys, team and contact metadata, a specific naming convention — still has to be configured afterwards.

If new components need that configuration before their first release is meaningful, create them in the UI and let CI resolve them by path instead.
