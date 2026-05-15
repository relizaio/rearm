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
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.FlowControl;
import io.reliza.model.Release;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.SbomComponent;
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
	@Autowired private ReleaseRepository releaseRepository;

	/**
	 * Self-injection so {@link #processPendingReconciles(int)} can call the
	 * {@code @Transactional} reconcile method through Spring's proxy.
	 */
	@Autowired @Lazy private SbomComponentService self;

	private static final int BASE_BACKOFF_SECONDS = 30;
	private static final int MAX_BACKOFF_SECONDS = 3600;

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
	 */
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

		Set<UUID> canonicalArtifactSet = new LinkedHashSet<>();

		for (UUID artifactUuid : collectBomArtifactUuids(rd)) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isEmpty()) continue;
			ArtifactData ad = oad.get();
			if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) continue;

			UUID canonicalArtifactUuid = resolveCanonicalArtifact(ad, orgUuid);
			canonicalArtifactSet.add(canonicalArtifactUuid);

			// Skip the parse if this canonical's component graph is already on disk.
			// Artifact content is immutable, so cached rows are still valid.
			if (artifactSbomComponentRepository.existsByCanonicalArtifactUuid(canonicalArtifactUuid)) {
				continue;
			}

			parseAndUpsertArtifactSbomComponents(ad, canonicalArtifactUuid, orgUuid);
		}

		// Rebuild this release's reverse-index entries. The release's BOM
		// artifact set may have shifted since the last reconcile (a deliverable
		// detached, a new SCE artifact added, etc.) — wholesale replacement
		// keeps the index in sync with the current artifact set.
		releaseArtifactIndexRepository.deleteAllByOrgAndReleaseUuid(orgUuid, releaseUuid);
		if (!canonicalArtifactSet.isEmpty()) {
			List<ReleaseArtifactIndex> rows = new ArrayList<>(canonicalArtifactSet.size());
			for (UUID canonical : canonicalArtifactSet) {
				ReleaseArtifactIndex idx = new ReleaseArtifactIndex();
				idx.setOrg(orgUuid);
				idx.setReleaseUuid(releaseUuid);
				idx.setCanonicalArtifactUuid(canonical);
				rows.add(idx);
			}
			try {
				releaseArtifactIndexRepository.saveAll(rows);
			} catch (DataIntegrityViolationException dive) {
				// Defensive: per-row save to recover from a partial conflict.
				for (ReleaseArtifactIndex idx : rows) {
					try {
						releaseArtifactIndexRepository.save(idx);
					} catch (DataIntegrityViolationException ignored) {
						// Already present.
					}
				}
			}
		}

		markReleaseReconciled(releaseUuid);
	}

	/**
	 * Parse one canonical artifact's BOM via rebom and write the
	 * per-component {@code artifact_sbom_components} rows. Called only
	 * when the canonical's rows don't already exist on disk.
	 */
	private void parseAndUpsertArtifactSbomComponents(
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

		try {
			artifactSbomComponentRepository.saveAll(rows);
		} catch (DataIntegrityViolationException dive) {
			// Lost the race with a concurrent reconcile of the same canonical —
			// per-row save in case some were inserted, ignore conflicts.
			for (ArtifactSbomComponent row : rows) {
				try {
					artifactSbomComponentRepository.save(row);
				} catch (DataIntegrityViolationException ignored) {
				}
			}
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
				if (agg.isRoot && !isMarkedRoot(existing)) {
					Map<String, Object> rec = existing.getRecordData() != null
							? new HashMap<>(existing.getRecordData())
							: new HashMap<>();
					rec.put("isRoot", true);
					existing.setRecordData(rec);
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
		Map<String, Object> rd = sc.getRecordData();
		return rd != null && Boolean.TRUE.equals(rd.get("isRoot"));
	}

	private SbomComponent buildSbomComponent(ComponentAggregation agg, UUID orgUuid) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(orgUuid);
		sc.setCanonicalPurl(agg.sample.canonicalPurl());
		Map<String, Object> record = new HashMap<>();
		if (agg.sample.type() != null) record.put("type", agg.sample.type());
		if (agg.sample.group() != null) record.put("group", agg.sample.group());
		if (agg.sample.name() != null) record.put("name", agg.sample.name());
		if (agg.sample.version() != null) record.put("version", agg.sample.version());
		if (agg.isRoot) record.put("isRoot", true);
		sc.setRecordData(record);
		return sc;
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

		ComponentAggregation(ParsedBomComponent sample) {
			this.sample = sample;
			this.isRoot = Boolean.TRUE.equals(sample.isRoot());
		}

		void mergeSample(ParsedBomComponent other) {
			if (Boolean.TRUE.equals(other.isRoot())) this.isRoot = true;
		}

		void setExactPurl(String purl) {
			if (this.exactPurl == null || purl != null) this.exactPurl = purl;
		}

		String getExactPurl() { return exactPurl; }
	}

	private record ParentKey(String sourceCanonical, String relationshipType) {}

	private record ParentEdge(String sourceFullPurl, String targetFullPurl) {}
}
