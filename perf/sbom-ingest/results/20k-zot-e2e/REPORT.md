# SBOM ingest e2e: 20,016 releases, zot registry (dedupe off), 13/s pacer

**Date:** 2026-08-18/19 - **Instance:** psclaude sandbox (8 cores / 30 GB, single k3s node, all services co-located)

## Configuration

| Param | Value |
|---|---|
| Releases (1 SBOM each) | 20,016 + 25 setup samples |
| SBOM shape | 50 components, lean profile (~24 KB), warm mode (real purls, reused across releases) |
| Pacer | 13/s (RPM 780), 24 threads, 5 API keys (capacity 16.7/s) |
| Registry | zot (ghcr.io project-zot latest, unpinned), `dedupe: false`, emptyDir, anonymous |
| Backend | rearm-backend:drain250 (reconcile batch 250/tick), 16 Gi limit (~11.2 G heap) |
| rebom-backend | 4 replicas |
| Path | public ingress -> GraphQL addReleaseProgrammatic multipart |

## Headline numbers

| Phase | Result |
|---|---|
| Accept | 20,016 in **1h 24m 54s** = **3.9/s** avg (started ~13/s, decayed - see below) |
| Accept errors | **0** (all 20,041 samples HTTP 200) |
| Accept latency | avg 5,863 ms / max 18,635 ms (early: ~170 ms) |
| Backlog peak | 19,465 queued at accept end |
| Drain rate | **~66-70/min** average (vs 245/min same config on registry:2) |
| Reconcile failures | **0** across 20,016 |
| sbom_components materialized | 22,827 |
| **End-to-end** (first submit -> all queryable) | **6h 18m 01s** (22:53:12 -> 05:11:13 UTC) |

## Throughput decay during accept (cumulative)

| Samples | Elapsed | Cumulative rate/s |
|---|---|---|
| 591 | 00:00:46 | 12.8 |
| 6015 | 00:11:16 | 8.9 |
| 9106 | 00:21:46 | 7.0 |
| 11541 | 00:32:16 | 6.0 |
| 13607 | 00:42:47 | 5.3 |
| 15461 | 00:53:17 | 4.8 |
| 17115 | 01:03:46 | 4.5 |
| 18650 | 01:14:16 | 4.2 |
| 20035 | 01:24:47 | 3.9 |

The run started at pacer speed (~13/s, ~170 ms/req) and decayed continuously as zot's
object count grew; drain (registry reads via rebom) degraded identically (245/min on a
~1k-object zot vs 66/min at 40k+ objects).

## Per-service resource use (30 s probes, whole run incl. drain)

| Service | CPU peak (m) | CPU avg (m) | Mem peak (Mi) | Mem avg (Mi) |
|---|---|---|---|---|
| zottest | 4280 | 1622 | 512 | 325 |
| rearm-postgresql | 1025 | 229 | 2823 | 1361 |
| rearm-backend | 1002 | 86 | 8200 | 5738 |
| rearm-rebom-postgres | 329 | 29 | 175 | 159 |
| rearm-oci-artifact | 255 | 20 | 87 | 70 |
| rearm-rebom-backend | 177 | 17 | 506 | 331 |
| rearm-ui | 13 | 1 | 4 | 2 |
| rearm-keycloak | 6 | 2 | 724 | 723 |
| testreg | 2 | 1 | 1139 | 530 |
| rearm-keycloak-postgres | 1 | 1 | 34 | 33 |

## Comparison across registries (same stack, same 20k workload)

| Registry | Accept | Avg latency | E2E | Registry CPU peak |
|---|---|---|---|---|
| Harbor (shared, middleware) | 3.3/s | 7,200 ms | 6h 42m | ~2,000m |
| plain Distribution (registry:2) | 15.0/s (pacer cap) | 113 ms | 1h 21m | ~250m |
| zot, dedupe on (default) | 3.8/s | 6,013 ms | not run to drain | ~3,374m |
| **zot, dedupe off (this run)** | **3.9/s avg (13 -> ~2/s decay)** | **5,863 ms** | **6h 18m** | see table |

## Findings

