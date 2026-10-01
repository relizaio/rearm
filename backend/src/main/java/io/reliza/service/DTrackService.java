package io.reliza.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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
                baseUri, apiToken, projectId, null, orgUuid, null, oid.get().getEffectiveDtrackVersion());
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
                baseUri, apiToken, projectId.toString(), null, orgUuid, null, oid.get().getEffectiveDtrackVersion());
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
    // LATEST VERSION (repository metadata)
    // ========================================

    /** Page size for the component listing: DT 5 pages lists, and 100 is served on both versions. */
    static final int REPOSITORY_META_PAGE_SIZE = 100;

    /** How long one repository-metadata request may take before it counts as unanswered. */
    static final Duration REPOSITORY_META_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The most pages one project listing reads: a synthetic bucket holds 500 components (5
     * pages), so this only stops a server that ignores paging from looping forever.
     */
    static final int REPOSITORY_META_MAX_PAGES = 50;

    /**
     * Dependency-Track answered a repository-metadata request with a 4xx other than a
     * package it does not know: the request itself is refused (a project that no longer
     * exists, a purl its parser rejects). Retrying later gets the same answer, so a caller
     * moves on to the next project or component rather than backing off the whole org.
     */
    public static class RepositoryMetaRejectedException extends RelizaException {
        private static final long serialVersionUID = 1L;

        public RepositoryMetaRejectedException(String message) {
            super(message);
        }
    }

    /**
     * Subset of Dependency-Track's {@code RepositoryMetaComponent}: the latest version its
     * repository analyzer found for a package (keyed by type, namespace and name, not by
     * version). Verified live on DT 5.1.0 and DT 4.14.2, which differ only in the date of the
     * latest release ({@code latestVersionPublishedAt} on 5, {@code published} on 4), not bound.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DtrackRepositoryMetaRaw(String latestVersion) {}

    /** A component of a project listing, as far as its latest version goes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DtrackComponentMetaRaw(String purl, DtrackRepositoryMetaRaw repositoryMeta) {}

    /** A component of a Dependency-Track project and the latest version of its package; null when none is known. */
    public record ComponentLatestVersion(String purl, String latestVersion) {}

    /**
     * The latest version of every component of a synthetic bucket project, from the
     * {@code repositoryMeta} that {@code GET /api/v1/component/project/{uuid}} carries per
     * component on DT 5 and DT 4 alike: one paged listing instead of one request per
     * component. A component whose purl type has no repository (deb, apk, rpm, generic) or
     * whose package the analyzer has not resolved comes back without a version.
     *
     * @return the components, and how many requests the listing took
     */
    public RepositoryMetaListing syntheticFetchRepositoryMeta(UUID orgUuid, UUID projectId) throws RelizaException {
        Optional<DTrackIntegration> integration = getDTrackIntegration(orgUuid);
        if (integration.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
        List<ComponentLatestVersion> out = new ArrayList<>();
        int requests = 0;
        int total = -1;
        int read = 0;
        for (int page = 1; page <= REPOSITORY_META_MAX_PAGES; page++) {
            URI uri = buildDTrackUri(integration.get(), "/api/v1/component/project/" + projectId
                    + "?pageSize=" + REPOSITORY_META_PAGE_SIZE + "&pageNumber=" + page);
            requests++;
            ResponseEntity<String> resp;
            try {
                resp = getRepositoryMeta(uri, integration.get().apiToken(),
                        "the components of Dependency-Track project " + projectId + " (page " + page + ")");
            } catch (WebClientResponseException notFound) {
                throw new RepositoryMetaRejectedException("Dependency-Track has no project " + projectId);
            }
            List<DtrackComponentMetaRaw> rows;
            try {
                rows = resp == null || resp.getBody() == null ? List.of()
                        : Utils.OM.readValue(resp.getBody(), new TypeReference<List<DtrackComponentMetaRaw>>() {});
            } catch (JacksonException e) {
                throw new RelizaException("Unreadable component listing of Dependency-Track project " + projectId
                        + " (page " + page + "): " + e.getMessage());
            }
            for (DtrackComponentMetaRaw row : rows) {
                if (row == null || row.purl() == null) continue;
                out.add(new ComponentLatestVersion(row.purl(),
                        row.repositoryMeta() == null ? null : StringUtils.trimToNull(row.repositoryMeta().latestVersion())));
            }
            read += rows.size();
            if (total < 0 && resp != null) total = IntegrationService.parseDtrackTotalCountHeader(resp);
            // the total decides where one is sent (a server may serve fewer rows per page
            // than asked); without it, a short page is the last
            boolean done = rows.isEmpty() || (total >= 0 ? read >= total : rows.size() < REPOSITORY_META_PAGE_SIZE);
            if (done) break;
        }
        return new RepositoryMetaListing(List.copyOf(out), requests);
    }

    /**
     * One repository-metadata GET: a 4xx is {@link RepositoryMetaRejectedException}, except a
     * 404, which is returned as a WebClientResponseException for the caller to read; a 5xx, a
     * 429, a timeout or no connection is a plain RelizaException, the instance unavailable.
     */
    private ResponseEntity<String> getRepositoryMeta(URI uri, String apiToken, String what) throws RelizaException {
        try {
            return dtrackWebClient.get().uri(uri).header("X-API-Key", apiToken)
                    .retrieve().toEntity(String.class).timeout(REPOSITORY_META_TIMEOUT).block();
        } catch (WebClientResponseException wcre) {
            HttpStatusCode status = wcre.getStatusCode();
            if (status.is4xxClientError() && !status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) throw wcre;
                throw new RepositoryMetaRejectedException("Dependency-Track refused (" + status.value() + ") reading " + what);
            }
            throw new RelizaException("Dependency-Track returned " + status.value() + " reading " + what);
        } catch (Exception e) {
            throw new RelizaException("Error reading " + what + ": " + e.getMessage());
        }
    }

    /** The components of a project listing and the number of requests it cost. */
    public record RepositoryMetaListing(List<ComponentLatestVersion> components, int requests) {}

    /** What {@code GET /api/v1/repository/latest} answered for a purl. */
    public enum LatestVersionAnswer {
        /** 200: the package's latest version. */
        FOUND,
        /** 204: Dependency-Track has no repository for the purl's type (deb, apk, rpm, generic). */
        NO_REPOSITORY_FOR_TYPE,
        /**
         * 404: a type it has repositories for, but no metadata for the package. It never
         * fetches on demand, so a package outside its portfolio stays unknown.
         */
        UNKNOWN_PACKAGE
    }

    /** One {@code repository/latest} answer; {@code latestVersion} is set for FOUND only. */
    public record LatestVersionLookup(LatestVersionAnswer answer, String latestVersion) {}

    /**
     * The latest version of one purl's package: {@code GET /api/v1/repository/latest?purl=},
     * the same on DT 5 and DT 4 (verified live on 5.1.0 and 4.14.2; the version in the purl
     * is ignored). For components that are in no synthetic bucket; bucketed ones are read in
     * bulk by {@link #syntheticFetchRepositoryMeta}. The purl travels as an encoded query
     * parameter inside a {@link URI}, so WebClient does not re-encode it.
     */
    public LatestVersionLookup fetchLatestVersion(UUID orgUuid, String purl) throws RelizaException {
        Optional<DTrackIntegration> integration = getDTrackIntegration(orgUuid);
        if (integration.isEmpty()) throw new RelizaException("DependencyTrack integration not configured");
        URI uri = buildDTrackUri(integration.get(),
                "/api/v1/repository/latest?purl=" + URLEncoder.encode(purl, StandardCharsets.UTF_8));
        ResponseEntity<String> resp;
        try {
            resp = getRepositoryMeta(uri, integration.get().apiToken(), "the latest version of " + purl);
        } catch (WebClientResponseException notFound) {
            return new LatestVersionLookup(LatestVersionAnswer.UNKNOWN_PACKAGE, null);
        }
        // only a 204 means no repository for the type: an empty 200 is not one
        if (resp != null && resp.getStatusCode().isSameCodeAs(HttpStatus.NO_CONTENT)) {
            return new LatestVersionLookup(LatestVersionAnswer.NO_REPOSITORY_FOR_TYPE, null);
        }
        String latest = null;
        if (resp != null && StringUtils.isNotBlank(resp.getBody())) {
            try {
                latest = StringUtils.trimToNull(Utils.OM.readValue(resp.getBody(), DtrackRepositoryMetaRaw.class).latestVersion());
            } catch (JacksonException e) {
                throw new RelizaException("Unreadable latest version of " + purl + ": " + e.getMessage());
            }
        }
        return latest == null ? new LatestVersionLookup(LatestVersionAnswer.UNKNOWN_PACKAGE, null)
                : new LatestVersionLookup(LatestVersionAnswer.FOUND, latest);
    }


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
