---
title: "Load Testing ReARM: 20,000 SBOMs Per Run Across Four OCI Registries"
date: "2026-08-25"
---

The Dependency-Track 5.0 announcement mentions early adopters ingesting "upwards of 20,000 SBOMs per hour". That is a useful reference point for the scale teams are starting to operate at, and it made us want the equivalent numbers for ReARM - measured on our own stack, with the SBOM size, the hardware, and our definition of "ingested" all written down. So we built a harness, ran it, and are publishing the whole thing so anyone can re-run it.

This post summarizes what we did, what we measured across four different OCI registry backends, and what we changed as a result. The full methodology, JMeter plan, SQL probes and raw run records now live in the ReARM repository under `perf/sbom-ingest`.

### What we tested

- **Unit of work: a release carrying one SBOM.** A release is what ReARM models, sure enough we added an SBOM to each of them, but essentially we were testing releases + SBOMs rather than bare SBOMs. In ReARM, bare SBOMs simply do not exist, each BOM always belongs to something.. Each run creates 20,016 releases, each with a 50-component CycloneDX SBOM (~24 KB) synthesized from a pool of 2,867 real components extracted from a production ReARM build - real purls, unique content per release, no dedup shortcuts.
- **Two completion markers, reported separately.** *Accepted* means the API returned and the release is queryable. *Fully processed* means the SBOM's components are reconciled and queryable per release - our honest equivalent of "ingested". A number without this distinction is trivially inflatable, because upload APIs - ours included - return before analysis completes.
- **The full production path**: public ingress, GraphQL multipart upload, authentication, rate limiting (100 requests/30s per API key - runs used 5 keys), BOM storage in rebom, artifact push to an OCI registry, and asynchronous component reconciliation.

### Infrastructure we used

Everything ran on a single-node k3s sandbox: **8 vCPU, 30 GB RAM** (OVH, Montreal), with *everything* co-located on that one node - ReARM backend (4 CPU / 16 GiB limit), rebom-backend (4 replicas), Keycloak, two PostgreSQL instances, the OCI registry itself (except the Azure run), and the JMeter load generator. These are deliberately modest, worst-case numbers: no component had a machine to itself. Dedicated hardware would do better across the board.

### Results across four registries

Identical workload, identical stack, only the artifact registry swapped:

| Registry | Accept rate | 20k fully processed (end-to-end) | Notes |
|---|---|---|---|
| CNCF Distribution (in-cluster) | 15.0/s (test pacer cap) | **1h 21m** | flat across the whole run |
| zot, sharded repositories | 75 pushes/s in probes | ~1h 21m class | fastest measured |
| Azure Container Registry (Premium, remote) | 4.0/s flat | 3h 56m | bounded by our chain amplifying ~60ms network round trips - not by ACR, which scaled to 4x that in direct probes |
| Harbor (in-cluster) | 3.3/s flat | 6h 42m | middleware cost per push; insensitive to repository size |
| zot, single hot repository | 13/s decaying to ~2/s | 6h 18m | per-repository tag-count degradation |

Zero SBOMs were lost or failed processing in any run - roughly 100,000 releases ingested across the campaign with zero reconciliation failures. Every difference above is throughput, never correctness.

### What we learned about zot - and why it is still our bundled default

ReARM CE bundles [zot](https://zotregistry.dev) as its default artifact registry, configured the way this workload wants it (dedupe off, GC off, pinned minimal image). The bundled zot is sized for typical CE usage - **up to roughly 2,000 SBOMs per month** - and at that volume it is effectively instant (sub-100ms pushes).

The load test surfaced one scaling behavior worth knowing: zot's per-push cost grows with the number of tags in a single repository (near-linear; concurrency does not help). Total registry size is irrelevant - a fresh repository on a 90,000-object instance pushes at full speed. ReARM stores BOMs in monthly repository buckets, so this only matters if a single month accumulates tens of thousands of SBOMs. For deployments beyond bundled-zot scale we set up **Harbor on demand** (flat performance at any repository size, at a higher fixed cost per push), and finer repository sharding is on our roadmap for high-volume zot deployments.

### Azure Container Registry, and what is next

The ACR run is the interesting one for cloud deployments: ACR itself was never the limit (direct probes scaled linearly with no throttling), and the flat 4/s came from our pipeline amplifying network round trips. Two follow-ups are planned: testing **ACR from within a private VPC** where the network path is short, and reducing the round trips per artifact in our push path.

### Configuration this surfaced

The processing (reconciliation) throughput ceiling turned out to be a scheduler batch-size constant. It is now a configurable property (`SBOM_RECONCILE_DRAIN_BATCH`) - at its tuned setting the post-burst backlog drains at ~250 releases/minute instead of ~66, taking the 20k end-to-end time on an in-cluster registry from over 6 hours to 1h21m.

One scope note: the instance's Dependency-Track integration remained enabled throughout the campaign, but [BEAR](https://github.com/relizaio/bear) SBOM enrichment was not in play - these numbers measure ReARM's own ingestion and reconciliation pipeline.

### Reproduce it

The harness - component pool builder, JMeter plan, completion probes and every run record referenced above - is in the [ReARM repository](https://github.com/relizaio/rearm) under `perf/sbom-ingest`. The README states every ceiling, batch size and dedup layer that shapes the numbers, so if you get different results, you will be able to say exactly why.
