/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static io.reliza.common.Utils.drop;
import static io.reliza.common.Utils.first;
import static io.reliza.common.Utils.last;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.reliza.common.CommitMatcherUtil;
import io.reliza.service.FindingComparisonService.FindingChangeTimelinePage;
import io.reliza.service.FindingComparisonService.OverTimeFindingChangesResult;
import io.reliza.dto.ChangelogRecords;
import io.reliza.dto.ChangelogRecords.AggregatedBranchChanges;
import io.reliza.dto.ChangelogRecords.AggregatedChangelog;
import io.reliza.dto.ChangelogRecords.CodeCommit;
import io.reliza.dto.ChangelogRecords.CommitRecord;
import io.reliza.dto.ChangelogRecords.ChangeType;
import io.reliza.dto.ChangelogRecords.CommitsByType;
import io.reliza.dto.ChangelogRecords.ComponentChangelog;
import io.reliza.dto.ChangelogRecords.NoneBranchChanges;
import io.reliza.dto.ChangelogRecords.NoneChangelog;
import io.reliza.dto.ChangelogRecords.NoneOrganizationChangelog;
import io.reliza.dto.ChangelogRecords.NoneProductChangelog;
import io.reliza.dto.ChangelogRecords.NoneReleaseChanges;
import io.reliza.dto.ChangelogRecords.ProductReleaseChanges;
import io.reliza.dto.ChangelogRecords.OrganizationChangelog;
import io.reliza.dto.ChangelogRecords.AggregatedOrganizationChangelog;
import io.reliza.dto.ChangelogRecords.ReleaseInfo;
import io.reliza.dto.ChangelogRecords.ReleaseFindingChanges;
import io.reliza.dto.ChangelogRecords.MetricsRevisionFindingChange;
import io.reliza.dto.ChangelogRecords.ReleaseSbomArtifact;
import io.reliza.dto.ChangelogRecords.ReleaseSbomChanges;
import io.reliza.dto.ChangelogRecords.ReleaseViolationInfo;
import io.reliza.dto.ChangelogRecords.ReleaseVulnerabilityInfo;
import io.reliza.dto.ChangelogRecords.ReleaseWeaknessInfo;
import io.reliza.dto.ChangelogFindingKind;
import io.reliza.dto.ComponentAttribution;
import io.reliza.dto.ComponentAttributionPage;
import io.reliza.dto.FindingAttributionBucket;
import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.SbomChangesWithAttribution;
import io.reliza.dto.ViolationWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.dto.WeaknessWithAttribution;
import io.reliza.model.changelog.CommitType;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.AcollectionData;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.changelog.CommitBody;
import io.reliza.model.changelog.CommitFooter;
import io.reliza.model.changelog.CommitMessage;
import io.reliza.model.changelog.ConventionalCommit;
import io.reliza.versioning.VersionApi;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.changelog.entry.FindingChangeScope;
import io.reliza.dto.FindingChangesRecord;
import io.reliza.model.dto.ReleaseMetricsDto;
import lombok.extern.slf4j.Slf4j;

/**
 * Facade service for changelog operations.
 * Delegates specialized operations to focused services while maintaining a stable public API.
 * 
 * This service orchestrates:
 * - Commit parsing (local)
 * - Finding comparisons (delegates to FindingComparisonService)
 * - SBOM comparisons (delegates to SbomComparisonService)
 * - Changelog JSON building (future: ChangelogJsonBuilderService)
 */
@Slf4j
@Service
public class ChangeLogService {
	
	private static final Comparator<ReleaseData> NEWEST_FIRST = 
		Comparator.comparing(ReleaseData::getCreatedDate).reversed();
	
	/**
	 * Bundles the shared parameters passed through the changelog computation pipeline.
	 * Reduces method parameter counts from 8-11 to 2-4.
	 */
	private record ChangelogContext(
		LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
		ComponentData component,
		UUID org,
		ReleaseData globalFirst,
		ReleaseData globalLast,
		Map<UUID, String> branchNameMap,
		String userTimeZone,
		List<VcsRepositoryData> vcsRepoDataList
	) {}
	
	/**
	 * KILL-SWITCH, default ON (board task #38): route the AGGREGATED changelog "Finding Changes" views
	 * through the posture-endpoint diff ({@link FindingComparisonService#computeOrgPostureDiff} /
	 * {@link FindingComparisonService#computePostureDiff} / the product variants) instead of the legacy
	 * pairwise release diff ({@link FindingComparisonService#compareMetricsAcrossComponents}). Covers the
	 * org rollup AND the AGGREGATED component / branch / product / feature-set changelogs (incl. their
	 * empty-window bypasses). This global flag is necessary but not sufficient: {@link #posturePathEnabled}
	 * additionally requires the org watermark at the current event vocabulary version (certified
	 * automatically by the boot backfill, which reseeds any org not yet current). Env
	 * {@code CHANGELOG_POSTURE_DIFF_ENABLED=false} forces the legacy path instance-wide (rollback lever).
	 */
	@Value("${relizaprops.changelogPostureDiffEnabled:true}")
	private boolean changelogPostureDiffEnabled;

	/**
	 * Rescan-inclusive org changelog (board task #42). When ON, the org changelog does NOT bail with "No
	 * changelog data found" on a window with no in-window release but with re-scan-driven finding changes --
	 * it computes the posture rollup + over-time from {@code finding_change_events} instead -- and the org
	 * over-time list reads ALL_POSTURE (events by change_date) rather than only in-window-produced releases.
	 * Gated on top of {@link #posturePathEnabled} (per-org, v3): a global kill-switch to restore the prior
	 * release-anchored behavior. Default ON (validated on demo); flip to false to A/B or roll back.
	 */
	@Value("${relizaprops.changelogRescanInclusiveEnabled:true}")
	private boolean changelogRescanInclusiveEnabled;

	@Autowired
	private FindingComparisonService findingComparisonService;
	
	@Autowired
	private SbomComparisonService sbomComparisonService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private ComponentService componentService;
	
	@Autowired
	private VcsRepositoryService vcsRepositoryService;
	
	@Autowired
	private AcollectionService acollectionService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	/**
	 * Whether the posture-diff READ path may run for {@code orgUuid}: the global flag must be on AND the
	 * org's {@code finding_change_events} backfill must be complete at the CURRENT event vocabulary version
	 * (board task #38). The reverse-replay reconstruction trusts the event log to be complete back to the
	 * retention horizon; an un-seeded org (or one seeded before a vocabulary widening and not yet reseeded)
	 * has an incomplete log, so it transparently falls back to the legacy pairwise diff until a full
	 * backfill/reseed runs. Once seeded, the always-on live emit keeps it seeded. A missing org / missing
	 * settings -- or ANY exception in the lookup -- reads as NOT seeded (fail-safe to legacy, never a 500).
	 */
	private boolean posturePathEnabled(UUID orgUuid) {
		if (!changelogPostureDiffEnabled) {
			return false;
		}
		try {
			return getOrganizationService.getOrganizationData(orgUuid)
					.map(OrganizationData::getSettings)
					.map(s -> s.isFindingChangeBackfillComplete()
							&& s.getFindingChangeBackfillVocabVersionOrDefault()
								>= ChangelogRecords.FINDING_CHANGE_EVENT_VOCAB_VERSION)
					.orElse(false);
		} catch (Exception e) {
			log.error("posture-diff gate: org {} settings lookup failed; falling back to the legacy "
					+ "changelog path for this request", orgUuid, e);
			return false;
		}
	}

	// ========== HELPER METHODS ==========
	
	/**
	 * Pre-fetches the latest acollection for each release, keyed by release UUID.
	 * Only the latest version is kept per release to avoid double-counting
	 * artifact diffs when multiple acollection versions exist.
	 */
	private Map<UUID, List<AcollectionData>> prefetchAcollections(List<ReleaseData> releases) {
		Map<UUID, List<AcollectionData>> map = new HashMap<>();
		for (ReleaseData rd : releases) {
			List<AcollectionData> acs = acollectionService.getAcollectionDatasOfRelease(rd.getUuid());
			if (acs != null && !acs.isEmpty()) {
				AcollectionData latest = acs.stream()
					.max(Comparator.comparingLong(AcollectionData::getVersion))
					.orElse(null);
				if (latest != null) {
					map.put(rd.getUuid(), List.of(latest));
				}
			}
		}
		return map;
	}
	
	/**
	 * From a pre-fetched acollections map (already filtered to latest per release),
	 * returns a flat list of acollections across all provided releases.
	 */
	private List<AcollectionData> pickLatestAcollections(
			Map<UUID, List<AcollectionData>> releaseAcollectionsMap, List<ReleaseData> releases) {
		List<AcollectionData> result = new ArrayList<>();
		for (ReleaseData rd : releases) {
			List<AcollectionData> acs = releaseAcollectionsMap.get(rd.getUuid());
			if (acs != null) {
				result.addAll(acs);
			}
		}
		return result;
	}
	
	/**
	 * Fetches all releases for a component across all its branches within a date range.
	 * Uses a single DB query by component+dates instead of enumerating branches first.
	 * Returns releases sorted newest first.
	 */
	private List<ReleaseData> fetchReleasesForComponentBetweenDates(
			UUID componentUuid, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		List<ReleaseData> releases = sharedReleaseService.listReleaseDataOfComponentBetweenDates(
			componentUuid, dateFrom, dateTo, ReleaseLifecycle.DRAFT);
		releases.sort(NEWEST_FIRST);
		return releases;
	}

	/**
	 * Posture-diff variant (board task #38, phase 3) of {@link #fetchReleasesForComponentBetweenDates}:
	 * in addition to the in-window releases, pulls each ACTIVE branch's latest release created
	 * {@code <= dateFrom} (the from-baseline / snapshot in effect AT the window start) so the endpoint
	 * reconstruction has a from-anchor without a full-history scan. Enumerating ALL active branches
	 * (not just those with an in-window release) is required for correctness: a branch carrying a
	 * finding via a release created before {@code dateFrom}, with no in-window release, must still be
	 * anchored at the from-endpoint -- otherwise a finding present at {@code from} via such a branch is
	 * invisible and gets mislabeled as new-to-organization when introduced mid-window elsewhere. For
	 * such a branch the from-anchor also serves as the to-endpoint (nothing newer exists in-window),
	 * so adding it covers both endpoints. Bounded: one LIMIT-1 lookup per active branch. De-dupes by
	 * release UUID. Only used when the posture-diff flag is on; the legacy path keeps the
	 * in-window-only fetch unchanged.
	 */
	List<ReleaseData> fetchReleasesForComponentWithFromBaseline(
			UUID componentUuid, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		// EXCLUDED-participation branches (TAG branches, or branches an operator opted out of finding
		// analytics) are dropped -- from BOTH the in-window releases and the from-baseline anchors -- so the
		// posture-diff counts the SAME branch set as the analytics chart / over-time paths (board task F2;
		// mirrors the over-time excludedBranchUuids filter in getOrganizationChangelogByDate /
		// getFindingChangeTimelinePage, which filters regardless of branch status). Full-status list so an
		// in-window release on an EXCLUDED archived branch is dropped too.
		List<BranchData> allBranches = branchService.listBranchDataOfComponent(componentUuid, null);
		Set<UUID> excludedBranchUuids = allBranches.stream()
			.filter(b -> b.getFindingAnalyticsParticipation() == BranchData.FindingAnalyticsParticipation.EXCLUDED)
			.map(BranchData::getUuid).collect(Collectors.toSet());

		List<ReleaseData> inWindow = fetchReleasesForComponentBetweenDates(componentUuid, dateFrom, dateTo);

		Map<UUID, ReleaseData> byUuid = new LinkedHashMap<>();
		inWindow.forEach(r -> {
			if (!excludedBranchUuids.contains(r.getBranch())) {
				byUuid.put(r.getUuid(), r);
			}
		});

		// Anchor only ACTIVE, non-EXCLUDED branches (matches the prior ACTIVE-only enumeration).
		for (BranchData branch : allBranches) {
			if (branch.getStatus() != StatusEnum.ACTIVE
					|| excludedBranchUuids.contains(branch.getUuid())) {
				continue;
			}
			sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(branch.getUuid(), dateFrom)
				.ifPresent(baseline -> byUuid.putIfAbsent(baseline.getUuid(), baseline));
		}
		List<ReleaseData> merged = new ArrayList<>(byUuid.values());
		merged.sort(NEWEST_FIRST);
		return merged;
	}

	/**
	 * BATCHED org-rollup analogue of {@link #fetchReleasesForComponentWithFromBaseline} (org
	 * posture-diff N+1 elimination). Produces the SAME {@code postureReleasesMap} the old per-component
	 * loop built -- each component's in-window releases widened with each active branch's from-anchor
	 * (branch-latest created {@code <= dateFrom}) -- but eliminates ALL THREE per-component round-trips,
	 * making the branch/release enumeration O(1) queries in the component count: (1) the in-window release
	 * fetch is ONE {@link SharedReleaseService#listReleaseDataOfOrgBetweenDatesByComponent} org-wide query
	 * grouped in memory instead of a per-component {@code fetchReleasesForComponentBetweenDates}; (2) the
	 * active-branch enumeration is ONE {@link BranchService#listBranchDataOfOrg} query (filtered to ACTIVE +
	 * the components in scope, grouped in memory) instead of a per-component {@code listBranchesOfComponent};
	 * and (3) ALL from-anchors are resolved in ONE chunked
	 * {@link SharedReleaseService#getBranchLatestReleasesAtOrBeforeDate} call instead of a per-branch
	 * LIMIT-1 lookup. De-dup (putIfAbsent -- in-window instance wins over the from-anchor for the same
	 * release UUID) and NEWEST_FIRST sort mirror the single-component method exactly, so the map is
	 * byte-identical. Only components with a non-empty widened set are added, and their names are
	 * registered -- identical to the prior loop's filter.
	 */
	Map<UUID, List<ReleaseData>> buildOrgPostureReleasesMap(
			UUID orgUuid,
			List<ComponentData> components,
			Map<UUID, String> componentNamesMap,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo) {

		Set<UUID> componentUuidsInScope = components.stream()
			.map(ComponentData::getUuid).collect(Collectors.toSet());

		// ONE query for every ACTIVE branch of the org, grouped by component in memory and restricted to
		// the components in scope (an org changelog may be perspective-scoped to a subset). Replaces the
		// former per-component listBranchesOfComponent N+1. EXCLUDED-participation branches (e.g. TAG
		// branches, or branches an operator opted out of analytics) are dropped so the Summary posture-diff
		// counts the SAME branch set as the analytics chart / over-time paths (board task F2 -- see the
		// != EXCLUDED filter in AnalyticsMetricsService and the over-time excludedBranchUuids filter in
		// getOrganizationChangelogByDate / getFindingChangeTimelinePage).
		Map<UUID, List<UUID>> activeBranchesByComponent = new LinkedHashMap<>();
		Set<UUID> allActiveBranches = new LinkedHashSet<>();
		// EXCLUDED-participation branches (any status) -- used to drop their in-window releases below, so an
		// EXCLUDED branch's release cannot slip in via the org-wide in-window query even though it is skipped
		// as a from-anchor. Mirrors the over-time excludedBranchUuids filter (getOrganizationChangelogByDate).
		Set<UUID> excludedBranchUuids = new HashSet<>();
		for (BranchData branch : branchService.listBranchDataOfOrg(orgUuid)) {
			if (branch.getFindingAnalyticsParticipation() == BranchData.FindingAnalyticsParticipation.EXCLUDED) {
				excludedBranchUuids.add(branch.getUuid());
			}
			if (branch.getStatus() != StatusEnum.ACTIVE
					|| branch.getFindingAnalyticsParticipation() == BranchData.FindingAnalyticsParticipation.EXCLUDED
					|| !componentUuidsInScope.contains(branch.getComponent())) {
				continue;
			}
			activeBranchesByComponent
				.computeIfAbsent(branch.getComponent(), k -> new ArrayList<>())
				.add(branch.getUuid());
			allActiveBranches.add(branch.getUuid());
		}

		// ONE org-wide in-window query, grouped by component in memory (replaces the former per-component
		// fetchReleasesForComponentBetweenDates N+1). Same DRAFT-minLifecycle filter; caller re-sorts.
		Map<UUID, List<ReleaseData>> inWindowByComponent =
			sharedReleaseService.listReleaseDataOfOrgBetweenDatesByComponent(
				orgUuid, dateFrom, dateTo, ReleaseLifecycle.DRAFT);

		// ONE batched (chunked) branch-latest-<=-dateFrom lookup for every active branch of the org.
		Map<UUID, ReleaseData> fromAnchorByBranch =
			sharedReleaseService.getBranchLatestReleasesAtOrBeforeDate(allActiveBranches, dateFrom);

		Map<UUID, List<ReleaseData>> postureReleasesMap = new LinkedHashMap<>();
		for (ComponentData component : components) {
			UUID componentUuid = component.getUuid();
			Map<UUID, ReleaseData> byUuid = new LinkedHashMap<>();
			inWindowByComponent.getOrDefault(componentUuid, List.of())
				.forEach(r -> {
					if (!excludedBranchUuids.contains(r.getBranch())) {
						byUuid.put(r.getUuid(), r);
					}
				});
			for (UUID branchUuid : activeBranchesByComponent.getOrDefault(componentUuid, List.of())) {
				ReleaseData baseline = fromAnchorByBranch.get(branchUuid);
				if (baseline != null) {
					byUuid.putIfAbsent(baseline.getUuid(), baseline);
				}
			}
			if (byUuid.isEmpty()) {
				continue;
			}
			List<ReleaseData> widened = new ArrayList<>(byUuid.values());
			widened.sort(NEWEST_FIRST);
			// Register the component name for components pulled in by the all-branch enumeration that
			// have no in-window release, otherwise their attribution renders as "Unknown".
			componentNamesMap.putIfAbsent(componentUuid, component.getName());
			postureReleasesMap.put(componentUuid, widened);
		}
		return postureReleasesMap;
	}

