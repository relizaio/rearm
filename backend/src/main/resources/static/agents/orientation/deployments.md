<!-- orientation section: deployments · core 2026-10-08 -->
## 10. Deployment operations (ReARM Pro only)

`rearm devops` commands change which build a running ReARM instance
serves. They are powerful and wrong-instance mistakes are
**visible to other people** — switching a shared sandbox or a
production instance to a feature-branch build is a real incident
that takes operator effort to recover from.

**Hard constraint: do not run any `rearm devops` command unless the
operator's task description explicitly names the target instance
(by URI or instance UUID).** "Test on the sandbox" is not specific
enough — ask "which sandbox URI / instance UUID?" before proceeding.
If the operator can't or won't name a specific instance, stop. Don't
guess. Don't pick "the only one I happen to know about" or "the one
my key seems to work against".

ReARM CE does not ship these commands. If `rearm devops
listfeaturesets` returns `Field 'listFeatureSetsOfInstance' in type
'Mutation' is undefined` (or similar), you are on CE and no devops
operation can proceed regardless of how the task is phrased — tell
the operator the edition mismatch.

### 10.1 Discovery — `listfeaturesets`

Always your first call. It confirms (a) you're talking to the
instance the operator named, (b) the FREEFORM key you were given can
actually see the deployment, and (c) the product + current feature
set match what the operator described:

```bash
rearm devops listfeaturesets \
  --instanceuri "<sandbox base URL the operator gave you>" \
  --namespace "<k8s namespace, typically 'rearm'>"
```

Returns, per product on the plan: the product UUID, the current
feature set, the plan entry's `integrateType` (`FOLLOW` or
`TARGET`), the release the plan currently aims at (`targetRelease`),
the release the instance actually reports as running
(`deployedRelease`, null until agent data has matched), and every
available feature set with its deployable releases
(`availableFeatureSets[].releases[]`: `uuid`, `version`,
`lifecycle`, `approvedForInstanceEnvironment`). **Record the current
`currentFeatureSet.uuid` and `targetRelease`** before doing anything
else — that's the rollback target if the operator needs to revert.

This is also how you answer "what version is deployed on this
instance": `deployedRelease.version` per product, no product read
access needed. The release fields need **rearm-cli 26.10.2 or
newer** (the version pinned in §1.2); a CLI older than that is below
`rearm_cli_min`, and the oldest ones show only feature-set names.

**Names are resolved here, by you.** Every write command takes
UUIDs only. When the operator says "switch to feature set X" or
"deploy version Y", find X under `availableFeatureSets[].name` and Y
under that feature set's `releases[].version` in this response and
use the matching `uuid`. Match exactly; if nothing matches, or more
than one entry could be meant (a typo, a partial name, two similar
feature sets), stop and ask the operator which one — do not pick the
closest candidate and do not pass the name through to the CLI hoping
it resolves.

### 10.2 Versioning — `versionfeatureset`

Creates a new feature set that pins one or more component branches.
The override branches must already exist on the components (i.e. CI
must have run on that branch at least once and produced a release):

```bash
rearm devops versionfeatureset \
  --product "<product-uuid from listfeaturesets>" \
  --overrides '[
    {"vcsUri":"<vcs uri>","repoPath":"<component sub-path>","branch":"<branch-name>"}
  ]'
```

Returns `{uuid, name, autoIntegrate, component}` for the new
feature set.

### 10.3 Switching — `switchfeatureset`

Retargets the named instance at the new feature set. The in-cluster
reconciler picks up the change within a minute or two and rolls
matching pods; the **pod image tag is the source of truth** for
what's actually running, not the GraphQL response:

```bash
rearm devops switchfeatureset \
  --instanceuri "<sandbox base URL>" \
  --product "<product-uuid>" \
  --featureset "<new feature-set uuid from §10.2>" \
  --namespace "<k8s namespace>"
```

Without further flags the plan entry keeps its integrate type and
follows the newest release of the new feature set that is approved
for the instance environment; you do not choose the version.

To deploy a **specific release** of the feature set, pass one of the
candidates `listfeaturesets` returned under
`availableFeatureSets[].releases[]` as `--release` (uuid or exact
version). The plan entry becomes `TARGET`, pinned to that release,
and stays there until changed. To go back to following the newest
approved release, pass `--follow`. The two flags are mutually
exclusive; both need **rearm-cli 26.10.2 or newer**, the version
pinned in §1.2. If you are stuck on an older CLI, report that to the
operator rather than guessing when the task needs a specific version.

```bash
# pin a release of the new feature set
rearm devops switchfeatureset \
  --instanceuri "<sandbox base URL>" \
  --product "<product-uuid>" \
  --featureset "<feature-set uuid>" \
  --release "<version or uuid from listfeaturesets>" \
  --namespace "<k8s namespace>"

# return to following the newest approved release
rearm devops switchfeatureset ... --featureset "<feature-set uuid>" --follow
```

A release that is not `ASSEMBLED` or later (DRAFT, PENDING,
REJECTED, CANCELLED) cannot be pinned; the server rejects it with the
list of valid candidates to consult. `approvedForInstanceEnvironment
= false` is allowed for a pin but means the release has not passed
the environment's approval gate — mention that to the operator
rather than pinning silently.

Re-run `listfeaturesets` to confirm `currentFeatureSet.uuid` (and
`targetRelease.version` when you pinned one) match. If after 10 min
the pod image tag hasn't changed, the reconcile is wedged — surface
to the operator with the timestamp of the switch and the instance
URI.

### 10.4 Pre-flight checklist

Before any `rearm devops` operation, you should be able to answer
all of these. If you can't tick all six, stop and ask:

- [ ] The operator's task explicitly named this instance (URI or UUID).
- [ ] `listfeaturesets` against that instance succeeded with the FREEFORM key the operator provided.
- [ ] The response's `product` and `currentFeatureSet` match the operator's description.
- [ ] You've recorded the previous `currentFeatureSet.uuid` so rollback is one command away.
- [ ] You understand the change rolls a running pod within minutes — there is no preview mode.
- [ ] You are on **ReARM Pro** — `listfeaturesets` succeeded at all (not a CE backend).
- [ ] If the task names a specific version: it appears under `availableFeatureSets[].releases[]` for the target feature set, and your CLI is 26.10.2 or newer (`--release` / `--follow`).

---

If you find a behavior the doc doesn't describe, the doc is wrong.
Surface the gap to the operator so it gets fixed in the next ReARM
release rather than guessing.
