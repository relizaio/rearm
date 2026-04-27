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
 * edges out of each uploaded BOM; reconciliation aggregates both per release
 * and keeps the two tables in sync by rebuilding the release's component /
 * edge rows from scratch on every reconcile call.
 *
 * <p>Each {@code release_sbom_components} row is strictly local to its own
 * release — only the BOMs the release itself carries (release-attached
 * artifacts, deliverables, source-code-entry artifacts) flow into its rows.
 * For PRODUCT releases the dep-aggregated inventory is synthesised at read
 * time in {@link #listReleaseSbomComponents(UUID)} by unioning the rows of
 * the product itself with every transitive dependency's rows. This avoids
 * write-time fan-out (a dep's BOM update doesn't have to re-reconcile every
 * product that bundles it) and means a product's inventory is always
 * up-to-date with the latest dep state without a product-side reconcile.
 *
 * <p>Reconciliation is idempotent and cheap to re-run. It used to fire
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

	/**
	 * Per-release SBOM component inventory.
	 *
	 * <p>For COMPONENT releases this is a direct read of
	 * {@code release_sbom_components}. For PRODUCT releases it is a
	 * read-time union of (a) the product's own rows (e.g. an aggregated
	 * BOM uploaded directly to the product) and (b) every transitive
	 * dependency's rows. The same canonical {@code sbom_components} row
	 * may appear in many deps; we merge those into one synthetic
	 * {@link ReleaseSbomComponent} stamped with the product's
	 * {@code releaseUuid} so the GraphQL surface is releaseUuid-consistent
	 * and dependency / dependedOnBy edge resolution sees the unioned
	 * parent set. The synthetic row is a transient JPA instance — never
	 * saved — so the persisted table stays strictly per-release-local.
	 */
	/**
	 * Operator force-reconcile for a release. For COMPONENT releases this is
	 * just {@link #reconcileReleaseSbomComponents(UUID)}. For PRODUCT releases
	 * we first cascade-reconcile every transitive dependency so the read-time
	 * aggregation on the product picks up the freshest dep state — the queue
	 * scheduler would catch up within a minute, but force-reconcile is the
	 * "do it now" entry point and should leave the product fully up-to-date
	 * the moment it returns. Each cascade reconcile runs in its own
	 * transaction (delegated through the Spring proxy) so a single dep
	 * failure doesn't poison the rest.
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

	public List<ReleaseSbomComponent> listReleaseSbomComponents(UUID releaseUuid) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty()) {
			return releaseSbomComponentRepository.findByReleaseUuid(releaseUuid);
		}
		ReleaseData rd = ord.get();
		boolean isProduct = getComponentService.getComponentData(rd.getComponent())
				.map(cd -> cd.getType() == ComponentType.PRODUCT)
				.orElse(false);
		if (!isProduct) {
			return releaseSbomComponentRepository.findByReleaseUuid(releaseUuid);
		}

		Set<UUID> sourceReleaseUuids = new LinkedHashSet<>();
		sourceReleaseUuids.add(releaseUuid);
		for (ReleaseData dep : sharedReleaseService.unwindReleaseDependencies(rd)) {
			sourceReleaseUuids.add(dep.getUuid());
		}
		List<ReleaseSbomComponent> rawRows = releaseSbomComponentRepository
				.findByReleaseUuidIn(sourceReleaseUuids);
		if (rawRows.isEmpty()) return List.of();

		Map<UUID, List<ReleaseSbomComponent>> byComponent = new LinkedHashMap<>();
		for (ReleaseSbomComponent r : rawRows) {
			byComponent.computeIfAbsent(r.getSbomComponentUuid(), k -> new ArrayList<>()).add(r);
		}
		List<ReleaseSbomComponent> aggregated = new ArrayList<>(byComponent.size());
		for (Map.Entry<UUID, List<ReleaseSbomComponent>> e : byComponent.entrySet()) {
			aggregated.add(mergeProductRow(releaseUuid, e.getKey(), e.getValue()));
		}
		return aggregated;
	}

	/**
	 * Build one synthetic per-product row for a single canonical component
	 * by unioning the {@code artifactParticipations} and {@code parents}
	 * jsonb across all source rows. Participations are deduped by artifact
	 * UUID with their {@code exactPurls} unioned; parents are deduped by
	 * (sourceSbomComponentUuid, relationshipType) with their
	 * {@code declaringArtifacts} unioned by (artifact, sourceExactPurl,
	 * targetExactPurl). Created/updated dates take the earliest/latest seen
	 * so the consumer sees a sensible aggregate timestamp.
	 */
	@SuppressWarnings("unchecked")
	private ReleaseSbomComponent mergeProductRow(UUID productReleaseUuid, UUID sbomComponentUuid,
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
							+ "\u0000" + parent.get("relationshipType");
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
		merged.setReleaseUuid(productReleaseUuid);
		merged.setSbomComponentUuid(sbomComponentUuid);
		merged.setArtifactParticipations(new ArrayList<>(participationsByArtifact.values()));
		merged.setParents(new ArrayList<>(parentsByKey.values()));
		if (earliestCreated != null) merged.setCreatedDate(earliestCreated);
		if (latestUpdated != null) merged.setLastUpdatedDate(latestUpdated);
		return merged;
	}

	private static String declarationKey(Map<String, Object> declaration) {
		return String.valueOf(declaration.get("artifact"))
				+ "\u0000" + String.valueOf(declaration.get("sourceExactPurl"))
				+ "\u0000" + String.valueOf(declaration.get("targetExactPurl"));
	}

	public Optional<SbomComponent> getSbomComponent(UUID uuid) {
		return sbomComponentRepository.findById(uuid);
	}

	/**
	 * Bulk-fetch sbom_components by UUID into a uuid→component map. Used by
	 * the per-release graph resolver to avoid an N+1 against the components
	 * table when resolving {@code component} / {@code targetCanonicalPurl} on
	 * many edges.
	 */
	public Map<UUID, SbomComponent> findSbomComponentsByIds(Collection<UUID> ids) {
		if (ids == null || ids.isEmpty()) return Map.of();
		Map<UUID, SbomComponent> out = new LinkedHashMap<>();
		sbomComponentRepository.findAllById(ids).forEach(sc -> out.put(sc.getUuid(), sc));
		return out;
	}

	public Optional<ReleaseSbomComponent> getReleaseSbomComponent(UUID uuid) {
		return releaseSbomComponentRepository.findById(uuid);
	}

	public record SbomComponentSearchQuery(String name, String version) {}

	public record ComponentPurlToSbom(String purl, List<UUID> sbomComponents) {}

	/**
	 * Native (non-DependencyTrack) batch search analogue of
	 * {@code IntegrationService.searchDependencyTrackComponentBatch}. Each
	 * input query is matched against {@code sbom_components} narrowed to the
	 * org via {@code release_sbom_components}, then results are grouped by
	 * canonical purl. The grouped shape mirrors {@code ComponentPurlToDtrack}
	 * so the UI can feed the resulting component UUIDs into
	 * {@code releasesBySbomComponents}. Each canonical purl maps to exactly
	 * one {@code sbom_components.uuid} today, so the {@code sbomComponents}
	 * list is typically length 1 — kept as a list for shape parity and
	 * future-proofing.
	 */
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

	/**
	 * Resolve a (possibly qualifier- or subpath-bearing) purl to its canonical
	 * sbom_components row. Returns null if the purl can't be parsed or no
	 * sbom_components row matches the canonical form.
	 */
	public UUID searchSbomComponentByPurl(String purl) {
		String canonical = io.reliza.common.Utils.canonicalizePurl(purl);
		if (canonical == null) return null;
		return sbomComponentRepository.findByCanonicalPurl(canonical)
				.map(SbomComponent::getUuid)
				.orElse(null);
	}

	/**
	 * Distinct release UUIDs whose inventory references any of the given
	 * canonical sbom_components. Returns both (a) component releases that
	 * directly carry the component in {@code release_sbom_components} and
	 * (b) every transitive product release that bundles those component
	 * releases — products no longer materialise their own rows under the
	 * read-time aggregation model, so the upward walk is what makes
	 * impact analysis ("which releases are affected by component X?")
	 * actually surface affected products.
	 *
	 * <p>Org filtering is left to the caller so this can be paired with
	 * {@link SharedReleaseService#getReleaseDataList(Collection, UUID)} which
	 * already applies the org check.
	 */
	public Set<UUID> findReleaseUuidsBySbomComponents(Collection<UUID> sbomComponentUuids) {
		if (sbomComponentUuids == null || sbomComponentUuids.isEmpty()) return Set.of();
		List<UUID> directReleaseUuids = releaseSbomComponentRepository
				.findDistinctReleaseUuidsBySbomComponentUuidIn(sbomComponentUuids);
		if (directReleaseUuids.isEmpty()) return Set.of();
		Set<UUID> all = new LinkedHashSet<>(directReleaseUuids);
		Set<UUID> productCircleBreaker = new HashSet<>();
		for (UUID seed : directReleaseUuids) {
			sharedReleaseService.getReleaseData(seed).ifPresent(rd -> {
				for (ReleaseData product : sharedReleaseService.locateAllProductsOfRelease(rd, productCircleBreaker)) {
					all.add(product.getUuid());
				}
			});
		}
		return all;
	}

	/**
	 * Pull all BOM-bearing artifact UUIDs that participate in a single
	 * release: inbound + outbound deliverables, source-code entry artifacts
	 * scoped to the release's component, and release-attached artifacts.
	 *
	 * <p>For PRODUCT releases we deliberately do <em>not</em> recurse into
	 * dependencies here — dep aggregation now happens at read time in
	 * {@link #listReleaseSbomComponents(UUID)}. This keeps each
	 * {@code release_sbom_components} row strictly local to its own release
	 * (a product reconcile only writes rows for BOMs the product itself
	 * carries, e.g. an aggregate uploaded directly), and the read path
	 * unions across deps so a product's inventory always reflects the
	 * latest dep state without re-reconciling the product.
	 */
	private Set<UUID> collectBomArtifactUuids(ReleaseData rd) {
		Set<UUID> artifactUuids = new LinkedHashSet<>();

		List<UUID> deliverableUuids = new ArrayList<>();
		if (rd.getInboundDeliverables() != null) deliverableUuids.addAll(rd.getInboundDeliverables());
		variantService.findBaseVariantForRelease(rd.getUuid())
				.ifPresent(v -> deliverableUuids.addAll(v.getOutboundDeliverables()));
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
