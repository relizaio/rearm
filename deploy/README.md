This directory contains deployment tooling for ReARM: Docker Compose stacks, Helm charts for various ReARM components, and a Helm chart that wraps the upstream Dependency-Track 5 Helm chart.

## License

All files in this directory and its subdirectories are licensed under the MIT License (see [LICENSE](./LICENSE) in this directory), notwithstanding the GNU AGPL v3 that applies to the rest of this repository.

Third-party components distributed here keep their own licenses and are not relicensed by the above:

- [`helm/dtrack5-helm/charts/dependency-track-*.tgz`](./helm/dtrack5-helm/charts/) - the upstream [Dependency-Track Helm chart](https://github.com/DependencyTrack/helm-charts), Copyright (c) the Dependency-Track authors, licensed under Apache-2.0. A copy of that license ships with the chart as [`LICENSE-dependency-track`](./helm/dtrack5-helm/LICENSE-dependency-track).

`helm/rearm-helm/charts/postgresql-*.tgz` is maintained by Reliza in [relizaio/helm-charts](https://github.com/relizaio/helm-charts) and is itself MIT licensed, so it needs no carve-out.

## October 2026 eMudhra CA compromise: pull the fixed images

The private keys of eight eMudhra intermediate CAs were compromised (https://emudhra.we-have-a-problem.com/). Browsers block the chains; the TLS clients inside the ReARM images check no revocation, so every image built before the fix accepts a certificate an attacker can mint for any host the services call (GitHub, Dependency-Track, OSV, identity providers, SMTP, webhooks, OCI registries). The fixed images are the tags below and every later tag; each carries the marker file `/trust-gate-ok` (`docker run --rm --entrypoint cat <image> /trust-gate-ok`; the OCI service image has no shell: `docker create` and `docker cp`), written only when the build's trust-store gate passed.

| image | first fixed tag |
|---|---|
| registry.relizahub.com/library/rearm-backend | pending the pin PR |
| registry.relizahub.com/library/rearm-ui | pending the pin PR |
| registry.relizahub.com/library/rearm-oci-artifact-service | pending the pin PR |
| registry.relizahub.com/library/rearm-keycloak | pending the pin PR |
| registry.relizahub.com/library/rebom-backend | pending the pin PR |
| registry.relizahub.com/library/rearm-documentation, rearm-landing | `:latest` built after the pin PR's commit date |

The Helm chart (`deploy/helm/rearm-helm/values.yaml`) and the compose file (`deploy/docker-compose/docker-compose.yml`) pin these tags from the pin commit on; an existing install takes them with `helm upgrade` or `docker compose pull && docker compose up -d`. The rearm CLI you run outside the images: install the release named in the same commit or later (older CLI releases keep working and, on Linux, are covered by your host's store once it is patched). The edge: the compose `tls` profile's Traefik v3.7.8 is not yet rebuilt on a fixed Go; it only talks to Let's Encrypt outbound, which fails safe (a forged ACME endpoint cannot obtain a certificate for your name); bump when a fixed Traefik exists. Your nodes' OS stores (kubelet image pulls, any curl on the host): Ubuntu `apt-get update && apt-get install -y --only-upgrade ca-certificates` (20260816ubuntu0.<release>.1); Debian the page's `sed` on /etc/ca-certificates.conf plus `update-ca-certificates`; Alpine `apk upgrade ca-certificates ca-certificates-bundle` (20261020-r0). Check with `curl https://dv-g3b-emudhra.we-have-a-problem.com/` and `curl https://dv-g3b-aaa-emudhra.we-have-a-problem.com/`: both must fail with a certificate error (curl exit 60); any HTTP answer means the store still trusts the compromised chain.
