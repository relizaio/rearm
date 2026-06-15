/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import io.reliza.common.HeapPressureGuard;
import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.AcollectionData.DiffComponent;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.DeliverableData;
import io.reliza.model.FlowControl;
import io.reliza.model.Release;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.ParsedBom;
import io.reliza.model.tea.Rebom.ParsedBomComponent;
import io.reliza.model.tea.Rebom.ParsedBomDependency;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains the SBOM component aggregation. As of V37 (artifact-keyed
 * shape), the per-component aggregation lives in
 * {@code artifact_sbom_components} keyed by canonical artifact — not per
 * (release, sbom_component). A release "owns" components by virtue of the
 * BOM artifacts it references (deliverables, source-code-entry artifacts,
 * release-attached artifacts), resolved through {@code artifact_canonical_map}
 * to canonical form. The per-release view is synthesized at read time.
 *
 * <p>The {@code release_artifact_index} table keeps a reverse mapping
 * release → canonical artifacts, rebuilt on every reconcile. It makes
 * impact analysis ("which releases reference this sbom_component") a
 * 1-join query instead of walking JSONB across the artifacts surface.
 *
 * <p>Reconciliation is queue-driven by {@code releases.flow_control}; the
 * every-minute Dependency-Track scheduler drains the queue under its
 * existing advisory lock. A release's reconcile is cheap when its
 * canonical artifacts have already been parsed by a prior reconcile —
 * existence check via {@code ArtifactSbomComponentRepository.existsByCanonicalArtifactUuid}
 * avoids re-parsing identical content across releases.
 */
@Slf4j
@Service
public class SbomComponentService {

	/**
	 * Generation cookie stamped onto {@code releases.sbom_schema_version}
	 * by every successful reconcile. Bumped to 3 on this V37 rewrite to
	 * the artifact-keyed shape. Future area migrations can either re-enqueue
	 * via flow_control (V25 / V27 / V28 / V37 pattern) or simply bump this
	 * constant — a catch-up scheduler can then find rows whose stored
	 * version is below the current value via the partial index.
	 */
	public static final int CURRENT_SBOM_SCHEMA_VERSION = 3;

	@Autowired private RebomService rebomService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ArtifactService artifactService;
	@Autowired private SharedArtifactService sharedArtifactService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private GetDeliverableService getDeliverableService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private VariantService variantService;
	// Phase 2b-2: BOM-diff now flows through the notification outbox via this
	// hook (SAAS impl writes a RELEASE_BOM_DIFF event). CE build has no impl
	// bean, so null-check before calling.
	@Autowired(required = false) private ReleaseChangeHook releaseChangeHook;
	// @Lazy: the post-reconcile pipeline calls back into AcollectionService for
	// the snapshot resolve + changelog cache; lazy keeps startup wiring cycle-safe.
	@Autowired @Lazy private AcollectionService acollectionService;
	@Autowired private ReleaseRepository releaseRepository;

	/**
	 * Self-injection so {@link #processPendingReconciles(int)} can call the
	 * {@code @Transactional} reconcile method through Spring's proxy.
	 */
	@Autowired @Lazy private SbomComponentService self;

	private static final int BASE_BACKOFF_SECONDS = 30;
	private static final int MAX_BACKOFF_SECONDS = 3600;

	// ===================================================================
	// Enrichment puller tuning (see pullEnrichmentForOrg)
	// ===================================================================
	// How many BOMs we successfully pull enriched licenses from per org per tick.
	// Each pull stamps every un-enriched component in that BOM, so this covers far
	// more than 5 components.
	private static final int ENRICHMENT_PULL_TARGET = 5;
	// Upper bound on un-enriched candidate components fetched per org per tick.
	// Generous so we can "add one more" past BOMs still PENDING and still reach
	// the pull target.
	private static final int ENRICHMENT_CANDIDATE_WINDOW = 200;
	// Upper bound on distinct BOMs probed per org per tick — caps rebom round-trips
	// even when the candidate window dedupes to many BOMs that are all PENDING.
	private static final int ENRICHMENT_MAX_BOMS_PROBED = 25;

	/**
	 * Persistence-context flush + clear granularity for bulk inserts. Keeps the
	 * Hibernate L1 cache bounded when a single BOM yields hundreds-to-thousands
	 * of {@link ArtifactSbomComponent} rows. Picked to amortize JDBC batch
	 * overhead while still letting GC reclaim the entity references between
	 * chunks.
	 */
	private static final int FLUSH_CHUNK = 500;

	@PersistenceContext
	private EntityManager entityManager;

	private final SbomComponentRepository sbomComponentRepository;
	private final ArtifactSbomComponentRepository artifactSbomComponentRepository;
	private final ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	private final ArtifactCanonicalMapRepository artifactCanonicalMapRepository;

	SbomComponentService(
			SbomComponentRepository sbomComponentRepository,
			ArtifactSbomComponentRepository artifactSbomComponentRepository,
			ReleaseArtifactIndexRepository releaseArtifactIndexRepository,
			ArtifactCanonicalMapRepository artifactCanonicalMapRepository) {
		this.sbomComponentRepository = sbomComponentRepository;
		this.artifactSbomComponentRepository = artifactSbomComponentRepository;
		this.releaseArtifactIndexRepository = releaseArtifactIndexRepository;
		this.artifactCanonicalMapRepository = artifactCanonicalMapRepository;
	}

	// ===================================================================
	// Queue API (unchanged from V25/V27/V28)
	// ===================================================================

	public void requestReconcile(UUID releaseUuid) {
		if (releaseUuid == null) return;
		releaseRepository.markSbomReconcileRequested(releaseUuid);
	}

	public void processPendingReconciles(int batchLimit) {
		// Load UUIDs only — the full Release has five JSONB columns
		// (recordData, metrics, approvalEvents, updateEvents, flowControl)
		// and Hibernate's dirty-checking snapshot deep-copies each via
		// serialize→bytes→deserialize. Batching full rows up front
		// allocated enough that the scheduler thread could OOM before
		// the per-iteration heap guard had a chance to fire. The
		// reconcile itself only needs the UUID; the FlowControl
		// failure-count read is on the rare exception path and pays
		// for one lazy findById there.
		List<UUID> pendingUuids = releaseRepository.findUuidsOfReleasesPendingSbomReconcile(batchLimit);
		if (pendingUuids.isEmpty()) return;
		log.debug("Draining {} pending SBOM reconciles", pendingUuids.size());
		int processed = 0;
		int total = pendingUuids.size();
		for (UUID releaseUuid : pendingUuids) {
			// Pre-flight free-heap guard. ParsedBom + aggregation Maps can
			// spike allocation by tens of MB per reconcile; if we're
			// already running hot, punting the next release back to the
			// queue lets the next scheduler tick try again after the GC
			// hint reclaims the previous reconcile's transient state.
			// Shared with DT batch loops via HeapPressureGuard.
			if (HeapPressureGuard.checkAndMaybeGc(log, "SBOM reconcile drain",
					String.format("before release %s (%d/%d done); remaining will be retried on the next scheduler tick.",
							releaseUuid, processed, total))) {
				return;
			}
			try {
				self.reconcileReleaseSbomComponents(releaseUuid);
				releaseRepository.clearSbomReconcileRequested(releaseUuid);
				// The release's full inventory is now rebuilt — the natural
				// "all BOMs reconciled" moment. Run the post-reconcile pipeline:
				// refresh the acollection snapshot, recompute the changelog cache,
				// and fire the once-per-release notification. Best-effort; won't
				// disturb the drain.
				postReconcileBomDiff(releaseUuid);
				processed++;
			} catch (Exception e) {
				int nextAttempt = releaseRepository.findById(releaseUuid)
						.map(SbomComponentService::currentReconcileFailureCount)
						.orElse(0) + 1;
				int backoff = Math.min(BASE_BACKOFF_SECONDS << Math.min(nextAttempt - 1, 7),
						MAX_BACKOFF_SECONDS);
				releaseRepository.recordSbomReconcileFailure(releaseUuid, backoff);
				log.error("SBOM reconcile failed for release {} (attempt {}, retry in {}s): {}",
						releaseUuid, nextAttempt, backoff, e.getMessage(), e);
			}
		}
	}

private static int currentReconcileFailureCount(Release r) {
		FlowControl fc = r.getFlowControl();
		if (fc == null || fc.sbomReconcileFailureCount() == null) return 0;
		return fc.sbomReconcileFailureCount();
	}

