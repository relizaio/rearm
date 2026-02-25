/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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
import lombok.extern.slf4j.Slf4j;

/**
 * BomLifecycleService coordinates the complete lifecycle of BOM artifacts:
 * 1. Storage in rebom-backend
 * 2. Deduplication detection
 * 
 * Note: DTrack integration is now handled asynchronously via the scheduler.
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
    private SharedArtifactService sharedArtifactService;

    /**
     * Result of BOM lifecycle processing
     */
    public record BomLifecycleResult(
        RebomResponse rebomResponse,
        Optional<ArtifactData> deduplicatedArtifact,
        boolean wasDeduplicatedBom
    ) {
        public BomLifecycleResult(
            RebomResponse rebomResponse,
            Optional<ArtifactData> deduplicatedArtifact
        ) {
            this(rebomResponse, deduplicatedArtifact, deduplicatedArtifact.isPresent());
        }
    }

    /**
     * Process complete BOM lifecycle: storage → deduplication
     * Note: DTrack integration is now handled asynchronously via the scheduler.
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
        
        log.debug("BOM lifecycle processing complete for artifact {}", artifactUuid);
        
        return new BomLifecycleResult(rebomResponse, deduplicatedArtifact);
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
