/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.reliza.model.VulnAnalysisData;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class VulnAnalysisUpdateService {
	
	@Lazy
	@Autowired
	private ArtifactService artifactService;
	
	@Lazy
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Lazy
	@Autowired
	private ReleaseService releaseService;
	
	/**
	 * Asynchronously process vulnerability analysis updates and trigger metrics recomputation
	 */
	@Async
	public void processVulnAnalysisUpdate(VulnAnalysisData analysisData) {
		log.info("Processing vulnerability analysis update for finding: {} (type: {}, scope: {})", 
				analysisData.getFindingId(), analysisData.getFindingType(), analysisData.getScope());
		
		switch (analysisData.getScope()) {
			case ORG:
				// For org-wide scope, process both artifacts and releases
				processOrgWideUpdate(analysisData);
				break;
			case RELEASE:
				// For release scope, only recompute metrics for that specific release
				processReleaseUpdate(analysisData);
				break;
			case BRANCH:
				// For branch scope, find and update releases in that branch
				processBranchUpdate(analysisData);
				break;
			case COMPONENT:
				// For component scope, find and update releases in that component
				processComponentUpdate(analysisData);
				break;
			default:
				log.warn("Unsupported scope type: {}", analysisData.getScope());
		}
	}
	
	/**
	 * Organizations whose KEV re-gate statement failed, with the newly-KEV ids it was asked for.
	 * Drained by the next KEV sync tick (KevCatalogSyncService.syncCatalog). In memory on purpose:
	 * a restart loses it, and what is lost is bounded by the compute's own re-stamp of
	 * knownExploited at each release's next scan (ReleaseMetricsComputeService.knownExploitedStampedCopy).
	 */
	private final Map<UUID, Set<String>> kevReGateRetry = new ConcurrentHashMap<>();

	/**
	 * Live KEV re-gate fan-out for one organization. Called by the KEV catalog
	 * sync (after the reconcile transaction commits) with every CVE of the
	 * sync that newly entered the catalog and that the org has a record of:
	 * KEV status is time-varying independent of any scan, so a release that
	 * already ships one of {@code cveIds} would otherwise keep a stale
	 * {@code kevCount}/gate verdict until its next scan. One statement finds
	 * every release of the org carrying any of the ids in any location (as a
	 * primary id or an alias), the releases are deduplicated, and each is
	 * recomputed once (which re-stamps {@code knownExploited}/{@code kevCount}
	 * and re-fires the gate via the metrics-change path). A failed statement
	 * queues the org and its ids for the next sync tick
	 * ({@link #drainKevReGateRetries}); per-release try/catch so one bad
	 * release never aborts the fan-out (TPF-131).
	 *
	 * <p>Runs {@code @Async} -- must be invoked through the Spring proxy
	 * (cross-bean call from the sync), never as a same-class self-call.
	 */
	@Async
	public void recomputeReleasesForNewlyKev(Collection<String> cveIds, UUID org) {
		if (org == null || cveIds == null || cveIds.isEmpty()) {
			return;
		}
		List<UUID> found;
		try {
			found = sharedReleaseService.findReleaseUuidsWithAnyVulnerabilityAnyLocation(org, cveIds);
		} catch (Exception e) {
			log.error("KEV re-gate: finding the releases of org {} carrying {} newly-KEV CVE(s) failed; "
					+ "queued for the next KEV sync tick", org, cveIds.size(), e);
			kevReGateRetry.compute(org, (k, prev) -> {
				Set<String> merged = prev == null ? new HashSet<>() : prev;
				merged.addAll(cveIds);
				return merged;
			});
			return;
		}
		Set<UUID> releaseUuids = new LinkedHashSet<>(found);
		log.info("KEV re-gate: {} release(s) of org {} carry {} newly-KEV CVE(s); recomputing metrics",
				releaseUuids.size(), org, cveIds.size());
		for (UUID releaseUuid : releaseUuids) {
			try {
				releaseService.computeReleaseMetrics(releaseUuid, false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release {} on newly-KEV CVEs", releaseUuid, e);
			}
		}
	}

	/** Takes and clears the queued KEV re-gate retries; the caller re-launches each through the proxy. */
	public Map<UUID, Set<String>> drainKevReGateRetries() {
		Map<UUID, Set<String>> out = new HashMap<>();
		for (UUID org : new ArrayList<>(kevReGateRetry.keySet())) {
			Set<String> ids = kevReGateRetry.remove(org);
			if (ids != null && !ids.isEmpty()) out.put(org, ids);
		}
		return out;
	}

	/**
	 * Process org-wide updates - affects both artifacts and releases
	 */
	private void processOrgWideUpdate(VulnAnalysisData analysisData) {
		// Use SQL-level queries to find affected artifacts (UUIDs only —
		// the downstream computeArtifactMetrics / computeReleaseMetrics
		// methods take UUIDs, so materializing full entities would force
		// pointless JSONB-snapshot deep copies on a potentially large
		// affected list).
		List<UUID> affectedArtifactUuids = findAffectedArtifactUuids(analysisData);
		log.info("Found {} affected artifacts for finding: {}", affectedArtifactUuids.size(), analysisData.getFindingId());

		for (UUID artifactUuid : affectedArtifactUuids) {
			try {
				artifactService.computeArtifactMetrics(artifactUuid);
			} catch (Exception e) {
				log.error("Error recomputing metrics for artifact: {}", artifactUuid, e);
			}
		}

		List<UUID> affectedReleaseUuids = findAffectedReleaseUuids(analysisData);
		log.info("Found {} affected releases for finding: {}", affectedReleaseUuids.size(), analysisData.getFindingId());

		for (UUID releaseUuid : affectedReleaseUuids) {
			try {
				releaseService.computeReleaseMetrics(releaseUuid, false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release: {}", releaseUuid, e);
			}
		}
	}
	
	/**
	 * Process release-specific update - only recompute metrics for the specific release
	 */
	private void processReleaseUpdate(VulnAnalysisData analysisData) {
		try {
			log.info("Recomputing metrics for release: {}", analysisData.getScopeUuid());
			releaseService.computeReleaseMetrics(analysisData.getScopeUuid(), false);
		} catch (Exception e) {
			log.error("Error recomputing metrics for release: {}", analysisData.getScopeUuid(), e);
		}
	}
	
	/**
	 * Process branch-specific update - find releases in the branch that match the finding
	 */
	private void processBranchUpdate(VulnAnalysisData analysisData) {
		List<UUID> affectedReleaseUuids = findAffectedReleaseUuidsInBranch(analysisData);
		log.info("Found {} affected releases in branch {} for finding: {}",
				affectedReleaseUuids.size(), analysisData.getScopeUuid(), analysisData.getFindingId());

		for (UUID releaseUuid : affectedReleaseUuids) {
			try {
				releaseService.computeReleaseMetrics(releaseUuid, false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release: {}", releaseUuid, e);
			}
		}
	}

	/**
	 * Process component-specific update - find releases in the component that match the finding
	 */
	private void processComponentUpdate(VulnAnalysisData analysisData) {
		List<UUID> affectedReleaseUuids = findAffectedReleaseUuidsInComponent(analysisData);
		log.info("Found {} affected releases in component {} for finding: {}",
				affectedReleaseUuids.size(), analysisData.getScopeUuid(), analysisData.getFindingId());

		for (UUID releaseUuid : affectedReleaseUuids) {
			try {
				releaseService.computeReleaseMetrics(releaseUuid, false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release: {}", releaseUuid, e);
			}
		}
	}
	
	/**
	 * UUIDs of artifacts affected by the vulnerability analysis.
	 */
	private List<UUID> findAffectedArtifactUuids(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();

		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<UUID> vulnArtifacts = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnArtifacts.addAll(artifactService.findArtifactUuidsWithVulnerability(analysisData.getOrg(), location, findingId));
                }
                vulnArtifacts.addAll(artifactService.findArtifactUuidsWithVulnerability(analysisData.getOrg(), rawLocation, findingId));
				return vulnArtifacts;
			case VIOLATION:
                List<UUID> violArtifacts = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violArtifacts.addAll(artifactService.findArtifactUuidsWithViolation(analysisData.getOrg(), location, findingId));
                }
				violArtifacts.addAll(artifactService.findArtifactUuidsWithViolation(analysisData.getOrg(), rawLocation, findingId));
                return violArtifacts;
			case WEAKNESS:
				return artifactService.findArtifactUuidsWithWeakness(analysisData.getOrg(), location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}

	/**
	 * UUIDs of releases affected by the vulnerability analysis in a specific branch.
	 */
	private List<UUID> findAffectedReleaseUuidsInBranch(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();
		UUID branchUuid = analysisData.getScopeUuid();

		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<UUID> vulnReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnReleases.addAll(sharedReleaseService.findReleaseUuidsWithVulnerabilityInBranch(analysisData.getOrg(), branchUuid, location, findingId));
                }
                vulnReleases.addAll(sharedReleaseService.findReleaseUuidsWithVulnerabilityInBranch(analysisData.getOrg(), branchUuid, rawLocation, findingId));
				return vulnReleases;
			case VIOLATION:
                List<UUID> violReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violReleases.addAll(sharedReleaseService.findReleaseUuidsWithViolationInBranch(analysisData.getOrg(), branchUuid, location, findingId));
                }
				violReleases.addAll(sharedReleaseService.findReleaseUuidsWithViolationInBranch(analysisData.getOrg(), branchUuid, rawLocation, findingId));
                return violReleases;
			case WEAKNESS:
				return sharedReleaseService.findReleaseUuidsWithWeaknessInBranch(analysisData.getOrg(), branchUuid, location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}

	/**
	 * UUIDs of releases affected by the vulnerability analysis in a specific component.
	 */
	private List<UUID> findAffectedReleaseUuidsInComponent(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();
		UUID componentUuid = analysisData.getScopeUuid();

		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<UUID> vulnReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnReleases.addAll(sharedReleaseService.findReleaseUuidsWithVulnerabilityInComponent(analysisData.getOrg(), componentUuid, location, findingId));
                }
                vulnReleases.addAll(sharedReleaseService.findReleaseUuidsWithVulnerabilityInComponent(analysisData.getOrg(), componentUuid, rawLocation, findingId));
				return vulnReleases;
			case VIOLATION:
                List<UUID> violReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violReleases.addAll(sharedReleaseService.findReleaseUuidsWithViolationInComponent(analysisData.getOrg(), componentUuid, location, findingId));
                }
				violReleases.addAll(sharedReleaseService.findReleaseUuidsWithViolationInComponent(analysisData.getOrg(), componentUuid, rawLocation, findingId));
                return violReleases;
			case WEAKNESS:
				return sharedReleaseService.findReleaseUuidsWithWeaknessInComponent(analysisData.getOrg(), componentUuid, location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}

	/**
	 * UUIDs of releases affected by the vulnerability analysis org-wide.
	 */
	private List<UUID> findAffectedReleaseUuids(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();

		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<UUID> vulnReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnReleases.addAll(sharedReleaseService.findReleaseUuidsWithVulnerability(analysisData.getOrg(), location, findingId));
                }
                vulnReleases.addAll(sharedReleaseService.findReleaseUuidsWithVulnerability(analysisData.getOrg(), rawLocation, findingId));
				return vulnReleases;
			case VIOLATION:
                List<UUID> violReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violReleases.addAll(sharedReleaseService.findReleaseUuidsWithViolation(analysisData.getOrg(), location, findingId));
                }
				violReleases.addAll(sharedReleaseService.findReleaseUuidsWithViolation(analysisData.getOrg(), rawLocation, findingId));
                return violReleases;
			case WEAKNESS:
				return sharedReleaseService.findReleaseUuidsWithWeakness(analysisData.getOrg(), location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}
}
