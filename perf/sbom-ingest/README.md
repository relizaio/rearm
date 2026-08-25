# SBOM ingest load test

A reproducible throughput harness for ReARM's SBOM ingestion path, driven by
JMeter against a fresh organization on a sandbox instance.

## What this measures, and what it does not

**System under test: ReARM.** Dependency-Track is wired up as a downstream
dependency so the full pipeline runs, but it is not the subject. Note that the
client-facing upload does not talk to Dependency-Track at all on the request
path -- `BomLifecycleService` defers that to the scheduler, which submits
synthetic BOMs out of band. So DTrack sits behind a queue, not in the hot path.

**The unit is a release, not an SBOM.** A release is what ReARM actually models;
an SBOM is an artifact hanging off one. Every run here is 1 SBOM per release
(the harness supports 2 for a variant), so "N releases/hour" is the honest
headline and "N SBOMs/hour" follows from it. Driving 20,000 SBOMs onto a handful
of releases would measure something nobody operates.

### Origin of the 20,000 SBOMs/hour target

The figure comes from the Dependency-Track 5.0 GA announcement (2026-06-09):

> "Early adopters running the v5 alphas have ingested upwards of 20,000 SBOMs
> per hour"

That sentence carries no hyperlink, no benchmark, and no methodology. There is
no published component count, hardware spec, or replica count, and "ingested" is
never defined -- which matters, because `POST /api/v1/bom` returns a processing
token immediately and analysis happens out of band, so "SBOMs accepted per hour"
is trivially inflatable. It is attributed to unnamed third parties on pre-GA
alpha builds.

**Treat it as a directional target, not a benchmark to beat.** This harness
exists to produce the thing that claim lacks: a number with a stated component
size, a stated definition of done, a stated hardware spec, and a script anyone
can re-run.

## Definitions of "done"

A single number is meaningless without saying when a release counts as ingested.
This harness reports three, and the run record must state which one a headline
figure refers to:

| Marker | Means | Bound by |
|---|---|---|
| **T1 accept** | The GraphQL mutation returned. Artifact stored, release queryable. | Rate limiter, Hikari pool, request handling |
| **T2 reconciled** | `flow_control->>'sbomReconcileRequestedAt'` cleared, so `sbom_components` reflects the BOM and the release's components are queryable. | The per-minute reconcile drain |
| **T3 fanned out** | The synthetic DTrack buckets for the org submitted and ingested. | Scheduler tick plus DTrack itself |

**T2 is the honest headline.** T1 is the closest analogue to what the DTrack
claim probably measured, and should be reported alongside precisely so the
comparison is explicit rather than implied.

## Known ceilings in ReARM

Measured against the code, not assumed. These bound the result before any
hardware limit is reached, and a run that ignores them will report a plateau
without explaining it.

| Limit | Value | Where |
|---|---|---|
| API rate limiter (Bucket4j, per key) | 100 requests / 30s = **12,000/hr per key** | `ws/RateLimitingFilter.java:65-72` |
| SBOM reconcile drain | 50 releases / minute **cluster-wide** = **3,000/hr** | `service/SchedulingService.java:251` |
| Hikari connection pool | 20 | `application.yaml:62` |
| Scheduler thread pool | 3 | `application.yaml:30` |
| Release finalizer | single thread, 180s delay per release | `App.java:327-331`, `ReleaseFinalizerService.java:51-57` |
| JPA statement timeout | 120s | `application.yaml:104` |

Two consequences worth stating plainly before any run:

- The rate limiter applies to **every** endpoint and programmatic API keys are
  **not** exempt; they get their own per-key bucket keyed `api:<apiKeyId>`. One
  key cannot exceed 12,000 requests/hour. Exceeding 20,000 releases/hour
  therefore requires multiple API keys, and the run record must say how many.
- On T2, the reconcile drain caps *sustained* throughput at **3,000
  releases/hour** regardless of how fast the client pushes. This is a batch-size
  constant, not a hardware limit, and it is **cluster-wide, not per instance**:
  `processPendingReconciles(50)` runs inside `scheduleResolveDependencyTrackStatus`,
  which holds the `RESOLVE_DEPENDENCY_TRACK_STATUS` advisory lock precisely so
  that only one instance runs it per tick. Adding backend replicas does not
  multiply the drain.

