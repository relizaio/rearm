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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommitMatcherUtil;
import io.reliza.dto.ChangelogRecords.AggregatedBranchChanges;
import io.reliza.dto.ChangelogRecords.AggregatedChangelog;
import io.reliza.dto.ChangelogRecords.CodeCommit;
import io.reliza.dto.ChangelogRecords.CommitRecord;
import io.reliza.dto.ChangelogRecords.CommitsByType;
import io.reliza.dto.ChangelogRecords.ComponentChangelog;
import io.reliza.dto.ChangelogRecords.NoneBranchChanges;
import io.reliza.dto.ChangelogRecords.NoneChangelog;
import io.reliza.dto.ChangelogRecords.NoneOrganizationChangelog;
import io.reliza.dto.ChangelogRecords.NoneReleaseChanges;
import io.reliza.dto.ChangelogRecords.OrganizationChangelog;
import io.reliza.dto.ChangelogRecords.AggregatedOrganizationChangelog;
import io.reliza.dto.ChangelogRecords.ReleaseInfo;
import io.reliza.dto.ChangelogRecords.ReleaseFindingChanges;
import io.reliza.dto.ChangelogRecords.ReleaseSbomArtifact;
import io.reliza.dto.ChangelogRecords.ReleaseSbomChanges;
import io.reliza.dto.ChangelogRecords.ReleaseViolationInfo;
import io.reliza.dto.ChangelogRecords.ReleaseVulnerabilityInfo;
import io.reliza.dto.ChangelogRecords.ReleaseWeaknessInfo;
import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.SbomChangesWithAttribution;
import io.reliza.model.changelog.CommitType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AcollectionData;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
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
import io.reliza.model.changelog.entry.AggregationType;
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
		
		if (releasesByBranch.isEmpty()) {
			return null;
		}
		
		// For PRODUCT type, flatten releases and delegate to product-specific method
		if (component.getType() == ComponentType.PRODUCT) {
			List<ReleaseData> allProductReleases = releasesByBranch.values().stream()
				.flatMap(List::stream)
				.sorted(NEWEST_FIRST)
				.collect(Collectors.toList());
			return computeProductChangelogFromReleases(
				allProductReleases, component, org, aggregationType, userTimeZone,
				null, null);
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
		
		if (aggregationType == AggregationType.NONE) {
			return computeNoneChangelog(ctx);
		}
		
		List<ReleaseData> allReleases = releasesByBranch.values().stream().flatMap(List::stream).toList();
		Map<UUID, List<AcollectionData>> releaseAcollectionsMap = prefetchAcollections(allReleases);
		return computeAggregatedChangelog(ctx, releaseAcollectionsMap, new HashMap<>());
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
		
		if (componentChangelogs.isEmpty()) {
			throw new RelizaException("No changelog data found for organization " + orgUuid + 
				" in date range " + dateFrom + " to " + dateTo);
		}
		
		// Return appropriate type based on aggregation mode
		if (aggregationType == AggregationType.NONE) {
			return new NoneOrganizationChangelog(orgUuid, dateFrom, dateTo, componentChangelogs);
		} else {
			// Compute org-wide SBOM and Finding aggregation
			SbomChangesWithAttribution orgSbomChanges = sbomComparisonService.aggregateChangelogsWithAttribution(
				componentAcollectionsMap, componentReleasesMap, branchNameMap, componentNamesMap);
			FindingChangesWithAttribution orgFindingChanges = findingComparisonService.compareMetricsAcrossComponents(
				componentReleasesMap, componentNamesMap, branchNameMap, forkPointCache);
			return new AggregatedOrganizationChangelog(
				orgUuid, dateFrom, dateTo, componentChangelogs, orgSbomChanges, orgFindingChanges);
		}
	}
	
	/**
	 * Computes changelog for NONE mode (per-release breakdown).
	 * Computes code, SBOM, and finding changes separately for each release.
	 * 
	 * @param ctx Shared changelog context (component, org, branch names, releases, etc.)
	 * @return NoneChangelog with per-release breakdown
	 */
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
					findingChanges
				));
			}
			
			if (!releaseChangesList.isEmpty()) {
				branchChangesList.add(new NoneBranchChanges(
					branchId,
					branchName,
					ctx.component().getUuid(),
					ctx.component().getName(),
					releaseChangesList
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
			.map(v -> new ReleaseVulnerabilityInfo(v.vulnId(), v.purl(),
				v.severity() != null ? v.severity().name() : null, v.aliases()))
			.toList();
	}
	
	private static List<ReleaseViolationInfo> toReleaseViolationInfoList(
			List<ReleaseMetricsDto.ViolationDto> violations) {
		if (violations == null) return List.of();
		return violations.stream()
			.map(v -> new ReleaseViolationInfo(
				v.type() != null ? v.type().name() : "UNKNOWN", v.purl()))
			.toList();
	}
	
	private static List<ReleaseWeaknessInfo> toReleaseWeaknessInfoList(
			List<ReleaseMetricsDto.WeaknessDto> weaknesses) {
		if (weaknesses == null) return List.of();
		return weaknesses.stream()
			.map(w -> new ReleaseWeaknessInfo(w.cweId(),
				w.severity() != null ? w.severity().name() : null,
				w.ruleId(), w.location()))
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
					commitsByType
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
	 * Computes product changelog using the sealed interface pattern.
	 * Extracts child component releases from product releases' parentReleases,
	 * computes per-child-component changelogs, and aggregates SBOM/finding changes to product level.
	 * Similar to organization changelog pattern but scoped to a single product.
	 *
	 * @param productReleases Product releases (sorted newest first)
	 * @param product Product component data
	 * @param org Organization UUID
	 * @param aggregationType NONE or AGGREGATED
	 * @param userTimeZone User's timezone
	 * @return ComponentChangelog with product-level aggregated data
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
		
		// Fetch child component release data
		List<ReleaseData> childReleaseDataList = sharedReleaseService.getReleaseDataList(childReleaseUuids, org);
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
		List<ComponentChangelog> childChangelogs = new ArrayList<>();
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
				
				// CRITICAL: Skip component addition/removal cases for now
				// TODO: Implement proper handling for added/removed components
				// - For added components: Could show "Component added" with target release details
				// - For removed components: Could show "Component removed" with baseline release details
				// Current approach of setting baseline==target causes issues with listAllReleasesBetweenReleases
				if (baselineReleaseUuid == null && targetReleaseUuid != null) {
					log.info("Component {} was added to product in target release - skipping changelog (not yet implemented)", 
						componentData.getName());
					continue;
				}
				
				if (targetReleaseUuid == null && baselineReleaseUuid != null) {
					log.info("Component {} was removed from product in target release - skipping changelog (not yet implemented)", 
						componentData.getName());
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
				
				// Note: compFirst and compLast are guaranteed non-null by check at line 1171
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
				
				childChangelogs.add(childChangelog);
				
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