1. **zot degrades with stored object count.** With ~1k objects it holds the 13/s pacer at
   ~170 ms; by ~40k objects (20k SBOMs x 2 artifacts each) both pushes and pulls slow ~10x.
   Control: plain registry:2 stayed flat across the identical 20k. Everything else in the
   stack is excluded by that control.
2. **Dedupe off is necessary but not sufficient.** It removed the BoltDB dedup-index
   serialization (13/s on a near-empty zot vs 3.8/s dedupe-on) but does not prevent the
   count-driven decay.
3. Mechanism inside zot unconfirmed - candidates: metaDB (BoltDB) writes per manifest,
   per-push tag indexing on a 40k-tag repo, background GC cycles. Logs were at warn level
   for this run; a rerun with info logs + GC disabled would separate these.
4. **Zero functional errors end-to-end** - the degradation is purely throughput/latency.
5. Caveats: unpinned zot image, minimal hand-written config, emptyDir storage, shared
   8-core node with the load generator co-located. Numbers are sandbox-relative.

## Raw data

`queue.csv` (30 s backlog series), `top.log` (30 s kubectl-top snapshots), `run.jtl.gz`
(per-request), `jmeter-stdout.log`, `timeline.txt`.

---

# Addendum: zot scale assessment to 200k SBOMs

Question: is zot viable up to ~200k stored SBOMs? Method: direct fill of the hot
monthly repo via the oci-service (identical ORAS write path, 17 KB unique payloads,
6 concurrent workers), latency logged per push; then two decisive probes.

## Degradation curve (hot repo, 6-way concurrent, avg s/push per 10k-object band)

| Objects in repo | Avg push latency (s) | Samples |
|---|---|---|
| ~40k | 1.70 | 7,000 |
| ~50k | 1.84 | 10,000 |
| ~60k | 2.24 | 10,000 |
| ~70k | 2.45 | 10,000 |
| ~80k | 2.80 | 10,000 |
| ~90k | 3.01 | 1,843 |

Near-linear: ~+0.27 s per +10k tags in the repo. Extrapolated to a single repo
holding 200k SBOMs' objects (~400k tags with raw+augmented): **~10-12 s per push**,
product-path accept well under 1/s. Concurrency does not help - 6 workers each saw
~6x the single-worker latency (writes serialize inside zot).

## The decisive probes (both at ~92k objects resident, same zot instance)

**1. Hot vs fresh repo (during full filler load):** hot repo 3.1-7.5 s/push; fresh
repo 0.33-1.45 s. **Degradation is per-repository tag count, not global store size.**

**2. Sharded layout (after fill stopped):** 300 pushes round-robin across 10 fresh
repos: **75 pushes/s single-threaded, 8-76 ms each** - vs 1.9-5.9 s to the hot repo
back-to-back. ~200x. Sharding fully restores original performance.

## Conclusion for the 200k question

**zot is viable at 200k+ total SBOMs if and only if no single repository accumulates
more than roughly 10-20k tags.** Total store size is irrelevant; per-repo tag count
is everything.

- Current layout risk: rebom buckets by month (`rebom-artifacts-YYYY-MM`). A month
  with 10k+ SBOMs degrades noticeably; the 20k e2e run in this report is exactly
  that failure mode (13/s decaying to ~2/s within one bucket).
- Fix: shard rebom's bucket naming finer - weekly/daily or hash-sharded (e.g.
  `rebom-artifacts-YYYY-MM-<xx>` by uuid prefix, 16-256 shards). Small change in
  rebom-backend's repository-name construction; the namespace-validation change in
  rearm#289 already accepts any bucket shape under the namespace.
- Correction on the dedupe recommendation: **both helm charts and both
  docker-compose stacks already ship `dedupe: false` and `gc: false`** for the
  bundled zot (with a pinned zot-minimal image, and a compose comment documenting
  exactly this access pattern). The dedupe-on penalty measured above applied only
  to this test's hand-written minimal config, which omitted the key and inherited
  zot's internal default of true. Production deployments were never exposed to it.
  No chart change needed; the per-repo tag-count finding (measured with dedupe
  off) is unaffected and remains the actionable issue.

Raw data: `results/zot-scale/filler-w*.csv` (48,843 per-push latencies), `curve.md`.

---

# Addendum 2: Harbor counterpart checks

