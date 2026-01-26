package io.reliza.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.service.IntegrationService.DependencyTrackUploadResult;
import io.reliza.common.Utils.ArtifactBelongsTo;

/**
 * Unit tests for ArtifactService BOM processing methods.
 * Tests the refactored storeArtifactOnRebom helper methods.
 */
@ExtendWith(MockitoExtension.class)
public class ArtifactServiceBomProcessingTest {

    @Mock
    private ArtifactRepository artifactRepository;
    
    @Mock
    private BomLifecycleService bomLifecycleService;
    
    @InjectMocks
    private ArtifactService artifactService;
    
    private ObjectMapper objectMapper;
    private UUID testOrgUuid;
    private UUID testArtifactUuid;
    private UUID testBomUuid;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        testOrgUuid = UUID.randomUUID();
        testArtifactUuid = UUID.randomUUID();
        testBomUuid = UUID.randomUUID();
    }
    
    // ==================== validateCycloneDxUpdate Tests ====================
    
    @Test
    void testValidateCycloneDxUpdate_NewArtifact_SetsVersion() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        ObjectNode bomJson = objectMapper.createObjectNode();
        bomJson.put("version", "1");
        bomJson.put("serialNumber", "urn:uuid:" + testBomUuid.toString());
        
        // Act
        ReflectionTestUtils.invokeMethod(artifactService, "validateCycloneDxUpdate", 
            artifactDto, bomJson, null);
        
        // Assert
        assertEquals("1", artifactDto.getVersion());
    }
    
    @Test
    void testValidateCycloneDxUpdate_SameSerialIncrementedVersion_Succeeds() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        ArtifactData existingAd = new ArtifactData();
        existingAd.setOrg(testOrgUuid);
        existingAd.setInternalBom(new InternalBom(testBomUuid, ArtifactBelongsTo.RELEASE));
        existingAd.setVersion("1");
        
        ObjectNode bomJson = objectMapper.createObjectNode();
        bomJson.put("version", "2");
        bomJson.put("serialNumber", "urn:uuid:" + testBomUuid.toString());
        
        // Mock getArtifactBomLatestVersion to return "1"
        ArtifactService spyService = spy(artifactService);
        doReturn("1").when(spyService).getArtifactBomLatestVersion(testBomUuid, testOrgUuid);
        
        // Act
        ReflectionTestUtils.invokeMethod(spyService, "validateCycloneDxUpdate", 
            artifactDto, bomJson, existingAd);
        
        // Assert
        assertEquals("2", artifactDto.getVersion());
        assertEquals(testArtifactUuid, artifactDto.getUuid()); // UUID unchanged
    }
    
    @Test
    void testValidateCycloneDxUpdate_SameSerialSameVersion_ThrowsException() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        ArtifactData existingAd = new ArtifactData();
        existingAd.setOrg(testOrgUuid);
        existingAd.setInternalBom(new InternalBom(testBomUuid, ArtifactBelongsTo.RELEASE));
        existingAd.setVersion("2");
        
        ObjectNode bomJson = objectMapper.createObjectNode();
        bomJson.put("version", "2");
        bomJson.put("serialNumber", "urn:uuid:" + testBomUuid.toString());
        
        ArtifactService spyService = spy(artifactService);
        doReturn("2").when(spyService).getArtifactBomLatestVersion(testBomUuid, testOrgUuid);
        
        // Act & Assert
        Exception exception = assertThrows(Exception.class, () -> {
            ReflectionTestUtils.invokeMethod(spyService, "validateCycloneDxUpdate", 
                artifactDto, bomJson, existingAd);
        });
        
        // ReflectionTestUtils wraps exceptions - find the RelizaException in the chain
        Throwable current = exception;
        boolean found = false;
        while (current != null) {
            if (current instanceof RelizaException) {
                assertTrue(current.getMessage().contains("incremented version"));
                found = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(found, "Expected RelizaException in exception chain");
    }
    
    @Test
    void testValidateCycloneDxUpdate_DifferentSerial_GeneratesNewUuid() throws Exception {
        // Arrange
        UUID originalUuid = testArtifactUuid;
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(originalUuid)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        UUID differentBomUuid = UUID.randomUUID();
        ArtifactData existingAd = new ArtifactData();
        existingAd.setOrg(testOrgUuid);
        existingAd.setInternalBom(new InternalBom(testBomUuid, ArtifactBelongsTo.RELEASE));
        existingAd.setVersion("1");
        
        ObjectNode bomJson = objectMapper.createObjectNode();
        bomJson.put("version", "1");
        bomJson.put("serialNumber", "urn:uuid:" + differentBomUuid.toString());
        
        ArtifactService spyService = spy(artifactService);
        doReturn("1").when(spyService).getArtifactBomLatestVersion(testBomUuid, testOrgUuid);
        
        // Act
        ReflectionTestUtils.invokeMethod(spyService, "validateCycloneDxUpdate", 
            artifactDto, bomJson, existingAd);
        
        // Assert
        assertNotEquals(originalUuid, artifactDto.getUuid()); // UUID changed
    }
    
    @Test
    void testValidateCycloneDxUpdate_SerialWithoutUrnPrefix_HandlesCorrectly() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        ArtifactData existingAd = new ArtifactData();
        existingAd.setOrg(testOrgUuid);
        existingAd.setInternalBom(new InternalBom(testBomUuid, ArtifactBelongsTo.RELEASE));
        existingAd.setVersion("1");
        
        ObjectNode bomJson = objectMapper.createObjectNode();
        bomJson.put("version", "2");
        bomJson.put("serialNumber", testBomUuid.toString()); // No urn:uuid: prefix
        
        ArtifactService spyService = spy(artifactService);
        doReturn("1").when(spyService).getArtifactBomLatestVersion(testBomUuid, testOrgUuid);
        
        // Act
        ReflectionTestUtils.invokeMethod(spyService, "validateCycloneDxUpdate", 
            artifactDto, bomJson, existingAd);
        
        // Assert
        assertEquals("2", artifactDto.getVersion());
        assertEquals(testArtifactUuid, artifactDto.getUuid()); // UUID unchanged
    }
    
    // ==================== prepareSpdxUpdate Tests ====================
    
    @Test
    void testPrepareSpdxUpdate_WithExistingArtifact_ReturnsSerialNumber() throws Exception {
        // Arrange
        ArtifactData existingAd = new ArtifactData();
        existingAd.setInternalBom(new InternalBom(testBomUuid, ArtifactBelongsTo.RELEASE));
        
        // Act
        UUID result = ReflectionTestUtils.invokeMethod(artifactService, "prepareSpdxUpdate", existingAd);
        
        // Assert
        assertEquals(testBomUuid, result);
    }
    
    @Test
    void testPrepareSpdxUpdate_WithoutExistingArtifact_ReturnsNull() throws Exception {
        // Act
        UUID result = ReflectionTestUtils.invokeMethod(artifactService, "prepareSpdxUpdate", (ArtifactData) null);
        
        // Assert
        assertNull(result);
    }
    
    @Test
    void testPrepareSpdxUpdate_WithExistingArtifactButNoInternalBom_ReturnsNull() throws Exception {
        // Arrange
        ArtifactData existingAd = new ArtifactData();
        existingAd.setInternalBom(null);
        
        // Act
        UUID result = ReflectionTestUtils.invokeMethod(artifactService, "prepareSpdxUpdate", existingAd);
        
        // Assert
        assertNull(result);
    }
    
    // ==================== extractInternalBomId Tests ====================
    
    @Test
    void testExtractInternalBomId_WithUrnPrefix_ExtractsCorrectly() throws Exception {
        // Arrange
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), null, null, null, null, null, null, null
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        // Act
        UUID result = ReflectionTestUtils.invokeMethod(artifactService, "extractInternalBomId", rebomResponse);
        
        // Assert
        assertEquals(testBomUuid, result);
    }
    
    @Test
    void testExtractInternalBomId_WithoutUrnPrefix_ExtractsCorrectly() throws Exception {
        // Arrange
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            testBomUuid.toString(), null, null, null, null, null, null, null
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        // Act
        UUID result = ReflectionTestUtils.invokeMethod(artifactService, "extractInternalBomId", rebomResponse);
        
        // Assert
        assertEquals(testBomUuid, result);
    }
    
    @Test
    void testExtractInternalBomId_WithInvalidUuid_ThrowsException() throws Exception {
        // Arrange
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "invalid-uuid", null, null, null, null, null, null, null
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        // Act & Assert
        Exception exception = assertThrows(Exception.class, () -> {
            ReflectionTestUtils.invokeMethod(artifactService, "extractInternalBomId", rebomResponse);
        });
        
        // ReflectionTestUtils wraps exceptions - find the RelizaException in the chain
        Throwable current = exception;
        boolean found = false;
        while (current != null) {
            if (current instanceof RelizaException) {
                assertTrue(current.getMessage().contains("serialNumber"));
                found = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(found, "Expected RelizaException in exception chain");
    }
    
    // ==================== applyCycloneDxResponse Tests ====================
    
    @Test
    void testApplyCycloneDxResponse_ExtractsDigestsCorrectly() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        String bomDigest = "abc123def456";
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), bomDigest, null, null, null, null, null, null
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        BomLifecycleService.BomLifecycleResult lifecycleResult = 
            new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false);
        
        RebomOptions rebomOptions = new RebomOptions();
        
        // Act
        ReflectionTestUtils.invokeMethod(artifactService, "applyCycloneDxResponse",
            artifactDto, rebomResponse, lifecycleResult, rebomOptions);
        
        // Assert
        assertNotNull(artifactDto.getDigestRecords());
        assertEquals(1, artifactDto.getDigestRecords().size());
        
        DigestRecord digestRecord = artifactDto.getDigestRecords().iterator().next();
        assertEquals(bomDigest, digestRecord.digest());
        assertEquals(DigestScope.REARM, digestRecord.scope());
    }
    
    @Test
    void testApplyCycloneDxResponse_SetsInternalBom() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), "digest123", null, null, null, null, null, null
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        BomLifecycleService.BomLifecycleResult lifecycleResult = 
            new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false);
        
        RebomOptions rebomOptions = new RebomOptions();
        
        // Act
        ReflectionTestUtils.invokeMethod(artifactService, "applyCycloneDxResponse",
            artifactDto, rebomResponse, lifecycleResult, rebomOptions);
        
        // Assert
        assertNotNull(artifactDto.getInternalBom());
        assertEquals(testBomUuid, artifactDto.getInternalBom().id());
    }
    
    @Test
    void testApplyCycloneDxResponse_GeneratesUuidIfMissing() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(null) // No UUID
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), "digest123", null, null, null, null, null, null
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        BomLifecycleService.BomLifecycleResult lifecycleResult = 
            new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false);
        
        RebomOptions rebomOptions = new RebomOptions();
        
        // Act
        ReflectionTestUtils.invokeMethod(artifactService, "applyCycloneDxResponse",
            artifactDto, rebomResponse, lifecycleResult, rebomOptions);
        
        // Assert
        assertNotNull(artifactDto.getUuid());
    }
    
    @Test
    void testApplyCycloneDxResponse_HandlesDTrackSuccess() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.CYCLONEDX)
            .build();
        
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), "digest123", null, null, null, null, null, null
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        BomLifecycleService.BomLifecycleResult lifecycleResult = 
            new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false);
        
        RebomOptions rebomOptions = new RebomOptions();
        
        // Act
        ReflectionTestUtils.invokeMethod(artifactService, "applyCycloneDxResponse",
            artifactDto, rebomResponse, lifecycleResult, rebomOptions);
        
        // Assert - DTrack integration is now async, so no dtur set here
        // The artifact should still be processed successfully
        assertNotNull(artifactDto.getInternalBom());
    }
    
    // ==================== applySpdxResponse Tests ====================
    
    @Test
    void testApplySpdxResponse_ExtractsVersion() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.SPDX)
            .build();
        
        String bomVersion = "2";
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), "digest123", null, null, null, null, null, bomVersion
        );
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, null, meta, false);
        
        BomLifecycleService.BomLifecycleResult lifecycleResult = 
            new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false);
        
        RebomOptions rebomOptions = new RebomOptions();
        
        // Act
        ReflectionTestUtils.invokeMethod(artifactService, "applySpdxResponse",
            artifactDto, rebomResponse, lifecycleResult, rebomOptions);
        
        // Assert
        assertEquals(bomVersion, artifactDto.getVersion());
    }
    
    @Test
    void testApplySpdxResponse_ExtractsOriginalFileDigest() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.SPDX)
            .build();
        
        String bomDigest = "rearm-digest-123";
        String originalFileDigest = "original-digest-456";
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), bomDigest, originalFileDigest, 
            1024L, "application/spdx+json", null, null, "1"
        );
        
        // Mock OASResponseDto
        io.reliza.model.dto.OASResponseDto mockOasResponse = mock(io.reliza.model.dto.OASResponseDto.class);
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, mockOasResponse, meta, false);
        
        BomLifecycleService.BomLifecycleResult lifecycleResult = 
            new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false);
        
        RebomOptions rebomOptions = new RebomOptions();
        
        // Act
        ReflectionTestUtils.invokeMethod(artifactService, "applySpdxResponse",
            artifactDto, rebomResponse, lifecycleResult, rebomOptions);
        
        // Assert
        assertNotNull(artifactDto.getDigestRecords());
        assertEquals(2, artifactDto.getDigestRecords().size());
        
        boolean hasRearmDigest = false;
        boolean hasOriginalDigest = false;
        
        for (DigestRecord dr : artifactDto.getDigestRecords()) {
            if (dr.scope() == DigestScope.REARM && dr.digest().equals(bomDigest)) {
                hasRearmDigest = true;
            }
            if (dr.scope() == DigestScope.ORIGINAL_FILE && dr.digest().equals(originalFileDigest)) {
                hasOriginalDigest = true;
            }
        }
        
        assertTrue(hasRearmDigest, "Should have REARM digest");
        assertTrue(hasOriginalDigest, "Should have ORIGINAL_FILE digest");
    }
    
    @Test
    void testApplySpdxResponse_SetsOriginalFileMetadata() throws Exception {
        // Arrange
        ArtifactDto artifactDto = ArtifactDto.builder()
            .uuid(testArtifactUuid)
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.SPDX)
            .build();
        
        Long originalSize = 2048L;
        String originalMediaType = "application/spdx+json";
        String originalDigest = "original-digest-789";
        
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + testBomUuid.toString(), "digest123", originalDigest, 
            originalSize, originalMediaType, null, null, "1"
        );
        
        // Mock OASResponseDto
        io.reliza.model.dto.OASResponseDto mockOasResponse = mock(io.reliza.model.dto.OASResponseDto.class);
        RebomResponse rebomResponse = new RebomResponse(testBomUuid, mockOasResponse, meta, false);
        
        BomLifecycleService.BomLifecycleResult lifecycleResult = 
            new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false);
        
        RebomOptions rebomOptions = new RebomOptions();
        
        // Act
        io.reliza.model.dto.OASResponseDto result = ReflectionTestUtils.invokeMethod(
            artifactService, "applySpdxResponse",
            artifactDto, rebomResponse, lifecycleResult, rebomOptions);
        
        // Assert
        verify(mockOasResponse).setOriginalSize(originalSize);
        verify(mockOasResponse).setOriginalMediaType(originalMediaType);
        verify(mockOasResponse).setFileSHA256Digest(originalDigest);
    }
}
