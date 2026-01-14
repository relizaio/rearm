/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
import io.reliza.service.DTrackService.DTrackContext;
import io.reliza.service.IntegrationService.DependencyTrackUploadResult;
import lombok.extern.slf4j.Slf4j;

/**
 * BomLifecycleService coordinates the complete lifecycle of BOM artifacts:
 * 1. Storage in rebom-backend
 * 2. Deduplication detection
 * 3. DTrack integration
 * 
 * This service follows the same service extraction pattern as rebom-backend's refactoring,
 * providing a single entry point for BOM processing while delegating to specialized services.
 */
@Slf4j
@Service
public class BomLifecycleService {

    @Autowired
    private RebomService rebomService;
    
    @Autowired
    private DTrackService dtrackService;
    
    @Autowired
    private SharedArtifactService sharedArtifactService;

    /**
     * Result of BOM lifecycle processing
     */
    public record BomLifecycleResult(
        RebomResponse rebomResponse,
        DependencyTrackUploadResult dtrackResult,
        Optional<ArtifactData> deduplicatedArtifact,
        boolean wasDeduplicatedBom
    ) {
        public BomLifecycleResult(
            RebomResponse rebomResponse,
            DependencyTrackUploadResult dtrackResult,
            Optional<ArtifactData> deduplicatedArtifact
        ) {
            this(rebomResponse, dtrackResult, deduplicatedArtifact, deduplicatedArtifact.isPresent());
        }
    }

    /**
     * Process complete BOM lifecycle: storage → deduplication → DTrack integration
     * 
     * @param bomJson Raw BOM JSON content
     * @param rebomOptions Options for rebom processing
     * @param bomFormat BOM format (CYCLONEDX or SPDX)
     * @param orgUuid Organization UUID
     * @param artifactUuid UUID of the artifact being processed
     * @param existingArtifact Existing artifact data (for updates)
     * @param existingSerialNumber Existing serial number (for SPDX versioning)
     * @return BomLifecycleResult containing all processing results
     * @throws RelizaException if any step fails
     */
    public BomLifecycleResult processBomArtifact(
        JsonNode bomJson,
        RebomOptions rebomOptions,
        BomFormat bomFormat,
        UUID orgUuid,
        UUID artifactUuid,
        ArtifactData existingArtifact,
        UUID existingSerialNumber
    ) throws RelizaException {
        
        log.debug("Starting BOM lifecycle processing for artifact {}, format: {}", 
            artifactUuid, bomFormat);
        
        // Step 1: Store BOM in rebom-backend
        RebomResponse rebomResponse = rebomService.uploadSbom(
            bomJson, 
            rebomOptions, 
            bomFormat, 
            orgUuid, 
            existingSerialNumber
        );
        
        String bomDigest = rebomResponse.meta().bomDigest();
        UUID internalBomId = extractInternalBomId(rebomResponse);
        
        log.debug("BOM stored in rebom-backend. Internal BOM ID: {}, Digest: {}", 
            internalBomId, bomDigest);
        
        // Step 2: Check for deduplication using bomDigest
        Optional<ArtifactData> deduplicatedArtifact = findDeduplicatedArtifact(
            bomDigest, 
            orgUuid, 
            artifactUuid
        );
        
        if (deduplicatedArtifact.isPresent()) {
            log.info("Found deduplicated artifact {} for digest {}", 
                deduplicatedArtifact.get().getUuid(), bomDigest);
        }
        
        // Step 3: Integrate with DTrack (if BOM artifact)
        DependencyTrackUploadResult dtrackResult = integratWithDTrack(
            bomJson,
            bomDigest,
            bomFormat,
            internalBomId,
            orgUuid,
            artifactUuid,
            rebomOptions,
            existingArtifact,
            deduplicatedArtifact
        );
        
        log.debug("BOM lifecycle processing complete for artifact {}", artifactUuid);
        
        return new BomLifecycleResult(rebomResponse, dtrackResult, deduplicatedArtifact);
    }

    /**
     * Find deduplicated artifact by bomDigest
     */
    private Optional<ArtifactData> findDeduplicatedArtifact(
        String bomDigest, 
        UUID orgUuid,
        UUID currentArtifactUuid
    ) {
        if (bomDigest == null || bomDigest.isEmpty()) {
            log.warn("BOM digest is empty - cannot check for deduplication");
            return Optional.empty();
        }
        
        try {
            Optional<Artifact> found = sharedArtifactService.findArtifactByStoredDigest(
                orgUuid, 
                bomDigest
            );
            
            // Convert to ArtifactData and check if it's not the current artifact
            if (found.isPresent()) {
                ArtifactData foundData = ArtifactData.dataFromRecord(found.get());
                if (foundData.getUuid().equals(currentArtifactUuid)) {
                    log.debug("Found artifact is the current artifact - not a duplicate");
                    return Optional.empty();
                }
                return Optional.of(foundData);
            }
            
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error checking for deduplicated artifact: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Integrate BOM with DTrack
     */
    private DependencyTrackUploadResult integratWithDTrack(
        JsonNode bomJson,
        String bomDigest,
        BomFormat bomFormat,
        UUID internalBomId,
        UUID orgUuid,
        UUID artifactUuid,
        RebomOptions rebomOptions,
        ArtifactData existingArtifact,
        Optional<ArtifactData> deduplicatedArtifact
    ) {
        try {
            DTrackContext context = new DTrackContext(
                orgUuid,
                bomDigest,
                bomJson,
                bomFormat,
                internalBomId.toString(),
                rebomOptions,
                artifactUuid,
                existingArtifact
            );
            
            DependencyTrackUploadResult result = dtrackService.handleDTrackIntegration(
                context, 
                deduplicatedArtifact
            );
            
            log.debug("DTrack integration complete for artifact {}", artifactUuid);
            return result;
            
        } catch (Exception e) {
            log.error("DTrack integration failed for artifact {}: {}", 
                artifactUuid, e.getMessage(), e);
            // Don't fail the entire BOM processing if DTrack integration fails
            return null;
        }
    }

    /**
     * Extract internal BOM ID from rebom response
     */
    private UUID extractInternalBomId(RebomResponse rebomResponse) throws RelizaException {
        try {
            String serialNumber = rebomResponse.meta().serialNumber();
            if (serialNumber == null || serialNumber.isEmpty()) {
                throw new RelizaException("Rebom response missing serialNumber");
            }
            
            // Remove urn:uuid: prefix if present
            String uuidString = serialNumber.replace("urn:uuid:", "");
            return UUID.fromString(uuidString);
            
        } catch (IllegalArgumentException e) {
            throw new RelizaException("Invalid UUID in rebom response serialNumber: " + 
                rebomResponse.meta().serialNumber());
        }
    }
}