	// ===================================================================
	// Reconcile — artifact-keyed write path
	// ===================================================================

	/**
	 * Rebuild the SBOM aggregation for a release.
	 *
	 * <p>For each BOM artifact the release references, resolve to canonical
	 * via {@code artifact_canonical_map}. If the canonical hasn't been
	 * parsed yet (no rows in {@code artifact_sbom_components}), parse the
	 * BOM via rebom and write per-component rows. Then rebuild this release's
	 * {@code release_artifact_index} entries to point at the resolved
	 * canonical set, and stamp {@code releases.sbom_schema_version}.
	 *
	 * <p>The artifact rows are content-addressed and immutable. A reconcile
	 * on another release that shares the same BOM content reuses them
	 * without re-parsing.
	 *
	 * <p>This outer method is intentionally NOT {@code @Transactional}. Each
	 * artifact's parse + persist happens in its own short-lived transaction
	 * (via {@link #parseAndUpsertArtifactSbomComponents}), and the index
	 * rebuild + version-cookie stamp happen in a second short-lived
	 * transaction (via {@link #rebuildReleaseArtifactIndex}). The split
	 * keeps the Hibernate L1 cache bounded per artifact instead of letting
	 * it accumulate every entity loaded across all BOMs the release carries
	 * — important when one release contains many or unusually large BOMs.
	 * Idempotency across partial failure is preserved by the
	 * {@code existsByCanonicalArtifactUuid} short-circuit and by the
	 * single-transaction rebuild step at the tail.
	 */
	public void reconcileReleaseSbomComponents(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) {
			log.warn("reconcileReleaseSbomComponents called for missing release {}", releaseUuid);
			return;
		}
		ReleaseData rd = ord.get();
		UUID orgUuid = rd.getOrg();
		if (orgUuid == null) {
			throw new IllegalStateException(
					"reconcileReleaseSbomComponents: release " + releaseUuid + " has no org");
		}

		Set<UUID> canonicalArtifactSet = new LinkedHashSet<>();