### Sustained rate vs burst capacity

These are different claims and the harness must not conflate them. T1 accept
does not block on T2: the reconcile queue is a marker on the release row, so a
burst is absorbed as backlog and drained afterwards. That makes two separate
numbers worth reporting:

- **Sustained** -- the arrival rate the system can hold indefinitely without the
  backlog growing without bound. Bounded by the drain: **3,000 releases/hour**.
  Sustained arrival above this grows the queue forever; it does not catch up.
- **Burst** -- how large a spike can be accepted quickly and drained afterwards,
  and how long the drain takes. At 50/min, a 20,000-release spike needs **6.7
  hours** of continuous draining, 10,000 needs 3.3, 5,000 needs 1.7.

Burst is the more realistic shape for CI-driven traffic, and is the arm that
most resembles how the number being compared against was probably produced. But
report the drain-to-zero time with it: a spike that is accepted in an hour and
takes most of a day to become queryable is a materially different product claim
from one processed as it arrives.

**While a release sits in the backlog it is accepted and queryable, but its
`sbom_components` are not populated** -- so component and vulnerability views for
that release are empty until the drain reaches it. The backlog is invisible to
ingestion and visible in the product.

#### Backlog safety: verified, nothing is dropped

Traced against the code rather than assumed, because the burst framing only
holds if a queued release is never silently lost:

- The queue is unbounded. It is a JSONB marker on the release row
  (`ReleaseRepository.java:417-422`), written first-write-wins so re-triggers do
  not reset FIFO position. No in-memory structure, no capacity check, no
  backpressure, no rejection path.
- **Nothing is ever given up on.** The failure path bumps
  `sbomReconcileFailureCount` and sets `sbomReconcileSkipUntil` but deliberately
  leaves `sbomReconcileRequestedAt` in place (`ReleaseRepository.java:461-477`).
  No code reads the failure count to decide to stop; the count only selects the
  backoff (30s doubling to a 3600s cap). The marker has exactly one clearer,
  the success path (`SbomComponentService.java:271`). There is no TTL, no
  max-age predicate in the batch query, and no sweeper. A release that sat for
  twelve hours is still queued.
- FIFO is fair. `ORDER BY sbomReconcileRequestedAt ASC` plus the `skipUntil`
  fence means a repeatedly-failing head-of-queue row cannot monopolize batches,
  and new arrivals cannot starve old ones.

#### But 50/min is a ceiling, not a floor

Two effects push the effective drain rate below 50/min, and both get *more*
likely as the backlog grows:

- **Heap-guard abort.** `HeapPressureGuard` is checked before every release in
  the loop and on trip it `return`s, abandoning the whole remaining batch for
  that tick (`SbomComponentService.java:248-252`). Thresholds are GC-hint below
  40% free heap and abort below 20%. No work is lost -- the remainder keeps its
  marker with no `skipUntil` and is re-selected next tick -- but if the guard
  trips early the tick drains nothing. Watch for the `free heap below 20%` error
  line; a sustained backlog of heavy BOMs makes this a plausible steady state.
- **Tick stretch.** The reconcile drain shares its PT1M tick with the per-org
  synthetic DTrack loop and three sweeps (`SchedulingService.java:223-289`).
  With `fixedRate` plus the advisory lock, an over-running tick just makes the
  next ones no-op, so the real cadence is the actual tick duration. A tick that
  takes three minutes drains ~17/min, i.e. 1,000/hr.

So treat 6.7 hours for a 20,000 spike as the optimistic bound, and record the
observed per-minute drain series rather than assuming the constant.

#### The slowest queue is auto-integrate, not reconcile

If the burst consists of ASSEMBLED releases belonging to products with feature
sets, the binding constraint is **not** SBOM reconcile:

| Queue | Batch / tick | Ceiling | 20,000 spike |
|---|---|---|---|
| Auto-integrate drain (`SchedulingService.java:42`, `:296`) | 20 / PT1M | **1,200/hr** | **~16 h** |
| SBOM reconcile (`SchedulingService.java:251`) | 50 / PT1M | 3,000/hr | ~6.7 h |
| Metrics compute (`SchedulingService.java:339`) | 102 / PT1M | ~6,120/hr | ~3.3 h |
| Notification outbox + delivery (`SchedulingService.java:650`, `:686`) | 50 / PT5S | 36,000/hr each | not a factor |

