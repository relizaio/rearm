/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
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

import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.FlowControl;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.model.tea.Rebom.ParsedBom;
import io.reliza.model.tea.Rebom.ParsedBomComponent;
import io.reliza.model.tea.Rebom.ParsedBomDependency;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.ReleaseSbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains the SBOM component tables ({@code sbom_components} and
 * {@code release_sbom_components}). Rebom parses components and dependency
 * edges out of each uploaded BOM; this service aggregates both per release
 * and keeps the two tables in sync by rebuilding the release's component /
 * edge rows from scratch on every reconcile call.
 *
 * Reconciliation is idempotent and cheap to re-run. It used to fire
 * synchronously from every artifact-mutation event, which raced on the
 * global {@code sbom_components} unique constraint and on per-release
 * delete/insert. We now <em>queue</em> reconciles by stamping
 * {@code releases.sbom_reconcile_requested_at} and let the every-minute
 * dependency-track scheduler drain the queue serially under its existing
 * advisory lock — multiple triggers within a minute coalesce into one
 * reconcile, and there is at most one reconcile in flight at a time across
 * replicas. The {@link #reconcileReleaseSbomComponents(UUID)} entry point
 * remains public for the operator force-reconcile GraphQL mutation.
 */
@Slf4j
@Service
public class SbomComponentService {

	@Autowired
	private RebomService rebomService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private ArtifactService artifactService;

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

	/**
	 * Self-injection so {@link #processPendingReconciles(int)} can call the
	 * {@code @Transactional} reconcile method through Spring's proxy. Direct
	 * {@code this.*} calls bypass AOP, leaving the {@code @Modifying} delete
	 * queries running outside any transaction.
	 */
	@Autowired
	@Lazy
	private SbomComponentService self;

	/** Failure-backoff caps; exponential up to one hour. */
	private static final int BASE_BACKOFF_SECONDS = 30;
	private static final int MAX_BACKOFF_SECONDS = 3600;

	private final SbomComponentRepository sbomComponentRepository;
	private final ReleaseSbomComponentRepository releaseSbomComponentRepository;

	SbomComponentService(
			SbomComponentRepository sbomComponentRepository,
			ReleaseSbomComponentRepository releaseSbomComponentRepository) {
		this.sbomComponentRepository = sbomComponentRepository;
		this.releaseSbomComponentRepository = releaseSbomComponentRepository;
	}

	/**
	 * Mark a release as needing SBOM-component reconciliation. Idempotent —
	 * the timestamp is only set if currently NULL (preserves FIFO ordering
	 * across the burst of triggers an artifact mutation can produce).
	 */
	public void requestReconcile(UUID releaseUuid) {
		if (releaseUuid == null) return;
		releaseRepository.markSbomReconcileRequested(releaseUuid);
	}

	/**
	 * Drain up to {@code batchLimit} pending reconciles. Called from the
	 * every-minute dependency-track scheduler under its advisory lock, so
	 * concurrency across replicas is already serialized; failures bump a
	 * backoff counter rather than re-throwing so one poison-pill release
	 * doesn't block the rest of the queue.
	 */
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

	/**
	 * Rebuild the sbom_components / release_sbom_components mappings for a
	 * single release. For every BOM artifact participating in the release
	 * we fetch the parsed components + dependencies from rebom, upsert the
	 * canonical component rows (synthesising an isRoot flag where rebom
	 * flagged it), and then upsert one join row per (release, component)
	 * containing all artifact participations and every <em>incoming</em>
	 * edge whose source canonical purl also appears in the release's
	 * component set. Stale join rows are dropped.
	 *
	 * <p>Edges are stored as in-edges (parents) rather than out-edges
	 * because the impact-analysis "what depends on this component" query
	 * is the dominant lookup; storing parents makes that a primary-key
	 * read instead of a GIN-on-jsonb scan. Forward "what does this depend
	 * on" is reconstructed in memory from a single per-release fetch.
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

		Map<String, ComponentAggregation> componentAggs = new LinkedHashMap<>();
		// Aggregation is keyed by TARGET canonical: each entry is a target
		// component carrying the list of source components that point at it.
		Map<String, Map<ParentKey, ParentAggregation>> parentAggs = new LinkedHashMap<>();

		for (UUID artifactUuid : collectBomArtifactUuids(rd)) {
			Optional<ArtifactData> oad = artifactService.getArtifactData(artifactUuid);
			if (oad.isEmpty()) continue;
			ArtifactData ad = oad.get();
			if (ad.getInternalBom() == null || ad.getInternalBom().id() == null) continue;

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
					agg.addParticipation(artifactUuid, pc.fullPurl());
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
					parentAgg.addDeclaration(artifactUuid, pd.sourceFullPurl(), pd.targetFullPurl());
				}
			}
		}

		if (componentAggs.isEmpty()) {
			// No components → just clear any existing rows for this release.
			releaseSbomComponentRepository.deleteAllByReleaseUuid(releaseUuid);
			return;
		}

		// Upsert the canonical component rows; returns canonical→uuid map the
		// edge upsert step uses to resolve source component UUIDs.
		Map<String, UUID> canonicalToUuid = upsertSbomComponents(componentAggs.values());

		Set<UUID> keepComponentUuids = new HashSet<>();
		for (Map.Entry<String, ComponentAggregation> e : componentAggs.entrySet()) {
			UUID componentUuid = canonicalToUuid.get(e.getKey());
			if (componentUuid == null) continue;
			keepComponentUuids.add(componentUuid);
			List<Map<String, Object>> parentsJson = renderParents(
					parentAggs.get(e.getKey()), canonicalToUuid);
			upsertReleaseSbomComponent(releaseUuid, componentUuid, e.getValue(), parentsJson);
		}

		// Drop any join rows for components that no longer participate.
		if (keepComponentUuids.isEmpty()) {
			releaseSbomComponentRepository.deleteAllByReleaseUuid(releaseUuid);
		} else {
			releaseSbomComponentRepository
					.deleteByReleaseUuidAndSbomComponentUuidNotIn(releaseUuid, keepComponentUuids);
		}
	}

	public List<ReleaseSbomComponent> listReleaseSbomComponents(UUID releaseUuid) {
		return releaseSbomComponentRepository.findByReleaseUuid(releaseUuid);
	}

	public Optional<SbomComponent> getSbomComponent(UUID uuid) {
		return sbomComponentRepository.findById(uuid);
	}

	public Optional<ReleaseSbomComponent> getReleaseSbomComponent(UUID uuid) {
		return releaseSbomComponentRepository.findById(uuid);
	}

	/**
	 * Pull all BOM-bearing artifact UUIDs that participate in a release. Mirrors
	 * ReleaseService.getAllDeliverableDataFromRelease but is inlined here to
	 * avoid a Spring circular dependency (ReleaseService is a caller of this
	 * service).
	 */
	private Set<UUID> collectBomArtifactUuids(ReleaseData rd) {
		Set<UUID> artifactUuids = new LinkedHashSet<>();

		// Artifacts on inbound/outbound deliverables. For PRODUCT releases we walk
		// the dependency tree (outbound deliverables of each dep); for COMPONENT
		// releases we include the release's own inbound + outbound deliverables.
		List<UUID> deliverableUuids = new ArrayList<>();
		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);
		if (isProduct) {
			Set<ReleaseData> dependencies = sharedReleaseService.unwindReleaseDependencies(rd);
			dependencies.stream()
					.map(variantService::getBaseVariantForRelease)
					.flatMap(v -> v.getOutboundDeliverables().stream())
					.distinct()
					.forEach(deliverableUuids::add);
		} else {
			if (rd.getInboundDeliverables() != null) deliverableUuids.addAll(rd.getInboundDeliverables());
			deliverableUuids.addAll(variantService.getBaseVariantForRelease(rd).getOutboundDeliverables());
		}
		for (DeliverableData dd : getDeliverableService.getDeliverableDataList(deliverableUuids)) {
			if (dd.getArtifacts() != null) artifactUuids.addAll(dd.getArtifacts());
		}

		// Artifacts on the source-code entry matching the release component.
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
		// Artifacts directly attached to the release.
		if (rd.getArtifacts() != null) artifactUuids.addAll(rd.getArtifacts());
		return artifactUuids;
	}

	/**
	 * Ensure a row exists for every canonical purl in the aggregation set and
	 * return a canonical→uuid map. Concurrent inserts of the same canonical
	 * purl across different releases are now naturally avoided (the queue
	 * scheduler runs single-threaded under the dtrack advisory lock), but we
	 * keep the race-tolerant catch as belt-and-suspenders for the operator
	 * force-reconcile path.
	 */
	private Map<String, UUID> upsertSbomComponents(Collection<ComponentAggregation> aggs) {
		List<String> canonicals = new ArrayList<>();
		for (ComponentAggregation agg : aggs) canonicals.add(agg.sample.canonicalPurl());

		Map<String, UUID> canonicalToUuid = new HashMap<>();
		Map<String, SbomComponent> existingByCanonical = new HashMap<>();
		for (SbomComponent sc : sbomComponentRepository.findByCanonicalPurlIn(canonicals)) {
			existingByCanonical.put(sc.getCanonicalPurl(), sc);
			canonicalToUuid.put(sc.getCanonicalPurl(), sc.getUuid());
		}

		for (ComponentAggregation agg : aggs) {
			String canonical = agg.sample.canonicalPurl();
			SbomComponent existing = existingByCanonical.get(canonical);
			if (existing != null) {
				// Flip isRoot on only if we now have evidence the component is a root.
				if (agg.isRoot && !isMarkedRoot(existing)) {
					Map<String, Object> rec = existing.getRecordData() != null
							? new HashMap<>(existing.getRecordData())
							: new HashMap<>();
					rec.put("isRoot", true);
					existing.setRecordData(rec);
					existing.setLastUpdatedDate(ZonedDateTime.now());
					try {
						sbomComponentRepository.save(existing);
					} catch (DataIntegrityViolationException ignored) {
						// best-effort; the read-side still resolves the row.
					}
				}
				continue;
			}
			SbomComponent sc = buildSbomComponent(agg);
			try {
				sc = sbomComponentRepository.save(sc);
				canonicalToUuid.put(canonical, sc.getUuid());
			} catch (DataIntegrityViolationException dive) {
				// Lost the race with another writer — re-read.
				sbomComponentRepository.findByCanonicalPurl(canonical)
						.ifPresent(rec -> canonicalToUuid.put(canonical, rec.getUuid()));
			}
		}
		return canonicalToUuid;
	}

	private boolean isMarkedRoot(SbomComponent sc) {
		Map<String, Object> rd = sc.getRecordData();
		return rd != null && Boolean.TRUE.equals(rd.get("isRoot"));
	}

	private SbomComponent buildSbomComponent(ComponentAggregation agg) {
		SbomComponent sc = new SbomComponent();
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
	 * Render the parents jsonb for one target component: one entry per
	 * (source canonical, relationshipType), each carrying its per-artifact
	 * declarations. Source entries that don't resolve to a canonical uuid
	 * (shouldn't happen since rebom only returns resolved edges, but
	 * defensive) are dropped.
	 */
	private List<Map<String, Object>> renderParents(
			Map<ParentKey, ParentAggregation> edges,
			Map<String, UUID> canonicalToUuid) {
		if (edges == null || edges.isEmpty()) return new ArrayList<>();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<ParentKey, ParentAggregation> e : edges.entrySet()) {
			UUID sourceUuid = canonicalToUuid.get(e.getKey().sourceCanonical);
			if (sourceUuid == null) continue;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("sourceSbomComponentUuid", sourceUuid.toString());
			entry.put("sourceCanonicalPurl", e.getKey().sourceCanonical);
			entry.put("relationshipType", e.getKey().relationshipType);
			entry.put("declaringArtifacts", e.getValue().sortedDeclarations());
			out.add(entry);
		}
		// Stable output: sort by source canonical purl then relationship type.
		out.sort((a, b) -> {
			int bySource = ((String) a.get("sourceCanonicalPurl"))
					.compareTo((String) b.get("sourceCanonicalPurl"));
			if (bySource != 0) return bySource;
			return ((String) a.get("relationshipType"))
					.compareTo((String) b.get("relationshipType"));
		});
		return out;
	}

	private void upsertReleaseSbomComponent(
			UUID releaseUuid,
			UUID sbomComponentUuid,
			ComponentAggregation agg,
			List<Map<String, Object>> parentsJson) {
		List<Map<String, Object>> participations = new ArrayList<>();
		for (Map.Entry<UUID, Set<String>> part : agg.sortedParticipations().entrySet()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("artifact", part.getKey().toString());
			entry.put("exactPurls", new ArrayList<>(part.getValue()));
			participations.add(entry);
		}

		Optional<ReleaseSbomComponent> existing = releaseSbomComponentRepository
				.findByReleaseUuidAndSbomComponentUuid(releaseUuid, sbomComponentUuid);
		if (existing.isPresent()) {
			ReleaseSbomComponent row = existing.get();
			row.setArtifactParticipations(participations);
			row.setParents(parentsJson);
			row.setLastUpdatedDate(ZonedDateTime.now());
			releaseSbomComponentRepository.save(row);
		} else {
			ReleaseSbomComponent row = new ReleaseSbomComponent();
			row.setReleaseUuid(releaseUuid);
			row.setSbomComponentUuid(sbomComponentUuid);
			row.setArtifactParticipations(participations);
			row.setParents(parentsJson);
			try {
				releaseSbomComponentRepository.save(row);
			} catch (DataIntegrityViolationException dive) {
				// Defensive — the queue should serialize per-release work, so
				// this branch is only reachable on a genuine concurrent write.
				releaseSbomComponentRepository
						.findByReleaseUuidAndSbomComponentUuid(releaseUuid, sbomComponentUuid)
						.ifPresent(r -> {
							r.setArtifactParticipations(participations);
							r.setParents(parentsJson);
							r.setLastUpdatedDate(ZonedDateTime.now());
							releaseSbomComponentRepository.save(r);
						});
			}
		}
	}

	private static String relationshipType(ParsedBomDependency pd) {
		String raw = pd.relationshipType();
		if (raw == null || raw.isBlank()) return "DEPENDS_ON";
		return raw.toUpperCase();
	}

	/**
	 * Per-canonical aggregation bucket: one representative ParsedBomComponent
	 * (first one seen; used for the record_data on sbom_components), whether
	 * we've seen any artifact declare this component as a root, plus the map
	 * of participating artifacts → full purls observed for that artifact.
	 */
	private static final class ComponentAggregation {
		private final ParsedBomComponent sample;
		private boolean isRoot;
		private final Map<UUID, Set<String>> participations = new LinkedHashMap<>();

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

		Map<UUID, Set<String>> sortedParticipations() {
			Map<UUID, Set<String>> sorted = new LinkedHashMap<>();
			participations.entrySet().stream()
					.sorted(Map.Entry.comparingByKey((a, b) -> a.toString().compareTo(b.toString())))
					.forEach(e -> sorted.put(e.getKey(), e.getValue()));
			return sorted;
		}
	}

	/** Grouping key for parent aggregation within one target canonical. */
	private record ParentKey(String sourceCanonical, String relationshipType) {}

	/**
	 * Per (source, target, relationshipType) bucket: one entry per artifact
	 * that declared the edge, capturing the exact purls it used on both ends.
	 */
	private static final class ParentAggregation {
		/** Keyed by (artifact, source full purl, target full purl). */
		private final Map<String, Map<String, Object>> declarations = new LinkedHashMap<>();

		void addDeclaration(UUID artifactUuid, String sourceFullPurl, String targetFullPurl) {
			String key = artifactUuid + "\u0000" + sourceFullPurl + "\u0000" + targetFullPurl;
			if (declarations.containsKey(key)) return;
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("artifact", artifactUuid.toString());
			entry.put("sourceExactPurl", sourceFullPurl);
			entry.put("targetExactPurl", targetFullPurl);
			declarations.put(key, entry);
		}

		List<Map<String, Object>> sortedDeclarations() {
			return declarations.entrySet().stream()
					.sorted(Map.Entry.comparingByKey())
					.map(Map.Entry::getValue)
					.toList();
		}
	}
}
