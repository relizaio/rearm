---
title: "Load Testing ReARM: 20,000 SBOMs Per Run Across Four OCI Registries"
date: "2026-09-02"
---

The Dependency-Track 5.0 announcement mentions early adopters ingesting "upwards of 20,000 SBOMs per hour". That is a useful marker for the scale teams are starting to operate at, and it made us want the equivalent numbers for ReARM: measured on our own stack, with the SBOM size, the hardware, and our definition of "ingested" all written down. So we built a harness, ran it, and are publishing the whole thing so anyone can re-run it.

This post covers what we tested, what we measured across four OCI registry backends, and what we changed as a result. The full methodology, JMeter plan, SQL probes and raw run records live in the ReARM repository under [`perf/sbom-ingest`](https://github.com/relizaio/rearm/tree/main/perf/sbom-ingest).

## What we tested

**The unit of work is a release carrying one SBOM.** In ReARM a bare SBOM does not exist: every BOM belongs to something, so the workload is really "release plus SBOM" rather than "SBOM" alone. Each run creates 20,016 releases, and each release carries its own 50-component CycloneDX SBOM (about 24 KB) synthesized from a pool of 2,867 real components extracted from a production ReARM build. Real purls, unique content per release, no deduplication shortcuts.

**We report two completion markers separately.** *Accepted* means the API returned and the release is queryable. *Fully processed* means the SBOM's components have been reconciled and are queryable per release. The second one is our honest equivalent of "ingested". A number without this distinction is trivially inflatable, because upload APIs, ours included, return before analysis completes.

**We exercised the full production path**: public ingress, GraphQL multipart upload, authentication, rate limiting (100 requests per 30 seconds per API key; runs used 5 keys), BOM storage in rebom, artifact push to an OCI registry, and asynchronous component reconciliation.

## Infrastructure

Everything ran on a single-node k3s sandbox: **8 vCPU, 30 GB RAM** (OVH, Montreal). And *everything* means everything. The ReARM backend (4 CPU / 16 GiB limit), rebom-backend (4 replicas), Keycloak, two PostgreSQL instances, the OCI registry itself (except in the Azure run), and the JMeter load generator all shared that one node. These are deliberately modest, worst-case numbers: no component had a machine to itself, and dedicated hardware would do better across the board.

## Results across four registries

Same workload, same stack, only the artifact registry swapped:

| Registry | Accept rate | 20k fully processed, end to end | Notes |
|---|---|---|---|
| CNCF Distribution, in-cluster | 15.0/s (capped by the test pacer) | **1h 21m** | Flat across the whole run |
| zot, sharded repositories | 75 pushes/s in direct probes | ~1h 21m class (drain-bound, projected from push probes) | Fastest registry we measured |
| Azure Container Registry, Premium, remote | 4.0/s, flat | 3h 56m | Bounded by our push chain amplifying ~60 ms network round trips, not by ACR, which handled 4x that in direct probes |
| Harbor, in-cluster | 3.3/s, flat | 6h 42m | Middleware cost per push, insensitive to repository size; measured before the reconcile batch tuning described below, so most of this time is drain |
| zot, single hot repository | 13/s decaying to ~2/s | 6h 18m | Per-repository tag-count degradation |

Every SBOM that was accepted was fully processed, in every run: roughly 100,000 releases across the campaign with zero reconciliation failures. The only errors of any kind were three uploads out of 20,016 that timed out at accept time on the remote ACR path (0.01%). Every difference in the table is throughput, not correctness.

## What we learned about zot, and why it is still our bundled default

ReARM CE bundles [zot](https://zotregistry.dev) as its default artifact registry, configured the way this workload wants it: dedupe off, GC off, pinned minimal image. The bundled zot is sized for typical CE usage, **up to roughly 2,000 SBOMs per month**, and at that volume it is effectively instant, with pushes under 100 ms.

The load test surfaced one scaling behavior worth knowing. zot's per-push cost grows with the number of tags in a single repository. The growth is near-linear, and adding concurrency does not help. Total registry size is irrelevant: a fresh repository on a 90,000-object instance pushes at full speed. ReARM stores BOMs in monthly repository buckets, so this only matters if a single month accumulates tens of thousands of SBOMs. For deployments beyond bundled-zot scale we set up **Harbor on demand**, which stays flat at any repository size at a higher fixed cost per push. Finer repository sharding for high-volume zot deployments is on our roadmap.

## Azure Container Registry, and what is next

The ACR run is the interesting one for cloud deployments. ACR itself was never the limit: direct probes scaled linearly with no throttling. The flat 4/s came from our pipeline amplifying network round trips between the OVH node and the remote registry. Two follow-ups are planned: testing **ACR from within a private VPC**, where the network path is short, and reducing the number of round trips per artifact in our push path.

## The configuration knob this surfaced

The ceiling on processing (reconciliation) throughput turned out to be a hard-coded scheduler batch size. It is now a configurable property, `relizaprops.sbomReconcileDrainBatch`, with a default of 50. At the tuned setting of 250 the post-burst backlog drains at roughly 250 releases per minute instead of roughly 66, which took the 20k end-to-end time on an in-cluster registry from over 6 hours down to 1h 21m.

One scope note: the instance's Dependency-Track integration remained enabled throughout the campaign, but [BEAR](https://github.com/relizaio/bear) SBOM enrichment was not in play. These numbers measure ReARM's own ingestion and reconciliation pipeline.

## Reproduce it

The harness, including the component pool builder, JMeter plan, completion probes and every run record referenced above, is in the [ReARM repository](https://github.com/relizaio/rearm/tree/main/perf/sbom-ingest) under `perf/sbom-ingest`. The README states every ceiling, batch size and dedup layer that shapes the numbers, so if you get different results, you will be able to say exactly why.