The immediate auto-integrate hop runs on `autoIntegrateExecutor` (core 2 / max 3,
queue 2000, **DiscardPolicy** -- `App.java:342-353`). At 20,000 releases in an
hour that queue overflows and the discards fall back to the 1,200/hr drain. No
work is lost, because the durable marker is written *before* the executor
hand-off (`OssReleaseService.java:1932`), but the fallback is 2.5x slower than
reconcile.

**Test-design consequence:** whether the synthetic releases participate in
auto-integrate changes the answer by more than a factor of two. Runs must state
it. The default arm uses plain component releases with no product feature set,
isolating SBOM ingestion; a separate arm exercises the product path, and its
number should never be quoted as the SBOM ingestion figure.

Do not quietly raise either limit to make a number look better. Report the
default-configuration result first; tuning is a separate, labelled run.

## Corpus design

### The pool, not a corpus of files

At the real product BOM's weight (4.9 KB per component), pre-generating 20,000
50-component SBOMs would be 5 GB on disk per run. Instead `lib/build_pool.py`
extracts components once into `lib/pool.json`, and the JMeter plan synthesizes
each release's BOM in memory at request time. Disk cost is the pool alone.

Build it from real SBOMs. Purls that resolve to nothing make downstream
vulnerability analysis a no-op, and a load test that skips the expensive part
measures parsing:

```
python3 lib/build_pool.py --out lib/pool.json <real.cdx.json> [more.cdx.json ...]
```

Current pool: 2867 components across maven, npm, generic, deb, apk, golang, rpm,
github, oci, helm, docker and pypi.

### Lean vs rich

| Profile | Per component | 50-component BOM | Keeps |
|---|---|---|---|
| `lean` (default) | 0.48 KB | ~24 KB | type, name, version, group, purl, licenses, hashes, description, supplier, cpe, scope |
| `--rich` | 4.46 KB | ~223 KB | the above plus properties, externalReferences, evidence |

Lean BOMs flatter the numbers on parse cost. Rich reproduces real-world weight
(the source product BOM is 4.9 KB per component). **State the profile in the run
record.**

### Warm vs cold

There are four dedup layers on this path, and a naive load test hits all of
them, measuring a fast path instead of ingestion:

1. SBOM probing returns an encrypted `DEDUP|<projectId>` short-circuit for
   content already seen (`DTrackService.startSbomProbing:92-105`).
2. `existsByCanonicalArtifactUuid` skips the rebom fetch, the full BOM parse and
   the component upsert entirely (`SbomComponentService.java:398`).
3. Synthetic bucket idempotency hashes the sorted canonical-purl set, so BOMs
   contributing no new purls produce **zero** DTrack uploads
   (`SyntheticSbomService.java:386-393`).
4. rebom-backend is content-addressed on its own side.

So the corpus mode is a first-class variable, not an implementation detail:

- **warm** -- sample components from the pool. Heavy purl reuse across releases,
  which is what a real organization looks like. Dedup engages legitimately.
- **cold** -- mutate purls so every release introduces new canonical purls.
  Worst case: maximum reconcile and fan-out work per release.

Report both. Warm alone overstates throughput; cold alone understates it.

## Layout

```
perf/sbom-ingest/
  README.md              this file: methodology and run protocol
  lib/build_pool.py      extracts a component pool from real CycloneDX docs
  lib/pool.json          the generated pool (lean profile)
  sql/                   drain and completion probes for T2/T3
  results/               run records, one directory per run
```

## Run record

Every run writes a record to `results/<date>-<label>/`. A number without this
metadata is not reportable:

- ReARM version and image tag, and the sandbox instance URI
- Hardware: node CPU count, RAM, whether the load generator shared the node
- Dependency-Track version, deployment topology, and resource limits
- Pool profile (lean/rich), corpus mode (warm/cold), components per BOM,
  SBOMs per release
- Number of API keys used, and target request rate
- T1/T2/T3 timings, and which one any headline number refers to
- Whether any limit in the ceilings table was altered from its default
