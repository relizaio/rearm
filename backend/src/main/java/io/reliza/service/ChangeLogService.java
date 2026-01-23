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
	 * Helper record to hold a pair of metrics for comparison
	 */
	private record MetricsPair(ReleaseMetricsDto metrics1, ReleaseMetricsDto metrics2) {}
	
	/**
	 * Helper record to hold pre-computed SBOM and Finding changes for a release.
	 * Used when building changelog in NONE aggregation mode.
	 */
	private record ReleaseChanges(
		AcollectionData.ArtifactChangelog sbomChanges,
		FindingChangesDto.FindingChangesRecord findingChanges
	) {
		/**
		 * Creates an empty ReleaseChanges (for releases with no comparison)
		 */
		public static ReleaseChanges empty() {
			return new ReleaseChanges(null, null);
		}
	}

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
	
	// ========== Delegation Methods to Specialized Services ==========
	
	/**
	 * Compares findings between two releases.
	 * Delegates to FindingComparisonService.
	 */
	public FindingChangesDto.FindingChangesRecord compareFindingChanges(
			UUID release1Uuid,
			UUID release2Uuid,
			UUID orgUuid) throws RelizaException {
		return findingComparisonService.compareFindingChanges(release1Uuid, release2Uuid, orgUuid);
	}
	
	/**
	 * Compares findings for a component between two dates.
	 * Delegates to FindingComparisonService.
	 */
	public FindingChangesDto.FindingChangesRecord compareFindingChangesByDate(
			UUID componentUuid,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo) throws RelizaException {
		return findingComparisonService.compareFindingChangesByDate(componentUuid, dateFrom, dateTo);
	}
	
	/**
	 * Aggregates SBOM changes between two releases.
	 * Delegates to SbomComparisonService.
	 */
	public AcollectionData.ArtifactChangelog aggregateSbomChanges(
			UUID release1Uuid,
			UUID release2Uuid,
			UUID orgUuid) throws RelizaException {
		return sbomComparisonService.aggregateSbomChanges(release1Uuid, release2Uuid, orgUuid);
	}
	
	/**
	 * Compares SBOM changes for a component between two dates.
	 * Delegates to SbomComparisonService.
	 */
	public AcollectionData.ArtifactChangelog compareSbomChangesByDate(
			UUID componentUuid,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo) {
		return sbomComparisonService.compareSbomChangesByDate(componentUuid, dateFrom, dateTo);
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
	 * Computes SBOM and Finding changes for each release compared to the previous release.
	 * This is used for NONE aggregation mode to show per-release changes.
	 * 
	 * Each release is compared to its immediate predecessor (releases are sorted newest first).
	 * 
	 * @param releases List of releases (sorted newest first)
	 * @param orgUuid Organization UUID
	 * @return Map of release UUID to pre-computed changes (SBOM and Finding deltas)
	 */
	private Map<UUID, ReleaseChanges> computePerReleaseChanges(
			List<ReleaseData> releases, 
			UUID orgUuid) throws RelizaException {
		
		Map<UUID, ReleaseChanges> changesMap = new HashMap<>();
		
		if (releases == null || releases.size() < 2) {
			return changesMap;
		}
				
		// Compare each release to the previous one (releases are sorted newest first)
		// Release at index i is compared to release at index i+1
		for (int i = 0; i < releases.size() - 1; i++) {
			ReleaseData currentRelease = releases.get(i);
			ReleaseData previousRelease = releases.get(i + 1);
			
			UUID currentUuid = currentRelease.getUuid();
			UUID previousUuid = previousRelease.getUuid();
			
		
			try {
				// Extract data for this release pair
				MetricsPair metrics = extractMetricsForReleases(previousUuid, currentUuid, orgUuid);
				List<AcollectionData> acollections = acollectionService.getAcollectionsForReleaseRange(previousUuid, currentUuid);
				
				FindingChangesDto.FindingChangesRecord findingChanges = (metrics == null) 
					? findingComparisonService.emptyFindingChanges()
					: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
				
				AcollectionData.ArtifactChangelog sbomChanges = 
					sbomComparisonService.aggregateChangelogs(acollections);
				
				// Store results
				ReleaseChanges changes = new ReleaseChanges(sbomChanges, findingChanges);
				changesMap.put(currentUuid, changes);

				
			} catch (Exception e) {
				log.error("Error comparing release {} to {}: {}", 
					previousUuid, currentUuid, e.getMessage(), e);
				// Store empty changes on error
				changesMap.put(currentUuid, ReleaseChanges.empty());
			}
		}
		
		return changesMap;
	}
	
	// ========== Changelog Building Methods ==========
	
	/**
	 * Gets changelog between two releases.
	 * Always includes CODE, SBOM, and Finding changes.
	 * Use the aggregated parameter to control whether changes are shown per-release (NONE) or aggregated (AGGREGATED).
	 */
	public ComponentJsonDto getChangelogBetweenReleases(
			UUID uuid1,
			UUID uuid2,
			UUID org,
			AggregationType aggregated,
			String userTimeZone) throws RelizaException {
		
		ComponentJsonDto changelog = null;
		List<ReleaseData> rds = sharedReleaseService.listAllReleasesBetweenReleases(uuid1, uuid2);
		
		if (rds.size() > 0) {
			ReleaseData rd = rds.get(0);
			if (rd != null) {
				Optional<ComponentData> opd = getComponentService.getComponentData(rd.getComponent());
				ComponentData pd = opd.orElseThrow(() -> 
					new RelizaException("Component not found: " + rd.getComponent()));
				
				// Compute per-release changes if NONE mode
				Map<UUID, ReleaseChanges> perReleaseChanges = null;
				if (aggregated == AggregationType.NONE) {
					perReleaseChanges = computePerReleaseChanges(rds, org);
				}
				
				if (pd.getType().equals(ComponentType.COMPONENT)) {
					changelog = getChangeLogJsonForReleaseDataList(rds, org, true, pd, aggregated, userTimeZone, perReleaseChanges);
				} else {
					changelog = getChangeLogJsonForProductReleaseDataList(rds, org, true, aggregated, userTimeZone);
				}
				
				// Add top-level SBOM and finding changes in AGGREGATED mode
				if (changelog != null && aggregated == AggregationType.AGGREGATED) {
					long startTime = System.currentTimeMillis();

					MetricsPair metrics = extractMetricsForReleases(uuid1, uuid2, org);
					List<AcollectionData> acollections = acollectionService.getAcollectionsForReleaseRange(uuid1, uuid2);
					
					// Sequential comparison
					AcollectionData.ArtifactChangelog sbomChanges = 
						sbomComparisonService.aggregateChangelogs(acollections);
					
					FindingChangesDto.FindingChangesRecord findingChanges = (metrics == null)
						? findingComparisonService.emptyFindingChanges()
						: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
					
					// For products, SBOM changes are already aggregated from components - don't overwrite
					if (!pd.getType().equals(ComponentType.PRODUCT)) {
						changelog.setSbomChanges(sbomChanges);
					}
					changelog.setFindingChanges(findingChanges);
					
					log.info("AGGREGATED changelog comparison completed in {}ms for releases {} -> {}",
							System.currentTimeMillis() - startTime, uuid1, uuid2);
				}
			}
		}
		
		return changelog;
	}
	
	/**
	 * Gets component changelog by date range.
	 * Aggregates releases from all active branches of the component within the specified date range.
	 */
	public ComponentJsonDto getComponentChangeLogByDate(
			UUID componentUuid,
			UUID orgUuid,
			ZonedDateTime dateFrom,
			ZonedDateTime dateTo,
			AggregationType aggregated,
			String timeZone) throws RelizaException {
		
		// Get component data
		Optional<ComponentData> opd = getComponentService.getComponentData(componentUuid);
		if (opd.isEmpty()) {
			log.warn("Component not found: {}", componentUuid);
			return null;
		}
		ComponentData pd = opd.get();
		
		// Get all active branches for this component
		List<BranchData> branches = branchService.listBranchDataOfComponent(componentUuid, null);
		if (branches == null || branches.isEmpty()) {
			log.warn("No branches found for component: {}", componentUuid);
			return null;
		}
		
		// Collect releases from all branches within the date range
		List<ReleaseData> allReleases = new ArrayList<>();
		for (BranchData branch : branches) {
			List<ReleaseData> branchReleases = sharedReleaseService.listReleaseDataOfBranchBetweenDates(
					branch.getUuid(), 
					dateFrom, 
					dateTo, 
					ReleaseLifecycle.DRAFT);
			if (branchReleases != null && !branchReleases.isEmpty()) {
				allReleases.addAll(branchReleases);
			}
		}
		
		if (allReleases.isEmpty()) {
			log.info("No releases found for component {} between {} and {}", componentUuid, dateFrom, dateTo);
			return null;
		}
		
		// Sort releases by creation date (most recent first)
		allReleases.sort((r1, r2) -> r2.getCreatedDate().compareTo(r1.getCreatedDate()));
		
		// Compute per-release changes if NONE mode
		Map<UUID, ReleaseChanges> perReleaseChanges = null;
		if (aggregated == AggregationType.NONE) {
			perReleaseChanges = computePerReleaseChanges(allReleases, orgUuid);
		}
		
		// Build changelog JSON using the existing helper method
		ComponentJsonDto changelog = getChangeLogJsonForReleaseDataList(
				allReleases, 
				orgUuid, 
				false,  // Don't remove last element for date-based queries
				pd, 
				aggregated, 
				timeZone,
				perReleaseChanges);
		
		// Add top-level SBOM and finding changes in AGGREGATED mode
		if (changelog != null && aggregated == AggregationType.AGGREGATED) {
			long startTime = System.currentTimeMillis();

			try {
				Optional<AnalyticsMetricsService.MetricsPair> metricsOpt = analyticsMetricsService.getMetricsPairForDateRange(componentUuid, dateFrom, dateTo);
				List<AcollectionData> acollections = acollectionService.getAcollectionsForDateRange(componentUuid, dateFrom, dateTo);
				
				// Sequential comparison
				AcollectionData.ArtifactChangelog sbomChanges = sbomComparisonService.aggregateChangelogs(acollections);
				
				FindingChangesDto.FindingChangesRecord findingChanges = metricsOpt.isEmpty()
					? findingComparisonService.emptyFindingChanges()
					: findingComparisonService.compareMetrics(metricsOpt.get().metrics1(), metricsOpt.get().metrics2());
				
				changelog.setSbomChanges(sbomChanges);
				changelog.setFindingChanges(findingChanges);
				
				log.info("Date-based changelog comparison completed in {}ms for component {} ({} to {})",
						System.currentTimeMillis() - startTime, componentUuid, dateFrom, dateTo);
			} catch (Exception e) {
				log.error("Error computing date-based aggregated changes: {}", e.getMessage(), e);
			}
		}
		
		return changelog;
	}
	
	/**
	 * Gets component changelog for a branch.
	 */
	public ComponentJsonDto getComponentChangeLog(
			UUID branchUuid,
			UUID orgUuid,
			AggregationType aggregated,
			String timeZone) throws RelizaException {
		
		ComponentJsonDto changelog = null;
		List<ReleaseData> releases = sharedReleaseService.listReleaseDataOfBranch(branchUuid, true);
		
		if (releases != null && !releases.isEmpty()) {
			ReleaseData rd = releases.get(0);
			if (rd != null) {
				Optional<ComponentData> opd = getComponentService.getComponentData(rd.getComponent());
				ComponentData pd = opd.orElseThrow(() -> new RelizaException("Component not found: " + rd.getComponent()));
				
				Map<UUID, ReleaseChanges> perReleaseChanges = null;
				
				// Compute per-release changes for NONE mode
				if (aggregated == AggregationType.NONE && releases.size() >= 2) {
					perReleaseChanges = computePerReleaseChanges(releases, orgUuid);
				}
				
				// Build changelog JSON with pre-computed data
				changelog = getChangeLogJsonForReleaseDataList(
					releases, orgUuid, false, pd, aggregated, timeZone, perReleaseChanges);
				
				// Add top-level aggregated changes for AGGREGATED mode
				if (aggregated == AggregationType.AGGREGATED && releases.size() >= 2) {
					try {
						UUID firstUuid = releases.get(releases.size() - 1).getUuid();
						UUID lastUuid = releases.get(0).getUuid();
						
						MetricsPair metrics = extractMetricsForReleases(firstUuid, lastUuid, orgUuid);
						List<AcollectionData> acollections = 
							acollectionService.getAcollectionsForReleaseRange(firstUuid, lastUuid);
						
						// Sequential comparison
						FindingChangesDto.FindingChangesRecord findingChanges = (metrics == null)
							? findingComparisonService.emptyFindingChanges()
							: findingComparisonService.compareMetrics(metrics.metrics1(), metrics.metrics2());
						
						AcollectionData.ArtifactChangelog sbomChanges = 
							sbomComparisonService.aggregateChangelogs(acollections);
						
						changelog.setFindingChanges(findingChanges);
						changelog.setSbomChanges(sbomChanges);
					} catch (Exception e) {
						log.error("Error computing aggregated changes for branch changelog: {}", e.getMessage(), e);
					}
				}
			}
		}
		
		return changelog;
	}
	
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
		ComponentData pd = opd.get();
		
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
			log.info("No product releases found for product {} between {} and {}", productUuid, dateFrom, dateTo);
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
	
	// ========== Helper Methods for JSON Building ==========
	
	/**
	 * Builds changelog JSON for component releases.
	 * Groups releases by branch and formats commit messages according to aggregation type.
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
		AcollectionData.ArtifactChangelog aggregatedSbom = aggregateProductSbomChanges(projectList);
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
				FindingChangesDto.FindingChangesRecord findingChanges = findingComparisonService.compareMetrics(
					metrics.metrics1(), metrics.metrics2());
				componentChangelog.setFindingChanges(findingChanges);
			}
			
			if (acollections != null && !acollections.isEmpty()) {
				AcollectionData.ArtifactChangelog sbomChanges = sbomComparisonService.aggregateChangelogs(acollections);
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
		List<AcollectionData.DiffComponent> allAdded = new ArrayList<>();
		List<AcollectionData.DiffComponent> allRemoved = new ArrayList<>();
		
		for (ComponentJsonDto component : projectList) {
			if (component.getSbomChanges() != null) {
				if (component.getSbomChanges().added() != null) {
					allAdded.addAll(component.getSbomChanges().added());
				}
				if (component.getSbomChanges().removed() != null) {
					allRemoved.addAll(component.getSbomChanges().removed());
				}
			}
		}
		
		// Deduplicate by purl
		Map<String, AcollectionData.DiffComponent> addedMap = allAdded.stream()
			.collect(Collectors.toMap(AcollectionData.DiffComponent::purl, Function.identity(), (a, b) -> a));
		Map<String, AcollectionData.DiffComponent> removedMap = allRemoved.stream()
			.collect(Collectors.toMap(AcollectionData.DiffComponent::purl, Function.identity(), (a, b) -> a));
		
		// Remove items that appear in both added and removed (version changes within components)
		Set<String> commonPurls = new HashSet<>(addedMap.keySet());
		commonPurls.retainAll(removedMap.keySet());
		commonPurls.forEach(purl -> {
			addedMap.remove(purl);
			removedMap.remove(purl);
		});
		
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
			FindingChangesDto.FindingChangesRecord findingChanges = findingComparisonService.compareMetrics(
				metrics.metrics1(), metrics.metrics2());
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
}
