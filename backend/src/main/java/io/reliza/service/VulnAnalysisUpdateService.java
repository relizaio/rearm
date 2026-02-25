/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.reliza.model.AnalysisScope;
import io.reliza.model.Artifact;
import io.reliza.model.Release;
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
	 * Process org-wide updates - affects both artifacts and releases
	 */
	private void processOrgWideUpdate(VulnAnalysisData analysisData) {
		// Use SQL-level queries to find affected artifacts
		List<Artifact> affectedArtifacts = findAffectedArtifacts(analysisData);
		log.info("Found {} affected artifacts for finding: {}", affectedArtifacts.size(), analysisData.getFindingId());
		
		for (Artifact artifact : affectedArtifacts) {
			try {
				artifactService.computeArtifactMetrics(artifact.getUuid());
			} catch (Exception e) {
				log.error("Error recomputing metrics for artifact: {}", artifact.getUuid(), e);
			}
		}

		List<Release> affectedReleases = findAffectedReleases(analysisData);
		log.info("Found {} affected releases for finding: {}", affectedReleases.size(), analysisData.getFindingId());

		for (Release release : affectedReleases) {
			try {
				releaseService.computeReleaseMetrics(release.getUuid(), false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release: {}", release.getUuid(), e);
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
		List<Release> affectedReleases = findAffectedReleasesInBranch(analysisData);
		log.info("Found {} affected releases in branch {} for finding: {}", 
				affectedReleases.size(), analysisData.getScopeUuid(), analysisData.getFindingId());
		
		for (Release release : affectedReleases) {
			try {
				releaseService.computeReleaseMetrics(release.getUuid(), false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release: {}", release.getUuid(), e);
			}
		}
	}
	
	/**
	 * Process component-specific update - find releases in the component that match the finding
	 */
	private void processComponentUpdate(VulnAnalysisData analysisData) {
		List<Release> affectedReleases = findAffectedReleasesInComponent(analysisData);
		log.info("Found {} affected releases in component {} for finding: {}", 
				affectedReleases.size(), analysisData.getScopeUuid(), analysisData.getFindingId());
		
		for (Release release : affectedReleases) {
			try {
				releaseService.computeReleaseMetrics(release.getUuid(), false);
			} catch (Exception e) {
				log.error("Error recomputing metrics for release: {}", release.getUuid(), e);
			}
		}
	}
	
	/**
	 * Find artifacts affected by the vulnerability analysis using SQL-level queries
	 */
	private List<Artifact> findAffectedArtifacts(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();
		
		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<Artifact> vulnArtifacts = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnArtifacts.addAll(artifactService.findArtifactsWithVulnerability(analysisData.getOrg(), location, findingId));
                }
                vulnArtifacts.addAll(artifactService.findArtifactsWithVulnerability(analysisData.getOrg(), rawLocation, findingId));
				return vulnArtifacts;
			case VIOLATION:
                List<Artifact> violArtifacts = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violArtifacts.addAll(artifactService.findArtifactsWithViolation(analysisData.getOrg(), location, findingId));
                }
				violArtifacts.addAll(artifactService.findArtifactsWithViolation(analysisData.getOrg(), rawLocation, findingId));
                return violArtifacts;
			case WEAKNESS:
				return artifactService.findArtifactsWithWeakness(analysisData.getOrg(), location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}
	
	/**
	 * Find releases affected by the vulnerability analysis in a specific branch
	 */
	private List<Release> findAffectedReleasesInBranch(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();
		UUID branchUuid = analysisData.getScopeUuid();
		
		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<Release> vulnReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnReleases.addAll(sharedReleaseService.findReleasesWithVulnerabilityInBranch(analysisData.getOrg(), branchUuid, location, findingId));
                }
                vulnReleases.addAll(sharedReleaseService.findReleasesWithVulnerabilityInBranch(analysisData.getOrg(), branchUuid, rawLocation, findingId));
				return vulnReleases;
			case VIOLATION:
                List<Release> violReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violReleases.addAll(sharedReleaseService.findReleasesWithViolationInBranch(analysisData.getOrg(), branchUuid, location, findingId));
                }
				violReleases.addAll(sharedReleaseService.findReleasesWithViolationInBranch(analysisData.getOrg(), branchUuid, rawLocation, findingId));
                return violReleases;
			case WEAKNESS:
				return sharedReleaseService.findReleasesWithWeaknessInBranch(analysisData.getOrg(), branchUuid, location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}
	
	/**
	 * Find releases affected by the vulnerability analysis in a specific component
	 */
	private List<Release> findAffectedReleasesInComponent(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();
		UUID componentUuid = analysisData.getScopeUuid();
		
		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<Release> vulnReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnReleases.addAll(sharedReleaseService.findReleasesWithVulnerabilityInComponent(analysisData.getOrg(), componentUuid, location, findingId));
                }
                vulnReleases.addAll(sharedReleaseService.findReleasesWithVulnerabilityInComponent(analysisData.getOrg(), componentUuid, rawLocation, findingId));
				return vulnReleases;
			case VIOLATION:
                List<Release> violReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violReleases.addAll(sharedReleaseService.findReleasesWithViolationInComponent(analysisData.getOrg(), componentUuid, location, findingId));
                }
				violReleases.addAll(sharedReleaseService.findReleasesWithViolationInComponent(analysisData.getOrg(), componentUuid, rawLocation, findingId));
                return violReleases;
			case WEAKNESS:
				return sharedReleaseService.findReleasesWithWeaknessInComponent(analysisData.getOrg(), componentUuid, location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}
	
	/**
	 * Find releases affected by the vulnerability analysis using SQL-level queries
	 */
	private List<Release> findAffectedReleases(VulnAnalysisData analysisData) {
        String location = analysisData.getLocation();
		String rawLocation = analysisData.getRawLocation();
		String findingId = analysisData.getFindingId();
		
		switch (analysisData.getFindingType()) {
			case VULNERABILITY:
                List<Release> vulnReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    vulnReleases.addAll(sharedReleaseService.findReleasesWithVulnerability(analysisData.getOrg(), location, findingId));
                }
                vulnReleases.addAll(sharedReleaseService.findReleasesWithVulnerability(analysisData.getOrg(), rawLocation, findingId));
				return vulnReleases;
			case VIOLATION:
                List<Release> violReleases = new LinkedList<>();
                if (!rawLocation.equals(location)) {
                    violReleases.addAll(sharedReleaseService.findReleasesWithViolation(analysisData.getOrg(), location, findingId));
                }
				violReleases.addAll(sharedReleaseService.findReleasesWithViolation(analysisData.getOrg(), rawLocation, findingId));
                return violReleases;
			case WEAKNESS:
				return sharedReleaseService.findReleasesWithWeakness(analysisData.getOrg(), location, findingId);
			default:
				log.warn("Unknown finding type: {}", analysisData.getFindingType());
				return List.of();
		}
	}
}