	/**
	 * Groups a flat list of releases by branch, maintaining insertion order.
	 * Sorts releases within each branch by creation date (newest first).
	 */
	private LinkedHashMap<UUID, List<ReleaseData>> groupReleasesByBranch(List<ReleaseData> releases) {
		LinkedHashMap<UUID, List<ReleaseData>> grouped = new LinkedHashMap<>();
		
		for (ReleaseData release : releases) {
			grouped.computeIfAbsent(release.getBranch(), k -> new ArrayList<>()).add(release);
		}
		
		// Sort each branch's releases by creation date (newest first)
		grouped.values().forEach(branchReleases -> branchReleases.sort(NEWEST_FIRST));
		
		return grouped;
	}
	
	/**
	 * Converts a ReleaseData to a ReleaseInfo record.
	 */
	private ReleaseInfo toReleaseInfo(ReleaseData release) {
		return new ReleaseInfo(release.getUuid(), release.getVersion(), release.getLifecycle());
	}
	
	/**
	 * Converts a CommitRecord to a CodeCommit, resolving conventional commit format.
	 */
	private CodeCommit toCodeCommit(CommitRecord commitRecord) {
		ConventionalCommit cc = resolveConventionalCommit(commitRecord.commitMessage());
		return new CodeCommit(
			commitRecord.commitId(),
			commitRecord.commitUri(),
			cc != null ? cc.getMessage() : commitRecord.commitMessage(),
			commitRecord.commitAuthor(),
			commitRecord.commitEmail(),
			cc != null ? cc.getType().getPrefix() : "other"
		);
	}
	
	/**
	 * Safely get CommitType for sorting purposes, defaulting to OTHERS for unknown types.
	 */
	private CommitType getCommitTypeForSorting(String changeType) {
		try {
			return CommitType.of(changeType);
		} catch (IllegalStateException e) {
			return CommitType.OTHERS;
		}
	}
	
	private ConventionalCommit resolveConventionalCommit(String commit) {
		if (StringUtils.isEmpty(commit)) {
			return null;
		}
		if (!VersionApi.isConventionalCommit(commit)) {
			return null;
		}

		String[] commitMessageArray = commit.split(CommitMatcherUtil.LINE_SEPARATOR);
		
		if (commitMessageArray.length == 1) {
			return new ConventionalCommit(new CommitMessage(first(commitMessageArray)));
		} else if (commitMessageArray.length == 2) {
			return new ConventionalCommit(new CommitMessage(first(commitMessageArray)),
					new CommitFooter(last(commitMessageArray)));
		} else {
			return new ConventionalCommit(new CommitMessage(first(commitMessageArray)),
					new CommitBody(drop(1, 1, commitMessageArray)),
					new CommitFooter(last(commitMessageArray)));
		}
	}

	
	// ========== Public API using Sealed Interface ==========
	
