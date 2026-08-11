# Dependency-Track 5 Helm Chart

ReARM relies on [Dependency-Track](https://dependencytrack.org) for SBOM analysis. **Any Dependency-Track instance works** - if you already run one, or use a managed one, simply point ReARM at it as described in [Dependency-Track integration](/integrations/dtrack).

This page covers a convenience chart we publish for teams who do not already have an instance and want one that fits ReARM without extra plumbing. It is a thin community wrapper around the official [Dependency-Track chart](https://github.com/DependencyTrack/helm-charts). The wrapper is [published](https://github.com/relizaio/rearm/tree/main/deploy/helm/dtrack5-helm) under MIT license inside ReARM Community Edition repository.

## Why use this chart

The upstream chart already covers the basics well, including serving Dependency-Track on a single hostname: its ingress routes `/api` to the API server and `/` to the frontend, so one host and one certificate is the norm either way. This wrapper differs in how it gets there, and in what it brings with it.

- **The backend is proxied through the frontend.** Rather than routing `/api` at the ingress, the frontend's nginx proxies it to the API server inside the cluster, so only one service is exposed. That is also where this chart configures gzip compression and security headers, and an optional HSTS toggle at the ingress. A side benefit is that the API server never needs to know where it is mounted.
- **An optional bundled PostgreSQL**, with a simple backup CronJob to S3 or Azure. The upstream chart has no database dependency at all - you point it at one you already run.
- **Optional Traefik `IngressRoute` support**, including a simple Let's Encrypt `certResolver` setup, or a plain route when TLS terminates at an upstream load balancer.
- **Database credentials and the key-encryption-key generated on first install** and preserved across upgrades, with `generated` / `plaintext` / `sealed` / `none` handling modes. Upstream expects both values to be supplied.

If none of that is useful to you, a stock Dependency-Track install is perfectly fine - ReARM does not care how it was deployed.

## Installation

Pre-requisites: a running Kubernetes cluster with Traefik as the ingress controller.

The chart is published as an OCI artifact and is publicly readable, so no registry login is needed:

```bash
helm install dtrack5 oci://registry.rearmhq.com/library/dtrack5 \
  --create-namespace -n dtrack5 \
  -f dtrack5-values.yaml
```

A minimal values file needs little more than the hostname:

```yaml
# Host the Dependency-Track UI is served on - this is also the API host,
# because the frontend proxies /api to the API server.
ingressHost: dtrack.example.com

# TLS terminated here by Traefik with a Let's Encrypt certificate.
# Use traefikBehindLb instead when TLS terminates at an upstream LB.
useTraefikLe: true
traefikBehindLb: false

postgres:
  enabled: true
  postgresStorage: 4G
```

### Secrets

The chart manages two secrets: the PostgreSQL credentials and the Dependency-Track key-encryption-key. One setting governs both:

| `create_secret_in_chart` | behaviour |
|---|---|
| `generated` (default) | values generated on first install and never rotated by later upgrades; they also survive `helm uninstall` and are reused on reinstall |
| `plaintext` | taken verbatim from `secrets.pgpassword` / `secrets.kek` |
| `sealed` | `SealedSecret` resources from kubeseal ciphertext; requires the sealed-secrets controller |
| `none` | the chart creates nothing - provision the Secrets yourself |

`generated` relies on Helm's `lookup`, which is a no-op under `helm template` and `--dry-run`. Rendering pipelines that never talk to the cluster (ArgoCD-style) would regenerate the values on every render, so use `sealed` or `none` there.

### Optional settings

```yaml
# Strict-Transport-Security, emitted by Traefik at the TLS edge.
# Off by default: a browser honours HSTS for the whole max-age once it
# has seen it, so enable it deliberately, per host.
hsts:
  enabled: true
  maxAge: 31536000
  includeSubdomains: true
  preload: false

# Nightly database backup to S3 or Azure. Requires a secret holding the
# bucket credentials - see the values file for the expected keys.
backups:
  enabled: true
  schedule: "0 1 * * *"
  storageType: s3
  secretName: rearm-backup
```

Every available setting is documented in the chart's [values file](https://github.com/relizaio/rearm/blob/main/deploy/helm/dtrack5-helm/values.yaml).

## Connecting it to ReARM

Once Dependency-Track is up, follow [Dependency-Track integration](/integrations/dtrack) to create the API key and configure the integration. The one simplification with this chart: the API Server URI and the Frontend URI are the same value - `https://` plus your `ingressHost`.
