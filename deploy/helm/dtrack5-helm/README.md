# dtrack5

Helm chart that deploys [Dependency-Track](https://dependencytrack.org) 5 for use with [ReARM](https://github.com/relizaio/rearm).

ReARM works with any Dependency-Track instance - if you already run one, just point ReARM at it. This chart is for teams that do not, and want one that fits ReARM without extra plumbing.

## Relationship to upstream

This is a thin wrapper around the official [Dependency-Track Helm chart](https://github.com/DependencyTrack/helm-charts). That chart is pinned in `Chart.yaml`, vendored under `charts/`, and does all the real work of running the api-server and frontend; everything here sits around it.

The upstream chart is Copyright (c) the Dependency-Track authors and licensed under Apache-2.0 - a copy ships as [`LICENSE-dependency-track`](./LICENSE-dependency-track). The wrapper itself is MIT, see [`LICENSE`](./LICENSE).

## What the wrapper adds

The upstream chart already serves Dependency-Track on a single hostname - its ingress routes `/api` to the api-server and `/` to the frontend. This wrapper differs in how it gets there, and in what it brings with it.

- **The backend is proxied through the frontend.** Instead of routing `/api` at the ingress, the frontend's nginx proxies it to the api-server in-cluster, so only one service is exposed. That is also where gzip and security headers are configured, with an optional HSTS toggle at the ingress. It also means the api-server never has to know where it is mounted.
- **An optional bundled PostgreSQL**, with a simple backup CronJob to S3 or Azure. Upstream declares no database dependency - you bring your own.
- **Optional Traefik `IngressRoute` support**, with a simple Let's Encrypt `certResolver` configuration, or a plain route when TLS terminates at an upstream load balancer.
- **Generated credentials.** The database password and the Dependency-Track key-encryption-key are generated on first install and preserved across upgrades, with `generated` / `plaintext` / `sealed` / `none` handling modes. Upstream expects both to be supplied.

## Installing

```bash
helm install dtrack5 oci://registry.rearmhq.com/library/dtrack5 \
  --create-namespace -n dtrack5 \
  -f dtrack5-values.yaml
```

That installs the latest published version; add `--version` to pin one. The chart is public, so no registry login is needed. Full instructions, the settings worth knowing, and how to connect the result to ReARM are in the [documentation](https://docs.rearmhq.com/integrations/dtrackChart). Every value is documented inline in [`values.yaml`](./values.yaml).

## How it is built

The upstream chart is a pinned dependency, vendored into the repository:

```yaml
dependencies:
- name: dependency-track
  version: "2.0.0-rc.3"          # exact: pre-release versions are not matched by range constraints
  repository: "https://dependencytrack.github.io/helm-charts"
```

`helm dependency update` resolves that into `charts/dependency-track-<version>.tgz` and writes `Chart.lock`. Both the archive and the lock file are committed, so a build never depends on the upstream repository being reachable and the exact bytes are reviewable.

CI packages the directory and publishes it to `registry.rearmhq.com/library/dtrack5` on merges to `main`, with an SBOM and a public Sigstore signature. The chart version in `Chart.yaml` is bumped by CI, so do not hand-edit it.

### Bumping Dependency-Track

1. Set the new `version` under `dependencies` in `Chart.yaml`, and `appVersion` to the Dependency-Track release it ships.
2. Delete the old `charts/*.tgz` and run `helm dependency update .` to fetch the new one and refresh `Chart.lock`.
3. Update the two upstream image digests in `values.yaml`. They are pinned independently of the subchart, so they do **not** follow `appVersion` on their own - leaving them stale means the chart claims a version it does not run. Resolve them from the registry, for example:
   ```bash
   crane digest dependencytrack/apiserver:<version>
   crane digest dependencytrack/frontend:<version>
   ```
4. Check the diff between the old and new subchart before trusting it. Upstream releases have gone out that change nothing but `appVersion`, and ones that move templates; `helm template` output either way is the thing to compare.

### Notes for maintainers

- Images are expressed as `<version>@sha256:...` in the `tag` field. The subchart builds the reference itself and only emits `repo@digest` when the tag *starts with* `sha256:`, so this form is what keeps the version readable while pulling by digest.
- The frontend nginx configuration lives in `mounted_files/nginx-conf` and is mounted as a ConfigMap. The nginx entrypoint runs envsubst over it once at container start, so editing it does not take effect on `helm upgrade` alone - roll the frontend afterwards. See the comment in `templates/nginx-conf-cm.yaml`.
- Any resource this chart creates for the first time during an `upgrade` rather than an `install` - the HSTS middleware, when you switch `hsts.enabled` on - ends up client-side owned. The first later change to one of its values then fails with a conflict against helm itself, and keeps failing; one `helm upgrade --force-conflicts` clears it permanently. Fresh installs are unaffected.
