/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
	 * Live KEV re-gate fan-out. Called by the KEV catalog sync (after the
	 * reconcile transaction commits) when a CVE newly enters the catalog:
	 * KEV status is time-varying independent of any scan, so a release that
	 * already ships {@code cveId} would otherwise keep a stale
	 * {@code kevCount}/gate verdict until its next scan. For each affected
	 * org we find every release carrying the CVE in any location, dedupe the
	 * union, and recompute each release's metrics (which re-stamps
	 * {@code knownExploited}/{@code kevCount} and re-fires the gate via the
	 * metrics-change path). Per-release try/catch so one bad release never
	 * aborts the fan-out.
	 *
	 * <p>Runs {@code @Async} — must be invoked through the Spring proxy
	 * (cross-bean call from the sync), never as a same-class self-call.
	 */
	@Async
	public void recomputeReleasesForNewlyKev(String cveId, Collection<UUID> affectedOrgs) {
		if (cveId == null || affectedOrgs == null || affectedOrgs.isEmpty()) {
			return;
		}
		Set<UUID> releaseUuids = new LinkedHashSet<>();
		for (UUID org : affectedOrgs) {
			if (org == null) continue;
			try {
				releaseUuids.addAll(
						sharedReleaseService.findReleaseUuidsWithVulnerabilityAnyLocation(org, cveId));
			} catch (Exception e) {
				log.error("Error finding releases for newly-KEV CVE {} in org {}", cveId, org, e);
			}
		}
		log.info("KEV re-gate: {} carries newly-KEV CVE {} across {} org(s); recomputing metrics",
				releaseUuids.size(), cveId, affectedOrgs.size());
		for (UUID releaseUuid : releaseUuids) {
			try {
				releaseService.computeReleaseMetrics(releaseUuid, false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release {} on newly-KEV CVE {}", releaseUuid, cveId, e);
			}
		}
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
