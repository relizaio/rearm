/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static io.reliza.common.Utils.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommitMatcherUtil;
import io.reliza.common.Utils;
import io.reliza.dto.ArtifactWithAttribution;
import io.reliza.dto.ComponentAttribution;
import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.SbomChangesWithAttribution;
import io.reliza.dto.ViolationWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.dto.WeaknessWithAttribution;
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
import io.reliza.model.changelog.CommitType;
import io.reliza.model.changelog.ConventionalCommit;
import io.reliza.model.changelog.entry.AggregationType;
import io.reliza.model.dto.ComponentJsonDto;
import io.reliza.model.dto.ComponentJsonDto.ComponentJsonDtoBuilder;
import io.reliza.model.dto.FindingChangesDto;
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
	private AnalyticsMetricsService analyticsMetricsService;
	
	public static record TicketRecord(String ticketSubject, List<ChangeRecord> changes) {}
	
	public static record ReleaseRecord(
		UUID uuid, 
		String version, 
		ReleaseLifecycle lifecycle, 
		List<ChangeRecord> changes,
		AcollectionData.ArtifactChangelog sbomChanges,
		FindingChangesDto.FindingChangesRecord findingChanges
	) {}
	
	public static record ChangeRecord(String changeType, List<CommitMessageRecord> commitRecords) {}
	
	public static record CommitMessageRecord(String linkifiedText, String rawText, String commitAuthor, String commitEmail) {}
	
	public static record CommitRecord(String commitUri, String commitId, String commitMessage, String commitAuthor, String commitEmail) {
		public String commitMessage() {
			return this.commitMessage;
		}
	}
	/**
	 * Sealed interface for component changelogs.
	 * Permits only NONE and AGGREGATED mode implementations.
	 */
	public sealed interface ComponentChangelog permits NoneChangelog, AggregatedChangelog {
		UUID componentUuid();
		String componentName();
		UUID orgUuid();
		ReleaseInfo firstRelease();
		ReleaseInfo lastRelease();
	}
	
	/**
	 * Changelog for NONE mode (per-release breakdown).
	 */
	public record NoneChangelog(
		UUID componentUuid,
		String componentName,
		UUID orgUuid,
		ReleaseInfo firstRelease,
		ReleaseInfo lastRelease,
		List<NoneBranchChanges> branches,
		List<ReleaseSbomChanges> sbomChanges,
		List<ReleaseFindingChanges> findingChanges
	) implements ComponentChangelog {}
	
	/**
	 * Changelog for AGGREGATED mode (component-level summary).
	 */
	public record AggregatedChangelog(
		UUID componentUuid,
		String componentName,
		UUID orgUuid,
		ReleaseInfo firstRelease,
		ReleaseInfo lastRelease,
		List<AggregatedBranchChanges> branches,
		SbomChangesWithAttribution sbomChanges,
		FindingChangesWithAttribution findingChanges
	) implements ComponentChangelog {}
	
	/**
	 * Sealed interface for organization changelogs.
	 * Permits only NONE and AGGREGATED mode implementations.
	 */
	public sealed interface OrganizationChangelog permits NoneOrganizationChangelog, AggregatedOrganizationChangelog {
		UUID orgUuid();
		ZonedDateTime dateFrom();
		ZonedDateTime dateTo();
		List<ComponentChangelog> components();
	}
	
	/**
	 * Organization changelog for NONE mode (per-component, per-release breakdown).
	 * Each component contains its own NoneChangelog.
	 */
	public record NoneOrganizationChangelog(
		UUID orgUuid,
		ZonedDateTime dateFrom,
		ZonedDateTime dateTo,
		List<ComponentChangelog> components
	) implements OrganizationChangelog {}
	
	/**
	 * Organization changelog for AGGREGATED mode (organization-wide summary).
	 * Each component contains its own AggregatedChangelog, plus org-wide SBOM/Finding aggregation.
	 */
	public record AggregatedOrganizationChangelog(
		UUID orgUuid,
		ZonedDateTime dateFrom,
		ZonedDateTime dateTo,
		List<ComponentChangelog> components,
		SbomChangesWithAttribution sbomChanges,
		FindingChangesWithAttribution findingChanges
	) implements OrganizationChangelog {}
	
	/**
	 * Release metadata.
	 */
	public record ReleaseInfo(
		UUID uuid,
		String version,
		ReleaseLifecycle lifecycle
	) {}
	
	/**
	 * Branch changes for NONE mode (per-release breakdown).
	 */
	public record NoneBranchChanges(
		UUID branchUuid,
		String branchName,
		List<ReleaseCodeChanges> releases
	) {}
	
	/**
	 * Branch changes for AGGREGATED mode (commits grouped by type).
	 */
	public record AggregatedBranchChanges(
		UUID branchUuid,
		String branchName,
		Map<String, List<CodeCommit>> commitsByType
	) {}
	
	/**
	 * Commits grouped by change type (for GraphQL list representation).
	 * Used to convert Map<String, List<CodeCommit>> to a list structure.
	 */
	public record CommitsByType(
		String changeType,
		List<CodeCommit> commits
	) {}
	
	/**
	 * Code changes for a single release (NONE mode).
	 */
	public record ReleaseCodeChanges(
		UUID releaseUuid,
		String version,
		ReleaseLifecycle lifecycle,
		List<CodeCommit> commits
	) {}
	
	/**
	 * Individual code commit.
	 */
	public record CodeCommit(
		String commitId,
		String commitUri,
		String message,
		String author,
		String email,
		String changeType
	) {}
	
	/**
	 * SBOM changes for a single release (NONE mode).
	 */
	public record ReleaseSbomChanges(
		UUID releaseUuid,
		List<String> addedArtifacts,
		List<String> removedArtifacts
	) {}
	
	/**
	 * Finding changes for a single release (NONE mode).
	 */
	public record ReleaseFindingChanges(
		UUID releaseUuid,
		int appearedCount,
		int resolvedCount,
		List<String> appearedVulnerabilities,
		List<String> resolvedVulnerabilities
	) {}
	
	// ========== HELPER RECORDS ==========
	
	/**
	 * Helper record to hold a pair of metrics for comparison
	 */
	public record MetricsPair(ReleaseMetricsDto metrics1, ReleaseMetricsDto metrics2) {}
	
	// ========== HELPER METHODS ==========
	
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
		grouped.values().forEach(branchReleases -> 
			branchReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()))
		);
		
		return grouped;
	}
	
	/**
	 * Gets a map of branch UUID to BranchData for a component.
	 */
	private Map<UUID, BranchData> getBranchDataMap(UUID componentUuid) {
		return branchService.listBranchDataOfComponent(componentUuid, null)
			.stream()
			.collect(Collectors.toMap(BranchData::getUuid, Function.identity()));
	}
	
	/**
	 * Helper record for old deprecated methods.
	 * @deprecated Use new sealed interface pattern instead
	 */
	@Deprecated
	private record ReleaseChanges(
		AcollectionData.ArtifactChangelog sbomChanges,
		FindingChangesDto.FindingChangesRecord findingChanges
	) {
		public static ReleaseChanges empty() {
			return new ReleaseChanges(null, null);
		}
	}
	
	/**
	 * Helper record for old deprecated methods.
	 * @deprecated Use new sealed interface pattern instead
	 */
	@Deprecated
	private record ComponentCodeChanges(List<BranchCodeChanges> branches) {}
	
	/**
	 * Helper record for old deprecated methods.
	 * @deprecated Use new sealed interface pattern instead
	 */
	@Deprecated
	private record BranchCodeChanges(UUID branchUuid, String branchName, Object changes) {}
	
	/**
	 * Helper record for old deprecated methods.
	 * @deprecated Use new sealed interface pattern instead
	 */
	@Deprecated
	private record ReleaseRangeMetadata(ReleaseData firstRelease, ReleaseData lastRelease) {}

	public ConventionalCommit resolveConventionalCommit(String commit) {
		ConventionalCommit conventionalCommit = null;
		if(StringUtils.isNotEmpty(commit)){
			if(!StringUtils.isEmpty(commit)){
				String[] commitMessageArray = commit.split(CommitMatcherUtil.LINE_SEPARATOR);
				
				// var matcher = COMMIT_MESSAGE_REGEX.pattern();
				if (commitMessageArray.length >= 1
						// && first(commitMessageArray).trim().matches(matcher)) {
						){

					if (commitMessageArray.length == 1) {
						conventionalCommit = new ConventionalCommit(new CommitMessage(first(commitMessageArray)));
					} else if (commitMessageArray.length == 2) {
						conventionalCommit = new ConventionalCommit(new CommitMessage(first(commitMessageArray)),
								new CommitFooter(last(commitMessageArray)));
					} else {
						conventionalCommit = new ConventionalCommit(new CommitMessage(first(commitMessageArray)),
								new CommitBody(drop(1, 1, commitMessageArray)),
								new CommitFooter(last(commitMessageArray)));
					}
				}
			}
		}

		return conventionalCommit;
	}

	public Map<CommitType, Set<ConventionalCommit>> groupByCommitType(List<ConventionalCommit> conventionalCommits) {
		Map<CommitType, Set<ConventionalCommit>> groupByType = conventionalCommits.stream()
				.collect(Collectors.groupingBy(ConventionalCommit::getType, Collectors.toSet()));

		TreeMap<CommitType, Set<ConventionalCommit>> sortedMap = new TreeMap<>(
				Comparator.comparingInt(CommitType::getDisplayPriority));
		sortedMap.putAll(groupByType);

		return sortedMap;
	}
	
	// ========== Extraction Helper Methods ==========
	
	/**
	 * Extracts metrics from two releases for comparison.
	 * Centralizes release data extraction logic.
	 * 
	 * @param uuid1 First release UUID (baseline)
	 * @param uuid2 Second release UUID (comparison target)
	 * @param orgUuid Organization UUID
	 * @return MetricsPair containing metrics from both releases, or null if releases or metrics not found
	 * @throws RelizaException if release data cannot be retrieved
	 */
	private MetricsPair extractMetricsForReleases(UUID uuid1, UUID uuid2, UUID orgUuid) throws RelizaException {
		Optional<ReleaseData> r1 = sharedReleaseService.getReleaseData(uuid1, orgUuid);
		Optional<ReleaseData> r2 = sharedReleaseService.getReleaseData(uuid2, orgUuid);
		
		if (r1.isEmpty() || r2.isEmpty()) {
			return null;
		}
		
		ReleaseMetricsDto metrics1 = r1.get().getMetrics();
		ReleaseMetricsDto metrics2 = r2.get().getMetrics();
		
		if (metrics1 == null || metrics2 == null) {
			return null;
		}
		
		return new MetricsPair(metrics1, metrics2);
	}
	
	
	
	/**
	 * Pre-computes SBOM and Finding changes for each release in a list.
	 * Each release is compared to its predecessor (previous release in the list).
	 * 
	 * @param releases List of releases (sorted newest first)
	 * @param orgUuid Organization UUID
	 * @return Map of release UUID to pre-computed changes (SBOM and Finding deltas)
	 */
	private Map<UUID, ReleaseChanges> computePerReleaseChanges(
			List<ReleaseData> releases, 
			UUID orgUuid) {
		
		Map<UUID, ReleaseChanges> changesMap = new HashMap<>();
		
		if (releases == null || releases.isEmpty()) {
			return changesMap;
		}
		
		// Compare each release to the previous one (releases are sorted newest first)
		for (int i = 0; i < releases.size(); i++) {
			ReleaseData currentRelease = releases.get(i);
			ReleaseData previousRelease = (i < releases.size() - 1) ? releases.get(i + 1) : null;
			
			if (previousRelease == null) {
				// First/oldest release - no comparison
				changesMap.put(currentRelease.getUuid(), ReleaseChanges.empty());
				continue;
			}
			
			try {
				// Get acollections for SBOM comparison
				List<AcollectionData> acollections = acollectionService.getAcollectionsForReleaseRange(
						previousRelease.getUuid(), currentRelease.getUuid());
				AcollectionData.ArtifactChangelog sbomChanges = sbomComparisonService.aggregateChangelogs(acollections);
				
				// Get metrics for finding comparison
				MetricsPair metrics = extractMetricsForReleases(previousRelease.getUuid(), currentRelease.getUuid(), orgUuid);
				
				FindingChangesDto.FindingChangesRecord findingChanges = (metrics == null)
					? findingComparisonService.emptyFindingChanges()
					: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
				
				changesMap.put(currentRelease.getUuid(), 
					new ReleaseChanges(sbomChanges, findingChanges));
				
			} catch (Exception e) {
				log.error("Error computing changes for release {}: {}", currentRelease.getUuid(), e.getMessage());
				changesMap.put(currentRelease.getUuid(), ReleaseChanges.empty());
			}
		}
		
		return changesMap;
	}
	
	// ========== NEW: Public API using Sealed Interface ==========
	
	/**
	 * Gets component changelog between two releases using the new sealed interface pattern.
	 * Returns either NoneChangelog (per-release breakdown) or AggregatedChangelog (component-level summary)
	 * based on the aggregation type.
	 * 
	 * @param uuid1 First release UUID (baseline)
	 * @param uuid2 Second release UUID (comparison target)
	 * @param org Organization UUID
	 * @param aggregationType NONE (per-release) or AGGREGATED (component-level)
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
		
		if (releases.isEmpty()) {
			throw new RelizaException("No releases found between " + uuid1 + " and " + uuid2);
		}
		
		ComponentData component = getComponentService.getComponentData(releases.get(0).getComponent())
			.orElseThrow(() -> new RelizaException("Component not found: " + releases.get(0).getComponent()));
		
		// Convert flat list to grouped structure for new methods
		LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch = groupReleasesByBranch(releases);
		Map<UUID, BranchData> branchDataMap = getBranchDataMap(component.getUuid());
		ReleaseData globalFirst = releases.get(releases.size() - 1);
		ReleaseData globalLast = releases.get(0);
		
		return (aggregationType == AggregationType.NONE)
			? computeNoneChangelog(releasesByBranch, branchDataMap, component, org, globalFirst, globalLast, userTimeZone)
			: computeAggregatedChangelog(releasesByBranch, branchDataMap, component, org, globalFirst, globalLast, userTimeZone);
	}
	
	/**
	 * Gets component changelog for a date range using the new sealed interface pattern.
	 * Returns either NoneChangelog (per-release breakdown) or AggregatedChangelog (component-level summary)
	 * based on the aggregation type.
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
		Map<UUID, BranchData> branchDataMap = new LinkedHashMap<>();
		LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch = new LinkedHashMap<>();
		
		if (branchUuid != null) {
			// Get releases for specific branch
			List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
				branchUuid, dateFrom, dateTo, ReleaseLifecycle.DRAFT);
			if (branchReleases != null && !branchReleases.isEmpty()) {
				// Sort within branch by creation date (newest first)
				branchReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
				releasesByBranch.put(branchUuid, branchReleases);
				branchService.getBranchData(branchUuid).ifPresent(bd -> branchDataMap.put(branchUuid, bd));
			}
		} else {
			// Get releases for all branches of the component
			List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, null);
			if (branches != null && !branches.isEmpty()) {
				for (BranchData branch : branches) {
					List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
						branch.getUuid(), dateFrom, dateTo, ReleaseLifecycle.DRAFT);
					if (branchReleases != null && !branchReleases.isEmpty()) {
						// Sort within branch by creation date (newest first)
						branchReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
						releasesByBranch.put(branch.getUuid(), branchReleases);
						branchDataMap.put(branch.getUuid(), branch);
					}
				}
			}
		}
		
		if (releasesByBranch.isEmpty()) {
			throw new RelizaException("No releases found for component " + componentUuid + 
				" in date range " + dateFrom + " to " + dateTo);
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
		
		return (aggregationType == AggregationType.NONE)
			? computeNoneChangelog(releasesByBranch, branchDataMap, component, org, globalFirstRelease, globalLastRelease, userTimeZone)
			: computeAggregatedChangelog(releasesByBranch, branchDataMap, component, org, globalFirstRelease, globalLastRelease, userTimeZone);
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
		
		// Process each component
		for (ComponentData component : components) {
			try {
				// Get releases for this component in date range using existing SharedReleaseService method
				List<ReleaseData> releases = new ArrayList<>();
				List<BranchData> branches = branchService.listBranchDataOfComponent(component.getUuid(), null);
				
				if (branches != null && !branches.isEmpty()) {
					for (BranchData branch : branches) {
						List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
							branch.getUuid(), dateFrom, dateTo, ReleaseLifecycle.DRAFT);
						if (branchReleases != null && !branchReleases.isEmpty()) {
							releases.addAll(branchReleases);
						}
					}
				}
				
				if (releases.isEmpty()) {
					continue;
				}
				
				// Sort releases by creation date (newest first)
				releases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
				
				// Convert flat list to grouped structure for new methods
				LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch = groupReleasesByBranch(releases);
				Map<UUID, BranchData> branchDataMap = getBranchDataMap(component.getUuid());
				ReleaseData globalFirst = releases.get(releases.size() - 1);
				ReleaseData globalLast = releases.get(0);
				
				ComponentChangelog componentChangelog = (aggregationType == AggregationType.NONE)
					? computeNoneChangelog(releasesByBranch, branchDataMap, component, orgUuid, globalFirst, globalLast, userTimeZone)
					: computeAggregatedChangelog(releasesByBranch, branchDataMap, component, orgUuid, globalFirst, globalLast, userTimeZone);
				
				componentChangelogs.add(componentChangelog);
				
				// Collect data for org-wide aggregation (AGGREGATED mode only)
				if (aggregationType == AggregationType.AGGREGATED) {
					List<AcollectionData> acollections = acollectionService.getAcollectionsForDateRange(
						component.getUuid(), dateFrom, dateTo);
					if (acollections != null && !acollections.isEmpty()) {
						componentAcollectionsMap.put(component.getUuid(), acollections);
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
				componentAcollectionsMap, componentReleasesMap, componentNamesMap);
			
			FindingChangesWithAttribution orgFindingChanges = findingComparisonService.compareMetricsAcrossComponents(
				componentReleasesMap, componentNamesMap);
			
			return new AggregatedOrganizationChangelog(
				orgUuid, dateFrom, dateTo, componentChangelogs, orgSbomChanges, orgFindingChanges);
		}
	}
	
	/**
	 * Computes changelog for NONE mode (per-release breakdown).
	 * Computes code, SBOM, and finding changes separately for each release.
	 * 
	 * @param releasesByBranch Releases already grouped by branch (sorted newest first within each branch)
	 * @param branchDataMap Map of branch UUID to BranchData
	 * @param component Component data
	 * @param org Organization UUID
	 * @param globalFirstRelease Oldest release across all branches (for metadata)
	 * @param globalLastRelease Newest release across all branches (for metadata)
	 * @param userTimeZone User's timezone for date formatting
	 * @return NoneChangelog with per-release breakdown
	 */
	private NoneChangelog computeNoneChangelog(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			ComponentData component,
			UUID org,
			ReleaseData globalFirstRelease,
			ReleaseData globalLastRelease,
			String userTimeZone) throws RelizaException {
		
		if (releasesByBranch.isEmpty()) {
			throw new RelizaException("No releases provided for changelog computation");
		}
		
		ReleaseInfo firstReleaseInfo = new ReleaseInfo(
			globalFirstRelease.getUuid(), globalFirstRelease.getVersion(), globalFirstRelease.getLifecycle());
		ReleaseInfo lastReleaseInfo = new ReleaseInfo(
			globalLastRelease.getUuid(), globalLastRelease.getVersion(), globalLastRelease.getLifecycle());
		
		// 1. Compute CODE changes per release
		List<NoneBranchChanges> branchChanges = computeNoneCodeChanges(
			releasesByBranch, branchDataMap, org, userTimeZone);
		
		// 2. Compute SBOM changes per release
		List<ReleaseSbomChanges> sbomChanges = computePerReleaseSbomChanges(releasesByBranch, org);
		
		// 3. Compute FINDING changes per release
		List<ReleaseFindingChanges> findingChanges = computePerReleaseFindingChanges(releasesByBranch, org);
		
		return new NoneChangelog(
			component.getUuid(),
			component.getName(),
			org,
			firstReleaseInfo,
			lastReleaseInfo,
			branchChanges,
			sbomChanges,
			findingChanges
		);
	}
	
	/**
	 * Computes code changes for NONE mode (per-release breakdown).
	 * Accepts pre-grouped releases by branch.
	 */
	private List<NoneBranchChanges> computeNoneCodeChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			UUID org,
			String userTimeZone) {
		
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		List<NoneBranchChanges> branchChangesList = new ArrayList<>();
		
		releasesByBranch.forEach((branchId, branchReleases) -> {
			BranchData currentBranch = branchDataMap.get(branchId);
			if (currentBranch == null) return;
			
			List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(branchReleases, org);
			Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(
				sceDataList, vcsRepoDataList, org);
			
			List<ReleaseCodeChanges> releaseCodeChangesList = new ArrayList<>();
			
			for (ReleaseData release : branchReleases) {
				Set<UUID> commitIds = release.getAllCommits();
				List<CodeCommit> commits = new ArrayList<>();
				
				for (UUID commitId : commitIds) {
					CommitRecord commitRecord = commitIdToRecordMap.get(commitId);
					if (commitRecord != null) {
						ConventionalCommit cc = resolveConventionalCommit(commitRecord.commitMessage());
						commits.add(new CodeCommit(
							commitRecord.commitId(),
							commitRecord.commitUri(),
							cc != null ? cc.getMessage() : commitRecord.commitMessage(),
							commitRecord.commitAuthor(),
							commitRecord.commitEmail(),
							cc != null ? cc.getType().name().toLowerCase() : "other"
						));
					}
				}
				
				if (!commits.isEmpty()) {
					releaseCodeChangesList.add(new ReleaseCodeChanges(
						release.getUuid(),
						release.getDecoratedVersionString(userTimeZone),
						release.getLifecycle(),
						commits
					));
				}
			}
			
			if (!releaseCodeChangesList.isEmpty()) {
				branchChangesList.add(new NoneBranchChanges(
					branchId,
					currentBranch.getName(),
					releaseCodeChangesList
				));
			}
		});
		
		return branchChangesList;
	}
	
	/**
	 * Computes SBOM changes for each release (NONE mode).
	 * Accepts pre-grouped releases by branch with sequential comparisons within each branch.
	 */
	private List<ReleaseSbomChanges> computePerReleaseSbomChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			UUID org) {
		
		List<ReleaseSbomChanges> sbomChangesList = new ArrayList<>();
		
		// Process each branch separately (already grouped and sorted)
		releasesByBranch.forEach((branchId, branchReleases) -> {
			
			for (int i = 0; i < branchReleases.size(); i++) {
				ReleaseData currentRelease = branchReleases.get(i);
				ReleaseData previousRelease = (i < branchReleases.size() - 1) ? branchReleases.get(i + 1) : null;
				
				if (previousRelease == null) {
					// First release in this branch - no comparison
					sbomChangesList.add(new ReleaseSbomChanges(
						currentRelease.getUuid(), List.of(), List.of()));
					continue;
				}
				
				try {
					List<AcollectionData> acollections = acollectionService.getAcollectionsForReleaseRange(
						previousRelease.getUuid(), currentRelease.getUuid());
					AcollectionData.ArtifactChangelog sbomChanges = sbomComparisonService.aggregateChangelogs(acollections);
					
					List<String> addedArtifacts = sbomChanges.added() != null 
						? sbomChanges.added().stream()
							.map(dc -> dc.purl())
							.filter(Objects::nonNull)
							.toList()
						: List.of();
					
					List<String> removedArtifacts = sbomChanges.removed() != null
						? sbomChanges.removed().stream()
							.map(dc -> dc.purl())
							.filter(Objects::nonNull)
							.toList()
						: List.of();
					
					sbomChangesList.add(new ReleaseSbomChanges(
						currentRelease.getUuid(), addedArtifacts, removedArtifacts));
					
				} catch (Exception e) {
					log.error("Error computing SBOM changes for release {} in branch {}: {}", 
						currentRelease.getUuid(), branchId, e.getMessage());
					sbomChangesList.add(new ReleaseSbomChanges(
						currentRelease.getUuid(), List.of(), List.of()));
				}
			}
		});
		
		return sbomChangesList;
	}
	
	/**
	 * Computes finding changes for each release (NONE mode).
	 * Accepts pre-grouped releases by branch with sequential comparisons within each branch.
	 */
	private List<ReleaseFindingChanges> computePerReleaseFindingChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			UUID org) {
		
		List<ReleaseFindingChanges> findingChangesList = new ArrayList<>();
		
		// Process each branch separately (already grouped and sorted)
		releasesByBranch.forEach((branchId, branchReleases) -> {
			
			for (int i = 0; i < branchReleases.size(); i++) {
				ReleaseData currentRelease = branchReleases.get(i);
				ReleaseData previousRelease = (i < branchReleases.size() - 1) ? branchReleases.get(i + 1) : null;
				
				if (previousRelease == null) {
					// First release in this branch - no comparison
					findingChangesList.add(new ReleaseFindingChanges(
						currentRelease.getUuid(), 0, 0, List.of(), List.of()));
					continue;
				}
				
				try {
					MetricsPair metrics = extractMetricsForReleases(
						previousRelease.getUuid(), currentRelease.getUuid(), org);
					
					FindingChangesDto.FindingChangesRecord findingChanges = (metrics == null)
						? findingComparisonService.emptyFindingChanges()
						: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
					
					List<String> appearedVulns = findingChanges.appearedVulnerabilities() != null
						? findingChanges.appearedVulnerabilities().stream()
							.map(v -> v.vulnId())
							.toList()
						: List.of();
					
					List<String> resolvedVulns = findingChanges.resolvedVulnerabilities() != null
						? findingChanges.resolvedVulnerabilities().stream()
							.map(v -> v.vulnId())
							.toList()
						: List.of();
					
					int appearedCount = (findingChanges.appearedVulnerabilities() != null ? findingChanges.appearedVulnerabilities().size() : 0)
						+ (findingChanges.appearedViolations() != null ? findingChanges.appearedViolations().size() : 0)
						+ (findingChanges.appearedWeaknesses() != null ? findingChanges.appearedWeaknesses().size() : 0);
					
					int resolvedCount = (findingChanges.resolvedVulnerabilities() != null ? findingChanges.resolvedVulnerabilities().size() : 0)
						+ (findingChanges.resolvedViolations() != null ? findingChanges.resolvedViolations().size() : 0)
						+ (findingChanges.resolvedWeaknesses() != null ? findingChanges.resolvedWeaknesses().size() : 0);
					
					findingChangesList.add(new ReleaseFindingChanges(
						currentRelease.getUuid(), appearedCount, resolvedCount, appearedVulns, resolvedVulns));
					
				} catch (Exception e) {
					log.error("Error computing finding changes for release {} in branch {}: {}", 
						currentRelease.getUuid(), branchId, e.getMessage());
					findingChangesList.add(new ReleaseFindingChanges(
						currentRelease.getUuid(), 0, 0, List.of(), List.of()));
				}
			}
		});
		
		return findingChangesList;
	}
	
	/**
	 * Computes changelog for AGGREGATED mode (component-level summary).
	 * Computes code, SBOM, and finding changes at component level with attribution.
	 * Aggregates per-branch metrics and combines them for component-level totals.
	 * 
	 * @param releasesByBranch Releases already grouped by branch (sorted newest first within each branch)
	 * @param branchDataMap Map of branch UUID to BranchData
	 * @param component Component data
	 * @param org Organization UUID
	 * @param globalFirstRelease Oldest release across all branches (for metadata)
	 * @param globalLastRelease Newest release across all branches (for metadata)
	 * @param userTimeZone User's timezone for date formatting
	 * @return AggregatedChangelog with component-level summary
	 */
	private AggregatedChangelog computeAggregatedChangelog(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			ComponentData component,
			UUID org,
			ReleaseData globalFirstRelease,
			ReleaseData globalLastRelease,
			String userTimeZone) throws RelizaException {
		
		if (releasesByBranch.isEmpty()) {
			throw new RelizaException("No releases provided for changelog computation");
		}
		
		ReleaseInfo firstReleaseInfo = new ReleaseInfo(
			globalFirstRelease.getUuid(), globalFirstRelease.getVersion(), globalFirstRelease.getLifecycle());
		ReleaseInfo lastReleaseInfo = new ReleaseInfo(
			globalLastRelease.getUuid(), globalLastRelease.getVersion(), globalLastRelease.getLifecycle());
		
		// 1. Compute CODE changes (aggregated by type)
		List<AggregatedBranchChanges> branchChanges = computeAggregatedCodeChanges(
			releasesByBranch, branchDataMap, org, userTimeZone);
		
		// 2. Compute component-level SBOM changes with accurate per-release attribution
		SbomChangesWithAttribution sbomChanges = computeComponentSbomChanges(
			releasesByBranch, branchDataMap, component);
		
		// 3. Compute component-level finding changes with accurate per-release attribution
		FindingChangesWithAttribution findingChanges = computeComponentFindingChanges(
			releasesByBranch, branchDataMap, org, component);
		
		return new AggregatedChangelog(
			component.getUuid(),
			component.getName(),
			org,
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
	 */
	private List<AggregatedBranchChanges> computeAggregatedCodeChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			UUID org,
			String userTimeZone) {
		
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		List<AggregatedBranchChanges> branchChangesList = new ArrayList<>();
		
		releasesByBranch.forEach((branchId, branchReleases) -> {
			BranchData currentBranch = branchDataMap.get(branchId);
			if (currentBranch == null) return;
			
			List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(branchReleases, org);
			Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(
				sceDataList, vcsRepoDataList, org);
			
			// Collect all commits and group by type
			Map<String, List<CodeCommit>> commitsByType = new HashMap<>();
			
			for (Map.Entry<UUID, CommitRecord> entry : commitIdToRecordMap.entrySet()) {
				CommitRecord commitRecord = entry.getValue();
				ConventionalCommit cc = resolveConventionalCommit(commitRecord.commitMessage());
				
				String changeType = cc != null ? cc.getType().name().toLowerCase() : "other";
				
				CodeCommit codeCommit = new CodeCommit(
					commitRecord.commitId(),
					commitRecord.commitUri(),
					cc != null ? cc.getMessage() : commitRecord.commitMessage(),
					commitRecord.commitAuthor(),
					commitRecord.commitEmail(),
					changeType
				);
				
				commitsByType.computeIfAbsent(changeType, k -> new ArrayList<>()).add(codeCommit);
			}
			
			if (!commitsByType.isEmpty()) {
				branchChangesList.add(new AggregatedBranchChanges(
					branchId,
					currentBranch.getName(),
					commitsByType
				));
			}
		});
		
		return branchChangesList;
	}
	
	/**
	 * Computes component-level SBOM changes with attribution (AGGREGATED mode).
	 * Uses attribution-aware aggregation to track exactly which release each artifact was added/removed in.
	 */
	private SbomChangesWithAttribution computeComponentSbomChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			ComponentData component) {
		
		try {
			// Use attribution-aware aggregation
			return sbomComparisonService.aggregateComponentChangelogsWithAttribution(
				releasesByBranch, branchDataMap, component);
				
		} catch (Exception e) {
			log.error("Error computing component SBOM changes: {}", e.getMessage());
			return new SbomChangesWithAttribution(List.of(), 0, 0);
		}
	}
	
	/**
	 * Computes component-level finding changes with attribution (AGGREGATED mode).
	 * Uses attribution-aware comparison to track exactly which release each finding appeared/resolved in.
	 */
	private FindingChangesWithAttribution computeComponentFindingChanges(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			Map<UUID, BranchData> branchDataMap,
			UUID org,
			ComponentData component) throws RelizaException {
		
		try {
			// Use attribution-aware comparison with lambda to capture org parameter
			return findingComparisonService.compareMetricsWithAttributionAcrossBranches(
				releasesByBranch, branchDataMap, component, org, 
				(uuid1, uuid2) -> {
					try {
						return extractMetricsForReleases(uuid1, uuid2, org);
					} catch (RelizaException e) {
						log.warn("Failed to extract metrics for releases {} and {}: {}", uuid1, uuid2, e.getMessage());
						return null;
					}
				});
				
		} catch (Exception e) {
			log.error("Error computing component finding changes: {}", e.getMessage());
			return new FindingChangesWithAttribution(List.of(), List.of(), List.of(), 0, 0);
		}
	}
	
	// ========== OLD: Changelog Building Methods (To be deprecated) ==========
	
	/**
	 * Gets changelog between two releases.
	 * Always includes CODE, SBOM, and Finding changes.
	 * Use the aggregated parameter to control whether changes are shown per-release (NONE) or aggregated (AGGREGATED).
	 * 
	 * @deprecated Use computeNoneChangelog or computeAggregatedChangelog instead
	 */
	// @Deprecated
	// public ComponentJsonDto getChangelogBetweenReleases(
	// 		UUID uuid1,
	// 		UUID uuid2,
	// 		UUID org,
	// 		AggregationType aggregated,
	// 		String userTimeZone) throws RelizaException {
		
	// 	ComponentJsonDto changelog = null;
	// 	List<ReleaseData> rds = sharedReleaseService.listAllReleasesBetweenReleases(uuid1, uuid2);
		
	// 	if (rds.size() > 0 && null != rds.get(0)) {
	// 		Optional<ComponentData> opd = getComponentService.getComponentData(rds.get(0).getComponent());
	// 		ComponentData pd = opd.orElseThrow(() -> 
	// 			new RelizaException("Component not found: " + rds.get(0).getComponent()));
			
	// 		if (pd.getType().equals(ComponentType.COMPONENT)) {
	// 			// Extract release range metadata
	// 			var lastRelease = rds.get(0);
	// 			var firstRelease = rds.get(rds.size() - 1);
	// 			rds.remove(rds.size() - 1); // removeLast = true
	// 			ReleaseRangeMetadata releaseRange = new ReleaseRangeMetadata(firstRelease, lastRelease);
				
	// 			if (aggregated == AggregationType.NONE) {
	// 				// ===== NONE MODE: Per-release breakdown =====
	// 				// Compute per-release SBOM and Finding changes
	// 				Map<UUID, ReleaseChanges> perReleaseChanges = computePerReleaseChanges(rds, org);
					
	// 				// Compute code changes (will embed per-release SBOM/Finding in ReleaseRecords)
	// 				ComponentCodeChanges codeChanges = computeCodeChanges(
	// 					rds, pd.getUuid(), org, aggregated, userTimeZone, perReleaseChanges);
					
	// 				// Assemble (no component-level SBOM/Finding changes in NONE mode)
	// 				changelog = assembleComponentChangelog(pd, org, releaseRange, codeChanges, null, null);
					
	// 			} else {
	// 				// ===== AGGREGATED MODE: Component-level summary =====
	// 				// Compute code changes (aggregated commits by type)
	// 				ComponentCodeChanges codeChanges = computeCodeChanges(
	// 					rds, pd.getUuid(), org, aggregated, userTimeZone, null);
					
	// 				// Compute component-level SBOM changes
	// 				List<AcollectionData> acollections = acollectionService.getAcollectionsForReleaseRange(uuid1, uuid2);
	// 				AcollectionData.ArtifactChangelog sbomChangesOld = 
	// 					sbomComparisonService.aggregateChangelogs(acollections);
	// 				SbomChangesWithAttribution sbomChanges = convertToSbomChangesWithAttribution(
	// 					sbomChangesOld, pd.getUuid(), pd.getName(), lastRelease, firstRelease);
					
	// 				// Compute component-level finding changes
	// 				MetricsPair metrics = extractMetricsForReleases(uuid1, uuid2, org);
	// 				FindingChangesDto.FindingChangesRecord findingChangesOld = (metrics == null)
	// 					? findingComparisonService.emptyFindingChanges()
	// 					: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
	// 				FindingChangesWithAttribution findingChanges = convertToFindingChangesWithAttribution(
	// 					findingChangesOld, pd.getUuid(), pd.getName(), lastRelease, firstRelease);
					
	// 				// Assemble with component-level changes
	// 				changelog = assembleComponentChangelog(pd, org, releaseRange, codeChanges, sbomChanges, findingChanges);
	// 			}
				
	// 		} else {
	// 			// Product changelog - keep old pattern for now
	// 			changelog = getChangeLogJsonForProductReleaseDataList(rds, org, true, aggregated, userTimeZone);
	// 		}
	// 	}
		
	// 	return changelog;
	// }
	
	// /**
	//  * Gets component changelog by date range.
	//  * Aggregates releases from all active branches of the component within the specified date range.
	//  */
	// public ComponentJsonDto getComponentChangeLogByDate(
	// 		UUID componentUuid,
	// 		UUID orgUuid,
	// 		ZonedDateTime dateFrom,
	// 		ZonedDateTime dateTo,
	// 		AggregationType aggregated,
	// 		String timeZone) throws RelizaException {
				
	// 	// Get component data
	// 	Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
	// 	if (opd.isEmpty()) {
	// 		log.warn("Component not found: {}", componentUuid);
	// 		return null;
	// 	}
	// 	ComponentData pd = opd.get();		
	// 	// Get all active branches for this component
	// 	List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, null);
	// 	if (branches == null || branches.isEmpty()) {
	// 		log.warn("No branches found for component: {}", componentUuid);
	// 		return null;
	// 	}		
	// 	// Collect releases from all branches within the date range
	// 	List<ReleaseData> allReleases = new ArrayList<>();
	// 	for (BranchData branch : branches) {
	// 		List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
	// 				branch.getUuid(), 
	// 				dateFrom, 
	// 				dateTo, 
	// 				ReleaseLifecycle.DRAFT);
	// 		if (branchReleases != null && !branchReleases.isEmpty()) {
	// 			allReleases.addAll(branchReleases);
	// 		}
	// 	}
		
	// 	if (allReleases.isEmpty()) {
	// 		return null;
	// 	}
		
	// 	// Sort releases by creation date (most recent first)
	// 	allReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
		
	// 	// Compute per-release changes if NONE mode
	// 	Map<UUID, ReleaseChanges> perReleaseChanges = null;
	// 	if (aggregated == AggregationType.NONE) {
	// 		perReleaseChanges = computePerReleaseChanges(allReleases, orgUuid);
	// 	}
		
	// 	// Build changelog JSON using the existing helper method
	// 	ComponentJsonDto changelog = getChangeLogJsonForReleaseDataList(
	// 			allReleases, 
	// 			orgUuid, 
	// 			false,  // Don't remove last element for date-based queries
	// 			pd, 
	// 			aggregated, 
	// 			timeZone,
	// 			perReleaseChanges);
		
	// 	// Add top-level SBOM and finding changes in AGGREGATED mode
	// 	if (changelog != null && aggregated == AggregationType.AGGREGATED && !allReleases.isEmpty()) {

	// 		try {
	// 			// For findings, use boundary comparison (same as branch changelog logic)
	// 			// Compare first release (oldest) to last release (newest) in the date range
	// 			UUID firstUuid = allReleases.get(allReleases.size() - 1).getUuid();
	// 			UUID lastUuid = allReleases.get(0).getUuid();
				
	// 			MetricsPair metrics = extractMetricsForReleases(firstUuid, lastUuid, orgUuid);
	// 			List<AcollectionData> acollections = acollectionService.getAcollectionsForDateRange(componentUuid, dateFrom, dateTo);
				
	// 			if (metrics != null) {
	// 				FindingChangesDto.FindingChangesRecord findingChanges = findingComparisonService.compareMetrics(
	// 					metrics.metrics1(), metrics.metrics2());
					
	// 				// Convert to attribution format
	// 				FindingChangesWithAttribution findingChangesWithAttr = convertToFindingChangesWithAttribution(
	// 					findingChanges, componentUuid, pd.getName(), allReleases.get(0), allReleases.get(allReleases.size() - 1));
	// 				changelog.setFindingChanges(findingChangesWithAttr);
	// 			}
				
	// 			if (acollections != null && !acollections.isEmpty()) {
	// 				AcollectionData.ArtifactChangelog sbomChanges = sbomComparisonService.aggregateChangelogs(acollections);
					
	// 				// Convert to attribution format
	// 				SbomChangesWithAttribution sbomChangesWithAttr = convertToSbomChangesWithAttribution(
	// 					sbomChanges, componentUuid, pd.getName(), allReleases.get(0), allReleases.get(allReleases.size() - 1));
	// 				changelog.setSbomChanges(sbomChangesWithAttr);
	// 			}
				
	// 		} catch (Exception e) {
	// 			log.error("Error computing date-based aggregated changes: {}", e.getMessage(), e);
	// 		}
	// 	}
		
	// 	return changelog;
	// }
	
	// /**
	//  * Gets component changelog for a branch.
	//  */
	// public ComponentJsonDto getComponentChangeLog(
	// 		UUID branchUuid,
	// 		UUID orgUuid,
	// 		AggregationType aggregated,
	// 		String timeZone) throws RelizaException {
		
	// 	ComponentJsonDto changelog = null;
	// 	List<ReleaseData> releases = sharedReleaseService.listReleaseDataOfBranch(branchUuid, true);
		
	// 	if (releases != null && !releases.isEmpty()) {
	// 		ReleaseData rd = releases.get(0);
	// 		if (rd != null) {
	// 			Optional<ComponentData> opd = getComponentService.getComponentData(rd.getComponent());
	// 			ComponentData pd = opd.orElseThrow(() -> new RelizaException("Component not found: " + rd.getComponent()));
				
	// 			Map<UUID, ReleaseChanges> perReleaseChanges = null;
				
	// 			// Compute per-release changes for NONE mode
	// 			if (aggregated == AggregationType.NONE && releases.size() >= 2) {
	// 				perReleaseChanges = computePerReleaseChanges(releases, orgUuid);
	// 			}
				
	// 			// Build changelog JSON with pre-computed data
	// 			changelog = getChangeLogJsonForReleaseDataList(
	// 				releases, orgUuid, false, pd, aggregated, timeZone, perReleaseChanges);
				
	// 			// Add top-level aggregated changes for AGGREGATED mode
	// 			if (aggregated == AggregationType.AGGREGATED && releases.size() >= 2) {
	// 				try {
	// 					UUID firstUuid = releases.get(releases.size() - 1).getUuid();
	// 					UUID lastUuid = releases.get(0).getUuid();
						
	// 					MetricsPair metrics = extractMetricsForReleases(firstUuid, lastUuid, orgUuid);
	// 					List<AcollectionData> acollections = 
	// 						acollectionService.getAcollectionsForReleaseRange(firstUuid, lastUuid);
						
	// 					// Sequential comparison
	// 					FindingChangesDto.FindingChangesRecord findingChangesOld = (metrics == null)
	// 						? findingComparisonService.emptyFindingChanges()
	// 						: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
						
	// 					AcollectionData.ArtifactChangelog sbomChangesOld = 
	// 						sbomComparisonService.aggregateChangelogs(acollections);
						
	// 					// Convert to attribution format
	// 					ReleaseData firstRel = releases.isEmpty() ? null : releases.get(releases.size() - 1);
	// 					ReleaseData lastRel = releases.isEmpty() ? null : releases.get(0);
	// 					FindingChangesWithAttribution findingChangesAttr = convertToFindingChangesWithAttribution(
	// 						findingChangesOld, pd.getUuid(), pd.getName(), lastRel, firstRel);
	// 					SbomChangesWithAttribution sbomChangesAttr = convertToSbomChangesWithAttribution(
	// 						sbomChangesOld, pd.getUuid(), pd.getName(), lastRel, firstRel);
	// 					changelog.setFindingChanges(findingChangesAttr);
	// 					changelog.setSbomChanges(sbomChangesAttr);
	// 				} catch (Exception e) {
	// 					log.error("Error computing aggregated changes for branch changelog: {}", e.getMessage(), e);
	// 				}
	// 			}
	// 		}
	// 	}
		
	// 	return changelog;
	// }
	
	/**
	 * Gets product changelog for a branch.
	 */
	public ComponentJsonDto getProductChangeLog(
			UUID branchUuid,
			UUID orgUuid,
			AggregationType aggregated,
			String timeZone) throws RelizaException {
		
		List<ReleaseData> productRds = sharedReleaseService.listReleaseDataOfBranch(branchUuid, true)
				.stream()
				.filter(release -> !ReleaseLifecycle.isAssemblyAllowed(release.getLifecycle()))
				.collect(Collectors.toList());
		
		ComponentJsonDto changelog = getChangeLogJsonForProductReleaseDataList(productRds, orgUuid, false, aggregated, timeZone);
		
		// Add top-level finding changes in AGGREGATED mode
		// Note: SBOM changes are already aggregated from components in getChangeLogJsonForProductReleaseDataList
		if (changelog != null && productRds.size() >= 2 && aggregated == AggregationType.AGGREGATED) {
			UUID firstUuid = productRds.get(productRds.size() - 1).getUuid();
			UUID lastUuid = productRds.get(0).getUuid();
			changelog = addProductLevelFindingChanges(changelog, firstUuid, lastUuid, orgUuid);
		}
		
		return changelog;
	}
	
	/**
	 * Gets product changelog by date range.
	 * Aggregates releases from all active branches of the product within the specified date range.
	 */
	public ComponentJsonDto getProductChangeLogByDate(
			UUID productUuid,
			UUID orgUuid,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo,
			AggregationType aggregated,
			String timeZone) throws RelizaException {
		
		// Get product data
		Optional<ComponentData> opd = getComponentService.getComponentData(productUuid);
		if (opd.isEmpty()) {
			log.warn("Product not found: {}", productUuid);
			return null;
		}
		
		// Get all active branches for this product
		List<BranchData> branches = branchService.listBranchDataOfComponent(productUuid, null);
		if (branches == null || branches.isEmpty()) {
			log.warn("No branches found for product: {}", productUuid);
			return null;
		}
		
		// Collect releases from all branches within the date range
		List<ReleaseData> allProductReleases = new ArrayList<>();
		for (BranchData branch : branches) {
			List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
					branch.getUuid(), 
					dateFrom, 
					dateTo, 
					ReleaseLifecycle.DRAFT);
			if (branchReleases != null && !branchReleases.isEmpty()) {
				// Filter out assembly-allowed releases for products
				List<ReleaseData> filteredReleases = branchReleases.stream()
					.filter(release -> !ReleaseLifecycle.isAssemblyAllowed(release.getLifecycle()))
					.collect(Collectors.toList());
				allProductReleases.addAll(filteredReleases);
			}
		}
		
		if (allProductReleases.isEmpty()) {
			return null;
		}
		
		// Sort releases by creation date (most recent first)
		allProductReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
		
		// Build changelog JSON using the product-specific helper method
		ComponentJsonDto changelog = getChangeLogJsonForProductReleaseDataList(
				allProductReleases, 
				orgUuid, 
				false,  // Don't remove last element for date-based queries
				aggregated, 
				timeZone);
		
		// Add top-level finding changes in AGGREGATED mode
		// Note: SBOM changes are already aggregated from components in getChangeLogJsonForProductReleaseDataList
		if (changelog != null && allProductReleases.size() >= 2 && aggregated == AggregationType.AGGREGATED) {
			UUID firstUuid = allProductReleases.get(allProductReleases.size() - 1).getUuid();
			UUID lastUuid = allProductReleases.get(0).getUuid();
			changelog = addProductLevelFindingChanges(changelog, firstUuid, lastUuid, orgUuid);
		}
		
		return changelog;
	}
	
	/**
	 * Gets organization-wide changelog by date range.
	 * Optionally filters by perspective.
	 * Includes SBOM changes and Finding changes only (no code changes).
	 * 
	 * @param orgUuid Organization UUID
	 * @param perspectiveUuid Optional perspective UUID (null = all components)
	 * @param dateFrom Start date
	 * @param dateTo End date
	 * @param aggregationType NONE or AGGREGATED
	 * @param timeZone User timezone for date formatting
	 * @return Organization changelog with SBOM and Finding changes only
	 */
	public ComponentJsonDto getOrganizationChangeLogByDate(
			UUID orgUuid,
			UUID perspectiveUuid,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo,
			AggregationType aggregationType,
			String timeZone) throws RelizaException {
				
		// Get components based on perspective
		List<ComponentData> components = getComponentsForOrganizationChangelog(orgUuid, perspectiveUuid);
		
		if (components.isEmpty()) {
			log.warn("No components found for organization {} with perspective {}", orgUuid, perspectiveUuid);
			return null;
		}
				
		// Build organization-level changelog structure
		ComponentJsonDtoBuilder changelogBuilder = ComponentJsonDto.builder()
			.uuid(orgUuid)
			.name("Organization Changelog")
			.org(orgUuid);
		
		List<ComponentJsonDto> componentChangelogs = new ArrayList<>();
		Map<UUID, List<AcollectionData>> componentAcollectionsMap = new HashMap<>();
		List<ReleaseData> allReleases = new ArrayList<>();
		
		// Process each component
		for (ComponentData component : components) {
			log.debug("Processing component: {} ({})", component.getName(), component.getUuid());
			
			// Get all active branches for this component
			List<BranchData> branches = branchService.listBranchDataOfComponent(component.getUuid(), null);
			if (branches == null || branches.isEmpty()) {
				log.debug("No branches found for component: {}", component.getName());
				continue;
			}
			
			log.debug("Found {} branches for component: {}", branches.size(), component.getName());
			
			// Collect releases from all branches within the date range
			List<ReleaseData> componentReleases = new ArrayList<>();
			for (BranchData branch : branches) {
				List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
						branch.getUuid(), 
						dateFrom, 
						dateTo, 
						ReleaseLifecycle.DRAFT);
				if (branchReleases != null && !branchReleases.isEmpty()) {
					log.debug("Found {} releases for branch {} in date range", branchReleases.size(), branch.getName());
					componentReleases.addAll(branchReleases);
					allReleases.addAll(branchReleases);
				} else {
					log.debug("No releases found for branch {} in date range", branch.getName());
				}
			}
			
			if (componentReleases.isEmpty()) {
				log.debug("No releases found for component {} in date range", component.getName());
				continue;
			}
						
			// Sort releases by creation date
			componentReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
			
			// Compute per-release changes if NONE mode (SBOM and Findings only, no code changes)
			Map<UUID, ReleaseChanges> perReleaseChanges = null;
			if (aggregationType == AggregationType.NONE) {
				perReleaseChanges = computePerReleaseChanges(componentReleases, orgUuid);
			}
			
			// Build component changelog (without code changes)
			log.debug("Building changelog for component: {}", component.getName());
			ComponentJsonDto componentChangelog = buildComponentChangelogWithoutCode(
					componentReleases, 
					orgUuid, 
					component, 
					aggregationType, 
					timeZone,
					perReleaseChanges);
			
			if (componentChangelog != null) {
				componentChangelogs.add(componentChangelog);
				log.debug("Successfully built changelog for component: {}", component.getName());
				
				// Collect acollections for AGGREGATED mode
				if (aggregationType == AggregationType.AGGREGATED) {
					List<AcollectionData> componentAcollections = acollectionService.getAcollectionsForDateRange(
							component.getUuid(), dateFrom, dateTo);
					if (componentAcollections != null && !componentAcollections.isEmpty()) {
						log.debug("Found {} acollections for component {} in AGGREGATED mode", 
								componentAcollections.size(), component.getName());
						componentAcollectionsMap.put(component.getUuid(), componentAcollections);
					}
				}
			} else {
				log.warn("Failed to build changelog for component: {}", component.getName());
			}
		}
		
		if (componentChangelogs.isEmpty()) {
			log.warn("No component changelogs generated for organization {} between {} and {}", orgUuid, dateFrom, dateTo);
			return null;
		}
				
		changelogBuilder.components(componentChangelogs);
		
		// Add top-level SBOM and finding changes in AGGREGATED mode
		if (aggregationType == AggregationType.AGGREGATED && !allReleases.isEmpty()) {
			
			try {
				// Build component releases map and component names map for attribution
				Map<UUID, List<ReleaseData>> componentReleases = new HashMap<>();
				Map<UUID, String> componentNames = new HashMap<>();
				
				for (ComponentData component : components) {
					List<ReleaseData> releases = allReleases.stream()
						.filter(r -> {
							// Check if this release belongs to this component
							try {
								Optional<BranchData> branchOpt = branchService.getBranchData(r.getBranch());
								return branchOpt.isPresent() && branchOpt.get().getComponent().equals(component.getUuid());
							} catch (Exception e) {
								return false;
							}
						})
						.collect(Collectors.toList());
					
					if (!releases.isEmpty()) {
						componentReleases.put(component.getUuid(), releases);
						componentNames.put(component.getUuid(), component.getName());
					}
				}
				
				// Use compareMetricsAcrossComponents for proper attribution
				if (!componentReleases.isEmpty()) {
					FindingChangesWithAttribution findingChanges = findingComparisonService.compareMetricsAcrossComponents(
						componentReleases, componentNames);
					changelogBuilder.findingChanges(findingChanges);
				}
				
				// Aggregate SBOM changes
				if (!componentAcollectionsMap.isEmpty()) {
					SbomChangesWithAttribution sbomChanges = sbomComparisonService.aggregateChangelogsWithAttribution(
						componentAcollectionsMap, componentReleases, componentNames);
					changelogBuilder.sbomChanges(sbomChanges);
				}
				
			} catch (Exception e) {
				log.error("Error computing aggregated changes for organization changelog: {}", e.getMessage(), e);
			}
		}
		
		return changelogBuilder.build();
	}
	
	// ========== Helper Methods for JSON Building ==========
	
	/**
	 * Assembles a complete component changelog from separately computed changes.
	 * This is the single assembly point where code, SBOM, and finding changes are combined.
	 * 
	 * @param component Component metadata
	 * @param org Organization UUID
	 * @param releaseRange First and last release in the changelog
	 * @param codeChanges Computed code changes (commits)
	 * @param sbomChanges Computed SBOM changes (can be null)
	 * @param findingChanges Computed finding changes (can be null)
	 * @return Complete ComponentJsonDto with all three types of changes
	 */
	private ComponentJsonDto assembleComponentChangelog(
			ComponentData component,
			UUID org,
			ReleaseRangeMetadata releaseRange,
			ComponentCodeChanges codeChanges,
			SbomChangesWithAttribution sbomChanges,
			FindingChangesWithAttribution findingChanges) {
		
		// Build branch DTOs from code changes
		List<ComponentJsonDto> branchDtos = codeChanges.branches().stream()
			.map(branchChange -> {
				ComponentJsonDtoBuilder branchBuilder = ComponentJsonDto.builder()
					.org(org)
					.uuid(branchChange.branchUuid())
					.name(branchChange.branchName());
				
				// Set either releases (NONE) or changes (AGGREGATED)
				if (branchChange.changes() instanceof List<?> list) {
					if (!list.isEmpty() && list.get(0) instanceof ReleaseRecord) {
						@SuppressWarnings("unchecked")
						List<ReleaseRecord> releases = (List<ReleaseRecord>) list;
						branchBuilder.releases(releases);
					} else if (!list.isEmpty() && list.get(0) instanceof ChangeRecord) {
						@SuppressWarnings("unchecked")
						List<ChangeRecord> changes = (List<ChangeRecord>) list;
						branchBuilder.changes(changes);
					}
				}
				
				return branchBuilder.build();
			})
			.collect(Collectors.toList());
		
		// Assemble final DTO with all three change types
		return ComponentJsonDto.builder()
			.org(org)
			.uuid(component.getUuid())
			.name(component.getName())
			.firstRelease(new ReleaseRecord(
				releaseRange.firstRelease().getUuid(),
				releaseRange.firstRelease().getVersion(),
				releaseRange.firstRelease().getLifecycle(),
				null, null, null))
			.lastRelease(new ReleaseRecord(
				releaseRange.lastRelease().getUuid(),
				releaseRange.lastRelease().getVersion(),
				releaseRange.lastRelease().getLifecycle(),
				null, null, null))
			.branches(branchDtos)
			.sbomChanges(sbomChanges)
			.findingChanges(findingChanges)
			.build();
	}
	
	/**
	 * Computes code changes (commits) for a component, organized by branch.
	 * This method extracts and processes commit data without building the final DTO.
	 * SBOM and Finding changes should be computed separately.
	 * 
	 * @param releases List of releases to analyze
	 * @param componentUuid Component UUID
	 * @param org Organization UUID
	 * @param aggregationType NONE (per-release) or AGGREGATED (combined)
	 * @param userTimeZone User's timezone for date formatting
	 * @param perReleaseChanges Pre-computed SBOM/Finding changes (for NONE mode)
	 * @return ComponentCodeChanges containing branch-organized code changes
	 */
	private ComponentCodeChanges computeCodeChanges(
			List<ReleaseData> releases,
			UUID componentUuid,
			UUID org,
			AggregationType aggregationType,
			String userTimeZone,
			Map<UUID, ReleaseChanges> perReleaseChanges) {
		
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		
		Map<UUID, BranchData> branchMap = branchService.listBranchDataOfComponent(componentUuid, null)
				.stream()
				.collect(Collectors.toMap(BranchData::getUuid, Function.identity()));
		
		LinkedHashMap<UUID, List<ReleaseData>> releasesGroupedByBranch = releases.stream()
				.collect(Collectors.groupingBy(ReleaseData::getBranch, LinkedHashMap::new, Collectors.toList()));
		
		List<BranchCodeChanges> branchChanges = new ArrayList<>();
		
		releasesGroupedByBranch.forEach((branchId, brReleases) -> {
			BranchData currentBranch = branchMap.get(branchId);
			
			List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(brReleases, org);
			Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
			
			Object changes = switch (aggregationType) {
				case NONE -> {
					List<ReleaseRecord> releaseRecordList = new ArrayList<>();
					for (var release : brReleases) {
						Set<UUID> ids = release.getAllCommits();
						Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap = commitIdToRecordMap.entrySet().stream()
								.filter(entry -> ids.contains(entry.getKey()))
								.collect(Collectors.toMap(
										Entry::getKey,
										e -> resolveConventionalCommit(e.getValue().commitMessage())));
						
						if (commitIdToConventionalCommitMap.size() > 0) {
							ReleaseChanges releaseChanges = perReleaseChanges != null 
								? perReleaseChanges.getOrDefault(release.getUuid(), ReleaseChanges.empty())
								: ReleaseChanges.empty();
							
							releaseRecordList.add(new ReleaseRecord(
									release.getUuid(),
									release.getDecoratedVersionString(userTimeZone),
									release.getLifecycle(),
									prepareChangeRecordList(commitIdToConventionalCommitMap, commitIdToRecordMap),
									releaseChanges.sbomChanges(),
									releaseChanges.findingChanges()));
						}
					}
					yield releaseRecordList;
				}
				case AGGREGATED -> {
					Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap = commitIdToRecordMap.entrySet().stream()
							.collect(Collectors.toMap(
									Entry::getKey,
									e -> resolveConventionalCommit(e.getValue().commitMessage())));
					yield prepareChangeRecordList(commitIdToConventionalCommitMap, commitIdToRecordMap);
				}
			};
			
			branchChanges.add(new BranchCodeChanges(branchId, currentBranch.getName(), changes));
		});
		
		return new ComponentCodeChanges(branchChanges);
	}
	
	/**
	 * Builds changelog JSON for component releases.
	 * Groups releases by branch and formats commit messages according to aggregation type.
	 * 
	 * @deprecated Use the decoupled pattern instead:
	 *             1. Compute code changes: {@link #computeCodeChanges}
	 *             2. Compute SBOM changes separately
	 *             3. Compute finding changes separately
	 *             4. Assemble all three: {@link #assembleComponentChangelog}
	 *             This method couples code change computation with DTO assembly and requires mutation.
	 * 
	 * @param releases List of releases to include in changelog
	 * @param org Organization UUID
	 * @param removeLast Whether to remove the last (oldest) release from the list
	 * @param pd Component data for the component
	 * @param aggregationType NONE (per-release changes) or AGGREGATED (all changes combined)
	 * @param userTimeZone User's timezone for date formatting
	 * @param perReleaseChanges Pre-computed SBOM/Finding changes per release (for NONE mode)
	 * @return ComponentJsonDto containing formatted changelog data
	 */
	@Deprecated
	private ComponentJsonDto getChangeLogJsonForReleaseDataList(
			List<ReleaseData> releases,
			UUID org,
			Boolean removeLast,
			ComponentData pd,
			AggregationType aggregationType,
			String userTimeZone,
			Map<UUID, ReleaseChanges> perReleaseChanges) {
		
		ComponentJsonDtoBuilder projectJson = ComponentJsonDto.builder();
		var lastRelease = releases.get(0);
		var firstRelease = releases.get(releases.size() - 1);
		
		if (removeLast)
			firstRelease = releases.remove(releases.size() - 1);
		
		// Always set component metadata and release range
		projectJson.org(org)
			.uuid(pd.getUuid())
			.name(pd.getName())
			.firstRelease(new ReleaseRecord(firstRelease.getUuid(), firstRelease.getVersion(), firstRelease.getLifecycle(), null, null, null))
			.lastRelease(new ReleaseRecord(lastRelease.getUuid(), lastRelease.getVersion(), lastRelease.getLifecycle(), null, null, null));
		
		List<VcsRepositoryData> vcsRepoDataList = vcsRepositoryService.listVcsRepoDataByOrg(org);
		List<ComponentJsonDto> branchesJsonList = new ArrayList<>();
		
		Map<UUID, BranchData> branchMap = branchService.listBranchDataOfComponent(pd.getUuid(), null)
				.stream()
				.collect(Collectors.toMap(BranchData::getUuid, Function.identity()));
		
		LinkedHashMap<UUID, List<ReleaseData>> releasesGroupedByBranch = releases.stream()
				.collect(Collectors.groupingBy(ReleaseData::getBranch, LinkedHashMap::new, Collectors.toList()));
		
		releasesGroupedByBranch.forEach((branchId, brReleases) -> {
			ComponentJsonDtoBuilder branchJson = ComponentJsonDto.builder();
			BranchData currentBranch = branchMap.get(branchId);
			branchJson.org(org)
				.uuid(branchId)
				.name(currentBranch.getName());
			
			List<SourceCodeEntryData> sceDataList = sharedReleaseService.getSceDataListFromReleases(brReleases, org);
			Map<UUID, CommitRecord> commitIdToRecordMap = sharedReleaseService.getCommitMessageMapForSceDataList(sceDataList, vcsRepoDataList, org);
			
			Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap = new HashMap<>();
			
			switch (aggregationType) {
				case NONE:
					List<ReleaseRecord> releaseRecordList = new ArrayList<>();
					for (var release : brReleases) {
						Set<UUID> ids = release.getAllCommits();
						commitIdToConventionalCommitMap = commitIdToRecordMap.entrySet().stream()
								.filter(entry -> ids.contains(entry.getKey()))
								.collect(Collectors.toMap(
										Entry::getKey,
										e -> resolveConventionalCommit(e.getValue().commitMessage())));
						if (commitIdToConventionalCommitMap.size() > 0) {
							// Get pre-computed changes for this release
							ReleaseChanges changes = perReleaseChanges != null 
								? perReleaseChanges.getOrDefault(release.getUuid(), ReleaseChanges.empty())
								: ReleaseChanges.empty();
							
							releaseRecordList.add(new ReleaseRecord(
									release.getUuid(),
									release.getDecoratedVersionString(userTimeZone),
									release.getLifecycle(),
									prepareChangeRecordList(commitIdToConventionalCommitMap, commitIdToRecordMap),
									changes.sbomChanges(),
									changes.findingChanges()));
						}
					}
					branchJson.releases(releaseRecordList);
					break;
				case AGGREGATED:
					commitIdToConventionalCommitMap = commitIdToRecordMap.entrySet().stream()
							.collect(Collectors.toMap(
									Entry::getKey,
									e -> resolveConventionalCommit(e.getValue().commitMessage())));
					branchJson.changes(prepareChangeRecordList(commitIdToConventionalCommitMap, commitIdToRecordMap));
					break;
			}
			branchesJsonList.add(branchJson.build());
		});
		
		projectJson.branches(branchesJsonList);
		return projectJson.build();
	}
	
	/**
	 * Builds changelog JSON for product releases.
	 * Extracts component releases from product releases and delegates to appropriate builder.
	 * 
	 * @param productRds List of product releases
	 * @param org Organization UUID
	 * @param removeLast Whether to remove the last (oldest) release from the list
	 * @param aggregationType NONE (per-component breakdown) or AGGREGATED (component-level aggregation)
	 * @param userTimeZone User's timezone for date formatting
	 * @return ComponentJsonDto containing formatted product changelog data
	 * @throws RelizaException if component data cannot be retrieved
	 */
	private ComponentJsonDto getChangeLogJsonForProductReleaseDataList(
			List<ReleaseData> productRds,
			UUID org,
			Boolean removeLast,
			AggregationType aggregationType,
			String userTimeZone) throws RelizaException {
		
		ComponentJsonDtoBuilder json = ComponentJsonDto.builder();
		
		// Extract component releases from product releases
		Map<UUID, List<UUID>> productToComponentReleaseMap = extractComponentReleaseMapping(productRds);
		
		var productRdLast = productRds.get(0);
		var productRdFirst = productRds.get(productRds.size() - 1);
		
		if (removeLast) {
			productRds.remove(productRds.size() - 1);
		}
		
		// Fetch component release data and metadata
		List<UUID> componentReleaseUuids = productToComponentReleaseMap.values().stream()
				.flatMap(List::stream)
				.distinct()
				.collect(Collectors.toList());
		
		List<ReleaseData> componentReleaseDataList = sharedReleaseService.getReleaseDataList(componentReleaseUuids, org);
		
		Map<UUID, ComponentData> projectDataMap = componentReleaseDataList.stream()
				.map(ReleaseData::getComponent)
				.distinct()
				.map(uuid -> getComponentService.getComponentData(uuid))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.collect(Collectors.toMap(ComponentData::getUuid, Function.identity()));
		
		if (aggregationType.equals(AggregationType.AGGREGATED)) {
			return buildAggregatedProductChangelog(json, productRdFirst, productRdLast, componentReleaseDataList, 
					projectDataMap, org, aggregationType, userTimeZone);
		} else {
			return buildNoneProductChangelog(json, componentReleaseDataList, projectDataMap, org, aggregationType, userTimeZone);
		}
	}
	
	/**
	 * Extract component release mapping from product releases.
	 * Maps each product release to its new component releases (excluding those in previous product releases).
	 */
	private Map<UUID, List<UUID>> extractComponentReleaseMapping(List<ReleaseData> productRds) {
		Map<UUID, List<UUID>> productToComponentReleaseMap = new HashMap<>();
		ListIterator<ReleaseData> productIterator = productRds.listIterator(productRds.size());
		List<UUID> prevParents = null;
		
		while (productIterator.hasPrevious()) {
			ReleaseData curr = productIterator.previous();
			List<UUID> parents = curr.getParentReleases().stream()
					.map(ParentRelease::getRelease)
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			
			List<UUID> originalParents = new ArrayList<>(parents);
			if (prevParents != null && !prevParents.isEmpty()) {
				parents.removeAll(prevParents);
			}
			
			productToComponentReleaseMap.put(curr.getUuid(), parents);
			prevParents = originalParents;
		}
		
		return productToComponentReleaseMap;
	}
	
	/**
	 * Build AGGREGATED mode product changelog with component-level SBOM/Finding changes.
	 */
	private ComponentJsonDto buildAggregatedProductChangelog(
			ComponentJsonDtoBuilder json,
			ReleaseData productRdFirst,
			ReleaseData productRdLast,
			List<ReleaseData> componentReleaseDataList,
			Map<UUID, ComponentData> projectDataMap,
			UUID org,
			AggregationType aggregationType,
			String userTimeZone) throws RelizaException {
		
		ComponentData pd = getComponentService.getComponentData(productRdFirst.getComponent())
			.orElseThrow(() -> new RelizaException("Product component not found: " + productRdFirst.getComponent()));
		json.org(org)
			.uuid(pd.getUuid())
			.name(pd.getName())
			.firstRelease(new ReleaseRecord(productRdFirst.getUuid(), productRdFirst.getVersion(), productRdFirst.getLifecycle(), null, null, null))
			.lastRelease(new ReleaseRecord(productRdLast.getUuid(), productRdLast.getVersion(), productRdLast.getLifecycle(), null, null, null));
		
		Map<UUID, List<ReleaseData>> groupedByComponent = componentReleaseDataList.stream()
				.collect(Collectors.groupingBy(ReleaseData::getComponent));
		
		List<ComponentJsonDto> projectList = new ArrayList<>();
		for (Map.Entry<UUID, List<ReleaseData>> entry : groupedByComponent.entrySet()) {
			List<ReleaseData> releases = entry.getValue();
			ComponentData componentData = projectDataMap.get(entry.getKey());
			BranchData bd = branchService.getBranchData(releases.get(0).getBranch())
				.orElseThrow(() -> new RelizaException("Branch not found: " + releases.get(0).getBranch()));
			releases.sort(new ReleaseData.ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
			
			ComponentJsonDto componentChangelog = getChangeLogJsonForReleaseDataList(
					releases, org, true, componentData, aggregationType, userTimeZone, null);
			
			if (releases.size() >= 2) {
				componentChangelog = computeComponentChangesForProduct(componentChangelog, releases, org, componentData);
			}
			
			projectList.add(componentChangelog);
		}
		json.components(projectList);
		
		// Aggregate per-component SBOM changes to product level
		AcollectionData.ArtifactChangelog aggregatedSbomOld = aggregateProductSbomChanges(projectList);
		// Convert to attribution format (product-level, use null for component info)
		SbomChangesWithAttribution aggregatedSbom = convertToSbomChangesWithAttribution(
			aggregatedSbomOld, null, "Product", null, null);
		json.sbomChanges(aggregatedSbom);
		
		return json.build();
	}
	
	/**
	 * Build NONE mode product changelog with per-component breakdown.
	 */
	private ComponentJsonDto buildNoneProductChangelog(
			ComponentJsonDtoBuilder json,
			List<ReleaseData> componentReleaseDataList,
			Map<UUID, ComponentData> projectDataMap,
			UUID org,
			AggregationType aggregationType,
			String userTimeZone) throws RelizaException {
		
		Map<UUID, List<ReleaseData>> groupedByComponent = componentReleaseDataList.stream()
				.collect(Collectors.groupingBy(ReleaseData::getComponent));
		
		List<ComponentJsonDto> projectList = new ArrayList<>();
		for (Map.Entry<UUID, List<ReleaseData>> entry : groupedByComponent.entrySet()) {
			List<ReleaseData> releases = entry.getValue();
			ComponentData pd = projectDataMap.get(entry.getKey());
			
			BranchData bd = branchService.getBranchData(releases.get(0).getBranch())
				.orElseThrow(() -> new RelizaException("Branch not found: " + releases.get(0).getBranch()));
			releases.sort(new ReleaseData.ReleaseVersionComparator(pd.getVersionSchema(), bd.getVersionSchema()));
			
			ComponentJsonDto componentChangelog = getChangeLogJsonForReleaseDataList(
					releases, org, true, pd, aggregationType, userTimeZone, null);
			
			if (releases.size() >= 2) {
				componentChangelog = computeComponentChangesForProduct(componentChangelog, releases, org, pd);
			}
			
			projectList.add(componentChangelog);
		}
		json.components(projectList);
		
		return json.build();
	}
	
	/**
	 * Computes SBOM and Finding changes for a component in a product changelog.
	 * Compares the first and last releases in the component's range.
	 * 
	 * @param componentChangelog Component changelog to enhance with aggregated changes
	 * @param releases List of component releases (sorted by version)
	 * @param org Organization UUID
	 * @param componentData Component metadata
	 * @return The modified ComponentJsonDto with SBOM/Finding changes added
	 * @throws RelizaException if metrics or acollections cannot be retrieved
	 */
	private ComponentJsonDto computeComponentChangesForProduct(
			ComponentJsonDto componentChangelog,
			List<ReleaseData> releases,
			UUID org,
			ComponentData componentData) throws RelizaException {
		
		ReleaseData firstReleaseData = releases.get(releases.size() - 1);
		ReleaseData lastReleaseData = releases.get(0);
		
		try {
			MetricsPair metrics = extractMetricsForReleases(firstReleaseData.getUuid(), lastReleaseData.getUuid(), org);
			List<AcollectionData> acollections = acollectionService.getAcollectionsForReleaseRange(firstReleaseData.getUuid(), lastReleaseData.getUuid());
			
			if (metrics != null) {
				FindingChangesDto.FindingChangesRecord findingChangesOld = findingComparisonService.compareMetrics(
					metrics.metrics1(), metrics.metrics2());
				FindingChangesWithAttribution findingChanges = convertToFindingChangesWithAttribution(
					findingChangesOld, componentData.getUuid(), componentData.getName(),
					firstReleaseData, lastReleaseData);
				componentChangelog.setFindingChanges(findingChanges);
			}
			
			if (acollections != null && !acollections.isEmpty()) {
				AcollectionData.ArtifactChangelog sbomChangesOld = sbomComparisonService.aggregateChangelogs(acollections);
				SbomChangesWithAttribution sbomChanges = convertToSbomChangesWithAttribution(
					sbomChangesOld, componentData.getUuid(), componentData.getName(),
					firstReleaseData, lastReleaseData);
				componentChangelog.setSbomChanges(sbomChanges);
			}
		} catch (Exception e) {
			log.error("Failed to compute SBOM/Finding changes for component {} in product changelog: {}", 
				componentData.getName(), e.getMessage(), e);
		}
		
		// Update firstRelease and lastRelease to match what we actually compared
		componentChangelog.setFirstRelease(new ReleaseRecord(
			firstReleaseData.getUuid(), firstReleaseData.getVersion(), firstReleaseData.getLifecycle(), null, null, null));
		componentChangelog.setLastRelease(new ReleaseRecord(
			lastReleaseData.getUuid(), lastReleaseData.getVersion(), lastReleaseData.getLifecycle(), null, null, null));
		
		return componentChangelog;
	}
	
	/**
	 * Aggregate SBOM changes from multiple components to product level.
	 * Deduplicates by purl and removes items that appear in both added and removed.
	 */
	private AcollectionData.ArtifactChangelog aggregateProductSbomChanges(List<ComponentJsonDto> projectList) {
		List<ArtifactWithAttribution> allArtifacts = new ArrayList<>();
		
		for (ComponentJsonDto component : projectList) {
			if (component.getSbomChanges() != null && component.getSbomChanges().artifacts() != null) {
				allArtifacts.addAll(component.getSbomChanges().artifacts());
			}
		}
		
		// Separate into added and removed based on net flags
		List<AcollectionData.DiffComponent> added = new ArrayList<>();
		List<AcollectionData.DiffComponent> removed = new ArrayList<>();
		
		for (ArtifactWithAttribution artifact : allArtifacts) {
			AcollectionData.DiffComponent diffComp = new AcollectionData.DiffComponent(
				artifact.purl(), artifact.version());
			
			if (artifact.isNetAdded()) {
				added.add(diffComp);
			} else if (artifact.isNetRemoved()) {
				removed.add(diffComp);
			}
		}
		
		// Deduplicate by purl
		Map<String, AcollectionData.DiffComponent> addedMap = added.stream()
			.collect(Collectors.toMap(AcollectionData.DiffComponent::purl, Function.identity(), (a, b) -> a));
		Map<String, AcollectionData.DiffComponent> removedMap = removed.stream()
			.collect(Collectors.toMap(AcollectionData.DiffComponent::purl, Function.identity(), (a, b) -> a));
		
		return new AcollectionData.ArtifactChangelog(
			new HashSet<>(addedMap.values()),
			new HashSet<>(removedMap.values())
		);
	}
	
	/**
	 * Adds product-level finding changes to a product changelog in AGGREGATED mode.
	 * SBOM changes are already aggregated from components, so we only compute Finding changes.
	 * 
	 * @param changelog Product changelog to enhance with finding changes
	 * @param firstUuid First (baseline) product release UUID
	 * @param lastUuid Last (comparison target) product release UUID
	 * @param orgUuid Organization UUID
	 * @throws RelizaException if metrics cannot be retrieved
	 */
	private ComponentJsonDto addProductLevelFindingChanges(
			ComponentJsonDto changelog,
			UUID firstUuid,
			UUID lastUuid,
			UUID orgUuid) throws RelizaException {
		MetricsPair metrics = extractMetricsForReleases(firstUuid, lastUuid, orgUuid);
		if (metrics != null) {
			FindingChangesDto.FindingChangesRecord findingChangesOld = findingComparisonService.compareMetrics(
				metrics.metrics1(), metrics.metrics2());
			// Convert to attribution format
			FindingChangesWithAttribution findingChanges = convertToFindingChangesWithAttribution(
				findingChangesOld, null, "Component", null, null);
			changelog.setFindingChanges(findingChanges);
		}
		return changelog;
	}
	
	/**
	 * Prepares a list of change records grouped by commit type.
	 * Separates breaking changes into their own category regardless of commit type.
	 * 
	 * @param commitIdToConventionalCommitMap Map of commit UUID to parsed conventional commit
	 * @param commitIdToRecordMap Map of commit UUID to commit metadata (author, email, URI)
	 * @return List of change records grouped by type and sorted by display priority
	 */
	private List<ChangeRecord> prepareChangeRecordList(
			Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap,
			Map<UUID, CommitRecord> commitIdToRecordMap) {
		
		List<ChangeRecord> changeRecordList = new ArrayList<>();
		Map<UUID, ConventionalCommit> filteredCommitMap;
		
		for (CommitType commitType : CommitType.values()) {
			filteredCommitMap = commitIdToConventionalCommitMap.entrySet().stream()
					.filter(entry -> entry.getValue().getType() == commitType && !entry.getValue().isBreakingChange())
					.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
			
			if (!filteredCommitMap.isEmpty())
				changeRecordList.add(new ChangeRecord(commitType.getFullName(),
						prepareCommitMessageRecordList(filteredCommitMap, commitIdToRecordMap)));
		}
		
		filteredCommitMap = commitIdToConventionalCommitMap.entrySet().stream()
				.filter(entry -> entry.getValue().isBreakingChange())
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		
		if (!filteredCommitMap.isEmpty())
			changeRecordList.add(new ChangeRecord("BREAKING CHANGES",
					prepareCommitMessageRecordList(filteredCommitMap, commitIdToRecordMap)));
		
		return changeRecordList;
	}
	
	/**
	 * Converts conventional commits into commit message records with metadata.
	 * Enriches commits with author information and linkified commit messages.
	 * 
	 * @param commitIdToConventionalCommitMap Map of commit UUID to parsed conventional commit
	 * @param commitIdToRecordMap Map of commit UUID to commit metadata (author, email, URI)
	 * @return List of commit message records with full metadata
	 */
	private List<CommitMessageRecord> prepareCommitMessageRecordList(
			Map<UUID, ConventionalCommit> commitIdToConventionalCommitMap,
			Map<UUID, CommitRecord> commitIdToRecordMap) {
		
		List<CommitMessageRecord> commitRecords = new ArrayList<>();
		
		for (UUID commitId : commitIdToConventionalCommitMap.keySet()) {
			String commitAuthor = null;
			String commitEmail = null;
			String linkifiedText = null;
			String rawText = commitIdToConventionalCommitMap.get(commitId).getMessage();
			
			CommitRecord commitRecord = commitIdToRecordMap.get(commitId);
			if (StringUtils.isNotEmpty(commitRecord.commitUri()) && StringUtils.isNotEmpty(commitRecord.commitId())) {
				linkifiedText = Utils.linkifyCommit(commitRecord.commitUri(), commitRecord.commitId());
			}
			
			if (StringUtils.isNotEmpty(commitRecord.commitAuthor()) && StringUtils.isNotEmpty(commitRecord.commitEmail())) {
				commitAuthor = commitRecord.commitAuthor();
				commitEmail = commitRecord.commitEmail();
			}
			
			commitRecords.add(new CommitMessageRecord(linkifiedText, rawText, commitAuthor, commitEmail));
		}
		
		return commitRecords;
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
	
	
	/**
	 * Helper method to build component changelog without code changes.
	 * Similar to getChangeLogJsonForReleaseDataList but excludes code changes.
	 */
	private ComponentJsonDto buildComponentChangelogWithoutCode(
			List<ReleaseData> releases,
			UUID orgUuid,
			ComponentData component,
			AggregationType aggregationType,
			String timeZone,
			Map<UUID, ReleaseChanges> perReleaseChanges) {
		
		// Group releases by branch
		Map<UUID, List<ReleaseData>> releasesByBranch = releases.stream()
			.collect(Collectors.groupingBy(ReleaseData::getBranch));
		
		List<ComponentJsonDto> branchRecords = new ArrayList<>();
		
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			
			Optional<BranchData> branchDataOpt = branchService.getBranchData(branchUuid);
			if (branchDataOpt.isEmpty()) {
				continue;
			}
			BranchData branchData = branchDataOpt.get();
			
			if (aggregationType == AggregationType.NONE) {
				// Build per-release records without code changes
				List<ReleaseRecord> releaseRecords = branchReleases.stream()
					.map(rd -> {
						ReleaseChanges changes = perReleaseChanges.get(rd.getUuid());
						return new ReleaseRecord(
							rd.getUuid(),
							rd.getVersion(),
							rd.getLifecycle(),
							null,  // No code changes
							changes != null ? changes.sbomChanges() : null,
							changes != null ? changes.findingChanges() : null
						);
					})
					.collect(Collectors.toList());
				
				branchRecords.add(ComponentJsonDto.builder()
					.uuid(branchUuid)
					.name(branchData.getName())
					.releases(releaseRecords)
					.build());
			} else {
				// AGGREGATED mode - aggregate at branch level
				try {
					List<AcollectionData> branchAcollections = new ArrayList<>();
					for (ReleaseData rd : branchReleases) {
						List<AcollectionData> releaseAcollections = acollectionService.getAcollectionsForReleaseRange(
							rd.getUuid(), rd.getUuid());
						if (releaseAcollections != null) {
							branchAcollections.addAll(releaseAcollections);
						}
					}
					
					AcollectionData.ArtifactChangelog branchSbomChanges = 
						sbomComparisonService.aggregateChangelogs(branchAcollections);
					
					// For findings, get metrics for first and last release
					FindingChangesDto.FindingChangesRecord branchFindingChanges = null;
					if (branchReleases.size() >= 2) {
						ReleaseData firstRelease = branchReleases.get(branchReleases.size() - 1);
						ReleaseData lastRelease = branchReleases.get(0);
						
						MetricsPair metrics = extractMetricsForReleases(firstRelease.getUuid(), lastRelease.getUuid(), orgUuid);
						
						branchFindingChanges = (metrics == null)
							? findingComparisonService.emptyFindingChanges()
							: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
					}
					
					// Convert to attribution format
					ReleaseData branchFirst = branchReleases.isEmpty() ? null : branchReleases.get(branchReleases.size() - 1);
					ReleaseData branchLast = branchReleases.isEmpty() ? null : branchReleases.get(0);
					SbomChangesWithAttribution branchSbomChangesAttr = convertToSbomChangesWithAttribution(
						branchSbomChanges, component.getUuid(), component.getName(),
						branchLast, branchFirst);
					FindingChangesWithAttribution branchFindingChangesAttr = convertToFindingChangesWithAttribution(
						branchFindingChanges, component.getUuid(), component.getName(),
						branchLast, branchFirst);
					
					branchRecords.add(ComponentJsonDto.builder()
						.uuid(branchUuid)
						.name(branchData.getName())
						.sbomChanges(branchSbomChangesAttr)
						.findingChanges(branchFindingChangesAttr)
						.build());
				} catch (Exception e) {
					log.error("Error aggregating branch changes: {}", e.getMessage());
				}
			}
		}
		
		return ComponentJsonDto.builder()
			.uuid(component.getUuid())
			.name(component.getName())
			.org(orgUuid)
			.branches(branchRecords)
			.build();
	}
	
	/**
	 * Convert old FindingChangesRecord to FindingChangesWithAttribution.
	 * For component-level changelogs, creates simple attribution with single component.
	 */
	private FindingChangesWithAttribution convertToFindingChangesWithAttribution(
			FindingChangesDto.FindingChangesRecord findingChanges,
			UUID componentUuid,
			String componentName,
			ReleaseData currentRelease,
			ReleaseData previousRelease) {
		
		if (findingChanges == null) {
			return new FindingChangesWithAttribution(
				List.of(), List.of(), List.of(),
				0, 0
			);
		}
		
		// Create component attribution for current release
		ComponentAttribution currentAttr = new ComponentAttribution(
			componentUuid,
			componentName,
			currentRelease != null ? currentRelease.getUuid() : null,
			currentRelease != null ? currentRelease.getVersion() : null,
			currentRelease != null ? currentRelease.getBranch() : null,
			currentRelease != null ? getBranchName(currentRelease.getBranch()) : null,
			previousRelease != null ? previousRelease.getVersion() : null
		);
		
		// Convert vulnerabilities
		List<VulnerabilityWithAttribution> vulnerabilities = new ArrayList<>();
		if (findingChanges.appearedVulnerabilities() != null) {
			for (var vuln : findingChanges.appearedVulnerabilities()) {
				vulnerabilities.add(new VulnerabilityWithAttribution(
					vuln.vulnId(),
					vuln.severity() != null ? vuln.severity().toString() : null,
					vuln.purl(),
					List.of(),  // resolvedIn
					List.of(currentAttr),  // appearedIn
					List.of(currentAttr),  // stillPresentIn
					false,  // isNetResolved
					true,   // isNetAppeared
					true,   // isStillPresent
					null    // orgContext not applicable for single-release view
				));
			}
		}
		if (findingChanges.resolvedVulnerabilities() != null) {
			for (var vuln : findingChanges.resolvedVulnerabilities()) {
				vulnerabilities.add(new VulnerabilityWithAttribution(
					vuln.vulnId(),
					vuln.severity() != null ? vuln.severity().toString() : null,
					vuln.purl(),
					List.of(currentAttr),  // resolvedIn
					List.of(),  // appearedIn
					List.of(),  // stillPresentIn
					true,   // isNetResolved
					false,  // isNetAppeared
					false,  // isStillPresent
					null    // orgContext not applicable for single-release view
				));
			}
		}
		
		// Convert violations
		List<ViolationWithAttribution> violations = new ArrayList<>();
		if (findingChanges.appearedViolations() != null) {
			for (var violation : findingChanges.appearedViolations()) {
				violations.add(new ViolationWithAttribution(
					violation.type() != null ? violation.type().toString() : null,
					violation.purl(),
					List.of(),  // resolvedIn
					List.of(currentAttr),  // appearedIn
					List.of(currentAttr),  // stillPresentIn
					false,  // isNetResolved
					true,   // isNetAppeared
					true,   // isStillPresent
					null    // orgContext not applicable for single-release view
				));
			}
		}
		if (findingChanges.resolvedViolations() != null) {
			for (var violation : findingChanges.resolvedViolations()) {
				violations.add(new ViolationWithAttribution(
					violation.type() != null ? violation.type().toString() : null,
					violation.purl(),
					List.of(currentAttr),  // resolvedIn
					List.of(),  // appearedIn
					List.of(),  // stillPresentIn
					true,   // isNetResolved
					false,  // isNetAppeared
					false,  // isStillPresent
					null    // orgContext not applicable for single-release view
				));
			}
		}
		
		// Convert weaknesses
		List<WeaknessWithAttribution> weaknesses = new ArrayList<>();
		if (findingChanges.appearedWeaknesses() != null) {
			for (var weakness : findingChanges.appearedWeaknesses()) {
				weaknesses.add(new WeaknessWithAttribution(
					weakness.cweId(),
					weakness.severity() != null ? weakness.severity().toString() : "UNKNOWN",
					weakness.location() != null ? weakness.location() : "",
					List.of(),  // resolvedIn
					List.of(currentAttr),  // appearedIn
					List.of(currentAttr),  // stillPresentIn
					false,  // isNetResolved
					true,   // isNetAppeared
					true,   // isStillPresent
					null    // orgContext not applicable for single-release view
				));
			}
		}
		if (findingChanges.resolvedWeaknesses() != null) {
			for (var weakness : findingChanges.resolvedWeaknesses()) {
				weaknesses.add(new WeaknessWithAttribution(
					weakness.cweId(),
					weakness.severity() != null ? weakness.severity().toString() : "UNKNOWN",
					weakness.location() != null ? weakness.location() : "",
					List.of(currentAttr),  // resolvedIn
					List.of(),  // appearedIn
					List.of(),  // stillPresentIn
					true,   // isNetResolved
					false,  // isNetAppeared
					false,  // isStillPresent
					null    // orgContext not applicable for single-release view
				));
			}
		}
		
		// Calculate totals from summary
		int totalAppeared = findingChanges.summary().totalAppearedCount();
		int totalResolved = findingChanges.summary().totalResolvedCount();
		
		return new FindingChangesWithAttribution(
			vulnerabilities,
			violations,
			weaknesses,
			totalAppeared,
			totalResolved
		);
	}
	
	/**
	 * Convert old ArtifactChangelog to SbomChangesWithAttribution.
	 * For component-level changelogs, creates simple attribution with single component.
	 */
	private SbomChangesWithAttribution convertToSbomChangesWithAttribution(
			AcollectionData.ArtifactChangelog sbomChanges,
			UUID componentUuid,
			String componentName,
			ReleaseData currentRelease,
			ReleaseData previousRelease) {
		
		if (sbomChanges == null) {
			return new SbomChangesWithAttribution(List.of(), 0, 0);
		}
		
		// Create component attribution for current release
		ComponentAttribution currentAttr = new ComponentAttribution(
			componentUuid,
			componentName,
			currentRelease != null ? currentRelease.getUuid() : null,
			currentRelease != null ? currentRelease.getVersion() : null,
			currentRelease != null ? currentRelease.getBranch() : null,
			currentRelease != null ? getBranchName(currentRelease.getBranch()) : null,
			previousRelease != null ? previousRelease.getVersion() : null
		);
		
		List<ArtifactWithAttribution> artifacts = new ArrayList<>();
		
		// Convert added artifacts
		if (sbomChanges.added() != null) {
			for (var artifact : sbomChanges.added()) {
				artifacts.add(new ArtifactWithAttribution(
					artifact.purl(),
					null,  // name - not available in DiffComponent
					artifact.version(),
					List.of(currentAttr),  // addedIn
					List.of(),  // removedIn
					true,   // isNetAdded
					false   // isNetRemoved
				));
			}
		}
		
		// Convert removed artifacts
		if (sbomChanges.removed() != null) {
			for (var artifact : sbomChanges.removed()) {
				artifacts.add(new ArtifactWithAttribution(
					artifact.purl(),
					null,  // name - not available in DiffComponent
					artifact.version(),
					List.of(),  // addedIn
					List.of(currentAttr),  // removedIn
					false,  // isNetAdded
					true    // isNetRemoved
				));
			}
		}
		
		int totalAdded = sbomChanges.added() != null ? sbomChanges.added().size() : 0;
		int totalRemoved = sbomChanges.removed() != null ? sbomChanges.removed().size() : 0;
		
		return new SbomChangesWithAttribution(artifacts, totalAdded, totalRemoved);
	}
	
	/**
	 * Helper to get branch name from UUID
	 */
	private String getBranchName(UUID branchUuid) {
		if (branchUuid == null) return null;
		try {
			Optional<BranchData> branchOpt = branchService.getBranchData(branchUuid);
			return branchOpt.map(BranchData::getName).orElse(null);
		} catch (Exception e) {
			log.warn("Could not retrieve branch name for UUID: {}", branchUuid);
			return null;
		}
	}
}
