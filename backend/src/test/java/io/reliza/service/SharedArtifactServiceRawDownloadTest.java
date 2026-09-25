/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.common.Utils.ArtifactBelongsTo;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * Covers which SOURCE the raw download serves from, which is the half of raw-byte retention
 * that the ingest tests cannot reach.
 *
 * The ordering in {@code downloadRawArtifact} is load-bearing: the retained-bytes branch has to
 * be reached BEFORE the internalBom branch, because an artifact with retained bytes has an
 * internalBom too. Reorder them and every BOM artifact silently goes back to serving rebom's
 * re-serialization under a checksum taken over the uploaded file -- green suite, defect back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SharedArtifactServiceRawDownloadTest {

    private SharedArtifactService service;
    private RebomService rebomService;
    private UUID artifactUuid;
    private UUID internalBomId;

    @BeforeEach
    void setUp() {
        service = new SharedArtifactService(mock(ArtifactRepository.class), "reliza", "http://localhost");
        rebomService = mock(RebomService.class);
        ReflectionTestUtils.setField(service, "rebomService", rebomService);
        artifactUuid = UUID.randomUUID();
        internalBomId = UUID.randomUUID();
    }

    private ArtifactData artifact(boolean withRetainedBytes, boolean withAsUploadedDigest) {
        ArtifactData ad = new ArtifactData();
        ReflectionTestUtils.setField(ad, "uuid", artifactUuid);
        ad.setOrg(UUID.randomUUID());
        ad.setType(ArtifactData.ArtifactType.BOM);
        ad.setBomFormat(BomFormat.CYCLONEDX);
        ad.setInternalBom(new InternalBom(internalBomId, ArtifactBelongsTo.RELEASE));
        Set<DigestRecord> digests = new LinkedHashSet<>();
        // ORIGINAL_FILE is always present -- retention does not remove or replace it.
        digests.add(new DigestRecord(TeaChecksumType.SHA_256, "aa11", DigestScope.ORIGINAL_FILE));
        if (withAsUploadedDigest) {
            digests.add(new DigestRecord(TeaChecksumType.SHA_256, "cc33", DigestScope.AS_UPLOADED));
        }
        if (withRetainedBytes) {
            digests.add(new DigestRecord(TeaChecksumType.SHA_256, "bb22", DigestScope.RAW_OCI_STORAGE));
            ad.setRawOciRepositoryName("downloadable-artifacts-2026-09");
        }
        ad.setDigestRecords(digests);
        List<TagRecord> tags = new ArrayList<>();
        tags.add(new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, "application/json", Removable.NO));
        tags.add(new TagRecord(CommonVariables.FILE_NAME_FIELD, "sbom.json", Removable.NO));
        ad.setTags(tags);
        return ad;
    }

    /**
     * An artifact with retained bytes must NOT reach the rebom branch, even though it has an
     * internalBom that branch would happily serve.
     */
    @Test
    void retainedBytesShortCircuitTheRebomPath() throws Exception {
        ArtifactData ad = artifact(true, true);

        // Subscribe, don't just assert the Mono exists: a lazy Mono that is never subscribed
        // would also be non-null if the short-circuit had NOT fired, so only subscribing shows
        // which branch ran. There is no registry here, so reaching the blob fetch surfaces as a
        // connection failure -- which is itself the proof, since the rebom branch would have
        // returned a document instead.
        Mono<ResponseEntity<byte[]>> mono = service.downloadRawArtifact(ad);
        assertThrows(Exception.class, mono::block,
            "the retained-bytes branch must attempt the blob fetch, not serve from rebom");
        verify(rebomService, never()).findRawBomById(any(), any());
        verify(rebomService, never()).findRawBomById(any(), any(), any());
        verify(rebomService, never()).findRawBomByVersion(any(), any(), anyInt());
    }

    /**
     * Retained bytes with no AS_UPLOADED digest is a contradiction: the artifact would be
     * serving unvalidated bytes under a promise of integrity. An ORIGINAL_FILE record is NOT a
     * substitute -- it may describe a different document entirely. Fail rather than serve.
     */
    @Test
    void retainedBytesWithoutAnAsUploadedDigestAreRefused() {
        ArtifactData ad = artifact(true, false);

        RelizaException e = assertThrows(RelizaException.class, () -> service.downloadRawArtifact(ad));
        assertTrue(e.getMessage().contains("no AS_UPLOADED digest"),
            "should name the missing verification digest, got: " + e.getMessage());
    }

    /**
     * Artifacts uploaded before retention have no blob, and must keep working exactly as before
     * -- served from rebom, no exception.
     */
    @Test
    void legacyArtifactsStillServeFromRebom() throws Exception {
        ArtifactData ad = artifact(false, true);
        JsonNode rebomDoc = Utils.OM.readTree("{\"bomFormat\":\"CycloneDX\"}");
        when(rebomService.findRawBomById(eq(internalBomId), any())).thenReturn(rebomDoc);

        ResponseEntity<byte[]> response = service.downloadRawArtifact(ad).block();

        assertNotNull(response);
        assertEquals(rebomDoc.toString(), new String(response.getBody(), StandardCharsets.UTF_8));
        verify(rebomService).findRawBomById(eq(internalBomId), any());
    }

    /**
     * A caller can post a digest record with neither algo nor scope -- DigestRecord's
     * components come straight off DigestRecordInput. Before these filters were null-safe,
     * such a record made the raw download throw NPE for that artifact permanently, turning
     * malformed input into a broken artifact rather than a rejected request.
     */
    @Test
    void aDigestRecordWithNoAlgoOrScopeDoesNotBreakTheDownload() throws Exception {
        ArtifactData ad = artifact(true, true);
        Set<DigestRecord> withJunk = new LinkedHashSet<>();
        withJunk.add(new DigestRecord(null, "no-algo-no-scope", null));
        withJunk.addAll(ad.getDigestRecords());
        ad.setDigestRecords(withJunk);

        // Reaches the fetch (and fails on the absent registry) rather than NPEing on the way.
        Exception e = assertThrows(Exception.class, () -> service.downloadRawArtifact(ad).block());
        assertFalse(e instanceof NullPointerException, "malformed digest records must not NPE");
    }

    /**
     * The repository pointer is per-artifact and must be used; falling back to the base
     * repository is only correct when none was recorded.
     */
    @Test
    void retainedBytesUseTheArtifactsOwnRepositoryPointer() throws Exception {
        ArtifactData ad = artifact(true, true);
        ad.setRawOciRepositoryName(null);

        // Resolves to the base repository rather than refusing outright: subscribing gets a
        // connection failure from the absent registry, NOT the RelizaException that a missing
        // AS_UPLOADED digest would raise before any fetch is attempted.
        Exception e = assertThrows(Exception.class, () -> service.downloadRawArtifact(ad).block());
        assertFalse(e instanceof RelizaException,
            "a null repository pointer must fall back to the base repository, not refuse");
    }
}
