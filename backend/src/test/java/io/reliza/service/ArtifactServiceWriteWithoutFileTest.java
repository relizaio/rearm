/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.tea.Link;
import io.reliza.model.tea.Link.ContentEnum;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.TeaChecksumType;

/**
 * createArtifact is addArtifactManual's branch for a mutation that carries no file, and
 * persistArtifact rebuilds the whole record from the dto. The UI's "Upload New Artifact Version"
 * form submitted without a file reached it with the artifact's uuid, storedIn=REARM, empty
 * digestRecords and an empty version, and the rebuild wiped everything derived from the stored
 * bytes. For a BOM on a source code entry that wipe was committed and every later save of the
 * release failed. Without a file, nothing may now claim ReARM storage or replace bytes ReARM holds.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ArtifactServiceWriteWithoutFileTest {

    private ArtifactService artifactService;

    @BeforeEach
    void setUp() throws Exception {
        artifactService = spy(new ArtifactService(null, null, "reliza", "http://localhost"));
        doReturn(new Artifact()).when(artifactService).persistArtifact(any(), any());
    }

    /** An artifact as an upload leaves it: stored in ReARM, internalBom and server-derived records set. */
    private static ArtifactData storedBom(UUID uuid) {
        ArtifactData ad = new ArtifactData();
        ReflectionTestUtils.setField(ad, "uuid", uuid);
        ad.setType(ArtifactType.BOM);
        ad.setBomFormat(BomFormat.CYCLONEDX);
        ad.setStoredIn(StoredIn.REARM);
        ad.setVersion("1");
        ad.setInternalBom(new InternalBom(UUID.randomUUID(), null));
        ad.setDigestRecords(new LinkedHashSet<>(List.of(
            new DigestRecord(TeaChecksumType.SHA_256, "a".repeat(64), DigestScope.AS_UPLOADED))));
        return ad;
    }

    /** What CreateArtifact.vue sends for a new version when no file was chosen: it forces REARM. */
    private static ArtifactDto uiNewVersionWithoutFile(UUID artifactUuid) {
        return ArtifactDto.builder()
            .uuid(artifactUuid)
            .org(UUID.randomUUID())
            .type(ArtifactType.BOM)
            .bomFormat(BomFormat.CYCLONEDX)
            .displayIdentifier("sbom")
            .storedIn(StoredIn.REARM)
            .digestRecords(new LinkedHashSet<>())
            .version("")
            .build();
    }

    private static ArtifactDto external(UUID artifactUuid) {
        Link link = new Link();
        link.setUri("https://example.com/sbom.json");
        link.setContent(ContentEnum.PLAIN_JSON);
        return ArtifactDto.builder()
            .uuid(artifactUuid)
            .org(UUID.randomUUID())
            .type(ArtifactType.BOM)
            .bomFormat(BomFormat.CYCLONEDX)
            .displayIdentifier("sbom")
            .storedIn(StoredIn.EXTERNALLY)
            .downloadLinks(List.of(link))
            .build();
    }

    private void refused(ArtifactDto dto, String expectedMessagePart) throws Exception {
        RelizaException e = assertThrows(RelizaException.class, () -> artifactService.createArtifact(dto, null));
        assertTrue(e.getMessage().contains(expectedMessagePart), e.getMessage());
        verify(artifactService, never()).persistArtifact(any(), any());
    }

    @Test
    void aNewVersionOfAStoredArtifactWithoutAFileIsRefusedBeforeAnyWrite() throws Exception {
        UUID uuid = UUID.randomUUID();
        doReturn(Optional.of(storedBom(uuid))).when(artifactService).getArtifactData(uuid);

        refused(uiNewVersionWithoutFile(uuid), "A new version of this artifact must include a file");
    }

    /**
     * Claiming external storage for the new version does not get around it: that would discard the
     * stored bytes' pointers in the same rebuild.
     */
    @Test
    void aStoredArtifactCannotBeSwitchedToExternalWithoutAFile() throws Exception {
        UUID uuid = UUID.randomUUID();
        doReturn(Optional.of(storedBom(uuid))).when(artifactService).getArtifactData(uuid);

        refused(external(uuid), "A new version of this artifact must include a file");
    }

    /**
     * The UI form forces storedIn=REARM even for an externally stored artifact, and hides the link
     * field, so the refusal it gets must not tell the user to add a link it cannot enter.
     */
    @Test
    void theUiFormOnAnExternalArtifactIsToldToAddAFile() throws Exception {
        UUID uuid = UUID.randomUUID();
        ArtifactData existing = storedBom(uuid);
        existing.setStoredIn(StoredIn.EXTERNALLY);
        existing.setInternalBom(null);
        doReturn(Optional.of(existing)).when(artifactService).getArtifactData(uuid);

        refused(uiNewVersionWithoutFile(uuid), "A new version of this artifact must include a file");
    }

    /** A new artifact claiming ReARM storage with no bytes: nothing could ever be downloaded from it. */
    @Test
    void aNewArtifactStoredInReArmWithoutAFileIsRefused() throws Exception {
        refused(uiNewVersionWithoutFile(null), "stored in ReARM must include a file");
    }

    /**
     * Legacy rows can carry an internalBom with storedIn unset. A no-file new version would wipe that
     * the same way, so the refusal keys on the bytes ReARM holds, not only on the storedIn flag.
     */
    @Test
    void aLegacyRowWithABomButNoStorageSetCannotBeEditedWithoutAFile() throws Exception {
        UUID uuid = UUID.randomUUID();
        ArtifactData legacy = storedBom(uuid);
        legacy.setStoredIn(null);
        doReturn(Optional.of(legacy)).when(artifactService).getArtifactData(uuid);
        ArtifactDto dto = external(uuid);
        dto.setStoredIn(null);

        refused(dto, "A new version of this artifact must include a file");
    }

    /**
     * Links with storage left unset is how API callers attach a link today (rearm-integration-tests'
     * attach-artifact and bundling steps send exactly this), so it must keep working.
     */
    @Test
    void aNewArtifactWithLinksAndNoStorageSetIsStillWritten() throws Exception {
        ArtifactDto dto = external(null);
        dto.setStoredIn(null);

        artifactService.createArtifact(dto, null);

        verify(artifactService).persistArtifact(any(), any());
    }

    @Test
    void aNewExternallyStoredArtifactIsStillWritten() throws Exception {
        artifactService.createArtifact(external(null), null);

        verify(artifactService).persistArtifact(any(), any());
    }

    /** Editing an artifact that was external all along loses nothing, so it stays allowed. */
    @Test
    void aNoFileEditOfAnExternallyStoredArtifactIsStillWritten() throws Exception {
        UUID uuid = UUID.randomUUID();
        ArtifactData existing = storedBom(uuid);
        existing.setStoredIn(StoredIn.EXTERNALLY);
        existing.setInternalBom(null);
        doReturn(Optional.of(existing)).when(artifactService).getArtifactData(uuid);

        artifactService.createArtifact(external(uuid), null);

        verify(artifactService).persistArtifact(any(), any());
    }
}
