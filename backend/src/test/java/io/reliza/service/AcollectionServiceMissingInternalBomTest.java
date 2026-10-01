/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.Acollection;
import io.reliza.model.AcollectionData;
import io.reliza.model.AcollectionData.VersionedArtifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.repositories.AcollectionRepository;

/**
 * resolveReleaseCollection runs on every save of a release. A ReARM-stored BOM whose record lost
 * its internalBom (what a no-file "new version" used to leave behind) made it throw, so the release
 * could not be saved at all. It now versions such an artifact 0 and carries on, so the file re-upload
 * that repairs it (rebom version 1 or more) always registers as a change.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AcollectionServiceMissingInternalBomTest {

    private AcollectionService acollectionService;
    private ArtifactService artifactService;
    private RebomService rebomService;
    private SharedReleaseService sharedReleaseService;
    private ArtifactGatherService artifactGatherService;
    private final UUID org = UUID.randomUUID();
    private final UUID releaseUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AcollectionRepository repository = mock(AcollectionRepository.class);
        when(repository.save(any(Acollection.class))).thenAnswer(inv -> inv.getArgument(0));
        acollectionService = new AcollectionService(repository);
        artifactService = mock(ArtifactService.class);
        rebomService = mock(RebomService.class);
        sharedReleaseService = mock(SharedReleaseService.class);
        artifactGatherService = mock(ArtifactGatherService.class);
        ReflectionTestUtils.setField(acollectionService, "artifactService", artifactService);
        ReflectionTestUtils.setField(acollectionService, "rebomService", rebomService);
        ReflectionTestUtils.setField(acollectionService, "sharedReleaseService", sharedReleaseService);
        ReflectionTestUtils.setField(acollectionService, "artifactGatherService", artifactGatherService);
        when(artifactService.isRebomStoreable(any())).thenCallRealMethod();

        ReleaseData rd = new ReleaseData();
        ReflectionTestUtils.setField(rd, "uuid", releaseUuid);
        ReflectionTestUtils.setField(rd, "org", org);
        when(sharedReleaseService.getReleaseData(releaseUuid)).thenReturn(Optional.of(rd));
    }

    private ArtifactData storedBom(InternalBom internalBom) {
        ArtifactData ad = new ArtifactData();
        ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
        ad.setOrg(org);
        ad.setType(ArtifactType.BOM);
        ad.setBomFormat(BomFormat.CYCLONEDX);
        ad.setStoredIn(StoredIn.REARM);
        ad.setInternalBom(internalBom);
        when(artifactService.getArtifactData(ad.getUuid())).thenReturn(Optional.of(ad));
        return ad;
    }

    @Test
    void aReleaseHoldingAWipedBomStillSaves() {
        ArtifactData wiped = storedBom(null);
        UUID serial = UUID.randomUUID();
        ArtifactData healthy = storedBom(new InternalBom(serial, null));
        when(rebomService.resolveBomMetas(serial, org)).thenReturn(List.of(new RebomService.BomMeta(
            null, null, null, "3", null, null, null, null, null, null, null, null, null, null, null, null)));
        when(artifactGatherService.gatherReleaseArtifacts(any())).thenReturn(Set.of(wiped.getUuid(), healthy.getUuid()));

        AcollectionData collection = assertDoesNotThrow(() -> acollectionService.resolveReleaseCollection(releaseUuid, WhoUpdated.getTestWhoUpdated()));

        assertTrue(collection.getArtifacts().contains(new VersionedArtifact(wiped.getUuid(), 0L, ArtifactType.BOM)),
            "the wiped BOM is versioned 0, below any real rebom version: " + collection.getArtifacts());
        assertTrue(collection.getArtifacts().contains(new VersionedArtifact(healthy.getUuid(), 3L, ArtifactType.BOM)),
            "a healthy BOM on the same release still takes its version from rebom: " + collection.getArtifacts());
        verify(rebomService, never()).resolveBomMetas(isNull(), any());
    }
}
