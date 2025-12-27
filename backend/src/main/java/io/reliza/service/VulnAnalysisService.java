/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.FindingType;
import io.reliza.model.LocationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.VulnAnalysis;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateVulnAnalysisDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.VulnAnalysisRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class VulnAnalysisService {
	
	@Autowired
	private VulnAnalysisRepository vulnAnalysisRepository;
	
	@Autowired
	private AuditService auditService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private VulnAnalysisUpdateService vulnAnalysisUpdateService;
	
	/**
	 * Get VulnAnalysis entity by UUID
	 */
	public Optional<VulnAnalysis> getVulnAnalysis(UUID analysisUuid) {
		return vulnAnalysisRepository.findById(analysisUuid);
	}
	
	/**
	 * Get VulnAnalysisData by UUID
	 */
	public Optional<VulnAnalysisData> getVulnAnalysisData(UUID analysisUuid) {
		Optional<VulnAnalysisData> vulnAnalysisData = Optional.empty();
		Optional<VulnAnalysis> vulnAnalysis = getVulnAnalysis(analysisUuid);
		if (vulnAnalysis.isPresent()) {
			vulnAnalysisData = Optional.of(VulnAnalysisData.dataFromRecord(vulnAnalysis.get()));
		}
		return vulnAnalysisData;
	}
	
	/**
	 * Find analysis by org, location, finding ID, and scope
	 */
	public Optional<VulnAnalysisData> findByOrgAndLocationAndFindingIdAndScope(
			UUID org, String location, String findingId, AnalysisScope scope) {
		Optional<VulnAnalysis> vulnAnalysis = vulnAnalysisRepository.findByOrgAndLocationAndFindingIdAndScope(
				org.toString(), location, findingId, scope.name());
		return vulnAnalysis.map(VulnAnalysisData::dataFromRecord);
	}
	
	/**
	 * Find analysis by org, location, finding ID, scope, and finding type
	 */
	public Optional<VulnAnalysisData> findByOrgAndLocationAndFindingIdAndScopeAndType(
			UUID org, String location, String findingId, AnalysisScope scope, UUID scopeId, FindingType findingType) {
		Optional<VulnAnalysis> vulnAnalysis = vulnAnalysisRepository.findByOrgAndLocationAndFindingIdAndScopeAndType(
				org.toString(), location, findingId, scope.name(), scopeId.toString(), findingType.name());
		return vulnAnalysis.map(VulnAnalysisData::dataFromRecord);
	}
	
	/**
	 * Find all analyses for a specific org, location, finding ID, and finding type
	 */
	public List<VulnAnalysisData> findByOrgAndLocationAndFindingIdAndType(UUID org, String location, String findingId, FindingType findingType) {
		// Minimize PURL if location is a PURL (for VULNERABILITY and VIOLATION types)
		String normalizedLocation = location;
		if ((findingType == FindingType.VULNERABILITY || findingType == FindingType.VIOLATION) 
			&& StringUtils.isNotEmpty(location) && location.startsWith("pkg:")) {
			normalizedLocation = Utils.minimizePurl(location);
		}
		
		List<VulnAnalysis> analyses = vulnAnalysisRepository.findByOrgAndLocationAndFindingIdAndType(
				org.toString(), normalizedLocation, findingId, findingType.name());
		return analyses.stream()
				.map(VulnAnalysisData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Find all analyses for a specific org and finding ID
	 */
	public List<VulnAnalysisData> findByOrgAndFindingId(UUID org, String findingId) {
		List<VulnAnalysis> analyses = vulnAnalysisRepository.findByOrgAndFindingId(org.toString(), findingId);
		return analyses.stream()
				.map(VulnAnalysisData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Find all analyses for a specific org, scope, and scopeUuid
	 */
	public List<VulnAnalysisData> findByOrgAndScopeAndScopeUuid(UUID org, AnalysisScope scope, UUID scopeUuid) {
		List<VulnAnalysis> analyses = vulnAnalysisRepository.findByOrgAndScopeAndScopeUuid(
				org.toString(), scope.name(), scopeUuid.toString());
		return analyses.stream()
				.map(VulnAnalysisData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Find all analyses for a specific org
	 */
	public List<VulnAnalysisData> findByOrg(UUID org) {
		List<VulnAnalysis> analyses = vulnAnalysisRepository.findByOrg(org.toString());
		return analyses.stream()
				.map(VulnAnalysisData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Find all vulnerability analyses affecting a component
	 * Includes: component-scoped analyses and org-wide analyses
	 */
	public List<VulnAnalysisData> findAllVulnAnalysisAffectingComponent(UUID componentUuid) throws RelizaException {
		Optional<ComponentData> componentData = getComponentService.getComponentData(componentUuid);
		if (componentData.isEmpty()) {
			throw new RelizaException("Component not found: " + componentUuid);
		}
		
		UUID org = componentData.get().getOrg();
		List<VulnAnalysis> analyses = vulnAnalysisRepository.findAffectingComponent(
				org.toString(), componentUuid.toString());
		return analyses.stream()
				.map(VulnAnalysisData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Find all vulnerability analyses affecting a branch
	 * Includes: branch-scoped, component-scoped (parent), and org-wide analyses
	 */
	public List<VulnAnalysisData> findAllVulnAnalysisAffectingBranch(UUID branchUuid) throws RelizaException {
		Optional<BranchData> branchData = branchService.getBranchData(branchUuid);
		if (branchData.isEmpty()) {
			throw new RelizaException("Branch not found: " + branchUuid);
		}
		
		UUID org = branchData.get().getOrg();
		UUID componentUuid = branchData.get().getComponent();
		
		List<VulnAnalysis> analyses = vulnAnalysisRepository.findAffectingBranch(
				org.toString(), branchUuid.toString(), componentUuid.toString());
		return analyses.stream()
				.map(VulnAnalysisData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Find all vulnerability analyses affecting a release
	 * Includes: release-scoped, branch-scoped (parent), component-scoped (parent), and org-wide analyses
	 */
	public List<VulnAnalysisData> findAllVulnAnalysisAffectingRelease(UUID releaseUuid) throws RelizaException {
		Optional<ReleaseData> releaseData = sharedReleaseService.getReleaseData(releaseUuid);
		if (releaseData.isEmpty()) {
			throw new RelizaException("Release not found: " + releaseUuid);
		}
		
		UUID org = releaseData.get().getOrg();
		UUID branchUuid = releaseData.get().getBranch();
		
		// Get component from branch
		Optional<BranchData> branchData = branchService.getBranchData(branchUuid);
		if (branchData.isEmpty()) {
			throw new RelizaException("Branch not found for release: " + branchUuid);
		}
		UUID componentUuid = branchData.get().getComponent();
		
		List<VulnAnalysis> analyses = vulnAnalysisRepository.findAffectingRelease(
				org.toString(), releaseUuid.toString(), branchUuid.toString(), componentUuid.toString());
		return analyses.stream()
				.map(VulnAnalysisData::dataFromRecord)
				.collect(Collectors.toList());
	}
	
	/**
	 * Internal save method with audit logging and revision increment
	 */
	@Transactional
	private VulnAnalysis saveVulnAnalysis(VulnAnalysis va, Map<String, Object> recordData, WhoUpdated wu) {
		if (null == recordData || recordData.isEmpty()) {
			throw new IllegalStateException("VulnAnalysis must have record data");
		}
		
		Optional<VulnAnalysis> existingAnalysis = vulnAnalysisRepository.findById(va.getUuid());
		if (existingAnalysis.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.VULN_ANALYSIS, va);
			va.setRevision(va.getRevision() + 1);
			va.setLastUpdatedDate(ZonedDateTime.now());
		}
		
		va.setRecordData(recordData);
		va = (VulnAnalysis) WhoUpdated.injectWhoUpdatedData(va, wu);
		VulnAnalysis savedVa = vulnAnalysisRepository.save(va);
		
		// Trigger async processing for org-wide analysis updates
		VulnAnalysisData savedVad = VulnAnalysisData.dataFromRecord(savedVa);
		vulnAnalysisUpdateService.processVulnAnalysisUpdate(savedVad);
		
		return savedVa;
	}
	
	/**
	 * Record to hold the scope hierarchy (release, branch, component)
	 */
	private record ScopeHierarchy(UUID release, UUID branch, UUID component) {}
	
	/**
	 * Helper method to populate release, branch, and component UUIDs based on scope
	 */
	private ScopeHierarchy populateScopeHierarchy(UUID scopeUuid, AnalysisScope scope) throws RelizaException {
		UUID release = null;
		UUID branch = null;
		UUID component = null;
		
		switch (scope) {
			case RELEASE:
				// For RELEASE scope: populate all three fields
				Optional<ReleaseData> releaseData = sharedReleaseService.getReleaseData(scopeUuid);
				if (releaseData.isPresent()) {
					release = releaseData.get().getUuid();
					branch = releaseData.get().getBranch();
					// Get component from branch
					Optional<BranchData> branchData = branchService.getBranchData(branch);
					if (branchData.isPresent()) {
						component = branchData.get().getComponent();
					}
				}
				break;
				
			case BRANCH:
				// For BRANCH scope: populate branch and component
				Optional<BranchData> branchData = branchService.getBranchData(scopeUuid);
				if (branchData.isPresent()) {
					branch = branchData.get().getUuid();
					component = branchData.get().getComponent();
				}
				break;
				
			case COMPONENT:
				// For COMPONENT scope: populate only component
				component = scopeUuid;
				break;
				
			default:
				// For other scopes, leave all null
				break;
		}
		
		return new ScopeHierarchy(release, branch, component);
	}
	
	/**
	 * Create a new vulnerability analysis
	 */
	@Transactional
	public VulnAnalysisData createVulnAnalysis(CreateVulnAnalysisDto createDto, WhoUpdated wu) throws RelizaException {
		
		// Minimize PURL if location type is PURL
		String normalizedLocation = createDto.getLocation();
		String rawLocation = null;
		if (createDto.getLocationType() == LocationType.PURL && createDto.getLocation() != null) {
			normalizedLocation = Utils.minimizePurl(createDto.getLocation());
			rawLocation = createDto.getLocation(); // Always store raw location for PURLs
		}
		
		// Check for existing analysis with same scope, location, finding ID, and type
		Optional<VulnAnalysisData> existingAnalysis = findByOrgAndLocationAndFindingIdAndScopeAndType(
				createDto.getScopeUuid(), normalizedLocation, createDto.getFindingId(), 
				createDto.getScope(), createDto.getScopeUuid(), createDto.getFindingType());
		
		if (existingAnalysis.isPresent()) {
			throw new RelizaException("Analysis record with the same scope already exists!");
		}
		
		// Populate release, branch, and component based on scope
		ScopeHierarchy hierarchy = populateScopeHierarchy(createDto.getScopeUuid(), createDto.getScope());
		
		VulnAnalysisData vad = VulnAnalysisData.createVulnAnalysisData(
				createDto.getOrg(), normalizedLocation, rawLocation, createDto.getLocationType(), 
				createDto.getFindingId(), createDto.getFindingAliases(), createDto.getFindingType(), 
				createDto.getScope(), createDto.getScopeUuid(),
				hierarchy.release(), hierarchy.branch(), hierarchy.component(),
				createDto.getState(), createDto.getJustification(), createDto.getDetails(), wu);
		
		// Set severity if provided
		if (createDto.getSeverity() != null) {
			vad.setSeverity(createDto.getSeverity());
		}
		
		VulnAnalysis va = new VulnAnalysis();
		va.setUuid(vad.getUuid());
		Map<String, Object> recordData = Utils.OM.convertValue(vad, new TypeReference<Map<String, Object>>() {});
		var savedVA = saveVulnAnalysis(va, recordData, wu);
		return VulnAnalysisData.dataFromRecord(savedVA);
	}
	
	/**
	 * Update an existing vulnerability analysis with a new state
	 */
	public VulnAnalysisData updateAnalysisState(
			UUID analysisUuid,
			AnalysisState newState,
			AnalysisJustification newJustification,
			String details,
			List<String> findingAliases,
			VulnerabilitySeverity severity,
			WhoUpdated wu) {
		
		Optional<VulnAnalysis> existingAnalysis = vulnAnalysisRepository.findById(analysisUuid);
		if (existingAnalysis.isEmpty()) {
			throw new IllegalArgumentException("VulnAnalysis not found: " + analysisUuid);
		}
		
		VulnAnalysis va = existingAnalysis.get();
		VulnAnalysisData vad = VulnAnalysisData.dataFromRecord(va);
		
		// Update finding aliases if provided
		if (findingAliases != null) {
			vad.setFindingAliases(findingAliases);
		}
		
		// Update severity if provided
		if (severity != null) {
			vad.setSeverity(severity);
		}
		
		// Add new history entry and update current state
		vad.addAnalysisHistoryEntry(newState, newJustification, details, severity, wu);
		
		Map<String, Object> recordData = Utils.OM.convertValue(vad, new TypeReference<Map<String, Object>>() {});
		VulnAnalysis savedVA = saveVulnAnalysis(va, recordData, wu);
		return VulnAnalysisData.dataFromRecord(savedVA);
	}
	
	/**
	 * Process ReleaseMetricsDto and enrich findings with their vulnerability analysis data
	 * Search priority: Release -> Branch -> Component -> Org (stops at first match)
	 */
	public void processReleaseMetricsDto(UUID orgId, UUID scopeId, AnalysisScope scopeType, ReleaseMetricsDto metricsDto) {
		if (metricsDto == null) {
			return;
		}
		metricsDto.organizeVulnerabilitiesWithAliases();
		metricsDto.deduplicateViolations();
		metricsDto.deduplicateWeaknesses();
		// Process violations
		if (metricsDto.getViolationDetails() != null && !metricsDto.getViolationDetails().isEmpty()) {
			List<ReleaseMetricsDto.ViolationDto> enrichedViolations = metricsDto.getViolationDetails().stream()
					.map(violation -> enrichViolation(orgId, scopeId, scopeType, violation))
					.collect(Collectors.toList());
			metricsDto.setViolationDetails(enrichedViolations);
		}
		
		// Process vulnerabilities
		if (metricsDto.getVulnerabilityDetails() != null && !metricsDto.getVulnerabilityDetails().isEmpty()) {
			List<ReleaseMetricsDto.VulnerabilityDto> enrichedVulnerabilities = metricsDto.getVulnerabilityDetails().stream()
					.map(vulnerability -> enrichVulnerability(orgId, scopeId, scopeType, vulnerability))
					.collect(Collectors.toList());
			metricsDto.setVulnerabilityDetails(enrichedVulnerabilities);
		}
		
		// Process weaknesses
		if (metricsDto.getWeaknessDetails() != null && !metricsDto.getWeaknessDetails().isEmpty()) {
			List<ReleaseMetricsDto.WeaknessDto> enrichedWeaknesses = metricsDto.getWeaknessDetails().stream()
					.map(weakness -> enrichWeakness(orgId, scopeId, scopeType, weakness))
					.collect(Collectors.toList());
			metricsDto.setWeaknessDetails(enrichedWeaknesses);
		}
		metricsDto.computeMetricsFromFacts();
	}
	
	/**
	 * Gets the most recent analysis date from the analysis history
	 */
	private ZonedDateTime getLatestAnalysisDate(VulnAnalysisData analysis) {
		if (analysis.getAnalysisHistory() != null && !analysis.getAnalysisHistory().isEmpty()) {
			// Get the most recent entry (last in the list)
			return analysis.getAnalysisHistory().get(analysis.getAnalysisHistory().size() - 1).getCreatedDate();
		}
		// Fallback to createdDate if history is empty
		return analysis.getCreatedDate();
	}
	
	private ReleaseMetricsDto.ViolationDto enrichViolation(UUID orgId, UUID scopeId, AnalysisScope scopeType, ReleaseMetricsDto.ViolationDto violation) {
		if (violation.analysisState() != null) {
			return violation; // Already enriched
		}
		
		// Minimize PURL before lookup
		String normalizedPurl = Utils.minimizePurl(violation.purl());
		
		// Search with priority: Release -> Branch -> Component -> Org
		Optional<VulnAnalysisData> analysisOpt = findAnalysisWithPriority(orgId, scopeId, scopeType, normalizedPurl, violation.type().name(), FindingType.VIOLATION);
		
		if (analysisOpt.isPresent()) {
			VulnAnalysisData analysis = analysisOpt.get();
			return new ReleaseMetricsDto.ViolationDto(
					violation.purl(),
					violation.type(),
					violation.license(),
					violation.violationDetails(),
					violation.sources(),
					analysis.getAnalysisState(),
					getLatestAnalysisDate(analysis),
					violation.attributedAt());
		}
		
		return violation; // No analysis found
	}
	
	private ReleaseMetricsDto.VulnerabilityDto enrichVulnerability(UUID orgId, UUID scopeId, AnalysisScope scopeType, ReleaseMetricsDto.VulnerabilityDto vulnerability) {
		// Minimize PURL before lookup
		String normalizedPurl = Utils.minimizePurl(vulnerability.purl());
		
		// Search with priority: Release -> Branch -> Component -> Org
		Optional<VulnAnalysisData> analysisOpt = findAnalysisWithPriority(orgId, scopeId, scopeType, normalizedPurl, vulnerability.vulnId(), FindingType.VULNERABILITY);
		
		if (analysisOpt.isPresent()) {
			VulnAnalysisData analysis = analysisOpt.get();
			
			// Combine aliases from vulnerability and analysis (no duplicates)
			Set<ReleaseMetricsDto.VulnerabilityAliasDto> combinedAliases = new LinkedHashSet<>();
			if (vulnerability.aliases() != null) {
				combinedAliases.addAll(vulnerability.aliases());
			}
			
			// Add aliases from analysis, converting String to VulnerabilityAliasDto
			if (analysis.getFindingAliases() != null && !analysis.getFindingAliases().isEmpty()) {
				for (String aliasId : analysis.getFindingAliases()) {
					ReleaseMetricsDto.VulnerabilityAliasType type = ReleaseMetricsDto.detectAliasType(aliasId);
					combinedAliases.add(new ReleaseMetricsDto.VulnerabilityAliasDto(type, aliasId));
				}
			}
			
			// Handle severity from VulnAnalysis
			VulnerabilitySeverity finalSeverity = vulnerability.severity();
			Set<ReleaseMetricsDto.SeveritySourceDto> combinedSeverities = new LinkedHashSet<>();
			if (vulnerability.severities() != null) {
				combinedSeverities.addAll(vulnerability.severities());
			}
			
			// If VulnAnalysis has a severity, add it as ANALYSIS source and use it as primary severity
			if (analysis.getSeverity() != null) {
				combinedSeverities.add(new ReleaseMetricsDto.SeveritySourceDto(
						ReleaseMetricsDto.SeveritySource.ANALYSIS, analysis.getSeverity()));
				finalSeverity = analysis.getSeverity();
			}
			
			return new ReleaseMetricsDto.VulnerabilityDto(
					vulnerability.purl(),
					vulnerability.vulnId(),
					finalSeverity,
					combinedAliases,
					vulnerability.sources(),
					combinedSeverities,
					analysis.getAnalysisState(),
					getLatestAnalysisDate(analysis),
					vulnerability.attributedAt());
		}
		
		return vulnerability; // No analysis found
	}
	
	private ReleaseMetricsDto.WeaknessDto enrichWeakness(UUID orgId, UUID scopeId, AnalysisScope scopeType, ReleaseMetricsDto.WeaknessDto weakness) {
		if (weakness.analysisState() != null) {
			return weakness; // Already enriched
		}
		
		// Search for analysis by location (code point) and finding ID (cweId or ruleId)
		String findingId = weakness.cweId() != null ? weakness.cweId() : weakness.ruleId();
		// Search with priority: Release -> Branch -> Component -> Org
		Optional<VulnAnalysisData> analysisOpt = findAnalysisWithPriority(orgId, scopeId, scopeType, weakness.location(), findingId, FindingType.WEAKNESS);
		
		if (analysisOpt.isPresent()) {
			VulnAnalysisData analysis = analysisOpt.get();
			
			// If VulnAnalysis has a severity, use it; otherwise use the weakness's severity
			VulnerabilitySeverity finalSeverity = analysis.getSeverity() != null 
					? analysis.getSeverity() 
					: weakness.severity();
			
			return new ReleaseMetricsDto.WeaknessDto(
					weakness.cweId(),
					weakness.ruleId(),
					weakness.location(),
					weakness.fingerprint(),
					finalSeverity,
					weakness.sources(),
					analysis.getAnalysisState(),
					getLatestAnalysisDate(analysis),
					weakness.attributedAt());
		}
		
		return weakness; // No analysis found
	}
	
	/**
	 * Find analysis with priority-based search
	 * Priority order: Release -> Branch -> Component -> Org
	 * Stops at first match found
	 */
	private Optional<VulnAnalysisData> findAnalysisWithPriority(UUID orgId, UUID scopeId, AnalysisScope scopeType, String location, String findingId, FindingType findingType) {
		// Build hierarchy traversal based on starting scope type
		UUID releaseId = null;
		UUID branchId = null;
		UUID componentId = null;
		
		// Resolve the hierarchy based on the starting scope
		switch (scopeType) {
		case RELEASE:
			releaseId = scopeId;
			break;
		case BRANCH:
			branchId = scopeId;
			break;
		case COMPONENT:
			componentId = scopeId;
			break;
		case RESOURCE_GROUP:
			throw new RuntimeException("Not implemented"); //TODO
		case ORG:
			break;
		default:
			throw new RuntimeException("Not implemented");
		}
		
		if (null != releaseId) {
			Optional<VulnAnalysisData> releaseResult = findByOrgAndLocationAndFindingIdAndScopeAndType(
					orgId, location, findingId, AnalysisScope.RELEASE, releaseId, findingType);
			if (releaseResult.isPresent()) return releaseResult;
			Optional<ReleaseData> releaseOpt = sharedReleaseService.getReleaseData(scopeId);
			if (releaseOpt.isPresent()) branchId = releaseOpt.get().getBranch();
		}
		
		if (null != branchId) {
			Optional<VulnAnalysisData> branchResult = findByOrgAndLocationAndFindingIdAndScopeAndType(
					orgId, location, findingId, AnalysisScope.BRANCH, branchId, findingType);
			if (branchResult.isPresent()) return branchResult;
			Optional<BranchData> obd = branchService.getBranchData(branchId);
			if (obd.isPresent()) componentId = obd.get().getComponent();
		} 
		
		if (null != componentId) {
			Optional<VulnAnalysisData> componentResult = findByOrgAndLocationAndFindingIdAndScopeAndType(
					orgId, location, findingId, AnalysisScope.COMPONENT, componentId, findingType);
			if (componentResult.isPresent()) return componentResult;
			Optional<ComponentData> ocd = getComponentService.getComponentData(componentId);
			if (ocd.isPresent()) orgId = ocd.get().getOrg();
		}
		
		if (null != orgId) {
			Optional<VulnAnalysisData> orgResult = findByOrgAndLocationAndFindingIdAndScopeAndType(
					orgId, location, findingId, AnalysisScope.ORG, orgId, findingType);
			if (orgResult.isPresent()) return orgResult;
		}
		
		return Optional.empty();
	}

}