		for (UUID artifactUuid : collectBomArtifactUuids(rd)) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isEmpty()) continue;
			ArtifactData ad = oad.get();
			if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) continue;

			UUID canonicalArtifactUuid = resolveCanonicalArtifact(ad, orgUuid);
			canonicalArtifactSet.add(canonicalArtifactUuid);

			// Skip the parse if this canonical's component graph is already on disk.
			// The artifact_sbom_components rows are content-addressed and immutable;
			// BEAR enrichment never changes the component set, only licenses — which
			// are pulled into sbom_components out-of-band by the enrichment puller,
			// so a re-parse here is unnecessary.
			if (artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonicalArtifactUuid)) {
				continue;
			}

			// Each artifact's parse runs in its own @Transactional via the Spring
			// proxy. The local ParsedBom + aggregation Maps inside the call are
			// fully GC-eligible the moment that transaction commits — they don't
			// have to wait for the whole release reconcile to finish.
			self.parseAndUpsertArtifactSbomComponents(ad, canonicalArtifactUuid, orgUuid);
		}

		self.rebuildReleaseArtifactIndex(releaseUuid, orgUuid, canonicalArtifactSet);
	}

	/**
	 * Wipe and rewrite {@code release_artifact_index} for one release, then
	 * stamp {@code releases.sbom_schema_version}. Runs as its own
	 * transaction so the entity manager's L1 cache is fresh — separate
	 * from any per-artifact parse transactions that ran earlier in the
	 * release's reconcile.
	 *
	 * <p>The release's BOM artifact set may have shifted since the last
	 * reconcile (a deliverable detached, a new SCE artifact added, etc.) —
	 * wholesale replacement keeps the index in sync with the current
	 * artifact set.
	 */
	@Transactional
	public void rebuildReleaseArtifactIndex(UUID releaseUuid, UUID orgUuid, Set<UUID> canonicalArtifactSet) {
		releaseArtifactIndexRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);
		if (canonicalArtifactSet != null && !canonicalArtifactSet.isEmpty()) {
			List<ReleaseArtifactIndex> rows = new ArrayList<>(canonicalArtifactSet.size());
			for (UUID canonical : canonicalArtifactSet) {
				ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
				idx.setOrg(orgUuid);
				idx.setReleaseUuid(releaseUuid);
				idx.setCanonicalArtifactUuid(canonical);
				rows.add(idx);
			}
			saveAllChunked(rows, releaseArtifactIndexRepository::saveAll);
		}
		markReleaseReconciled(releaseUuid);
	}

	// ===================================================================
	// Enrichment puller — pulls BEAR-enriched licenses into sbom_components
	// ===================================================================

	/**
	 * Pull BEAR-enriched licenses for an org's un-enriched matchable components,
	 * stamping {@code sbom_components.enriched_at} so the synthetic Dependency-Track
	 * gate can ship them. The front step of the every-minute synthetic tick.
	 *
	 * <p>Skips entirely for orgs without BEAR configured — their components ship
	 * un-gated (see {@link SyntheticSbomService#submitOrg}) and would otherwise
	 * churn here forever (enriched_at would never be set).
	 *
	 * <p>Picks the oldest un-enriched matchable components, dedupes them to the
	 * BOMs that declare them, and probes rebom for each BOM's enrichment status.
	 * COMPLETED → parse it and stamp every component it carries; PENDING / FAILED
	 * (rebom's own scheduler retries those) / SKIPPED → skip and move to the next
	 * BOM ("add one more") until {@value #ENRICHMENT_PULL_TARGET} BOMs are pulled
	 * or candidates run out. Each pull stamps the whole BOM, so a single tick
	 * covers far more than the target component count.
	 */
	public void pullEnrichmentForOrg(UUID orgUuid) {
		if (orgUuid == null) return;
		boolean bearConfigured;
		try {
			bearConfigured = rebomService.isEnrichmentConfigured(orgUuid);
		} catch (Exception e) {
			log.warn("Enrichment puller: unable to determine BEAR config for org {}: {}",
					orgUuid, e.getMessage());
			return;
		}
		if (!bearConfigured) return;

		List<SbomComponent> candidates = sbomComponentRepository
				.findUnenrichedMatchableByOrgOrdered(orgUuid.toString(), ENRICHMENT_CANDIDATE_WINDOW);
		if (candidates.isEmpty()) return;

		// Dedupe candidates to distinct BOMs, preserving oldest-first order, bounded.
		Set<UUID> bomIds = new LinkedHashSet<>();
		for (SbomComponent sc : candidates) {
			if (bomIds.size() >= ENRICHMENT_MAX_BOMS_PROBED) break;
			UUID bomId = resolveBomForComponent(orgUuid, sc.getUuid());
			if (bomId != null) bomIds.add(bomId);
		}

		int pulls = 0;
		for (UUID bomId : bomIds) {
			if (pulls >= ENRICHMENT_PULL_TARGET) break;
			try {
				RebomService.BomMeta meta = rebomService.getBomMetadataById(bomId, orgUuid);
				if (meta == null
						|| meta.enrichmentStatus() != RebomService.EnrichmentStatus.COMPLETED) {
					// PENDING / FAILED / SKIPPED — not ready; try the next BOM.
					continue;
				}
				ParsedBom parsed = rebomService.parseBom(bomId, orgUuid);
				if (parsed == null || parsed.components() == null) continue;
				Map<String, List<Map<String, Object>>> licByCanonical = new LinkedHashMap<>();
				for (ParsedBomComponent pc : parsed.components()) {
					if (pc == null || pc.canonicalPurl() == null) continue;
					licByCanonical.putIfAbsent(pc.canonicalPurl(), pc.licenses());
				}
				self.stampEnrichedLicenses(orgUuid, licByCanonical);
				pulls++;
			} catch (Exception e) {
				log.warn("Enrichment pull failed for bom {} (org {}): {}",
						bomId, orgUuid, e.getMessage());
			}
		}
	}

	/**
	 * Resolve a representative BOM id for a canonical sbom_component: pick any
	 * artifact that declares it, map to its canonical artifact, and read that
	 * artifact's internal BOM id. Null when no such artifact / BOM exists.
	 */
	private UUID resolveBomForComponent(UUID orgUuid, UUID sbomComponentUuid) {
		return artifactSbomComponentRepository
				.findFirstByOrgAndSbomComponentUuid(orgUuid, sbomComponentUuid)
				.map(ArtifactSbomComponent::getCanonicalArtifactUuid)
				.flatMap(artifactService::getArtifactData)
				.map(ad -> ad.getInternalBom() != null ? ad.getInternalBom().id() : null)
				.orElse(null);
	}

	/**
	 * Stamp enriched licenses + {@code enriched_at} for the components of one
	 * COMPLETED BOM, in place (UPDATE, never delete+reinsert). Fill-once: a
	 * component already enriched (by a prior pull of another BOM sharing it) is
	 * left untouched, so we never re-stamp or overwrite.
	 */
	@Transactional
	public void stampEnrichedLicenses(UUID orgUuid, Map<String, List<Map<String, Object>>> licByCanonical) {
		if (licByCanonical == null || licByCanonical.isEmpty()) return;
		List<String> canonicals = new ArrayList<>(licByCanonical.keySet());
		for (SbomComponent sc :
				sbomComponentRepository.findByOrgAndCanonicalPurlIn(orgUuid.toString(), canonicals)) {
			// Fill-once: never re-stamp or overwrite an already-enriched component.
			// TODO: revisit for skip-patterned components — one skipped during this
			// BOM's enrichment keeps raw licenses but is still stamped here, so a
			// later BOM that does enrich it won't update it (accepted limitation).
			if (sc.getEnrichedAt() != null) continue;
			List<Map<String, Object>> lic = licByCanonical.get(sc.getCanonicalPurl());
			if (lic != null && !lic.isEmpty()) sc.setLicenses(lic);
			ZonedDateTime now = ZonedDateTime.now();
			sc.setEnrichedAt(now);
			sc.setLastUpdatedDate(now);
			try {
				sbomComponentRepository.save(sc);
			} catch (OptimisticLockingFailureException | DataIntegrityViolationException ex) {
				// Lost a race with a concurrent writer (reconcile / another pull);
				// the other write wins. Re-evaluated next tick if still un-enriched.
			}
		}
	}

	/**
	 * Parse one canonical artifact's BOM via rebom and write the
	 * per-component {@code artifact_sbom_components} rows. Called only
	 * when the canonical's rows don't already exist on disk.
	 *
	 * <p>Annotated {@code @Transactional} so each canonical's persist runs
	 * in its own short-lived session — see {@link #reconcileReleaseSbomComponents}
	 * for why the outer method dropped the transaction wrapper. Must be
	 * called through Spring's proxy ({@code self.*}) for the annotation to
	 * fire.
	 */
	@Transactional
	public void parseAndUpsertArtifactSbomComponents(
			ArtifactData ad, UUID canonicalArtifactUuid, UUID orgUuid) {

		ParsedBom parsed;
		try {
			parsed = rebomService.parseBom(ad.getInternalBom().id(), orgUuid);
		} catch (Exception e) {
			log.warn("Unable to fetch parsed BOM for artifact {} (bom {}): {}",
					ad.getUuid(), ad.getInternalBom().id(), e.getMessage());
			return;
		}
		if (parsed == null) return;

		// Aggregate the components this BOM declares, keyed by canonical purl.
		Map<String, ComponentAggregation> componentAggs = new LinkedHashMap<>();
		if (parsed.components() != null) {
			for (ParsedBomComponent pc : parsed.components()) {
				if (pc == null || pc.canonicalPurl() == null) continue;
				ComponentAggregation agg = componentAggs.computeIfAbsent(
						pc.canonicalPurl(), k -> new ComponentAggregation(pc));
				agg.mergeSample(pc);
				agg.setExactPurl(pc.fullPurl());
			}
		}

		// Aggregate the in-edges (parents) keyed by target canonical purl.
		Map<String, Map<ParentKey, ParentEdge>> parentAggs = new LinkedHashMap<>();
		if (parsed.dependencies() != null) {
			for (ParsedBomDependency pd : parsed.dependencies()) {
				if (pd == null || pd.sourceCanonicalPurl() == null
						|| pd.targetCanonicalPurl() == null) continue;
				ParentKey key = new ParentKey(pd.sourceCanonicalPurl(), relationshipType(pd));
				ParentEdge edge = new ParentEdge(pd.sourceFullPurl(), pd.targetFullPurl());
				parentAggs.computeIfAbsent(pd.targetCanonicalPurl(), k -> new LinkedHashMap<>())
						.putIfAbsent(key, edge);
			}
		}

		// The raw ParsedBom can be tens of MB for large BOMs. We've extracted
		// everything we need into the smaller aggregation maps; releasing the
		// reference here lets the JVM mark it for collection while we move on
		// to the persist phase rather than waiting for the method to exit.
		parsed = null;

		if (componentAggs.isEmpty()) return;

		// Upsert canonical sbom_components and get back canonical→uuid map.
		Map<String, UUID> canonicalToUuid = upsertSbomComponents(componentAggs.values(), orgUuid);

		// Write one artifact_sbom_components row per component in this BOM.
		List<ArtifactSbomComponent> rows = new ArrayList<>(componentAggs.size());
		for (Map.Entry<String, ComponentAggregation> e : componentAggs.entrySet()) {
			String canonical = e.getKey();
			UUID componentUuid = canonicalToUuid.get(canonical);
			if (componentUuid == null) continue;

			List<Map<String, Object>> parentsJson = renderParents(
					parentAggs.get(canonical), canonicalToUuid);

			ArtifactSbomComponent row = new ArtifactSbomComponent();
			row.setOrg(orgUuid);
			row.setCanonicalArtifactUuid(canonicalArtifactUuid);
			row.setSbomComponentUuid(componentUuid);
			row.setExactPurl(e.getValue().getExactPurl() != null
					? e.getValue().getExactPurl() : canonical);
			row.setParents(parentsJson);
			rows.add(row);
		}

		// Drop the aggregation maps — no longer needed once rows are built.
		componentAggs = null;
		parentAggs = null;
		canonicalToUuid = null;

		try {
			saveAllChunked(rows, artifactSbomComponentRepository::saveAll);
		} catch (DataIntegrityViolationException dive) {
			// Lost the race with a concurrent reconcile of the same canonical —
			// per-row save in case some were inserted, ignore conflicts. Chunked
			// flush already happened on prior batches; no harm.
			for (ArtifactSbomComponent row : rows) {
				try {
					artifactSbomComponentRepository.save(row);
				} catch (DataIntegrityViolationException ignored) {
				}
			}
		}
	}

	/**
	 * Batch-save with intermediate {@code flush() + clear()} so the L1 cache
	 * doesn't hold every entity inserted by a single call. Important when
	 * one canonical artifact's BOM yields thousands of {@link ArtifactSbomComponent}
	 * rows.
	 */
	private <T> void saveAllChunked(List<T> rows, java.util.function.Function<List<T>, Iterable<T>> saver) {
		if (rows == null || rows.isEmpty()) return;
		int size = rows.size();
		for (int i = 0; i < size; i += FLUSH_CHUNK) {
			List<T> chunk = rows.subList(i, Math.min(i + FLUSH_CHUNK, size));
			saver.apply(chunk);
			entityManager.flush();
			entityManager.clear();
		}
	}

	private void markReleaseReconciled(UUID releaseUuid) {
		releaseRepository.recordSbomReconciledAtVersion(releaseUuid, CURRENT_SBOM_SCHEMA_VERSION);
	}

	// ===================================================================
	// Canonical artifact resolution (lazy, BOM artifacts only, org-scoped)
	// ===================================================================

	private UUID resolveCanonicalArtifact(ArtifactData ad, UUID orgUuid) {
		UUID artifactUuid = ad.getUuid();

		Optional<ArtifactCanonicalMap> existing =
				artifactCanonicalMapRepository.findByArtifactUuid(artifactUuid);
		if (existing.isPresent()) {
			return existing.get().getCanonicalArtifactUuid();
		}

		String rearmDigest = extractRearmDigest(ad);
		UUID canonical = artifactUuid;
		if (rearmDigest != null) {
			Optional<Artifact> match = sharedArtifactService.findArtifactByStoredDigest(orgUuid, rearmDigest);
			canonical = match.map(Artifact::getUuid).orElse(artifactUuid);
		}

		ArtifactCanonicalMap row = new ArtifactCanonicalMap();
		row.setOrg(orgUuid);
		row.setArtifactUuid(artifactUuid);
		row.setCanonicalArtifactUuid(canonical);
		try {
			artifactCanonicalMapRepository.save(row);
		} catch (DataIntegrityViolationException dive) {
			return artifactCanonicalMapRepository.findByArtifactUuid(artifactUuid)
					.map(ArtifactCanonicalMap::getCanonicalArtifactUuid)
					.orElse(artifactUuid);
		}
		return canonical;
	}

	private static String extractRearmDigest(ArtifactData ad) {
		Set<DigestRecord> drs = ad.getDigestRecords();
		if (drs == null || drs.isEmpty()) return null;
		for (DigestRecord dr : drs) {
			if (dr.scope() == DigestScope.REARM && dr.digest() != null) return dr.digest();
		}
		return null;
	}

	// ===================================================================
	// Force-reconcile (operator GraphQL mutation)
	// ===================================================================

	public void forceReconcileWithDeps(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) {
			log.warn("forceReconcileWithDeps called for missing release {}", releaseUuid);
			return;
		}
		ReleaseData rd = ord.get();
		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);
		if (isProduct) {
			for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
				try {
					self.reconcileReleaseSbomComponents(dep.getUuid());
				} catch (Exception e) {
					log.warn("Cascade reconcile of dep {} for product {} failed: {}",
							dep.getUuid(), releaseUuid, e.getMessage(), e);
				}
			}
		}
		self.reconcileReleaseSbomComponents(releaseUuid);
	}

	// ===================================================================
	// Reconcile-driven BOM-diff: acollection changelog cache,
	// snapshot safety net, and once-per-release notification
	// ===================================================================

	/**
	 * Post-reconcile pipeline, run off the drain once a release's component
	 * inventory has been rebuilt. Three responsibilities, all best-effort:
	 *
	 * <ol>
	 * <li><b>Snapshot safety net (Phase 4a):</b> re-resolve the acollection
	 *     snapshot so the artifact list TEA reads stays current. This is the
	 *     home for the catch-all that {@code ReleaseFinalizerService.finalize
	 *     Release} used to provide before finalize became a no-op.</li>
	 * <li><b>Changelog cache (Phase 2):</b> recompute the SBOM-components
	 *     changelog as a set-difference of reconcile inventories and cache it
	 *     onto {@code acollection.artifactComparison} — for both this release
	 *     (vs its predecessor) and the next release (vs this one), since this
	 *     release's inventory change invalidates the successor's diff too.
	 *     Ungated by lifecycle so DRAFT releases also show a current changelog
	 *     in the UI.</li>
	 * <li><b>Notification (Phase 1):</b> fire the once-per-release BOM-diff
	 *     alert, gated on lifecycle {@code >= ASSEMBLED} and the one-shot
	 *     {@code flow_control.bomDiffNotifiedAt} flag, reusing the self-diff
	 *     computed for the cache.</li>
	 * </ol>
	 */
	public void postReconcileBomDiff(UUID releaseUuid) {
		// (1) Snapshot safety net — independent failure domain from the diff work.
		// Cheap-on-no-change: skips the per-artifact rebom resolve when the
		// artifact set is unchanged (the common case, since mutations already
		// resolve the snapshot synchronously via saveRelease).
		try {
			acollectionService.resolveReleaseCollectionIfArtifactsChanged(releaseUuid, WhoUpdated.getAutoWhoUpdated());
		} catch (Exception e) {
			log.warn("post-reconcile acollection resolve failed for release {}: {}",
					releaseUuid, e.getMessage());
		}

		try {
			// Light read: this path only needs record-data fields (branch,
			// lifecycle, org, component) for the diff + notification gate — no
			// metrics detail arrays or approval/update events.
			Optional<ReleaseData> ord = sharedReleaseService.getReleaseDataLight(releaseUuid);
			if (ord.isEmpty()) return;
			ReleaseData rd = ord.get();
			UUID branch = rd.getBranch();

			Map<String, DiffComponent> selfInventory = collectReleaseInventory(releaseUuid);

			// (2a) This release vs its lineage predecessor → cache + notification baseline.
			ArtifactChangelog selfDiff = null;
			UUID prevReleaseUuid = sharedReleaseService
					.findPreviousReleasesOfBranchForRelease(branch, releaseUuid);
			if (prevReleaseUuid != null) {
				Map<String, DiffComponent> prevInventory = collectReleaseInventory(prevReleaseUuid);
				// Only diff against a populated baseline — an empty predecessor
				// inventory usually means it just isn't reconciled yet, and
				// caching an all-"added" diff would be misleading.
				if (!selfInventory.isEmpty() && !prevInventory.isEmpty()) {
					selfDiff = diffInventories(selfInventory, prevInventory);
					acollectionService.cacheReleaseChangelog(releaseUuid, prevReleaseUuid, selfDiff);
				}
			}

			// (2b) Next release vs this one → keep the successor's cached diff fresh.
			UUID nextReleaseUuid = sharedReleaseService
					.findNextReleasesOfBranchForRelease(branch, releaseUuid);
			if (nextReleaseUuid != null) {
				Map<String, DiffComponent> nextInventory = collectReleaseInventory(nextReleaseUuid);
				if (!nextInventory.isEmpty() && !selfInventory.isEmpty()) {
					ArtifactChangelog nextDiff = diffInventories(nextInventory, selfInventory);
					acollectionService.cacheReleaseChangelog(nextReleaseUuid, releaseUuid, nextDiff);
				}
			}

			// (3) Once-per-release notification, reusing the self-diff.
			maybeFireBomDiffNotification(rd, selfDiff);
		} catch (Exception e) {
			log.warn("post-reconcile BOM-diff cache/notification failed for release {}: {}",
					releaseUuid, e.getMessage(), e);
		}
	}

	/**
	 * Once-per-release BOM-diff notification. Gate: lifecycle {@code >=
	 * ASSEMBLED} (the all-BOMs-uploaded signal) and the one-shot
	 * {@code flow_control.bomDiffNotifiedAt} flag unset. A valid {@code
	 * selfDiff} (non-null — i.e. a populated baseline was available) is
	 * required, so we don't burn the one-shot claim before a comparable
	 * predecessor inventory exists; a later reconcile retries. The atomic
	 * claim then fires {@link ReleaseChangeHook#onReleaseBomDiff} (whose
	 * SAAS impl no-ops unless the diff has both additions and removals).
	 */
	private void maybeFireBomDiffNotification(ReleaseData rd, ArtifactChangelog selfDiff) {
		if (selfDiff == null) return;
		ReleaseLifecycle lifecycle = rd.getLifecycle();
		if (lifecycle == null || lifecycle.ordinal() < ReleaseLifecycle.ASSEMBLED.ordinal()) return;
		// Atomic one-shot claim: 0 rows affected => a prior reconcile already notified.
		if (releaseRepository.claimBomDiffNotification(rd.getUuid()) == 0) return;
		if (releaseChangeHook != null) releaseChangeHook.onReleaseBomDiff(rd, selfDiff);
	}

	/**
	 * Set-difference two release inventories into an {@link ArtifactChangelog}:
	 * components present in {@code curr} but not {@code prev} are added,
	 * those present in {@code prev} but not {@code curr} are removed. Both maps
	 * are keyed by canonical purl (version-included), so a version bump shows
	 * as a remove of the old + add of the new — matching rebom's prior diff.
	 */
	private static ArtifactChangelog diffInventories(
			Map<String, DiffComponent> curr, Map<String, DiffComponent> prev) {
		Set<DiffComponent> added = new LinkedHashSet<>();
		for (Map.Entry<String, DiffComponent> e : curr.entrySet()) {
			if (!prev.containsKey(e.getKey())) added.add(e.getValue());
		}
		Set<DiffComponent> removed = new LinkedHashSet<>();
		for (Map.Entry<String, DiffComponent> e : prev.entrySet()) {
			if (!curr.containsKey(e.getKey())) removed.add(e.getValue());
		}
		return new ArtifactChangelog(added, removed);
	}

	/**
	 * Lean inventory for a release: maps each canonical purl (version-included)
	 * the release's BOM artifacts declare to a {@link DiffComponent} carrying
	 * that same canonical purl plus the version — matching the shape rebom's
	 * {@code bomDiff} produced (full purl in {@code purl}, version alongside),
	 * so cached changelogs and the UI rendering are unchanged. Avoids the full
	 * per-component participation/parent synthesis of
	 * {@link #listReleaseSbomComponents}; resolution mirrors that method
	 * (product releases fold in their transitive dependency inventories).
	 */
	public Map<String, DiffComponent> collectReleaseInventory(UUID releaseUuid) {
		// Light read: only org / component-type / dependency record-data fields are
		// used to resolve the canonical-purl set — no metrics detail or events.
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseDataLight(releaseUuid);
		if (ord.isEmpty()) return Map.of();
		ReleaseData rd = ord.get();
		UUID orgUuid = rd.getOrg();
		if (orgUuid == null) return Map.of();

		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);

		Set<UUID> sourceReleaseUuids = new LinkedHashSet<>();
		sourceReleaseUuids.add(releaseUuid);
		if (isProduct) {
			for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
				sourceReleaseUuids.add(dep.getUuid());
			}
		}

		Set<UUID> canonicalSet = new LinkedHashSet<>();
		for (ReleaseArtifactIndex idx :
				releaseArtifactIndexRepository.findByOrgAndReleaseUuidIn(orgUuid, sourceReleaseUuids)) {
			canonicalSet.add(idx.getCanonicalArtifactUuid());
		}
		if (canonicalSet.isEmpty()) return Map.of();

		List<ArtifactSbomComponent> rows = artifactSbomComponentRepository
				.findByOrgAndCanonicalArtifactUuidIn(orgUuid, canonicalSet);
		if (rows.isEmpty()) return Map.of();

		Set<UUID> componentIds = new LinkedHashSet<>();
		for (ArtifactSbomComponent r : rows) componentIds.add(r.getSbomComponentUuid());

		Map<UUID, SbomComponent> comps = findSbomComponentsByIds(componentIds, orgUuid);
		Map<String, DiffComponent> out = new LinkedHashMap<>();
		for (SbomComponent sc : comps.values()) {
			// Root components are the release's own top-level identity (the BOM's
			// metadata.component), not consumed dependencies — exclude them so a
			// release's own purl doesn't surface as an added/removed component.
			if (isMarkedRoot(sc)) continue;
			String canonicalPurl = sc.getCanonicalPurl();
			if (canonicalPurl == null) continue;
			String version = SbomComponentData.dataFromRecord(sc).version();
			out.put(canonicalPurl, new DiffComponent(canonicalPurl, version));
		}
		return out;
	}

	// ===================================================================
	// Read API — synthesize per-release view from artifact-keyed rows
	// ===================================================================

	public List<ReleaseSbomComponent> listReleaseSbomComponents(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) return List.of();
		ReleaseData rd = ord.get();
		UUID orgUuid = rd.getOrg();
		if (orgUuid == null) return List.of();

		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);

		Set<UUID> sourceReleaseUuids = new LinkedHashSet<>();
		sourceReleaseUuids.add(releaseUuid);
		if (isProduct) {
			for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
				sourceReleaseUuids.add(dep.getUuid());
			}
		}

		// Resolve the union of canonical artifacts referenced by the release
		// (and, for products, every transitive dep).
		Set<UUID> canonicalSet = new LinkedHashSet<>();
		for (ReleaseArtifactIndex idx :
				releaseArtifactIndexRepository.findByOrgAndReleaseUuidIn(orgUuid, sourceReleaseUuids)) {
			canonicalSet.add(idx.getCanonicalArtifactUuid());
		}
		if (canonicalSet.isEmpty()) return List.of();

		// Bulk-fetch every artifact_sbom_components row for the canonical set.
		List<ArtifactSbomComponent> rows = artifactSbomComponentRepository
				.findByOrgAndCanonicalArtifactUuidIn(orgUuid, canonicalSet);
		if (rows.isEmpty()) return List.of();

		// Group rows by sbom_component_uuid to build the per-release view.
		Map<UUID, List<ArtifactSbomComponent>> byComponent = new LinkedHashMap<>();
		for (ArtifactSbomComponent r : rows) {
			byComponent.computeIfAbsent(r.getSbomComponentUuid(), k -> new ArrayList<>()).add(r);
		}

		// Bulk-fetch canonical components referenced by rows + their parents
		// (we need their canonical purls for the rendered parent entries).
		Set<UUID> referencedIds = new HashSet<>(byComponent.keySet());
		for (ArtifactSbomComponent r : rows) {
			if (r.getParents() != null) {
				for (Map<String, Object> p : r.getParents()) {
					UUID src = parseUuid(p.get("sourceSbomComponentUuid"));
					if (src != null) referencedIds.add(src);
				}
			}
		}
		Map<UUID, SbomComponent> sbomCompByUuid = findSbomComponentsByIds(referencedIds, orgUuid);

		List<ReleaseSbomComponent> result = new ArrayList<>(byComponent.size());
		for (Map.Entry<UUID, List<ArtifactSbomComponent>> e : byComponent.entrySet()) {
			result.add(mergeReleaseRow(orgUuid, releaseUuid, e.getKey(), e.getValue(), sbomCompByUuid));
		}
		return result;
	}

	/**
	 * Build one synthesized per-(release, sbom_component) view by unioning
	 * the contributing canonical artifacts' rows. Each row contributes one
	 * artifact participation; each row's parents array contributes a set of
	 * declarations to the unioned parent edges.
	 */
	private ReleaseSbomComponent mergeReleaseRow(
			UUID orgUuid,
			UUID releaseUuid,
			UUID sbomComponentUuid,
			List<ArtifactSbomComponent> sourceRows,
			Map<UUID, SbomComponent> sbomCompByUuid) {

		// artifactParticipations: one entry per contributing canonical artifact.
		Map<UUID, Map<String, Object>> participationsByArtifact = new LinkedHashMap<>();
		// parents: keyed by (sourceSbomComponentUuid, relationshipType) — one
		// entry per logical edge, with declaringArtifacts collected from each
		// contributing artifact row.
		Map<String, Map<String, Object>> parentsByKey = new LinkedHashMap<>();
		ZonedDateTime earliestParsed = null;
		ZonedDateTime latestParsed = null;

		for (ArtifactSbomComponent src : sourceRows) {
			if (src.getParsedAt() != null) {
				if (earliestParsed == null || src.getParsedAt().isBefore(earliestParsed)) earliestParsed = src.getParsedAt();
				if (latestParsed == null || src.getParsedAt().isAfter(latestParsed)) latestParsed = src.getParsedAt();
			}

			// Participation: this artifact (canonical) referenced the component
			// using exact_purl.
			Map<String, Object> participation = participationsByArtifact
					.computeIfAbsent(src.getCanonicalArtifactUuid(), k -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("artifact", k.toString());
						m.put("exactPurls", new ArrayList<String>());
						return m;
					});
			@SuppressWarnings("unchecked")
			List<String> exactPurls = (List<String>) participation.get("exactPurls");
			if (!exactPurls.contains(src.getExactPurl())) exactPurls.add(src.getExactPurl());

			// Parents: each entry in the artifact's parents array becomes a
			// declaringArtifacts entry under the unioned edge.
			if (src.getParents() != null) {
				for (Map<String, Object> p : src.getParents()) {
					if (p == null) continue;
					String sourceCanonicalPurl = String.valueOf(p.get("sourceCanonicalPurl"));
					String relationshipType = String.valueOf(p.get("relationshipType"));
					String key = sourceCanonicalPurl + " " + relationshipType;
					Map<String, Object> publicEntry = parentsByKey.computeIfAbsent(key, k -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("sourceSbomComponentUuid", p.get("sourceSbomComponentUuid"));
						m.put("sourceCanonicalPurl", sourceCanonicalPurl);
						m.put("relationshipType", relationshipType);
						m.put("declaringArtifacts", new ArrayList<Map<String, Object>>());
						return m;
					});
					@SuppressWarnings("unchecked")
					List<Map<String, Object>> decls = (List<Map<String, Object>>) publicEntry.get("declaringArtifacts");
					Map<String, Object> decl = new LinkedHashMap<>();
					decl.put("artifact", src.getCanonicalArtifactUuid().toString());
					decl.put("sourceExactPurl", p.get("sourceExactPurl"));
					decl.put("targetExactPurl", p.get("targetExactPurl"));
					String declKey = declarationKey(decl);
					boolean seen = false;
					for (Map<String, Object> existing : decls) {
						if (declarationKey(existing).equals(declKey)) { seen = true; break; }
					}
					if (!seen) decls.add(decl);
				}
			}
		}

		// Stable sort: participations by artifact uuid, parents by source
		// canonical purl + relationship.
		List<Map<String, Object>> participationsSorted = new ArrayList<>(participationsByArtifact.values());
		participationsSorted.sort((a, b) -> String.valueOf(a.get("artifact")).compareTo(String.valueOf(b.get("artifact"))));
		// Sort exact purls inside each participation entry deterministically.
		for (Map<String, Object> part : participationsSorted) {
			@SuppressWarnings("unchecked")
			List<String> purls = (List<String>) part.get("exactPurls");
			Collections.sort(purls);
		}

		List<Map<String, Object>> parentsSorted = new ArrayList<>(parentsByKey.values());
		parentsSorted.sort((a, b) -> {
			String sa = String.valueOf(a.get("sourceCanonicalPurl"));
			String sb = String.valueOf(b.get("sourceCanonicalPurl"));
			int by = sa.compareTo(sb);
			if (by != 0) return by;
			return String.valueOf(a.get("relationshipType")).compareTo(String.valueOf(b.get("relationshipType")));
		});

		ReleaseSbomComponent merged = new ReleaseSbomComponent();
		merged.setUuid(syntheticReleaseRowUuid(releaseUuid, sbomComponentUuid));
		merged.setOrg(orgUuid);
		merged.setReleaseUuid(releaseUuid);
		merged.setSbomComponentUuid(sbomComponentUuid);
		merged.setArtifactParticipations(participationsSorted);
		merged.setParents(parentsSorted);
		if (earliestParsed != null) merged.setCreatedDate(earliestParsed);
		if (latestParsed != null) merged.setLastUpdatedDate(latestParsed);
		return merged;
	}

	private static UUID syntheticReleaseRowUuid(UUID releaseUuid, UUID sbomComponentUuid) {
		String key = releaseUuid.toString() + ":" + sbomComponentUuid.toString();
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
	}

	private static String declarationKey(Map<String, Object> declaration) {
		return String.valueOf(declaration.get("artifact"))
				+ " " + String.valueOf(declaration.get("sourceExactPurl"))
				+ " " + String.valueOf(declaration.get("targetExactPurl"));
	}

	private static UUID parseUuid(Object value) {
		if (value == null) return null;
		if (value instanceof UUID u) return u;
		if (value instanceof String s && !s.isBlank()) {
			try { return UUID.fromString(s); } catch (IllegalArgumentException iae) { return null; }
		}
		return null;
	}

	// ===================================================================
	// Misc public API
	// ===================================================================

	public Optional<SbomComponent> getSbomComponent(UUID uuid) {
		return sbomComponentRepository.findById(uuid);
	}

	public Map<UUID, SbomComponent> findSbomComponentsByIds(Collection<UUID> ids, UUID orgUuid) {
		if (ids == null || ids.isEmpty() || orgUuid == null) return Map.of();
		Map<UUID, SbomComponent> out = new LinkedHashMap<>();
		sbomComponentRepository.findAllById(ids).forEach(sc -> {
			if (orgUuid.equals(sc.getOrg())) out.put(sc.getUuid(), sc);
		});
		return out;
	}

	/**
	 * No persisted per-(release, sbom_component) row exists since V37 —
	 * the synthesized view is built on demand. Returns empty for code paths
	 * that still call this lookup directly; the GraphQL surface is served
	 * via {@link #listReleaseSbomComponents(UUID)}.
	 */
	public Optional<ReleaseSbomComponent> getReleaseSbomComponent(UUID uuid) {
		return Optional.empty();
	}

	public record SbomComponentSearchQuery(String name, String version) {}

	public record ComponentPurlToSbom(String purl, List<UUID> sbomComponents) {}

	public List<ComponentPurlToSbom> searchSbomComponentsBatch(
			List<SbomComponentSearchQuery> queries, UUID orgUuid) {
		if (queries == null || queries.isEmpty() || orgUuid == null) return List.of();
		String orgUuidStr = orgUuid.toString();
		Map<String, Set<UUID>> byCanonical = new LinkedHashMap<>();
		for (SbomComponentSearchQuery q : queries) {
			if (q == null || q.name() == null || q.name().isBlank()) continue;
			List<SbomComponent> matches = sbomComponentRepository
					.searchByOrgAndNameAndOptionalVersion(orgUuidStr, q.name(), q.version());
			for (SbomComponent sc : matches) {
				byCanonical.computeIfAbsent(sc.getCanonicalPurl(), k -> new LinkedHashSet<>())
						.add(sc.getUuid());
			}
		}
		List<ComponentPurlToSbom> out = new ArrayList<>(byCanonical.size());
		for (Map.Entry<String, Set<UUID>> e : byCanonical.entrySet()) {
			out.add(new ComponentPurlToSbom(e.getKey(), new ArrayList<>(e.getValue())));
		}
		return out;
	}

	public UUID searchSbomComponentByPurl(String purl, UUID orgUuid) {
		if (orgUuid == null) return null;
		String canonical = io.reliza.common.Utils.canonicalizePurl(purl);
		if (canonical == null) return null;
		return sbomComponentRepository.findByOrgAndCanonicalPurl(orgUuid, canonical)
				.map(SbomComponent::getUuid)
				.orElse(null);
	}

	public boolean releaseHasSbomComponents(UUID releaseUuid, UUID orgUuid) {
		if (orgUuid == null || releaseUuid == null) return false;
		return releaseArtifactIndexRepository.existsByOrgAndReleaseUuid(orgUuid, releaseUuid);
	}

	/**
	 * Impact analysis: distinct release UUIDs that reference any canonical
	 * artifact whose BOM contains any of the given canonical sbom_components.
	 * Walks upward through {@code locateAllProductsOfRelease} so product
	 * releases bundling affected component releases also surface.
	 */
	public Set<UUID> findReleaseUuidsBySbomComponents(Collection<UUID> sbomComponentUuids, UUID orgUuid) {
		if (sbomComponentUuids == null || sbomComponentUuids.isEmpty() || orgUuid == null) return Set.of();
		List<UUID> directReleaseUuids = artifactSbomComponentRepository
				.findDistinctReleaseUuidsByOrgAndSbomComponentUuidIn(orgUuid, sbomComponentUuids);
		if (directReleaseUuids.isEmpty()) return Set.of();
		Set<UUID> all = new LinkedHashSet<>(directReleaseUuids);
		Set<UUID> productCircleBreaker = new HashSet<>();
		for (UUID seed : directReleaseUuids) {
			sharedReleaseService.getReleaseData(seed, orgUuid).ifPresent(rd -> {
				for (ReleaseData product : sharedReleaseService.locateAllProductsOfRelease(rd, productCircleBreaker, orgUuid)) {
					all.add(product.getUuid());
				}
			});
		}
		return all;
	}

	// ===================================================================
	// BOM artifact collection (unchanged from V25 — same set of sources)
	// ===================================================================

	private Set<UUID> collectBomArtifactUuids(ReleaseData rd) {
		Set<UUID> artifactUuids = new LinkedHashSet<>();

		List<UUID> deliverableUuids = new ArrayList<>();
		if (rd.getInboundDeliverables() != null) deliverableUuids.addAll(rd.getInboundDeliverables());
		variantService.findBaseVariantForRelease(rd.getUuid())
				.ifPresent(v -> deliverableUuids.addAll(v.getOutboundDeliverables()));
		for (DeliverableData dd : getDeliverableService.getDeliverableDataList(deliverableUuids)) {
			if (dd.getArtifacts() != null) artifactUuids.addAll(dd.getArtifacts());
		}

		if (rd.getSourceCodeEntry() != null) {
			getSourceCodeEntryService.getSourceCodeEntryData(rd.getSourceCodeEntry())
					.ifPresent(sce -> {
						if (sce.getArtifacts() != null) {
							sce.getArtifacts().stream()
									.filter(scea -> rd.getComponent().equals(scea.componentUuid()))
									.forEach(scea -> artifactUuids.add(scea.artifactUuid()));
						}
					});
		}
		if (rd.getArtifacts() != null) artifactUuids.addAll(rd.getArtifacts());
		return artifactUuids;
	}

	// ===================================================================
	// Canonical sbom_components upsert (unchanged from V25)
	// ===================================================================

	private Map<String, UUID> upsertSbomComponents(
			Collection<ComponentAggregation> aggs, UUID orgUuid) {
		List<String> canonicals = new ArrayList<>();
		for (ComponentAggregation agg : aggs) canonicals.add(agg.sample.canonicalPurl());

		Map<String, UUID> canonicalToUuid = new HashMap<>();
		Map<String, SbomComponent> existingByCanonical = new HashMap<>();
		for (SbomComponent sc :
				sbomComponentRepository.findByOrgAndCanonicalPurlIn(orgUuid.toString(), canonicals)) {
			existingByCanonical.put(sc.getCanonicalPurl(), sc);
			canonicalToUuid.put(sc.getCanonicalPurl(), sc.getUuid());
		}

		for (ComponentAggregation agg : aggs) {
			String canonical = agg.sample.canonicalPurl();
			SbomComponent existing = existingByCanonical.get(canonical);
			if (existing != null) {
				boolean changed = false;
				if (agg.isRoot && !isMarkedRoot(existing)) {
					Map<String, Object> rec = existing.getRecordData() != null
							? new HashMap<>(existing.getRecordData())
							: new HashMap<>();
					rec.put("isRoot", true);
					existing.setRecordData(rec);
					changed = true;
				}
				// Union any newly-asserted identities (e.g. a CPE a prior ingest
				// lacked); only write when the set actually grew.
				List<ComponentIdentity> mergedIds = mergeIdentities(
						existing.getIdentities(), buildIdentities(canonical, agg.cpes));
				if (mergedIds != null) { existing.setIdentities(mergedIds); changed = true; }
				// Licenses: reconcile only fills when the row has none — it never
				// overwrites. The enrichment puller is the sole writer of enriched
				// licenses (alongside enriched_at), so a raw re-parse must not clobber
				// a value the puller already set.
				if (agg.getLicenses() != null && !agg.getLicenses().isEmpty()) {
					boolean empty = existing.getLicenses() == null || existing.getLicenses().isEmpty();
					if (empty) {
						existing.setLicenses(agg.getLicenses());
						changed = true;
					}
				}
				if (changed) {
					existing.setLastUpdatedDate(ZonedDateTime.now());
					try { sbomComponentRepository.save(existing); }
					catch (DataIntegrityViolationException ignored) {}
				}
				continue;
			}
			SbomComponent sc = buildSbomComponent(agg, orgUuid);
			try {
				sc = sbomComponentRepository.save(sc);
				canonicalToUuid.put(canonical, sc.getUuid());
			} catch (DataIntegrityViolationException dive) {
				sbomComponentRepository.findByOrgAndCanonicalPurl(orgUuid, canonical)
						.ifPresent(rec -> canonicalToUuid.put(canonical, rec.getUuid()));
			}
		}
		return canonicalToUuid;
	}

	private boolean isMarkedRoot(SbomComponent sc) {
		return sc.isRoot();
	}

	private SbomComponent buildSbomComponent(ComponentAggregation agg, UUID orgUuid) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(orgUuid);
		sc.setCanonicalPurl(agg.sample.canonicalPurl());
		SbomComponentData data = new SbomComponentData(
				agg.sample.type(), agg.sample.group(), agg.sample.name(),
				agg.sample.version(), agg.isRoot ? Boolean.TRUE : null);
		sc.setRecordData(data.toRecordMap());
		sc.setIdentities(buildIdentities(agg.sample.canonicalPurl(), agg.cpes));
		if (agg.getLicenses() != null && !agg.getLicenses().isEmpty()) {
			sc.setLicenses(agg.getLicenses());
		}
		return sc;
	}

	/**
	 * Assemble the flat {scheme,value} identity union for a canonical component:
	 * the canonical primary identity (its scheme inferred from the prefix) plus
	 * every distinct CPE. De-duped by {scheme,value}, insertion-ordered so the
	 * primary stays first. Synthesised backend-side for now; will be replaced by
	 * rebom's own identities array (see Rebom.ParsedBomComponent TODO).
	 */
	private static List<ComponentIdentity> buildIdentities(
			String canonicalPurl, java.util.Collection<String> cpes) {
		List<ComponentIdentity> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		if (canonicalPurl != null && !canonicalPurl.isBlank()) {
			addIdentity(out, seen, schemeOf(canonicalPurl), canonicalPurl);
		}
		if (cpes != null) {
			for (String cpe : cpes) {
				if (cpe != null && !cpe.isBlank()) addIdentity(out, seen, "cpe", cpe);
			}
		}
		return out;
	}

	private static void addIdentity(List<ComponentIdentity> out,
			java.util.Set<String> seen, String scheme, String value) {
		String key = scheme + '\u0001' + value;
		if (!seen.add(key)) return;
		out.add(new ComponentIdentity(scheme, value));
	}

	/** Infer the identity scheme from a self-namespacing canonical identity. */
	private static String schemeOf(String canonical) {
		if (canonical.startsWith("pkg:")) return "purl";
		if (canonical.startsWith("cpe:")) return "cpe";
		if (canonical.startsWith("swid:")) return "swid";
		if (canonical.startsWith("swhid:")) return "swhid";
		if (canonical.startsWith("gitoid:")) return "omniborid";
		if (canonical.startsWith("cdx:")) return "cdx";
		return "purl";
	}

	/**
	 * Union {@code incoming} identity entries into {@code existing} (de-dupe by
	 * {scheme,value}), returning the merged list when it grew, or null when no
	 * new identity was added (so callers can skip a no-op write).
	 */
	private static List<ComponentIdentity> mergeIdentities(
			List<ComponentIdentity> existing, List<ComponentIdentity> incoming) {
		if (incoming == null || incoming.isEmpty()) return null;
		List<ComponentIdentity> merged = existing != null
				? new ArrayList<>(existing) : new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (ComponentIdentity e : merged) {
			seen.add(e.scheme() + '\u0001' + e.value());
		}
		boolean changed = false;
		for (ComponentIdentity e : incoming) {
			String key = e.scheme() + '\u0001' + e.value();
			if (seen.add(key)) { merged.add(e); changed = true; }
		}
		return changed ? merged : null;
	}

	/**
	 * Render the parents JSONB for one target component within one BOM:
	 * one entry per (source canonical, relationshipType). The
	 * declaringArtifacts wrapper from the prior per-release shape is gone
	 * — the row's {@code canonical_artifact_uuid} field IS the declaring
	 * artifact. Source canonical UUIDs are resolved from the canonical→uuid
	 * map; entries whose source can't be resolved are dropped.
	 */
	private List<Map<String, Object>> renderParents(
			Map<ParentKey, ParentEdge> edges,
			Map<String, UUID> canonicalToUuid) {
		if (edges == null || edges.isEmpty()) return new ArrayList<>();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<ParentKey, ParentEdge> e : edges.entrySet()) {
			UUID sourceUuid = canonicalToUuid.get(e.getKey().sourceCanonical);
			if (sourceUuid == null) continue;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("sourceSbomComponentUuid", sourceUuid.toString());
			entry.put("sourceCanonicalPurl", e.getKey().sourceCanonical);
			entry.put("relationshipType", e.getKey().relationshipType);
			entry.put("sourceExactPurl", e.getValue().sourceFullPurl);
			entry.put("targetExactPurl", e.getValue().targetFullPurl);
			out.add(entry);
		}
		out.sort((a, b) -> {
			int bySource = String.valueOf(a.get("sourceCanonicalPurl"))
					.compareTo(String.valueOf(b.get("sourceCanonicalPurl")));
			if (bySource != 0) return bySource;
			return String.valueOf(a.get("relationshipType"))
					.compareTo(String.valueOf(b.get("relationshipType")));
		});
		return out;
	}

	private static String relationshipType(ParsedBomDependency pd) {
		String raw = pd.relationshipType();
		if (raw == null || raw.isBlank()) return "DEPENDS_ON";
		return raw.toUpperCase();
	}

	// ===================================================================
	// Aggregation buckets (per artifact)
	// ===================================================================

	private static final class ComponentAggregation {
		final ParsedBomComponent sample;
		boolean isRoot;
		private String exactPurl;
		// Union of distinct CPE coordinates seen for this canonical across the
		// BOM's components (NVD aliases / divergent assertions). Insertion-ordered
		// so the first-seen CPE stays the primary on the synthetic component.
		final java.util.LinkedHashSet<String> cpes = new java.util.LinkedHashSet<>();
		// First non-empty declared licenses (exact CycloneDX shape), carried
		// transiently for re-emission to Dependency-Track.
		private List<Map<String, Object>> licenses;

		ComponentAggregation(ParsedBomComponent sample) {
			this.sample = sample;
			this.isRoot = Boolean.TRUE.equals(sample.isRoot());
			mergeSample(sample);
		}

		void mergeSample(ParsedBomComponent other) {
			if (Boolean.TRUE.equals(other.isRoot())) this.isRoot = true;
			if (other.cpe() != null && !other.cpe().isBlank()) this.cpes.add(other.cpe());
			if ((this.licenses == null || this.licenses.isEmpty())
					&& other.licenses() != null && !other.licenses().isEmpty()) {
				this.licenses = other.licenses();
			}
		}

		void setExactPurl(String purl) {
			if (this.exactPurl == null || purl != null) this.exactPurl = purl;
		}

		String getExactPurl() { return exactPurl; }

		List<Map<String, Object>> getLicenses() { return licenses; }
	}

	private record ParentKey(String sourceCanonical, String relationshipType) {}

	private record ParentEdge(String sourceFullPurl, String targetFullPurl) {}
}
