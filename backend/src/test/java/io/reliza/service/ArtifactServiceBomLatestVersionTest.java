/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.service.RebomService.MalformedRebomAnswerException;

/**
 * SCORE-56: {@link ArtifactService#getArtifactBomLatestVersion} reports rebom's
 * {@code meta.bomVersion} (via {@link RebomService#latestStoredBomVersion}), so an SPDX-born SBOM at
 * version 2 answers 2, not the converted document's 1; the stored document is read only as a fallback.
 * Also pins the CycloneDX pre-check {@code validateCycloneDxUpdate} on an SPDX-born serialNumber.
 */
@ExtendWith(MockitoExtension.class)
public class ArtifactServiceBomLatestVersionTest {

    @Mock
    private RebomService rebomService;

    @InjectMocks
    private ArtifactService artifactService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UUID org;
    private UUID serial;

    @BeforeEach
    void setUp() {
        org = UUID.randomUUID();
        serial = UUID.randomUUID();
        // ArtifactService is constructor-built, so @InjectMocks does not fill its @Autowired fields
        ReflectionTestUtils.setField(artifactService, "rebomService", rebomService);
    }

    private ObjectNode documentWithVersion(String version) {
        ObjectNode bom = objectMapper.createObjectNode();
        bom.put("version", version);
        bom.put("serialNumber", "urn:uuid:" + serial);
        return bom;
    }

    // ==================== T-1: getArtifactBomLatestVersion ====================

    @Test
    void spdxBornReportsRebomBomVersionNotTheConvertedDocument() throws Exception {
        when(rebomService.latestStoredBomVersion(org, serial.toString())).thenReturn(OptionalInt.of(2));
        lenient().when(rebomService.findBomByIdJson(serial, org)).thenReturn(documentWithVersion("1"));

        assertEquals("2", artifactService.getArtifactBomLatestVersion(serial, org));
        verify(rebomService, never()).findBomByIdJson(any(), any());
    }

    @Test
    void cycloneDxReportsRebomBomVersion() throws Exception {
        when(rebomService.latestStoredBomVersion(org, serial.toString())).thenReturn(OptionalInt.of(3));

        assertEquals("3", artifactService.getArtifactBomLatestVersion(serial, org));
        verify(rebomService, never()).findBomByIdJson(any(), any());
    }

    @Test
    void noRowKeyedBySerialFallsBackToTheDocumentVersion() throws Exception {
        when(rebomService.latestStoredBomVersion(org, serial.toString())).thenReturn(OptionalInt.empty());
        when(rebomService.findBomByIdJson(serial, org)).thenReturn(documentWithVersion("4"));

        assertEquals("4", artifactService.getArtifactBomLatestVersion(serial, org));
    }

    @Test
    void unreadableRebomAnswerFallsBackToTheDocumentVersion() throws Exception {
        when(rebomService.latestStoredBomVersion(org, serial.toString()))
                .thenThrow(new MalformedRebomAnswerException("bomMetaBySerialNumber answered bomVersion null"));
        when(rebomService.findBomByIdJson(serial, org)).thenReturn(documentWithVersion("4"));

        assertEquals("4", artifactService.getArtifactBomLatestVersion(serial, org));
    }

    @Test
    void rebomErrorIsRethrownWithTheExistingMessageShape() throws Exception {
        when(rebomService.latestStoredBomVersion(org, serial.toString()))
                .thenThrow(new RelizaException("rebom is down"));

        RelizaException thrown = assertThrows(RelizaException.class,
                () -> artifactService.getArtifactBomLatestVersion(serial, org));
        assertTrue(thrown.getMessage().startsWith("Error retrieving BOM for artifact:"), thrown.getMessage());
        verify(rebomService, never()).findBomByIdJson(any(), any());
    }

