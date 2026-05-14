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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.FlowControl;
import io.reliza.model.PurlQualifier;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.ReleaseSbomComponentArtifact;
import io.reliza.model.ReleaseSbomEdge;
import io.reliza.model.SbomComponent;
import io.reliza.model.tea.Rebom.ParsedBom;
import io.reliza.model.tea.Rebom.ParsedBomComponent;
import io.reliza.model.tea.Rebom.ParsedBomDependency;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.PurlQualifierRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.ReleaseSbomComponentArtifactRepository;
import io.reliza.repositories.ReleaseSbomComponentRepository;
import io.reliza.repositories.ReleaseSbomEdgeRepository;
import io.reliza.repositories.SbomComponentRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains the SBOM component aggregation tables. Rebom parses components
 * and dependency edges out of each uploaded BOM; reconciliation aggregates
 * both per release and rebuilds the release's component / participation /
 * edge rows from scratch on every reconcile call.
 *
 * <p>Schema (post V37 — normalized):
 * <ul>
 *   <li>{@code sbom_components} — canonical per-(org, canonical_purl) row
 *       with frequently-read metadata (type / group / name / version /
 *       is_root) in typed columns.</li>
 *   <li>{@code release_sbom_components} — per-(release, canonical) anchor
 *       row; no JSONB content of its own.</li>
 *   <li>{@code release_sbom_component_artifacts} — one row per (release,
 *       canonical component, declaring artifact, exact PURL). Replaces the
 *       prior {@code artifactParticipations} JSONB array. Declaring
 *       artifact UUIDs are resolved through {@code artifact_canonical_map}
 *       to canonical form, so two releases that uploaded the same BOM
 *       collapse onto one canonical artifact UUID in the rows.</li>
 *   <li>{@code release_sbom_edges} — one row per (release, source canonical,
 *       target canonical, relationship, declaring artifact, exact source /
 *       target PURLs). Replaces the prior {@code parents} JSONB array.</li>
 *   <li>{@code purl_qualifiers} — per-org intern for full PURLs with
 *       qualifiers / subpaths. Null FK in child rows means exact == canonical.</li>
 *   <li>{@code artifact_canonical_map} — lazy per-org pointer from each BOM
 *       artifact UUID to its content-canonical counterpart. Populated only
 *       when the SBOM reconcile path needs it. No FK to the artifacts
 *       table: dangling references surface as "no data found" + log.warn.</li>
 * </ul>
 *
 * <p>The public {@link ReleaseSbomComponent} type still exposes
 * {@code artifactParticipations} / {@code parents} as map-shaped lists, but
 * those are populated as <em>transient</em> fields at read time from the
 * normalized child tables — the GraphQL surface is unchanged from the prior
 * JSONB-backed implementation.
 *
 * <p>Reconciliation is queued via {@code releases.flow_control} and drained
 * by the every-minute scheduler; on success the reconciler stamps
 * {@code releases.sbom_schema_version} with {@link #CURRENT_SBOM_SCHEMA_VERSION}
 * — the "did this release's aggregation use the current schema?" cookie that
 * future migrations can bump in code (no Flyway re-enqueue UPDATE needed)
 * to force fresh reconciles of stale rows.
 */
@Slf4j
@Service
public class SbomComponentService {

	/**
	 * Bumped whenever the on-disk SBOM aggregation layout changes. The
	 * reconciler stamps this onto {@code releases.sbom_schema_version} on
	 * success. Future migrations can either re-enqueue everything via
	 * flow_control (V25 / V27 / V28 / V37 pattern) or simply bump this
	 * constant — the catch-up scheduler can then enqueue any release
	 * whose stored version is below the current value.
	 */
	public static final int CURRENT_SBOM_SCHEMA_VERSION = 1;

	@Autowired
	private RebomService rebomService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private ArtifactService artifactService;

	@Autowired
	private SharedArtifactService sharedArtifactService;

	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;

	@Autowired
	private GetDeliverableService getDeliverableService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private VariantService variantService;

	@Autowired
	private ReleaseRepository releaseRepository;

	@Autowired
	@Lazy
	private SbomComponentService self;

	private static final int BASE_BACKOFF_SECONDS = 30;
	private static final int MAX_BACKOFF_SECONDS = 3600;

	private final SbomComponentRepository sbomComponentRepository;
	private final ReleaseSbomComponentRepository releaseSbomComponentRepository;
	private final ReleaseSbomComponentArtifactRepository releaseSbomComponentArtifactRepository;
	private final ReleaseSbomEdgeRepository releaseSbomEdgeRepository;
	private final PurlQualifierRepository purlQualifierRepository;
	private final ArtifactCanonicalMapRepository artifactCanonicalMapRepository;

	SbomComponentService(
			SbomComponentRepository sbomComponentRepository,
			ReleaseSbomComponentRepository releaseSbomComponentRepository,
			ReleaseSbomComponentArtifactRepository releaseSbomComponentArtifactRepository,
			ReleaseSbomEdgeRepository releaseSbomEdgeRepository,
			PurlQualifierRepository purlQualifierRepository,
			ArtifactCanonicalMapRepository artifactCanonicalMapRepository) {
		this.sbomComponentRepository = sbomComponentRepository;
		this.releaseSbomComponentRepository = releaseSbomComponentRepository;
		this.releaseSbomComponentArtifactRepository = releaseSbomComponentArtifactRepository;
		this.releaseSbomEdgeRepository = releaseSbomEdgeRepository;
		this.purlQualifierRepository = purlQualifierRepository;
		this.artifactCanonicalMapRepository = artifactCanonicalMapRepository;
	}

	// ===================================================================
	// Queue API
	// ===================================================================

	public void requestReconcile(UUID releaseUuid) {
		if (releaseUuid == null) return;
		releaseRepository.markSbomReconcileRequested(releaseUuid);
	}

	public void processPendingReconciles(int batchLimit) {
		List<Release> pending = releaseRepository.findReleasesPendingSbomReconcile(batchLimit);
		if (pending.isEmpty()) return;
		log.debug("Draining {} pending SBOM reconciles", pending.size());
		for (Release r : pending) {
			UUID releaseUuid = r.getUuid();
			try {
				self.reconcileReleaseSbomComponents(releaseUuid);
				releaseRepository.clearSbomReconcileRequested(releaseUuid);
			} catch (Exception e) {
				int nextAttempt = currentReconcileFailureCount(r) + 1;
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
	// Reconcile — writes the normalized rows.
	// ===================================================================

	@Transactional
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

		// Aggregate keyed by TARGET canonical purl.
		Map<String, ComponentAggregation> componentAggs = new LinkedHashMap<>();
		// Edges keyed by target canonical → (source canonical, relationship) → declarations.
		Map<String, Map<ParentKey, ParentAggregation>> parentAggs = new LinkedHashMap<>();

		for (UUID artifactUuid : collectBomArtifactUuids(rd)) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isEmpty()) continue;
			ArtifactData ad = oad.get();
			if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) continue;

			UUID canonicalArtifactUuid = resolveCanonicalArtifact(ad, orgUuid);

			ParsedBom parsed;
			try {
				parsed = rebomService.parseBom(ad.getInternalBom().id(), orgUuid);
			} catch (Exception e) {
				log.warn("Unable to fetch parsed BOM for artifact {} (bom {}): {}",
						artifactUuid, ad.getInternalBom().id(), e.getMessage());
				continue;
			}
			if (parsed == null) continue;

			if (parsed.components() != null) {
				for (ParsedBomComponent pc : parsed.components()) {
					if (pc == null || pc.canonicalPurl() == null) continue;
					ComponentAggregation agg = componentAggs.computeIfAbsent(
							pc.canonicalPurl(), k -> new ComponentAggregation(pc));
					agg.mergeSample(pc);
					agg.addParticipation(canonicalArtifactUuid, pc.fullPurl());
				}
			}

			if (parsed.dependencies() != null) {
				for (ParsedBomDependency pd : parsed.dependencies()) {
					if (pd == null || pd.sourceCanonicalPurl() == null
							|| pd.targetCanonicalPurl() == null) continue;
					ParentKey key = new ParentKey(
							pd.sourceCanonicalPurl(),
							relationshipType(pd));
					ParentAggregation parentAgg = parentAggs
							.computeIfAbsent(pd.targetCanonicalPurl(), k -> new LinkedHashMap<>())
							.computeIfAbsent(key, k -> new ParentAggregation());
					parentAgg.addDeclaration(canonicalArtifactUuid, pd.sourceFullPurl(), pd.targetFullPurl());
				}
			}
		}

		// Empty aggregation → just drop everything for this release.
		if (componentAggs.isEmpty()) {
			releaseSbomEdgeRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);
			releaseSbomComponentArtifactRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);
			releaseSbomComponentRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);
			markReleaseReconciled(releaseUuid);
			return;
		}

		// Canonical sbom_components rows. Returns canonical_purl → uuid.
		Map<String, UUID> canonicalToUuid = upsertSbomComponents(componentAggs.values(), orgUuid);

		// Compute keep-set and decide which anchors stay.
		Set<UUID> keepComponentUuids = new HashSet<>();
		for (String canonical : componentAggs.keySet()) {
			UUID uuid = canonicalToUuid.get(canonical);
			if (uuid != null) keepComponentUuids.add(uuid);
		}

		// Drop anchors no longer present — cascades to existing child rows.
		releaseSbomComponentRepository
				.deleteByOrgAndReleaseUuidAndSbomComponentUuidNotIn(orgUuid, releaseUuid, keepComponentUuids);

		// Wipe child rows for the remaining anchors — we're rebuilding them.
		releaseSbomEdgeRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);
		releaseSbomComponentArtifactRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);

		// Upsert anchors — insert if missing, leave existing alone so their
		// stable uuid (UI navigates by this) is preserved across reconciles.
		Map<UUID, ReleaseSbomComponent> anchorByComponentUuid = new HashMap<>();
		for (ReleaseSbomComponent existing :
				releaseSbomComponentRepository.findByOrgAndReleaseUuid(orgUuid, releaseUuid)) {
			anchorByComponentUuid.put(existing.getSbomComponentUuid(), existing);
		}
		for (UUID componentUuid : keepComponentUuids) {
			ReleaseSbomComponent anchor = anchorByComponentUuid.get(componentUuid);
			if (anchor != null) {
				anchor.setLastUpdatedDate(ZonedDateTime.now());
				releaseSbomComponentRepository.save(anchor);
			} else {
				ReleaseSbomComponent fresh = new ReleaseSbomComponent();
				fresh.setOrg(orgUuid);
				fresh.setReleaseUuid(releaseUuid);
				fresh.setSbomComponentUuid(componentUuid);
				try {
					releaseSbomComponentRepository.save(fresh);
				} catch (DataIntegrityViolationException dive) {
					// Race with another writer — accept the prior winner.
				}
			}
		}

		// Resolve / intern every exact PURL referenced by the new child rows
		// (skipping those equal to the canonical — those stay as NULL FK).
		Set<String> exactPurlsToIntern = collectExactPurlsToIntern(componentAggs, parentAggs);
		Map<String, UUID> purlToUuid = upsertPurlQualifiers(exactPurlsToIntern, orgUuid);

		// Insert release_sbom_component_artifacts.
		List<ReleaseSbomComponentArtifact> artRows = new ArrayList<>();
		for (Map.Entry<String, ComponentAggregation> e : componentAggs.entrySet()) {
			UUID componentUuid = canonicalToUuid.get(e.getKey());
			if (componentUuid == null) continue;
			ComponentAggregation agg = e.getValue();
			String canonicalPurl = e.getKey();
			for (Map.Entry<UUID, Set<String>> part : agg.participations.entrySet()) {
				UUID artifactCanonicalUuid = part.getKey();
				for (String fullPurl : part.getValue()) {
					UUID exactPurlUuid = canonicalPurl.equals(fullPurl)
							? null
							: purlToUuid.get(fullPurl);
					ReleaseSbomComponentArtifact row = new ReleaseSbomComponentArtifact();
					row.setOrg(orgUuid);
					row.setReleaseUuid(releaseUuid);
					row.setSbomComponentUuid(componentUuid);
					row.setArtifactUuid(artifactCanonicalUuid);
					row.setExactPurlUuid(exactPurlUuid);
					artRows.add(row);
				}
			}
		}
		if (!artRows.isEmpty()) releaseSbomComponentArtifactRepository.saveAll(artRows);

		// Insert release_sbom_edges.
		List<ReleaseSbomEdge> edgeRows = new ArrayList<>();
		for (Map.Entry<String, Map<ParentKey, ParentAggregation>> e : parentAggs.entrySet()) {
			UUID targetUuid = canonicalToUuid.get(e.getKey());
			if (targetUuid == null) continue;
			String targetCanonicalPurl = e.getKey();
			for (Map.Entry<ParentKey, ParentAggregation> edgeEntry : e.getValue().entrySet()) {
				UUID sourceUuid = canonicalToUuid.get(edgeEntry.getKey().sourceCanonical);
				if (sourceUuid == null) continue;
				String sourceCanonicalPurl = edgeEntry.getKey().sourceCanonical;
				String relationship = edgeEntry.getKey().relationshipType;
				for (DeclaringArtifactRecord d : edgeEntry.getValue().declarations.values()) {
					UUID sourceExactPurlUuid = sourceCanonicalPurl.equals(d.sourceFullPurl)
							? null
							: purlToUuid.get(d.sourceFullPurl);
					UUID targetExactPurlUuid = targetCanonicalPurl.equals(d.targetFullPurl)
							? null
							: purlToUuid.get(d.targetFullPurl);
					ReleaseSbomEdge edge = new ReleaseSbomEdge();
					edge.setOrg(orgUuid);
					edge.setReleaseUuid(releaseUuid);
					edge.setTargetSbomComponentUuid(targetUuid);
					edge.setSourceSbomComponentUuid(sourceUuid);
					edge.setRelationshipType(relationship);
					edge.setDeclaringArtifactUuid(d.artifactUuid);
					edge.setSourceExactPurlUuid(sourceExactPurlUuid);
					edge.setTargetExactPurlUuid(targetExactPurlUuid);
					edgeRows.add(edge);
				}
			}
		}
		if (!edgeRows.isEmpty()) releaseSbomEdgeRepository.saveAll(edgeRows);

		markReleaseReconciled(releaseUuid);
	}

	private void markReleaseReconciled(UUID releaseUuid) {
		releaseRepository.recordSbomReconciledAtVersion(releaseUuid, CURRENT_SBOM_SCHEMA_VERSION);
	}

	/**
	 * Operator force-reconcile — same as before, just bypasses the queue.
	 */
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
	// Canonical artifact resolution (lazy, BOM artifacts only, org-scoped,
	// no FK constraints). Called from inside reconcile only.
	// ===================================================================

	/**
	 * Returns the canonical artifact UUID this artifact maps to within its
	 * org. Creates the mapping row on first request — subsequent reconciles
	 * for any release that touches this artifact will hit the cached row.
	 *
	 * <p>Self-canonical (mapping = artifact_uuid → artifact_uuid) is the
	 * default when no other artifact in the org carries the same REARM-scope
	 * digest. The artifacts table is never modified.
	 */
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
	// Read API — assemble Map-shaped views from the normalized child tables.
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
		if (!isProduct) {
			return loadAssembledRowsForReleases(orgUuid, List.of(releaseUuid));
		}

		Set<UUID> sourceReleaseUuids = new LinkedHashSet<>();
		sourceReleaseUuids.add(releaseUuid);
		for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
			sourceReleaseUuids.add(dep.getUuid());
		}
		List<ReleaseSbomComponent> rawRows = loadAssembledRowsForReleases(orgUuid, sourceReleaseUuids);
		if (rawRows.isEmpty()) return List.of();

		Map<UUID, List<ReleaseSbomComponent>> byComponent = new LinkedHashMap<>();
		for (ReleaseSbomComponent r : rawRows) {
			byComponent.computeIfAbsent(r.getSbomComponentUuid(), k -> new ArrayList<>()).add(r);
		}
		List<ReleaseSbomComponent> aggregated = new ArrayList<>(byComponent.size());
		for (Map.Entry<UUID, List<ReleaseSbomComponent>> e : byComponent.entrySet()) {
			aggregated.add(mergeProductRow(orgUuid, releaseUuid, e.getKey(), e.getValue()));
		}
		return aggregated;
	}

	/**
	 * Load anchor rows + child rows for the given release UUIDs and
	 * populate each anchor's transient {@code artifactParticipations} and
	 * {@code parents} so the GraphQL surface keeps its prior shape.
	 */
	private List<ReleaseSbomComponent> loadAssembledRowsForReleases(UUID orgUuid, Collection<UUID> releaseUuids) {
		List<ReleaseSbomComponent> anchors =
				releaseSbomComponentRepository.findByOrgAndReleaseUuidIn(orgUuid, releaseUuids);
		if (anchors.isEmpty()) return List.of();

		List<ReleaseSbomComponentArtifact> artifactRows =
				releaseSbomComponentArtifactRepository.findByOrgAndReleaseUuidIn(orgUuid, releaseUuids);
		List<ReleaseSbomEdge> edgeRows =
				releaseSbomEdgeRepository.findByOrgAndReleaseUuidIn(orgUuid, releaseUuids);

		// Resolve PURL intern rows and canonical components referenced by the children.
		Set<UUID> purlIds = new HashSet<>();
		for (ReleaseSbomComponentArtifact r : artifactRows) {
			if (r.getExactPurlUuid() != null) purlIds.add(r.getExactPurlUuid());
		}
		for (ReleaseSbomEdge e : edgeRows) {
			if (e.getSourceExactPurlUuid() != null) purlIds.add(e.getSourceExactPurlUuid());
			if (e.getTargetExactPurlUuid() != null) purlIds.add(e.getTargetExactPurlUuid());
		}
		Map<UUID, String> purlByUuid = new HashMap<>(purlIds.size() * 2);
		if (!purlIds.isEmpty()) {
			purlQualifierRepository.findAllById(purlIds).forEach(q ->
					purlByUuid.put(q.getUuid(), q.getFullPurl()));
		}

		Set<UUID> componentIds = new HashSet<>();
		for (ReleaseSbomComponent a : anchors) componentIds.add(a.getSbomComponentUuid());
		for (ReleaseSbomEdge e : edgeRows) {
			componentIds.add(e.getSourceSbomComponentUuid());
			componentIds.add(e.getTargetSbomComponentUuid());
		}
		Map<UUID, SbomComponent> sbomCompByUuid = new HashMap<>(componentIds.size() * 2);
		if (!componentIds.isEmpty()) {
			sbomComponentRepository.findAllById(componentIds).forEach(sc -> {
				if (orgUuid.equals(sc.getOrg())) sbomCompByUuid.put(sc.getUuid(), sc);
			});
		}

		// Group child rows by (release, target/component) so each anchor
		// can grab its slice in O(1).
		Map<RowKey, List<ReleaseSbomComponentArtifact>> artByAnchor = new HashMap<>();
		for (ReleaseSbomComponentArtifact r : artifactRows) {
			artByAnchor.computeIfAbsent(
					new RowKey(r.getReleaseUuid(), r.getSbomComponentUuid()),
					k -> new ArrayList<>()).add(r);
		}
		Map<RowKey, List<ReleaseSbomEdge>> edgesByAnchor = new HashMap<>();
		for (ReleaseSbomEdge e : edgeRows) {
			edgesByAnchor.computeIfAbsent(
					new RowKey(e.getReleaseUuid(), e.getTargetSbomComponentUuid()),
					k -> new ArrayList<>()).add(e);
		}

		for (ReleaseSbomComponent anchor : anchors) {
			RowKey key = new RowKey(anchor.getReleaseUuid(), anchor.getSbomComponentUuid());
			SbomComponent target = sbomCompByUuid.get(anchor.getSbomComponentUuid());
			String targetCanonicalPurl = target != null ? target.getCanonicalPurl() : null;
			anchor.setArtifactParticipations(renderParticipations(
					artByAnchor.getOrDefault(key, List.of()),
					purlByUuid,
					targetCanonicalPurl));
			anchor.setParents(renderParents(
					edgesByAnchor.getOrDefault(key, List.of()),
					purlByUuid,
					sbomCompByUuid));
		}
		return anchors;
	}

	private static List<Map<String, Object>> renderParticipations(
			List<ReleaseSbomComponentArtifact> rows,
			Map<UUID, String> purlByUuid,
			String targetCanonicalPurl) {
		if (rows.isEmpty()) return List.of();
		// Group by artifact_uuid → set of exact purls (canonical when FK is null).
		Map<UUID, Set<String>> byArtifact = new LinkedHashMap<>();
		for (ReleaseSbomComponentArtifact r : rows) {
			String exact = r.getExactPurlUuid() == null
					? targetCanonicalPurl
					: purlByUuid.get(r.getExactPurlUuid());
			if (exact == null) continue;
			byArtifact.computeIfAbsent(r.getArtifactUuid(), k -> new TreeSet<>()).add(exact);
		}
		List<Map<String, Object>> out = new ArrayList<>(byArtifact.size());
		byArtifact.entrySet().stream()
				.sorted(Map.Entry.comparingByKey((a, b) -> a.toString().compareTo(b.toString())))
				.forEach(e -> {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("artifact", e.getKey().toString());
					entry.put("exactPurls", new ArrayList<>(e.getValue()));
					out.add(entry);
				});
		return out;
	}

	private static List<Map<String, Object>> renderParents(
			List<ReleaseSbomEdge> rows,
			Map<UUID, String> purlByUuid,
			Map<UUID, SbomComponent> sbomCompByUuid) {
		if (rows.isEmpty()) return List.of();
		// Group by (source_uuid, relationship); collect per-declaring-artifact records.
		Map<EdgeGroupKey, EdgeGroup> grouped = new LinkedHashMap<>();
		for (ReleaseSbomEdge e : rows) {
			EdgeGroupKey key = new EdgeGroupKey(e.getSourceSbomComponentUuid(), e.getRelationshipType());
			EdgeGroup g = grouped.computeIfAbsent(key, k -> new EdgeGroup());
			SbomComponent source = sbomCompByUuid.get(e.getSourceSbomComponentUuid());
			SbomComponent target = sbomCompByUuid.get(e.getTargetSbomComponentUuid());
			String sourceCanonical = source == null ? null : source.getCanonicalPurl();
			String targetCanonical = target == null ? null : target.getCanonicalPurl();
			g.sourceCanonicalPurl = sourceCanonical;
			String srcExact = e.getSourceExactPurlUuid() == null
					? sourceCanonical
					: purlByUuid.get(e.getSourceExactPurlUuid());
			String tgtExact = e.getTargetExactPurlUuid() == null
					? targetCanonical
					: purlByUuid.get(e.getTargetExactPurlUuid());
			g.addDeclaration(e.getDeclaringArtifactUuid(), srcExact, tgtExact);
		}
		List<Map<String, Object>> out = new ArrayList<>(grouped.size());
		for (Map.Entry<EdgeGroupKey, EdgeGroup> e : grouped.entrySet()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("sourceSbomComponentUuid", e.getKey().sourceUuid.toString());
			entry.put("sourceCanonicalPurl", e.getValue().sourceCanonicalPurl);
			entry.put("relationshipType", e.getKey().relationshipType);
			entry.put("declaringArtifacts", e.getValue().sortedDeclarations());
			out.add(entry);
		}
		out.sort((a, b) -> {
			String sa = (String) a.get("sourceCanonicalPurl");
			String sb = (String) b.get("sourceCanonicalPurl");
			if (sa == null) sa = "";
			if (sb == null) sb = "";
			int byCanonical = sa.compareTo(sb);
			if (byCanonical != 0) return byCanonical;
			return ((String) a.get("relationshipType"))
					.compareTo((String) b.get("relationshipType"));
		});
		return out;
	}

	// ===================================================================
	// Product-release in-memory merge — identical to prior implementation
	// since it operates on the already-assembled Map-shaped views.
	// ===================================================================

	@SuppressWarnings("unchecked")
	private ReleaseSbomComponent mergeProductRow(UUID orgUuid, UUID productReleaseUuid, UUID sbomComponentUuid,
			List<ReleaseSbomComponent> sourceRows) {
		Map<String, Map<String, Object>> participationsByArtifact = new LinkedHashMap<>();
		Map<String, Map<String, Object>> parentsByKey = new LinkedHashMap<>();
		ZonedDateTime earliestCreated = null;
		ZonedDateTime latestUpdated = null;

		for (ReleaseSbomComponent src : sourceRows) {
			if (src.getCreatedDate() != null
					&& (earliestCreated == null || src.getCreatedDate().isBefore(earliestCreated))) {
				earliestCreated = src.getCreatedDate();
			}
			if (src.getLastUpdatedDate() != null
					&& (latestUpdated == null || src.getLastUpdatedDate().isAfter(latestUpdated))) {
				latestUpdated = src.getLastUpdatedDate();
			}

			List<Map<String, Object>> parts = src.getArtifactParticipations();
			if (parts != null) {
				for (Map<String, Object> part : parts) {
					if (part == null) continue;
					String artifactKey = String.valueOf(part.get("artifact"));
					Map<String, Object> existing = participationsByArtifact.get(artifactKey);
					if (existing == null) {
						Map<String, Object> copy = new LinkedHashMap<>(part);
						List<String> exact = new ArrayList<>();
						Object rawExact = part.get("exactPurls");
						if (rawExact instanceof List<?> list) {
							for (Object o : list) if (o != null) exact.add(o.toString());
						}
						copy.put("exactPurls", exact);
						participationsByArtifact.put(artifactKey, copy);
					} else {
						List<String> exact = (List<String>) existing.get("exactPurls");
						Set<String> dedup = new LinkedHashSet<>(exact);
						Object rawExact = part.get("exactPurls");
						if (rawExact instanceof List<?> list) {
							for (Object o : list) if (o != null) dedup.add(o.toString());
						}
						existing.put("exactPurls", new ArrayList<>(dedup));
					}
				}
			}

			List<Map<String, Object>> parents = src.getParents();
			if (parents != null) {
				for (Map<String, Object> parent : parents) {
					if (parent == null) continue;
					String parentKey = parent.get("sourceSbomComponentUuid")
							+ " " + parent.get("relationshipType");
					Map<String, Object> existing = parentsByKey.get(parentKey);
					if (existing == null) {
						Map<String, Object> copy = new LinkedHashMap<>(parent);
						List<Map<String, Object>> declarations = new ArrayList<>();
						Object rawDecls = parent.get("declaringArtifacts");
						if (rawDecls instanceof List<?> list) {
							for (Object o : list) {
								if (o instanceof Map<?, ?> m) declarations.add(new LinkedHashMap<>((Map<String, Object>) m));
							}
						}
						copy.put("declaringArtifacts", declarations);
						parentsByKey.put(parentKey, copy);
					} else {
						List<Map<String, Object>> declarations = (List<Map<String, Object>>) existing.get("declaringArtifacts");
						Set<String> seen = new HashSet<>();
						for (Map<String, Object> d : declarations) {
							seen.add(declarationKey(d));
						}
						Object rawDecls = parent.get("declaringArtifacts");
						if (rawDecls instanceof List<?> list) {
							for (Object o : list) {
								if (o instanceof Map<?, ?> m) {
									Map<String, Object> decl = new LinkedHashMap<>((Map<String, Object>) m);
									if (seen.add(declarationKey(decl))) declarations.add(decl);
								}
							}
						}
					}
				}
			}
		}

		ReleaseSbomComponent merged = new ReleaseSbomComponent();
		merged.setUuid(syntheticProductRowUuid(productReleaseUuid, sbomComponentUuid));
		merged.setOrg(orgUuid);
		merged.setReleaseUuid(productReleaseUuid);
		merged.setSbomComponentUuid(sbomComponentUuid);
		merged.setArtifactParticipations(new ArrayList<>(participationsByArtifact.values()));
		merged.setParents(new ArrayList<>(parentsByKey.values()));
		if (earliestCreated != null) merged.setCreatedDate(earliestCreated);
		if (latestUpdated != null) merged.setLastUpdatedDate(latestUpdated);
		return merged;
	}

	private static UUID syntheticProductRowUuid(UUID productReleaseUuid, UUID sbomComponentUuid) {
		String key = productReleaseUuid.toString() + ":" + sbomComponentUuid.toString();
		return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
	}

	private static String declarationKey(Map<String, Object> declaration) {
		return String.valueOf(declaration.get("artifact"))
				+ " " + String.valueOf(declaration.get("sourceExactPurl"))
				+ " " + String.valueOf(declaration.get("targetExactPurl"));
	}

	// ===================================================================
	// Misc public API used by other services / GraphQL.
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

	public Optional<ReleaseSbomComponent> getReleaseSbomComponent(UUID uuid) {
		return releaseSbomComponentRepository.findById(uuid);
	}

	public record SbomComponentSearchQuery(String name, String version) {}

	public record ComponentPurlToSbom(String purl, List<UUID> sbomComponents) {}

	public List<ComponentPurlToSbom> searchSbomComponentsBatch(
			List<SbomComponentSearchQuery> queries, UUID orgUuid) {
		if (queries == null || queries.isEmpty() || orgUuid == null) return List.of();
		Map<String, Set<UUID>> byCanonical = new LinkedHashMap<>();
		for (SbomComponentSearchQuery q : queries) {
			if (q == null || q.name() == null || q.name().isBlank()) continue;
			List<SbomComponent> matches = sbomComponentRepository
					.searchByOrgAndNameAndOptionalVersion(orgUuid, q.name(), q.version());
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
		return releaseSbomComponentRepository.existsByOrgAndReleaseUuid(orgUuid, releaseUuid);
	}

	public Set<UUID> findReleaseUuidsBySbomComponents(Collection<UUID> sbomComponentUuids, UUID orgUuid) {
		if (sbomComponentUuids == null || sbomComponentUuids.isEmpty() || orgUuid == null) return Set.of();
		List<UUID> directReleaseUuids = releaseSbomComponentRepository
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
	// BOM artifact collection (unchanged from prior implementation).
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
	// Canonical sbom_components upsert (promoted columns, no JSONB).
	// ===================================================================

	private Map<String, UUID> upsertSbomComponents(Collection<ComponentAggregation> aggs, UUID orgUuid) {
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
				if (agg.isRoot && !existing.isRoot()) {
					existing.setRoot(true);
					existing.setLastUpdatedDate(ZonedDateTime.now());
					try {
						sbomComponentRepository.save(existing);
					} catch (DataIntegrityViolationException ignored) {
					}
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

	private SbomComponent buildSbomComponent(ComponentAggregation agg, UUID orgUuid) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(orgUuid);
		sc.setCanonicalPurl(agg.sample.canonicalPurl());
		sc.setPurlType(agg.sample.type());
		sc.setPkgGroup(agg.sample.group());
		sc.setName(agg.sample.name());
		sc.setVersion(agg.sample.version());
		sc.setRoot(agg.isRoot);
		return sc;
	}

	// ===================================================================
	// PURL qualifier interning.
	// ===================================================================

	private Set<String> collectExactPurlsToIntern(
			Map<String, ComponentAggregation> componentAggs,
			Map<String, Map<ParentKey, ParentAggregation>> parentAggs) {
		Set<String> need = new HashSet<>();
		for (Map.Entry<String, ComponentAggregation> e : componentAggs.entrySet()) {
			String canonical = e.getKey();
			for (Set<String> exacts : e.getValue().participations.values()) {
				for (String exact : exacts) {
					if (exact != null && !exact.equals(canonical)) need.add(exact);
				}
			}
		}
		for (Map.Entry<String, Map<ParentKey, ParentAggregation>> e : parentAggs.entrySet()) {
			String targetCanonical = e.getKey();
			for (Map.Entry<ParentKey, ParentAggregation> edge : e.getValue().entrySet()) {
				String sourceCanonical = edge.getKey().sourceCanonical;
				for (DeclaringArtifactRecord d : edge.getValue().declarations.values()) {
					if (d.sourceFullPurl != null && !d.sourceFullPurl.equals(sourceCanonical)) {
						need.add(d.sourceFullPurl);
					}
					if (d.targetFullPurl != null && !d.targetFullPurl.equals(targetCanonical)) {
						need.add(d.targetFullPurl);
					}
				}
			}
		}
		return need;
	}

	private Map<String, UUID> upsertPurlQualifiers(Set<String> fullPurls, UUID orgUuid) {
		if (fullPurls.isEmpty()) return Map.of();
		Map<String, UUID> out = new HashMap<>(fullPurls.size() * 2);
		for (PurlQualifier q : purlQualifierRepository.findByOrgAndFullPurlIn(orgUuid.toString(), fullPurls)) {
			out.put(q.getFullPurl(), q.getUuid());
		}
		for (String full : fullPurls) {
			if (out.containsKey(full)) continue;
			PurlQualifier q = new PurlQualifier();
			q.setOrg(orgUuid);
			q.setFullPurl(full);
			try {
				PurlQualifier saved = purlQualifierRepository.save(q);
				out.put(full, saved.getUuid());
			} catch (DataIntegrityViolationException dive) {
				purlQualifierRepository.findByOrgAndFullPurl(orgUuid, full)
						.ifPresent(prior -> out.put(full, prior.getUuid()));
			}
		}
		return out;
	}

	// ===================================================================
	// Helpers + aggregation buckets.
	// ===================================================================

	private static String relationshipType(ParsedBomDependency pd) {
		String raw = pd.relationshipType();
		if (raw == null || raw.isBlank()) return "DEPENDS_ON";
		return raw.toUpperCase();
	}

	private static final class ComponentAggregation {
		final ParsedBomComponent sample;
		boolean isRoot;
		final Map<UUID, Set<String>> participations = new LinkedHashMap<>();

		ComponentAggregation(ParsedBomComponent sample) {
			this.sample = sample;
			this.isRoot = Boolean.TRUE.equals(sample.isRoot());
		}

		void mergeSample(ParsedBomComponent other) {
			if (Boolean.TRUE.equals(other.isRoot())) this.isRoot = true;
		}

		void addParticipation(UUID artifactUuid, String fullPurl) {
			participations.computeIfAbsent(artifactUuid, k -> new TreeSet<>()).add(fullPurl);
		}
	}

	private record ParentKey(String sourceCanonical, String relationshipType) {}

	private static final class ParentAggregation {
		final Map<String, DeclaringArtifactRecord> declarations = new LinkedHashMap<>();

		void addDeclaration(UUID artifactUuid, String sourceFullPurl, String targetFullPurl) {
			String key = artifactUuid + " "
					+ (sourceFullPurl == null ? "" : sourceFullPurl) + " "
					+ (targetFullPurl == null ? "" : targetFullPurl);
			declarations.putIfAbsent(key, new DeclaringArtifactRecord(artifactUuid, sourceFullPurl, targetFullPurl));
		}
	}

	private record DeclaringArtifactRecord(UUID artifactUuid, String sourceFullPurl, String targetFullPurl) {}

	private record RowKey(UUID releaseUuid, UUID sbomComponentUuid) {}

	private record EdgeGroupKey(UUID sourceUuid, String relationshipType) {}

	private static final class EdgeGroup {
		String sourceCanonicalPurl;
		final Map<String, Map<String, Object>> declarations = new LinkedHashMap<>();

		void addDeclaration(UUID artifactUuid, String sourceExact, String targetExact) {
			String key = artifactUuid + " "
					+ (sourceExact == null ? "" : sourceExact) + " "
					+ (targetExact == null ? "" : targetExact);
			if (declarations.containsKey(key)) return;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("artifact", artifactUuid.toString());
			entry.put("sourceExactPurl", sourceExact);
			entry.put("targetExactPurl", targetExact);
			declarations.put(key, entry);
		}

		List<Map<String, Object>> sortedDeclarations() {
			List<Map<String, Object>> out = new ArrayList<>(declarations.values());
			Collections.sort(out, (a, b) -> {
				int byArtifact = String.valueOf(a.get("artifact")).compareTo(String.valueOf(b.get("artifact")));
				if (byArtifact != 0) return byArtifact;
				int bySrc = String.valueOf(a.get("sourceExactPurl")).compareTo(String.valueOf(b.get("sourceExactPurl")));
				if (bySrc != 0) return bySrc;
				return String.valueOf(a.get("targetExactPurl")).compareTo(String.valueOf(b.get("targetExactPurl")));
			});
			return out;
		}
	}
}
