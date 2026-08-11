This directory contains deployment tooling for ReARM: Docker Compose stacks, Helm charts for various ReARM components, and a Helm chart that wraps the upstream Dependency-Track 5 Helm chart.

## License

All files in this directory and its subdirectories are licensed under the MIT License (see [LICENSE](./LICENSE) in this directory), notwithstanding the GNU AGPL v3 that applies to the rest of this repository.

Third-party components distributed here keep their own licenses and are not relicensed by the above:

- [`helm/dtrack5-helm/charts/dependency-track-*.tgz`](./helm/dtrack5-helm/charts/) - the upstream [Dependency-Track Helm chart](https://github.com/DependencyTrack/helm-charts), Copyright (c) the Dependency-Track authors, licensed under Apache-2.0. A copy of that license ships with the chart as [`LICENSE-dependency-track`](./helm/dtrack5-helm/LICENSE-dependency-track).

`helm/rearm-helm/charts/postgresql-*.tgz` is maintained by Reliza in [relizaio/helm-charts](https://github.com/relizaio/helm-charts) and is itself MIT licensed, so it needs no carve-out.
