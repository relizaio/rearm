/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.reliza.common.Utils;
import io.reliza.model.AnalysisState;
import io.reliza.model.FindingChangeEvent;
import static io.reliza.dto.ChangelogRecords.UNKNOWN_SEVERITY;
import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.dto.ChangelogRecords.MetricsRevisionFindingChange;
import io.reliza.dto.ChangelogRecords.ReleaseViolationInfo;
import io.reliza.dto.ChangelogRecords.ReleaseVulnerabilityInfo;
import io.reliza.dto.ChangelogRecords.ReleaseWeaknessInfo;
import io.reliza.dto.ComponentAttribution;
import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.HistoricallyResolvedFinding;
import io.reliza.dto.OrgLevelContext;
import io.reliza.dto.ViolationWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.dto.WeaknessWithAttribution;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.dto.FindingChangesRecord;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;
import io.reliza.repositories.MetricsAuditRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service responsible for comparing findings (vulnerabilities, violations, weaknesses)
 * between releases or date ranges.
 * 
 * This service follows the Single Responsibility Principle by focusing solely on
 * finding comparison logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FindingComparisonService {
	
	private final BranchService branchService;
	private final SharedReleaseService sharedReleaseService;
	private final GetComponentService getComponentService;
	private final MetricsAuditRepository metricsAuditRepository;
	private final GetOrganizationService getOrganizationService;

	// The branch-grain v3 store is the SOLE finding-change persistence (v1/v2 dropped in V64). Field-injected
	// (not in the @RequiredArgsConstructor) so unit tests that `new` this service with the core deps and no
	// backfill service get an empty read (readFindingChangesInRange returns empty when the service is absent).
	@Autowired(required = false)
	private FindingDimBackfillService findingDimBackfillService;

	private static final String FINDING_KEY_DELIMITER = "|";

	/**
	 * READ SEAM: returns the finding-change events for a window from the SOLE store -- the branch-grain v3
	 * ("events-lite") table hydrated into transient {@link FindingChangeEvent}s. The reconstruction engine
	 * groups by {@code ev.getReleaseUuid()}; {@code hydrateInRangeV3} stitches each v3 fact with
	 * {@code releaseUuid = first_release_uuid}, so the engine is UNCHANGED -- the same release-keyed replay,
	 * just fewer rows (inherited APPEARED dropped). {@code releaseUuids} are matched against
	 * {@code first_release_uuid}. A hydration failure logs ERROR and returns empty for the window
	 * (investigate the dangling dim); a unit test that constructs this service without a
	 * {@link FindingDimBackfillService} likewise reads empty.
	 */
	private List<FindingChangeEvent> readFindingChangesInRange(UUID org, Collection<UUID> releaseUuids,
			ZonedDateTime from, ZonedDateTime to) {
		if (findingDimBackfillService == null || org == null) {
			return List.of();
		}
		try {
			return findingDimBackfillService.hydrateInRangeV3(org, releaseUuids, from, to);
		} catch (RuntimeException e) {
			log.error("finding_change_events v3 read failed for org {} -- returning empty for this window "
					+ "(investigate the dangling dim/hydration failure)", org, e);
			return List.of();
		}
	}

	/**
	 * Returns branch name from cache, or fetches and caches it.
	 */
	private String cachedBranchName(UUID branchUuid, Map<UUID, String> cache) {
		return cache.computeIfAbsent(branchUuid, branchService::getBranchName);
	}
	
	// Reusable null-safe key extractors for each finding type.
	// FROZEN CONTRACT (board task #38 normalization): finding_key is a hash INPUT to the finding_dim
	// dimension key (see FindingDimKey). Changing any of these three expressions -- the format, the
	// null-fallbacks, the delimiter, or the fields used -- silently RE-KEYS every finding_dim and
	// breaks cross-producer dedup. Do NOT alter them without bumping FindingDimKey.KEY_VERSION and
	// coordinating a re-key; the FindingDimKeyTest golden vectors guard the resulting digests.
	private static final Function<VulnerabilityDto, String> VULN_KEY =
		vuln -> vuln.vulnId() + FINDING_KEY_DELIMITER + (vuln.purl() != null ? vuln.purl() : "");
	private static final Function<ViolationDto, String> VIOLATION_KEY =
		violation -> violation.type() + FINDING_KEY_DELIMITER + (violation.purl() != null ? violation.purl() : "");
	private static final Function<WeaknessDto, String> WEAKNESS_KEY =
		weakness -> (weakness.cweId() != null ? weakness.cweId() : weakness.ruleId()) + FINDING_KEY_DELIMITER + (weakness.location() != null ? weakness.location() : "");
	
	/**
	 * Shared comparator that reads metrics directly from ReleaseData objects.
	 * Used by both component-level and org-level finding comparison methods.
	 */
	private final BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> DIRECT_METRICS_COMPARATOR =
		(older, newer) -> {
			ReleaseMetricsDto olderMetrics = older.getMetrics();
			ReleaseMetricsDto newerMetrics = newer.getMetrics();
			return (olderMetrics != null && newerMetrics != null) ? compareMetrics(olderMetrics, newerMetrics) : null;
		};
	
	/**
	 * Generic comparison result holder
	 */
	private record ComparisonResult<T>(
		List<T> appeared,
		List<T> resolved
	) {}
	
	/**
	 * Generic comparison method for finding lists.
	 * Eliminates code duplication across vulnerability, violation, and weakness comparisons.
	 */
	private <T> ComparisonResult<T> compareFindings(
			List<T> list1,
			List<T> list2,
			Function<T, String> keyExtractor) {
		
		List<T> appeared = new ArrayList<>();
		List<T> resolved = new ArrayList<>();
		
		// Build maps by key
		Map<String, T> map1 = new HashMap<>();
		Map<String, T> map2 = new HashMap<>();
		
		if (list1 != null) {
			for (T item : list1) {
				map1.put(keyExtractor.apply(item), item);
			}
		}
		
		if (list2 != null) {
			for (T item : list2) {
				map2.put(keyExtractor.apply(item), item);
			}
		}
		
		// Find appeared (in list2 but not in list1)
		for (Map.Entry<String, T> entry : map2.entrySet()) {
			if (!map1.containsKey(entry.getKey())) {
				appeared.add(entry.getValue());
			}
		}
		
		// Find resolved (in list1 but not in list2)
		for (Map.Entry<String, T> entry : map1.entrySet()) {
			if (!map2.containsKey(entry.getKey())) {
				resolved.add(entry.getValue());
			}
		}
		
		return new ComparisonResult<>(appeared, resolved);
	}
	
	

	/**
	 * Per-finding attribution accumulator. The three {@code *In} lists are PREVIEW-CAPPED at
	 * {@link #ATTRIBUTION_PREVIEW_CAP}: they are the only attribution serialized to the client (the "inline
	 * but capped" changelog read contract -- see ai-agents/changelog-read-contract-redesign.md). The exact
	 * per-bucket totals, the component-level aggregates, and the present-release membership needed to compute
	 * flags/orgContext are kept in full via the release/component sets below (bounded by #releases/#components,
	 * never serialized). Historically these were full {@code List<ComponentAttribution>} -- a single org
	 * changelog materialized (and serialized) hundreds of entries per finding, dominating payload (91% on a
	 * heavy org) and driving an O(N^2) presentIn dedup scan. Dedup is now O(1) via the release sets.
	 */
	private static class FindingAttribution<T> {
		T finding;
		// Materialization cap for the preview lists. Default = ATTRIBUTION_PREVIEW_CAP (the normal changelog
		// read). The drill-down (findingAttributionByDate) runs a single-key filtered build with cap = MAX to
		// materialize the FULL list for exactly one finding (bounded memory: one finding, not the whole org).
		private final int cap;
		// Preview lists: <= cap entries, the only attribution SERIALIZED to the client.
		final List<ComponentAttribution> appearedIn = new ArrayList<>();
		final List<ComponentAttribution> resolvedIn = new ArrayList<>();
		final List<ComponentAttribution> presentIn = new ArrayList<>();
		// Per-bucket dedup sets (by releaseUuid) -> exact totals + O(1) dedup (replaces the old O(N^2) scan).
		private final Set<UUID> appearedReleases = new HashSet<>();
		private final Set<UUID> resolvedReleases = new HashSet<>();
		private final Set<UUID> presentReleases = new HashSet<>();
		// Component-level aggregates for org flags/context (bounded by #components; never serialized).
		final Set<UUID> appearedComponents = new HashSet<>();
		final Set<UUID> resolvedComponents = new HashSet<>();
		final Set<UUID> presentComponents = new HashSet<>();
		final LinkedHashSet<String> presentComponentNames = new LinkedHashSet<>();

		FindingAttribution() { this(ATTRIBUTION_PREVIEW_CAP); }
		FindingAttribution(int cap) { this.cap = cap; }

		int appearedInCount() { return appearedReleases.size(); }
		int resolvedInCount() { return resolvedReleases.size(); }
		int presentInCount()  { return presentReleases.size(); }
		boolean hasPresent()  { return !presentReleases.isEmpty(); }

		void addAppeared(ComponentAttribution a) {
			if (appearedReleases.add(a.releaseUuid())) {
				appearedComponents.add(a.componentUuid());
				if (appearedIn.size() < cap) appearedIn.add(a);
			}
		}
		void addResolved(ComponentAttribution a) {
			if (resolvedReleases.add(a.releaseUuid())) {
				resolvedComponents.add(a.componentUuid());
				if (resolvedIn.size() < cap) resolvedIn.add(a);
			}
		}
		void addPresent(ComponentAttribution a) {
			if (presentReleases.add(a.releaseUuid())) {
				presentComponents.add(a.componentUuid());
				presentComponentNames.add(a.componentName());
				if (presentIn.size() < cap) presentIn.add(a);
			}
		}
		/** True iff this finding is present in any of the given releases (e.g. branch-latest). */
		boolean presentInAnyOf(Collection<UUID> releaseUuids) {
			for (UUID r : releaseUuids) if (presentReleases.contains(r)) return true;
			return false;
		}
	}

	/**
	 * Max attribution entries materialized/serialized per finding bucket (appearedIn/resolvedIn/presentIn).
	 * The UI shows these inline and offers "+N more" -> paginated drill-down (findingAttributionByDate) for
	 * the remainder. Kept SMALL (1) because the inline previews scale with finding count: at ~6000 in-window
	 * findings, each extra preview entry per bucket adds ~2.7 MB to the response (measured on the heavy rig).
	 * K=1 shows one example release per bucket + "+N more"; the full list is one click away via the drawer.
	 */
	static final int ATTRIBUTION_PREVIEW_CAP = 1;
	
	/**
	 * Holder for the three parallel attribution maps, reducing parameter noise. Also carries the per-build
	 * options: an optional single-finding {@code keyFilter} and the materialization {@code cap}. The normal
	 * changelog read uses {@code (null, ATTRIBUTION_PREVIEW_CAP)} -> every finding, preview-capped. The
	 * {@code findingAttributionByDate} drill-down uses {@code (theKey, MAX)} -> ONLY that finding accumulates,
	 * fully materialized (bounded memory: one finding). {@code keyFilter} null = accept all keys.
	 */
	private static class AttributionMaps {
		final Map<String, FindingAttribution<ReleaseMetricsDto.VulnerabilityDto>> vulns = new HashMap<>();
		final Map<String, FindingAttribution<ReleaseMetricsDto.ViolationDto>> violations = new HashMap<>();
		final Map<String, FindingAttribution<ReleaseMetricsDto.WeaknessDto>> weaknesses = new HashMap<>();
		final String keyFilter;
		final int cap;

		AttributionMaps() { this(null, ATTRIBUTION_PREVIEW_CAP); }
		AttributionMaps(String keyFilter, int cap) { this.keyFilter = keyFilter; this.cap = cap; }

		/** True when this key is excluded by the single-finding drill-down filter (skip it entirely). */
		boolean rejects(String key) { return keyFilter != null && !keyFilter.equals(key); }

		/** Fetch-or-create the accumulator for {@code key}, honoring the per-build cap. */
		<T> FindingAttribution<T> get(Map<String, FindingAttribution<T>> map, String key) {
			return map.computeIfAbsent(key, k -> new FindingAttribution<>(cap));
		}
	}
	
	/**
	 * Compares findings between two metrics objects.
	 * Pure function - no side effects, no data extraction.
	 * This is the core comparison logic that can be used with any metrics source.
	 * 
	 * @param metrics1 Starting metrics
	 * @param metrics2 Ending metrics
	 * @return FindingChangesRecord with appeared, resolved, and severity changed findings
	 */
	public FindingChangesRecord compareMetrics(
			ReleaseMetricsDto metrics1,
			ReleaseMetricsDto metrics2) {
		
		if (metrics1 == null || metrics2 == null) {
			log.warn("FINDINGS_COMPARISON: One or both metrics are null - metrics1={}, metrics2={}", 
				metrics1 != null ? "present" : "null", 
				metrics2 != null ? "present" : "null");
			return FindingChangesRecord.EMPTY;
		}
		
		// Compare vulnerabilities
		var vulnChanges = compareFindings(
			metrics1.getVulnerabilityDetails(),
			metrics2.getVulnerabilityDetails(),
			VULN_KEY
		);
		
		// Compare violations
		var violationChanges = compareFindings(
			metrics1.getViolationDetails(),
			metrics2.getViolationDetails(),
			VIOLATION_KEY
		);
		
		// Compare weaknesses
		var weaknessChanges = compareFindings(
			metrics1.getWeaknessDetails(),
			metrics2.getWeaknessDetails(),
			WEAKNESS_KEY
		);
		
		return new FindingChangesRecord(
			vulnChanges.appeared,
			vulnChanges.resolved,
			violationChanges.appeared,
			violationChanges.resolved,
			weaknessChanges.appeared,
			weaknessChanges.resolved
		);
	}


	
	/**
	 * Generic helper to track findings (appeared or resolved) for any finding type.
	 * The {@code adder} picks which attribution bucket to add to (e.g. {@code FindingAttribution::addAppeared}
	 * or {@code ::addResolved}); the accumulator dedups by release, keeps exact counts, and preview-caps.
	 */
	private <T> void trackFindings(
			AttributionMaps maps,
			List<T> findings,
			Map<String, FindingAttribution<T>> findingMap,
			ComponentAttribution attr,
			Set<String> handledFindings,
			Function<T, String> keyExtractor,
			BiConsumer<FindingAttribution<T>, ComponentAttribution> adder) {

		if (findings == null) return;

		for (T finding : findings) {
			String key = keyExtractor.apply(finding);
			if (maps.rejects(key)) continue; // single-finding drill-down: ignore other keys
			FindingAttribution<T> fa = maps.get(findingMap, key);
			if (fa.finding == null) fa.finding = finding;
			adder.accept(fa, attr);
			if (handledFindings != null) {
				handledFindings.add(key);
			}
		}
	}
	
	/**
	 * Generic helper to track present findings for any finding type
	 */
	private <T> void trackPresentFindings(
			AttributionMaps maps,
			List<T> presentFindings,
			Map<String, FindingAttribution<T>> findingMap,
			ComponentAttribution releaseAttr,
			Function<T, String> keyExtractor) {

		if (presentFindings == null) return;

		for (T finding : presentFindings) {
			String key = keyExtractor.apply(finding);
			if (maps.rejects(key)) continue; // single-finding drill-down: ignore other keys
			FindingAttribution<T> fa = findingMap.get(key);
			if (fa != null) {
				fa.addPresent(releaseAttr);
			}
		}
	}
	
	/**
	 * Tracks appeared and resolved findings for all three finding types from a FindingChangesRecord.
	 * Eliminates the repeated 6-call pattern used in both component-level and org-level methods.
	 */
	private void trackAllFindings(
			FindingChangesRecord changes,
			AttributionMaps maps,
			ComponentAttribution attr,
			Set<String> handledFindings) {
		
		trackFindings(maps, changes.appearedVulnerabilities(), maps.vulns, attr, handledFindings, VULN_KEY, FindingAttribution::addAppeared);
		trackFindings(maps, changes.resolvedVulnerabilities(), maps.vulns, attr, handledFindings, VULN_KEY, FindingAttribution::addResolved);
		trackFindings(maps, changes.appearedViolations(), maps.violations, attr, handledFindings, VIOLATION_KEY, FindingAttribution::addAppeared);
		trackFindings(maps, changes.resolvedViolations(), maps.violations, attr, handledFindings, VIOLATION_KEY, FindingAttribution::addResolved);
		trackFindings(maps, changes.appearedWeaknesses(), maps.weaknesses, attr, handledFindings, WEAKNESS_KEY, FindingAttribution::addAppeared);
		trackFindings(maps, changes.resolvedWeaknesses(), maps.weaknesses, attr, handledFindings, WEAKNESS_KEY, FindingAttribution::addResolved);
	}
	
	/**
	 * Tracks present findings for all three finding types from a metrics object.
	 */
	private void trackAllPresentFindings(
			ReleaseMetricsDto metrics,
			AttributionMaps maps,
			ComponentAttribution releaseAttr) {
		
		trackPresentFindings(maps, metrics.getVulnerabilityDetails(), maps.vulns, releaseAttr, VULN_KEY);
		trackPresentFindings(maps, metrics.getViolationDetails(), maps.violations, releaseAttr, VIOLATION_KEY);
		trackPresentFindings(maps, metrics.getWeaknessDetails(), maps.weaknesses, releaseAttr, WEAKNESS_KEY);
	}
	
	/**
	 * Tracks inherited findings for all three finding types from a metrics object.
	 */
	private void trackAllInheritedFindings(
			ReleaseMetricsDto metrics,
			AttributionMaps maps,
			Set<String> handledFindings) {
		
		trackInheritedFindings(maps, metrics.getVulnerabilityDetails(), maps.vulns, handledFindings, VULN_KEY);
		trackInheritedFindings(maps, metrics.getViolationDetails(), maps.violations, handledFindings, VIOLATION_KEY);
		trackInheritedFindings(maps, metrics.getWeaknessDetails(), maps.weaknesses, handledFindings, WEAKNESS_KEY);
	}

	/**
	 * Generic helper to track inherited findings for any finding type
	 */
	private <T> void trackInheritedFindings(
			AttributionMaps maps,
			List<T> findings,
			Map<String, FindingAttribution<T>> findingMap,
			Set<String> handledFindings,
			Function<T, String> keyExtractor) {

		if (findings == null) return;

		for (T finding : findings) {
			String key = keyExtractor.apply(finding);
			if (maps.rejects(key)) continue; // single-finding drill-down: ignore other keys
			if (!findingMap.containsKey(key) && !handledFindings.contains(key)) {
				FindingAttribution<T> fa = maps.get(findingMap, key);
				fa.finding = finding;
			}
		}
	}
	
	/**
	 * Compare finding metrics across branches with accurate per-release attribution.
	 * Performs sequential comparisons within each branch to track exactly which release
	 * each vulnerability, violation, or weakness appeared/resolved in.
	 * 
	 * @param releasesByBranch Releases grouped by branch (sorted newest first within each branch)
	 * @param componentData Component data for attribution
	 * @return Finding changes with accurate per-release attribution
	 */
	public FindingChangesWithAttribution compareMetricsWithAttributionAcrossBranches(
			LinkedHashMap<UUID, List<ReleaseData>> releasesByBranch,
			ComponentData componentData,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache
		) {
		
		log.debug("Starting finding attribution comparison for component {}", componentData.getName());
		
		AttributionMaps maps = new AttributionMaps();
		
		// Track findings handled by fork point comparison to avoid treating them as inherited
		Set<String> forkPointHandledFindings = new HashSet<>();
		
		// PHASES 0-1: Fork point comparisons + pairwise consecutive comparisons
		processForkPointAndPairwise(releasesByBranch,
			componentData.getUuid(), componentData.getName(),
			DIRECT_METRICS_COMPARATOR, maps, forkPointHandledFindings, branchNameCache, forkPointCache, componentData);
		
		// PHASE 2: Track truly inherited findings from first (oldest) release in each branch
		// These are findings that existed before the fork point AND were not handled by fork point comparison
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			List<ReleaseData> branchReleases = entry.getValue();
			
			if (branchReleases.isEmpty()) continue;
			
			// Get the first (oldest) release in this branch
			ReleaseData firstRelease = branchReleases.get(branchReleases.size() - 1);
			ReleaseMetricsDto firstMetrics = firstRelease.getMetrics();
			
			if (firstMetrics == null) continue;
			
			// Track truly inherited findings using generic helper
			trackAllInheritedFindings(firstMetrics, maps, forkPointHandledFindings);
		}
		
		// Track current state by querying ALL releases in each branch
		// This populates presentIn with findings that exist in each release
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			
			if (branchReleases.isEmpty()) continue;
			
			// Iterate through ALL releases in this branch
			for (ReleaseData release : branchReleases) {
				ReleaseMetricsDto releaseMetrics = release.getMetrics();
				
				if (releaseMetrics == null) continue;
				
				ComponentAttribution releaseAttr = new ComponentAttribution(
					componentData.getUuid(),
					componentData.getName(),
					release.getUuid(),
					release.getVersion(),
					branchUuid,
					cachedBranchName(branchUuid, branchNameCache)
				);
				
				// Track present findings using generic helper
				trackAllPresentFindings(releaseMetrics, maps, releaseAttr);
			}
		}
		
		// Determine which releases are the latest in each branch
		Map<UUID, UUID> latestReleasePerBranch = new HashMap<>();
		for (Map.Entry<UUID, List<ReleaseData>> entry : releasesByBranch.entrySet()) {
			UUID branchUuid = entry.getKey();
			List<ReleaseData> branchReleases = entry.getValue();
			if (!branchReleases.isEmpty()) {
				// Index 0 = newest (latest)
				latestReleasePerBranch.put(branchUuid, branchReleases.get(0).getUuid());
			}
		}
		
		// Build attributed findings using component-level flag computation
		return buildAttributedFindings(maps,
			(key, fa) -> computeComponentFindingFlags(fa, latestReleasePerBranch));
	}
	
	/**
	 * Shared core logic: processes fork point comparisons and pairwise consecutive comparisons
	 * for a single component's branches. Used by both component-level and org-level methods.
	 * 
	 * @param releasesByBranch Releases grouped by branch (sorted newest first)
	 * @param componentUuid Component UUID for attribution
	 * @param componentName Component name for attribution
	 * @param comparator Function that compares two releases and returns finding changes, or null to skip
	 * @param maps Attribution maps holder to populate
	 * @param forkPointHandledFindings Set to track findings handled by fork point (populated by this method)
	 */
	private void processForkPointAndPairwise(
			Map<UUID, List<ReleaseData>> releasesByBranch,
			UUID componentUuid,
			String componentName,
			BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> comparator,
			AttributionMaps maps,
			Set<String> forkPointHandledFindings,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache) {
		processForkPointAndPairwise(releasesByBranch, componentUuid, componentName,
			comparator, maps, forkPointHandledFindings, branchNameCache, forkPointCache, null);
	}

	private void processForkPointAndPairwise(
			Map<UUID, List<ReleaseData>> releasesByBranch,
			UUID componentUuid,
			String componentName,
			BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> comparator,
			AttributionMaps maps,
			Set<String> forkPointHandledFindings,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache,
			ComponentData componentData) {
		
		// baseBranchCache: caches base branch UUID per component to avoid repeated findBranchByName calls
		Map<UUID, Optional<UUID>> baseBranchCache = new HashMap<>();
		
		// PHASE 0: Fork point comparisons for each branch's oldest release
		for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
			UUID branchUuid = branchEntry.getKey();
			List<ReleaseData> branchReleases = branchEntry.getValue();
			if (branchReleases.isEmpty()) continue;
			
			ReleaseData oldestRelease = branchReleases.get(branchReleases.size() - 1);
			
			// Use fork point cache to avoid redundant DB lookups
			String cacheKey = branchUuid + ":" + oldestRelease.getUuid();
			ReleaseData forkPointRelease = null;
			
			if (forkPointCache.containsKey(cacheKey)) {
				forkPointRelease = forkPointCache.get(cacheKey);
			} else {
				UUID forkPointReleaseId = sharedReleaseService.findPreviousReleasesOfBranchForRelease(
					branchUuid, oldestRelease.getUuid(), oldestRelease, componentData, baseBranchCache);
				
				if (forkPointReleaseId != null) {
					var forkPointReleaseOpt = sharedReleaseService.getReleaseData(forkPointReleaseId);
					forkPointRelease = forkPointReleaseOpt.orElse(null);
				}
				forkPointCache.put(cacheKey, forkPointRelease);
			}
			
			if (forkPointRelease == null) {
				log.debug("No fork point found for branch {} release {}", 
					cachedBranchName(branchUuid, branchNameCache), oldestRelease.getVersion());
				continue;
			}
			
			// Only compare if fork point is on a DIFFERENT branch
			if (forkPointRelease.getBranch().equals(branchUuid)) {
				log.debug("Fork point is on same branch for {}/{}, skipping", 
					componentName, cachedBranchName(branchUuid, branchNameCache));
				continue;
			}
			
			FindingChangesRecord forkPointChanges = comparator.apply(forkPointRelease, oldestRelease);
			if (forkPointChanges == null) continue;
			
			log.debug("Fork point comparison for {}: {} appeared vulns, {} resolved vulns",
				componentName,
				forkPointChanges.appearedVulnerabilities() != null ? forkPointChanges.appearedVulnerabilities().size() : 0,
				forkPointChanges.resolvedVulnerabilities() != null ? forkPointChanges.resolvedVulnerabilities().size() : 0);
			
			ComponentAttribution attr = new ComponentAttribution(
				componentUuid, componentName,
				oldestRelease.getUuid(), oldestRelease.getVersion(),
				branchUuid, cachedBranchName(branchUuid, branchNameCache)
			);
			
			trackAllFindings(forkPointChanges, maps, attr, forkPointHandledFindings);
		}
		
		// PHASE 1: Pairwise consecutive comparisons on each branch
		processPairwiseComparisons(releasesByBranch, componentUuid, componentName,
			comparator, maps, branchNameCache);
	}
	
	/**
	 * Processes pairwise consecutive comparisons within each branch.
	 * Compares older→newer release pairs and tracks appeared/resolved findings.
	 * 
	 * @param releasesByBranch Releases grouped by branch (sorted newest first)
	 * @param componentUuid Component UUID for attribution
	 * @param componentName Component name for attribution
	 * @param pairComparator Function that compares two releases (older, newer) and returns finding changes, or null to skip
	 * @param maps Attribution maps holder to populate
	 */
	private void processPairwiseComparisons(
			Map<UUID, List<ReleaseData>> releasesByBranch,
			UUID componentUuid,
			String componentName,
			BiFunction<ReleaseData, ReleaseData, FindingChangesRecord> pairComparator,
			AttributionMaps maps,
			Map<UUID, String> branchNameCache) {
		
		for (Map.Entry<UUID, List<ReleaseData>> branchEntry : releasesByBranch.entrySet()) {
			List<ReleaseData> branchReleases = branchEntry.getValue();
			if (branchReleases.size() < 2) continue;
			
			// Iterate from oldest to newest (pairwise)
			for (int i = branchReleases.size() - 1; i > 0; i--) {
				ReleaseData olderRelease = branchReleases.get(i);
				ReleaseData newerRelease = branchReleases.get(i - 1);
				
				FindingChangesRecord changes = pairComparator.apply(olderRelease, newerRelease);
				if (changes == null) continue;
				
				ComponentAttribution pairAttr = new ComponentAttribution(
					componentUuid, componentName,
					newerRelease.getUuid(), newerRelease.getVersion(),
					newerRelease.getBranch(), cachedBranchName(newerRelease.getBranch(), branchNameCache)
				);
				
				trackAllFindings(changes, maps, pairAttr, null);
			}
		}
	}
	
	/**
	 * Builds the final FindingChangesWithAttribution from the three attributed finding lists.
	 * Counts net appeared/resolved totals.
	 */
	private FindingChangesWithAttribution buildFindingChangesResult(
			List<VulnerabilityWithAttribution> vulnerabilities,
			List<ViolationWithAttribution> violations,
			List<WeaknessWithAttribution> weaknesses) {
		
		int totalAppeared = (int) (vulnerabilities.stream().filter(v -> v.isNetAppeared()).count()
			+ violations.stream().filter(v -> v.isNetAppeared()).count()
			+ weaknesses.stream().filter(w -> w.isNetAppeared()).count());
		
		int totalResolved = (int) (vulnerabilities.stream().filter(v -> v.isNetResolved()).count()
			+ violations.stream().filter(v -> v.isNetResolved()).count()
			+ weaknesses.stream().filter(w -> w.isNetResolved()).count());
		
		return new FindingChangesWithAttribution(
			vulnerabilities, violations, weaknesses,
			totalAppeared, totalResolved);
	}
	
	/**
	 * Builds attributed finding lists from the three attribution maps using a flag-computing strategy.
	 * Shared by both component-level and org-level methods -- the only difference is how flags are computed.
	 *
	 * @param maps Attribution maps holder
	 * @param flagComputer Function that takes (key, FindingAttribution) and returns FindingFlags
	 */
	private FindingChangesWithAttribution buildAttributedFindings(
			AttributionMaps maps,
			BiFunction<String, FindingAttribution<?>, FindingFlags> flagComputer) {
		
		List<VulnerabilityWithAttribution> vulnerabilities = maps.vulns.entrySet().stream()
			.map(e -> {
				FindingFlags flags = flagComputer.apply(e.getKey(), e.getValue());
				var fa = e.getValue();
				return new VulnerabilityWithAttribution(
					e.getKey(),
					fa.finding.vulnId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : UNKNOWN_SEVERITY,
					fa.finding.purl(),
					fa.finding.aliases(),
					fa.resolvedIn, fa.appearedIn, fa.presentIn,
					fa.resolvedInCount(), fa.appearedInCount(), fa.presentInCount(),
					flags.isNetResolved(), flags.isNetAppeared(), flags.isStillPresent(),
					flags.orgContext(),
					fa.finding.analysisState()
				);
			})
			.collect(Collectors.toList());

		List<ViolationWithAttribution> violations = maps.violations.entrySet().stream()
			.map(e -> {
				FindingFlags flags = flagComputer.apply(e.getKey(), e.getValue());
				var fa = e.getValue();
				return new ViolationWithAttribution(
					e.getKey(),
					fa.finding.type() != null ? fa.finding.type().name() : UNKNOWN_SEVERITY,
					fa.finding.purl(),
					fa.resolvedIn, fa.appearedIn, fa.presentIn,
					fa.resolvedInCount(), fa.appearedInCount(), fa.presentInCount(),
					flags.isNetResolved(), flags.isNetAppeared(), flags.isStillPresent(),
					flags.orgContext(),
					fa.finding.analysisState()
				);
			})
			.collect(Collectors.toList());
		
		List<WeaknessWithAttribution> weaknesses = maps.weaknesses.entrySet().stream()
			.map(e -> {
				FindingFlags flags = flagComputer.apply(e.getKey(), e.getValue());
				var fa = e.getValue();
				return new WeaknessWithAttribution(
					e.getKey(),
					fa.finding.cweId(),
					fa.finding.severity() != null ? fa.finding.severity().name() : UNKNOWN_SEVERITY,
					fa.finding.ruleId(),
					fa.finding.location() != null ? fa.finding.location() : "",
					fa.resolvedIn, fa.appearedIn, fa.presentIn,
					fa.resolvedInCount(), fa.appearedInCount(), fa.presentInCount(),
					flags.isNetResolved(), flags.isNetAppeared(), flags.isStillPresent(),
					flags.orgContext(),
					fa.finding.analysisState()
				);
			})
			.collect(Collectors.toList());
		
		return buildFindingChangesResult(vulnerabilities, violations, weaknesses);
	}
	
	/**
	 * Tracks findings that are inherited within a component (present in both first and last metrics of a branch).
	 * Adds the component UUID to the inheritedInComponents map for each inherited finding key.
	 */
	private <T> void trackInheritedInComponents(
			List<T> firstFindings,
			List<T> lastFindings,
			Function<T, String> keyExtractor,
			Map<String, Set<UUID>> inheritedInComponents,
			UUID componentUuid) {
		if (firstFindings == null || lastFindings == null) return;
		
		Set<String> firstKeys = new HashSet<>();
		for (T finding : firstFindings) {
			firstKeys.add(keyExtractor.apply(finding));
		}
		for (T finding : lastFindings) {
			String key = keyExtractor.apply(finding);
			if (firstKeys.contains(key)) {
				inheritedInComponents.computeIfAbsent(key, k -> new HashSet<>()).add(componentUuid);
			}
		}
	}
	
	/**
	 * Computed flags for a finding (used at both component and org level).
	 */
	private record FindingFlags(
		boolean isNetAppeared,
		boolean isStillPresent,
		boolean isNetResolved,
		OrgLevelContext orgContext
	) {}
	
	/**
	 * Computes component-level flags for a single finding.
	 */
	private <T> FindingFlags computeComponentFindingFlags(
			FindingAttribution<T> fa,
			Map<UUID, UUID> latestReleasePerBranch) {
		
		boolean isNetAppeared = fa.appearedInCount() > 0 && fa.resolvedInCount() == 0;
		boolean existsInLatestRelease = fa.presentInAnyOf(latestReleasePerBranch.values());
		boolean isStillPresent = existsInLatestRelease && !isNetAppeared;
		boolean isNetResolved = fa.resolvedInCount() > 0 && !existsInLatestRelease;
		
		return new FindingFlags(isNetAppeared, isStillPresent, isNetResolved, null);
	}
	
	
	/**
	 * Computes org-level flags and OrgLevelContext for a single finding.
	 * Centralizes the flag computation logic that was previously triplicated for vulns/violations/weaknesses.
	 */
	private <T> FindingFlags computeOrgFindingFlags(
			String key,
			FindingAttribution<T> fa,
			Map<String, Set<UUID>> inheritedInComponents,
			int totalComponents) {
		
		Set<UUID> inherited = inheritedInComponents.getOrDefault(key, Collections.emptySet());
		// Full component-level aggregates (maintained incrementally; independent of the preview cap).
		Set<UUID> appearedComponents = fa.appearedComponents;
		Set<UUID> resolvedComponents = fa.resolvedComponents;
		Set<UUID> presentComponents = fa.presentComponents;

		boolean isNetAppeared = fa.appearedInCount() > 0 && fa.resolvedInCount() == 0;
		boolean isStillPresent = fa.presentInCount() > 0 && !isNetAppeared;
		boolean isNetResolved = fa.resolvedInCount() > 0 && fa.presentInCount() == 0;

		boolean isFullyResolved = resolvedComponents.size() > 0 && presentComponents.isEmpty();
		boolean isPartiallyResolved = !isFullyResolved && resolvedComponents.size() > 0 && presentComponents.size() > 0;
		boolean isInheritedInAllComponents = !isFullyResolved && !isPartiallyResolved && inherited.size() == totalComponents && totalComponents > 1;
		boolean isNewToOrganization = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && inherited.isEmpty()
				&& (appearedComponents.size() > 0
						|| (!presentComponents.isEmpty() && appearedComponents.isEmpty() && resolvedComponents.isEmpty()));
		boolean wasPreviouslyReported = !isFullyResolved && !isPartiallyResolved && !isInheritedInAllComponents && appearedComponents.size() > 0 &&
			(inherited.size() > 0 || presentComponents.stream().anyMatch(c -> !appearedComponents.contains(c)));

		// Distinct affected-component names, full (not preview-capped).
		List<String> affectedComponentNames = new ArrayList<>(fa.presentComponentNames);
		
		OrgLevelContext orgContext = new OrgLevelContext(
			isNewToOrganization,
			wasPreviouslyReported,
			isPartiallyResolved,
			isFullyResolved,
			isInheritedInAllComponents,
			presentComponents.size(),
			affectedComponentNames
		);
		
		return new FindingFlags(isNetAppeared, isStillPresent, isNetResolved, orgContext);
	}
	
	/**
	 * Compare metrics across multiple components for organization-level changelog.
	 * Aggregates findings from all components with proper semantics:
	 * - isStillPresent = inherited (existed in first AND last release) in ANY component
	 * - isNetAppeared = appeared in at least one component and NOT inherited in any
	 * - isNetResolved = resolved in at least one component
	 * 
	 * @param componentReleases Map of component UUID to list of releases
	 * @param componentNames Map of component UUID to component name
	 * @return Finding changes with attribution across all components
	 */
	public FindingChangesWithAttribution compareMetricsAcrossComponents(
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache) {
		return compareMetricsAcrossComponents(componentReleases, componentNames, branchNameCache, forkPointCache,
			null, ATTRIBUTION_PREVIEW_CAP);
	}

	/**
	 * Filtered/uncapped variant of the legacy org comparison. {@code keyFilter} non-null => only that finding
	 * key accumulates (drill-down); {@code cap} = MAX materializes its full list. Normal path delegates here
	 * with {@code (null, ATTRIBUTION_PREVIEW_CAP)}.
	 */
	public FindingChangesWithAttribution compareMetricsAcrossComponents(
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			Map<String, ReleaseData> forkPointCache,
			String keyFilter,
			int cap) {

		log.debug("ORG-COMPARE: Starting org-level finding comparison across {} components", componentReleases.size());

		AttributionMaps maps = new AttributionMaps(keyFilter, cap);
		
		// Track which components have which findings as inherited (first AND last release)
		Map<String, Set<UUID>> inheritedInComponents = new HashMap<>();
		
		// First pass: collect appeared/resolved from each component, per branch
		for (Map.Entry<UUID, List<ReleaseData>> entry : componentReleases.entrySet()) {
			UUID componentUuid = entry.getKey();
			List<ReleaseData> releases = entry.getValue();
			String componentName = componentNames.getOrDefault(componentUuid, "Unknown");
			
			if (releases.isEmpty()) continue;
			
			// Group releases by branch to compare per-branch (not just overall first vs last)
			Map<UUID, List<ReleaseData>> releasesByBranch = releases.stream()
				.collect(Collectors.groupingBy(ReleaseData::getBranch));
			// Sort each branch's releases by creation date (newest first) - required by processForkPointAndPairwise
			releasesByBranch.values().forEach(list -> list.sort(Comparator.comparing(ReleaseData::getCreatedDate).reversed()));
			
			log.debug("ORG-COMPARE: Component {} has {} branches", componentName, releasesByBranch.size());
			
			// PHASES 0-1: Fork point comparisons + pairwise consecutive comparisons (uses cache from component-level)
			Set<String> forkPointHandledFindings = new HashSet<>();
			processForkPointAndPairwise(releasesByBranch, componentUuid, componentName,
				DIRECT_METRICS_COMPARATOR, maps, forkPointHandledFindings, branchNameCache, forkPointCache);
			
			// PHASE 2: Track inherited findings from oldest release in each branch
			// Without this, findings that existed before the date range never enter the maps,
			// causing trackPresentFindings to silently skip them (resulting in 0 "Still Present")
			for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
				if (branchReleases.isEmpty()) continue;
				ReleaseData oldestRelease = branchReleases.get(branchReleases.size() - 1);
				ReleaseMetricsDto oldestMetrics = oldestRelease.getMetrics();
				if (oldestMetrics != null) {
					trackAllInheritedFindings(oldestMetrics, maps, forkPointHandledFindings);
				}
			}
			
			// Track inherited findings per branch: existed in BOTH oldest AND newest release
			for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
				if (branchReleases.size() < 2) continue;
				ReleaseMetricsDto firstMetrics = branchReleases.get(branchReleases.size() - 1).getMetrics();
				ReleaseMetricsDto lastMetrics = branchReleases.get(0).getMetrics();
				if (firstMetrics != null && lastMetrics != null) {
					trackInheritedInComponents(firstMetrics.getVulnerabilityDetails(),
						lastMetrics.getVulnerabilityDetails(), VULN_KEY, inheritedInComponents, componentUuid);
					trackInheritedInComponents(firstMetrics.getViolationDetails(),
						lastMetrics.getViolationDetails(), VIOLATION_KEY, inheritedInComponents, componentUuid);
					trackInheritedInComponents(firstMetrics.getWeaknessDetails(),
						lastMetrics.getWeaknessDetails(), WEAKNESS_KEY, inheritedInComponents, componentUuid);
				}
			}
			
			// Track present findings from latest release per branch
			for (List<ReleaseData> branchReleases : releasesByBranch.values()) {
				if (branchReleases.isEmpty()) continue;
				
				ReleaseData latestRelease = branchReleases.get(0);
				ReleaseMetricsDto latestMetrics = latestRelease.getMetrics();
				
				if (latestMetrics == null) continue;
				
				ComponentAttribution latestAttr = new ComponentAttribution(
					componentUuid, componentName,
					latestRelease.getUuid(), latestRelease.getVersion(),
					latestRelease.getBranch(), cachedBranchName(latestRelease.getBranch(), branchNameCache)
				);
				
				trackAllPresentFindings(latestMetrics, maps, latestAttr);
			}
		}
		
		// Build attributed findings using org-level flag computation
		int totalComponents = componentReleases.size();
		return buildAttributedFindings(maps,
			(key, fa) -> computeOrgFindingFlags(key, fa, inheritedInComponents, totalComponents));
	}

	// ==================================================================================
	// POSTURE-ENDPOINT DIFF rollup for the AGGREGATED org changelog (board task #38, phase 3).
	//
	// The legacy compareMetricsAcrossComponents above diffs release PAIRS by their CURRENT metrics,
	// keyed only on finding identity: it ignores severity / KEV and never compares a release to its
	// own earlier scan, so it misses within-release post-first-scan escalations (e.g. a KEV_ADDED that
	// does not change set membership) and its "resolved" is undated.
	//
	// This path instead reconstructs the org's finding POSTURE at the window endpoints [from, to] and
	// diffs those two sets:
	//   - posture at t = per component, per branch: take the branch-latest release created <= t, and
	//     read the metrics that were LIVE at t for it (reconstructed from metrics_audit for from;
	//     current metrics for to when to ~ now).
	//   - the two endpoint postures are folded into the SAME AttributionMaps / FindingAttribution the
	//     legacy path uses, then computeOrgFindingFlags / buildAttributedFindings run VERBATIM -- only
	//     the SOURCE of appearedIn/resolvedIn/presentIn changes (endpoints, not pairwise diffs).
	//   - a KEV/severity overlay from in-window finding_change_events is applied on top, since a
	//     set-diff cannot see escalations that don't change set membership.
	// ==================================================================================

	/** Denotes which window endpoint a reconstructed posture corresponds to. */
	private enum PostureEndpoint { FROM, TO }

	/**
	 * Per (component, branch) endpoint posture: the metrics live at the endpoint and the release they
	 * belong to (the branch-latest release created &lt;= endpoint). {@code release} may be null when the
	 * branch had no release at-or-before the endpoint (e.g. the branch was created inside the window ->
	 * no from-baseline); {@code metrics} is then empty.
	 */
	private record EndpointPosture(ReleaseData release, ReleaseMetricsDto metrics) {}

	/**
	 * BATCHED reconstruction context (org posture-diff N+1 elimination), EVENT-SOURCED. Carries, per
	 * release UUID, the {@code finding_change_events} in {@code (from, now]} that
	 * {@link #reconstructLiveMetricsAt} reverse-replays onto the release's CURRENT metrics to derive the
	 * metrics live at an endpoint -- the batched equivalent of the per-release
	 * {@code findingChangeEventRepository.findInRange} lookup. A release absent from the map has no events
	 * after {@code from} (posture unchanged since {@code from} -> reconstruct == current). No
	 * {@code metrics_audit} read is involved. {@code retentionHorizon} is {@code now -
	 * findingChangeRetentionDays} for the org: an endpoint older than it cannot be reconstructed (older
	 * events purged) and degrades to current -- the SAME single horizon the over-time changelog clamps to.
	 * {@code from} / {@code to} are the exact endpoint instants this prefetch was built for;
	 * {@link #reconstructLiveMetricsAt} matches {@code at} against them via {@link #covers}. When
	 * {@code null} (component / branch / product scopes, unit tests) {@code reconstructLiveMetricsAt} does
	 * its own per-call event fetch.
	 */
	record PostureReconstructionPrefetch(
			Map<UUID, List<FindingChangeEvent>> eventsByRelease,
			ZonedDateTime retentionHorizon,
			ZonedDateTime from,
			ZonedDateTime to) {

		/** Prefetched events for {@code releaseUuid} (in {@code (from, now]}, change_date ASC), or empty. */
		List<FindingChangeEvent> eventsFor(UUID releaseUuid) {
			return eventsByRelease.getOrDefault(releaseUuid, List.of());
		}

		/** Whether {@code at} is one of the two instants this prefetch was built for. */
		boolean covers(ZonedDateTime at) {
			return at.equals(from) || at.equals(to);
		}
	}

	/**
	 * Builds a {@link PostureReconstructionPrefetch} for the org rollup: gathers ALL anchor release UUIDs
	 * across {@code componentReleases}, then fetches every {@code finding_change_events} row in
	 * {@code (from, now]} for them in ONE chunked query, grouped by release UUID (change_date ASC). This
	 * is the batched equivalent of the per-release {@code findInRange} that
	 * {@link #reconstructLiveMetricsAt} would otherwise issue per anchor. No {@code metrics_audit} read.
	 */
	public PostureReconstructionPrefetch prefetchPostureReconstruction(
			Map<UUID, List<ReleaseData>> componentReleases, ZonedDateTime from, ZonedDateTime to) {
		Set<UUID> releaseUuids = new HashSet<>();
		UUID org = null;
		if (componentReleases != null) {
			for (List<ReleaseData> releases : componentReleases.values()) {
				if (releases == null) continue;
				for (ReleaseData r : releases) {
					if (r.getLifecycle() != ReleaseLifecycle.CANCELLED
							&& r.getLifecycle() != ReleaseLifecycle.REJECTED
							// retired: defensive -- these are already dropped from posture at the in-window
							// filter in computePostureDiff before they could become anchors; skip prefetching too
							&& !ReleaseLifecycle.isSupportEnded(r.getLifecycle())
							&& r.getUuid() != null) {
						releaseUuids.add(r.getUuid());
						if (org == null) org = r.getOrg();
					}
				}
			}
		}
		Map<UUID, List<FindingChangeEvent>> eventsByRelease = new HashMap<>();
		if (org != null && !releaseUuids.isEmpty()) {
			ZonedDateTime upper = ZonedDateTime.now();
			List<UUID> ids = new ArrayList<>(releaseUuids);
			for (int i = 0; i < ids.size(); i += RECONSTRUCTION_PREFETCH_CHUNK) {
				List<UUID> chunk = ids.subList(i, Math.min(i + RECONSTRUCTION_PREFETCH_CHUNK, ids.size()));
				for (FindingChangeEvent ev : readFindingChangesInRange(org, chunk, from, upper)) {
					eventsByRelease.computeIfAbsent(ev.getReleaseUuid(), k -> new ArrayList<>()).add(ev);
				}
			}
		}
		return new PostureReconstructionPrefetch(eventsByRelease, retentionHorizon(org), from, to);
	}

	/**
	 * {@code now - findingChangeRetentionDays} for the org (events older than this are purged), or NULL
	 * when retention is disabled (the default = full history, nothing purged so nothing to clamp) or the
	 * org is unknown. A null horizon flows through to {@code reconstructionClampedSince} as "no clamp".
	 */
	private ZonedDateTime retentionHorizon(UUID org) {
		if (org == null) return null;
		OrganizationData.Settings settings = getOrganizationService.getOrganizationData(org)
				.map(OrganizationData::getSettings).orElse(null);
		if (settings == null || !settings.isFindingChangeRetentionEnabled()) {
			return null; // full history retained -- reconstruction may reach arbitrarily far back
		}
		return ZonedDateTime.now().minusDays(settings.getFindingChangeRetentionDays());
	}

	/** Chunk size for the batched reconstruction prefetch array params. */
	private static final int RECONSTRUCTION_PREFETCH_CHUNK = 1000;

	/**
	 * POSTURE-ENDPOINT diff rollup for the AGGREGATED org changelog. Reconstructs the org finding
	 * posture at {@code from} and at {@code to}, folds both endpoints into the shared attribution maps,
	 * and reuses {@link #computeOrgFindingFlags} / {@link #buildAttributedFindings} verbatim so the
	 * bucket / presentation contract is unchanged. A Newly-KEV / severity-increase overlay from in-window
	 * {@code finding_change_events} is layered on top (badge-only; never double-counted into Net).
	 *
	 * <p>Reuses the SAME lineage helper as the rest of the service
	 * ({@code SharedReleaseService.getBranchLatestReleaseAtOrBeforeDate} for the branch-latest-as-of-t
	 * anchor), then reconstructs the metrics live at each endpoint by REVERSE-REPLAYING the anchor's
	 * {@code finding_change_events} onto its current metrics ({@link #reconstructLiveMetricsAt}) -- no
	 * {@code metrics_audit} read.
	 *
	 * @param org               requesting organization (scopes the KEV/severity event overlay)
	 * @param componentReleases in-window (+ from-baseline) releases grouped by component
	 * @param componentNames    component UUID -&gt; name
	 * @param branchNameCache    branch UUID -&gt; name cache
	 * @param from              window start (inclusive)
	 * @param to                window end (inclusive)
	 * @return finding changes with the SAME attribution DTO, plus the Worsened annotations/counts
	 */
	public FindingChangesWithAttribution computeOrgPostureDiff(
			UUID org,
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			ZonedDateTime from,
			ZonedDateTime to) {
		// Thin scope-neutral delegator preserving the original org-scoped public API (and the existing
		// OrgPostureDiffTest). The body lives in the scope-neutral computePostureDiff below; at COMPONENT
		// scope ChangeLogService calls computePostureDiff directly with a single-entry map. Null prefetch
		// -> reconstructLiveMetricsAt keeps its per-call single-row lookups (unit-test / fallback path).
		return computePostureDiff(org, componentReleases, componentNames, branchNameCache, from, to, null);
	}

	/**
	 * Org-rollup entry point with a BATCHED reconstruction {@code prefetch} (org posture-diff N+1
	 * elimination). Identical semantics to the 6-arg {@link #computeOrgPostureDiff}; the only difference
	 * is that {@link #reconstructLiveMetricsAt} consults the prefetched metrics_audit maps instead of
	 * issuing two queries per anchor per endpoint. Pass {@code null} to keep the per-call behavior.
	 */
	public FindingChangesWithAttribution computeOrgPostureDiff(
			UUID org,
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			ZonedDateTime from,
			ZonedDateTime to,
			PostureReconstructionPrefetch prefetch) {
		return computePostureDiff(org, componentReleases, componentNames, branchNameCache, from, to, prefetch);
	}

	/**
	 * SCOPE-NEUTRAL posture-endpoint diff core. Identical body to the former {@code computeOrgPostureDiff};
	 * it is agnostic to whether {@code componentReleases} spans a whole org (many entries) or a single
	 * component (one entry). The org-scoped delegator above and the component path in
	 * {@code ChangeLogService.getComponentChangelogByDate} both call this. At component scope
	 * {@code totalComponents == 1}, so the cross-component-only flags
	 * (isInheritedInAllComponents, cross-component New suppression) naturally degrade to no-ops.
	 */
	public FindingChangesWithAttribution computePostureDiff(
			UUID org,
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			ZonedDateTime from,
			ZonedDateTime to) {
		// Component / branch scope entry: build the batched prefetch here too (one grouped events fetch +
		// one org-settings lookup for the WHOLE request) instead of per-anchor findInRange + retentionHorizon
		// queries -- a multi-branch component would otherwise re-create the exact per-anchor N+1 the org
		// rollup eliminated. Unit tests that want the per-call path use the 7-arg overload with null.
		return computePostureDiff(org, componentReleases, componentNames, branchNameCache, from, to,
				prefetchPostureReconstruction(componentReleases, from, to));
	}

	/**
	 * Component / branch scope entry with a single-finding {@code keyFilter} + {@code cap} for the
	 * {@code findingAttributionByDate} drill-down (builds its own batched prefetch, like the 6-arg above).
	 */
	public FindingChangesWithAttribution computePostureDiff(
			UUID org,
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			ZonedDateTime from,
			ZonedDateTime to,
			String keyFilter,
			int cap) {
		return computePostureDiff(org, componentReleases, componentNames, branchNameCache, from, to,
				prefetchPostureReconstruction(componentReleases, from, to), keyFilter, cap);
	}

	/**
	 * SCOPE-NEUTRAL posture-endpoint diff core with an optional BATCHED reconstruction {@code prefetch}.
	 * When {@code prefetch} is non-null (org rollup), {@link #reconstructLiveMetricsAt} reads the
	 * prefetched maps; when null (all other scopes / unit tests) it falls back to its per-call single-row
	 * lookups. All other behavior is identical, so the FindingChangesWithAttribution result is byte-for-byte
	 * the same either way.
	 */
	public FindingChangesWithAttribution computePostureDiff(
			UUID org,
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			ZonedDateTime from,
			ZonedDateTime to,
			PostureReconstructionPrefetch prefetch) {
		return computePostureDiff(org, componentReleases, componentNames, branchNameCache, from, to, prefetch,
			null, ATTRIBUTION_PREVIEW_CAP);
	}

	/**
	 * Filtered/uncapped variant of the scope-neutral posture core. When {@code keyFilter} is non-null ONLY that
	 * finding key accumulates (the {@code findingAttributionByDate} drill-down), with {@code cap} controlling
	 * preview materialization (MAX = full list for that one finding). The normal path delegates here with
	 * {@code (null, ATTRIBUTION_PREVIEW_CAP)} -- byte-identical to before.
	 */
	public FindingChangesWithAttribution computePostureDiff(
			UUID org,
			Map<UUID, List<ReleaseData>> componentReleases,
			Map<UUID, String> componentNames,
			Map<UUID, String> branchNameCache,
			ZonedDateTime from,
			ZonedDateTime to,
			PostureReconstructionPrefetch prefetch,
			String keyFilter,
			int cap) {

		log.debug("ORG-POSTURE-DIFF: starting posture-endpoint diff across {} components over [{}, {}]",
			componentReleases.size(), from, to);

		AttributionMaps maps = new AttributionMaps(keyFilter, cap);

		// Components in which finding key k is present at each endpoint, plus the per-component
		// attribution anchor (branch-latest as-of to) for present/resolved and the from-membership.
		// inheritedInComponents: present at BOTH endpoints in that component (feeds isInheritedInAllComponents).
		Map<String, Set<UUID>> inheritedInComponents = new HashMap<>();

		// Per finding-key NET escalation state (from-state vs to-state KEV/severity), aggregated across
		// components. Gates the Worsened overlay so a KEV-added-then-removed / severity-up-then-down that
		// nets to no change inside the window is NOT badged (S1).
		Map<String, EndpointEscalation> escalations = new HashMap<>();

		// Union of all in-window release UUIDs (for the KEV/severity event overlay).
		Set<UUID> allReleaseUuids = new HashSet<>();

		for (Map.Entry<UUID, List<ReleaseData>> entry : componentReleases.entrySet()) {
			UUID componentUuid = entry.getKey();
			List<ReleaseData> releases = entry.getValue();
			String componentName = componentNames.getOrDefault(componentUuid, "Unknown");
			if (releases == null || releases.isEmpty()) continue;

			releases.forEach(r -> allReleaseUuids.add(r.getUuid()));

			Map<UUID, List<ReleaseData>> releasesByBranch = releases.stream()
				.filter(r -> r.getLifecycle() != ReleaseLifecycle.CANCELLED
						&& r.getLifecycle() != ReleaseLifecycle.REJECTED
						&& !ReleaseLifecycle.isSupportEnded(r.getLifecycle())) // retired releases leave the posture
				.collect(Collectors.groupingBy(ReleaseData::getBranch));

			for (Map.Entry<UUID, List<ReleaseData>> be : releasesByBranch.entrySet()) {
				UUID branchUuid = be.getKey();
				List<ReleaseData> branchReleases = be.getValue();
				branchReleases.sort(Comparator.comparing(ReleaseData::getCreatedDate).reversed());

				EndpointPosture fromPosture = reconstructBranchPosture(branchUuid, branchReleases, from, PostureEndpoint.FROM, to, prefetch);
				EndpointPosture toPosture = reconstructBranchPosture(branchUuid, branchReleases, to, PostureEndpoint.TO, to, prefetch);

				// The attribution anchor for this (component, branch) endpoint is the branch-latest
				// as-of to (present / resolved are attributed to it, mirroring the legacy path's
				// latest-per-branch anchoring). If the branch has no release at-or-before to, skip.
				ReleaseData toRelease = toPosture.release();
				if (toRelease == null) continue;

				ComponentAttribution toAttr = new ComponentAttribution(
					componentUuid, componentName,
					toRelease.getUuid(), toRelease.getVersion(),
					branchUuid, cachedBranchName(branchUuid, branchNameCache));

				foldEndpointsIntoMaps(
					fromPosture.metrics(), toPosture.metrics(),
					maps, componentUuid, toAttr, inheritedInComponents, escalations);
			}
		}

		int totalComponents = componentReleases.size();

		// ONE horizon per request: reuse the prefetch's snapshot so the degrade decision and the clamp
		// disclosure share the same instant (no false clamp at the exact boundary, no extra org lookup).
		ZonedDateTime horizon = prefetch != null ? prefetch.retentionHorizon() : retentionHorizon(org);
		return assembleFromFoldedMaps(org, maps, inheritedInComponents, escalations,
			allReleaseUuids, totalComponents, from, to, horizon);
	}

	/**
	 * SHARED tail of every posture-endpoint diff (org / component / product). Given the folded
	 * {@link AttributionMaps} plus the per-key {@code inheritedInComponents} / {@code escalations} state and
	 * the in-window release-UUID set, builds the KEV/severity worsened overlay and runs
	 * {@link #computeOrgFindingFlags} + {@link #suppressCrossComponentInheritedNew} + the overlay decorator
	 * through {@link #buildAttributedFindings} VERBATIM. Extracted so {@link #computePostureDiff} (org /
	 * component) and {@link #computeProductPostureDiff} share the identical flag/overlay/DTO contract while
	 * differing only in HOW the endpoint postures are folded into {@code maps} (branch-latest-as-of-t for
	 * org/component vs. exact pinned constituent releases for products).
	 *
	 * @param totalComponents the folding-unit count (org/component: components; product: constituents),
	 *                        so {@code isInheritedInAllComponents} degrades correctly per scope.
	 */
	private FindingChangesWithAttribution assembleFromFoldedMaps(
			UUID org,
			AttributionMaps maps,
			Map<String, Set<UUID>> inheritedInComponents,
			Map<String, EndpointEscalation> escalations,
			Set<UUID> allReleaseUuids,
			int totalComponents,
			ZonedDateTime from,
			ZonedDateTime to,
			ZonedDateTime retentionHorizon) {

		// Build the worsened overlay from in-window finding_change_events BEFORE building the DTO so
		// the decorated flag computer can stamp isNewlyKev / isSeverityIncreased / previousSeverity onto
		// each finding's OrgLevelContext.
		WorsenedOverlay overlay = buildWorsenedOverlay(org, allReleaseUuids, from, to, maps, escalations);

		FindingChangesWithAttribution base = buildAttributedFindings(maps,
			(key, fa) -> {
				FindingFlags flags = computeOrgFindingFlags(key, fa, inheritedInComponents, totalComponents);
				flags = suppressCrossComponentInheritedNew(key, flags, inheritedInComponents);
				return decorateWithOverlay(key, flags, overlay);
			});

		// DISCLOSURE: the from-endpoint degrades to current metrics for EVERY release when `from` predates
		// the org's event-retention horizon (older events purged -> not reverse-reconstructable). That is a
		// single per-org determination (all anchors share the org horizon and the same `from`), so surface
		// it once here instead of silently degrading. Non-null clamp tells the caller the New/Resolved/
		// Worsened numbers treat current as the `from`-baseline for postures before this instant. The
		// horizon is the SAME snapshot the reconstruction used (threaded from the caller/prefetch).
		ZonedDateTime reconstructionClampedSince =
			(retentionHorizon != null && from != null && from.isBefore(retentionHorizon))
				? retentionHorizon : null;

		// totalNewlyKev / totalSeverityIncreased: count distinct keys that are still present at to AND
		// carry the annotation. overlay sets are already present-at-to filtered.
		return new FindingChangesWithAttribution(
			base.vulnerabilities(), base.violations(), base.weaknesses(),
			base.totalAppeared(), base.totalResolved(),
			overlay.newlyKevKeys().size(), overlay.severityIncreasedKeys().size(),
			reconstructionClampedSince, true);
	}

	// ==================================================================================
	// PRODUCT-scope posture-endpoint diff (board task #38, phase 3 -- product path).
	//
	// A product does NOT own its own findings: its posture at time T is the UNION of the findings of the
	// PINNED constituent component-releases of the product's latest PRODUCT-release created <= T, each
	// reconstructed LIVE-AT-T. This is structurally different from the org / component path:
	//   - org / component: endpoints are found by DATE among a component's releases (branch-latest as-of-t).
	//   - product: endpoints are the EXACT constituent releases the product pinned at each product-release;
	//     we do NOT date-pick among constituents. Re-pins (to-product upgrades a constituent to a version
	//     that added/dropped a finding) fold naturally as appeared/resolved; re-scans (SAME pinned
	//     constituent whose own metrics changed between from and to) are caught by reconstructLiveMetricsAt.
	// The two endpoint posture sets are folded through the SAME internals the org path uses
	// (foldEndpointsIntoMaps -> AttributionMaps keyed by constituent componentUuid, then
	// assembleFromFoldedMaps runs computeOrgFindingFlags / buildAttributedFindings / KEV-severity overlay
	// VERBATIM). isNewToOrganization here reads "new to product" (NOT renamed -- same field, product scope).
	// ==================================================================================

	/**
	 * One constituent of a product endpoint: the exact component-release the product PINNED at that endpoint
	 * (via {@code getParentReleases()} -&gt; {@code ParentRelease.getRelease()}), plus its component name for
	 * attribution. {@code release} may be null when the constituent is only pinned at the OTHER endpoint
	 * (component added/removed between the from-product and to-product) -- the null side then folds as
	 * appeared / resolved.
	 */
	public record ProductConstituent(UUID componentUuid, String componentName, ReleaseData release) {}

	/**
	 * PRODUCT-scope posture-endpoint diff. Reconstructs the product finding posture at {@code from} and at
	 * {@code to} as the UNION of the pinned constituent component-releases' findings -- each reconstructed
	 * LIVE-AT-the-endpoint via {@link #reconstructLiveMetricsAt} -- then folds both endpoints into the shared
	 * attribution maps (keyed by constituent componentUuid) and runs the flag/overlay/DTO tail
	 * ({@link #assembleFromFoldedMaps}) verbatim, so the bucket / presentation contract matches org and
	 * component scope exactly. {@code isNewToOrganization} reads as "new to product".
	 *
	 * <p>Unlike the org / component path, constituents are NOT date-picked: the caller supplies the EXACT
	 * pinned releases of the product's from-product-release and to-product-release. A constituent present at
	 * both endpoints whose pinned release is the SAME but whose own scan changed in-window is caught by the
	 * per-endpoint {@link #reconstructLiveMetricsAt} (re-scan); a constituent re-pinned to a different
	 * version folds as appeared/resolved on the finding delta of the two pinned releases (re-pin).
	 *
	 * @param org           requesting organization (scopes the KEV/severity event overlay)
	 * @param fromConstituents pinned constituents of the product's from-product-release (release live-at-from)
	 * @param toConstituents   pinned constituents of the product's to-product-release   (release live-at-to)
	 * @param from          window start (inclusive)
	 * @param to            window end (inclusive)
	 * @return finding changes with the SAME attribution DTO as org / component scope
	 */
	public FindingChangesWithAttribution computeProductPostureDiff(
			UUID org,
			Collection<ProductConstituent> fromConstituents,
			Collection<ProductConstituent> toConstituents,
			ZonedDateTime from,
			ZonedDateTime to) {

		log.debug("PRODUCT-POSTURE-DIFF: {} from-constituents, {} to-constituents over [{}, {}]",
			fromConstituents != null ? fromConstituents.size() : 0,
			toConstituents != null ? toConstituents.size() : 0, from, to);

		AttributionMaps maps = new AttributionMaps();
		Map<String, Set<UUID>> inheritedInComponents = new HashMap<>();
		Map<String, EndpointEscalation> escalations = new HashMap<>();
		Set<UUID> allReleaseUuids = new HashSet<>();

		// Index each endpoint's pinned constituents by component so the from/to pinned release for the SAME
		// constituent component are diffed against each other (re-pin) rather than across components.
		Map<UUID, ProductConstituent> fromByComponent = indexConstituents(fromConstituents);
		Map<UUID, ProductConstituent> toByComponent = indexConstituents(toConstituents);

		Set<UUID> allComponents = new HashSet<>();
		allComponents.addAll(fromByComponent.keySet());
		allComponents.addAll(toByComponent.keySet());

		// Batched prefetch over the union of pinned constituent releases at BOTH endpoints: one grouped
		// events fetch + one org-settings lookup for the whole product diff, instead of a per-constituent
		// findInRange + retentionHorizon query pair per endpoint.
		Map<UUID, List<ReleaseData>> constituentReleases = new HashMap<>();
		for (ProductConstituent c : concatConstituents(fromConstituents, toConstituents)) {
			if (c != null && c.componentUuid() != null && c.release() != null) {
				constituentReleases.computeIfAbsent(c.componentUuid(), k -> new ArrayList<>()).add(c.release());
			}
		}
		PostureReconstructionPrefetch prefetch = prefetchPostureReconstruction(constituentReleases, from, to);

		for (UUID componentUuid : allComponents) {
			ProductConstituent fromC = fromByComponent.get(componentUuid);
			ProductConstituent toC = toByComponent.get(componentUuid);

			// Reconstruct each endpoint's constituent metrics LIVE-AT-the-endpoint from the EXACT pinned
			// release (no date-pick). A missing side (constituent added/removed between product releases)
			// contributes empty metrics -> the present side folds as appeared / resolved.
			ReleaseMetricsDto fromMetrics = reconstructConstituentMetrics(fromC, from, prefetch);
			ReleaseMetricsDto toMetrics = reconstructConstituentMetrics(toC, to, prefetch);

			// Attribution anchor: the to-pinned constituent release when present (mirrors the org path's
			// to-anchor for present/resolved), else the from-pinned release (resolved-only constituent).
			ProductConstituent anchorC = (toC != null && toC.release() != null) ? toC : fromC;
			if (anchorC == null || anchorC.release() == null) continue;
			ReleaseData anchorRelease = anchorC.release();
			allReleaseUuids.add(anchorRelease.getUuid());
			if (fromC != null && fromC.release() != null) allReleaseUuids.add(fromC.release().getUuid());

			String componentName = anchorC.componentName() != null ? anchorC.componentName() : "Unknown";
			ComponentAttribution anchorAttr = new ComponentAttribution(
				componentUuid, componentName,
				anchorRelease.getUuid(), anchorRelease.getVersion(),
				anchorRelease.getBranch(), null);

			foldEndpointsIntoMaps(fromMetrics, toMetrics, maps, componentUuid, anchorAttr,
				inheritedInComponents, escalations);
		}

		int totalConstituents = allComponents.size();
		return assembleFromFoldedMaps(org, maps, inheritedInComponents, escalations,
			allReleaseUuids, totalConstituents, from, to, prefetch.retentionHorizon());
	}

	private static Map<UUID, ProductConstituent> indexConstituents(Collection<ProductConstituent> constituents) {
		Map<UUID, ProductConstituent> byComponent = new HashMap<>();
		if (constituents == null) return byComponent;
		for (ProductConstituent c : constituents) {
			if (c == null || c.componentUuid() == null) continue;
			byComponent.putIfAbsent(c.componentUuid(), c);
		}
		return byComponent;
	}

	private static List<ProductConstituent> concatConstituents(
			Collection<ProductConstituent> a, Collection<ProductConstituent> b) {
		List<ProductConstituent> all = new ArrayList<>();
		if (a != null) all.addAll(a);
		if (b != null) all.addAll(b);
		return all;
	}

	/**
	 * Reconstructs a product constituent's findings LIVE-AT {@code at} from its EXACT pinned release. Returns
	 * empty metrics when the constituent (or its release) is absent at this endpoint -- that side then folds
	 * as appeared / resolved. Uses {@link #reconstructLiveMetricsAt}, so a re-scan of the SAME pinned release
	 * between the two endpoints is reflected.
	 */
	private ReleaseMetricsDto reconstructConstituentMetrics(ProductConstituent constituent, ZonedDateTime at,
			PostureReconstructionPrefetch prefetch) {
		if (constituent == null || constituent.release() == null) return new ReleaseMetricsDto();
		ReleaseData release = constituent.release();
		ReleaseMetricsDto current = release.getMetrics() != null ? release.getMetrics() : new ReleaseMetricsDto();
		return reconstructLiveMetricsAt(release, at, current, prefetch);
	}

	/**
	 * Reconstructs one (branch) endpoint posture: the branch-latest release created &lt;= {@code endpointTime}
	 * and the metrics that were LIVE at {@code endpointTime} for it.
	 *
	 * <p>The branch-latest-as-of-t anchor is found via
	 * {@code SharedReleaseService.getBranchLatestReleaseAtOrBeforeDate} (bounded LIMIT-1 per-branch lookup);
	 * when the anchor is already among {@code branchReleases} (the in-window set) that instance is reused to
	 * avoid a re-fetch. BOTH endpoints reconstruct the metrics live AT {@code endpointTime} via
	 * {@link #reconstructLiveMetricsAt}, which REVERSE-REPLAYS the anchor's {@code finding_change_events} in
	 * {@code (endpointTime, now]} onto its current metrics -- no {@code metrics_audit} read. When t is
	 * now/future (the {@code to ~ now} case) there are no later events, so it is the release's CURRENT
	 * metrics. If t predates the org's event-retention horizon, it falls back to CURRENT metrics (accepted,
	 * degrade-to-today behavior for that release only).
	 */
	private EndpointPosture reconstructBranchPosture(
			UUID branchUuid,
			List<ReleaseData> branchReleases,
			ZonedDateTime endpointTime,
			PostureEndpoint endpoint,
			ZonedDateTime windowTo,
			PostureReconstructionPrefetch prefetch) {

		// Prefer an already-loaded in-window release that is the branch-latest as-of endpointTime.
		ReleaseData anchor = null;
		for (ReleaseData r : branchReleases) { // sorted newest-first
			if (r.getCreatedDate() != null && !r.getCreatedDate().isAfter(endpointTime)) {
				anchor = r;
				break;
			}
		}
		if (anchor == null) {
			// The from-baseline (or a to-anchor) may sit OUTSIDE the fetched in-window set; fetch it.
			// Unlike the pre-filtered in-window list above, this lookup drops only CANCELLED/REJECTED, so
			// guard the retired case here or a retired out-of-window baseline would leak into the posture.
			anchor = sharedReleaseService
				.getBranchLatestReleaseAtOrBeforeDate(branchUuid, endpointTime)
				.filter(r -> !ReleaseLifecycle.isSupportEnded(r.getLifecycle()))
				.orElse(null);
		}
		if (anchor == null) {
			return new EndpointPosture(null, new ReleaseMetricsDto());
		}

		ReleaseMetricsDto currentMetrics = anchor.getMetrics() != null ? anchor.getMetrics() : new ReleaseMetricsDto();

		// Reconstruct the metrics live AT the endpoint from metrics_audit, for BOTH endpoints. This
		// self-handles the endpoint ~ now case: when endpointTime is now/future there is no audit row
		// overwritten after it, so reconstructLiveMetricsAt falls back to current metrics -- no explicit
		// now-check needed. A HISTORICAL to (to < now) is reconstructed to its true at-to posture rather
		// than diffing against TODAY's metrics.
		ReleaseMetricsDto liveAtEndpoint = reconstructLiveMetricsAt(anchor, endpointTime, currentMetrics, prefetch);
		return new EndpointPosture(anchor, liveAtEndpoint);
	}

	/**
	 * Reconstructs the metrics that were LIVE for {@code release} at {@code at} by REVERSE-REPLAYING the
	 * release's {@code finding_change_events} onto its CURRENT metrics ({@code fallbackCurrent}) -- NO
	 * {@code metrics_audit} read. The current metrics are the always-retained "now" snapshot; every event
	 * that occurred strictly after {@code at} is inverted (newest -> oldest) to walk the state backwards to
	 * {@code at} (see {@link #reverseReplay}).
	 *
	 * <p>Returns {@code fallbackCurrent} unchanged when there are no events after {@code at} (posture
	 * unchanged since {@code at}, includes the {@code to ~ now} case), OR when {@code at} predates the org's
	 * event-retention horizon (older events were purged and cannot be replayed -- degrade-to-current for this
	 * release only, the disclosed retention edge, now on the SAME single horizon the over-time changelog
	 * clamps to).
	 *
	 * <p>BATCHED path (org posture-diff N+1 elimination): when {@code prefetch} is non-null and covers
	 * {@code at}, the per-release {@code findInRange} query is replaced by a lookup into the prefetched
	 * events map. When {@code prefetch} is null (component / branch / product scopes, unit tests) it does a
	 * single-release event fetch. The replay logic is identical; only the SOURCE of the events differs.
	 */
	private ReleaseMetricsDto reconstructLiveMetricsAt(
			ReleaseData release, ZonedDateTime at, ReleaseMetricsDto fallbackCurrent,
			PostureReconstructionPrefetch prefetch) {

		UUID releaseUuid = release.getUuid();

		// Events for this release that occurred strictly AFTER `at` (up to now), plus the retention horizon.
		List<FindingChangeEvent> eventsAfterAt;
		ZonedDateTime retentionHorizon;
		if (prefetch != null && prefetch.covers(at)) {
			retentionHorizon = prefetch.retentionHorizon();
			eventsAfterAt = prefetch.eventsFor(releaseUuid).stream()
					.filter(ev -> ev.getChangeDate() != null && ev.getChangeDate().isAfter(at))
					.toList();
		} else {
			retentionHorizon = retentionHorizon(release.getOrg());
			eventsAfterAt = (release.getOrg() == null || releaseUuid == null)
					? List.of()
					: readFindingChangesInRange(release.getOrg(), List.of(releaseUuid), at, ZonedDateTime.now())
						.stream().filter(ev -> ev.getChangeDate() != null && ev.getChangeDate().isAfter(at))
						.toList();
		}

		// RETENTION EDGE (disclosed, accepted): `at` older than the event-retention horizon cannot be
		// reconstructed (events before the horizon were purged), so degrade to CURRENT metrics for this
		// release only -- the same fail-safe the metrics_audit path applied at its retention edge, now on a
		// SINGLE consistent horizon shared with the over-time changelog.
		if (retentionHorizon != null && at.isBefore(retentionHorizon)) {
			return fallbackCurrent;
		}

		// No events after `at`: the state live at `at` IS the release's current metrics. Nothing to reverse.
		if (eventsAfterAt.isEmpty()) {
			return fallbackCurrent;
		}

		return reverseReplay(fallbackCurrent, eventsAfterAt);
	}

	/**
	 * Reverse-replays {@code eventsAfterAt} (a release's {@code finding_change_events} that occurred
	 * strictly after an endpoint instant) onto its CURRENT metrics to reconstruct the metrics LIVE at that
	 * instant. Processes events NEWEST -> OLDEST, applying each transition's INVERSE:
	 * <ul>
	 *   <li>APPEARED -> the key was ABSENT before, so remove it;</li>
	 *   <li>RESOLVED -> it was PRESENT before, so re-add it (rebuilt from the event's denormalized payload,
	 *       which captured the finding as it was while still present);</li>
	 *   <li>KEV_ADDED / KEV_REMOVED -> restore the prior KEV flag (false / true);</li>
	 *   <li>SEVERITY_INCREASED / SEVERITY_DECREASED -> restore the prior ({@code previousSeverity})
	 *       severity.</li>
	 * </ul>
	 * Attribute inverses on an absent key are no-ops (a later APPEARED already removed it). Strict
	 * reverse-chronological order makes flapping (APPEARED/RESOLVED interleavings on one key) reconstruct
	 * correctly. Only the three finding-detail lists are populated -- the only fields the posture fold reads.
	 */
	private ReleaseMetricsDto reverseReplay(ReleaseMetricsDto current, List<FindingChangeEvent> eventsAfterAt) {
		Map<String, VulnerabilityDto> vulns = new LinkedHashMap<>();
		for (VulnerabilityDto v : nullSafe(current.getVulnerabilityDetails())) vulns.put(VULN_KEY.apply(v), v);
		Map<String, ViolationDto> violations = new LinkedHashMap<>();
		for (ViolationDto v : nullSafe(current.getViolationDetails())) violations.put(VIOLATION_KEY.apply(v), v);
		Map<String, WeaknessDto> weaknesses = new LinkedHashMap<>();
		for (WeaknessDto w : nullSafe(current.getWeaknessDetails())) weaknesses.put(WEAKNESS_KEY.apply(w), w);

		// NEWEST -> OLDEST. Break change_date ties with the monotonic to_metrics_revision (higher =
		// later = inverted first) so flapping transitions stamped at an identical instant (same-millisecond
		// saves, or backfill stamping equal revisionCreatedDates) still reconstruct deterministically and
		// in true revision order.
		List<FindingChangeEvent> reversed = new ArrayList<>(eventsAfterAt);
		reversed.sort(Comparator
				.comparing(FindingChangeEvent::getChangeDate, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparingInt(FindingChangeEvent::getToMetricsRevision)
				.reversed());

		for (FindingChangeEvent ev : reversed) {
			String key = ev.getFindingKey();
			if (key == null || ev.getChangeKind() == null || ev.getFindingKind() == null) continue;
			switch (ev.getFindingKind()) {
				case VULNERABILITY -> applyInverseVuln(vulns, key, ev);
				case VIOLATION -> applyInverseViolation(violations, key, ev);
				case WEAKNESS -> applyInverseWeakness(weaknesses, key, ev);
			}
		}

		ReleaseMetricsDto out = new ReleaseMetricsDto();
		out.setVulnerabilityDetails(new ArrayList<>(vulns.values()));
		out.setViolationDetails(new ArrayList<>(violations.values()));
		out.setWeaknessDetails(new ArrayList<>(weaknesses.values()));
		return out;
	}

	private void applyInverseVuln(Map<String, VulnerabilityDto> vulns, String key, FindingChangeEvent ev) {
		switch (ev.getChangeKind()) {
			case APPEARED -> vulns.remove(key);
			case RESOLVED -> vulns.put(key, vulnFromEvent(ev));
			case KEV_ADDED -> { VulnerabilityDto v = vulns.get(key); if (v != null) vulns.put(key, withVulnKev(v, false)); }
			case KEV_REMOVED -> { VulnerabilityDto v = vulns.get(key); if (v != null) vulns.put(key, withVulnKev(v, true)); }
			case SEVERITY_INCREASED, SEVERITY_DECREASED -> {
				VulnerabilityDto v = vulns.get(key);
				if (v != null) vulns.put(key, withVulnSeverity(v, parseSeverity(ev.getPreviousSeverity())));
			}
		}
	}

	private void applyInverseViolation(Map<String, ViolationDto> violations, String key, FindingChangeEvent ev) {
		switch (ev.getChangeKind()) {
			case APPEARED -> violations.remove(key);
			case RESOLVED -> violations.put(key, violationFromEvent(ev));
			default -> { /* violations carry no KEV/severity events */ }
		}
	}

	private void applyInverseWeakness(Map<String, WeaknessDto> weaknesses, String key, FindingChangeEvent ev) {
		switch (ev.getChangeKind()) {
			case APPEARED -> weaknesses.remove(key);
			case RESOLVED -> weaknesses.put(key, weaknessFromEvent(ev));
			case SEVERITY_INCREASED, SEVERITY_DECREASED -> {
				WeaknessDto w = weaknesses.get(key);
				if (w != null) weaknesses.put(key, withWeakSeverity(w, parseSeverity(ev.getPreviousSeverity())));
			}
			default -> { /* weaknesses carry no KEV events */ }
		}
	}

	private static VulnerabilityDto vulnFromEvent(FindingChangeEvent ev) {
		return new VulnerabilityDto(ev.getPurl(), ev.getVulnId(), parseSeverity(ev.getSeverity()),
				ev.getAliases() != null ? ev.getAliases() : Set.of(), Set.of(), Set.of(),
				parseAnalysisState(ev.getAnalysisState()), null, null, null, Set.of(), Set.of(), null, null,
				ev.getKnownExploited());
	}

	private static VulnerabilityDto withVulnKev(VulnerabilityDto v, Boolean kev) {
		return new VulnerabilityDto(v.purl(), v.vulnId(), v.severity(), v.aliases(), v.sources(),
				v.severities(), v.analysisState(), v.analysisDate(), v.attributedAt(), v.description(),
				v.cwes(), v.references(), v.published(), v.updated(), kev);
	}

	private static VulnerabilityDto withVulnSeverity(VulnerabilityDto v, VulnerabilitySeverity sev) {
		return new VulnerabilityDto(v.purl(), v.vulnId(), sev, v.aliases(), v.sources(),
				v.severities(), v.analysisState(), v.analysisDate(), v.attributedAt(), v.description(),
				v.cwes(), v.references(), v.published(), v.updated(), v.knownExploited());
	}

	private static ViolationDto violationFromEvent(FindingChangeEvent ev) {
		return new ViolationDto(ev.getPurl(), parseViolationType(ev.getViolationType()), null, null,
				Set.of(), parseAnalysisState(ev.getAnalysisState()), null, null);
	}

	private static WeaknessDto weaknessFromEvent(FindingChangeEvent ev) {
		return new WeaknessDto(ev.getCweId(), ev.getRuleId(),
				ev.getLocation() != null ? ev.getLocation() : "", null, parseSeverity(ev.getSeverity()),
				Set.of(), parseAnalysisState(ev.getAnalysisState()), null, null);
	}

	private static WeaknessDto withWeakSeverity(WeaknessDto w, VulnerabilitySeverity sev) {
		return new WeaknessDto(w.cweId(), w.ruleId(), w.location(), w.fingerprint(), sev,
				w.sources(), w.analysisState(), w.analysisDate(), w.attributedAt());
	}

	private static VulnerabilitySeverity parseSeverity(String raw) {
		if (raw == null) return null;
		try { return VulnerabilitySeverity.valueOf(raw); } catch (IllegalArgumentException e) { return null; }
	}

	private static ViolationType parseViolationType(String raw) {
		if (raw == null) return null;
		try { return ViolationType.valueOf(raw); } catch (IllegalArgumentException e) { return null; }
	}

	/**
	 * Folds a (component, branch) pair of endpoint postures into the shared attribution maps. For each
	 * finding key: present-at-to -&gt; presentIn(toAttr); present-at-from-only -&gt; resolvedIn(toAttr);
	 * present-at-to-not-from -&gt; appearedIn(toAttr, the branch-latest as-of {@code to} anchor);
	 * present at BOTH -&gt; inheritedInComponents. The finding DTO is seeded from whichever endpoint carries
	 * it (to preferred, else from) so buildAttributedFindings can render it.
	 */
	private void foldEndpointsIntoMaps(
			ReleaseMetricsDto fromMetrics,
			ReleaseMetricsDto toMetrics,
			AttributionMaps maps,
			UUID componentUuid,
			ComponentAttribution toAttr,
			Map<String, Set<UUID>> inheritedInComponents,
			Map<String, EndpointEscalation> escalations) {

		foldEndpointsForType(
			maps, fromMetrics.getVulnerabilityDetails(), toMetrics.getVulnerabilityDetails(),
			maps.vulns, VULN_KEY, componentUuid, toAttr, inheritedInComponents,
			escalations, VulnerabilityDto::knownExploited, VulnerabilityDto::severity);
		foldEndpointsForType(
			maps, fromMetrics.getViolationDetails(), toMetrics.getViolationDetails(),
			maps.violations, VIOLATION_KEY, componentUuid, toAttr, inheritedInComponents,
			escalations, v -> null, v -> null);
		foldEndpointsForType(
			maps, fromMetrics.getWeaknessDetails(), toMetrics.getWeaknessDetails(),
			maps.weaknesses, WEAKNESS_KEY, componentUuid, toAttr, inheritedInComponents,
			escalations, w -> null, WeaknessDto::severity);
	}

	private <T> void foldEndpointsForType(
			AttributionMaps maps,
			List<T> fromFindings,
			List<T> toFindings,
			Map<String, FindingAttribution<T>> findingMap,
			Function<T, String> keyExtractor,
			UUID componentUuid,
			ComponentAttribution toAttr,
			Map<String, Set<UUID>> inheritedInComponents,
			Map<String, EndpointEscalation> escalations,
			Function<T, Boolean> kevExtractor,
			Function<T, VulnerabilitySeverity> severityExtractor) {

		Map<String, T> fromByKey = indexBy(fromFindings, keyExtractor);
		Map<String, T> toByKey = indexBy(toFindings, keyExtractor);

		// Present at to: presentIn; if not present at from -> appeared; else inherited (present both).
		for (Map.Entry<String, T> e : toByKey.entrySet()) {
			String key = e.getKey();
			if (maps.rejects(key)) continue; // single-finding drill-down: ignore other keys
			FindingAttribution<T> fa = maps.get(findingMap, key);
			if (fa.finding == null) fa.finding = e.getValue();
			fa.addPresent(toAttr);
			if (fromByKey.containsKey(key)) {
				inheritedInComponents.computeIfAbsent(key, k -> new HashSet<>()).add(componentUuid);
			} else {
				fa.addAppeared(toAttr);
			}
			// Record the net from->to escalation state for this (component) key (S1 overlay gate).
			recordEscalation(escalations, key, fromByKey.get(key), e.getValue(),
					kevExtractor, severityExtractor);
		}
		// Present at from but NOT at to: resolved in this component (attributed to the to-anchor).
		for (Map.Entry<String, T> e : fromByKey.entrySet()) {
			String key = e.getKey();
			if (toByKey.containsKey(key)) continue;
			if (maps.rejects(key)) continue; // single-finding drill-down: ignore other keys
			FindingAttribution<T> fa = maps.get(findingMap, key);
			if (fa.finding == null) fa.finding = e.getValue();
			fa.addResolved(toAttr);
			// Present-at-from-only: record from-state so a net check sees the from side (to side absent).
			recordEscalation(escalations, key, e.getValue(), null, kevExtractor, severityExtractor);
		}
	}

	/**
	 * Per finding-key NET escalation state aggregated across components, used to gate the Worsened
	 * overlay (board task #38, phase 3 correctness fix S1). Since B1 reconstructs BOTH endpoints, the
	 * badge is decided on the actual from-state vs to-state -- not mere in-window event existence, which
	 * would badge a KEV-added-then-removed or severity-up-then-down that nets to no change.
	 *
	 * <p>Aggregated across components: {@code kevAtFrom}/{@code kevAtTo} = KEV set in ANY component's
	 * from/to endpoint state; {@code bestSeverityFrom}/{@code bestSeverityTo} = the most-severe (lowest
	 * {@link VulnerabilitySeverity} ordinal) seen at from/to across components (null = none seen).
	 */
	private static final class EndpointEscalation {
		boolean presentAtFrom;
		boolean kevAtFrom;
		boolean kevAtTo;
		VulnerabilitySeverity bestSeverityFrom;
		VulnerabilitySeverity bestSeverityTo;

		void observeFrom(boolean kev, VulnerabilitySeverity sev) {
			presentAtFrom = true; // observeFrom is called iff the finding is present at the from-endpoint
			if (kev) kevAtFrom = true;
			bestSeverityFrom = moreSevere(bestSeverityFrom, sev);
		}
		void observeTo(boolean kev, VulnerabilitySeverity sev) {
			if (kev) kevAtTo = true;
			bestSeverityTo = moreSevere(bestSeverityTo, sev);
		}
		/**
		 * true iff KEV is net-added on a finding present at BOTH endpoints (board task F10). A finding ABSENT
		 * at from that appears already-KEV at to is NEW, not "Newly-KEV" -- it belongs in the New bucket, so it
		 * must NOT badge here (symmetric with {@link #isNetSeverityIncreased}, which likewise never fires for a
		 * from-absent finding). Present-at-to is enforced separately by the caller's presentAtTo gate.
		 */
		boolean isNetNewlyKev() {
			return presentAtFrom && kevAtTo && !kevAtFrom;
		}
		/** true iff the to-severity is strictly more severe than the from-severity (net). */
		boolean isNetSeverityIncreased() {
			return bestSeverityTo != null && bestSeverityFrom != null
					&& bestSeverityTo.ordinal() < bestSeverityFrom.ordinal();
		}
	}

	/** The more-severe of two severities (lowest ordinal), null-tolerant. */
	private static VulnerabilitySeverity moreSevere(VulnerabilitySeverity a, VulnerabilitySeverity b) {
		if (a == null) return b;
		if (b == null) return a;
		return b.ordinal() < a.ordinal() ? b : a;
	}

	/** Records one finding's from/to KEV+severity into the per-key escalation aggregate. */
	private static <T> void recordEscalation(
			Map<String, EndpointEscalation> escalations, String key,
			T fromFinding, T toFinding,
			Function<T, Boolean> kevExtractor, Function<T, VulnerabilitySeverity> severityExtractor) {
		if (fromFinding == null && toFinding == null) return;
		EndpointEscalation esc = escalations.computeIfAbsent(key, k -> new EndpointEscalation());
		if (fromFinding != null) {
			esc.observeFrom(Boolean.TRUE.equals(kevExtractor.apply(fromFinding)),
					severityExtractor.apply(fromFinding));
		}
		if (toFinding != null) {
			esc.observeTo(Boolean.TRUE.equals(kevExtractor.apply(toFinding)),
					severityExtractor.apply(toFinding));
		}
	}

	/**
	 * The Newly-KEV / severity-increase overlay. A finding key is badged only when BOTH hold:
	 * <ol>
	 *   <li>an in-window {@code finding_change_events} row (KEV_ADDED / SEVERITY_INCREASED) exists for
	 *       it (the escalation actually happened inside [from, to], and carries {@code previousSeverity});
	 *   <li>the escalation is NET-true from the reconstructed {@code from}-state to the {@code to}-state
	 *       AND the finding is present at {@code to} (via {@link EndpointEscalation}) -- so a KEV
	 *       added-then-removed or a severity up-then-down inside the window nets out and is NOT badged
	 *       (S1). {@code previousSeverity} is the from-state severity.
	 * </ol>
	 * Present-at-to is implied by {@code isNetNewlyKev} / {@code isNetSeverityIncreased}: those require a
	 * to-state observation, which only exists when the finding is present at {@code to}.
	 *
	 * <p>USER-FACING SEMANTIC (board task F9): because the badge requires the finding to be present at BOTH
	 * endpoints, it counts "OPEN findings that worsened in this window", NOT every in-window escalation event.
	 * An escalation on a finding that was resolved off all branch tips before {@code to} nets out and is not
	 * badged (consistent with New/Resolved, which are also posture-anchored). The full stream of raw escalation
	 * events (including those on since-resolved findings) is the over-time changelog tab, not this overlay --
	 * so the badge count is legitimately lower than the raw KEV_ADDED / SEVERITY_INCREASED event count. The UI
	 * labels/tooltips say "open findings ... in this period" to convey this.
	 */
	private record WorsenedOverlay(
			Set<String> newlyKevKeys,
			Set<String> severityIncreasedKeys,
			Map<String, String> previousSeverityByKey) {}

	private WorsenedOverlay buildWorsenedOverlay(
			UUID org,
			Set<UUID> releaseUuids,
			ZonedDateTime from,
			ZonedDateTime to,
			AttributionMaps maps,
			Map<String, EndpointEscalation> escalations) {

		Set<String> newlyKev = new HashSet<>();
		Set<String> sevInc = new HashSet<>();
		Map<String, String> prevSevByKey = new HashMap<>();

		if (org == null || releaseUuids == null || releaseUuids.isEmpty()) {
			return new WorsenedOverlay(newlyKev, sevInc, prevSevByKey);
		}

		// present-at-to keys: any finding key that has at least one presentIn attribution.
		Set<String> presentAtTo = new HashSet<>();
		collectPresentKeys(maps.vulns, presentAtTo);
		collectPresentKeys(maps.violations, presentAtTo);
		collectPresentKeys(maps.weaknesses, presentAtTo);

		// Chunk the release-UUID IN-list (same bound as the reconstruction prefetch) so an org rollup with
		// tens of thousands of in-window releases cannot blow the JDBC parameter limit.
		List<UUID> releaseIds = new ArrayList<>(releaseUuids);
		for (int i = 0; i < releaseIds.size(); i += RECONSTRUCTION_PREFETCH_CHUNK) {
			List<UUID> chunk = releaseIds.subList(i, Math.min(i + RECONSTRUCTION_PREFETCH_CHUNK, releaseIds.size()));
			for (FindingChangeEvent ev : readFindingChangesInRange(org, chunk, from, to)) {
				String key = ev.getFindingKey();
				if (key == null || !presentAtTo.contains(key)) continue;
				EndpointEscalation esc = escalations.get(key);
				if (esc == null) continue; // no reconstructed endpoint state -> cannot confirm a net change
				switch (ev.getChangeKind()) {
					// Badge only when the escalation is NET-true from->to, not merely event-present in-window.
					case KEV_ADDED -> {
						if (esc.isNetNewlyKev()) newlyKev.add(key);
					}
					case SEVERITY_INCREASED -> {
						if (esc.isNetSeverityIncreased()) {
							sevInc.add(key);
							// from-state severity as the pre-escalation baseline.
							String prevSev = esc.bestSeverityFrom != null ? esc.bestSeverityFrom.name() : null;
							prevSevByKey.putIfAbsent(key, prevSev);
						}
					}
					default -> { /* APPEARED / RESOLVED: not a worsened annotation */ }
				}
			}
		}
		return new WorsenedOverlay(newlyKev, sevInc, prevSevByKey);
	}

	private static <T> void collectPresentKeys(Map<String, FindingAttribution<T>> findingMap, Set<String> out) {
		for (Map.Entry<String, FindingAttribution<T>> e : findingMap.entrySet()) {
			if (e.getValue().hasPresent()) out.add(e.getKey());
		}
	}

	/**
	 * Decorates a finding's {@link FindingFlags} with the Worsened annotations (isNewlyKev /
	 * isSeverityIncreased / previousSeverity) from the overlay, stamping them onto its
	 * {@link OrgLevelContext}. Net/still-present flags are untouched -- a KEV-added net-New finding stays
	 * net-New and is badge-only (no double count). Findings not in the overlay keep the default false/null.
	 */
	private FindingFlags decorateWithOverlay(String key, FindingFlags flags, WorsenedOverlay overlay) {
		boolean newlyKev = overlay.newlyKevKeys().contains(key);
		boolean sevInc = overlay.severityIncreasedKeys().contains(key);
		String prevSev = overlay.previousSeverityByKey().get(key);
		OrgLevelContext ctx = flags.orgContext();
		if (ctx == null) {
			return flags; // component-level path never uses the overlay
		}
		OrgLevelContext decorated = new OrgLevelContext(
			ctx.isNewToOrganization(), ctx.wasPreviouslyReported(), ctx.isPartiallyResolved(),
			ctx.isFullyResolved(), ctx.isInheritedInAllComponents(), ctx.componentCount(),
			ctx.affectedComponentNames(),
			newlyKev, sevInc, prevSev);
		return new FindingFlags(flags.isNetAppeared(), flags.isStillPresent(), flags.isNetResolved(), decorated);
	}

	/**
	 * POSTURE-DIFF-ONLY correction (board task #38, phase 3 fix S3). The shared
	 * {@link #computeOrgFindingFlags} sets {@code isNetAppeared} purely from {@code appearedIn} /
	 * {@code resolvedIn}, ignoring cross-component inheritance: a finding that is INHERITED (present at
	 * both endpoints) on component A but folds as APPEARED on a mid-window-forked branch B is org-New in
	 * neither the badge sense nor reality, yet the raw flag would count it in {@code totalAppeared}.
	 *
	 * <p>We suppress {@code isNetAppeared} ONLY when the finding is inherited in at least one component
	 * AND its org context is not New-to-org -- i.e. it was previously reported elsewhere. This mirrors the
	 * org-New badge ({@code isNewToOrganization}) exactly and is applied on this path only, so the shared
	 * flag engine and the flag-OFF legacy path are untouched (flag isolation holds). The finding still
	 * renders (with its appearedIn attribution and org context); only the headline Net-New tally drops it.
	 */
	private FindingFlags suppressCrossComponentInheritedNew(
			String key, FindingFlags flags, Map<String, Set<UUID>> inheritedInComponents) {
		if (!flags.isNetAppeared()) return flags;
		OrgLevelContext ctx = flags.orgContext();
		if (ctx == null) return flags;
		boolean inheritedSomewhere = !inheritedInComponents.getOrDefault(key, Collections.emptySet()).isEmpty();
		if (inheritedSomewhere && !ctx.isNewToOrganization()) {
			// Org-inherited (previously reported): not net-New at org level. Keep still-present true so the
			// finding is not silently dropped from every headline bucket.
			return new FindingFlags(false, true, flags.isNetResolved(), ctx);
		}
		return flags;
	}

	/**
	 * For a given target release, return all vulnerabilities that were detected in some prior
	 * release on the target's lineage (its branch + fork-point ancestry; or unioned across child
	 * component releases for products) but are absent from the target's current metrics.
	 *
	 * <p>Reuses the existing {@link #compareMetrics} primitive on each consecutive release pair
	 * along the lineage. Iterates oldest -> newest so that, for CVEs that resolve more than once
	 * on a lineage, the latest resolution wins by overwrite.
	 *
	 * @param target            the release we are emitting VEX for
	 * @param recurseChildren   if {@code true} and {@code target} is a {@code PRODUCT}, recurse
	 *                          into the current child component releases and union the results
	 *                          (deduplicating by {@code (vulnId, purl)} with latest-resolution
	 *                          preferred)
	 * @param cutOffDate        if non-null, exclude resolutions whose resolving-release
	 *                          {@code createdDate} is strictly after this date (used for
	 *                          historical snapshots)
	 * @return historical-resolved findings; empty list if none
	 */
	public List<HistoricallyResolvedFinding> findHistoricallyResolvedForRelease(
			ReleaseData target,
			boolean recurseChildren,
			ZonedDateTime cutOffDate) throws Exception {

		if (target == null) return List.of();

		// Product short-circuit: walk each child release's lineage and union (latest-resolution wins).
		if (recurseChildren && target.getComponent() != null) {
			Optional<ComponentData> compOpt = getComponentService.getComponentData(target.getComponent());
			if (compOpt.isPresent() && compOpt.get().getType() == ComponentType.PRODUCT) {
				Map<String, HistoricallyResolvedFinding> productMap = new LinkedHashMap<>();
				Set<ReleaseData> children = sharedReleaseService.unwindReleaseDependencies(target);
				for (ReleaseData childRelease : children) {
					// recurseChildren=false: a child's release dependency tree is (per data model)
					// a single component, not nested products.
					List<HistoricallyResolvedFinding> childResults =
							findHistoricallyResolvedForRelease(childRelease, false, cutOffDate);
					for (HistoricallyResolvedFinding f : childResults) {
						String key = f.vulnerability().vulnId() + FINDING_KEY_DELIMITER
								+ (f.vulnerability().purl() != null ? f.vulnerability().purl() : "");
						HistoricallyResolvedFinding existing = productMap.get(key);
						if (existing == null
								|| (f.resolvingReleaseCreatedDate() != null
									&& existing.resolvingReleaseCreatedDate() != null
									&& f.resolvingReleaseCreatedDate().isAfter(existing.resolvingReleaseCreatedDate()))) {
							productMap.put(key, f);
						}
					}
				}
				return new ArrayList<>(productMap.values());
			}
		}

		List<ReleaseData> lineage = buildLineage(target);
		if (cutOffDate != null) {
			lineage = lineage.stream()
					.filter(r -> r.getCreatedDate() != null && !r.getCreatedDate().isAfter(cutOffDate))
					.collect(Collectors.toList());
		}
		if (lineage.size() < 2) return List.of();

		Set<String> targetVulnKeys = new HashSet<>();
		if (target.getMetrics() != null && target.getMetrics().getVulnerabilityDetails() != null) {
			for (VulnerabilityDto v : target.getMetrics().getVulnerabilityDetails()) {
				targetVulnKeys.add(VULN_KEY.apply(v));
			}
		}

		Map<String, HistoricallyResolvedFinding> resolvedMap = new LinkedHashMap<>();
		for (int i = 0; i < lineage.size() - 1; i++) {
			ReleaseData older = lineage.get(i);
			ReleaseData newer = lineage.get(i + 1);
			if (older.getMetrics() == null || newer.getMetrics() == null) continue;
			FindingChangesRecord changes = compareMetrics(older.getMetrics(), newer.getMetrics());
			if (changes == null || changes.resolvedVulnerabilities() == null) continue;
			for (VulnerabilityDto resolvedVuln : changes.resolvedVulnerabilities()) {
				String key = VULN_KEY.apply(resolvedVuln);
				if (targetVulnKeys.contains(key)) continue;
				resolvedMap.put(key, new HistoricallyResolvedFinding(
						resolvedVuln,
						newer.getUuid(),
						newer.getVersion(),
						newer.getCreatedDate()));
			}
		}
		return new ArrayList<>(resolvedMap.values());
	}

	/**
	 * Build the lineage release list -- every release that {@code target} "inherits" from,
	 * ordered oldest -> newest, ending at {@code target}. Walks the target's branch up to and
	 * including {@code target}, then traverses fork points to ancestor branches recursively.
	 *
	 * <p>Mirrors the changelog's {@code processForkPointAndPairwise} fork-point convention:
	 * a "different-branch" return from {@code findPreviousReleasesOfBranchForRelease} is a
	 * fork point; a same-branch return means we've already covered the predecessor.
	 */
	private List<ReleaseData> buildLineage(ReleaseData target) {
		LinkedList<ReleaseData> chain = new LinkedList<>();
		Set<UUID> visited = new HashSet<>();
		Map<UUID, Optional<UUID>> baseBranchCache = new HashMap<>();

		ReleaseData cursor = target;
		while (cursor != null && !visited.contains(cursor.getBranch())) {
			visited.add(cursor.getBranch());

			// sorted=false: we re-sort by createdDate below; the version-aware sort would cost
			// two extra DB lookups (BranchData + ComponentData) per iteration for a result we
			// immediately discard.
			List<ReleaseData> branchReleases = sharedReleaseService
					.listReleaseDataOfBranch(cursor.getBranch(), Integer.MAX_VALUE, /*sorted=*/ false);
			if (branchReleases == null) branchReleases = List.of();
			ZonedDateTime cursorCreated = cursor.getCreatedDate();
			List<ReleaseData> filtered = new ArrayList<>();
			for (ReleaseData r : branchReleases) {
				// Skip CANCELLED / REJECTED -- their metrics may be incomplete and a null pair would
				// silently break the chain (older→cancelled and cancelled→newer both skipped, leaving
				// older never compared with newer).
				if (r.getLifecycle() == ReleaseLifecycle.CANCELLED
						|| r.getLifecycle() == ReleaseLifecycle.REJECTED) {
					continue;
				}
				if (r.getCreatedDate() != null && cursorCreated != null
						&& !r.getCreatedDate().isAfter(cursorCreated)) {
					filtered.add(r);
				}
			}
			filtered.sort(Comparator.comparing(ReleaseData::getCreatedDate));
			chain.addAll(0, filtered);

			if (filtered.isEmpty()) break;
			ReleaseData oldestOfBranch = filtered.get(0);

			UUID forkPointId = sharedReleaseService.findPreviousReleasesOfBranchForRelease(
					cursor.getBranch(), oldestOfBranch.getUuid(), oldestOfBranch,
					/*componentData=*/ null, baseBranchCache);
			if (forkPointId == null) break;

			Optional<ReleaseData> forkOpt = sharedReleaseService.getReleaseData(forkPointId);
			if (forkOpt.isEmpty()) break;
			ReleaseData fork = forkOpt.get();
			if (fork.getBranch().equals(cursor.getBranch())) break;

			cursor = fork;
		}

		return chain;
	}

	// ==================================================================================
	// metrics_audit "Finding changes over time" (board task #37)
	//
	// Surfaces re-scan-driven finding changes that the release-anchored changelog cannot
	// see: a finding that appears/resolves/escalates between scans of the SAME release
	// (no new release). Reads the metrics_audit history (persisted on every metrics save)
	// and diffs the consecutive snapshot timeline per release. This is purely additive --
	// it does not touch compareMetrics' behavior or the existing changelog paths.
	// ==================================================================================

	/**
	 * Lightweight per-release context the caller already has from its perspective-filtered
	 * component/release resolution. Passing it avoids re-querying releases or component names.
	 */
	public record AuditChangeReleaseContext(
			UUID releaseUuid,
			String version,
			UUID componentUuid,
			String componentName,
			ReleaseLifecycle lifecycle,
			ReleaseMetricsDto liveMetrics) {}

	/**
	 * Computes the flat, date-sorted list of re-scan-driven finding changes for the given
	 * releases over [from, to], reading the persisted {@code metrics_audit} history.
	 *
	 * <p>Per release we build the ordered snapshot timeline
	 * {@code [lastRevisionBefore(from)?, audit rows in window..., LIVE]} and diff each
	 * consecutive pair. Each change is bucketed at the date the older snapshot was overwritten
	 * (= the older audit row's {@code revisionCreatedDate}), which is when the newer state came
	 * into effect; only changes whose date falls in [from, to] are emitted. attributedAt is never
	 * consulted -- it is min-preserved ("first ever seen") and cannot represent resolution or later
	 * change. CANCELLED / REJECTED releases are skipped (mirrors the lineage path).
	 *
	 * @param releaseContexts perspective-filtered releases (UUID/version/component/live metrics)
	 * @param from            window start (inclusive)
	 * @param to              window end (inclusive)
	 * @return date-sorted change records; empty list if none
	 */
	public List<MetricsRevisionFindingChange> computeMetricsAuditChanges(
			Collection<AuditChangeReleaseContext> releaseContexts,
			ZonedDateTime from,
			ZonedDateTime to) {

		if (releaseContexts == null || releaseContexts.isEmpty() || from == null || to == null) {
			return List.of();
		}

		// Index eligible releases by UUID; skip CANCELLED / REJECTED.
		Map<UUID, AuditChangeReleaseContext> byUuid = new LinkedHashMap<>();
		for (AuditChangeReleaseContext ctx : releaseContexts) {
			if (ctx == null || ctx.releaseUuid() == null) continue;
			if (ctx.lifecycle() == ReleaseLifecycle.CANCELLED
					|| ctx.lifecycle() == ReleaseLifecycle.REJECTED) {
				continue;
			}
			byUuid.put(ctx.releaseUuid(), ctx);
		}
		if (byUuid.isEmpty()) return List.of();

		Set<UUID> releaseUuids = byUuid.keySet();
		String entityType = MetricsEntityType.RELEASE.name();

		// In-window audit rows (entity_uuid scoped -> NULL-org rows still returned), ordered
		// by (entity_uuid, metrics_revision).
		List<MetricsAudit> inWindow = metricsAuditRepository.findRevisionsInRange(
				entityType, releaseUuids, from, to);
		// The single latest audit row strictly before the window per release (timeline seed).
		List<MetricsAudit> seedRows = metricsAuditRepository.findLatestRevisionBeforeDate(
				entityType, releaseUuids, from);

		Map<UUID, List<MetricsAudit>> windowByRelease = inWindow.stream()
				.collect(Collectors.groupingBy(MetricsAudit::getEntityUuid));
		Map<UUID, MetricsAudit> seedByRelease = seedRows.stream()
				.collect(Collectors.toMap(MetricsAudit::getEntityUuid, Function.identity(), (a, b) -> a));

		List<MetricsRevisionFindingChange> changes = new ArrayList<>();

		for (AuditChangeReleaseContext ctx : byUuid.values()) {
			UUID releaseUuid = ctx.releaseUuid();
			List<MetricsAudit> window = windowByRelease.getOrDefault(releaseUuid, List.of());
			MetricsAudit seed = seedByRelease.get(releaseUuid);

			// No in-window audit rows -> no overwrite happened in the window -> nothing to surface.
			if (window.isEmpty()) continue;

			// Build the ordered snapshot timeline. Each entry carries the metrics that were in
			// effect AND the date that state was overwritten (revisionCreatedDate). The seed's date
			// is < from so its outgoing transition is filtered out below; it only serves as a
			// baseline so the first in-window finding does not spuriously register as APPEARED.
			List<TimelineSnapshot> timeline = new ArrayList<>();
			if (seed != null) {
				timeline.add(new TimelineSnapshot(toMetricsDto(seed.getMetrics()), seed.getRevisionCreatedDate()));
			}
			for (MetricsAudit a : window) {
				timeline.add(new TimelineSnapshot(toMetricsDto(a.getMetrics()), a.getRevisionCreatedDate()));
			}
			// LIVE snapshot is terminal: it is never the "older" element of a pair, so its (absent)
			// change date is never used.
			ReleaseMetricsDto liveMetrics = ctx.liveMetrics() != null ? ctx.liveMetrics() : new ReleaseMetricsDto();
			timeline.add(new TimelineSnapshot(liveMetrics, null));

			for (int i = 0; i < timeline.size() - 1; i++) {
				TimelineSnapshot older = timeline.get(i);
				TimelineSnapshot newer = timeline.get(i + 1);
				// The transition older->newer occurred when older was overwritten == older's
				// revisionCreatedDate. Only emit changes whose date lands inside the window.
				ZonedDateTime changeDate = older.changeDate();
				if (changeDate == null || changeDate.isBefore(from) || changeDate.isAfter(to)) {
					continue;
				}
				appendTransitionChanges(changes, ctx, changeDate, older.metrics(), newer.metrics());
			}
		}

		changes.sort(Comparator.comparing(MetricsRevisionFindingChange::changeDate));
		return changes;
	}

	/** A single point on a release's metrics-snapshot timeline. */
	private record TimelineSnapshot(ReleaseMetricsDto metrics, ZonedDateTime changeDate) {}

	/**
	 * READ PATH (board task #38, phase 3). Loads the flat, date-sorted list of re-scan-driven finding
	 * changes for the given releases over {@code [from, to]} directly from the persisted
	 * {@code finding_change_events} diff table, instead of re-walking and re-diffing the heavy
	 * {@code metrics_audit} snapshots ({@link #computeMetricsAuditChanges}).
	 *
	 * <p>Each persisted {@link FindingChangeEvent} row is mapped back onto the read DTO via the SAME
	 * {@link #toRevisionFindingChange} projection the audit-read path used, so the changelog output is
	 * byte-identical regardless of source -- the GraphQL contract and UI (#163) are unaffected.
	 *
	 * <p>The {@code releaseUuids} set continues to carry the perspective/authorization boundary (the
	 * caller passes only releases it has already resolved through the perspective filter), exactly as
	 * the audit-read path did. CANCELLED / REJECTED releases need no read-time filter -- they never
	 * emitted events at write time -- but the release-set filter still excludes anything unauthorized.
	 * The repository query already orders by {@code change_date}; the explicit re-sort is a cheap,
	 * source-agnostic guarantee (it matches {@link #computeMetricsAuditChanges}' final sort).
	 *
	 * <p><b>Retention clamp (board task #38, phase 3).</b> An org may opt into a bounded finding-change
	 * window via {@code OrganizationData.Settings.findingChangeRetentionDays} (retention is DISABLED by
	 * default = full history; the physical v1 purge sweep was retired with the v1/v2 tables in V64, but
	 * this READ-clamp guardrail is deliberately kept -- see the decommission runbook). When enabled, rows
	 * older than {@code now - retentionDays} are treated as unavailable, so reading with the caller's raw
	 * {@code from} could span a horizon for which the data no longer exists. We clamp the effective
	 * lower bound to {@code max(from, now - retentionDays)} -- reading the SAME setting the retention
	 * sweep uses -- so the query window never exceeds what retention keeps. Only this over-time read is
	 * clamped; the release-anchored changelog (keyed off release {@code created_date}) is unaffected.
	 * When the clamp moves the lower bound, {@link OverTimeFindingChangesResult#clampedSince()} carries
	 * the effective bound so the UI can disclose that older changes were not surfaced; it is null when
	 * the requested window already sat within the retention horizon.
	 *
	 * @param org          the requesting organization (scopes the read; never null on this path)
	 * @param releaseUuids perspective-filtered release UUIDs (the authorization boundary)
	 * @param from         window start (inclusive)
	 * @param to           window end (inclusive)
	 * @return date-sorted change records plus the effective clamp lower bound; empty/null if none
	 */
	/** Effective read lower bound after the retention clamp, plus the disclosed {@code clampedSince} (null when unclamped). */
	private record RetentionClamp(ZonedDateTime effectiveFrom, ZonedDateTime clampedSince) {}

	/**
	 * Clamps {@code from} up to the org's finding-change retention horizon (rows older than
	 * {@code now - retentionDays} are treated as unavailable). Retention is
	 * DISABLED by default (full history) -> no horizon, no clamp. Shared by {@link #loadOverTimeFindingChanges}
	 * and {@link #loadFindingChangeTimeline} so the inline and drawer {@code since} disclosures cannot desync.
	 */
	private RetentionClamp clampToRetention(UUID org, ZonedDateTime from) {
		OrganizationData.Settings settings = getOrganizationService.getOrganizationData(org)
				.map(OrganizationData::getSettings).orElse(null);
		if (settings != null && settings.isFindingChangeRetentionEnabled()) {
			ZonedDateTime horizon = ZonedDateTime.now().minusDays(settings.getFindingChangeRetentionDays());
			if (from.isBefore(horizon)) {
				return new RetentionClamp(horizon, horizon);
			}
		}
		return new RetentionClamp(from, null);
	}

	public OverTimeFindingChangesResult loadOverTimeFindingChanges(
			UUID org,
			Collection<UUID> releaseUuids,
			ZonedDateTime from,
			ZonedDateTime to) {

		if (org == null || releaseUuids == null || releaseUuids.isEmpty() || from == null || to == null) {
			return OverTimeFindingChangesResult.EMPTY;
		}

		// Cap the query window to the retention horizon (shared with loadFindingChangeTimeline so the inline
		// `since` and the drawer `since` cannot desync): rows older than now - retentionDays have been purged.
		RetentionClamp clamp = clampToRetention(org, from);
		ZonedDateTime effectiveFrom = clamp.effectiveFrom();
		ZonedDateTime clampedSince = clamp.clampedSince();

		Map<UUID, String> branchNameCache = new HashMap<>();
		List<MetricsRevisionFindingChange> changes = new ArrayList<>();
		for (FindingChangeEvent ev : readFindingChangesInRange(org, releaseUuids, effectiveFrom, to)) {
			changes.add(toRevisionFindingChange(ev, branchNameCache));
		}
		changes.sort(Comparator.comparing(MetricsRevisionFindingChange::changeDate));
		// Payload control: the inline changelog carries only the newest OVER_TIME_INLINE_CAP events (this is
		// the "recent activity" preview -- a heavy org can accrue tens of thousands). {@code total} discloses
		// the full count; the full/per-finding timeline is paged via findingChangeTimelineByDate.
		int total = changes.size();
		List<MetricsRevisionFindingChange> inline = total > OVER_TIME_INLINE_CAP
			? new ArrayList<>(changes.subList(total - OVER_TIME_INLINE_CAP, total)) // newest N (list is date-ASC)
			: changes;
		return new OverTimeFindingChangesResult(inline, clampedSince, total);
	}

	/**
	 * ORG-scope, rescan-inclusive analogue of {@link #loadOverTimeFindingChanges} for the changelog's inline
	 * over-time list (board task #42). Reads EVERY authorized finding-change whose {@code changeDate} is in
	 * {@code [from,to]} org-wide (via the v3 org+component scan), NOT bounded to a set of in-window-produced
	 * releases -- so re-scan-driven changes on components that shipped nothing in the window surface. The
	 * {@code allowedComponentUuids} set carries the perspective/authorization boundary; {@code
	 * excludedBranchUuids} (EXCLUDED findingAnalyticsParticipation) are dropped to match the analytics chart.
	 * v3-only ({@link #isOrgPostureReadAvailable}); returns EMPTY otherwise so the caller falls back to the
	 * release-anchored read. Same retention clamp / inline cap / {@code total} semantics as the release-scoped
	 * method -- and, like {@link #loadOverTimeFindingChanges}, sorts date-ASC then tail-slices the newest
	 * {@code OVER_TIME_INLINE_CAP} (NOT newest-first like the paginated {@link #loadOrgPostureTimeline}).
	 */
	public OverTimeFindingChangesResult loadOrgPostureOverTime(
			UUID org, Collection<UUID> allowedComponentUuids, Set<UUID> excludedBranchUuids,
			ZonedDateTime from, ZonedDateTime to) {

		if (org == null || allowedComponentUuids == null || allowedComponentUuids.isEmpty()
				|| from == null || to == null || !isOrgPostureReadAvailable(org)) {
			return OverTimeFindingChangesResult.EMPTY;
		}
		RetentionClamp clamp = clampToRetention(org, from);
		List<FindingChangeEvent> events;
		try {
			events = findingDimBackfillService.hydrateInRangeByComponentsV3(org, allowedComponentUuids,
					clamp.effectiveFrom(), to, PageRequest.of(0, ORG_POSTURE_SCAN_CAP, Sort.by(Sort.Direction.DESC, "changeDate")));
		} catch (RuntimeException e) {
			log.error("org posture over-time read failed for org {} -- returning empty for this window", org, e);
			return OverTimeFindingChangesResult.EMPTY;
		}
		if (events.size() >= ORG_POSTURE_SCAN_CAP) {
			log.warn("org posture over-time scan hit the {}-row cap for org {} in [{}, {}] -- oldest events "
					+ "omitted; narrow the window to see them", ORG_POSTURE_SCAN_CAP, org, clamp.effectiveFrom(), to);
		}
		Map<UUID, String> branchNameCache = new HashMap<>();
		List<MetricsRevisionFindingChange> changes = new ArrayList<>();
		for (FindingChangeEvent ev : events) {
			if (excludedBranchUuids != null && ev.getBranchUuid() != null
					&& excludedBranchUuids.contains(ev.getBranchUuid())) {
				continue;
			}
			changes.add(toRevisionFindingChange(ev, branchNameCache));
		}
		changes.sort(Comparator.comparing(MetricsRevisionFindingChange::changeDate));
		int total = changes.size();
		List<MetricsRevisionFindingChange> inline = total > OVER_TIME_INLINE_CAP
			? new ArrayList<>(changes.subList(total - OVER_TIME_INLINE_CAP, total))
			: changes;
		return new OverTimeFindingChangesResult(inline, clamp.clampedSince(), total);
	}

	/**
	 * Max over-time finding-change events materialized inline on the changelog response (newest-first slice).
	 * The full timeline (and per-finding drill-down) is served by {@code findingChangeTimelineByDate}.
	 */
	public static final int OVER_TIME_INLINE_CAP = 1000;

	/**
	 * Hard bound on rows hydrated by {@link #loadOrgPostureTimeline}. Unlike the release-set-bounded drawer
	 * read, the org posture read is not release-scoped, so a very wide window could otherwise materialize a
	 * large slice of history. The scan takes the NEWEST rows first; if the cap is hit the oldest tail of the
	 * window is omitted (logged, never silent). Generous vs realistic changelog windows.
	 */
	static final int ORG_POSTURE_SCAN_CAP = 20000;

	/**
	 * Result of {@link #loadOverTimeFindingChanges}: the date-sorted over-time finding changes (capped to the
	 * newest {@link #OVER_TIME_INLINE_CAP}) plus the effective clamp lower bound and the FULL {@code total}
	 * event count (>= changes.size() when capped). {@code clampedSince} is non-null ONLY when the requested
	 * {@code from} predated the retention horizon and the window was capped to it (surfaced to the UI as
	 * {@code overTimeFindingChangesSince}).
	 */
	public record OverTimeFindingChangesResult(
			List<MetricsRevisionFindingChange> changes,
			ZonedDateTime clampedSince,
			int total) {

		public static final OverTimeFindingChangesResult EMPTY =
				new OverTimeFindingChangesResult(List.of(), null, 0);
	}

	/**
	 * Paginated over-time timeline behind the capped inline {@code overTimeFindingChanges} -- the drawer's
	 * source. Reads the (bounded) release-set events over {@code [from, to]}, optionally filters to a single
	 * {@code findingKeyFilter}, sorts NEWEST-first, and returns the requested page plus the FULL {@code total}
	 * and the retention clamp bound. Same retention clamp as {@link #loadOverTimeFindingChanges}. Cost is one
	 * bounded release-set read (the release set carries the perspective/authorization boundary, as elsewhere).
	 */
	public FindingChangeTimelinePage loadFindingChangeTimeline(
			UUID org, Collection<UUID> releaseUuids, ZonedDateTime from, ZonedDateTime to,
			String findingKeyFilter, int page, int pageSize) {

		if (org == null || releaseUuids == null || releaseUuids.isEmpty() || from == null || to == null || pageSize <= 0) {
			return FindingChangeTimelinePage.EMPTY;
		}
		RetentionClamp clamp = clampToRetention(org, from);
		ZonedDateTime effectiveFrom = clamp.effectiveFrom();
		ZonedDateTime clampedSince = clamp.clampedSince();
		Map<UUID, String> branchNameCache = new HashMap<>();
		List<MetricsRevisionFindingChange> all = new ArrayList<>();
		for (FindingChangeEvent ev : readFindingChangesInRange(org, releaseUuids, effectiveFrom, to)) {
			if (findingKeyFilter != null && !findingKeyFilter.equals(ev.getFindingKey())) {
				continue;
			}
			all.add(toRevisionFindingChange(ev, branchNameCache));
		}
		all.sort(Comparator.comparing(MetricsRevisionFindingChange::changeDate).reversed()); // newest first
		int total = all.size();
		int fromIdx = Math.min(Math.max(0, page) * pageSize, total);
		int toIdx = Math.min(fromIdx + pageSize, total);
		// Release-anchored read is unbounded, so total is the TRUE full count -> never truncated.
		return new FindingChangeTimelinePage(
				new ArrayList<>(all.subList(fromIdx, toIdx)), total, Math.max(0, page), pageSize, clampedSince, false);
	}

	/**
	 * Whether the org "posture over time" read ({@link #loadOrgPostureTimeline}) is available for {@code org}.
	 * It is a v3 (branch-grain) read; v3 is now the sole finding-change store, so it is available whenever the
	 * hydration service is wired and the org is concrete. Callers that get {@code false} (e.g. a unit-scoped
	 * service without the backfill bean) must use the release-anchored {@link #loadFindingChangeTimeline}.
	 */
	public boolean isOrgPostureReadAvailable(UUID org) {
		return org != null && findingDimBackfillService != null;
	}

	/**
	 * ORG posture-over-time timeline (board task #39): every finding-change whose {@code changeDate} is in
	 * {@code [from,to]} for the authorized {@code allowedComponentUuids}, INCLUDING re-scan-driven changes
	 * on releases produced before the window -- the movement the release-anchored {@link
	 * #loadFindingChangeTimeline} structurally misses. The component set carries the perspective/authorization
	 * boundary (it is the same set the changelog resolves through the perspective/org filter); events on
	 * {@code excludedBranchUuids} (findingAnalyticsParticipation = EXCLUDED, to match the analytics chart) are
	 * dropped. v3-only: {@link #isOrgPostureReadAvailable} must be true (verified by the caller). Same
	 * retention clamp, newest-first ordering, cap disclosure and {@code total} semantics as the drawer read.
	 */
	public FindingChangeTimelinePage loadOrgPostureTimeline(
			UUID org, Collection<UUID> allowedComponentUuids, Set<UUID> excludedBranchUuids,
			ZonedDateTime from, ZonedDateTime to, String findingKeyFilter, int page, int pageSize) {

		if (org == null || allowedComponentUuids == null || allowedComponentUuids.isEmpty()
				|| from == null || to == null || pageSize <= 0) {
			return FindingChangeTimelinePage.EMPTY;
		}
		RetentionClamp clamp = clampToRetention(org, from);
		ZonedDateTime effectiveFrom = clamp.effectiveFrom();
		ZonedDateTime clampedSince = clamp.clampedSince();

		List<FindingChangeEvent> events;
		try {
			// Org-wide read (not release-set-bounded): bound the scan to the newest ORG_POSTURE_SCAN_CAP rows
			// so a very wide window cannot materialize the whole history. Realistic changelog windows are far
			// below the cap; if it IS hit, the oldest tail is omitted and we log it (no silent truncation).
			events = findingDimBackfillService.hydrateInRangeByComponentsV3(org, allowedComponentUuids,
					effectiveFrom, to, PageRequest.of(0, ORG_POSTURE_SCAN_CAP, Sort.by(Sort.Direction.DESC, "changeDate")));
		} catch (RuntimeException e) {
			log.error("org posture-over-time v3 read failed for org {} -- returning empty for this window "
					+ "(investigate the dangling dim/hydration failure)", org, e);
			return FindingChangeTimelinePage.EMPTY;
		}
		// When the scan hits the row cap, older events in the window were never scanned, so total is a FLOOR
		// (undercount), not the true count -- disclosed to the UI via the truncated flag (board task F4).
		boolean truncated = events.size() >= ORG_POSTURE_SCAN_CAP;
		if (truncated) {
			log.warn("org posture-over-time scan hit the {}-row cap for org {} in [{}, {}] -- oldest events in "
					+ "the window are omitted; narrow the window to see them", ORG_POSTURE_SCAN_CAP, org, effectiveFrom, to);
		}

		Map<UUID, String> branchNameCache = new HashMap<>();
		List<MetricsRevisionFindingChange> all = new ArrayList<>();
		for (FindingChangeEvent ev : events) {
			if (excludedBranchUuids != null && ev.getBranchUuid() != null
					&& excludedBranchUuids.contains(ev.getBranchUuid())) {
				continue; // EXCLUDED-participation branch: match the analytics chart's exclusion
			}
			if (findingKeyFilter != null && !findingKeyFilter.equals(ev.getFindingKey())) {
				continue;
			}
			all.add(toRevisionFindingChange(ev, branchNameCache));
		}
		all.sort(Comparator.comparing(MetricsRevisionFindingChange::changeDate).reversed()); // newest first
		int total = all.size();
		int fromIdx = Math.min(Math.max(0, page) * pageSize, total);
		int toIdx = Math.min(fromIdx + pageSize, total);
		return new FindingChangeTimelinePage(
				new ArrayList<>(all.subList(fromIdx, toIdx)), total, Math.max(0, page), pageSize, clampedSince, truncated);
	}

	/** One page of the over-time timeline (see {@link #loadFindingChangeTimeline}). */
	public record FindingChangeTimelinePage(
			List<MetricsRevisionFindingChange> items,
			int total,
			int page,
			int pageSize,
			ZonedDateTime since,
			// ADDITIVE (board task F4): true when {@code total} is a FLOOR, not the true count -- the org-scope
			// ALL_POSTURE read bounds its scan to ORG_POSTURE_SCAN_CAP newest rows, so a wider window has more
			// events than were counted. The release-anchored read is unbounded, so this is always false there.
			// The UI must render a truncated total as "N+" / "narrow the window", never as a plain exact count.
			boolean truncated) {

		public static final FindingChangeTimelinePage EMPTY =
				new FindingChangeTimelinePage(List.of(), 0, 0, 0, null, false);
	}

	/**
	 * Backfill counterpart of {@link #computeMetricsAuditChanges} (board task #38, phase 2). Walks the
	 * SAME audit-snapshot timeline for ONE release over the full range, but returns persistable
	 * {@link FindingChangeEvent} rows (via the shared {@link #diffPairToEvents}) instead of the
	 * lightweight read DTO -- so backfill produces rows BYTE-IDENTICAL to what live emission writes for
	 * the same transition.
	 *
	 * <p><b>Alignment with live emission.</b> {@code SharedReleaseService.saveReleaseMetrics} archives
	 * the pre-overwrite snapshot as an audit row stamped with the OLD {@code metrics_revision}, then
	 * emits the diff (old live -> new) with {@code toRevision = that old revision} and
	 * {@code changeDate = that audit row's revisionCreatedDate}. So here each consecutive transition
	 * {@code older -> newer} is stamped with {@code older.metricsRevision} and
	 * {@code older.revisionCreatedDate}, and the terminal {@code lastAudit -> live} transition with
	 * {@code lastAudit.metricsRevision} / {@code lastAudit.revisionCreatedDate}. The dedup tuple
	 * {@code (release, to_metrics_revision, changeKind, findingKind, findingKey)} therefore matches
	 * live emission exactly, so the {@code ON CONFLICT DO NOTHING} insert collapses any overlap.
	 *
	 * <p>CANCELLED / REJECTED releases must be filtered by the caller (it holds the lifecycle); this
	 * method does not re-check, mirroring the emitter contract. {@code auditRows} must be ordered by
	 * {@code metrics_revision} ascending (the repository query guarantees this).
	 *
	 * @param auditRows   the release's audit rows over the backfill range, ordered by metrics_revision
	 * @param liveMetrics the current live metrics (terminal timeline element; null -> treated as empty)
	 * @param attr        denormalized release attribution stamped onto every emitted row
	 * @return the persistable change-event rows for every detected transition; empty if none
	 */
	public List<FindingChangeEvent> backfillEventsForRelease(
			List<MetricsAudit> auditRows,
			ReleaseMetricsDto liveMetrics,
			EventAttribution attr) {

		if (auditRows == null || auditRows.isEmpty()) {
			// No archived snapshot ever overwritten -> live emission has nothing to have produced
			// either, so there is nothing to backfill.
			return List.of();
		}

		// Build the ordered timeline: [audit rows..., LIVE]. Each audit entry carries the revision it
		// was stamped with and the date it was overwritten; the LIVE terminal element is never the
		// "older" half of a pair, so its (absent) revision/date are never consulted.
		List<BackfillSnapshot> timeline = new ArrayList<>();
		for (MetricsAudit a : auditRows) {
			timeline.add(new BackfillSnapshot(
					toMetricsDto(a.getMetrics()), a.getRevisionCreatedDate(), a.getMetricsRevision()));
		}
		ReleaseMetricsDto live = liveMetrics != null ? liveMetrics : new ReleaseMetricsDto();
		timeline.add(new BackfillSnapshot(live, null, -1));

		List<FindingChangeEvent> events = new ArrayList<>();
		for (int i = 0; i < timeline.size() - 1; i++) {
			BackfillSnapshot older = timeline.get(i);
			BackfillSnapshot newer = timeline.get(i + 1);
			events.addAll(diffPairToEvents(
					older.metrics(), newer.metrics(), attr, older.changeDate(), older.metricsRevision()));
		}
		return events;
	}

	/**
	 * BRANCH-CHAINED ("events-lite" v3) variant of {@link #backfillEventsForRelease}: produces the SAME
	 * event stream, then drops the initial {@code APPEARED} rows for findings INHERITED from the branch
	 * predecessor. This is the ENTIRE difference between v3 and v1/v2 events (see
	 * {@code ai-agents/finding-events-dedup-v3-design.md} 6a): a shared-dependency finding carried forward
	 * unchanged no longer re-declares APPEARED on every release, collapsing the ~148x per-release fan-out.
	 *
	 * <p>Only a BORN-WITH inherited finding's initial {@code APPEARED} is dropped -- one present in the
	 * release's FIRST NON-EMPTY audit snapshot (its birth scan), computed here as {@code bornWithKeys}. A
	 * finding that TRICKLES IN later via a re-scan (absent at birth, matched afterward) KEEPS its APPEARED so
	 * reverse-replay can show it ABSENT before it arrived -- dropping it pinned it open back to release birth
	 * and over-counted historical posture (board task F1). This matches the live emit, which drops inherited
	 * APPEARED only on a release's first scan. A within-release re-appearance (a flap) is NEVER dropped -- it
	 * is not the finding's first appearance on the release. New findings at first scan (not inherited),
	 * RESOLVED, and every severity/KEV transition are untouched: cross-release resolution and worsening are
	 * surfaced by the read-time anchor comparison exactly as in v1/v2, so they must NOT become per-release
	 * events here.
	 *
	 * <p><b>PRECONDITION: {@code auditRows} MUST be the release's FULL history from birth</b> (as
	 * {@code metricsAuditRepository.findAllRevisionsForEntitySince(.., MIN_DATE)} returns) so
	 * {@code bornWithKeys} is the TRUE birth scan. A date-bounded or windowed slice not beginning at birth is
	 * SAFE but its born-with set is the slice-start snapshot: findings present there generate no APPEARED
	 * within the slice, so it simply drops nothing (harmless for the insert-only repair sweep), while the
	 * branch backfill must feed the full range to actually dedup. NOTE: the PRODUCTION backfill does NOT call this whole-list method -- it uses the
	 * bounded-memory WINDOWED pair path ({@link #diffAuditPairToEventsV3} / {@link #diffAuditToLiveEventsV3},
	 * sharing {@link #dropInheritedInitialAppearance}) for customer-scale histories. This method remains the
	 * tested reference the windowed path is proven byte-identical to (FindingChangeEventDiffTest).
	 *
	 * @param inheritedKeys finding_keys present in the branch predecessor's terminal metrics (empty for the
	 *        first release on a branch -> nothing dropped -> byte-identical to {@link #backfillEventsForRelease})
	 */
	public List<FindingChangeEvent> backfillEventsForReleaseV3(
			List<MetricsAudit> auditRows,
			ReleaseMetricsDto liveMetrics,
			EventAttribution attr,
			Set<String> inheritedKeys) {

		if (auditRows == null || auditRows.isEmpty()) {
			return List.of();
		}
		List<BackfillSnapshot> timeline = new ArrayList<>();
		for (MetricsAudit a : auditRows) {
			timeline.add(new BackfillSnapshot(
					toMetricsDto(a.getMetrics()), a.getRevisionCreatedDate(), a.getMetricsRevision()));
		}
		ReleaseMetricsDto live = liveMetrics != null ? liveMetrics : new ReleaseMetricsDto();
		timeline.add(new BackfillSnapshot(live, null, -1));

		// Born-with = the finding keys of the FIRST NON-EMPTY snapshot (the birth scan). Only these inherited
		// APPEAREDs are dropped; trickle-ins (absent at birth, matched later by a re-scan) keep their APPEARED
		// so reverse-replay can show them absent before they arrived (board task F1).
		Set<String> bornWithKeys = Set.of();
		for (BackfillSnapshot s : timeline) {
			Set<String> k = findingKeysOf(s.metrics());
			if (!k.isEmpty()) { bornWithKeys = k; break; }
		}

		Set<String> appearedOnThisRelease = new HashSet<>();
		List<FindingChangeEvent> events = new ArrayList<>();
		for (int i = 0; i < timeline.size() - 1; i++) {
			BackfillSnapshot older = timeline.get(i);
			BackfillSnapshot newer = timeline.get(i + 1);
			List<FindingChangeEvent> pairEvents = diffPairToEvents(
					older.metrics(), newer.metrics(), attr, older.changeDate(), older.metricsRevision());
			events.addAll(dropInheritedInitialAppearance(pairEvents, inheritedKeys, bornWithKeys, appearedOnThisRelease));
		}
		return events;
	}

	/**
	 * The v3 inherited-drop, shared by the whole-list ({@link #backfillEventsForReleaseV3}) and the WINDOWED
	 * ({@code FindingChangeEventBackfillService} backfill) producers so they cannot drift. Drops the FIRST
	 * {@code APPEARED} of each INHERITED finding on this release -- i.e. the release-grain re-declaration of a
	 * finding the branch already declared on an earlier release ({@code inheritedKeys}). Everything else is
	 * kept: a genuinely-new finding's APPEARED (not inherited), a RE-appearance of an inherited finding after
	 * a within-release RESOLVE (so flaps reconstruct correctly), and all RESOLVED / severity / KEV events.
	 *
	 * <p>Uses a caller-owned {@code appearedOnThisRelease} set (finding_keys already APPEARED on this release)
	 * so a finding is de-declared at most ONCE per release. The set persists ACROSS windows so the windowed and
	 * whole-list producers agree.
	 *
	 * <p>BORN-WITH GATE (board task F1): the drop fires ONLY for findings in {@code bornWithKeys} -- the keys
	 * present in the release's FIRST NON-EMPTY audit snapshot (its birth scan). A born-with inherited finding is
	 * present from birth, so reverse-replay keeps it present at every reconstructable instant (T &gt;= birth)
	 * WITHOUT its (dropped) initial APPEARED -- safe. A finding that TRICKLES IN later via a re-scan (a
	 * newly-disclosed CVE matched against an already-shipped release, dated at re-scan time) is NOT born-with;
	 * its APPEARED carries the real appearance instant reverse-replay needs to show it ABSENT at an earlier T, so
	 * it is KEPT. This matches the live emit, which only drops inherited APPEARED on a release's FIRST scan. The
	 * earlier rule dropped a trickle-in's APPEARED too, pinning it open back to release birth and over-counting
	 * historical posture (F1). Findings present at a mid-history slice's START generate no APPEARED within that
	 * slice, so a slice not beginning at birth drops nothing -- harmless (the repair sweep's insert-only re-diff).
	 */
	private List<FindingChangeEvent> dropInheritedInitialAppearance(List<FindingChangeEvent> pairEvents,
			Set<String> inheritedKeys, Set<String> bornWithKeys, Set<String> appearedOnThisRelease) {
		if (inheritedKeys == null || inheritedKeys.isEmpty() || bornWithKeys == null || bornWithKeys.isEmpty()) {
			return pairEvents;
		}
		List<FindingChangeEvent> out = new ArrayList<>(pairEvents.size());
		for (FindingChangeEvent ev : pairEvents) {
			if (ev.getChangeKind() == FindingChangeKind.APPEARED) {
				// add() is true only on the finding's FIRST appearance on this release; a later re-appearance
				// (after a within-release resolve) returns false and is kept so the flap stays reconstructable.
				boolean firstAppearanceOnRelease = appearedOnThisRelease.add(ev.getFindingKey());
				if (firstAppearanceOnRelease && inheritedKeys.contains(ev.getFindingKey())
						&& bornWithKeys.contains(ev.getFindingKey())) {
					continue; // inherited AND born-with -- redundant re-declaration, safe to drop
				}
			}
			out.add(ev);
		}
		return out;
	}

	/**
	 * The set of finding_keys present in a metrics snapshot (across vulnerabilities / violations /
	 * weaknesses), using the SAME frozen key extractors the diff and {@link FindingDim} use. Feeds the v3
	 * branch-chained producer with a branch predecessor's "inherited findings".
	 */
	public Set<String> findingKeysOf(ReleaseMetricsDto metrics) {
		if (metrics == null) {
			return Set.of();
		}
		Set<String> keys = new HashSet<>();
		for (VulnerabilityDto v : nullSafe(metrics.getVulnerabilityDetails())) {
			keys.add(VULN_KEY.apply(v));
		}
		for (ViolationDto v : nullSafe(metrics.getViolationDetails())) {
			keys.add(VIOLATION_KEY.apply(v));
		}
		for (WeaknessDto w : nullSafe(metrics.getWeaknessDetails())) {
			keys.add(WEAKNESS_KEY.apply(w));
		}
		return keys;
	}

	/**
	 * The finding_keys present in a RAW {@code metrics_audit} snapshot (map form). The v3 branch-chained
	 * backfill accumulates these across a release's audit history to build a DRIFT-PROOF "already seen on
	 * this branch" set -- unlike a predecessor's CURRENT metrics, an archived audit snapshot never changes,
	 * so the inherited-drop stays correct even for predecessors re-scanned long after a child forked.
	 */
	public Set<String> findingKeysOfRawMetrics(Map<String, Object> rawMetrics) {
		return findingKeysOf(toMetricsDto(rawMetrics));
	}

	/**
	 * The inherited finding_keys for a release's FIRST scan (live-emit v3 path): the live finding keys of
	 * the previous SAME-BRANCH release. Aligns with the branch-chained backfill's predecessor chain --
	 * first-on-branch inherits NOTHING (a base-branch fork point is a DIFFERENT branch, so it is excluded),
	 * and CANCELLED/REJECTED predecessors never contributed posture. A live-vs-backfill disagreement (this
	 * reads the predecessor's CURRENT metrics; the backfill reads its terminal metrics) is a HARMLESS
	 * dedup-key superset -- a kept inherited APPEARED == v1/v2 behavior, so reconstruction stays correct.
	 */
	public Set<String> firstScanInheritedKeys(ReleaseData rd) {
		UUID branch = rd.getBranch();
		if (branch == null) {
			return Set.of();
		}
		UUID prevUuid = sharedReleaseService.findPreviousReleasesOfBranchForRelease(branch, rd.getUuid());
		if (prevUuid == null) {
			return Set.of();
		}
		ReleaseData prev = sharedReleaseService.getReleaseData(prevUuid).orElse(null);
		if (prev == null || !branch.equals(prev.getBranch())
				|| prev.getLifecycle() == ReleaseLifecycle.CANCELLED
				|| prev.getLifecycle() == ReleaseLifecycle.REJECTED) {
			return Set.of();
		}
		return findingKeysOf(prev.getMetrics());
	}

	/**
	 * ONE audit-pair diff for the WINDOWED backfill: identical semantics to a consecutive pair inside
	 * {@link #backfillEventsForRelease} (same {@link #diffPairToEvents}, stamped with the OLDER row's
	 * overwrite instant + revision), exposed so the backfill can walk a large history in bounded-memory
	 * windows without loading every snapshot at once.
	 */
	public List<FindingChangeEvent> diffAuditPairToEvents(
			MetricsAudit older, MetricsAudit newer, EventAttribution attr) {
		return diffPairToEvents(
				toMetricsDto(older.getMetrics()), toMetricsDto(newer.getMetrics()),
				attr, older.getRevisionCreatedDate(), older.getMetricsRevision());
	}

	/**
	 * The TERMINAL (last archived snapshot -> live current metrics) pair for the windowed backfill --
	 * identical to {@link #backfillEventsForRelease}'s final timeline pair.
	 */
	public List<FindingChangeEvent> diffAuditToLiveEvents(
			MetricsAudit lastRow, ReleaseMetricsDto liveMetrics, EventAttribution attr) {
		ReleaseMetricsDto live = liveMetrics != null ? liveMetrics : new ReleaseMetricsDto();
		return diffPairToEvents(
				toMetricsDto(lastRow.getMetrics()), live,
				attr, lastRow.getRevisionCreatedDate(), lastRow.getMetricsRevision());
	}

	/**
	 * WINDOWED-BACKFILL v3 pair diff: {@link #diffAuditPairToEvents} + the branch-chained inherited-drop
	 * ({@link #dropInheritedInitialAppearance}), so the windowed v3 backfill produces the SAME stream as the
	 * whole-list {@link #backfillEventsForReleaseV3} while bounding per-release memory. The
	 * {@code appearedOnThisRelease} set is CALLER-OWNED so the "drop each inherited finding's first
	 * appearance once per release" state survives across windows.
	 */
	public List<FindingChangeEvent> diffAuditPairToEventsV3(MetricsAudit older, MetricsAudit newer,
			EventAttribution attr, Set<String> inheritedKeys, Set<String> bornWithKeys,
			Set<String> appearedOnThisRelease) {
		List<FindingChangeEvent> events = diffAuditPairToEvents(older, newer, attr);
		return dropInheritedInitialAppearance(events, inheritedKeys, bornWithKeys, appearedOnThisRelease);
	}

	/**
	 * The (last archived snapshot -> live) TERMINAL pair for the windowed v3 backfill. The inherited-drop
	 * still applies here for any inherited finding making its first appearance on the release in this pair.
	 */
	public List<FindingChangeEvent> diffAuditToLiveEventsV3(MetricsAudit lastRow, ReleaseMetricsDto liveMetrics,
			EventAttribution attr, Set<String> inheritedKeys, Set<String> bornWithKeys,
			Set<String> appearedOnThisRelease) {
		ReleaseMetricsDto live = liveMetrics != null ? liveMetrics : new ReleaseMetricsDto();
		List<FindingChangeEvent> events = diffAuditToLiveEvents(lastRow, live, attr);
		return dropInheritedInitialAppearance(events, inheritedKeys, bornWithKeys, appearedOnThisRelease);
	}

	/** A single point on a release's metrics-snapshot timeline for the backfill (carries the revision). */
	private record BackfillSnapshot(ReleaseMetricsDto metrics, ZonedDateTime changeDate, int metricsRevision) {}

	/**
	 * rev-0 / null-metrics rows are treated as an empty {@link ReleaseMetricsDto} so the first real
	 * snapshot's findings register as APPEARED. Deserializes the JSONB map the same way ReleaseData
	 * does ({@code Utils.OM.convertValue(map, ReleaseMetricsDto.class)}).
	 */
	private static ReleaseMetricsDto toMetricsDto(Map<String, Object> raw) {
		if (raw == null || raw.isEmpty()) return new ReleaseMetricsDto();
		return Utils.OM.convertValue(raw, ReleaseMetricsDto.class);
	}

	/**
	 * Denormalized release attribution stamped onto every emitted {@link FindingChangeEvent}.
	 * This is the write-time carrier; the read-time {@link AuditChangeReleaseContext} maps onto it
	 * (org is irrelevant on the read path -- the rows are produced transiently, never persisted).
	 */
	public record EventAttribution(
			UUID org,
			UUID releaseUuid,
			String version,
			UUID componentUuid,
			String componentName,
			UUID branchUuid) {}

	/**
	 * SINGLE SOURCE OF TRUTH for the per-pair diff -> finding changes. Diffs one consecutive
	 * metrics-snapshot pair and returns the persistable {@link FindingChangeEvent} rows for every
	 * detected change. Used by BOTH the write-time emit ({@code SharedReleaseService.saveReleaseMetrics})
	 * and the read-time #252 changelog path (via {@link #appendTransitionChanges}), guaranteeing the
	 * two cannot drift.
	 *
	 * <p>APPEARED / RESOLVED come from {@link #compareMetrics} (reused, signature untouched).
	 * SEVERITY_INCREASED (via {@link VulnerabilitySeverity} ordinal) and KEV_ADDED (knownExploited
	 * false/null -> true) are detected here for keys present in BOTH snapshots. {@code previousSeverity}
	 * is populated only for SEVERITY_INCREASED. Every row is bucketed at {@code changeDate} and stamped
	 * with {@code toRevision} (the revision the newer snapshot produced) for write-time dedup.
	 *
	 * @param older     the snapshot being overwritten (treat null as empty so first findings APPEAR)
	 * @param newer     the snapshot coming into effect
	 * @param attr      denormalized release attribution
	 * @param changeDate the moment the change took effect
	 * @param toRevision the metrics revision the newer snapshot produced
	 */
	public List<FindingChangeEvent> diffPairToEvents(
			ReleaseMetricsDto older,
			ReleaseMetricsDto newer,
			EventAttribution attr,
			ZonedDateTime changeDate,
			int toRevision) {

		ReleaseMetricsDto olderSafe = older != null ? older : new ReleaseMetricsDto();
		ReleaseMetricsDto newerSafe = newer != null ? newer : new ReleaseMetricsDto();

		List<FindingChangeEvent> out = new ArrayList<>();

		FindingChangesRecord changes = compareMetrics(olderSafe, newerSafe);

		// APPEARED / RESOLVED (by key) -- reuse the existing comparator output.
		if (changes != null) {
			for (VulnerabilityDto v : nullSafe(changes.appearedVulnerabilities())) {
				out.add(vulnEvent(attr, changeDate, toRevision, FindingChangeKind.APPEARED, v, null));
			}
			for (VulnerabilityDto v : nullSafe(changes.resolvedVulnerabilities())) {
				out.add(vulnEvent(attr, changeDate, toRevision, FindingChangeKind.RESOLVED, v, null));
			}
			for (ViolationDto v : nullSafe(changes.appearedViolations())) {
				out.add(violationEvent(attr, changeDate, toRevision, FindingChangeKind.APPEARED, v));
			}
			for (ViolationDto v : nullSafe(changes.resolvedViolations())) {
				out.add(violationEvent(attr, changeDate, toRevision, FindingChangeKind.RESOLVED, v));
			}
			for (WeaknessDto w : nullSafe(changes.appearedWeaknesses())) {
				out.add(weaknessEvent(attr, changeDate, toRevision, FindingChangeKind.APPEARED, w, null));
			}
			for (WeaknessDto w : nullSafe(changes.resolvedWeaknesses())) {
				out.add(weaknessEvent(attr, changeDate, toRevision, FindingChangeKind.RESOLVED, w, null));
			}
		}

		// SEVERITY_INCREASED / KEV_ADDED for keys present in BOTH snapshots.
		Map<String, VulnerabilityDto> olderVulns = indexBy(olderSafe.getVulnerabilityDetails(), VULN_KEY);
		for (VulnerabilityDto newV : nullSafe(newerSafe.getVulnerabilityDetails())) {
			VulnerabilityDto oldV = olderVulns.get(VULN_KEY.apply(newV));
			if (oldV == null) continue; // APPEARED already handled above
			if (severityIncreased(oldV.severity(), newV.severity())) {
				out.add(vulnEvent(attr, changeDate, toRevision, FindingChangeKind.SEVERITY_INCREASED, newV,
						oldV.severity() != null ? oldV.severity().name() : null));
			} else if (severityDecreased(oldV.severity(), newV.severity())) {
				out.add(vulnEvent(attr, changeDate, toRevision, FindingChangeKind.SEVERITY_DECREASED, newV,
						oldV.severity() != null ? oldV.severity().name() : null));
			}
			if (kevAdded(oldV.knownExploited(), newV.knownExploited())) {
				out.add(vulnEvent(attr, changeDate, toRevision, FindingChangeKind.KEV_ADDED, newV, null));
			} else if (kevRemoved(oldV.knownExploited(), newV.knownExploited())) {
				out.add(vulnEvent(attr, changeDate, toRevision, FindingChangeKind.KEV_REMOVED, newV, null));
			}
		}

		// SEVERITY_INCREASED for weaknesses present in both snapshots (weaknesses carry a severity
		// but no KEV flag).
		Map<String, WeaknessDto> olderWeaks = indexBy(olderSafe.getWeaknessDetails(), WEAKNESS_KEY);
		for (WeaknessDto newW : nullSafe(newerSafe.getWeaknessDetails())) {
			WeaknessDto oldW = olderWeaks.get(WEAKNESS_KEY.apply(newW));
			if (oldW == null) continue;
			if (severityIncreased(oldW.severity(), newW.severity())) {
				out.add(weaknessEvent(attr, changeDate, toRevision, FindingChangeKind.SEVERITY_INCREASED, newW,
						oldW.severity() != null ? oldW.severity().name() : null));
			} else if (severityDecreased(oldW.severity(), newW.severity())) {
				out.add(weaknessEvent(attr, changeDate, toRevision, FindingChangeKind.SEVERITY_DECREASED, newW,
						oldW.severity() != null ? oldW.severity().name() : null));
			}
		}

		return out;
	}

	/**
	 * Thin ADAPTER over {@link #diffPairToEvents}: the #252 read path diffs one snapshot pair and
	 * appends {@link MetricsRevisionFindingChange} records. Mapping every emitted
	 * {@link FindingChangeEvent} back onto the read DTO keeps write-time and read-time on a single
	 * diff implementation -- they cannot drift. {@code toRevision} is irrelevant on the read path
	 * (the changes are not persisted / deduped) so a sentinel 0 is passed.
	 */
	private void appendTransitionChanges(
			List<MetricsRevisionFindingChange> out,
			AuditChangeReleaseContext ctx,
			ZonedDateTime changeDate,
			ReleaseMetricsDto older,
			ReleaseMetricsDto newer) {

		EventAttribution attr = new EventAttribution(
				null, ctx.releaseUuid(), ctx.version(), ctx.componentUuid(), ctx.componentName(), null);
		Map<UUID, String> branchNameCache = new HashMap<>();
		for (FindingChangeEvent ev : diffPairToEvents(older, newer, attr, changeDate, 0)) {
			out.add(toRevisionFindingChange(ev, branchNameCache));
		}
	}

	/**
	 * Maps a persistable {@link FindingChangeEvent} onto the lightweight read DTO
	 * {@link MetricsRevisionFindingChange}. The {@link ReleaseVulnerabilityInfo} /
	 * {@link ReleaseViolationInfo} / {@link ReleaseWeaknessInfo} projections are rebuilt from the
	 * event's denormalized columns so the read DTO is identical to the pre-refactor output.
	 */
	private MetricsRevisionFindingChange toRevisionFindingChange(FindingChangeEvent ev, Map<UUID, String> branchNameCache) {
		ReleaseVulnerabilityInfo vuln = null;
		ReleaseViolationInfo violation = null;
		ReleaseWeaknessInfo weakness = null;
		switch (ev.getFindingKind()) {
			case VULNERABILITY -> vuln = new ReleaseVulnerabilityInfo(
					ev.getVulnId(), ev.getPurl(), ev.getSeverity(), ev.getAliases(),
					parseAnalysisState(ev.getAnalysisState()));
			case VIOLATION -> violation = new ReleaseViolationInfo(
					ev.getViolationType(), ev.getPurl(), parseAnalysisState(ev.getAnalysisState()));
			case WEAKNESS -> weakness = new ReleaseWeaknessInfo(
					ev.getCweId(), ev.getSeverity(), ev.getRuleId(),
					ev.getLocation() != null ? ev.getLocation() : "",
					parseAnalysisState(ev.getAnalysisState()));
		}
		UUID branchUuid = ev.getBranchUuid();
		// Resolve the branch name via the per-call cache (F7b); a null branch (e.g. the metrics_audit-oracle
		// path, which carries no branch) leaves both null.
		String branchName = branchUuid == null ? null : cachedBranchName(branchUuid, branchNameCache);
		return new MetricsRevisionFindingChange(
				ev.getChangeDate(), ev.getChangeKind(), ev.getReleaseUuid(), ev.getVersion(),
				ev.getComponentUuid(), ev.getComponentName(), branchUuid, branchName, vuln, violation, weakness,
				ev.getPreviousSeverity());
	}

	private static AnalysisState parseAnalysisState(String raw) {
		if (raw == null) return null;
		try {
			return AnalysisState.valueOf(raw);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Severity ordering is the {@link VulnerabilitySeverity} declaration order
	 * (CRITICAL=0 .. UNASSIGNED=4): a lower ordinal is more severe. An "increase" is a move to a
	 * strictly lower ordinal. Nulls are not treated as an increase (avoids noise when a scan simply
	 * fails to assign severity).
	 */
	private static boolean severityIncreased(VulnerabilitySeverity oldSev, VulnerabilitySeverity newSev) {
		if (oldSev == null || newSev == null) return false;
		return newSev.ordinal() < oldSev.ordinal();
	}

	/**
	 * Severity DECREASE: a move to a strictly HIGHER ordinal (less severe). The mirror of
	 * {@link #severityIncreased}; emitted so the event log is bidirectional and reverse-replay can
	 * restore the pre-change (more-severe) value. Nulls are not a decrease (symmetric with increase).
	 */
	private static boolean severityDecreased(VulnerabilitySeverity oldSev, VulnerabilitySeverity newSev) {
		if (oldSev == null || newSev == null) return false;
		return newSev.ordinal() > oldSev.ordinal();
	}

	/** KEV-add: the flag transitions false/null -> true. */
	private static boolean kevAdded(Boolean oldKev, Boolean newKev) {
		return Boolean.TRUE.equals(newKev) && !Boolean.TRUE.equals(oldKev);
	}

	/** KEV-remove: the flag transitions true -> false/null (mirror of {@link #kevAdded}). */
	private static boolean kevRemoved(Boolean oldKev, Boolean newKev) {
		return Boolean.TRUE.equals(oldKev) && !Boolean.TRUE.equals(newKev);
	}

	private static <T> List<T> nullSafe(List<T> list) {
		return list != null ? list : List.of();
	}

	private static <T> Map<String, T> indexBy(List<T> list, Function<T, String> keyExtractor) {
		Map<String, T> map = new HashMap<>();
		if (list != null) {
			for (T item : list) {
				map.put(keyExtractor.apply(item), item);
			}
		}
		return map;
	}

	/**
	 * Stamps the shared attribution / change metadata onto a fresh {@link FindingChangeEvent}.
	 * The finding-type-specific identity columns are filled by the per-type builders below; the
	 * {@code from(...)} projections in {@link ReleaseVulnerabilityInfo} etc. remain the single source
	 * of truth for the type/severity null-guards, which we reuse here so write and read agree.
	 */
	private static FindingChangeEvent baseEvent(
			EventAttribution attr, ZonedDateTime changeDate, int toRevision, FindingChangeKind kind,
			FindingChangeEvent.FindingKind findingKind, String findingKey, String previousSeverity) {
		FindingChangeEvent ev = new FindingChangeEvent();
		ev.setOrg(attr.org());
		ev.setReleaseUuid(attr.releaseUuid());
		ev.setVersion(attr.version());
		ev.setComponentUuid(attr.componentUuid());
		ev.setComponentName(attr.componentName());
		ev.setChangeDate(changeDate);
		ev.setToMetricsRevision(toRevision);
		ev.setChangeKind(kind);
		ev.setFindingKind(findingKind);
		ev.setFindingKey(findingKey);
		ev.setPreviousSeverity(previousSeverity);
		ev.setBranchUuid(attr.branchUuid());
		return ev;
	}

	private static FindingChangeEvent vulnEvent(
			EventAttribution attr, ZonedDateTime changeDate, int toRevision, FindingChangeKind kind,
			VulnerabilityDto v, String previousSeverity) {
		ReleaseVulnerabilityInfo info = ReleaseVulnerabilityInfo.from(v);
		FindingChangeEvent ev = baseEvent(attr, changeDate, toRevision, kind,
				FindingChangeEvent.FindingKind.VULNERABILITY, VULN_KEY.apply(v), previousSeverity);
		ev.setVulnId(info.vulnId());
		ev.setPurl(info.purl());
		ev.setSeverity(info.severity());
		ev.setKnownExploited(v.knownExploited());
		ev.setAnalysisState(info.analysisState() != null ? info.analysisState().name() : null);
		ev.setAliases(info.aliases());
		return ev;
	}

	private static FindingChangeEvent violationEvent(
			EventAttribution attr, ZonedDateTime changeDate, int toRevision, FindingChangeKind kind,
			ViolationDto v) {
		ReleaseViolationInfo info = ReleaseViolationInfo.from(v);
		FindingChangeEvent ev = baseEvent(attr, changeDate, toRevision, kind,
				FindingChangeEvent.FindingKind.VIOLATION, VIOLATION_KEY.apply(v), null);
		ev.setViolationType(info.type());
		ev.setPurl(info.purl());
		ev.setAnalysisState(info.analysisState() != null ? info.analysisState().name() : null);
		return ev;
	}

	private static FindingChangeEvent weaknessEvent(
			EventAttribution attr, ZonedDateTime changeDate, int toRevision, FindingChangeKind kind,
			WeaknessDto w, String previousSeverity) {
		ReleaseWeaknessInfo info = ReleaseWeaknessInfo.from(w);
		FindingChangeEvent ev = baseEvent(attr, changeDate, toRevision, kind,
				FindingChangeEvent.FindingKind.WEAKNESS, WEAKNESS_KEY.apply(w), previousSeverity);
		ev.setCweId(info.cweId());
		ev.setSeverity(info.severity());
		ev.setRuleId(info.ruleId());
		ev.setLocation(info.location());
		ev.setAnalysisState(info.analysisState() != null ? info.analysisState().name() : null);
		return ev;
	}

}