	/**
	 * Gets component changelog between two releases using the new sealed interface pattern.
	 * Returns either NoneChangelog (per-release breakdown) or AggregatedChangelog (component-level summary)
	 * based on the aggregationType parameter.
	 * 
	 * BASELINE SEMANTICS:
	 * - The "baseline" is the older release (by creation date), used as the comparison point
	 * - The "target" is the newer release, representing the current state
	 * - listAllReleasesBetweenReleases() may exclude the baseline due to exclusive fromDateTime boundary
	 * - This method ensures BOTH baseline and target are included in the releases list
	 * - Baseline is included for version range display (e.g., "3.1.0 - 3.2.0")
	 * - Baseline is EXCLUDED from changelog output (only changes AFTER baseline are shown)
	 * - Baseline acollection is fetched for SBOM/finding comparison but not displayed
	 * - All release tracking uses UUID-based identification, not list position
	 * 
	 * @param uuid1 First release UUID (can be baseline or target)
	 * @param uuid2 Second release UUID (can be baseline or target)
	 * @param org Organization UUID
	 * @param aggregationType NONE for per-release breakdown, AGGREGATED for component-level summary
	 * @param userTimeZone User's timezone for date formatting
	 * @return ComponentChangelog (sealed interface - either NoneChangelog or AggregatedChangelog)
	 * @throws RelizaException if releases or component not found
	 */
	public ComponentChangelog getComponentChangelog(
			UUID uuid1,
			UUID uuid2,
			UUID org,
			AggregationType aggregationType,
			String userTimeZone) throws RelizaException {
		
		List<ReleaseData> releases = sharedReleaseService.listAllReleasesBetweenReleases(uuid1, uuid2);
		
		// Fetch both releases explicitly to ensure baseline is included
		ReleaseData release1 = sharedReleaseService.getReleaseData(uuid1, org)
			.orElseThrow(() -> new RelizaException("Release not found: " + uuid1));
		ReleaseData release2 = sharedReleaseService.getReleaseData(uuid2, org)
			.orElseThrow(() -> new RelizaException("Release not found: " + uuid2));
		
		// Ensure both releases are in the list (baseline may be excluded by listAllReleasesBetweenReleases)

		Set<UUID> existingUuids = releases.stream()
			.map(ReleaseData::getUuid)
			.collect(Collectors.toSet());
		
		if (!existingUuids.contains(uuid1)) {
			releases.add(release1);
		}
		if (!existingUuids.contains(uuid2)) {
			releases.add(release2);
		}
		
		// Re-sort after adding releases to maintain newest-first order
		releases.sort(NEWEST_FIRST);
		
		if (releases.isEmpty()) {
			throw new RelizaException("No releases found between " + uuid1 + " and " + uuid2);
		}
		
		ComponentData component = getComponentService.getComponentData(releases.get(0).getComponent())
			.orElseThrow(() -> new RelizaException("Component not found: " + releases.get(0).getComponent()));
		
		// For PRODUCT type, delegate to product-specific method that handles child component releases
		// Pass release1 and release2 directly - computeProductChangelogFromReleases will determine baseline/target
		if (component.getType() == ComponentType.PRODUCT) {
			return computeProductChangelogFromReleases(
				releases, component, org, aggregationType, userTimeZone,
				release1, release2);
		}
		
		// Convert flat list to grouped structure for new methods
		LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch = groupReleasesByBranch(releases);

		Map<UUID, String> branchNameMap = branchService.getBranchDataList(releasesByBranch.keySet())
			.stream().collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));

		ReleaseData globalFirst = releases.get(releases.size() - 1);
		ReleaseData globalLast = releases.get(0);
		
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		ChangelogContext ctx = new ChangelogContext(
			releasesByBranch, component, org, globalFirst, globalLast,
			branchNameMap, userTimeZone, vcsRepoDataList);
		
		if (aggregationType == AggregationType.NONE) {
			return computeNoneChangelog(ctx);
		}
		
		Map<UUID, List<AcollectionData>> releaseAcollectionsMap = prefetchAcollections(releases);
		return computeAggregatedChangelog(ctx, releaseAcollectionsMap, new HashMap<>());
	}
	
	/**
	 * Gets component changelog for a date range using the new sealed interface pattern.
	 * Returns either NoneChangelog (per-release breakdown) or AggregatedChangelog (component-level summary)
	 * based on the aggregation type.
	 * 
	 * IMPORTANT: Date-based changelogs have DIFFERENT semantics than UUID-based changelogs:
	 * - UUID-based (getComponentChangelog): Compares two specific releases, excludes baseline from output
	 * - Date-based (this method): Shows ALL releases in date range, NO baseline exclusion
	 * 
	 * RATIONALE: When querying by date range, users expect to see all activity in that period,
	 * not a comparison between two specific points. The oldest release in the range is NOT treated
	 * as a baseline to exclude - it's simply the first release in the time window.
	 * 
	 * @param componentUuid Component UUID
	 * @param branchUuid Optional branch UUID to filter by specific branch
	 * @param org Organization UUID
	 * @param aggregationType NONE (per-release) or AGGREGATED (component-level)
	 * @param userTimeZone User's timezone for date formatting
	 * @param dateFrom Start date
	 * @param dateTo End date
	 * @return ComponentChangelog (sealed interface - either NoneChangelog or AggregatedChangelog)
	 * @throws RelizaException if component not found or no releases in date range
	 */
	public ComponentChangelog getComponentChangelogByDate(
			UUID componentUuid,
			UUID branchUuid,
			UUID org,
			AggregationType aggregationType,
			String userTimeZone,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo) throws RelizaException {
		
		// Get component
		ComponentData component = getComponentService.getComponentData(componentUuid)
			.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
		
		// Get releases grouped by branch (avoid flatten-then-regroup anti-pattern)
		LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch;
		
		if (branchUuid != null) {
			// Get releases for specific branch
			releasesByBranch = new LinkedHashMap<>();
			List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
				branchUuid, dateFrom, dateTo, ReleaseLifecycle.DRAFT);
			if (branchReleases != null && !branchReleases.isEmpty()) {
				// Sort within branch by creation date (newest first)
				branchReleases.sort(NEWEST_FIRST);
				releasesByBranch.put(branchUuid, branchReleases);
			}
		} else {
			// Get releases for all branches of the component
			List<ReleaseData> allReleases = fetchReleasesForComponentBetweenDates(componentUuid, dateFrom, dateTo);
			releasesByBranch = groupReleasesByBranch(allReleases);
		}
		
		// EMPTY-WINDOW CONTROL FLOW (board task #38, phase 3). Legacy behavior returns null when there is
		// no in-window release -> the UI renders nothing. When the posture-diff flag is ON, a COMPONENT
		// with no in-window release but a non-empty from-baseline-widened set STILL yields a populated
		// AggregatedChangelog (legacy findingChanges empty, postureFindingChanges + overTime populated).
		// The bypass is gated on the flag AND only applies to the AGGREGATED, all-branches, non-product
		// path (the only path the component posture-diff wires into); every other empty case keeps the
		// legacy null return, and the flag-OFF behavior is unchanged.
		boolean postureEnabled = posturePathEnabled(org); // evaluated ONCE per request (org settings lookup)
		boolean componentPostureEligible = postureEnabled
				&& aggregationType == AggregationType.AGGREGATED
				&& branchUuid == null
				&& component.getType() != ComponentType.PRODUCT;
		// BRANCH-scope posture-diff eligibility (board task #38, phase 3 -- branch path). Mirrors the
		// component gate but for the AGGREGATED path when a specific branchUuid IS provided (viewing one
		// branch's changelog). Unlike the all-branches component path, this reconstructs the posture for
		// THAT SINGLE BRANCH only (no enumeration of the component's other branches). Non-product only
		// (products don't route through branchUuid changelogs). flag-OFF keeps the legacy null return.
		boolean branchPostureEligible = postureEnabled
				&& aggregationType == AggregationType.AGGREGATED
				&& branchUuid != null
				&& component.getType() != ComponentType.PRODUCT;
		// PRODUCT-scope posture-diff eligibility (board task #38, phase 3 -- product path). Mirrors the
		// component gate but for the PRODUCT AGGREGATED, all-branches path. A product's posture is the
		// union of its latest product-release's PINNED constituent releases (reconstructed live-at-T), so
		// it needs its own empty-window bypass and its own posture-diff computation.
		boolean productPostureEligible = postureEnabled
				&& aggregationType == AggregationType.AGGREGATED
				&& branchUuid == null
				&& component.getType() == ComponentType.PRODUCT;
		// FEATURE-SET-scoped PRODUCT posture-diff eligibility (board task #38, phase 3 -- product feature-set
		// path). A feature set is a product's branch, so this is the PRODUCT AGGREGATED path when a specific
		// branchUuid IS provided. Unlike productPostureEligible (all feature sets) it resolves the from/to
		// product-releases on THAT ONE branch only. flag-OFF keeps the legacy null / empty-posture return.
		boolean productBranchPostureEligible = postureEnabled
				&& aggregationType == AggregationType.AGGREGATED
				&& branchUuid != null
				&& component.getType() == ComponentType.PRODUCT;
		if (releasesByBranch.isEmpty()) {
			if (componentPostureEligible) {
				return computeEmptyWindowComponentPostureChangelog(
					component, org, dateFrom, dateTo);
			}
			if (branchPostureEligible) {
				return computeEmptyWindowBranchPostureChangelog(
					component, branchUuid, org, dateFrom, dateTo);
			}
			if (productPostureEligible) {
				return computeEmptyWindowProductPostureChangelog(
					component, org, dateFrom, dateTo);
			}
			if (productBranchPostureEligible) {
				return computeEmptyWindowProductBranchPostureChangelog(
					component, branchUuid, org, dateFrom, dateTo);
			}
			return null;
		}

		// For PRODUCT type, flatten releases and delegate to product-specific method
		if (component.getType() == ComponentType.PRODUCT) {
			List<ReleaseData> allProductReleases = releasesByBranch.values().stream()
				.flatMap(List::stream)
				.sorted(NEWEST_FIRST)
				.collect(Collectors.toList());
			ComponentChangelog productChangelog = computeProductChangelogFromReleases(
				allProductReleases, component, org, aggregationType, userTimeZone,
				null, null);
			// ADDITIVE (board task #38, phase 3): attach the PRODUCT-scope window posture-diff when the flag
			// is ON. Populated alongside the legacy findingChanges (the UI prefers postureFindingChanges when
			// present); null otherwise. flag-OFF behavior is unchanged.
			if (productPostureEligible && productChangelog instanceof AggregatedChangelog aggregated) {
				productChangelog = withPostureFindingChanges(aggregated,
					computeProductPostureDiff(component, org, dateFrom, dateTo));
			} else if (productBranchPostureEligible
					&& productChangelog instanceof AggregatedChangelog aggregated) {
				// FEATURE-SET-scoped: resolve the from/to product-releases on THIS branch only.
				productChangelog = withPostureFindingChanges(aggregated,
					computeProductBranchPostureDiff(component, branchUuid, org, dateFrom, dateTo));
			}
			return productChangelog;
		}
		
		// Find global first and last releases for metadata (across all branches)
		ReleaseData globalFirstRelease = null;
		ReleaseData globalLastRelease = null;
		for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
			ReleaseData branchFirst = branchReleases.get(branchReleases.size() - 1); // oldest in branch
			ReleaseData branchLast = branchReleases.get(0); // newest in branch
			
			if (globalFirstRelease == null || branchFirst.getCreatedDate().isBefore(globalFirstRelease.getCreatedDate())) {
				globalFirstRelease = branchFirst;
			}
			if (globalLastRelease == null || branchLast.getCreatedDate().isAfter(globalLastRelease.getCreatedDate())) {
				globalLastRelease = branchLast;
			}
		}
		Map<UUID, String> branchNameMap = branchService.getBranchDataList(releasesByBranch.keySet())
			.stream().collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));
		
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		ChangelogContext ctx = new ChangelogContext(
			releasesByBranch, component, org, globalFirstRelease, globalLastRelease,
			branchNameMap, userTimeZone, vcsRepoDataList);

		List<ReleaseData> allReleases = releasesByBranch.values().stream().flatMap(List::stream).toList();

		// ADDITIVE (board task #37): re-scan-driven finding changes over time. Read from the
		// finding_change_events diff table (board task #38, phase 3). The result carries the
		// retention-horizon clamp lower bound (non-null only when the window was clamped).
		OverTimeFindingChangesResult overTime = computeOverTimeFindingChanges(
			org, allReleases, dateFrom, dateTo);

		if (aggregationType == AggregationType.NONE) {
			return withOverTimeFindingChanges(computeNoneChangelog(ctx), overTime);
		}

		Map<UUID, List<AcollectionData>> releaseAcollectionsMap = prefetchAcollections(allReleases);
		AggregatedChangelog aggregated = computeAggregatedChangelog(ctx, releaseAcollectionsMap, new HashMap<>());
		// ADDITIVE (board task #38, phase 3): attach the COMPONENT-scope window posture-diff when the flag
		// is ON (all-branches, non-product path). Populated alongside the legacy findingChanges (the UI
		// prefers postureFindingChanges when present); null otherwise.
		if (componentPostureEligible) {
			aggregated = withPostureFindingChanges(aggregated,
				computeComponentPostureDiff(component, org, dateFrom, dateTo));
		}
		// ADDITIVE (board task #38, phase 3 -- branch path): attach the BRANCH-scope window posture-diff
		// when the flag is ON and a specific branch is being viewed. Reconstructs THAT SINGLE branch's
		// posture (no other-branch enumeration); populated alongside the legacy findingChanges.
		if (branchPostureEligible) {
			aggregated = withPostureFindingChanges(aggregated,
				computeBranchPostureDiff(component, branchUuid, org, dateFrom, dateTo));
		}
		return withOverTimeFindingChanges(aggregated, overTime);
	}

	/**
	 * Builds the COMPONENT-scope window posture-diff (board task #38, phase 3) for a single component,
	 * reusing the org-level machinery verbatim: it widens the component's release set to include the
	 * from-baseline (branch-latest created &lt;= dateFrom) via
	 * {@link #fetchReleasesForComponentWithFromBaseline}, wraps it in a single-entry
	 * {@code postureReleasesMap}, resolves the component + branch names, and delegates to
	 * {@link FindingComparisonService#computePostureDiff}. {@code totalComponents == 1}, so the
	 * cross-component-only flags (isInheritedInAllComponents / cross-component New suppression) are no-ops.
	 * Returns null when the widened set is empty (nothing to diff). Best-effort: never throws.
	 */
	private FindingChangesWithAttribution computeComponentPostureDiff(
			ComponentData component, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		try {
			UUID componentUuid = component.getUuid();
			List<ReleaseData> widened = fetchReleasesForComponentWithFromBaseline(
				componentUuid, dateFrom, dateTo);
			if (widened.isEmpty()) {
				return null;
			}
			Map<UUID, List<ReleaseData>> postureReleasesMap = new LinkedHashMap<>();
			postureReleasesMap.put(componentUuid, widened);

			Map<UUID, String> componentNamesMap = new HashMap<>();
			componentNamesMap.put(componentUuid, component.getName());

			// Resolve branch names for every posture branch (branches with no in-window release are
			// pulled in by the from-baseline enumeration), mirroring the org flag-ON backfill pattern,
			// so attribution resolves instead of rendering as "Unknown".
			Set<UUID> postureBranchUuids = widened.stream()
				.map(ReleaseData::getBranch).collect(Collectors.toSet());
			Map<UUID, String> branchNameMap = postureBranchUuids.isEmpty() ? new HashMap<>()
				: branchService.getBranchDataList(postureBranchUuids).stream()
					.collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));

			return findingComparisonService.computePostureDiff(
				org, postureReleasesMap, componentNamesMap, branchNameMap, dateFrom, dateTo);
		} catch (Exception e) {
			log.error("Error computing component posture-diff for component {}",
				component.getUuid(), e);
			return null;
		}
	}

	/**
	 * Returns a copy of the AggregatedChangelog with {@code postureFindingChanges} attached; the rest of
	 * the record is copied verbatim (ADDITIVE).
	 */
	private AggregatedChangelog withPostureFindingChanges(
			AggregatedChangelog a, FindingChangesWithAttribution posture) {
		return new AggregatedChangelog(a.componentUuid(), a.componentName(), a.orgUuid(),
			a.firstRelease(), a.lastRelease(), a.branches(), a.sbomChanges(), a.findingChanges(),
			a.overTimeFindingChanges(), a.overTimeFindingChangesSince(), posture);
	}

	/**
	 * EMPTY-WINDOW component changelog (board task #38, phase 3): a component with NO in-window release but
	 * a non-empty from-baseline-widened set. Returns a populated AggregatedChangelog with EMPTY legacy
	 * pieces (branches / sbom / findingChanges) and populated {@code postureFindingChanges} +
	 * {@code overTimeFindingChanges}, so the UI can render the posture-diff even though the legacy
	 * release-centric path found nothing. Returns null (legacy behavior) only when the widened set is also
	 * empty -- i.e. there is genuinely nothing to show. Only reached when {@code componentPostureEligible}.
	 */
	private ComponentChangelog computeEmptyWindowComponentPostureChangelog(
			ComponentData component, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		FindingChangesWithAttribution posture = computeComponentPostureDiff(component, org, dateFrom, dateTo);
		if (posture == null) {
			// Nothing in-window and nothing in the widened baseline: keep the legacy null (render nothing).
			return null;
		}
		// Over-time changes are keyed on the widened release set (empty in-window -> use the baseline set),
		// best-effort. Keeps parity with the non-empty path's overTime population.
		List<ReleaseData> widened = fetchReleasesForComponentWithFromBaseline(
			component.getUuid(), dateFrom, dateTo);
		OverTimeFindingChangesResult overTime = computeOverTimeFindingChanges(org, widened, dateFrom, dateTo);

		AggregatedChangelog aggregated = new AggregatedChangelog(
			component.getUuid(), component.getName(), org,
			null, null, List.of(),
			SbomChangesWithAttribution.EMPTY, FindingChangesWithAttribution.EMPTY,
			overTime.changes(), overTime.clampedSince(), posture);
		return aggregated;
	}

	/**
	 * BRANCH-scope release set (board task #38, phase 3 -- branch path): the SINGLE branch's in-window
	 * releases widened with that branch's from-anchor (its latest release created {@code <= dateFrom}). This
	 * is the branch analogue of {@link #fetchReleasesForComponentWithFromBaseline} -- but it enumerates NO
	 * other branch: only the one being viewed. De-duped by release UUID, sorted NEWEST_FIRST. May be empty
	 * (branch has neither an in-window release nor a from-anchor).
	 */
	private List<ReleaseData> fetchReleasesForBranchWithFromBaseline(
			UUID branchUuid, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		Map<UUID, ReleaseData> byUuid = new LinkedHashMap<>();
		List<ReleaseData> inWindow = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
			branchUuid, dateFrom, dateTo, ReleaseLifecycle.DRAFT);
		if (inWindow != null) {
			inWindow.forEach(r -> byUuid.put(r.getUuid(), r));
		}
		sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(branchUuid, dateFrom)
			.ifPresent(baseline -> byUuid.putIfAbsent(baseline.getUuid(), baseline));
		List<ReleaseData> merged = new ArrayList<>(byUuid.values());
		merged.sort(NEWEST_FIRST);
		return merged;
	}

	/**
	 * Builds the BRANCH-scope window posture-diff (board task #38, phase 3 -- branch path). Reconstructs the
	 * finding posture for ONE branch at {@code from}/{@code to}: it widens the branch's in-window release set
	 * with that branch's from-anchor (branch-latest created {@code <= dateFrom}) via
	 * {@link #fetchReleasesForBranchWithFromBaseline}, wraps it in a single-entry {@code postureReleasesMap}
	 * keyed by the component UUID (the map is per-component; the branch scoping comes from restricting the
	 * releases to this branch -- {@code computePostureDiff} groups by branch internally), resolves the
	 * component + branch names, and delegates to {@link FindingComparisonService#computePostureDiff}. Unlike
	 * {@link #computeComponentPostureDiff} it does NOT enumerate the component's other branches. Returns null
	 * when the widened set is empty (nothing to diff). Best-effort: never throws.
	 */
	private FindingChangesWithAttribution computeBranchPostureDiff(
			ComponentData component, UUID branchUuid, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		try {
			UUID componentUuid = component.getUuid();
			List<ReleaseData> widened = fetchReleasesForBranchWithFromBaseline(branchUuid, dateFrom, dateTo);
			if (widened.isEmpty()) {
				return null;
			}
			Map<UUID, List<ReleaseData>> postureReleasesMap = new LinkedHashMap<>();
			postureReleasesMap.put(componentUuid, widened);

			Map<UUID, String> componentNamesMap = new HashMap<>();
			componentNamesMap.put(componentUuid, component.getName());

			Map<UUID, String> branchNameMap = branchService.getBranchDataList(Set.of(branchUuid)).stream()
				.collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));

			return findingComparisonService.computePostureDiff(
				org, postureReleasesMap, componentNamesMap, branchNameMap, dateFrom, dateTo);
		} catch (Exception e) {
			log.error("Error computing branch posture-diff for component {} branch {}",
				component.getUuid(), branchUuid, e);
			return null;
		}
	}

	/**
	 * EMPTY-WINDOW branch changelog (board task #38, phase 3 -- branch path): a branch with NO in-window
	 * release but a present from-anchor (a release created {@code <= dateFrom}). Mirrors
	 * {@link #computeEmptyWindowComponentPostureChangelog} for the single branch -- returns a populated
	 * AggregatedChangelog with EMPTY legacy pieces and populated {@code postureFindingChanges} +
	 * {@code overTimeFindingChanges} (all the branch's findings surface as StillPresent via the from-anchor,
	 * which also serves as the to-anchor). Returns null (legacy behavior) only when even the from-anchor is
	 * absent -- the branch has no release {@code <= from}, so there is genuinely nothing to show. Only reached
	 * when {@code branchPostureEligible}.
	 */
	private ComponentChangelog computeEmptyWindowBranchPostureChangelog(
			ComponentData component, UUID branchUuid, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		FindingChangesWithAttribution posture = computeBranchPostureDiff(
			component, branchUuid, org, dateFrom, dateTo);
		if (posture == null) {
			return null;
		}
		List<ReleaseData> widened = fetchReleasesForBranchWithFromBaseline(branchUuid, dateFrom, dateTo);
		OverTimeFindingChangesResult overTime = computeOverTimeFindingChanges(org, widened, dateFrom, dateTo);

		return new AggregatedChangelog(
			component.getUuid(), component.getName(), org,
			null, null, List.of(),
			SbomChangesWithAttribution.EMPTY, FindingChangesWithAttribution.EMPTY,
			overTime.changes(), overTime.clampedSince(), posture);
	}

	/**
	 * Builds the PRODUCT-scope window posture-diff (board task #38, phase 3 -- product path). A product does
	 * NOT own findings: its posture at time T is the UNION of the PINNED constituent component-releases of
	 * the product's latest PRODUCT-release created {@code <= T}, each reconstructed LIVE-AT-T. So this method
	 * resolves the from-product-release (latest product-release {@code <= dateFrom}) and the to-product-release
	 * (latest {@code <= dateTo}) across the product's active branches, extracts each one's pinned constituent
	 * releases from {@code getParentReleases()} -&gt; {@link ParentRelease#getRelease()}, fetches those
	 * constituent releases WITH full metrics, and delegates to
	 * {@link FindingComparisonService#computeProductPostureDiff}. Unlike the component path it does NOT
	 * date-pick among constituents (the pinned releases are exact); re-pins fold as appeared/resolved and
	 * re-scans are caught by the endpoint reconstruction. Returns null when there is no from- and no
	 * to-product-release (nothing to diff). Best-effort: never throws.
	 */
	private FindingChangesWithAttribution computeProductPostureDiff(
			ComponentData product, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		return computeProductPostureDiff(
			latestProductReleaseAtOrBefore(product.getUuid(), dateFrom),
			latestProductReleaseAtOrBefore(product.getUuid(), dateTo),
			product, org, dateFrom, dateTo);
	}

	/**
	 * Shared PRODUCT-scope posture-diff builder, parameterized by the already-resolved from/to product
	 * releases (board task #38, phase 3 -- product path). Both the all-feature-sets path
	 * ({@link #computeProductPostureDiff(ComponentData, UUID, ZonedDateTime, ZonedDateTime)}, resolves the
	 * endpoints ACROSS the product's active branches) and the FEATURE-SET-scoped path
	 * ({@link #computeProductBranchPostureDiff}, resolves them on a SINGLE branch) funnel through here so the
	 * pinned-constituent extraction + {@link FindingComparisonService#computeProductPostureDiff} machinery is
	 * shared. The only difference between the two callers is HOW the from/to product-releases are picked.
	 * Returns null when there is no from- and no to-product-release (nothing to diff). Best-effort: never
	 * throws.
	 */
	private FindingChangesWithAttribution computeProductPostureDiff(
			ReleaseData fromProduct, ReleaseData toProduct,
			ComponentData product, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		try {
			if (fromProduct == null && toProduct == null) {
				return null;
			}

			// Collect every pinned constituent release UUID across both endpoints, fetch them WITH full
			// metrics in one batch (the posture-diff reads getVulnerabilityDetails / getMetrics), then
			// resolve component names for attribution.
			Set<UUID> constituentUuids = new HashSet<>();
			collectPinnedReleaseUuids(fromProduct, constituentUuids);
			collectPinnedReleaseUuids(toProduct, constituentUuids);
			if (constituentUuids.isEmpty()) {
				return null;
			}
			Map<UUID, ReleaseData> constituentByUuid = sharedReleaseService
				.getReleaseDataList(constituentUuids, org).stream()
				.collect(Collectors.toMap(ReleaseData::getUuid, Function.identity(), (a, b) -> a));

			Set<UUID> constituentComponents = constituentByUuid.values().stream()
				.map(ReleaseData::getComponent).collect(Collectors.toSet());
			Map<UUID, String> componentNames = constituentComponents.stream()
				.map(getComponentService::getComponentData)
				.filter(Optional::isPresent).map(Optional::get)
				.collect(Collectors.toMap(ComponentData::getUuid, ComponentData::getName, (a, b) -> a));

			List<FindingComparisonService.ProductConstituent> fromConstituents =
				toProductConstituents(fromProduct, constituentByUuid, componentNames);
			List<FindingComparisonService.ProductConstituent> toConstituents =
				toProductConstituents(toProduct, constituentByUuid, componentNames);

			return findingComparisonService.computeProductPostureDiff(
				org, fromConstituents, toConstituents, dateFrom, dateTo);
		} catch (Exception e) {
			log.error("Error computing product posture-diff for product {}",
				product.getUuid(), e);
			return null;
		}
	}

	/**
	 * The product's latest PRODUCT-release created {@code <= at}, across all its active branches (bounded
	 * LIMIT-1 lookup per branch, newest wins). Returns null when the product has no release at-or-before
	 * {@code at}. Full ReleaseData (carries {@code getParentReleases()} for constituent extraction).
	 */
	private ReleaseData latestProductReleaseAtOrBefore(UUID productUuid, ZonedDateTime at) {
		// BranchData so findingAnalyticsParticipation is available: EXCLUDED feature sets (product branches an
		// operator opted out of finding analytics) are skipped so the product posture-diff resolves its
		// endpoints from the SAME branch set as the analytics chart (board task F2).
		List<BranchData> activeBranches = branchService.listBranchDataOfComponent(productUuid, StatusEnum.ACTIVE);
		ReleaseData latest = null;
		for (BranchData branch : activeBranches) {
			if (branch.getFindingAnalyticsParticipation() == BranchData.FindingAnalyticsParticipation.EXCLUDED) {
				continue;
			}
			ReleaseData candidate = sharedReleaseService
				.getBranchLatestReleaseAtOrBeforeDate(branch.getUuid(), at).orElse(null);
			// Retired product releases (END_OF_SUPPORT+) leave the posture. The product path bypasses the
			// component/branch in-window filter (computePostureDiff), so skip them here. This drops a branch's
			// retired latest without falling back to an OLDER same-branch release; the common in-place
			// retirement selects the same (now-retired) release at both endpoints -> both dropped -> clean.
			// (Accepted edge: a NEWER EOL release over an OLDER still-GA release within the window can fold as
			// resolved -- retirement normally flows old->new, so this is rare.)
			if (candidate == null || candidate.getCreatedDate() == null
					|| ReleaseLifecycle.isSupportEnded(candidate.getLifecycle())) continue;
			if (latest == null || candidate.getCreatedDate().isAfter(latest.getCreatedDate())) {
				latest = candidate;
			}
		}
		return latest;
	}

	/**
	 * The FEATURE-SET-scoped analogue of {@link #latestProductReleaseAtOrBefore}: the latest PRODUCT-release
	 * created {@code <= at} on ONE specific product branch (feature set), not across all the product's
	 * branches. Returns null when that feature set has no product-release at-or-before {@code at}. Full
	 * ReleaseData (carries {@code getParentReleases()} for constituent extraction).
	 */
	private ReleaseData latestProductBranchReleaseAtOrBefore(UUID branchUuid, ZonedDateTime at) {
		// A feature set whose latest product-release is retired (END_OF_SUPPORT+) leaves the posture: return
		// null so the endpoint folds to empty (this path bypasses the component/branch in-window filter).
		// Same accepted new-EOL-over-older-GA-in-window edge as latestProductReleaseAtOrBefore.
		return sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(branchUuid, at)
			.filter(r -> !ReleaseLifecycle.isSupportEnded(r.getLifecycle()))
			.orElse(null);
	}

	/**
	 * Builds the FEATURE-SET-scoped PRODUCT window posture-diff (board task #38, phase 3 -- product
	 * feature-set path). A "feature set" is a product's branch, so a feature-set-scoped changelog is
	 * {@code componentChangelogByDate} on a PRODUCT with {@code branchUuid} set. Unlike
	 * {@link #computeProductPostureDiff(ComponentData, UUID, ZonedDateTime, ZonedDateTime)} (which resolves
	 * the from/to product-releases ACROSS all the product's active branches), this resolves BOTH endpoints on
	 * the SINGLE {@code branchUuid} via {@link #latestProductBranchReleaseAtOrBefore}, then reuses the exact
	 * same pinned-constituent extraction + {@link FindingComparisonService#computeProductPostureDiff} via the
	 * shared {@link #computeProductPostureDiff(ReleaseData, ReleaseData, ComponentData, UUID, ZonedDateTime, ZonedDateTime)}.
	 * Returns null when the feature set has neither a from- nor a to-product-release. Best-effort: never throws.
	 */
	private FindingChangesWithAttribution computeProductBranchPostureDiff(
			ComponentData product, UUID branchUuid, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		// An EXCLUDED feature set does not participate in finding analytics (it has no analytics chart line),
		// so its posture-diff Summary is empty too -- consistent with the chart / over-time exclusion (board
		// task F2). Returning null renders no posture-diff, matching the no-release-in-window case.
		BranchData branch = branchService.getBranchData(branchUuid).orElse(null);
		if (branch != null
				&& branch.getFindingAnalyticsParticipation() == BranchData.FindingAnalyticsParticipation.EXCLUDED) {
			return null;
		}
		return computeProductPostureDiff(
			latestProductBranchReleaseAtOrBefore(branchUuid, dateFrom),
			latestProductBranchReleaseAtOrBefore(branchUuid, dateTo),
			product, org, dateFrom, dateTo);
	}

	/** Adds every pinned constituent release UUID of {@code productRelease} to {@code out} (null-safe). */
	private void collectPinnedReleaseUuids(ReleaseData productRelease, Set<UUID> out) {
		if (productRelease == null || productRelease.getParentReleases() == null) return;
		for (ParentRelease pr : productRelease.getParentReleases()) {
			if (pr.getRelease() != null) out.add(pr.getRelease());
		}
	}

	/**
	 * Maps a product-release's pinned constituents to {@link FindingComparisonService.ProductConstituent},
	 * keyed by the constituent's component. Constituent releases that no longer resolve are skipped.
	 * Returns an empty list for a null product-release (that endpoint contributes no findings -> the other
	 * endpoint's constituents fold as appeared / resolved).
	 */
	private List<FindingComparisonService.ProductConstituent> toProductConstituents(
			ReleaseData productRelease,
			Map<UUID, ReleaseData> constituentByUuid,
			Map<UUID, String> componentNames) {
		List<FindingComparisonService.ProductConstituent> result = new ArrayList<>();
		if (productRelease == null || productRelease.getParentReleases() == null) return result;
		for (ParentRelease pr : productRelease.getParentReleases()) {
			if (pr.getRelease() == null) continue;
			ReleaseData constituent = constituentByUuid.get(pr.getRelease());
			if (constituent == null) continue;
			UUID componentUuid = constituent.getComponent();
			result.add(new FindingComparisonService.ProductConstituent(
				componentUuid, componentNames.get(componentUuid), constituent));
		}
		return result;
	}

	/**
	 * EMPTY-WINDOW product changelog (board task #38, phase 3 -- product path). A product with NO in-window
	 * product-release but a from-product-release {@code <= dateFrom} must STILL return a populated
	 * AggregatedChangelog with {@code postureFindingChanges} (+ overTime) instead of null, mirroring the
	 * component empty-window bypass. Returns null (legacy behavior) only when there is genuinely nothing to
	 * diff. Only reached when {@code productPostureEligible}.
	 */
	private ComponentChangelog computeEmptyWindowProductPostureChangelog(
			ComponentData product, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		return buildEmptyWindowProductPostureChangelog(
			product, org, dateFrom, dateTo,
			computeProductPostureDiff(product, org, dateFrom, dateTo),
			latestProductReleaseAtOrBefore(product.getUuid(), dateFrom));
	}

	/**
	 * FEATURE-SET-scoped analogue of {@link #computeEmptyWindowProductPostureChangelog} (board task #38,
	 * phase 3 -- product feature-set path). A product feature set (branch) with NO in-window product-release
	 * but a from-product-release {@code <= dateFrom} on THAT branch still yields a populated
	 * AggregatedChangelog (its pinned constituents surface as StillPresent) instead of null. Resolves both the
	 * posture and the over-time from-baseline on the SINGLE {@code branchUuid}. Only reached when
	 * {@code productBranchPostureEligible}.
	 */
	private ComponentChangelog computeEmptyWindowProductBranchPostureChangelog(
			ComponentData product, UUID branchUuid, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		return buildEmptyWindowProductPostureChangelog(
			product, org, dateFrom, dateTo,
			computeProductBranchPostureDiff(product, branchUuid, org, dateFrom, dateTo),
			latestProductBranchReleaseAtOrBefore(branchUuid, dateFrom));
	}

	/**
	 * Shared empty-window PRODUCT changelog assembler for both the all-feature-sets and the feature-set-scoped
	 * paths. Given the already-resolved {@code posture} and the from-product-release (all-branches vs
	 * single-branch differs only in how those two were resolved), keys the over-time list on the
	 * from-product-release's pinned constituents (best-effort) and wraps everything in an AggregatedChangelog
	 * with EMPTY legacy pieces. Returns null (legacy behavior) when {@code posture} is null.
	 */
	private ComponentChangelog buildEmptyWindowProductPostureChangelog(
			ComponentData product, UUID org, ZonedDateTime dateFrom, ZonedDateTime dateTo,
			FindingChangesWithAttribution posture, ReleaseData fromProduct) {
		if (posture == null) {
			return null;
		}
		// Over-time changes keyed on the from-product-release's pinned constituents (empty in-window ->
		// use the from-baseline constituents), best-effort. Keeps parity with the non-empty path.
		List<ReleaseData> overTimeReleases = new ArrayList<>();
		if (fromProduct != null) {
			Set<UUID> uuids = new HashSet<>();
			collectPinnedReleaseUuids(fromProduct, uuids);
			if (!uuids.isEmpty()) {
				overTimeReleases = sharedReleaseService.getReleaseDataListLight(uuids, org);
			}
		}
		OverTimeFindingChangesResult overTime = computeOverTimeFindingChanges(org, overTimeReleases, dateFrom, dateTo);

		return new AggregatedChangelog(
			product.getUuid(), product.getName(), org,
			null, null, List.of(),
			SbomChangesWithAttribution.EMPTY, FindingChangesWithAttribution.EMPTY,
			overTime.changes(), overTime.clampedSince(), posture);
	}

	/**
	 * Best-effort computation of the "Finding changes over time" list for a single component's
	 * in-window releases (board task #37), read from the persisted {@code finding_change_events} diff
	 * table (board task #38, phase 3) rather than re-diffing {@code metrics_audit}. The release-UUID
	 * set continues to carry the perspective/authorization boundary. Never throws -- a failure returns
	 * an empty list so the release-anchored changelog response is unaffected.
	 */
	private OverTimeFindingChangesResult computeOverTimeFindingChanges(
			UUID org, List<ReleaseData> releases,
			ZonedDateTime dateFrom, ZonedDateTime dateTo) {
		try {
			Set<UUID> releaseUuids = releases.stream().map(ReleaseData::getUuid).collect(Collectors.toSet());
			return findingComparisonService.loadOverTimeFindingChanges(
				org, releaseUuids, dateFrom, dateTo);
		} catch (Exception e) {
			log.error("Error computing over-time finding changes for org {}", org, e);
			return OverTimeFindingChangesResult.EMPTY;
		}
	}

	/**
	 * Returns a copy of the given component changelog with {@code overTimeFindingChanges} and the
	 * retention-clamp {@code overTimeFindingChangesSince} populated. Only NONE/AGGREGATED component
	 * changelogs carry the fields; product changelogs are returned unchanged. ADDITIVE -- the rest of
	 * the record is copied verbatim.
	 */
	private ComponentChangelog withOverTimeFindingChanges(
			ComponentChangelog cl, OverTimeFindingChangesResult overTime) {
		if (cl instanceof NoneChangelog n) {
			return new NoneChangelog(n.componentUuid(), n.componentName(), n.orgUuid(),
				n.firstRelease(), n.lastRelease(), n.branches(), overTime.changes(), overTime.clampedSince());
		}
		if (cl instanceof AggregatedChangelog a) {
			return new AggregatedChangelog(a.componentUuid(), a.componentName(), a.orgUuid(),
				a.firstRelease(), a.lastRelease(), a.branches(), a.sbomChanges(), a.findingChanges(),
				overTime.changes(), overTime.clampedSince(), a.postureFindingChanges());
		}
		return cl;
	}
	
	/**
	 * Gets organization-wide changelog for a date range using the new sealed interface pattern.
	 * Returns either NoneOrganizationChangelog (per-component, per-release) or 
	 * AggregatedOrganizationChangelog (org-wide summary with attribution).
	 * 
	 * @param orgUuid Organization UUID
	 * @param perspectiveUuid Optional perspective UUID to filter components
	 * @param dateFrom Start date
	 * @param dateTo End date
	 * @param aggregationType NONE (per-component breakdown) or AGGREGATED (org-wide summary)
	 * @param userTimeZone User's timezone for date formatting
	 * @return OrganizationChangelog (sealed interface)
	 * @throws RelizaException if no components or releases found
	 */
	public OrganizationChangelog getOrganizationChangelogByDate(
			UUID orgUuid,
			UUID perspectiveUuid,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo,
			AggregationType aggregationType,
			String userTimeZone) throws RelizaException {
		
		// Get components based on perspective
		List<ComponentData> components = getComponentsForOrganizationChangelog(orgUuid, perspectiveUuid);
		
		if (components.isEmpty()) {
			throw new RelizaException("No components found for organization " + orgUuid + 
				(perspectiveUuid != null ? " with perspective " + perspectiveUuid : ""));
		}
		
		List<ComponentChangelog> componentChangelogs = new ArrayList<>();
		Map<UUID, List<AcollectionData>> componentAcollectionsMap = new HashMap<>();
		Map<UUID, List<ReleaseData>> componentReleasesMap = new HashMap<>();
		Map<UUID, String> componentNamesMap = new HashMap<>();
		Map<String, ReleaseData> forkPointCache = new HashMap<>();
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(orgUuid);
		
		// Phase 1: Fetch all releases per component and collect all branch UUIDs
		Map<UUID, List<ReleaseData>> componentReleasesLocal = new LinkedHashMap<>();
		Set<UUID> allBranchUuids = new HashSet<>();
		for (ComponentData component : components) {
			List<ReleaseData> releases = fetchReleasesForComponentBetweenDates(
				component.getUuid(), dateFrom, dateTo);
			if (!releases.isEmpty()) {
				componentReleasesLocal.put(component.getUuid(), releases);
				releases.forEach(rd -> allBranchUuids.add(rd.getBranch()));
			}
		}
		
		// Phase 2: Batch-resolve all branch names in one call
		Map<UUID, String> branchNameMap = allBranchUuids.isEmpty() ? new HashMap<>()
			: branchService.getBranchDataList(allBranchUuids).stream()
				.collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));
		
		// Phase 3: Process each component using pre-resolved branch names
		for (ComponentData component : components) {
			List<ReleaseData> releases = componentReleasesLocal.get(component.getUuid());
			if (releases == null || releases.isEmpty()) continue;
			
			try {
				// Convert flat list to grouped structure for new methods
				LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch = groupReleasesByBranch(releases);
				ReleaseData globalFirst = releases.get(releases.size() - 1);
				ReleaseData globalLast = releases.get(0);
				
				ChangelogContext ctx = new ChangelogContext(
					releasesByBranch, component, orgUuid, globalFirst, globalLast,
					branchNameMap, userTimeZone, vcsRepoDataList);
				
				// Pre-fetch acollections once for all releases (used by both component-level and org-level SBOM)
				Map<UUID, List<AcollectionData>> releaseAcollectionsMap = (aggregationType == AggregationType.AGGREGATED)
					? prefetchAcollections(releases) : new HashMap<>();
				
				ComponentChangelog componentChangelog = (aggregationType == AggregationType.NONE)
					? computeNoneChangelog(ctx)
					: computeAggregatedChangelog(ctx, releaseAcollectionsMap, forkPointCache);
				componentChangelogs.add(componentChangelog);
				
				// Collect data for org-wide aggregation (AGGREGATED mode only)
				if (aggregationType == AggregationType.AGGREGATED) {
					List<AcollectionData> latestAcollections = pickLatestAcollections(releaseAcollectionsMap, releases);
					if (!latestAcollections.isEmpty()) {
						componentAcollectionsMap.put(component.getUuid(), latestAcollections);
					}
					componentReleasesMap.put(component.getUuid(), releases);
					componentNamesMap.put(component.getUuid(), component.getName());
				}
				
			} catch (Exception e) {
				log.error("Error computing changelog for component {}: {}", component.getUuid(), e.getMessage());
				// Continue with other components
			}
		}
		
		// Re-scan-driven changes (a CVE newly disclosed against an already-shipped release) move org posture
		// WITHOUT producing an in-window release, so the release-anchored componentChangelogs can be empty on
		// a real change day -- exactly when the Findings-Over-Time chart shows a spike. For AGGREGATED +
		// posture-path orgs, proceed and compute the org rollup + over-time from finding_change_events instead
		// of bailing (board task #42); the per-component release/SBOM sections are simply empty. NONE mode and
		// legacy-path orgs keep the old behavior (no event-based rollup to fall back on).
		// Gate note: the no-bail rollup is gated on posturePathEnabled (the org's finding_change_events
		// backfill is complete) while the over-time list below is gated on isOrgPostureReadAvailable
		// (write-mode == V3_ONLY, no fallback). These are deliberately independent -- the posture ROLLUP
		// can be reconstructed from v2 or v3, but the org-scope over-time SCAN is a v3-only read. They
		// coincide on the only instances that have events at all (operated instances are V3_ONLY;
		// customers have no finding_change_events, so posturePathEnabled is false for them). The narrow
		// theoretical gap (posture-enabled but non-V3_ONLY org, empty in-window releases) renders the
		// rollup with an empty over-time list -- degraded, never wrong.
		boolean eventDrivenOrgRollup = changelogRescanInclusiveEnabled
			&& aggregationType == AggregationType.AGGREGATED && posturePathEnabled(orgUuid);
		if (componentChangelogs.isEmpty() && !eventDrivenOrgRollup) {
			throw new RelizaException("No changelog data found for organization " + orgUuid +
				" in date range " + dateFrom + " to " + dateTo);
		}
		
		// ADDITIVE (board task #37): re-scan-driven finding changes over time, read from the
		// finding_change_events diff table (board task #38, phase 3). Independent of the
		// release-anchored changelog above. The union of perspective-filtered release UUIDs across
		// all components carries the perspective/authorization boundary. Best-effort: a failure here
		// must not break the changelog response.
		OverTimeFindingChangesResult overTime;
		try {
			if (changelogRescanInclusiveEnabled && aggregationType == AggregationType.AGGREGATED
					&& findingComparisonService.isOrgPostureReadAvailable(orgUuid)) {
				// Rescan-inclusive org over-time (board task #42): read all authorized finding-change events
				// by change_date, NOT just those on in-window-produced releases, so re-scan changes surface.
				// Component set = the perspective/org allowlist; EXCLUDED-participation branches dropped to
				// match the analytics chart.
				Set<UUID> allowedComponentUuids = components.stream()
					.map(ComponentData::getUuid).collect(Collectors.toSet());
				Set<UUID> excludedBranchUuids = branchService.listBranchDataOfOrg(orgUuid).stream()
					.filter(b -> b.getFindingAnalyticsParticipation() == BranchData.FindingAnalyticsParticipation.EXCLUDED)
					.map(BranchData::getUuid).collect(Collectors.toSet());
				overTime = findingComparisonService.loadOrgPostureOverTime(
					orgUuid, allowedComponentUuids, excludedBranchUuids, dateFrom, dateTo);
			} else {
				// Release-anchored over-time: flag off, or a non-V3_ONLY AGGREGATED org. (NONE mode never
				// reaches here -- it returns a NoneOrganizationChangelog above.)
				Set<UUID> releaseUuids = new HashSet<>();
				for (ComponentData component : components) {
					List<ReleaseData> releases = componentReleasesLocal.get(component.getUuid());
					if (releases != null) {
						releases.forEach(r -> releaseUuids.add(r.getUuid()));
					}
				}
				overTime = findingComparisonService.loadOverTimeFindingChanges(
					orgUuid, releaseUuids, dateFrom, dateTo);
			}
		} catch (Exception e) {
			log.error("Error computing over-time finding changes for organization {}", orgUuid, e);
			overTime = OverTimeFindingChangesResult.EMPTY;
		}

		// Return appropriate type based on aggregation mode
		if (aggregationType == AggregationType.NONE) {
			return new NoneOrganizationChangelog(orgUuid, dateFrom, dateTo, componentChangelogs,
				overTime.changes(), overTime.clampedSince());
		} else {
			// Compute org-wide SBOM and Finding aggregation
			SbomChangesWithAttribution orgSbomChanges = sbomComparisonService.aggregateChangelogsWithAttribution(
				componentAcollectionsMap, componentReleasesMap, branchNameMap, componentNamesMap);

			// "Finding Changes" rollup: legacy pairwise release diff, or (flag ON) the posture-endpoint
			// diff (board task #38, phase 3). The posture-diff path widens each component's release set
			// to include the from-baseline (branch-latest created <= dateFrom) so the from-endpoint can be
			// reconstructed; this widened set is scoped to the org rollup only -- the per-component /
			// NONE changelogs above are untouched.
			FindingChangesWithAttribution orgFindingChanges;
			if (posturePathEnabled(orgUuid)) {
				// Build the org posture release map with the from-anchors resolved in a BATCHED pass
				// (org posture-diff N+1 elimination): ONE chunked branch-latest query instead of a
				// per-branch LIMIT-1 lookup for every active branch of every component. Byte-identical
				// result to the old per-component fetchReleasesForComponentWithFromBaseline loop.
				Map<UUID, List<ReleaseData>> postureReleasesMap = buildOrgPostureReleasesMap(
					orgUuid, components, componentNamesMap, dateFrom, dateTo);
				// Backfill branch names for any posture branches not already mapped (branches with
				// no in-window release are absent from the in-window branchNameMap above), so their
				// attribution resolves instead of rendering as "Unknown".
				Set<UUID> postureBranchUuids = postureReleasesMap.values().stream()
					.flatMap(List::stream).map(ReleaseData::getBranch).collect(Collectors.toSet());
				postureBranchUuids.removeAll(branchNameMap.keySet());
				if (!postureBranchUuids.isEmpty()) {
					branchService.getBranchDataList(postureBranchUuids)
						.forEach(bd -> branchNameMap.putIfAbsent(bd.getUuid(), bd.getName()));
				}
				orgFindingChanges = findingComparisonService.computeOrgPostureDiff(
					orgUuid, postureReleasesMap, componentNamesMap, branchNameMap, dateFrom, dateTo,
					findingComparisonService.prefetchPostureReconstruction(postureReleasesMap, dateFrom, dateTo));
			} else {
				orgFindingChanges = findingComparisonService.compareMetricsAcrossComponents(
					componentReleasesMap, componentNamesMap, branchNameMap, forkPointCache);
			}
			return new AggregatedOrganizationChangelog(
				orgUuid, dateFrom, dateTo, componentChangelogs, orgSbomChanges, orgFindingChanges,
				overTime.changes(), overTime.clampedSince());
		}
	}
	
	/**
	 * Computes changelog for NONE mode (per-release breakdown).
	 * Computes code, SBOM, and finding changes separately for each release.
	 * 
	 * @param ctx Shared changelog context (component, org, branch names, releases, etc.)
	 * @return NoneChangelog with per-release breakdown
	 */
	/** Default / max page size for the finding-attribution drill-down. */
	private static final int DEFAULT_ATTR_PAGE_SIZE = 50;
	private static final int MAX_ATTR_PAGE_SIZE = 500;

	/**
	 * Drill-down for ONE finding's attribution bucket (the "+N more" expansion behind the preview-capped
	 * inline lists). Recomputes the SAME scope + path (posture vs legacy) as the changelog but with a
	 * single-finding filter and uncapped materialization, so the returned {@code total} equals the
	 * {@code *InCount} the changelog showed inline. Scope: org (only orgUuid) / component (+componentUuid) /
	 * branch (+componentUuid+branchUuid). Best-effort empty page if the finding is not present in scope.
	 */
	public ComponentAttributionPage getFindingAttributionPage(
			UUID orgUuid, UUID componentUuid, UUID branchUuid, UUID perspectiveUuid,
			ZonedDateTime dateFrom, ZonedDateTime dateTo,
			ChangelogFindingKind findingKind, String findingKey, FindingAttributionBucket bucket,
			int page, int pageSize) throws RelizaException {

		if (findingKey == null || findingKey.isBlank()) {
			throw new RelizaException("findingKey is required for findingAttributionByDate");
		}
		if (branchUuid != null && componentUuid == null) {
			throw new RelizaException("branchUuid requires componentUuid");
		}
		int safePage = Math.max(0, page);
		int safeSize = pageSize <= 0 ? DEFAULT_ATTR_PAGE_SIZE : Math.min(pageSize, MAX_ATTR_PAGE_SIZE);
		final int cap = Integer.MAX_VALUE; // full list for the single filtered finding (bounded: one finding)

		FindingChangesWithAttribution fc;
		if (componentUuid != null) {
			// Component/branch scope uses the posture-endpoint diff (computePostureDiff), matching the surface
			// that wires the drill-down: the component/product changelog feeds the UI its posture-diff
			// `postureFindingChanges` (whose *InCount this pages), and drill-down is UI-gated to those views.
			// The legacy pairwise `findingChanges` path (posture flag off) does NOT enable "+N more", so its
			// counts are never drilled -- see changelog-read-contract-redesign.md.
			ComponentData component = getComponentService.getComponentData(componentUuid)
				.orElseThrow(() -> new RelizaException("Component not found: " + componentUuid));
			List<ReleaseData> widened = (branchUuid != null)
				? fetchReleasesForBranchWithFromBaseline(branchUuid, dateFrom, dateTo)
				: fetchReleasesForComponentWithFromBaseline(componentUuid, dateFrom, dateTo);
			if (widened.isEmpty()) {
				return new ComponentAttributionPage(List.of(), 0, safePage, safeSize);
			}
			Map<UUID, List<ReleaseData>> postureReleasesMap = new LinkedHashMap<>();
			postureReleasesMap.put(componentUuid, widened);
			Map<UUID, String> componentNamesMap = new HashMap<>();
			componentNamesMap.put(componentUuid, component.getName());
			Set<UUID> branchUuids = widened.stream().map(ReleaseData::getBranch).collect(Collectors.toSet());
			Map<UUID, String> branchNameMap = branchUuids.isEmpty() ? new HashMap<>()
				: branchService.getBranchDataList(branchUuids).stream()
					.collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));
			fc = findingComparisonService.computePostureDiff(
				orgUuid, postureReleasesMap, componentNamesMap, branchNameMap, dateFrom, dateTo, findingKey, cap);
		} else {
			fc = computeOrgFindingChangesFiltered(orgUuid, perspectiveUuid, dateFrom, dateTo, findingKey, cap);
		}

		List<ComponentAttribution> full = extractAttributionBucket(fc, findingKind, findingKey, bucket);
		int total = full.size();
		int fromIdx = Math.min(safePage * safeSize, total);
		int toIdx = Math.min(fromIdx + safeSize, total);
		return new ComponentAttributionPage(new ArrayList<>(full.subList(fromIdx, toIdx)), total, safePage, safeSize);
	}

	/**
	 * Org-scope finding-changes with a single-finding {@code keyFilter}, mirroring the org changelog's
	 * path selection (posture-endpoint diff when the org is certified, else the legacy pairwise diff) so the
	 * drill-down page total matches the inline count.
	 */
	private FindingChangesWithAttribution computeOrgFindingChangesFiltered(
			UUID orgUuid, UUID perspectiveUuid, ZonedDateTime dateFrom, ZonedDateTime dateTo,
			String keyFilter, int cap) throws RelizaException {

		List<ComponentData> components = getComponentsForOrganizationChangelog(orgUuid, perspectiveUuid);
		if (components.isEmpty()) {
			return FindingChangesWithAttribution.EMPTY;
		}
		Map<UUID, String> componentNamesMap = new HashMap<>();
		components.forEach(c -> componentNamesMap.put(c.getUuid(), c.getName()));

		if (posturePathEnabled(orgUuid)) {
			Map<UUID, List<ReleaseData>> postureReleasesMap = buildOrgPostureReleasesMap(
				orgUuid, components, componentNamesMap, dateFrom, dateTo);
			if (postureReleasesMap.isEmpty()) {
				return FindingChangesWithAttribution.EMPTY;
			}
			Set<UUID> branchUuids = postureReleasesMap.values().stream()
				.flatMap(List::stream).map(ReleaseData::getBranch).collect(Collectors.toSet());
			Map<UUID, String> branchNameMap = branchUuids.isEmpty() ? new HashMap<>()
				: branchService.getBranchDataList(branchUuids).stream()
					.collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));
			return findingComparisonService.computePostureDiff(
				orgUuid, postureReleasesMap, componentNamesMap, branchNameMap, dateFrom, dateTo,
				findingComparisonService.prefetchPostureReconstruction(postureReleasesMap, dateFrom, dateTo),
				keyFilter, cap);
		}

		// Legacy path: in-window releases per component (mirrors compareMetricsAcrossComponents in the changelog).
		Map<UUID, List<ReleaseData>> componentReleasesMap = new LinkedHashMap<>();
		Set<UUID> allBranchUuids = new HashSet<>();
		for (ComponentData component : components) {
			List<ReleaseData> releases = fetchReleasesForComponentBetweenDates(component.getUuid(), dateFrom, dateTo);
			if (!releases.isEmpty()) {
				componentReleasesMap.put(component.getUuid(), releases);
				releases.forEach(r -> allBranchUuids.add(r.getBranch()));
			}
		}
		if (componentReleasesMap.isEmpty()) {
			return FindingChangesWithAttribution.EMPTY;
		}
		Map<UUID, String> branchNameMap = allBranchUuids.isEmpty() ? new HashMap<>()
			: branchService.getBranchDataList(allBranchUuids).stream()
				.collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));
		return findingComparisonService.compareMetricsAcrossComponents(
			componentReleasesMap, componentNamesMap, branchNameMap, new HashMap<>(), keyFilter, cap);
	}

	/**
	 * Paginated over-time timeline for the drawer behind the capped inline {@code overTimeFindingChanges}.
	 * Resolves the SAME in-window (from-baseline-widened for component/branch) release set the changelog uses,
	 * then delegates to {@link FindingComparisonService#loadFindingChangeTimeline} (optionally filtered to one
	 * {@code findingKey}). Scope: org (only orgUuid) / component (+componentUuid) / branch (+componentUuid+branchUuid).
	 */
	public FindingChangeTimelinePage getFindingChangeTimelinePage(
			UUID orgUuid, UUID componentUuid, UUID branchUuid, UUID perspectiveUuid,
			ZonedDateTime dateFrom, ZonedDateTime dateTo, String findingKey, int page, int pageSize,
			FindingChangeScope scope) throws RelizaException {

		if (branchUuid != null && componentUuid == null) {
			throw new RelizaException("branchUuid requires componentUuid");
		}
		int safeSize = pageSize <= 0 ? DEFAULT_ATTR_PAGE_SIZE : Math.min(pageSize, MAX_ATTR_PAGE_SIZE);
		String keyFilter = (findingKey == null || findingKey.isBlank()) ? null : findingKey;

		// ALL_POSTURE (org-level only): surface EVERY authorized finding-change in the window -- including
		// re-scan-driven changes on releases produced before the window, which the release-anchored path
		// below cannot see (board task #39). v3-grain read; falls through to release-anchored when the org
		// does not read from v3 (isOrgPostureReadAvailable == false). componentUuid/branchUuid are null here
		// (org scope); a scoped ALL_POSTURE request degrades to release-anchored.
		if (scope == FindingChangeScope.ALL_POSTURE && componentUuid == null && branchUuid == null
				&& findingComparisonService.isOrgPostureReadAvailable(orgUuid)) {
			Set<UUID> allowedComponentUuids = getComponentsForOrganizationChangelog(orgUuid, perspectiveUuid)
				.stream().map(ComponentData::getUuid).collect(Collectors.toSet());
			if (allowedComponentUuids.isEmpty()) {
				return FindingChangeTimelinePage.EMPTY;
			}
			// EXCLUDED-participation branches are omitted from the analytics chart; match that here so the
			// posture-over-time list and the chart agree on which branches count. One org-wide branch read.
			Set<UUID> excludedBranchUuids = branchService.listBranchDataOfOrg(orgUuid).stream()
				.filter(b -> b.getFindingAnalyticsParticipation() == BranchData.FindingAnalyticsParticipation.EXCLUDED)
				.map(BranchData::getUuid).collect(Collectors.toSet());
			return findingComparisonService.loadOrgPostureTimeline(
				orgUuid, allowedComponentUuids, excludedBranchUuids, dateFrom, dateTo, keyFilter,
				Math.max(0, page), safeSize);
		}

		Set<UUID> releaseUuids = new HashSet<>();
		if (branchUuid != null) {
			fetchReleasesForBranchWithFromBaseline(branchUuid, dateFrom, dateTo)
				.forEach(r -> releaseUuids.add(r.getUuid()));
		} else if (componentUuid != null) {
			fetchReleasesForComponentWithFromBaseline(componentUuid, dateFrom, dateTo)
				.forEach(r -> releaseUuids.add(r.getUuid()));
		} else {
			for (ComponentData component : getComponentsForOrganizationChangelog(orgUuid, perspectiveUuid)) {
				fetchReleasesForComponentBetweenDates(component.getUuid(), dateFrom, dateTo)
					.forEach(r -> releaseUuids.add(r.getUuid()));
			}
		}
		if (releaseUuids.isEmpty()) {
			return FindingChangeTimelinePage.EMPTY;
		}
		return findingComparisonService.loadFindingChangeTimeline(
			orgUuid, releaseUuids, dateFrom, dateTo, keyFilter, Math.max(0, page), safeSize);
	}

	/** Picks the requested finding (by kind + key) out of the filtered result and returns its bucket list. */
	private List<ComponentAttribution> extractAttributionBucket(
			FindingChangesWithAttribution fc, ChangelogFindingKind kind, String findingKey,
			FindingAttributionBucket bucket) {
		if (fc == null) {
			return List.of();
		}
		switch (kind) {
			case VULNERABILITY -> {
				for (VulnerabilityWithAttribution v : fc.vulnerabilities()) {
					if (findingKey.equals(v.findingKey())) {
						return pickBucket(v.appearedIn(), v.presentIn(), v.resolvedIn(), bucket);
					}
				}
			}
			case VIOLATION -> {
				for (ViolationWithAttribution v : fc.violations()) {
					if (findingKey.equals(v.findingKey())) {
						return pickBucket(v.appearedIn(), v.presentIn(), v.resolvedIn(), bucket);
					}
				}
			}
			case WEAKNESS -> {
				for (WeaknessWithAttribution w : fc.weaknesses()) {
					if (findingKey.equals(w.findingKey())) {
						return pickBucket(w.appearedIn(), w.presentIn(), w.resolvedIn(), bucket);
					}
				}
			}
		}
		return List.of();
	}

	private static List<ComponentAttribution> pickBucket(
			List<ComponentAttribution> appeared, List<ComponentAttribution> present,
			List<ComponentAttribution> resolved, FindingAttributionBucket bucket) {
		return switch (bucket) {
			case APPEARED -> appeared;
			case PRESENT -> present;
			case RESOLVED -> resolved;
		};
	}

	private NoneChangelog computeNoneChangelog(ChangelogContext ctx) throws RelizaException {
		
		if (ctx.releasesByBranch().isEmpty()) {
			throw new RelizaException("No releases provided for changelog computation");
		}
		
		ReleaseInfo firstReleaseInfo = toReleaseInfo(ctx.globalFirst());
		ReleaseInfo lastReleaseInfo = toReleaseInfo(ctx.globalLast());
		List<NoneBranchChanges> branchChangesList = new ArrayList<>();
		
		for (Map.Entry<UUID, List<ReleaseData>> branchEntry : ctx.releasesByBranch().entrySet()) {
			UUID branchId = branchEntry.getKey();
			List<ReleaseData> branchReleases = branchEntry.getValue();
			String branchName = ctx.branchNameMap().get(branchId);
			if (branchName == null) {
				log.warn("Branch name not found for branch UUID {}, skipping", branchId);
				continue;
			}
			
			// Exclude baseline release from changelog - only show releases after baseline
			UUID baselineUuid = ctx.globalFirst().getUuid();
			
			// Filter out baseline release by UUID
			List<ReleaseData> releasesToShow = branchReleases.stream()
				.filter(r -> !r.getUuid().equals(baselineUuid))
				.collect(Collectors.toList());
			
			// Early return if no releases to show after excluding baseline
			if (releasesToShow.isEmpty()) {
				continue;  // Skip this branch - no changes to display
			}
			
			// Prepare commit data for releases to show
			List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(releasesToShow, ctx.org());
			Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(
				sceDataList, ctx.vcsRepoDataList(), ctx.org());
			
			// Pre-fetch all acollections for releases to show (avoids per-pair DB calls)
			Map<UUID, AcollectionData> acollectionByRelease = new HashMap<>();
			for (ReleaseData rd : releasesToShow) {
				AcollectionData ac = acollectionService.getLatestCollectionDataOfRelease(rd.getUuid());
				if (ac != null) {
					acollectionByRelease.put(rd.getUuid(), ac);
				}
			}
			
			// IMPORTANT: Also fetch baseline release acollection for SBOM/finding comparison
			// Although baseline is excluded from display, we need its acollection to compute
			// SBOM and finding changes for the oldest release in releasesToShow (which compares against baseline)
			// See computeSbomChangesFromAcollections and computeFindingChangesForRelease below
			ReleaseData baselineRelease = ctx.globalFirst();
			AcollectionData baselineAc = acollectionService.getLatestCollectionDataOfRelease(baselineRelease.getUuid());
			if (baselineAc != null) {
				acollectionByRelease.put(baselineRelease.getUuid(), baselineAc);
			} else {
				// DEFENSIVE: Log warning if baseline acollection is missing
				// This could cause incorrect SBOM/finding diffs for the first release after baseline
				log.warn("Baseline acollection not found for release {} - SBOM/finding changes may be incomplete", 
					baselineRelease.getUuid());
			}
			
			List<NoneReleaseChanges> releaseChangesList = new ArrayList<>();
			
			for (int i = 0; i < releasesToShow.size(); i++) {
				ReleaseData currentRelease = releasesToShow.get(i);
				// For the oldest release in releasesToShow, compare against baseline
				ReleaseData previousRelease = (i < releasesToShow.size() - 1) 
					? releasesToShow.get(i + 1) 
					: baselineRelease;
				
				// --- Code changes ---
				List<CodeCommit> commits = new ArrayList<>();
				for (UUID commitId : currentRelease.getAllCommits()) {
					CommitRecord commitRecord = commitIdToRecordMap.get(commitId);
					if (commitRecord != null) {
						commits.add(toCodeCommit(commitRecord));
					}
				}
				
				// --- SBOM changes (using pre-fetched acollections) ---
				ReleaseSbomChanges sbomChanges = computeSbomChangesFromAcollections(
					previousRelease, currentRelease, acollectionByRelease);
				
				// --- Finding changes ---
				ReleaseFindingChanges findingChanges = computeFindingChangesForRelease(previousRelease, currentRelease);
				
				releaseChangesList.add(new NoneReleaseChanges(
					currentRelease.getUuid(),
					currentRelease.getDecoratedVersionString(ctx.userTimeZone()),
					currentRelease.getLifecycle(),
					commits,
					sbomChanges,
					findingChanges,
					currentRelease.getCreatedDate()
				));
			}
			
			if (!releaseChangesList.isEmpty()) {
				branchChangesList.add(new NoneBranchChanges(
					branchId,
					branchName,
					ctx.component().getUuid(),
					ctx.component().getName(),
					releaseChangesList,
					ChangeType.CHANGED
				));
			}
		}
		
		return new NoneChangelog(
			ctx.component().getUuid(),
			ctx.component().getName(),
			ctx.org(),
			firstReleaseInfo,
			lastReleaseInfo,
			branchChangesList
		);
	}
	
	private static final ReleaseSbomChanges EMPTY_SBOM_CHANGES = new ReleaseSbomChanges(List.of(), List.of());
	private static final ReleaseFindingChanges EMPTY_FINDING_CHANGES = new ReleaseFindingChanges(
		0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	
	/**
	 * Extracts name from a purl string (e.g. "pkg:npm/lodash@4.17.21" → "lodash").
	 */
	private static String nameFromPurl(String purl) {
		if (purl == null) return "";
		// Strip scheme (pkg:type/) and version (@...)
		String withoutScheme = purl.contains("/") ? purl.substring(purl.lastIndexOf('/') + 1) : purl;
		int atIdx = withoutScheme.indexOf('@');
		return atIdx >= 0 ? withoutScheme.substring(0, atIdx) : withoutScheme;
	}
	
	/**
	 * Converts a DiffComponent to a structured ReleaseSbomArtifact.
	 */
	private static ReleaseSbomArtifact toReleaseSbomArtifact(AcollectionData.DiffComponent dc) {
		return new ReleaseSbomArtifact(dc.purl(), nameFromPurl(dc.purl()), dc.version() != null ? dc.version() : "");
	}
	
	private static List<ReleaseVulnerabilityInfo> toReleaseVulnInfoList(
			List<ReleaseMetricsDto.VulnerabilityDto> vulns) {
		if (vulns == null) return List.of();
		return vulns.stream()
			.map(ReleaseVulnerabilityInfo::from)
			.toList();
	}

	private static List<ReleaseViolationInfo> toReleaseViolationInfoList(
			List<ReleaseMetricsDto.ViolationDto> violations) {
		if (violations == null) return List.of();
		return violations.stream()
			.map(ReleaseViolationInfo::from)
			.toList();
	}

	private static List<ReleaseWeaknessInfo> toReleaseWeaknessInfoList(
			List<ReleaseMetricsDto.WeaknessDto> weaknesses) {
		if (weaknesses == null) return List.of();
		return weaknesses.stream()
			.map(ReleaseWeaknessInfo::from)
			.toList();
	}
	
	/**
	 * Computes SBOM changes for a single release compared to its predecessor
	 * using pre-fetched acollection data (avoids per-pair DB queries).
	 */
	private ReleaseSbomChanges computeSbomChangesFromAcollections(
			ReleaseData previousRelease, ReleaseData currentRelease,
			Map<UUID, AcollectionData> acollectionByRelease) {
		if (previousRelease == null) return EMPTY_SBOM_CHANGES;
		try {
			AcollectionData currAc = acollectionByRelease.get(currentRelease.getUuid());
			if (currAc == null) return EMPTY_SBOM_CHANGES;
			
			AcollectionData.ArtifactChangelog changelog = sbomComparisonService.aggregateChangelogs(List.of(currAc));
			
			List<ReleaseSbomArtifact> added = changelog.added() != null
				? changelog.added().stream().filter(dc -> dc.purl() != null)
					.map(ChangeLogService::toReleaseSbomArtifact).toList()
				: List.of();
			List<ReleaseSbomArtifact> removed = changelog.removed() != null
				? changelog.removed().stream().filter(dc -> dc.purl() != null)
					.map(ChangeLogService::toReleaseSbomArtifact).toList()
				: List.of();
			
			return new ReleaseSbomChanges(added, removed);
		} catch (Exception e) {
			log.error("Error computing SBOM changes for release {}: {}", currentRelease.getUuid(), e.getMessage());
			return EMPTY_SBOM_CHANGES;
		}
	}
	
	/**
	 * Computes finding changes for a single release compared to its predecessor.
	 */
	private ReleaseFindingChanges computeFindingChangesForRelease(
			ReleaseData previousRelease, ReleaseData currentRelease) {
		if (previousRelease == null) return EMPTY_FINDING_CHANGES;
		try {
			ReleaseMetricsDto m1 = previousRelease.getMetrics();
			ReleaseMetricsDto m2 = currentRelease.getMetrics();
			
			FindingChangesRecord fc = (m1 == null || m2 == null)
				? FindingChangesRecord.EMPTY
				: findingComparisonService.compareMetrics(m1, m2);
			
			List<ReleaseVulnerabilityInfo> appearedVulns = toReleaseVulnInfoList(fc.appearedVulnerabilities());
			List<ReleaseVulnerabilityInfo> resolvedVulns = toReleaseVulnInfoList(fc.resolvedVulnerabilities());
			List<ReleaseViolationInfo> appearedViols = toReleaseViolationInfoList(fc.appearedViolations());
			List<ReleaseViolationInfo> resolvedViols = toReleaseViolationInfoList(fc.resolvedViolations());
			List<ReleaseWeaknessInfo> appearedWeaks = toReleaseWeaknessInfoList(fc.appearedWeaknesses());
			List<ReleaseWeaknessInfo> resolvedWeaks = toReleaseWeaknessInfoList(fc.resolvedWeaknesses());
			
			int appearedCount = appearedVulns.size() + appearedViols.size() + appearedWeaks.size();
			int resolvedCount = resolvedVulns.size() + resolvedViols.size() + resolvedWeaks.size();
			
			return new ReleaseFindingChanges(appearedCount, resolvedCount,
				appearedVulns, resolvedVulns, appearedViols, resolvedViols, appearedWeaks, resolvedWeaks);
		} catch (Exception e) {
			log.error("Error computing finding changes for release {}: {}", currentRelease.getUuid(), e.getMessage());
			return EMPTY_FINDING_CHANGES;
		}
	}
	
	/**
	 * Computes changelog for AGGREGATED mode (component-level summary).
	 * Computes code, SBOM, and finding changes at component level with attribution.
	 * Aggregates per-branch metrics and combines them for component-level totals.
	 * 
	 * BASELINE HANDLING IN AGGREGATED MODE:
	 * - CODE changes: Baseline is excluded via computeAggregatedCodeChanges (commits filtered by UUID)
	 * - SBOM changes: Baseline acollection handling is delegated to sbomComparisonService
	 *   The service receives the full releasesByBranch map (including baseline) and releaseAcollectionsMap,
	 *   allowing it to fetch baseline acollections as needed for comparison
	 * - FINDING changes: Baseline handling is delegated to findingComparisonService
	 *   The service receives the full releasesByBranch map and handles baseline comparison internally
	 * 
	 * @param ctx Shared changelog context (component, org, branch names, etc.)
	 * @param releaseAcollectionsMap Pre-fetched acollections keyed by release UUID
	 * @param forkPointCache Shared fork point cache across components
	 * @return AggregatedChangelog with component-level summary
	 */
	private AggregatedChangelog computeAggregatedChangelog(
			ChangelogContext ctx,
			Map<UUID, List<AcollectionData>> releaseAcollectionsMap,
			Map<String, ReleaseData> forkPointCache) throws RelizaException {
		
		if (ctx.releasesByBranch().isEmpty()) {
			throw new RelizaException("No releases provided for changelog computation");
		}
		
		ReleaseInfo firstReleaseInfo = toReleaseInfo(ctx.globalFirst());
		ReleaseInfo lastReleaseInfo = toReleaseInfo(ctx.globalLast());
		
		// 1. Compute CODE changes (aggregated by type)
		// Baseline is explicitly excluded from commit collection via baselineUuid parameter
		List<AggregatedBranchChanges> branchChanges = computeAggregatedCodeChanges(
			ctx.releasesByBranch(), ctx.branchNameMap(), ctx.org(), ctx.vcsRepoDataList(), ctx.component(),
			ctx.globalFirst().getUuid());
		
		// 2. Compute component-level SBOM changes with accurate per-release attribution
		// NOTE: releasesByBranch includes baseline release - service handles baseline comparison internally
		SbomChangesWithAttribution sbomChanges = computeComponentSbomChanges(
			ctx.releasesByBranch(), ctx.branchNameMap(), ctx.component(), releaseAcollectionsMap);
		
		// 3. Compute component-level finding changes with accurate per-release attribution
		// NOTE: releasesByBranch includes baseline release - service handles baseline comparison internally
		FindingChangesWithAttribution findingChanges = computeComponentFindingChanges(
			ctx.releasesByBranch(), ctx.component(), ctx.branchNameMap(), forkPointCache);
		
		return new AggregatedChangelog(
			ctx.component().getUuid(),
			ctx.component().getName(),
			ctx.org(),
			firstReleaseInfo,
			lastReleaseInfo,
			branchChanges,
			sbomChanges,
			findingChanges
		);
	}
	
	/**
	 * Computes code changes for AGGREGATED mode (commits grouped by type).
	 * Accepts pre-grouped releases by branch.
	 * 
	 * @param baselineUuid UUID of the baseline release to exclude from commit collection
	 */
	private List<AggregatedBranchChanges> computeAggregatedCodeChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, String> branchNameMap,
			UUID org,
			List<VcsRepositoryData> vcsRepoDataList,
			ComponentData component,
			UUID baselineUuid) {
		List<AggregatedBranchChanges> branchChangesList = new ArrayList<>();
		
		for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
			UUID branchId = branchEntry.getKey();
			List<ReleaseData> branchReleases = branchEntry.getValue();
			String branchName = branchNameMap.get(branchId);
			if (branchName == null) {
				log.warn("Branch name not found for branch UUID {}, skipping", branchId);
				continue;
			}
			
			// Get first and last release versions and UUIDs (releases are sorted newest first)
			UUID firstReleaseUuid = branchReleases.isEmpty() ? null : branchReleases.get(branchReleases.size() - 1).getUuid();
			String firstVersion = branchReleases.isEmpty() ? null : branchReleases.get(branchReleases.size() - 1).getVersion();
			UUID lastReleaseUuid = branchReleases.isEmpty() ? null : branchReleases.get(0).getUuid();
			String lastVersion = branchReleases.isEmpty() ? null : branchReleases.get(0).getVersion();
			
			// Exclude baseline release from commit collection - only show commits from releases after baseline
			List<ReleaseData> releasesForCommits = branchReleases.stream()
				.filter(r -> !r.getUuid().equals(baselineUuid))
				.collect(Collectors.toList());
			
			// Skip this branch if no releases to show after excluding baseline
			if (releasesForCommits.isEmpty()) {
				continue;
			}
			
			List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(releasesForCommits, org);
			Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(
				sceDataList, vcsRepoDataList, org);
			
			// Collect all commits and group by type
			Map<String, List<CodeCommit>> commitsByTypeMap = new HashMap<>();
			
			for (Map.Entry<UUID, CommitRecord> entry : commitIdToRecordMap.entrySet()) {
				CodeCommit codeCommit = toCodeCommit(entry.getValue());
				commitsByTypeMap.computeIfAbsent(codeCommit.changeType(), k -> new ArrayList<>()).add(codeCommit);
			}
			
			if (!commitsByTypeMap.isEmpty()) {
				List<CommitsByType> commitsByType = commitsByTypeMap.entrySet().stream()
					.map(e -> new CommitsByType(e.getKey(), e.getValue()))
					.sorted((a, b) -> {
						// Sort by CommitType display priority (lower priority = shown first)
						CommitType typeA = getCommitTypeForSorting(a.changeType());
						CommitType typeB = getCommitTypeForSorting(b.changeType());
						return Integer.compare(typeA.getDisplayPriority(), typeB.getDisplayPriority());
					})
					.collect(Collectors.toList());
				branchChangesList.add(new AggregatedBranchChanges(
					branchId,
					branchName,
					component.getUuid(),
					component.getName(),
					firstReleaseUuid,
					firstVersion,
					lastReleaseUuid,
					lastVersion,
					commitsByType,
					ChangeType.CHANGED
				));
			}
		}
		
		return branchChangesList;
	}
	
	/**
	 * Computes component-level SBOM changes with attribution (AGGREGATED mode).
	 * Uses attribution-aware aggregation to track exactly which release each artifact was added/removed in.
	 */
	private SbomChangesWithAttribution computeComponentSbomChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, String> branchNameMap,
			ComponentData component,
			Map<UUID, List<AcollectionData>> releaseAcollectionsMap) {
		
		try {
			// Use attribution-aware aggregation with pre-fetched acollections
			return sbomComparisonService.aggregateComponentChangelogsWithAttribution(
				releasesByBranch, branchNameMap, component, releaseAcollectionsMap);
				
		} catch (Exception e) {
			log.error("Error computing component SBOM changes: {}", e.getMessage());
			return SbomChangesWithAttribution.EMPTY;
		}
	}
	
	/**
	 * Computes component-level finding changes with attribution (AGGREGATED mode).
	 * Uses attribution-aware comparison to track exactly which release each finding appeared/resolved in.
	 */
	private FindingChangesWithAttribution computeComponentFindingChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			ComponentData component,
			Map<UUID, String> branchNameMap,
			Map<String, ReleaseData> forkPointCache
		) {
		
		try {
			return findingComparisonService.compareMetricsWithAttributionAcrossBranches(
				releasesByBranch, component, branchNameMap, forkPointCache);
				
		} catch (Exception e) {
			log.error("Error computing component finding changes: {}", e.getMessage());
			return FindingChangesWithAttribution.EMPTY;
		}
	}
	
	/**
	 * Computes a product changelog.
	 * <p>
	 * AGGREGATED mode is a single baseline-&gt;target pass. NONE mode groups the
	 * changes per product release: each product release in the range becomes a
	 * {@link ProductReleaseChanges} entry holding the child component changes it
	 * introduced (see {@link #computeNoneProductChangelog}).
	 *
	 * @param productReleases Product releases in range (sorted newest first)
	 * @param product Product component data
	 * @param org Organization UUID
	 * @param aggregationType NONE or AGGREGATED
	 * @param userTimeZone User's timezone
	 * @param explicitFirst Baseline product release (release-comparison), or null for date-based
	 * @param explicitLast Target product release (release-comparison), or null for date-based
	 * @return ComponentChangelog (NoneProductChangelog for NONE, AggregatedChangelog for AGGREGATED)
	 */
	private ComponentChangelog computeProductChangelogFromReleases(
			List<ReleaseData> productReleases,
			ComponentData product,
			UUID org,
			AggregationType aggregationType,
			String userTimeZone,
			ReleaseData explicitFirst,
			ReleaseData explicitLast) throws RelizaException {

		if (productReleases.isEmpty()) {
			throw new RelizaException("No product releases provided for changelog computation");
		}

		if (aggregationType == AggregationType.NONE) {
			return computeNoneProductChangelog(productReleases, product, org, userTimeZone,
				explicitFirst, explicitLast);
		}
		// AGGREGATED: single baseline->target pass (behavior unchanged).
		return computeProductChangelogStep(productReleases, product, org, aggregationType,
			userTimeZone, explicitFirst, explicitLast);
	}

	/**
	 * Computes a NONE-mode product changelog grouped per product release.
	 * <p>
	 * Walks the product releases in the range oldest-&gt;newest and, for each
	 * consecutive pair, computes (via {@link #computeProductChangelogStep}) the
	 * child component changes the newer product release introduced. The oldest
	 * product release in the range is the comparison floor and is not emitted as
	 * its own group. For date-based changelogs (explicit endpoints absent) the
	 * product release immediately preceding the window is used as the floor when
	 * available, so the oldest in-window release still gets its own group.
	 * Product releases that introduced no child change are omitted.
	 */
	private NoneProductChangelog computeNoneProductChangelog(
			List<ReleaseData> productReleases,
			ComponentData product,
			UUID org,
			String userTimeZone,
			ReleaseData explicitFirst,
			ReleaseData explicitLast) throws RelizaException {

		// Determine baseline (older) and target (newer) product releases for range metadata.
		ReleaseData productFirst = (explicitFirst != null)
			? explicitFirst : productReleases.get(productReleases.size() - 1);
		ReleaseData productLast = (explicitLast != null)
			? explicitLast : productReleases.get(0);
		if (productFirst.getCreatedDate().isAfter(productLast.getCreatedDate())) {
			ReleaseData tmp = productFirst;
			productFirst = productLast;
			productLast = tmp;
		}

		// Ordered oldest->newest, de-duplicated by UUID.
		LinkedHashMap<UUID, ReleaseData> orderedAsc = new LinkedHashMap<>();
		productReleases.stream()
			.sorted(Comparator.comparing(ReleaseData::getCreatedDate))
			.forEach(rd -> orderedAsc.putIfAbsent(rd.getUuid(), rd));
		List<ReleaseData> walk = new ArrayList<>(orderedAsc.values());

		// Date-based changelog: prepend the product release just before the window as
		// the floor so the oldest in-window release also gets its own group.
		if (explicitFirst == null && !walk.isEmpty()) {
			ReleaseData oldest = walk.get(0);
			UUID predUuid = sharedReleaseService.findPreviousReleasesOfBranchForRelease(
				oldest.getBranch(), oldest.getUuid());
			if (predUuid != null) {
				sharedReleaseService.getReleaseData(predUuid, org)
					.ifPresent(pred -> walk.add(0, pred));
			}
		}

		// Walk consecutive pairs newest-first so groups read newest product release first.
		List<ProductReleaseChanges> groups = new ArrayList<>();
		for (int i = walk.size() - 1; i >= 1; i--) {
			ReleaseData prev = walk.get(i - 1);
			ReleaseData curr = walk.get(i);
			ComponentChangelog stepResult = computeProductChangelogStep(
				List.of(prev, curr), product, org, AggregationType.NONE, userTimeZone, prev, curr);
			if (stepResult instanceof NoneChangelog noneStep && !noneStep.branches().isEmpty()) {
				groups.add(new ProductReleaseChanges(
					curr.getUuid(),
					curr.getDecoratedVersionString(userTimeZone),
					curr.getLifecycle(),
					curr.getCreatedDate(),
					noneStep.branches()));
			}
		}

		return new NoneProductChangelog(
			product.getUuid(), product.getName(), org,
			toReleaseInfo(productFirst), toReleaseInfo(productLast), groups);
	}

	/**
	 * Computes the changes for a single product changelog step (one baseline-&gt;target
	 * product release pair). Extracts child component releases from the product
	 * releases' parentReleases, computes per-child-component changelogs, and for
	 * AGGREGATED mode aggregates SBOM/finding changes to product level.
	 *
	 * @param productReleases Product releases (non-empty; endpoints taken from explicitFirst/Last)
	 * @param product Product component data
	 * @param org Organization UUID
	 * @param aggregationType NONE or AGGREGATED
	 * @param userTimeZone User's timezone
	 * @return ComponentChangelog with product-level data for this step
	 */
	private ComponentChangelog computeProductChangelogStep(
			List<ReleaseData> productReleases,
			ComponentData product,
			UUID org,
			AggregationType aggregationType,
			String userTimeZone,
			ReleaseData explicitFirst,
			ReleaseData explicitLast) throws RelizaException {

		if (productReleases.isEmpty()) {
			throw new RelizaException("No product releases provided for changelog computation");
		}
		
		// Determine baseline (older) and target (newer) releases
		// If explicit releases provided, use them; otherwise use oldest and newest from list
		ReleaseData productFirst = (explicitFirst != null) ? explicitFirst : productReleases.get(productReleases.size() - 1);
		ReleaseData productLast = (explicitLast != null) ? explicitLast : productReleases.get(0);
	
		// If productFirst is newer than productLast, swap them to maintain semantic correctness
		if (productFirst.getCreatedDate().isAfter(productLast.getCreatedDate())) {
			ReleaseData tmp = productFirst;
			productFirst = productLast;  // productFirst becomes the older release (baseline)
			productLast = tmp;           // productLast becomes the newer release (target)
		}
		
		ReleaseInfo firstReleaseInfo = toReleaseInfo(productFirst);
		ReleaseInfo lastReleaseInfo = toReleaseInfo(productLast);
		
		// Extract child component release UUIDs from baseline and target product releases
		Set<UUID> baselineChildReleaseUuids = new HashSet<>();
		Set<UUID> targetChildReleaseUuids = new HashSet<>();
		
		if (productFirst.getParentReleases() != null) {
			for (ParentRelease pr : productFirst.getParentReleases()) {
				if (pr.getRelease() != null) {
					baselineChildReleaseUuids.add(pr.getRelease());
				}
			}
		}
		
		if (productLast.getParentReleases() != null) {
			for (ParentRelease pr : productLast.getParentReleases()) {
				if (pr.getRelease() != null) {
					targetChildReleaseUuids.add(pr.getRelease());
				}
			}
		}
		
		// Collect all unique child release UUIDs
		Set<UUID> childReleaseUuids = new HashSet<>();
		childReleaseUuids.addAll(baselineChildReleaseUuids);
		childReleaseUuids.addAll(targetChildReleaseUuids);
		
		if (childReleaseUuids.isEmpty()) {
			log.warn("Product {} has no child component releases", product.getName());
			if (aggregationType == AggregationType.NONE) {
				return new NoneChangelog(product.getUuid(), product.getName(), org,
					firstReleaseInfo, lastReleaseInfo, List.of());
			} else {
				return new AggregatedChangelog(product.getUuid(), product.getName(), org,
					firstReleaseInfo, lastReleaseInfo, List.of(),
					SbomChangesWithAttribution.EMPTY,
					FindingChangesWithAttribution.EMPTY);
			}
		}
		
		// Fetch child component release data (totals-only: changelog uses branch/component, not finding detail)
		List<ReleaseData> childReleaseDataList = sharedReleaseService.getReleaseDataListLight(childReleaseUuids, org);
		List<UUID> branchList = childReleaseDataList.stream()
			.map(ReleaseData::getBranch).distinct().toList();
		Map<UUID, String> branchNameMap = branchService.getBranchDataList(branchList)
			.stream().collect(Collectors.toMap(BranchData::getUuid, BranchData::getName, (a, b) -> a));

		// Build maps of baseline and target child releases by component UUID
		Map<UUID, UUID> baselineChildByComponent = new HashMap<>();
		Map<UUID, UUID> targetChildByComponent = new HashMap<>();
		
		for (ReleaseData rd : childReleaseDataList) {
			if (baselineChildReleaseUuids.contains(rd.getUuid())) {
				baselineChildByComponent.put(rd.getComponent(), rd.getUuid());
			}
			if (targetChildReleaseUuids.contains(rd.getUuid())) {
				targetChildByComponent.put(rd.getComponent(), rd.getUuid());
			}
		}
		
		// Identify changed components (baseline != target)
		Set<UUID> changedComponents = new HashSet<>();
		for (UUID componentUuid : targetChildByComponent.keySet()) {
			UUID baselineRelease = baselineChildByComponent.get(componentUuid);
			UUID targetRelease = targetChildByComponent.get(componentUuid);
			if (baselineRelease == null || !baselineRelease.equals(targetRelease)) {
				changedComponents.add(componentUuid);
			}
		}
		// Also include components only in baseline (removed from product)
		for (UUID componentUuid : baselineChildByComponent.keySet()) {
			if (!targetChildByComponent.containsKey(componentUuid)) {
				changedComponents.add(componentUuid);
			}
		}
		
		// Group child releases by component, filtering to only changed components
		Map<UUID, List<ReleaseData>> childReleasesByComponent = childReleaseDataList.stream()
			.filter(rd -> changedComponents.contains(rd.getComponent()))
			.collect(Collectors.groupingBy(ReleaseData::getComponent));
		
		// Build component data map
		Map<UUID, ComponentData> componentDataMap = childReleasesByComponent.keySet().stream()
			.map(uuid -> getComponentService.getComponentData(uuid))
			.filter(Optional::isPresent)
			.map(Optional::get)
			.collect(Collectors.toMap(ComponentData::getUuid, Function.identity(), (a, b) -> a));
		
		// Compute per-child-component changelogs and collect data for aggregation
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		Map<UUID, List<AcollectionData>> componentAcollectionsMap = new HashMap<>();
		Map<UUID, List<ReleaseData>> componentReleasesMap = new HashMap<>();
		Map<UUID, String> componentNamesMap = new HashMap<>();
		
		// Collect all branch changes from child components
		List<NoneBranchChanges> allNoneBranchChanges = new ArrayList<>();
		List<AggregatedBranchChanges> allAggregatedBranchChanges = new ArrayList<>();
		
		for (Map.Entry<UUID, List<ReleaseData>> entry : childReleasesByComponent.entrySet()) {
			UUID componentUuid = entry.getKey();
			List<ReleaseData> componentReleases = entry.getValue();
			ComponentData componentData = componentDataMap.get(componentUuid);
			
			if (componentData == null || componentReleases.isEmpty()) continue;
			
			try {
				// Determine baseline and target releases for this component
				UUID baselineReleaseUuid = baselineChildByComponent.get(componentUuid);
				UUID targetReleaseUuid = targetChildByComponent.get(componentUuid);
				
				// IMPORTANT: Handle component addition/removal cases
				// - baselineReleaseUuid == null: Component was added to product (new in target)
				// - targetReleaseUuid == null: Component was removed from product (only in baseline)
				
				if (baselineReleaseUuid == null && targetReleaseUuid != null) {
					// Component was newly added to the product in the target release
					ReleaseData targetRelease = componentReleases.stream()
						.filter(rd -> rd.getUuid().equals(targetReleaseUuid))
						.findFirst().orElse(null);
					if (targetRelease != null) {
						UUID branchId = targetRelease.getBranch();
						String branchName = branchNameMap.getOrDefault(branchId, "");
						if (aggregationType == AggregationType.NONE) {
							allNoneBranchChanges.add(new NoneBranchChanges(
								branchId, branchName, componentUuid, componentData.getName(),
								List.of(new NoneReleaseChanges(targetReleaseUuid, targetRelease.getDecoratedVersionString(userTimeZone), targetRelease.getLifecycle(), List.of(), EMPTY_SBOM_CHANGES, EMPTY_FINDING_CHANGES, targetRelease.getCreatedDate())),
								ChangeType.ADDED));
						} else {
							allAggregatedBranchChanges.add(new AggregatedBranchChanges(
								branchId, branchName, componentUuid, componentData.getName(),
								null, null, targetReleaseUuid, targetRelease.getVersion(), List.of(), ChangeType.ADDED));
							List<ReleaseData> addedReleaseList = List.of(targetRelease);
							Map<UUID, List<AcollectionData>> addedAcollections = prefetchAcollections(addedReleaseList);
							List<AcollectionData> latestAcollections = pickLatestAcollections(addedAcollections, addedReleaseList);
							if (!latestAcollections.isEmpty()) {
								componentAcollectionsMap.put(componentUuid, latestAcollections);
							}
							componentReleasesMap.put(componentUuid, addedReleaseList);
							componentNamesMap.put(componentUuid, componentData.getName());
						}
					} else {
						log.error("Could not find release data for added component {} (releaseUuid={}) - data inconsistency", componentData.getName(), targetReleaseUuid);
					}
					continue;
				}
				
				if (targetReleaseUuid == null && baselineReleaseUuid != null) {
					// Component was removed from the product in the target release
					ReleaseData baselineRelease = componentReleases.stream()
						.filter(rd -> rd.getUuid().equals(baselineReleaseUuid))
						.findFirst().orElse(null);
					if (baselineRelease != null) {
						UUID branchId = baselineRelease.getBranch();
						String branchName = branchNameMap.getOrDefault(branchId, "");
						if (aggregationType == AggregationType.NONE) {
							allNoneBranchChanges.add(new NoneBranchChanges(
								branchId, branchName, componentUuid, componentData.getName(),
								List.of(new NoneReleaseChanges(baselineReleaseUuid, baselineRelease.getDecoratedVersionString(userTimeZone), baselineRelease.getLifecycle(), List.of(), EMPTY_SBOM_CHANGES, EMPTY_FINDING_CHANGES, baselineRelease.getCreatedDate())),
								ChangeType.REMOVED));
						} else {
							allAggregatedBranchChanges.add(new AggregatedBranchChanges(
								branchId, branchName, componentUuid, componentData.getName(),
								baselineReleaseUuid, baselineRelease.getVersion(), null, null, List.of(), ChangeType.REMOVED));
							// NOTE: SBOM/finding aggregation intentionally skipped for removed components.
							// The baseline release's acollection reflects internal component history,
							// not the product-level removal of all its artifacts.
						}
					} else {
						log.error("Could not find release data for removed component {} (releaseUuid={}) - data inconsistency", componentData.getName(), baselineReleaseUuid);
					}
					continue;
				}
				
				// DEFENSIVE: If both are null, skip (should not happen due to filtering)
				if (baselineReleaseUuid == null || targetReleaseUuid == null) {
					log.warn("Missing baseline AND target release for component {} - skipping", componentUuid);
					continue;
				}
				
				// DEFENSIVE: Skip if baseline == target (no changes to show)
				// NOTE: This should NEVER trigger due to filtering at lines 1052-1066 which excludes
				// unchanged components. If this log appears, it indicates a bug in the filtering logic.
				if (baselineReleaseUuid.equals(targetReleaseUuid)) {
					log.warn("UNEXPECTED: Component {} has baseline == target despite filtering - possible bug", componentUuid);
					continue;
				}
				
				// Fetch releases between baseline and target
				// Note: listAllReleasesBetweenReleases excludes the baseline (fromDateTime is exclusive)
				// so we need to ensure both baseline and target are included
				List<ReleaseData> releasesInRange = sharedReleaseService.listAllReleasesBetweenReleases(
					baselineReleaseUuid, targetReleaseUuid);
				
				// Fetch baseline and target releases explicitly
				ReleaseData compFirst = sharedReleaseService.getReleaseData(baselineReleaseUuid, org)
					.orElse(null);
				ReleaseData compLast = sharedReleaseService.getReleaseData(targetReleaseUuid, org)
					.orElse(null);
				
				if (compFirst == null || compLast == null) {
					log.warn("Could not fetch baseline or target release for component {}", componentUuid);
					continue;
				}
				
				// Ensure both baseline and target are included in the range
				// (listAllReleasesBetweenReleases may exclude one or both due to exclusive date boundaries)
				// Use single-pass Set lookup for efficiency (O(n) once instead of O(n) twice)
				Set<UUID> existingUuids = releasesInRange.stream()
					.map(ReleaseData::getUuid)
					.collect(Collectors.toSet());
				
				// Note: compFirst and compLast are guaranteed non-null by check at line 1213
				if (!existingUuids.contains(baselineReleaseUuid)) {
					releasesInRange.add(compFirst);
				}
				if (!existingUuids.contains(targetReleaseUuid)) {
					releasesInRange.add(compLast);
				}
			
				// This ensures correct ordering for groupReleasesByBranch and changelog computation
				releasesInRange.sort(NEWEST_FIRST);
				
				// Group by branch for the child component
				LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch = groupReleasesByBranch(releasesInRange);
				
				ChangelogContext ctx = new ChangelogContext(
					releasesByBranch, componentData, org, compFirst, compLast,
					branchNameMap, userTimeZone, vcsRepoDataList);
				
				// Pre-fetch acollections once for AGGREGATED mode
				Map<UUID, List<AcollectionData>> releaseAcollectionsMap = (aggregationType == AggregationType.AGGREGATED)
					? prefetchAcollections(releasesInRange) : new HashMap<>();
				
				ComponentChangelog childChangelog = (aggregationType == AggregationType.NONE)
					? computeNoneChangelog(ctx)
					: computeAggregatedChangelog(ctx, releaseAcollectionsMap, new HashMap<>());
				
				
				// Collect branch changes from child changelogs for product-level display
				if (childChangelog instanceof NoneChangelog noneChild) {
					allNoneBranchChanges.addAll(noneChild.branches());
				} else if (childChangelog instanceof AggregatedChangelog aggChild) {
					allAggregatedBranchChanges.addAll(aggChild.branches());
				}
				
				// Collect data for product-level SBOM/finding aggregation (AGGREGATED mode)
				if (aggregationType == AggregationType.AGGREGATED) {
					List<AcollectionData> latestAcollections = pickLatestAcollections(releaseAcollectionsMap, releasesInRange);
					if (!latestAcollections.isEmpty()) {
						componentAcollectionsMap.put(componentUuid, latestAcollections);
					}
					componentReleasesMap.put(componentUuid, releasesInRange);
					componentNamesMap.put(componentUuid, componentData.getName());
				}
				
			} catch (Exception e) {
				log.error("Error computing changelog for child component {} in product {}: {}",
					componentData.getName(), product.getName(), e.getMessage());
			}
		}
		
		if (aggregationType == AggregationType.NONE) {
			return new NoneChangelog(
				product.getUuid(), product.getName(), org,
				firstReleaseInfo, lastReleaseInfo,
				allNoneBranchChanges);
		} else {
			// Aggregate SBOM changes across child components
			SbomChangesWithAttribution productSbomChanges = sbomComparisonService.aggregateChangelogsWithAttribution(
				componentAcollectionsMap, componentReleasesMap, branchNameMap, componentNamesMap);
			
			// Aggregate finding changes across child components
			FindingChangesWithAttribution productFindingChanges = findingComparisonService.compareMetricsAcrossComponents(
				componentReleasesMap, componentNamesMap, branchNameMap, new HashMap<>());
			
			return new AggregatedChangelog(
				product.getUuid(), product.getName(), org,
				firstReleaseInfo, lastReleaseInfo,
				allAggregatedBranchChanges, productSbomChanges, productFindingChanges);
		}
	}
	
	/**
	 * Helper method to get components for organization changelog.
	 * Filters by perspective if provided, otherwise returns all active components.
	 */
	private List<ComponentData> getComponentsForOrganizationChangelog(UUID orgUuid, UUID perspectiveUuid) {
		List<ComponentData> components;
		
		if (perspectiveUuid == null) {
			// Get all COMPONENT type components in org
			components = componentService.listComponentDataByOrganization(orgUuid, ComponentType.COMPONENT);
		} else {
			// Get components filtered by perspective
			components = componentService.listComponentDataByOrganizationAndPerspective(orgUuid, perspectiveUuid, ComponentType.COMPONENT);
		}
		
		return components;
	}
}