**1. Dedupe-equivalent config: does not exist, and is not needed.** Harbor's registry
component is CNCF Distribution (filesystem driver, redis layer-info cache). Blob
storage is content-addressed globally by design - one copy per digest, shared across
repos inherently, with no per-push dedup index to maintain. zot's dedupe failure mode
has no Harbor analogue.

**2. Per-repo tag-count sensitivity: none measured.** Probed through the same
oci-service path (REGISTRY_HOST flipped to harbor.harbor and back): 60 serial pushes
into the ~86k-tag rebom-artifacts-2026-08 repo vs 60 into a fresh repo -- **13s flat
for both (~4.6/s serial)**. Harbor pushes at the same speed into a huge repo as into
an empty one.

Combined picture: Harbor's per-push cost is middleware overhead - flat with repo size
but high and CPU-expensive under concurrency (3.3/s aggregate, ~2 cores at 24-way in
the main runs). zot is the opposite: near-zero overhead on small repos (75/s, 8ms)
but degrades linearly with tags-per-repo. Consequently: sharded-bucket zot is the
fastest option measured; unsharded-bucket zot is the slowest; Harbor sits in between,
insensitive to layout but paying its fixed toll on every push.

---

# Addendum 3: Azure Container Registry (Premium) 20k e2e

Same harness, same stack, registry switched to a throwaway ACR Premium instance
(remote, TLS, WAN path). 13/s pacer, 5 keys, warm mode, 50-comp lean BOMs.

| Metric | Result |
|---|---|
| Accept | 20,016 attempts in **1h 23m 19s**, **4.0/s flat** (no decay: 4.2 -> 4.0/s first to last interval) |
| Accept errors | **3 of 20,041 (0.01%)**: 1 client socket timeout (120s max), 2 transient; 20,014 releases created |
| Accept latency | avg 5,683 ms (per-push ~5.7s = ~7 registry round-trips x WAN RTT) |
| Backlog peak | 19,360 |
| Drain rate | **~126/min** sustained (pulls also WAN-bound, lighter than pushes) |
| Reconcile failures | **0** |
| **End-to-end** | **3h 56m 16s** (13:00:03 -> 16:56:19 UTC) |

Characteristics: **layout-insensitive like Harbor** (flat rate across 20k tags in one
repo -- no zot-style decay, no dedupe concern; managed content-addressed backend).

**Bound-by correction (post-run probes):** initially attributed to WAN round trips;
direct measurement disproved that. RTT is ~60ms (OVH Montreal node; likely US-region
ACR), giving a ~0.4s network floor per push -- matching a warm single push exactly.
A direct concurrency probe scaled near-linearly: 1 worker 0.94/s, 8 workers 17/s
aggregate (~0.47s/push), no 429s -- **no ACR concurrency cap observed, and 17/s
direct is 4x what the full run achieved**. The 4/s run bound is therefore in the
ReARM chain's amplification under load (backend -> rebom -> two serial oci pushes
per BOM at 24-way turns ~0.5s of registry time into 5.7s of request time; the same
chain turns registry:2's ~1ms into 113ms). Levers: reduce pushes per BOM (the raw
artifact), parallelize the chain, or add oci-service/rebom capacity -- not registry
proximity alone.

## Final registry comparison (identical 20k workload, same stack)

| Registry | Accept | Decay w/ tags | Drain | E2E | Bound by |
|---|---|---|---|---|---|
| zot, sharded repos | 75/s probe (13/s pacer holds) | none | 245/min-class | ~1h 21m-class | drain batch config |
| plain Distribution (in-cluster) | 15.0/s (pacer cap) | none | 245/min | 1h 21m | drain batch config |
| **ACR Premium (remote)** | **4.0/s flat** | **none** | **126/min** | **3h 56m** | chain amplification of ~60ms RTT (ACR itself scaled to 17/s in probes) |
| Harbor (in-cluster, middleware) | 3.3/s flat | none | 66/min* | 6h 42m | middleware CPU (*drain measured at batch 50) |
| zot, one hot repo | 13 -> 2/s decaying | severe (linear) | 66-70/min | 6h 18m | per-repo tag indexing |

Zero reconcile failures in every run; differences are purely throughput/latency.