    @Test
    void runtimeErrorIsRethrownWithTheExistingMessageShape() throws Exception {
        when(rebomService.latestStoredBomVersion(org, serial.toString()))
                .thenThrow(new IllegalStateException("connection reset"));

        RelizaException thrown = assertThrows(RelizaException.class,
                () -> artifactService.getArtifactBomLatestVersion(serial, org));
        assertTrue(thrown.getMessage().startsWith("Error retrieving BOM for artifact:"), thrown.getMessage());
        verify(rebomService, never()).findBomByIdJson(any(), any());
    }

    @Test
    void asksRebomWithTheArtifactsOwnOrgAndSerial() throws Exception {
        when(rebomService.latestStoredBomVersion(any(), any())).thenReturn(OptionalInt.of(1));

        artifactService.getArtifactBomLatestVersion(serial, org);

        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> serialCaptor = ArgumentCaptor.forClass(String.class);
        verify(rebomService).latestStoredBomVersion(orgCaptor.capture(), serialCaptor.capture());
        assertEquals(org, orgCaptor.getValue());
        assertEquals(serial.toString(), serialCaptor.getValue());
    }

    // ==================== T-2: validateCycloneDxUpdate on an SPDX-born serial ====================

    private ArtifactData spdxBornArtifactAtVersion2() throws Exception {
        ArtifactData existing = new ArtifactData();
        existing.setOrg(org);
        existing.setInternalBom(new InternalBom(serial, ArtifactBelongsTo.RELEASE));
        existing.setVersion("2");
        lenient().when(rebomService.latestStoredBomVersion(org, serial.toString())).thenReturn(OptionalInt.of(2));
        lenient().when(rebomService.findBomByIdJson(serial, org)).thenReturn(documentWithVersion("1"));
        return existing;
    }

    private ArtifactDto cycloneDxDto(UUID uuid) {
        return ArtifactDto.builder().uuid(uuid).bomFormat(BomFormat.CYCLONEDX).build();
    }

    @Test
    void preCheckRefusesCycloneDxAtRebomsVersionOnSpdxBornSerial() throws Exception {
        ArtifactData existing = spdxBornArtifactAtVersion2();
        ArtifactDto dto = cycloneDxDto(UUID.randomUUID());

        Exception thrown = assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(
                artifactService, "validateCycloneDxUpdate", dto, documentWithVersion("2"), existing));
        // ReflectionTestUtils wraps a checked exception: find the RelizaException in the chain
        Throwable current = thrown;
        while (null != current && !(current instanceof RelizaException)) {
            current = current.getCause();
        }
        assertNotNull(current, "Expected RelizaException in exception chain");
        assertEquals("Uploaded bom should have an incremented version", current.getMessage());
    }

    @Test
    void preCheckPassesCycloneDxAboveRebomsVersionAndKeepsTheUuid() throws Exception {
        ArtifactData existing = spdxBornArtifactAtVersion2();
        UUID artifactUuid = UUID.randomUUID();
        ArtifactDto dto = cycloneDxDto(artifactUuid);

        ReflectionTestUtils.invokeMethod(artifactService, "validateCycloneDxUpdate",
                dto, documentWithVersion("3"), existing);

        assertEquals("3", dto.getVersion());
        assertEquals(artifactUuid, dto.getUuid());
    }

    @Test
    void preCheckPassesAnotherSerialAndMintsANewUuid() throws Exception {
        ArtifactData existing = spdxBornArtifactAtVersion2();
        UUID artifactUuid = UUID.randomUUID();
        ArtifactDto dto = cycloneDxDto(artifactUuid);
        ObjectNode otherSerial = documentWithVersion("2");
        otherSerial.put("serialNumber", "urn:uuid:" + UUID.randomUUID());

        ReflectionTestUtils.invokeMethod(artifactService, "validateCycloneDxUpdate", dto, otherSerial, existing);

        assertEquals("2", dto.getVersion());
        assertNotEquals(artifactUuid, dto.getUuid());
    }
}
