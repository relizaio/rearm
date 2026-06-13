package io.reliza.service;

import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.reliza.model.AnalysisScope;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKeyAccess;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.service.IntegrationService.DependencyTrackUploadResult;
import io.reliza.service.IntegrationService.UploadableBom;
import io.reliza.service.RebomService.BomMeta;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class DTrackService {
    
    @Autowired
    private IntegrationService integrationService;
    
    @Autowired
    private EncryptionService encryptionService;
    
    @Autowired
    private WebClient dtrackWebClient;
    
    @Autowired
    private SharedArtifactService sharedArtifactService;
    
    @Autowired
    private RebomService rebomService;
    
    @Autowired
    @Lazy
    private ArtifactService artifactService;

    @Autowired
    private VulnAnalysisService vulnAnalysisService;

    @Autowired
    private ApiKeyAccessService apiKeyAccessService;

    // ========================================
    // PUBLIC API - Orchestration Layer
    // ========================================
    
    
    // ========================================
    // SBOM PROBING
    // ========================================

    public enum SbomProbingStatus { PENDING, ENRICHING, DONE, FAILED }

    public record SbomProbingResultDto(SbomProbingStatus status, ArtifactData.DependencyTrackIntegration metrics) {}

    public String startSbomProbing(UUID orgUuid, String sbomJson, UUID componentUuid, UUID branchUuid,
            UUID apiKeyUuid, String ipAddress) throws RelizaException {
        // 1. Detect BOM format
        BomFormat format = artifactService.detectBomFormat(sbomJson);

        // 2. Get digest from rebom and check for existing artifact
        String digest = rebomService.getBomDigestProbe(sbomJson, format, orgUuid);
        if (StringUtils.isNotEmpty(digest)) {
            Optional<Artifact> existingArtifact =
                sharedArtifactService.findArtifactByStoredDigest(orgUuid, digest);
            if (existingArtifact.isPresent()) {
                ArtifactData ead = ArtifactData.dataFromRecord(existingArtifact.get());
                if (ead.getMetrics() != null && StringUtils.isNotEmpty(ead.getMetrics().getDependencyTrackProject())) {
                    StringBuilder dedup = new StringBuilder("DEDUP|").append(ead.getMetrics().getDependencyTrackProject());
                    appendScopeSuffix(dedup, componentUuid, branchUuid);
                    return encryptionService.encrypt(dedup.toString());
                }
            }
        }

        // 3. Determine scope for session
        UUID scopeId = orgUuid;
        AnalysisScope scope = AnalysisScope.ORG;
        if (branchUuid != null) {
            scopeId = branchUuid;
            scope = AnalysisScope.BRANCH;
        } else if (componentUuid != null) {
            scopeId = componentUuid;
            scope = AnalysisScope.COMPONENT;
        }
        final UUID fScopeId = scopeId;
        final AnalysisScope fScope = scope;

        // 4. Create DB session record
        String probingProjectName = "probing___" + UUID.randomUUID();
        String initialNotes = buildSessionNotes(SbomProbingStatus.ENRICHING, null, probingProjectName, null, scopeId, scope);
        ApiKeyAccess session =
            apiKeyAccessService.createSbomProbingSession(apiKeyUuid, ipAddress, orgUuid, initialNotes);
        UUID sessionUuid = session.getUuid();

        // 5. Launch async enrichment + DTrack upload
        final String capturedSbomJson = sbomJson;
        final BomFormat capturedFormat = format;
        final UUID capturedOrg = orgUuid;
        final String capturedProjectName = probingProjectName;
        CompletableFuture.runAsync(() ->
            performEnrichmentAndUpload(sessionUuid, capturedSbomJson, capturedFormat,
                capturedOrg, capturedProjectName, fScopeId, fScope));

        return encryptionService.encrypt("SESSION|" + sessionUuid);
    }

    private void performEnrichmentAndUpload(UUID sessionUuid, String sbomJson, BomFormat format,
            UUID orgUuid, String probingProjectName, UUID scopeId, AnalysisScope scope) {
        try {
            RebomService.EnrichedBomProbeResult enrichResult =
                rebomService.getEnrichedBomProbe(sbomJson, format, orgUuid);
            String bomToSubmit;
            switch (enrichResult.status()) {
                case COMPLETED -> bomToSubmit = enrichResult.enrichedBom();
                case SKIPPED -> bomToSubmit = sbomJson;
                default -> {
                    // FAILED
                    apiKeyAccessService.updateSbomProbingSessionNotes(sessionUuid,
                        buildSessionNotes(SbomProbingStatus.FAILED, null, probingProjectName, null, scopeId, scope));
                    return;
                }
            }
            JsonNode bomNode = Utils.OM.readTree(bomToSubmit);
            UploadableBom uploadableBom = new UploadableBom(bomNode, null, false);
            DependencyTrackUploadResult uploadResult = integrationService.sendBomToDependencyTrack(
                orgUuid, uploadableBom, probingProjectName, "0.1.0");
            if (uploadResult == null) {
                apiKeyAccessService.updateSbomProbingSessionNotes(sessionUuid,
                    buildSessionNotes(SbomProbingStatus.FAILED, null, probingProjectName, null, scopeId, scope));
                return;
            }
            apiKeyAccessService.updateSbomProbingSessionNotes(sessionUuid,
                buildSessionNotes(SbomProbingStatus.PENDING, uploadResult.projectId(),
                    probingProjectName, uploadResult.token(), scopeId, scope));
        } catch (Exception e) {
            log.error("Error during SBOM probing enrichment for session {}: {}", sessionUuid, e.getMessage(), e);
            apiKeyAccessService.updateSbomProbingSessionNotes(sessionUuid,
                buildSessionNotes(SbomProbingStatus.FAILED, null, probingProjectName, null, scopeId, scope));
        }
    }

    private String buildSessionNotes(SbomProbingStatus status, String projectId,
            String probingProjectName, String uploadToken, UUID scopeId, AnalysisScope scope) {
        Map<String, Object> notes = new HashMap<>();
        notes.put("status", status.name());
        notes.put("projectId", projectId);
        notes.put("probingProjectName", probingProjectName);
        notes.put("uploadToken", uploadToken);
        notes.put("scopeId", scopeId != null ? scopeId.toString() : null);
        notes.put("scope", scope != null ? scope.name() : null);
        try {
            return Utils.OM.writeValueAsString(notes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize session notes", e);
        }
    }

    private void appendScopeSuffix(StringBuilder sb, UUID componentUuid, UUID branchUuid) {
        if (branchUuid != null) {
            sb.append("|b:").append(branchUuid);
        } else if (componentUuid != null) {
            sb.append("|c:").append(componentUuid);
        }
    }

    public SbomProbingResultDto checkSbomProbingResult(UUID orgUuid, String encryptedRunId) throws RelizaException {
        String raw = encryptionService.decrypt(encryptedRunId);

        // DEDUP path: instant DONE
        if (raw.startsWith("DEDUP|")) {
            String[] parts = raw.split("\\|", 3);
            String projectId = parts[1];
            UUID scopeId = orgUuid;
            AnalysisScope scope = AnalysisScope.ORG;
            if (parts.length == 3) {
                String scopePart = parts[2];
                if (scopePart.startsWith("b:")) { scopeId = UUID.fromString(scopePart.substring(2)); scope = AnalysisScope.BRANCH; }
                else if (scopePart.startsWith("c:")) { scopeId = UUID.fromString(scopePart.substring(2)); scope = AnalysisScope.COMPONENT; }
            }
            return fetchVulnsAndBuildDone(orgUuid, projectId, scopeId, scope);
        }

        // SESSION path
        if (raw.startsWith("SESSION|")) {
            UUID sessionUuid = UUID.fromString(raw.substring("SESSION|".length()));
            Optional<ApiKeyAccess> oas = apiKeyAccessService.getSbomProbingSession(sessionUuid);
            if (oas.isEmpty()) return new SbomProbingResultDto(SbomProbingStatus.FAILED, null);
            String notesJson = oas.get().getNotes();
            if (StringUtils.isEmpty(notesJson)) return new SbomProbingResultDto(SbomProbingStatus.FAILED, null);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> notes = Utils.OM.readValue(notesJson, Map.class);
                SbomProbingStatus status = SbomProbingStatus.valueOf((String) notes.get("status"));
                switch (status) {
                    case ENRICHING -> { return new SbomProbingResultDto(SbomProbingStatus.ENRICHING, null); }
                    case FAILED -> { return new SbomProbingResultDto(SbomProbingStatus.FAILED, null); }
                    case PENDING -> {
                        String uploadToken = (String) notes.get("uploadToken");
                        String projectId = (String) notes.get("projectId");
                        String scopeStr = (String) notes.get("scope");
                        String scopeIdStr = (String) notes.get("scopeId");
                        UUID scopeId = scopeIdStr != null ? UUID.fromString(scopeIdStr) : orgUuid;
                        AnalysisScope scope = scopeStr != null ? AnalysisScope.valueOf(scopeStr) : AnalysisScope.ORG;
                        return pollDTrackToken(orgUuid, uploadToken, projectId, scopeId, scope);
                    }
                    default -> { return new SbomProbingResultDto(SbomProbingStatus.FAILED, null); }
                }
            } catch (Exception e) {
                throw new RelizaException("Error reading probing session: " + e.getMessage());
            }
        }

        throw new RelizaException("Invalid runId format");
    }

    private SbomProbingResultDto pollDTrackToken(UUID orgUuid, String uploadToken, String projectId,
            UUID scopeId, AnalysisScope scope) throws RelizaException {
        Optional<IntegrationData> oid = integrationService.getIntegrationDataByOrgTypeIdentifier(
            orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
        if (oid.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
        String apiToken = encryptionService.decrypt(oid.get().getSecret());
        URI baseUri = oid.get().getUri();
        URI eventTokenUri = URI.create(baseUri + "/api/v1/event/token/" + uploadToken);
        var resp = dtrackWebClient.get().uri(eventTokenUri).header("X-API-Key", apiToken)
            .retrieve().toEntity(String.class).block();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> respMap = Utils.OM.readValue(resp.getBody(), Map.class);
            Boolean isProcessing = (Boolean) respMap.get("processing");
            if (Boolean.TRUE.equals(isProcessing)) return new SbomProbingResultDto(SbomProbingStatus.PENDING, null);
        } catch (Exception e) {
            throw new RelizaException("Error checking DTrack processing status: " + e.getMessage());
        }
        return fetchVulnsAndBuildDone(orgUuid, projectId, scopeId, scope);
    }

    private SbomProbingResultDto fetchVulnsAndBuildDone(UUID orgUuid, String projectId,
            UUID scopeId, AnalysisScope scope) throws RelizaException {
        try {
            Optional<IntegrationData> oid = integrationService.getIntegrationDataByOrgTypeIdentifier(
                orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
            if (oid.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
            String apiToken = encryptionService.decrypt(oid.get().getSecret());
            URI baseUri = oid.get().getUri();
            List<VulnerabilityDto> vulns = integrationService.fetchDependencyTrackVulnerabilityDetails(
                baseUri, apiToken, projectId, null, orgUuid, null);
            List<ViolationDto> violations = integrationService.fetchDependencyTrackViolationDetails(
                baseUri, apiToken, projectId, null, orgUuid, null);
            ArtifactData.DependencyTrackIntegration dti = new ArtifactData.DependencyTrackIntegration();
            dti.setVulnerabilityDetails(vulns);
            dti.setViolationDetails(violations);
            vulnAnalysisService.processReleaseMetricsDto(orgUuid, scopeId, scope, dti);
            return new SbomProbingResultDto(SbomProbingStatus.DONE, dti);
        } catch (Exception e) {
            throw new RelizaException("Error fetching vulnerabilities and violations: " + e.getMessage());
        }
    }

    
    
    // ========================================
    // BOM UPLOAD & PROCESSING
    // ========================================
    
    /**
     * Get or create DTrack project. Handles 409 conflict automatically.
     */
    private UUID getOrCreateProject(
            DTrackIntegration integration,
            String projectName,
            String projectVersion) throws RelizaException {
        
        DependencyTrackProjectInput dtpi = new DependencyTrackProjectInput(
            projectName, projectVersion, null, "APPLICATION", List.of(), List.of(), true, false);
        URI projectCreateUri = buildDTrackUri(integration, "/api/v1/project");
        
        try {
            var createResponse = dtrackWebClient
                .put()
                .uri(projectCreateUri)
                .header("X-API-Key", integration.apiToken())
                .bodyValue(dtpi)
                .retrieve()
                .toEntity(String.class)
                .block();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> createProjResp = Utils.OM.readValue(
                createResponse.getBody(), Map.class);
            return UUID.fromString((String) createProjResp.get("uuid"));
            
        } catch (WebClientResponseException wcre) {
            if (wcre.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(409))) {
                log.info("Project {}:{} already exists in DTrack, looking up existing project", 
                    projectName, projectVersion);
                return lookupExistingProject(integration, projectName, projectVersion);
            } else {
                throw new RelizaException("Failed to create DTrack project: " + wcre.getMessage());
            }
        } catch (Exception e) {
            throw new RelizaException("Failed to create DTrack project: " + e.getMessage());
        }
    }
    
    /**
     * Lookup existing DTrack project by name and version.
     */
    private UUID lookupExistingProject(
            DTrackIntegration integration,
            String projectName,
            String projectVersion) throws RelizaException {
        
        try {
            String lookupUriStr = integration.integrationData().getUri().toString() + 
                "/api/v1/project/lookup?name=" + 
                java.net.URLEncoder.encode(projectName, java.nio.charset.StandardCharsets.UTF_8) + 
                "&version=" + java.net.URLEncoder.encode(projectVersion, 
                    java.nio.charset.StandardCharsets.UTF_8);
            URI lookupUri = URI.create(lookupUriStr);
            
            var lookupResp = dtrackWebClient
                .get()
                .uri(lookupUri)
                .header("X-API-Key", integration.apiToken())
                .retrieve()
                .toEntity(String.class)
                .block();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> lookupProjResp = Utils.OM.readValue(
                lookupResp.getBody(), Map.class);
            String projectIdStr = (String) lookupProjResp.get("uuid");
            
            if (StringUtils.isEmpty(projectIdStr)) {
                log.error("Project lookup failed for {}:{} - no UUID in response", 
                    projectName, projectVersion);
                throw new RelizaException("Failed to lookup existing DTrack project");
            }
            
            return UUID.fromString(projectIdStr);
        } catch (Exception e) {
            throw new RelizaException("Failed to lookup DTrack project: " + e.getMessage());
        }
    }
    
    /**
     * Upload BOM to specific DTrack project.
     */
    private DependencyTrackUploadResult uploadBomToProject(
            DTrackIntegration integration,
            UUID projectId,
            UploadableBom bom,
            String projectName,
            String projectVersion) throws RelizaException {
        
        try {
            URI apiUri = buildDTrackUri(integration, "/api/v1/bom");
            String bomBase64 = encodeBom(bom);
            
            DependencyTrackBomPayload payload = new DependencyTrackBomPayload(projectId, bomBase64);
            
            var tokenResp = dtrackWebClient
                .put()
                .uri(apiUri)
                .header("X-API-Key", integration.apiToken())
                .bodyValue(payload)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("DTrack BOM upload failed: status={}, body={}", 
                                    response.statusCode(), body);
                                return Mono.error(new RuntimeException(
                                    "DTrack BOM upload error: " + response.statusCode() + " - " + body));
                            });
                    }
                    return response.toEntity(String.class);
                })
                .block();
            
            @SuppressWarnings("unchecked")
            Map<String, Object> addBomResp = Utils.OM.readValue(tokenResp.getBody(), Map.class);
            String token = (String) addBomResp.get("token");
            URI fullDtrackUri = URI.create(integration.integrationData().getFrontendUri()
                .toString() + "/projects/" + projectId.toString());
            
            return new DependencyTrackUploadResult(projectId.toString(), token, 
                projectName, projectVersion, fullDtrackUri);
        } catch (Exception e) {
            log.error("Error uploading BOM to DTrack project: " + projectId, e);
            throw new RelizaException("Error uploading BOM to DTrack project: " + e.getMessage());
        }
    }
    
    /**
     * Encode BOM to Base64 for DTrack API.
     */
    private String encodeBom(UploadableBom bom) throws JacksonException {
        if (!bom.isRaw()) {
            String bomString = Utils.OM.writeValueAsString(bom.bomJson());
            return Base64.getEncoder().encodeToString(bomString.getBytes());
        }
        return Base64.getEncoder().encodeToString(bom.rawBom());
    }
    
    // ========================================
    // INTEGRATION & AUTHENTICATION
    // ========================================
    
    /**
     * Get DTrack integration data with decrypted API token.
     * Returns empty if no integration configured.
     */
    private Optional<DTrackIntegration> getDTrackIntegration(UUID orgUuid) {
        Optional<IntegrationData> oid = integrationService.getIntegrationDataByOrgTypeIdentifier(
            orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
        
        if (oid.isEmpty()) {
            return Optional.empty();
        }
        
        String apiToken = encryptionService.decrypt(oid.get().getSecret());
        return Optional.of(new DTrackIntegration(oid.get(), apiToken));
    }
    
    // ========================================
    // HTTP COMMUNICATION HELPERS
    // ========================================
    
    /**
     * Build DTrack API URI.
     */
    private URI buildDTrackUri(DTrackIntegration integration, String path) {
        return URI.create(integration.integrationData().getUri().toString() + path);
    }

    // ========================================
    // SYNTHETIC-SBOM REUSE HOOKS
    // ========================================
    // Public entrypoints letting SyntheticSbomService drive the same project /
    // upload / token / findings client used by the per-artifact path, without
    // duplicating the WebClient plumbing or exposing DTrackIntegration.

    /** Create (or look up) the DTrack project for a synthetic bucket. */
    public UUID syntheticGetOrCreateProject(UUID orgUuid, String projectName, String projectVersion)
            throws RelizaException {
        Optional<DTrackIntegration> integration = getDTrackIntegration(orgUuid);
        if (integration.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
        return getOrCreateProject(integration.get(), projectName, projectVersion);
    }

    /** Upload a synthetic CycloneDX BOM to a project; returns the processing token. */
    public String syntheticUploadBom(UUID orgUuid, UUID projectId, JsonNode bomJson,
            String projectName, String projectVersion) throws RelizaException {
        Optional<DTrackIntegration> integration = getDTrackIntegration(orgUuid);
        if (integration.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
        UploadableBom ub = new UploadableBom(bomJson, null, false);
        DependencyTrackUploadResult res = uploadBomToProject(
            integration.get(), projectId, ub, projectName, projectVersion);
        return res == null ? null : res.token();
    }

    /** True while DTrack is still processing the given upload token. */
    public boolean syntheticIsTokenProcessing(UUID orgUuid, String token) throws RelizaException {
        Optional<IntegrationData> oid = integrationService.getIntegrationDataByOrgTypeIdentifier(
            orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
        if (oid.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
        String apiToken = encryptionService.decrypt(oid.get().getSecret());
        URI eventTokenUri = URI.create(oid.get().getUri() + "/api/v1/event/token/" + token);
        var resp = dtrackWebClient.get().uri(eventTokenUri).header("X-API-Key", apiToken)
            .retrieve().toEntity(String.class).block();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> respMap = Utils.OM.readValue(resp.getBody(), Map.class);
            return Boolean.TRUE.equals(respMap.get("processing"));
        } catch (Exception e) {
            throw new RelizaException("Error checking DTrack processing status: " + e.getMessage());
        }
    }

    /** Fetch the vulnerabilities + violations DTrack computed for a synthetic project. */
    public SyntheticFindings syntheticFetchFindings(UUID orgUuid, UUID projectId) throws RelizaException {
        Optional<IntegrationData> oid = integrationService.getIntegrationDataByOrgTypeIdentifier(
            orgUuid, IntegrationType.DEPENDENCYTRACK, CommonVariables.BASE_INTEGRATION_IDENTIFIER);
        if (oid.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
        String apiToken = encryptionService.decrypt(oid.get().getSecret());
        URI baseUri = oid.get().getUri();
        try {
            List<IntegrationService.VulnWithCpe> vulns = integrationService.fetchDependencyTrackVulnerabilityDetailsWithCpe(
                baseUri, apiToken, projectId.toString(), null, orgUuid, null);
            List<IntegrationService.ViolationWithCpe> violations = integrationService.fetchDependencyTrackViolationDetailsWithCpe(
                baseUri, apiToken, projectId.toString(), null, orgUuid, null);
            return new SyntheticFindings(vulns, violations);
        } catch (Exception e) {
            throw new RelizaException("Error fetching synthetic findings: " + e.getMessage());
        }
    }

    /** Vulnerabilities + violations fetched for one synthetic project, each paired with its CPE. */
    public record SyntheticFindings(List<IntegrationService.VulnWithCpe> vulns,
            List<IntegrationService.ViolationWithCpe> violations) {}

    // ========================================
    // HELPER RECORDS & CLASSES
    // ========================================
    
    /**
     * DTrack integration data with decrypted token.
     */
    private record DTrackIntegration(
        IntegrationData integrationData,
        String apiToken) {}
    
    /**
     * DTrack project input for creation.
     */
    private record DependencyTrackProjectInput(
        String name, 
        String version, 
        String parent, 
        String classifier, 
        List<Object> accessTeams,
        List<Object> tags, 
        Boolean active, 
        Boolean isLatest) {}
    
    /**
     * DTrack BOM upload payload.
     */
    private record DependencyTrackBomPayload(
        UUID project,
        String bom) {}
}
