/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.packageurl.PackageURL;

import io.reliza.common.CommonVariables;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils;
import org.springframework.transaction.annotation.Transactional;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.EnvironmentType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ComponentData;
import io.reliza.model.GenericReleaseData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ReleaseData.ReleaseDateComparator;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseVersionComparator;
import io.reliza.model.dto.CveSearchResultDto;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.CveSearchResultDto.BranchWithReleases;
import io.reliza.model.dto.CveSearchResultDto.ComponentWithBranches;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.repositories.ReleaseLiteRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.dto.ChangelogRecords.CommitRecord;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SharedReleaseService {

	@Autowired
	BranchService branchService;

	@Autowired
	GetComponentService getComponentService;

	@Autowired
	GetSourceCodeEntryService getSourceCodeEntryService;

	@Autowired
	ArtifactService artifactService;

	@Autowired
	private SidPurlResolver sidPurlResolver;

	// Best-effort, REQUIRES_NEW + afterCommit emitter of finding-change deltas (board task #38,
	// phase 1). Living in its own bean keeps the diff-emit in a physically separate transaction so a
	// diff-emit failure can never roll back the customer-critical metrics write.
	//
	// @Lazy: the emitter depends (transitively) on FindingComparisonService, which depends on this
	// SharedReleaseService by constructor -- so a direct injection would re-form the original
	// FindingComparisonService <-> SharedReleaseService cycle through the emitter. @Lazy on this seam
	// (the emitter is only touched on the metrics-write path, via an afterCommit callback) breaks it;
	// the previous @Lazy on the FindingComparisonService field is removed.
	@Autowired
	@Lazy
	private FindingChangeEventEmitter findingChangeEventEmitter;

	public final static Integer DEFAULT_NUM_RELEASES = 300;
	private final static Integer DEFAULT_NUM_RELEASES_FOR_LATEST_RELEASE = 10;
	
	private final ReleaseRepository repository;
	private final ReleaseLiteRepository liteRepository;
	private final MetricsAuditRepository metricsAuditRepository;

	SharedReleaseService(ReleaseRepository repository, ReleaseLiteRepository liteRepository, MetricsAuditRepository metricsAuditRepository) {
		this.repository = repository;
		this.liteRepository = liteRepository;
		this.metricsAuditRepository = metricsAuditRepository;
	}
	
	@Transactional
	public void touchReleaseLastScanned(UUID releaseUuid) {
		repository.touchLastScanned(releaseUuid);
	}

	/**
	 * Per-org change signal for the today-analytics refresh: the pair moves on
	 * every metrics write (SUM of per-row metrics_revision counters) and on any
	 * release entering/leaving the scannable (ASSEMBLED+) set. Consumed by
	 * OssAnalyticsMetricsService, which diffs it against an in-memory snapshot
	 * to decide which orgs need their "today" analytics row recomputed.
	 */
	public record OrgMetricsSignal(long revSum, long assembledCount, long maxUpdatedEpoch) {}

	public Map<UUID, OrgMetricsSignal> getOrgMetricsSignals() {
		Map<UUID, OrgMetricsSignal> signals = new HashMap<>();
		for (Object[] row : repository.findOrgMetricsSignals()) {
			try {
				signals.put(UUID.fromString((String) row[0]), new OrgMetricsSignal(
						((Number) row[1]).longValue(), ((Number) row[2]).longValue(),
						((Number) row[3]).longValue()));
			} catch (IllegalArgumentException e) {
				// org value in record_data isn't a UUID -- skip the garbage row
				log.warn("Skipping org metrics signal row with malformed org: {}", row[0]);
			}
		}
		return signals;
	}


	@Transactional
	public void saveReleaseMetrics (Release r, ReleaseMetricsDto metrics) {
		try {
			// Pre-overwrite ("old live") metrics map: r.getMetrics() still holds the persisted
			// snapshot here -- updateMetrics() below is what writes the new one to the DB.
			Map<String, Object> oldLiveRaw = r.getMetrics();
			// changeDate must be IDENTICAL to the audit row's revisionCreatedDate so the changelog
			// re-source (phase 3) buckets the change at the same instant the snapshot rolled over.
			ZonedDateTime revisionCreatedDate = ZonedDateTime.now();
			if (oldLiveRaw != null) {
				int revision = r.getMetricsRevision();
				int maxAuditRevision = metricsAuditRepository.findMaxRevision(
						MetricsEntityType.RELEASE.name(), r.getUuid());
				if (maxAuditRevision >= revision) {
					revision = maxAuditRevision + 1;
					log.error("Duplicate metrics audit revision detected for release {} - expected {} but max audit is {}, bumping to {}",
							r.getUuid(), r.getMetricsRevision(), maxAuditRevision, revision);
					repository.bumpMetricsRevision(r.getUuid());
				}
				MetricsAudit audit = buildMetricsAudit(r, revision, revisionCreatedDate);
				metricsAuditRepository.save(audit);
				// Additive emit of finding-change deltas (board task #38, phase 1) -- DECOUPLED from
				// this transaction. Deferred to afterCommit + REQUIRES_NEW so a diff-emit failure can
				// NEVER roll back the customer-critical metrics + metrics_audit write (this runs on the
				// hot DT-sync ingestion path), and so a rolled-back metrics tx leaves NO orphan
				// finding_change_events rows. The metrics_audit write above is unchanged -- the
				// changelog still reads audit until phase 3.
				scheduleFindingChangeEventEmit(r, oldLiveRaw, metrics, revisionCreatedDate, revision);
			}
			String metricsJson = Utils.OM.writeValueAsString(metrics);
			repository.updateMetrics(r.getUuid(), metricsJson);
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("Failed to serialize release metrics for release " + r.getUuid(), e);
		}
	}

	/**
	 * Best-effort, transaction-isolated scheduling of the finding-change-event emit (board task #38,
	 * phase 1). The emit is deferred to an {@code afterCommit} synchronization that calls the
	 * {@link FindingChangeEventEmitter} (its own {@code REQUIRES_NEW} bean), so:
	 * <ul>
	 *   <li>it fires ONLY if the metrics transaction actually committed -- a rolled-back metrics write
	 *       never leaves orphan {@code finding_change_events} rows (the synchronization simply does not
	 *       run, and a replayed save re-registers it);</li>
	 *   <li>it runs in a physically separate transaction, so it cannot mark the metrics tx
	 *       rollback-only -- the metrics + metrics_audit write stay atomic with each other and
	 *       unaffected;</li>
	 *   <li>any failure is caught, logged at ERROR (operator alerting fires on ERROR) and swallowed --
	 *       it can never propagate back into {@code saveReleaseMetrics}.</li>
	 * </ul>
	 * When there is no ambient synchronization (e.g. a direct unit-test invocation with no tx manager),
	 * the emit runs inline -- there is no shared tx to poison and best-effort swallow still applies.
	 */
	private void scheduleFindingChangeEventEmit(Release r, Map<String, Object> oldLiveRaw,
			ReleaseMetricsDto metrics, ZonedDateTime revisionCreatedDate, int revision) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					emitFindingChangeEventsBestEffort(r, oldLiveRaw, metrics, revisionCreatedDate, revision);
				}
			});
		} else {
			emitFindingChangeEventsBestEffort(r, oldLiveRaw, metrics, revisionCreatedDate, revision);
		}
	}

	/** Invokes the REQUIRES_NEW emitter, swallowing (and logging at ERROR) any failure. */
	private void emitFindingChangeEventsBestEffort(Release r, Map<String, Object> oldLiveRaw,
			ReleaseMetricsDto metrics, ZonedDateTime revisionCreatedDate, int revision) {
		try {
			findingChangeEventEmitter.emit(r, oldLiveRaw, metrics, revisionCreatedDate, revision);
		} catch (Exception e) {
			log.error("Failed to emit finding_change_events for release {} (revision {}) -- metrics + audit "
					+ "write are unaffected; this diff-emit is best-effort", r.getUuid(), revision, e);
		}
	}

	private MetricsAudit buildMetricsAudit(Release r, int revision, ZonedDateTime revisionCreatedDate) {
		MetricsAudit audit = new MetricsAudit();
		audit.setEntityType(MetricsEntityType.RELEASE);
		audit.setEntityUuid(r.getUuid());
		audit.setOrg(UUID.fromString((String) r.getRecordData().get("org")));
		audit.setMetricsRevision(revision);
		audit.setRevisionCreatedDate(revisionCreatedDate);
		audit.setEntityCreatedDate(r.getCreatedDate());
		audit.setMetrics(r.getMetrics());
		return audit;
	}

	/**
	 * Validates that adding the given parent releases to release {@code selfUuid} would not
	 * create a circular dependency. Walks the ancestry graph of each proposed parent
	 * transitively; if {@code selfUuid} is encountered, the dependency chain is circular.
	 *
	 * @throws RelizaException if a cycle is detected
	 */
	public void checkCircularDependency(UUID selfUuid, Collection<ParentRelease> proposedParents) throws RelizaException {
		Set<UUID> visited = new HashSet<>();
		Deque<UUID> queue = new ArrayDeque<>();
		for (ParentRelease pr : proposedParents) {
			queue.add(pr.getRelease());
		}
		while (!queue.isEmpty()) {
			UUID current = queue.poll();
			if (selfUuid.equals(current)) {
				throw new RelizaException("Circular dependency detected: release " + selfUuid + " would depend on itself");
			}
			if (visited.add(current)) {
				getReleaseData(current).ifPresent(rd ->
					rd.getParentReleases().forEach(pr -> queue.add(pr.getRelease()))
				);
			}
		}
	}

	public Optional<Release> getRelease (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<Release> getRelease (UUID releaseUuid, UUID orgUuid) {
		return repository.findReleaseByIdAndOrg(releaseUuid, orgUuid.toString());
	}
	
	public Optional<ReleaseData> getReleaseData (UUID uuid) {
		Optional<ReleaseData> ord = Optional.empty();
		Optional<Release> r = getRelease(uuid);
		if (r.isPresent()) {
			ord = Optional
							.of(
								ReleaseData
									.dataFromRecord(r
										.get()
								));
		}
		return ord;
	}
	
	/**
	 * Totals-only read: returns ReleaseData built from the light view (no
	 * per-finding metric detail arrays, no approval/update events). Use on read
	 * paths that only need release fields + severity/policy totals to avoid
	 * loading the heavy metrics jsonb. For detail-level needs use
	 * {@link #getReleaseData(UUID)}.
	 */
	public Optional<ReleaseData> getReleaseDataLight (UUID uuid) {
		return liteRepository.findById(uuid).map(ReleaseData::fromLite);
	}

	/**
	 * Batch totals-only read. Skips uuids that no longer resolve (logged), like
	 * {@link #getReleaseDataList(Collection, UUID)}.
	 */
	/** Earliest created_date among a component's releases; null when it has none. */
	public ZonedDateTime findEarliestReleaseDateOfComponent (UUID componentUuid) {
		java.time.Instant earliest = repository.findEarliestReleaseDateOfComponent(componentUuid.toString());
		return earliest == null ? null : earliest.atZone(java.time.ZoneOffset.UTC);
	}

	public List<ReleaseData> getReleaseDataListLight (Collection<UUID> uuidList) {
		if (uuidList == null || uuidList.isEmpty()) {
			return new LinkedList<>();
		}
		return liteRepository.findByUuidIn(uuidList).stream()
				.map(ReleaseData::fromLite)
				.collect(Collectors.toCollection(LinkedList::new));
	}

	/**
	 * Org-scoped batch totals-only read -- drop-in for
	 * {@link #getReleaseDataList(Collection, UUID)} that mirrors its org filter
	 * (releases whose org differs are skipped, as the org-scoped per-uuid load
	 * does) while avoiding the heavy metrics detail arrays and events.
	 */
	public List<ReleaseData> getReleaseDataListLight (Collection<UUID> uuidList, UUID org) {
		if (uuidList == null || uuidList.isEmpty()) {
			return new LinkedList<>();
		}
		return liteRepository.findByUuidIn(uuidList).stream()
				.map(ReleaseData::fromLite)
				.filter(rd -> org == null || org.equals(rd.getOrg()))
				.collect(Collectors.toCollection(LinkedList::new));
	}

	/**
	 * Org-scoped totals-only single read -- drop-in for {@link #getReleaseData(UUID, UUID)}
	 * that returns the release only when it belongs to {@code org}, while avoiding the
	 * heavy metrics detail arrays and approval/update events.
	 */
	public Optional<ReleaseData> getReleaseDataLight (UUID uuid, UUID org) {
		return liteRepository.findById(uuid).map(ReleaseData::fromLite)
				.filter(rd -> org == null || org.equals(rd.getOrg()));
	}

	public Optional<ReleaseData> getReleaseData (UUID uuid, UUID myOrgUuid) {
		Optional<ReleaseData> orData = Optional.empty();
		Optional<Release> r = getRelease(uuid, myOrgUuid);
		if (r.isPresent()) {
			ReleaseData rd = ReleaseData.dataFromRecord(r.get());
			orData = Optional.of(rd);
		}
		return orData;
	}

	/**
	 * This method returns latest Release data of specific branch or feature set
	 * @param branchUuid - UUID of desired branch or feature set
	 * @param et - Environment Type for which to check specific approvals, if empty method attempts to find latest release
	 * @return Optional of latest release data for specified environment; if not found returns empty Optional
	 */
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID branchUuid) {
		BranchData bd = branchService.getBranchData(branchUuid).get();
		return getReleaseDataOfBranch(bd.getOrg(), branchUuid, ReleaseLifecycle.ASSEMBLED);
	}
	
	/**
	 * This method returns the latest Release data of a specific branch or feature set
	 * which is NOT CANCELLED or REJECTED
	 * @param branchUuid - UUID of desired branch or feature set
	 * @return Optional of latest release data for specified branch, excluding CANCELLED or REJECTED; if not found returns empty Optional
	 */
	public Optional<ReleaseData> getLatestNonCancelledOrRejectedReleaseDataOfBranch(UUID branchUuid) {
		List<ReleaseData> releases = listReleaseDataOfBranch(branchUuid, true); // sorted by version/date desc
		return releases.stream()
				.filter(rd -> rd.getLifecycle() != ReleaseData.ReleaseLifecycle.CANCELLED
						&& rd.getLifecycle() != ReleaseData.ReleaseLifecycle.REJECTED)
				.findFirst();
	}
	/**
	 * This method returns latest Release data of specific branch or feature set
	 * @param branchUuid - UUID of desired branch or feature set
	 * @param et - Environment Type for which to check specific approvals, if empty method attempts to find latest release
	 * @return Optional of latest release data for specified environment; if not found returns empty Optional
	 */
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID orgUuid, UUID branchUuid) {
		return getReleaseDataOfBranch(orgUuid, branchUuid, ReleaseLifecycle.ASSEMBLED);
	}
	
	/**
	 * 
	 * @param orgUuid needs to be included, because branch may belong to external org
	 * @param branchUuid
	 * @param et
	 * @param status
	 * @return
	 */
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID orgUuid, UUID branchUuid, ReleaseLifecycle lifecycle) {
		return getReleaseDataOfBranch(orgUuid, branchUuid, lifecycle, null);
	}
	
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID orgUuid, UUID branchUuid, ReleaseLifecycle lifecycle, ZonedDateTime upToDate) {
		BranchData bd = branchService.getBranchData(branchUuid).get();
		if (null == orgUuid) orgUuid = bd.getOrg();
		ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();
		return getReleaseDataOfBranch(orgUuid, bd, pd, lifecycle, upToDate);
	}
	
	public Optional<ReleaseData> getReleaseDataOfBranch (UUID orgUuid, BranchData bd, ComponentData pd, ReleaseLifecycle lifecycle, ZonedDateTime upToDate) {
		if (null == orgUuid) orgUuid = bd.getOrg();
		List<GenericReleaseData> brReleaseData = listReleaseDataOfBranch(bd.getUuid(), orgUuid, lifecycle, DEFAULT_NUM_RELEASES_FOR_LATEST_RELEASE, upToDate);
		if (!brReleaseData.isEmpty()) {
			Collections.sort(brReleaseData, new ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
			return Optional.of((ReleaseData) brReleaseData.get(0));
		}
		return Optional.empty();
	}
	
	/**
	 * Returns latest release of a branch that has the given EnvironmentType in its approvedEnvironments.
	 * Falls back to the latest release if et is null.
	 */
	public Optional<ReleaseData> getReleaseDataOfBranchByEnvironment (UUID orgUuid, UUID branchUuid, EnvironmentType et) {
		if (null == et) {
			return getReleaseDataOfBranch(orgUuid, branchUuid);
		}
		BranchData bd = branchService.getBranchData(branchUuid).get();
		if (null == orgUuid) orgUuid = bd.getOrg();
		ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();
		List<GenericReleaseData> brReleaseData = listReleaseDataOfBranch(branchUuid, orgUuid, ReleaseLifecycle.ASSEMBLED, DEFAULT_NUM_RELEASES_FOR_LATEST_RELEASE, null);
		if (!brReleaseData.isEmpty()) {
			Collections.sort(brReleaseData, new ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
			for (GenericReleaseData grd : brReleaseData) {
				Optional<ReleaseData> ord = getReleaseData(grd.getUuid(), bd.getOrg());
				if (ord.isPresent() && ord.get().getApprovedEnvironments().contains(et)) {
					return ord;
				}
			}
		}
		return Optional.empty();
	}
	
	public List<ReleaseData> listReleaseDataOfBranch (UUID branchUuid) {
		return listReleaseDataOfBranch(branchUuid, false);
	}

	/**
	 * Look up a release by (component, version). Releases.component is a
	 * denormalised field on record_data; the unique index covers
	 * (component, version) so this is a direct read. Used by the
	 * getNewVersion → PR-upsert path where the caller has only the
	 * version string the wrapper just returned.
	 */
	public Optional<Release> findReleaseByComponentAndVersion (UUID componentUuid, String version) {
		if (componentUuid == null || version == null) return Optional.empty();
		return repository.findByComponentAndVersion(componentUuid.toString(), version);
	}
	
	public List<ReleaseData> listReleaseDataOfBranch (UUID branchUuid, boolean sorted) {
		return listReleaseDataOfBranch(branchUuid, 300, sorted);
	}
	
	public List<ReleaseData> listReleaseDataOfBranch (UUID branchUuid, Integer numRecords, boolean sorted) {
		if (null == numRecords || 0 == numRecords) numRecords = DEFAULT_NUM_RELEASES;
		List<Release> releases = listReleasesOfBranch(branchUuid, numRecords, 0);
		List<ReleaseData> rdList = releases
										.stream()
										.map(ReleaseData::dataFromRecord)
										.collect(Collectors.toList());
		if (sorted) {
			BranchData bd = branchService.getBranchData(branchUuid).get();
			ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();
			rdList.sort(new ReleaseData.ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
		}
		return rdList;
	}

	/**
	 * Totals-only counterpart to {@link #listReleaseDataOfBranch(UUID, Integer, boolean)}:
	 * same branch filter / ordering / sort, but releases are loaded via the light
	 * view so the heavy metrics detail arrays (and events) are never read. Use on
	 * list paths that only surface release fields + totals.
	 */
	public List<ReleaseData> listReleaseDataOfBranchLight (UUID branchUuid, Integer numRecords, boolean sorted) {
		if (null == numRecords || 0 == numRecords) numRecords = DEFAULT_NUM_RELEASES;
		String limitAsStr = numRecords < 1 ? "ALL" : numRecords.toString();
		List<ReleaseData> rdList = liteRepository.findReleasesOfBranchLite(branchUuid.toString(), limitAsStr, "0")
										.stream()
										.map(ReleaseData::fromLite)
										.collect(Collectors.toList());
		if (sorted) {
			BranchData bd = branchService.getBranchData(branchUuid).get();
			ComponentData pd = getComponentService.getComponentData(bd.getComponent()).get();
			rdList.sort(new ReleaseData.ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
		}
		return rdList;
	}
	
	public List<GenericReleaseData> listReleaseDataOfBranch (UUID branchUuid, UUID orgUuid, ReleaseLifecycle lifecycle, Integer limit) {
		return listReleaseDataOfBranch(branchUuid, orgUuid, lifecycle, limit, null);
	}
	
	public List<GenericReleaseData> listReleaseDataOfBranch (UUID branchUuid, UUID orgUuid, ReleaseLifecycle lifecycle, Integer limit, ZonedDateTime upToDate) {
		List<Release> releases = listReleasesOfBranch(branchUuid, limit, 0, upToDate);
		List<GenericReleaseData> retList = releases
						.stream()
						.map(ReleaseData::dataFromRecord)
						.filter(r -> (null == lifecycle || r.getLifecycle().ordinal() >= lifecycle.ordinal()))
						.collect(Collectors.toList());
		return retList;
	}

	private List<Release> listReleasesOfBranch (UUID branchUuid,  Integer limit, Integer offset) {
		return listReleasesOfBranch(branchUuid, limit, offset, null);
	}

	private List<Release> listReleasesOfBranch (UUID branchUuid,  Integer limit, Integer offset, ZonedDateTime upToDate) {
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		String offsetAsStr = null;
		if (null == offset || offset < 0) {
			offsetAsStr = "0";
		} else {
			offsetAsStr = offset.toString();
		}
		if (upToDate != null) {
			return repository.findReleasesOfBranchUpToDate(branchUuid.toString(), upToDate, limitAsStr, offsetAsStr);
		}
		return repository.findReleasesOfBranch(branchUuid.toString(), limitAsStr, offsetAsStr);
	}

	public List<ReleaseData> listReleaseDatasOfComponent (UUID componentUuid, Integer limit, Integer offset) {
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		String offsetAsStr = null;
		if (null == offset || offset < 0) {
			offsetAsStr = "0";
		} else {
			offsetAsStr = offset.toString();
		}
		var releases = repository.findReleasesOfComponent(componentUuid.toString(), limitAsStr, offsetAsStr);
		return releases.stream().map(ReleaseData::dataFromRecord).toList();
	}
	
	public List<ReleaseData> listProductReleasesOfOrg (UUID org, Long limit, Long offset) {
		String limitAsStr = null;
		if (null == limit || limit < 1) {
			limitAsStr = "ALL";
		} else {
			limitAsStr = limit.toString();
		}
		String offsetAsStr = null;
		if (null == offset || offset < 0) {
			offsetAsStr = "0";
		} else {
			offsetAsStr = offset.toString();
		}
		var releases = repository.findProductReleasesOfOrg(org.toString(), limitAsStr,  offsetAsStr);
		return releases.stream().map(ReleaseData::dataFromRecord).toList();
	}
	
	/**
	 * This method parses 10 most recent releases of a product (could be a component as well) to establish component level dependencies
	 * @param componentUuid
	 * @return
	 */
	public Set<UUID> obtainComponentsOfProductOrComponent (UUID componentUuid, Set<UUID> dedupComponents) {
		Set<UUID> components = new LinkedHashSet<>();
		var latestReleases = listReleaseDatasOfComponent(componentUuid, 10, 0);
		if (!latestReleases.isEmpty()) {
			Set<ReleaseData> releaseDeps = new LinkedHashSet<>();
			latestReleases.forEach(x -> releaseDeps.addAll(unwindReleaseDependencies(x)));
			releaseDeps.forEach(rd -> {
				if (!dedupComponents.contains(rd.getComponent())) {
					components.add(rd.getComponent());
					var recursiveComps = obtainComponentsOfProductOrComponent(rd.getComponent(), components);
					components.addAll(recursiveComps);
				}
			});
		}
		return components;
	}
	
	/**
	 * This method recursively checks all release components, then components of those releases and finally flattens all that to a list
	 * @param releaseUuid
	 * @return List of releases that are dependencies to the release we are unwinding
	 */
	public Set<ReleaseData> unwindReleaseDependencies (ReleaseData rd) {
		Set<ReleaseData> retListOfReleases = new LinkedHashSet<>();
		Set<UUID> retListOfUuids = new HashSet<>(); // needed to check for circular links
		List<UUID> dependencies = rd.getParentReleases().stream().map(ParentRelease::getRelease).collect(Collectors.toList());
		// base case - no dependencies
		if (dependencies.isEmpty()) {
			return retListOfReleases;
		} else {
			List<ReleaseData> depsFromDb = getReleaseDataListLight(dependencies, rd.getOrg());
			depsFromDb.forEach(depData -> {
				// check we're not going in circles
				if (!retListOfUuids.contains(depData.getUuid())) {
					retListOfUuids.add(depData.getUuid());
					retListOfReleases.add(depData);
					// security check - org must either match base release of be known external org
					if (!depData.getOrg().equals(rd.getOrg()) && !depData.getOrg().equals(CommonVariables.EXTERNAL_PROJ_ORG_UUID)) {
						log.error("Security: Release from another organization with id " + depData.getUuid() + " is detected among dependencies of release " + rd.getUuid());
						throw new IllegalStateException("Security: Detected release from a different organization");
					} else {
						Set<ReleaseData> recursiveSet = unwindReleaseDependencies(depData);
						recursiveSet.forEach(recRl -> {
							if (!retListOfUuids.contains(recRl.getUuid())) {
								retListOfUuids.add(recRl.getUuid());
								retListOfReleases.add(recRl);
							}
						});
					}
				}
			});
		}
		
		return retListOfReleases;
	}
	
	public List<ReleaseData> getReleaseDataList (Collection<UUID> uuidList, UUID org) {
		var rlzList = new LinkedList<ReleaseData>();
		uuidList.forEach(uuid -> {
			var rlzO = getReleaseData(uuid, org);
			if (rlzO.isPresent()) {
				rlzList.add(rlzO.get());
			} else {
				log.warn("Could not locate releaze with UUID = " + uuid + " from org = " + org);
			}
		});
		return rlzList;
	}
	
	/**
	 * This method will locate release products with only 1st level depth, without recursion
	 * Much more efficient than locateAllProductsOfRelease
	 * @param rd
	 * @return
	 */
	public Set<ReleaseData> greedylocateProductsOfRelease (ReleaseData rd) {
		return greedylocateProductsOfRelease(rd, null, true);
	}
	
	
	public Set<ReleaseData> greedylocateProductsOfReleaseCollection (Collection<ReleaseData> inputRds, UUID myOrg) {
		var inputSet = inputRds.stream().map(x -> x.getUuid()).collect(Collectors.toSet());
		// construct string for postgres query
		String inputArrStr = constructGreedyProductLocateQueryFromReleaseSet(inputSet);
		List<Release> releaseSet = repository.findProductsByReleases(myOrg.toString(), inputArrStr);
		log.debug("Size of product release set = " + releaseSet.size());
		return releaseSet.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toSet());
	}

	private String constructGreedyProductLocateQueryFromReleaseSet(Set<UUID> inputSet) {
		StringBuilder inputArrStringBuilder = new StringBuilder();
		inputArrStringBuilder.append("[");
		var inputIterator = inputSet.iterator();
		int i=0;
		while (inputIterator.hasNext()) {
			if (i==0) {
				inputArrStringBuilder.append("\"");
			} else {
				inputArrStringBuilder.append(",\"");
			}
			inputArrStringBuilder.append(inputIterator.next().toString());
			inputArrStringBuilder.append("\"");
			++i;
		}
		inputArrStringBuilder.append("]");
		String inputArrStr = inputArrStringBuilder.toString();
		log.debug("String to query for products = " + inputArrStr);
		return inputArrStr;
	}
	
	/**
	 * Inverse of {@link #unwindReleaseDependencies(ReleaseData)} -- recursively
	 * locates every product release that bundles {@code rd}, then every
	 * product that bundles those, and so on. Mirrors the dropped helper that
	 * used to live on ReleaseService; lives here so callers in lower-tier
	 * services (e.g. {@code SbomComponentService}) can reach it without a
	 * circular dependency on ReleaseService.
	 *
	 * @param rd starting release
	 * @param setToBreakCircles mutable visited set; pass an empty set on
	 *        external entry, or a shared one when fanning out across many
	 *        seeds to dedupe across calls.
	 * @return all transitive product releases, deduplicated by uuid.
	 */
	public Set<ReleaseData> locateAllProductsOfRelease(ReleaseData rd, Set<UUID> setToBreakCircles) {
		return locateAllProductsOfRelease(rd, setToBreakCircles, null);
	}

	/**
	 * Org-scoped variant of {@link #locateAllProductsOfRelease(ReleaseData, Set)}.
	 * When {@code myOrg} is non-null the upward walk only surfaces products
	 * owned by that org -- important for impact-analysis on a seed release
	 * that lives in the external/system sentinel org but whose bundling
	 * products are in the caller's org.
	 */
	public Set<ReleaseData> locateAllProductsOfRelease(ReleaseData rd, Set<UUID> setToBreakCircles, UUID myOrg) {
		Set<ReleaseData> products = new LinkedHashSet<>(
				greedylocateProductsOfRelease(rd, myOrg, false));
		if (products.isEmpty()) return products;
		List<ReleaseData> ancestors = new ArrayList<>();
		for (ReleaseData direct : products) {
			if (setToBreakCircles.add(direct.getUuid())) {
				ancestors.addAll(locateAllProductsOfRelease(direct, setToBreakCircles, myOrg));
			}
		}
		products.addAll(ancestors);
		return products;
	}

	/**
	 *
	 * @param rd
	 * @param myOrg - used in case we're dealing with external organization to pin to our org
	 * @return
	 */
	public Set<ReleaseData> greedylocateProductsOfRelease (ReleaseData rd, UUID myOrg, boolean sorted) {
		ReleaseData processingRd = null;
		if (null != myOrg) {
			var ord = getReleaseData(rd.getUuid(), myOrg);
			if (ord.isPresent()) processingRd = ord.get();
		} else {
			// legacy callers not supplying myorg or auth
			processingRd = rd;
			myOrg = rd.getOrg();
		}
		if (null != processingRd) {
			List<Release> wipProducts = this.repository.findProductsByRelease(myOrg.toString(),
					processingRd.getUuid().toString());
			var rdSet = wipProducts.stream().map(ReleaseData::dataFromRecord).collect(Collectors.toSet());

			if (sorted) {
				List<ReleaseData> sortedRdList = new LinkedList<>(rdSet);
				Collections.sort(sortedRdList, new ReleaseDateComparator());
				rdSet = new LinkedHashSet<>(sortedRdList);
			}
			return rdSet;
		} else {
			return Set.of();
		}
	}

	public Set<ParentRelease> getCurrentProductParentRelease(UUID branchUuid, ReleaseLifecycle lifecycle){
		return getCurrentProductParentRelease(branchUuid, null, lifecycle);
	}

	public Set<ParentRelease> getCurrentProductParentRelease(UUID branchUuid, ReleaseData triggeringRelease, ReleaseLifecycle lifecycle){
		BranchData bd = branchService.getBranchData(branchUuid).get();
		return getCurrentProductParentRelease(branchUuid, triggeringRelease, bd.getDependencies(), lifecycle);
	}
	
	public Set<ParentRelease> getCurrentProductParentRelease(UUID branchUuid, ReleaseData triggeringRelease, List<ChildComponent> dependencies, ReleaseLifecycle lifecycle){
		BranchData bd = branchService.getBranchData(branchUuid).get();
		Set<ParentRelease> parentReleases = new HashSet<>();
		boolean requirementsMet = true;
		
		// Group dependencies by component UUID to handle same component with multiple branches.
		// JOB-status deps participate in product-release composition just like REQUIRED /
		// TRANSIENT -- the product release remembers what version of the job was current at
		// integration time, the UI surfaces it, and only the actual-vs-target match treats
		// JOB releases as non-gating (so a stale deployed job doesn't flap the match).
		Map<UUID, List<ChildComponent>> componentToDeps = dependencies.stream()
			.filter(cp -> !cp.getUuid().equals(bd.getComponent()) && (cp.getStatus() == StatusEnum.REQUIRED || cp.getStatus() == StatusEnum.TRANSIENT || cp.getStatus() == StatusEnum.JOB))
			.collect(Collectors.groupingBy(ChildComponent::getUuid));
		
		for (Map.Entry<UUID, List<ChildComponent>> entry : componentToDeps.entrySet()) {
			List<ChildComponent> componentDeps = entry.getValue();
			Optional<ReleaseData> selectedRelease = Optional.empty();
			
			if (componentDeps.size() == 1) {
				// Single dependency for this component
				ChildComponent cp = componentDeps.get(0);
				selectedRelease = getReleaseForChildComponent(cp, triggeringRelease, bd.getOrg(), lifecycle);
			} else {
				// Multiple branches for same component - apply priority logic:
				// 1. If triggering release matches one of the branches, use that
				// 2. If one of the branches is BASE, that takes priority
				// 3. Otherwise, use the latest release by timestamp from any branch
				
				// First check if triggering release matches any branch
				if (triggeringRelease != null) {
					for (ChildComponent cp : componentDeps) {
						if (cp.getBranch() != null && cp.getBranch().equals(triggeringRelease.getBranch())) {
							selectedRelease = Optional.of(triggeringRelease);
							break;
						}
					}
				}
				
				// If no triggering release match, check for BASE branch priority
				if (selectedRelease.isEmpty()) {
					ChildComponent baseBranchDep = null;
					for (ChildComponent cp : componentDeps) {
						if (cp.getBranch() != null) {
							Optional<BranchData> childBranchOpt = branchService.getBranchData(cp.getBranch());
							if (childBranchOpt.isPresent() && childBranchOpt.get().getType() == BranchType.BASE) {
								baseBranchDep = cp;
								break;
							}
						}
					}
					
					if (baseBranchDep != null) {
						// Use BASE branch
						selectedRelease = getReleaseForChildComponent(baseBranchDep, triggeringRelease, bd.getOrg(), lifecycle);
					} else {
						// No BASE branch - get latest release by timestamp from all branches
						List<ReleaseData> candidateReleases = new ArrayList<>();
						for (ChildComponent cp : componentDeps) {
							Optional<ReleaseData> ord = getReleaseForChildComponent(cp, triggeringRelease, bd.getOrg(), lifecycle);
							ord.ifPresent(candidateReleases::add);
						}
						if (!candidateReleases.isEmpty()) {
							// Sort by created date descending and take the latest
							candidateReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
							selectedRelease = Optional.of(candidateReleases.get(0));
						}
					}
				}
			}
			
			if (selectedRelease.isPresent()) {
				ParentRelease dr = ParentRelease.minimalParentReleaseFactory(selectedRelease.get().getUuid());
				parentReleases.add(dr);
			} else {
				// Check if any of the deps for this component is REQUIRED
				boolean anyRequired = componentDeps.stream().anyMatch(cp -> cp.getStatus() == StatusEnum.REQUIRED);
				if (anyRequired) {
					requirementsMet = false;
				}
			}
		}
		
		if(!requirementsMet){
			return new HashSet<>();
		}
		return parentReleases;
	}
	
	private Optional<ReleaseData> getReleaseForChildComponent(ChildComponent cp, ReleaseData triggeringRelease, UUID orgUuid, ReleaseLifecycle lifecycle) {
		if (null != cp.getRelease()) {
			return getReleaseData(cp.getRelease());
		} else if (triggeringRelease != null && cp.getBranch() != null && cp.getBranch().equals(triggeringRelease.getBranch())) {
			return Optional.of(triggeringRelease);
		} else {
			return getReleaseDataOfBranch(orgUuid, cp.getBranch(), lifecycle);
		}
	}
	
	public List<SourceCodeEntryData> getSceDataListFromReleases(List<ReleaseData> releases, UUID org){
		List<UUID> commitIds = releases
			.stream()
			.map(release -> release.getAllCommits())
			.flatMap(x -> x.stream())
			.map(x -> (null == x) ? new UUID(0,0) : x)
			.collect(Collectors.toList());
		List<SourceCodeEntryData> sces = new ArrayList<>();
		if(null!= commitIds && commitIds.size() > 0)
		{
			sces = getSourceCodeEntryService.getSceDataList(commitIds, List.of(org));
		}
		return sces;
	}
	
	public List<ReleaseData> listAllReleasesBetweenReleases(UUID uuid1, UUID uuid2) throws RelizaException{
		List<ReleaseData> rds = new LinkedList<ReleaseData>();

		boolean proceed = false;
		Optional<ReleaseData> or1 = getReleaseData(uuid1);
		Optional<ReleaseData> or2 = getReleaseData(uuid2);
		ReleaseData r1 = null;
		ReleaseData r2 = null;

		proceed = or1.isPresent() && or2.isPresent();

		if(proceed){
			List<Release> releases = new LinkedList<>();
			
			r1 = or1.get();
			r2 = or2.get();
			boolean onSameBranch = r1.getBranch().equals(r2.getBranch());
			var rdc = new ReleaseDateComparator();
			int comparision = rdc.compare(r1, r2);
			
			if(onSameBranch){
				if(comparision >= 0){
					releases = listReleasesOfBranchBetweenDates(
						r1.getBranch(), 
						r1.getCreatedDate(),
						r2.getCreatedDate()
					);
				} else {
					releases = listReleasesOfBranchBetweenDates(
						r1.getBranch(), 
						r2.getCreatedDate(),
						r1.getCreatedDate()
					);
				}
				if(releases.size() > 0)
					rds = releases
						.stream()
						.map(ReleaseData::dataFromRecord)
						.collect(Collectors.toList());
			} else if (comparision >= 0){
				rds.add(r1);
				rds.add(r2);
			} else {
				rds.add(r2);
				rds.add(r1);
			}
		}

		return rds;
	}
	
	//fromDateTime is exclusive, toDateTime is inclusive (releases where fromDateTime < created_date <= toDateTime)
	private List<Release> listReleasesOfBranchBetweenDates (UUID branchUuid,  ZonedDateTime fromDateTime, ZonedDateTime toDateTime) {
		return repository.findReleasesOfBranchBetweenDates(branchUuid.toString(), Utils.stringifyZonedDateTimeForSql(fromDateTime),
				Utils.stringifyZonedDateTimeForSql(toDateTime));
	}
	
	public List<ReleaseData> listReleaseDataOfBranchBetweenDates(UUID branchUuid, ZonedDateTime fromDateTime, 
			ZonedDateTime toDateTime, ReleaseLifecycle minLifecycle) {
		return listReleasesOfBranchBetweenDates(branchUuid, fromDateTime, toDateTime)
				.stream()
				.map(ReleaseData::dataFromRecord)
				.filter(rd -> rd.getLifecycle() != null && 
						rd.getLifecycle().ordinal() >= minLifecycle.ordinal())
				.collect(Collectors.toList());
	}
	
	public List<ReleaseData> listReleaseDataOfComponentBetweenDates(UUID componentUuid, ZonedDateTime fromDateTime,
			ZonedDateTime toDateTime, ReleaseLifecycle minLifecycle) {
		return repository.findReleasesOfComponentBetweenDates(componentUuid.toString(), fromDateTime, toDateTime)
				.stream()
				.map(ReleaseData::dataFromRecord)
				.filter(rd -> rd.getLifecycle() != null &&
						rd.getLifecycle().ordinal() >= minLifecycle.ordinal())
				.collect(Collectors.toList());
	}

	/**
	 * BATCHED org-wide analogue of {@link #listReleaseDataOfComponentBetweenDates} (org posture-diff N+1
	 * elimination): every in-window release of the ORG in ONE query, grouped by component UUID. Applies
	 * the same {@code minLifecycle} filter in memory as the per-component method, so each component's list
	 * is identical to what a per-component call would return (modulo ordering, which the caller re-sorts).
	 * Components with no in-window release are simply absent from the map (caller defaults to empty).
	 */
	public Map<UUID, List<ReleaseData>> listReleaseDataOfOrgBetweenDatesByComponent(UUID orgUuid,
			ZonedDateTime fromDateTime, ZonedDateTime toDateTime, ReleaseLifecycle minLifecycle) {
		return repository.findReleasesOfOrgBetweenDates(orgUuid.toString(), fromDateTime, toDateTime)
				.stream()
				.map(ReleaseData::dataFromRecord)
				.filter(rd -> rd.getLifecycle() != null &&
						rd.getLifecycle().ordinal() >= minLifecycle.ordinal())
				.filter(rd -> rd.getComponent() != null)
				.collect(Collectors.groupingBy(ReleaseData::getComponent));
	}

	/**
	 * Branch-latest release created AT-OR-BEFORE {@code atDateTime} (inclusive). Returns the single
	 * newest non-CANCELLED / non-REJECTED release on the branch whose {@code created_date <= atDateTime},
	 * or empty if none. Bounded per-branch lookup (LIMIT 1), used by the changelog posture-diff to seed
	 * each branch's from-baseline (the snapshot in effect at the window start).
	 */
	public Optional<ReleaseData> getBranchLatestReleaseAtOrBeforeDate(UUID branchUuid, ZonedDateTime atDateTime) {
		if (branchUuid == null || atDateTime == null) return Optional.empty();
		UUID releaseUuid = repository.findLatestReleaseAtOrBeforeTimestamp(branchUuid.toString(), atDateTime.toString());
		if (releaseUuid == null) return Optional.empty();
		return getReleaseData(releaseUuid);
	}

	/** Chunk size for the batched branch-latest lookup array param (well under any parameter/array limit). */
	private static final int BRANCH_LATEST_BATCH_CHUNK = 1000;

	/**
	 * BATCHED form of {@link #getBranchLatestReleaseAtOrBeforeDate} (org posture-diff N+1 elimination):
	 * for EACH branch in {@code branchUuids}, its latest non-CANCELLED / non-REJECTED release created
	 * {@code <= atDateTime}, resolved in a handful of round-trips instead of one LIMIT-1 query per
	 * branch. Returns a map keyed by branch UUID; branches with no qualifying release are simply absent
	 * (mirrors the single-branch {@code Optional.empty()}). The array param is chunked at
	 * {@value #BRANCH_LATEST_BATCH_CHUNK} branches per call to stay well within Postgres array limits;
	 * results are merged. Per-branch result is byte-identical to the single-branch query (same
	 * DISTINCT-ON / created_date DESC / lifecycle-exclusion semantics).
	 */
	public Map<UUID, ReleaseData> getBranchLatestReleasesAtOrBeforeDate(Collection<UUID> branchUuids, ZonedDateTime atDateTime) {
		Map<UUID, ReleaseData> result = new HashMap<>();
		if (branchUuids == null || branchUuids.isEmpty() || atDateTime == null) return result;
		List<UUID> distinct = branchUuids.stream().filter(Objects::nonNull).distinct().toList();
		String timestamp = atDateTime.toInstant().toString(); // RFC-3339 Z instant: a region-zoned ZonedDateTime would render a [Region] suffix Postgres rejects
		for (int i = 0; i < distinct.size(); i += BRANCH_LATEST_BATCH_CHUNK) {
			List<UUID> chunk = distinct.subList(i, Math.min(i + BRANCH_LATEST_BATCH_CHUNK, distinct.size()));
			String[] chunkStrings = chunk.stream().map(UUID::toString).toArray(String[]::new);
			for (Release r : repository.findLatestReleasesAtOrBeforeTimestampBatch(chunkStrings, timestamp)) {
				ReleaseData rd = ReleaseData.dataFromRecord(r);
				if (rd.getBranch() != null) {
					result.put(rd.getBranch(), rd);
				}
			}
		}
		return result;
	}

	/**
	 * Find the previous release to compare against for a given release.
	 * For releases with a previous release on the same branch, returns that release.
	 * For the first release on a branch (Branch Root), computes the Inferred Fork Point
	 * by finding the most recent release on the base branch created before this release.
	 * 
	 * TODO: Future Enhancement - Exact Parent Linking (parent_hash Strategy)
	 * Current implementation uses timestamp-based inference which has limitations:
	 * - Cannot handle "Stacked Branches" (Grandchild -> Child -> Main)
	 * - Subject to race conditions with concurrent commits
	 * 
	 * Future approach:
	 * 1. Schema: Add parent_hash column to commits table (store Git parent hash from %P)
	 * 2. Ingestion: Update git log command to extract parent hash for each commit
	 * 3. Lookup: When findPreviousCommit returns null (Branch Root):
	 *    - Read parent_hash from current commit
	 *    - Query: SELECT branchId FROM commits WHERE hash = :parent_hash
	 *    - Link to that branch's latest release (supports stacked branches)
	 * 
	 * This would eliminate timestamp estimation and reflect the exact Git DAG structure.
	 * 
	 * @param branchUuid - UUID of the branch
	 * @param release - UUID of the release to find previous for
	 * @return UUID of the previous release, or null if none found
	 */
	public UUID findPreviousReleasesOfBranchForRelease (UUID branchUuid,  UUID release) {
		return findPreviousReleasesOfBranchForRelease(branchUuid, release, null, null, null);
	}

	/**
	 * Optimized overload that accepts pre-resolved data to avoid redundant DB lookups.
	 * When releaseData and componentData are provided, skips getReleaseData() and getComponentData() calls.
	 * When baseBranchCache is provided, caches the base branch UUID per component to avoid repeated findBranchByName() calls.
	 */
	public UUID findPreviousReleasesOfBranchForRelease (UUID branchUuid, UUID release,
			ReleaseData releaseData, ComponentData componentData, Map<UUID, Optional<UUID>> baseBranchCache) {
		UUID prevReleaseId = null;

		prevReleaseId = repository.findPreviousReleasesOfBranchForRelease(branchUuid.toString(), release);
		// would be null for First release on this branch (Branch Root): compute Inferred Fork Point

		if(prevReleaseId == null){
			// Use pre-resolved releaseData if available, otherwise fetch from DB
			ReleaseData rd = releaseData;
			if (rd == null) {
				Optional<ReleaseData> ord = getReleaseData(release);
				rd = ord.orElse(null);
			}
			if (rd != null) {
				UUID componentId = rd.getComponent();
				ZonedDateTime branchRootTimestamp = rd.getCreatedDate(); // T_Start
				
				// Use pre-resolved componentData if available, otherwise fetch from DB
				String baseBranchName = null;
				if (componentData != null && componentData.getDefaultBranch() != null) {
					baseBranchName = componentData.getDefaultBranch().name();
				} else if (componentData == null) {
					Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
					if (ocd.isPresent() && ocd.get().getDefaultBranch() != null) {
						baseBranchName = ocd.get().getDefaultBranch().name();
					}
				}
				
				if (baseBranchName != null) {
					try {
						// Use baseBranchCache if available to avoid repeated findBranchByName calls
						UUID baseBranchUuid = null;
						if (baseBranchCache != null && baseBranchCache.containsKey(componentId)) {
							Optional<UUID> cached = baseBranchCache.get(componentId);
							baseBranchUuid = cached.orElse(null);
						} else {
							Optional<Branch> baseBranchOpt = branchService.findBranchByName(componentId, baseBranchName.toLowerCase());
							if (baseBranchOpt.isPresent()) {
								baseBranchUuid = baseBranchOpt.get().getUuid();
							}
							if (baseBranchCache != null) {
								baseBranchCache.put(componentId, Optional.ofNullable(baseBranchUuid));
							}
						}
						
						if (baseBranchUuid != null) {
							// Inferred Fork Point: Find the most recent release on base branch created BEFORE T_Start
							UUID inferredForkPointId = repository.findLatestReleaseBeforeTimestamp(
								baseBranchUuid.toString(), 
								branchRootTimestamp.toString()
							);
							
							if (inferredForkPointId != null) {
								prevReleaseId = inferredForkPointId;
								log.debug("Inferred Fork Point found for Branch Root release {} at timestamp {}: base branch release {}", 
									release, branchRootTimestamp, inferredForkPointId);
							} else {
								// Fallback: if no release exists before T_Start, use latest release on base branch
								Optional<ReleaseData> baseBranchLatestRd = getLatestNonCancelledOrRejectedReleaseDataOfBranch(baseBranchUuid);
								if (baseBranchLatestRd.isPresent()) {
									prevReleaseId = baseBranchLatestRd.get().getUuid();
									log.debug("No Inferred Fork Point found before timestamp {}. Falling back to latest base branch release: {}", 
										branchRootTimestamp, prevReleaseId);
								} else {
									log.debug("No release found on base branch {} for comparison.", baseBranchName);
								}
							}
						} else {
							log.debug("Base branch {} not found for component {}", baseBranchName, componentId);
						}
					} catch (Exception e) {
						log.error("Error finding base branch by name: {}", baseBranchName, e);
					}
				}
			}
		}
		
		return prevReleaseId;
	}
	public UUID findNextReleasesOfBranchForRelease (UUID branchUuid,  UUID release) {
		return repository.findNextReleasesOfBranchForRelease(branchUuid.toString(), release);
	}

	/**
	 * Strict same-branch previous release: the raw LAG query without the
	 * inferred-fork-point fallback that {@link #findPreviousReleasesOfBranchForRelease}
	 * applies at a branch root. Used for release-view navigation, which must stay
	 * within the same branch / feature set (returns null at the first release).
	 */
	public UUID findPreviousReleaseStrictlyOnBranch (UUID branchUuid, UUID release) {
		return repository.findPreviousReleasesOfBranchForRelease(branchUuid.toString(), release);
	}
	

	/**
	 * This method attempts to prepare a map of commit id to message for all commits
	 * @param sces - List of sce data
	 * @param org - UUID of the org
	 * @param commitIdToMessageMap - Map of commit id to commit, message, and uri for all commits in the releases
	 * @return Map of commit id to message for all commits
	 */
	public Map<UUID, CommitRecord> getCommitMessageMapForSceDataList(List<SourceCodeEntryData> sces, List<VcsRepositoryData> vrds, UUID org){
		
		Map<UUID, CommitRecord> commitIdToMessageMap = new HashMap<UUID, CommitRecord>();
		// List<SourceCodeEntryData> sces = getSceDataListFromReleases(releases, org);
		if(null!= sces && sces.size() > 0)
		{
			sces
			.stream()
			.filter(Objects::nonNull)
			.forEach(sce -> {
				String msg = StringUtils.isNotEmpty(sce.getCommitMessage()) ? sce.getCommitMessage() : "(No commit message)";
				List<VcsRepositoryData> vcsRepo = vrds.stream().filter(vrd -> vrd.getUuid().equals(sce.getVcs())).collect(Collectors.toList());
				String commitUri = vcsRepo.size() > 0 ? vcsRepo.get(0).getUri() : "";
				commitIdToMessageMap.put(sce.getUuid(), new CommitRecord(commitUri, sce.getCommit(), msg, sce.getCommitAuthor(), sce.getCommitEmail()));
			});  // Collectors.toMap throws npe on null values - JDK-8148463(https://bugs.openjdk.java.net/browse/JDK-8148463) 
				// .collect(Collectors.toMap(
				// 	SourceCodeEntryData::getUuid, 
				// 	SourceCodeEntryData::getCommitMessage
				// ));
		}
		return commitIdToMessageMap;
	}
	
	// --- sid PURL orchestrator ---

	/** Output of {@link #buildReleaseIdentifiers}. The orchestrator does not mutate inputs. */
	public record BuildIdentifiersResult(
			List<RearmIdentifier> identifiers,
			String sidComponentNameSnapshot
	) {}

	/**
	 * Build the canonical identifier list for a release at creation time.
	 *
	 * <p>Caller-supplied identifiers are preserved; a platform sid PURL is appended when
	 * the resolver returns enabled; carryover from the component's own PURL runs only when
	 * the caller supplied no identifiers. Existing snapshot is honored as-is -- never overwritten.
	 *
	 * @throws RelizaException on perspective ambiguity, or when the resolver returns enabled
	 *   with invalid authority segments (would emit a malformed {@code pkg:sid//...}).
	 */
	public BuildIdentifiersResult buildReleaseIdentifiers(ComponentData cd, OrganizationData org, String version,
			String existingSidComponentName, List<RearmIdentifier> callerProvided) throws RelizaException {
		List<RearmIdentifier> result = (callerProvided != null && !callerProvided.isEmpty())
				? new LinkedList<>(callerProvided)
				: new LinkedList<>();

		SidPurlResolver.ResolvedSidPolicy policy = sidPurlResolver.resolveForComponent(cd, org);

		// Strip caller-supplied sid only when the platform is about to emit its own --
		// otherwise vendor-supplied sid (typical for EXTERNAL components) passes through.
		if (policy.enabled()) {
			result.removeIf(SharedReleaseService::isSidPurlIdentifier);
		}

		String snapshotToWrite = existingSidComponentName;
		if (policy.enabled()) {
			SidIdentifierResult sidResult = buildSidPurlIdentifier(cd, policy, version, existingSidComponentName);
			result.add(sidResult.identifier());
			snapshotToWrite = sidResult.snapshotName();
		}

		boolean callerEmpty = (callerProvided == null || callerProvided.isEmpty());
		if (callerEmpty) {
			Optional<RearmIdentifier> carry = deriveCarryoverPurl(cd, version, policy.enabled());
			carry.ifPresent(result::add);
		}

		return new BuildIdentifiersResult(result, snapshotToWrite);
	}

	private record SidIdentifierResult(RearmIdentifier identifier, String snapshotName) {}

	/**
	 * Version-stamp the component's first PURL into a release identifier.
	 * {@code skipSid=true} skips {@code pkg:sid/...} on the scan so a stale vendor sid
	 * doesn't race the platform-emitted one. {@code skipSid=false} preserves it -- that's
	 * how vendor-asserted sid identity rides through to releases of EXTERNAL components.
	 * Returns empty (not throws) on failure so callers can decide.
	 */
	public Optional<RearmIdentifier> deriveCarryoverPurl(ComponentData cd, String version, boolean skipSid) {
		try {
			List<RearmIdentifier> compIdentifiers = cd.getIdentifiers();
			if (compIdentifiers == null || compIdentifiers.isEmpty()) {
				return Optional.empty();
			}
			Optional<RearmIdentifier> purlIdentifier = compIdentifiers.stream()
					.filter(ti -> ti.getIdType() == RearmIdentifierType.PURL)
					.filter(ti -> !skipSid || !isSidPurlIdentifier(ti))
					.findFirst();
			if (purlIdentifier.isEmpty()) {
				return Optional.empty();
			}
			PackageURL purlObj = new PackageURL(purlIdentifier.get().getIdValue());
			PackageURL versionedPurl = Utils.setVersionOnPurl(purlObj, version);
			RearmIdentifier teaPurl = new RearmIdentifier();
			teaPurl.setIdType(RearmIdentifierType.PURL);
			teaPurl.setIdValue(versionedPurl.canonicalize());
			return Optional.of(teaPurl);
		} catch (Exception e) {
			log.error("Error deriving carryover PURL for component {}", cd.getUuid(), e);
			return Optional.empty();
		}
	}

	/**
	 * Build a sid PURL identifier from a pre-resolved policy. Throws if the policy is
	 * enabled but its segments are invalid -- never emits {@code pkg:sid//<name>@v}.
	 * Returns the snapshot the caller should persist (existing one if non-null,
	 * otherwise {@code cd.getName()} captured here). Caller must ensure
	 * {@code policy.enabled() == true}.
	 */
	private SidIdentifierResult buildSidPurlIdentifier(ComponentData cd,
			SidPurlResolver.ResolvedSidPolicy policy, String version, String existingSnapshot)
			throws RelizaException {
		SidPurlUtils.ValidationResult vr = SidPurlUtils.validateAuthoritySegments(policy.authoritySegments());
		if (!vr.valid()) {
			throw new RelizaException("Cannot emit sid PURL for component " + cd.getUuid()
					+ ": resolver returned enabled but authority segments are invalid (" + vr.error() + ")");
		}

		// Snapshot is immutable once set; updateRelease enforces this on the write side.
		String snapshotName = existingSnapshot != null ? existingSnapshot : cd.getName();

		String sidPurl = SidPurlUtils.buildSidPurl(policy.authoritySegments(), snapshotName, version,
				/* qualifiers */ null, /* subpath */ null);

		RearmIdentifier ti = new RearmIdentifier();
		ti.setIdType(RearmIdentifierType.PURL);
		ti.setIdValue(sidPurl);
		return new SidIdentifierResult(ti, snapshotName);
	}

	/**
	 * @return true iff the identifier is a PURL whose parsed type is {@code "sid"}.
	 *   Delegates to {@link SidPurlUtils#isSidPurl(String)}.
	 */
	private static boolean isSidPurlIdentifier(RearmIdentifier ti) {
		if (ti == null || ti.getIdType() != RearmIdentifierType.PURL) {
			return false;
		}
		return SidPurlUtils.isSidPurl(ti.getIdValue());
	}
	
	private Set<UUID> gatherReleaseIdsForArtifact(UUID artifactUuid, UUID orgUuid){
		Set<UUID> releases = new HashSet<>();
		Set<UUID> allDirectReleases = findReleasesByReleaseArtifact(artifactUuid, orgUuid).stream().map(r -> r.getUuid()).collect(Collectors.toSet());
		Set<UUID> allSceReleases = repository.findReleasesSharingSceArtifact(artifactUuid.toString()).stream().map(r -> r.getUuid()).collect(Collectors.toSet());
		Set<UUID> allDeliverableReleases = repository.findReleasesSharingDeliverableArtifact(artifactUuid.toString()).stream().map(r -> r.getUuid()).collect(Collectors.toSet());
		releases.addAll(allDirectReleases);
		releases.addAll(allSceReleases);
		releases.addAll(allDeliverableReleases);
		return releases;
	}

	private Set<UUID> gatherReleaseIdsForArtifacts(Collection<UUID> artifactUuids, UUID orgUuid){
		if (artifactUuids == null || artifactUuids.isEmpty()) {
			return new HashSet<>();
		}
		Set<UUID> releases = new HashSet<>();
		Collection<String> artifactUuidsAsStrings = artifactUuids.stream().map(UUID::toString).toList();
		Set<UUID> allDirectReleases = repository.findReleasesByReleaseArtifacts(artifactUuidsAsStrings, orgUuid.toString())
				.stream().map(Release::getUuid).collect(Collectors.toSet());
		Set<UUID> allSceReleases = repository.findReleasesSharingSceArtifacts(artifactUuidsAsStrings)
				.stream().map(Release::getUuid).collect(Collectors.toSet());
		Set<UUID> allDeliverableReleases = repository.findReleasesSharingDeliverableArtifacts(artifactUuidsAsStrings)
				.stream().map(Release::getUuid).collect(Collectors.toSet());
		releases.addAll(allDirectReleases);
		releases.addAll(allSceReleases);
		releases.addAll(allDeliverableReleases);
		return releases;
	}

	public List<ReleaseData> gatherReleasesForArtifact(UUID artifactUuid, UUID orgUuid){
		Set<UUID> releaseIds = gatherReleaseIdsForArtifact(artifactUuid, orgUuid);
		var releaseDatas = getReleaseDataListLight(releaseIds, orgUuid);
		return sortReleasesByBranchAndVersion(releaseDatas);
	}

	private List<ReleaseData> sortReleasesByBranchAndVersion(List<ReleaseData> releaseDatas) {
		// Group releases by branch
		Map<UUID, List<ReleaseData>> releasesByBranch = releaseDatas.stream()
			.collect(Collectors.groupingBy(ReleaseData::getBranch));
		
		// Sort each branch group and collect results
		List<ReleaseData> sortedReleases = new LinkedList<>();
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = new LinkedList<>(entry.getValue());

			Optional<BranchData> bdOpt = branchService.getBranchData(branchUuid);
			if (bdOpt.isPresent()) {
				BranchData bd = bdOpt.get();
				Optional<ComponentData> pdOpt = getComponentService.getComponentData(bd.getComponent());
				if (pdOpt.isPresent()) {
					ComponentData pd = pdOpt.get();
					Collections.sort(branchReleases, new ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
				}
			}
			sortedReleases.addAll(branchReleases);
		}
		return sortedReleases;
	}
	
	public List<Release> findReleasesByReleaseArtifact (UUID artifactUuid, UUID orgUuid) {
		return repository.findReleasesByReleaseArtifact(artifactUuid.toString(), orgUuid.toString());
	}
	
	/**
	 * We expect no more than one release here, but will log warn if not
	 * @param deliverableUuid
	 * @param orgUuid - organization UUID - will only search for this org + external org
	 * @return
	 */
	public Optional<ReleaseData> getReleaseByOutboundDeliverable (UUID deliverableUuid, UUID orgUuid) {
		Optional<Release> or = Optional.empty();
		List<Release> releases = repository.findReleasesByDeliverable(deliverableUuid.toString(), orgUuid.toString());
		if (null != releases && !releases.isEmpty()) {
			or = Optional.of(releases.get(0));
			if (releases.size() > 1) {
				log.warn("More than one release returned per deliverable uuid = " + deliverableUuid);
			}
		}
		Optional<ReleaseData> ord = Optional.empty();
		if (or.isPresent()) ord = Optional.of(ReleaseData.dataFromRecord(or.get()));
		return ord;
	}
	
	public List<Release> findReleasesBySce(UUID sce, UUID org) {
		return repository.findReleaseBySce(sce.toString(), org.toString());
	}
	
	public List<ReleaseData> findReleaseDatasBySce(UUID sce, UUID org) {
		return findReleasesBySce(sce,org).stream().map(ReleaseData::dataFromRecord).toList();
	}
	
	/**
	 * Group a precomputed set of release UUIDs into {@code ComponentWithBranches}.
	 * Mirrors the tail of {@link #findReleaseDatasByDtrackProjects} so callers
	 * that already know which releases to surface (e.g. the SBOM-component
	 * search path that walks {@code release_sbom_components}) can reuse the
	 * same component/branch grouping without going through dtrack.
	 */
	public List<ComponentWithBranches> findReleaseDatasByReleaseIds(Collection<UUID> releaseIds, final UUID org) {
		if (releaseIds == null || releaseIds.isEmpty()) return List.of();
		Set<UUID> ids = (releaseIds instanceof Set<?>) ? (Set<UUID>) releaseIds : new HashSet<>(releaseIds);
		var releaseDatas = getReleaseDataListLight(ids, org);
		return convertReleasesToComponentWithBranches(releaseDatas, org, null);
	}

	public List<ComponentWithBranches> findReleaseDatasByDtrackProjects(Collection<UUID> dtrackProjects, final UUID org) {
		log.debug("dtrack project size = {}", dtrackProjects.size());
		long startTime = System.currentTimeMillis();
		Set<UUID> arts = new HashSet<>(artifactService.listArtifactUuidsByDtrackProjects(dtrackProjects));
		log.debug("artifacts size = {}", arts.size());
		long afterArtifacts = System.currentTimeMillis();
		log.debug("findReleaseDatasByDtrackProjects - listArtifactsByDtrackProjects took {} ms, found {} artifacts", afterArtifacts - startTime, arts.size());
		Set<UUID> releaseIds = gatherReleaseIdsForArtifacts(arts, org);
		long afterGatherReleases = System.currentTimeMillis();
		log.debug("findReleaseDatasByDtrackProjects - gatherReleaseIdsForArtifacts took {} ms, found {} releases", afterGatherReleases - afterArtifacts, releaseIds.size());
		
		var releaseDatas = getReleaseDataListLight(releaseIds, org);
		log.debug("releaseDatas size = {}", releaseDatas.size());
		long afterGetReleaseData = System.currentTimeMillis();
		log.debug("findReleaseDatasByDtrackProjects - getReleaseDataList took {} ms", afterGetReleaseData - afterGatherReleases);
		
		var result = convertReleasesToComponentWithBranches(releaseDatas, org, null);
		long endTime = System.currentTimeMillis();
		log.debug("findReleaseDatasByDtrackProjects - convertReleasesToComponentWithBranches took {} ms, total {} ms", endTime - afterGetReleaseData, endTime - startTime);
		return result;
	}
	
	public List<ReleaseData> findReleasesByOrgAndIdentifier(UUID org, RearmIdentifierType idType, String idValue) {
		List<ReleaseData> rds = new LinkedList<>();
		List<Release> releases = repository.findReleasesByOrgAndIdentifier(org.toString(), idType.name(), idValue);
		if (!releases.isEmpty()) rds = releases.stream().map(ReleaseData::dataFromRecord).toList();
		return rds;
	}
	
	// UUID-only finders for VulnAnalysisUpdateService -- caller feeds each
	// UUID straight to computeReleaseMetrics(uuid, false), so materializing
	// the full Release (with five JSONB columns' worth of snapshot
	// deep-copies per row) would be pure waste and a heap-pressure
	// liability when a popular CVE / new violation rule affects many
	// releases org-wide.
	public List<UUID> findReleaseUuidsWithVulnerability(UUID org, String location, String findingId) {
		return repository.findReleasesWithVulnerability(org.toString(), location, findingId);
	}

	/**
	 * UUIDs of releases in {@code org} whose metrics carry {@code findingId}
	 * (a CVE id) in ANY package location. Drives the KEV live re-gate
	 * fan-out, where the triggering CVE has no single location and every
	 * release that ships it must be recomputed.
	 */
	public List<UUID> findReleaseUuidsWithVulnerabilityAnyLocation(UUID org, String findingId) {
		return repository.findReleasesWithVulnerabilityAnyLocation(org.toString(), findingId);
	}

	public List<UUID> findReleaseUuidsWithViolation(UUID org, String location, String findingId) {
		return repository.findReleasesWithViolation(org.toString(), location, findingId);
	}

	public List<UUID> findReleaseUuidsWithWeakness(UUID org, String location, String findingId) {
		return repository.findReleasesWithWeakness(org.toString(), location, findingId);
	}

	public List<UUID> findReleaseUuidsWithVulnerabilityInBranch(UUID org, UUID branch, String location, String findingId) {
		return repository.findReleasesWithVulnerabilityInBranch(org.toString(), branch.toString(), location, findingId);
	}

	public List<UUID> findReleaseUuidsWithViolationInBranch(UUID org, UUID branch, String location, String findingId) {
		return repository.findReleasesWithViolationInBranch(org.toString(), branch.toString(), location, findingId);
	}

	public List<UUID> findReleaseUuidsWithWeaknessInBranch(UUID org, UUID branch, String location, String findingId) {
		return repository.findReleasesWithWeaknessInBranch(org.toString(), branch.toString(), location, findingId);
	}

	public List<UUID> findReleaseUuidsWithVulnerabilityInComponent(UUID org, UUID component, String location, String findingId) {
		return repository.findReleasesWithVulnerabilityInComponent(org.toString(), component.toString(), location, findingId);
	}

	public List<UUID> findReleaseUuidsWithViolationInComponent(UUID org, UUID component, String location, String findingId) {
		return repository.findReleasesWithViolationInComponent(org.toString(), component.toString(), location, findingId);
	}

	public List<UUID> findReleaseUuidsWithWeaknessInComponent(UUID org, UUID component, String location, String findingId) {
		return repository.findReleasesWithWeaknessInComponent(org.toString(), component.toString(), location, findingId);
	}

	/**
	 * Find all intermediate failed releases (PENDING, REJECTED, CANCELLED) between the current release
	 * and the previous successful release (DRAFT or higher).
	 * 
	 * @param currentRelease The current release to find intermediate failed releases for
	 * @return List of failed releases between current and last successful release, ordered by date descending
	 */
	public List<ReleaseData> findIntermediateFailedReleases(ReleaseData currentRelease) {
		// Only compute for successful releases (DRAFT or higher, ordinal >= 3)
		if (currentRelease.getLifecycle().ordinal() < ReleaseLifecycle.DRAFT.ordinal()) {
			return new LinkedList<>();
		}

		UUID branchUuid = currentRelease.getBranch();
		ZonedDateTime currentDate = currentRelease.getCreatedDate();

		// Find the previous successful (DRAFT+) release created strictly before this one.
		// Uses created_date < currentDate so the current release itself is never returned.
		Optional<ReleaseData> prevSuccessful = getReleaseDataOfBranch(
			currentRelease.getOrg(), branchUuid, ReleaseLifecycle.DRAFT, currentDate
		);

		// Lower bound: previous successful release date (exclusive), or epoch if this is the first
		ZonedDateTime fromDate = prevSuccessful
			.map(ReleaseData::getCreatedDate)
			.orElse(ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC));

		// Fetch all releases in (fromDate, currentDate] and keep only failed ones.
		// The current DRAFT release itself falls at currentDate but is excluded by the lifecycle filter.
		return listReleasesOfBranchBetweenDates(branchUuid, fromDate, currentDate)
			.stream()
			.map(ReleaseData::dataFromRecord)
			.filter(r -> r.getLifecycle() == ReleaseLifecycle.PENDING
					  || r.getLifecycle() == ReleaseLifecycle.REJECTED
					  || r.getLifecycle() == ReleaseLifecycle.CANCELLED)
			.collect(Collectors.toList());
	}
	
	/**
	 * Convert a list of releases to hierarchical ComponentWithBranches structure
	 * @param releaseDataList List of release data to organize
	 * @param orgUuid Organization UUID for fetching latest DRAFT releases
	 * @param perspectiveUuid Optional perspective UUID to filter components by perspective
	 * @return List of ComponentWithBranches containing hierarchical release data
	 */
	private List<ComponentWithBranches> convertReleasesToComponentWithBranches(
			List<ReleaseData> releaseDataList, UUID orgUuid, UUID perspectiveUuid) {
		
		// Get perspective components if perspectiveUuid is provided
		Set<UUID> perspectiveComponentUuids = null;
		if (perspectiveUuid != null) {
			List<ComponentData> perspectiveComponents = getComponentService.listComponentsByPerspective(perspectiveUuid);
			perspectiveComponentUuids = perspectiveComponents.stream()
					.map(ComponentData::getUuid)
					.collect(Collectors.toSet());
		}
		
		// Group releases by component
		Map<UUID, List<ReleaseData>> releasesByComponent = releaseDataList.stream()
				.collect(Collectors.groupingBy(ReleaseData::getComponent));
		
		// Build hierarchical structure
		List<ComponentWithBranches> result = new ArrayList<>();
		
		for (Map.Entry<UUID, List<ReleaseData>> componentEntry : releasesByComponent.entrySet()) {
			UUID componentUuid = componentEntry.getKey();
			List<ReleaseData> componentReleases = componentEntry.getValue();
			
			// Filter by perspective if perspectiveUuid is provided
			if (perspectiveComponentUuids != null && !perspectiveComponentUuids.contains(componentUuid)) {
				continue;
			}

			ComponentData componentData = getComponentService.getComponentData(componentUuid).get();
			
			// Group releases by branch
			Map<UUID, List<ReleaseData>> releasesByBranch = componentReleases.stream()
					.collect(Collectors.groupingBy(ReleaseData::getBranch));
			
			// Build branch list
			List<BranchWithReleases> branches = new ArrayList<>();
			
			for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
				UUID branchUuid = branchEntry.getKey();
				List<ReleaseData> branchReleases = branchEntry.getValue();
				
				BranchData branchData = branchService.getBranchData(branchUuid).get();
				
				// Sort releases by version schema
				branchReleases.sort(new ReleaseVersionComparator(componentData.getVersionSchema(), branchData.getVersionSchema()));
				
				// Get latest DRAFT release version
				String latestReleaseVersion = null;
				Optional<ReleaseData> latestDraftRelease = getReleaseDataOfBranch(orgUuid, branchUuid, ReleaseLifecycle.DRAFT);
				if (latestDraftRelease.isPresent()) {
					latestReleaseVersion = latestDraftRelease.get().getVersion();
				}
				
				branches.add(new BranchWithReleases(
						branchUuid,
						branchData.getName(),
						branchData.getStatus(),
						branchData.getVersionSchema(),
						latestReleaseVersion,
						branchReleases
				));
			}
			
			// Sort branches by name
			branches.sort((b1, b2) -> b1.name().compareTo(b2.name()));
			
			result.add(new ComponentWithBranches(
					componentUuid,
					componentData.getName(),
					componentData.getType(),
					componentData.getVersionSchema(),
					branches
			));
		}
		
		// Sort components by name
		result.sort((c1, c2) -> c1.name().compareTo(c2.name()));
		
		return result;
	}
	
	/**
	 * Search for releases by CVE ID in their metrics, organized by Component -> Branch -> Releases
	 * @param orgUuid Organization UUID
	 * @param cveId CVE ID to search for (searches both vulnId and alias aliasId)
	 * @param perspectiveUuid Optional perspective UUID to filter components by perspective
	 * @return List of ComponentWithBranches containing hierarchical release data
	 */
	/**
	 * Cap on releases hydrated and returned by the CVE search. The uuid page is
	 * newest-first, so the cap keeps the most relevant matches; the window total
	 * still reports the full match count for the UI's truncation note.
	 */
	public static final int CVE_SEARCH_RELEASE_LIMIT = 500;

	public CveSearchResultDto.CveSearchResult findReleasesByCveId(UUID orgUuid, String cveId, UUID perspectiveUuid) {
		List<Object[]> rows = repository.findReleasesByCveId(orgUuid.toString(), cveId, CVE_SEARCH_RELEASE_LIMIT);
		int totalMatches = rows.isEmpty() ? 0 : ((Number) rows.get(0)[1]).intValue();
		List<UUID> uuids = rows.stream()
				.map(row -> Utils.parseUuidFromObject(row[0]))
				.collect(Collectors.toList());
		// Lite hydration: totals-only metrics, no detail arrays or events — the
		// result tree needs component/branch/version/lifecycle and the summary
		// counts, never the megabyte-scale finding details.
		List<ReleaseData> releaseDataList = getReleaseDataListLight(uuids, orgUuid);
		releaseDataList.sort(new ReleaseData.ReleaseDateComparator());
		List<ComponentWithBranches> components =
				convertReleasesToComponentWithBranches(releaseDataList, orgUuid, perspectiveUuid);
		return new CveSearchResultDto.CveSearchResult(
				components, totalMatches, releaseDataList.size(), totalMatches > releaseDataList.size());
	}
	
	/**
	 * Finds all releases within a time frame for an organization.
	 * @param orgUuid Organization UUID
	 * @param startDate Start date (inclusive)
	 * @param endDate End date (inclusive)
	 * @param perspectiveUuid Optional perspective UUID to filter components by perspective
	 * @return List of ComponentWithBranches containing hierarchical release data
	 */
	public List<ComponentWithBranches> findReleasesByTimeFrame(UUID orgUuid, ZonedDateTime startDate, ZonedDateTime endDate, UUID perspectiveUuid) {
		List<Release> releases = repository.findReleasesOfOrgBetweenDates(orgUuid.toString(), startDate, endDate);
		List<ReleaseData> releaseDataList = releases.stream()
				.map(ReleaseData::dataFromRecord)
				.collect(Collectors.toList());
		
		return convertReleasesToComponentWithBranches(releaseDataList, orgUuid, perspectiveUuid);
	}
	
	/**
	 * Finds all releases within a time frame for a component.
	 * @param componentUuid Component UUID
	 * @param startDate Start date (inclusive)
	 * @param endDate End date (inclusive)
	 * @return List of ComponentWithBranches containing hierarchical release data
	 */
	public List<ComponentWithBranches> findReleasesByTimeFrameAndComponent(UUID componentUuid, ZonedDateTime startDate, ZonedDateTime endDate) {
		List<Release> releases = repository.findReleasesOfComponentBetweenDates(componentUuid.toString(), startDate, endDate);
		List<ReleaseData> releaseDataList = releases.stream()
				.map(ReleaseData::dataFromRecord)
				.collect(Collectors.toList());
		if (releaseDataList.isEmpty()) {
			return Collections.emptyList();
		}
		UUID orgUuid = releaseDataList.get(0).getOrg();
		return convertReleasesToComponentWithBranches(releaseDataList, orgUuid, null);
	}
	
	/**
	 * Finds all releases within a time frame for a branch.
	 * @param branchUuid Branch UUID
	 * @param startDate Start date (inclusive)
	 * @param endDate End date (inclusive)
	 * @return List of ComponentWithBranches containing hierarchical release data
	 */
	public List<ComponentWithBranches> findReleasesByTimeFrameAndBranch(UUID branchUuid, ZonedDateTime startDate, ZonedDateTime endDate) {
		List<Release> releases = repository.findReleasesOfBranchBetweenDates(branchUuid.toString(), startDate, endDate);
		List<ReleaseData> releaseDataList = releases.stream()
				.map(ReleaseData::dataFromRecord)
				.collect(Collectors.toList());
		if (releaseDataList.isEmpty()) {
			return Collections.emptyList();
		}
		UUID orgUuid = releaseDataList.get(0).getOrg();
		return convertReleasesToComponentWithBranches(releaseDataList, orgUuid, null);
	}

	public List<ReleaseData> listReleaseDataOfOrgBetweenDates(UUID orgUuid, ZonedDateTime startDate, ZonedDateTime endDate, Integer limit) {
		return listReleaseDataOfOrgBetweenDates(orgUuid, startDate, endDate, limit, null);
	}

	/**
	 * Same as the 4-arg variant but with an optional {@code componentType}
	 * filter applied before the limit cap. Components are looked up
	 * in-memory per release -- fine for the typical UI limit (≤100) and
	 * avoids a SQL join through release.record_data → component table
	 * that would defeat the existing JSONB index on org.
	 *
	 * <p>Passing null for {@code componentType} matches every type
	 * (back-compat with the 4-arg path). Filter sits before
	 * {@code stream.limit} so we don't undercount when the first N
	 * releases happen to be of the unwanted type.
	 */
	public List<ReleaseData> listReleaseDataOfOrgBetweenDates(UUID orgUuid, ZonedDateTime startDate, ZonedDateTime endDate, Integer limit,
			io.reliza.model.ComponentData.ComponentType componentType) {
		var stream = repository.findReleasesOfOrgBetweenDates(orgUuid.toString(), startDate, endDate)
				.stream()
				.map(ReleaseData::dataFromRecord)
				.sorted(new ReleaseData.ReleaseDateComparator());
		if (componentType != null) {
			stream = stream.filter(rd -> matchesComponentType(rd, componentType));
		}
		if (limit != null) stream = stream.limit(limit);
		return stream.collect(Collectors.toList());
	}

	private boolean matchesComponentType(ReleaseData rd, io.reliza.model.ComponentData.ComponentType componentType) {
		if (rd == null || rd.getComponent() == null) return false;
		var ocd = getComponentService.getComponentData(rd.getComponent());
		return ocd.isPresent() && componentType.equals(ocd.get().getType());
	}

	public List<ReleaseData> listReleaseDataOfComponentBetweenDates(UUID componentUuid, ZonedDateTime startDate, ZonedDateTime endDate, Integer limit) {
		var stream = repository.findReleasesOfComponentBetweenDates(componentUuid.toString(), startDate, endDate)
				.stream()
				.map(ReleaseData::dataFromRecord)
				.sorted(new ReleaseData.ReleaseDateComparator());
		if (limit != null) stream = stream.limit(limit);
		return stream.collect(Collectors.toList());
	}

	public List<ReleaseData> listReleaseDataOfBranchBetweenDates(UUID branchUuid, ZonedDateTime startDate, ZonedDateTime endDate, Integer limit) {
		var stream = repository.findReleasesOfBranchBetweenDates(branchUuid.toString(), startDate, endDate)
				.stream()
				.map(ReleaseData::dataFromRecord)
				.sorted(new ReleaseData.ReleaseDateComparator());
		if (limit != null) stream = stream.limit(limit);
		return stream.collect(Collectors.toList());
	}
}
