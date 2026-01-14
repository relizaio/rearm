package io.reliza.service;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.service.IntegrationService.DependencyTrackUploadResult;
import io.reliza.service.IntegrationService.UploadableBom;
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
    
    // ========================================
    // PUBLIC API - Orchestration Layer
    // ========================================
    
    /**
     * Main entry point for DTrack integration.
     * Handles deduplication, project creation/update, and BOM upload.
     */
    @Transactional
    public DependencyTrackUploadResult handleDTrackIntegration(
            DTrackContext context, 
            Optional<ArtifactData> deduplicatedArtifact) throws RelizaException {
        log.debug("Handling DTrack integration for artifact {}", context.artifactUuid());
        
        Optional<DependencyTrackUploadResult> deduplicatedResult = 
            tryDeduplication(deduplicatedArtifact);
        if (deduplicatedResult.isPresent()) {
            log.info("Artifact {} deduplicated, reusing existing DTrack project", context.artifactUuid());
            return deduplicatedResult.get();
        }
        
        if (context.hasExistingDTrackProject()) {
            log.info("Updating existing DTrack project for artifact {}", context.artifactUuid());
            return updateExistingProject(context);
        }
        
        log.info("Creating new DTrack project for artifact {}", context.artifactUuid());
        return createNewProject(context);
    }
    
    /**
     * Refresh metrics for existing DTrack project.
     * Returns true if refresh was successful or resubmission was triggered.
     */
    @Transactional
    public boolean requestMetricsRefresh(ArtifactData artifact) {
        boolean isRequested = false;
        Optional<DTrackIntegration> integration = getDTrackIntegration(artifact.getOrg());
        
        if (integration.isEmpty() || artifact.getType() != ArtifactType.BOM) {
            return isRequested;
        }
        
        String depTrackProject = (null != artifact.getMetrics()) 
            ? artifact.getMetrics().getDependencyTrackProject() : null;
        
        if (StringUtils.isEmpty(depTrackProject)) {
            isRequested = resubmitArtifact(artifact);
        } else {
            isRequested = refreshExistingProject(integration.get(), artifact, depTrackProject);
        }
        return isRequested;
    }
    
    /**
     * Resubmit artifact to DTrack (for self-healing when project is deleted).
     */
    public boolean resubmitArtifact(ArtifactData artifact) {
        try {
            log.info("Resubmitting artifact to DTrack: uuid={}, bomFormat={}, version={}", 
                artifact.getUuid(), artifact.getBomFormat(), artifact.getVersion());
            
            var downloadable = sharedArtifactService.downloadArtifact(artifact);
            byte[] bomContent = downloadable.block().getBody();
            
            log.info("Downloaded BOM for resubmission: size={} bytes, first 100 chars: {}", 
                bomContent.length, 
                new String(bomContent, 0, Math.min(100, bomContent.length)));
            
            String projectVersion = (null != artifact.getMetrics() && 
                StringUtils.isNotEmpty(artifact.getMetrics().getProjectVersion())) 
                ? artifact.getMetrics().getProjectVersion() : UUID.randomUUID().toString();
            String projectName = (null != artifact.getMetrics() && 
                StringUtils.isNotEmpty(artifact.getMetrics().getProjectName())) 
                ? artifact.getMetrics().getProjectName() : UUID.randomUUID().toString();
            
            String oldProjectId = (null != artifact.getMetrics() && 
                StringUtils.isNotEmpty(artifact.getMetrics().getDependencyTrackProject())) 
                ? artifact.getMetrics().getDependencyTrackProject() : null;
            
            log.info("Resubmitting to DTrack project: name={}, version={}, oldProjectId={}", 
                projectName, projectVersion, oldProjectId);
            
            UploadableBom ub = new UploadableBom(null, bomContent, true);
            uploadBomAndUpdateRelated(artifact.getOrg(), ub, projectName, projectVersion, 
                oldProjectId, artifact);
            
            log.info("Successfully resubmitted artifact {} to DTrack", artifact.getUuid());
            return true;
        } catch (Exception e) {
            log.error("Error on resubmitting artifact to dependency track, artifact id = " 
                + artifact.getUuid(), e);
            return false;
        }
    }
    
    /**
     * Upload BOM to DTrack and update related artifacts if project ID changes.
     * Handles both initial upload and resubmission scenarios.
     */
    public DependencyTrackUploadResult uploadBomAndUpdateRelated(
            UUID orgUuid,
            UploadableBom bom,
            String projectName,
            String projectVersion,
            String oldProjectId,
            ArtifactData updateArtifact) throws RelizaException {
        
        DependencyTrackUploadResult dtur = sendBomToDependencyTrack(
            orgUuid, bom, projectName, projectVersion);
        
        if (updateArtifact != null) {
            sharedArtifactService.updateArtifactFromDtur(updateArtifact, dtur);
        }
        
        if (oldProjectId != null && dtur != null && dtur.projectId() != null && 
                !oldProjectId.equals(dtur.projectId())) {
            
            log.info("DTrack project ID changed from {} to {}, updating related artifacts", 
                oldProjectId, dtur.projectId());
            
            List<Artifact> relatedArtifacts = artifactService.listArtifactsByDtrackProjects(
                List.of(UUID.fromString(oldProjectId)));
            
            for (Artifact artifact : relatedArtifacts) {
                try {
                    ArtifactData relatedAd = ArtifactData.dataFromRecord(artifact);
                    if (relatedAd.getOrg().equals(orgUuid) && 
                            (updateArtifact == null || !relatedAd.getUuid().equals(updateArtifact.getUuid()))) {
                        sharedArtifactService.updateArtifactFromDtur(relatedAd, dtur);
                        log.debug("Updated artifact {} with new DTrack project {}", 
                            relatedAd.getUuid(), dtur.projectId());
                    }
                } catch (Exception e) {
                    log.error("Failed to update related artifact: {}", e.getMessage());
                }
            }
        }
        
        return dtur;
    }
    
    /**
     * Upload BOM to existing DTrack project.
     */
    public DependencyTrackUploadResult uploadBomToExistingProject(
            UUID orgUuid, 
            UploadableBom bom, 
            UUID existingProjectId, 
            String projectName, 
            String projectVersion) throws RelizaException {
        
        Optional<DTrackIntegration> integration = getDTrackIntegration(orgUuid);
        if (integration.isEmpty()) {
            return null;
        }
        
        return uploadBomToProject(integration.get(), existingProjectId, bom, 
            projectName, projectVersion);
    }
    
    // ========================================
    // DEDUPLICATION & ORCHESTRATION
    // ========================================
    
    private Optional<DependencyTrackUploadResult> tryDeduplication(
            Optional<ArtifactData> deduplicatedArtifact) {
        if (deduplicatedArtifact.isEmpty()) {
            return Optional.empty();
        }
        
        ArtifactData existingAd = deduplicatedArtifact.get();
        
        if (isDTrackProjectDeleted(existingAd)) {
            log.info("Existing artifact {} has deleted DTrack project, skipping deduplication", 
                existingAd.getUuid());
            return Optional.empty();
        }
        
        boolean refreshed = requestMetricsRefresh(existingAd);
        
        if (!refreshed) {
            var reloaded = sharedArtifactService.getArtifact(existingAd.getUuid());
            if (reloaded.isPresent()) {
                existingAd = ArtifactData.dataFromRecord(reloaded.get());
            } else {
                return Optional.empty();
            }
        }
        
        if (existingAd.getMetrics() == null) {
            return Optional.empty();
        }
        
        return Optional.of(new DependencyTrackUploadResult(
            existingAd.getMetrics().getDependencyTrackProject(),
            existingAd.getMetrics().getUploadToken(),
            existingAd.getMetrics().getProjectName(),
            existingAd.getMetrics().getProjectVersion(),
            existingAd.getMetrics().getDependencyTrackFullUri()));
    }
    
    private boolean isDTrackProjectDeleted(ArtifactData artifact) {
        if (artifact.getMetrics() == null) {
            return false;
        }
        Boolean deleted = artifact.getMetrics().getDtrackProjectDeleted();
        return deleted != null && deleted;
    }
    
    private boolean refreshExistingProject(
            DTrackIntegration integration, 
            ArtifactData artifact, 
            String projectId) {
        try {
            URI refreshUri = buildDTrackUri(integration, 
                "/api/v1/metrics/project/" + projectId + "/refresh");
            var response = dtrackWebClient
                .get()
                .uri(refreshUri)
                .header("X-API-Key", integration.apiToken())
                .retrieve()
                .toEntity(String.class)
                .block();
            if (response.getStatusCode() == HttpStatus.OK) return true;
            else log.warn(response.toString());
        } catch (WebClientResponseException wcre) {
            if (wcre.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404)) && 
                    null != artifact.getMetrics()) {
                return resubmitArtifact(artifact);
            } else {
                log.error("Web exception processing status of artifact on dependency track with id = " 
                    + artifact.getUuid(), wcre);
            }
        } catch (Exception e) {
            log.error("Exception processing status of artifact on dependency track with id = " 
                + artifact.getUuid(), e);
        }
        return false;
    }
    
    // ========================================
    // PROJECT MANAGEMENT
    // ========================================
    
    private DependencyTrackUploadResult updateExistingProject(DTrackContext context) 
            throws RelizaException {
        requestMetricsRefresh(context.existingArtifact());
        
        var reloaded = sharedArtifactService.getArtifact(context.existingArtifact().getUuid());
        if (reloaded.isEmpty()) {
            throw new RelizaException("Failed to reload artifact after metrics refresh");
        }
        
        ArtifactData refreshedAd = ArtifactData.dataFromRecord(reloaded.get());
        JsonNode bomJson = prepareBomForUpload(context);
        UploadableBom ub = new UploadableBom(bomJson, null, false);
        
        return uploadBomToExistingProject(
            context.orgUuid(), ub,
            UUID.fromString(refreshedAd.getMetrics().getDependencyTrackProject()),
            refreshedAd.getMetrics().getProjectName(),
            refreshedAd.getMetrics().getProjectVersion());
    }
    
    private DependencyTrackUploadResult createNewProject(DTrackContext context) 
            throws RelizaException {
        JsonNode bomJson = prepareBomForUpload(context);
        String projectVersion = context.rebomOptions().version() + "-" + 
            context.artifactUuid().toString();
        UploadableBom ub = new UploadableBom(bomJson, null, false);
        String oldProjectId = context.getExistingDTrackProjectId();
        
        return uploadBomAndUpdateRelated(
            context.orgUuid(), ub, context.rebomOptions().name(), 
            projectVersion, oldProjectId, null);
    }
    
    // ========================================
    // BOM UPLOAD & PROCESSING
    // ========================================
    
    /**
     * Send BOM to DTrack - creates or finds project, then uploads BOM.
     */
    private DependencyTrackUploadResult sendBomToDependencyTrack(
            UUID orgUuid, 
            UploadableBom bom, 
            String projectName, 
            String projectVersion) throws RelizaException {
        
        Optional<DTrackIntegration> integration = getDTrackIntegration(orgUuid);
        if (integration.isEmpty()) {
            return null;
        }
        
        try {
            UUID projectId = getOrCreateProject(integration.get(), projectName, projectVersion);
            return uploadBomToProject(integration.get(), projectId, bom, projectName, projectVersion);
        } catch (Exception e) {
            log.error("Error on uploading bom to dependency track", e);
            throw new RelizaException("Error on uploading bom to dependency track: " + e.getMessage());
        }
    }
    
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
     * Prepare BOM for upload (convert SPDX to CycloneDX if needed).
     */
    private JsonNode prepareBomForUpload(DTrackContext context) throws RelizaException {
        if (context.bomFormat() == BomFormat.SPDX) {
            try {
                return rebomService.findRawBomById(
                    UUID.fromString(context.internalBomId()), context.orgUuid(), BomFormat.CYCLONEDX);
            } catch (JsonProcessingException e) {
                throw new RelizaException("Failed to convert SPDX to CycloneDX: " + e.getMessage());
            }
        }
        return context.bomJson();
    }
    
    /**
     * Encode BOM to Base64 for DTrack API.
     */
    private String encodeBom(UploadableBom bom) throws JsonProcessingException {
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
    // HELPER RECORDS & CLASSES
    // ========================================
    
    /**
     * Context for DTrack integration containing all necessary data.
     */
    public record DTrackContext(
        UUID orgUuid,
        String bomDigest,
        JsonNode bomJson,
        BomFormat bomFormat,
        String internalBomId,
        io.reliza.model.tea.Rebom.RebomOptions rebomOptions,
        UUID artifactUuid,
        ArtifactData existingArtifact) {
        
        /**
         * Check if artifact has an existing DTrack project.
         */
        public boolean hasExistingDTrackProject() {
            return existingArtifact != null && 
                   existingArtifact.getMetrics() != null && 
                   StringUtils.isNotEmpty(existingArtifact.getMetrics().getDependencyTrackProject());
        }
        
        /**
         * Get existing DTrack project ID if available.
         */
        public String getExistingDTrackProjectId() {
            if (hasExistingDTrackProject()) {
                return existingArtifact.getMetrics().getDependencyTrackProject();
            }
            return null;
        }
    }
    
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
