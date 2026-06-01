# DevOps: Instances, Secrets & Continuous Delivery

::: warning ReARM Pro only
The DevOps surface — Instances, Secrets, feature-set deploys, and the `rearm devops` CLI commands — is part of **ReARM Pro**. It is not present in ReARM Community Edition (the implementation lives in the Pro-only packages, so the corresponding GraphQL fields and CLI commands simply aren't there on CE).
:::

Beyond tracking what you *built*, ReARM Pro tracks what you have *deployed* and can drive deployments. The pieces:

- **[Instances](../concepts/#instance)** — a record of a running deployment target (a VM, a Kubernetes namespace, a PaaS, …), identified by URI. ReARM tracks, per namespace on the instance, which [Feature Set](../concepts/#feature-set) and releases are *targeted* (the plan) and which are *actually running* (the actual state, reported by the in-cluster agent).
- **[Clusters](../concepts/#cluster)** — a set of instances managed together (e.g. a Kubernetes cluster with one instance per namespace).
- **Secrets** — values ReARM stores and resolves per instance/namespace so a deployment can be configured without those values living in the repo. On Kubernetes, secret material is delivered through [Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets).
- **`rearm-cd`** — the in-cluster reconciler that connects an instance to ReARM (see below).

## ReARM CD

[`rearm-cd`](https://github.com/relizaio/rearm-cd) is a small agent you install **inside your Kubernetes cluster**. It connects the cluster to ReARM so that deployments to the instance can be controlled *from* ReARM: `rearm-cd` watches the instance's target plan in ReARM and reconciles the running workloads to match it, in a GitOps/continuous-delivery style. It uses [Bitnami Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets) for secret material and is installed via its Helm chart with a ReARM API key (`REARM_APIKEYID` / `REARM_APIKEY` / `REARM_URI`). RBAC can be cluster-wide or scoped to specific namespaces.

In short: you change the *target* in ReARM, `rearm-cd` makes the cluster match it.

## Deploying with feature sets

A deploy in ReARM Pro is expressed by pointing an instance at a [Feature Set](../concepts/#feature-set) — a versioned bundle that pins each component (and product) release that should ship together. Two CLI commands drive this (`rearm devops …`):

| Command | What it does |
| --- | --- |
| `rearm devops listfeaturesets --instanceuri <uri> --namespace <ns>` | Discovery: the product, the feature set currently deployed, and the feature sets you could switch to. Record the current feature set as your rollback target. |
| `rearm devops versionfeatureset --product <uuid> --overrides '[{"vcsUri":"…","repoPath":"…","branch":"…"}]'` | Create a new feature set that re-pins the listed component branches to specific releases — "assemble the exact set of versions I want to ship together." |
| `rearm devops switchfeatureset --instanceuri <uri> --product <uuid> --featureset <uuid> --namespace <ns>` | Point the running instance's product at that feature set. `rearm-cd` then reconciles the cluster to it. |

::: tip
`rearm devops` commands change what a running instance serves and are visible to anyone using that instance — switching a shared or production instance to the wrong build is a real incident. Always run `listfeaturesets` first, record the current feature set as the rollback target, and confirm you are naming the intended instance by URI.
:::

## Letting an AI agent deploy

The DevOps surface composes with the [agentic workflow](./agentic). An [Agent](../concepts/#agent)'s API key normally carries only the `AGENT` permission function — enough to run sessions, attribute commits, and read releases, but not to change a deployment. Grant that key the **`DEVOPS` permission function scoped to a single instance** (or its parent cluster) and the agent can also run `versionfeatureset` / `switchfeatureset` against that instance.

That lets an agent close the loop end to end — build and attribute a release, then promote and deploy it by versioning a feature set and switching its instance onto it — with `rearm-cd` reconciling the cluster and every step recorded in the agent's session audit trail. Grant it deliberately and scope it narrowly (one instance, never org-wide): it is the difference between an agent that *proposes* changes and one that *ships* them.

## Reference

- [`rearm-cd`](https://github.com/relizaio/rearm-cd) — the in-cluster reconciler (Helm-installed).
- [Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets) — secret delivery prerequisite for `rearm-cd`.
- [Agentic workflow](./agentic) — granting an agent DevOps permission to deploy.
- Concepts: [Instance](../concepts/#instance), [Cluster](../concepts/#cluster), [Feature Set](../concepts/#feature-set).
