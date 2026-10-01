/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ArtifactUploadResponseDto;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.tea.Rebom.RebomResponse;
import io.reliza.model.tea.TeaChecksumType;

/**
 * Pins what the raw download promises: the bytes the publisher uploaded, and a checksum over
 * THOSE bytes, recorded as AS_UPLOADED.
 *
 * The change is additive on purpose. ORIGINAL_FILE, OCI_STORAGE, REARM and the size tag keep
 * the values and sources they had before, so no existing row or reader shifts; AS_UPLOADED is
 * the new record, and the only one taken over the bytes as sent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ArtifactServiceRawUploadRetentionTest {

    /** Pretty-printed the way syft, cdxgen and friends emit, with a unicode description. */
    private static final String PRETTY_CDX = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "serialNumber": "urn:uuid:0d7d5b9d-52cb-4eb2-a6b2-18bea4114067",
          "version": 1,
          "components": [
            {
              "type": "library",
              "name": "left-pad",
              "version": "1.3.0",
              "description": "unicode probe: caf\\u00e9"
            }
          ]
        }
        """;

    /**
     * A score of 1e-7 is valid CycloneDX and valid JSON. V8 renders it "1e-7" and Jackson
     * renders it "1.0E-7", so any path that re-serializes cannot return the uploaded bytes --
     * which is what breaks the one guarantee SPDX artifacts used to have.
     */
    private static final String EXPONENTIAL_CDX = """
        {
          "bomFormat": "CycloneDX",
          "specVersion": "1.6",
          "serialNumber": "urn:uuid:9f2c1b44-0a77-4a2e-9d3f-1c2b3a4d5e6f",
          "version": 1,
          "vulnerabilities": [
            { "id": "PROBE-0001", "ratings": [ { "score": 1e-7, "method": "other" } ] }
          ]
        }
        """;

    private static final String PRETTY_SPDX = """
        {
          "spdxVersion": "SPDX-2.3",
          "SPDXID": "SPDXRef-DOCUMENT",
          "name": "probe"
        }
        """;

    private ArtifactService artifactService;
    private UUID artifactUuid;

    @BeforeEach
    void setUp() {
        artifactService = spy(new ArtifactService(null, null, "reliza", "http://localhost"));
        artifactUuid = UUID.randomUUID();
    }

    private OASResponseDto stubPush(String manifestDigest, String repositoryName) {
        ArtifactUploadResponseDto oci = new ArtifactUploadResponseDto();
        oci.setDigest("sha256:" + manifestDigest);
        OASResponseDto push = new OASResponseDto();
        push.setOciResponse(oci);
        push.setOciRepositoryName(repositoryName);
        doReturn(push).when(artifactService)
            .uploadFileToConfiguredOci(any(Resource.class), anyString(), anyString());
        return push;
    }

    private static String asUploaded(ArtifactDto dto) {
        return dto.getDigestRecords().stream()
            .filter(dr -> dr.scope() == DigestScope.AS_UPLOADED)
            .map(DigestRecord::digest)
            .findFirst()
            .orElse(null);
    }

    private OASResponseDto retain(String documentText, ArtifactDto artifactDto) {
        byte[] uploaded = documentText.getBytes(StandardCharsets.UTF_8);
        // Stands in for rebom's response: its own stored copy, a different document.
        OASResponseDto rebomResponse = new OASResponseDto();
        rebomResponse.setFileSHA256Digest("digest-of-reboms-processed-copy");
        rebomResponse.setOriginalSize(4321L);
        Resource file = new ByteArrayResource(uploaded) {
            @Override public String getFilename() { return "sbom.json"; }
        };
        ReflectionTestUtils.invokeMethod(artifactService, "retainRawUpload",
            artifactDto, file, uploaded);
        return rebomResponse;
    }

    private static ArtifactDto bomDto(UUID uuid, BomFormat format) {
        return ArtifactDto.builder()
            .uuid(uuid)
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(format)
            .build();
    }

    @Test
    void asUploadedIsTheDigestOfTheBytesAsSent() {
        stubPush("aaaa1111", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(artifactUuid, BomFormat.CYCLONEDX);

        retain(PRETTY_CDX, dto);

        assertEquals(Utils.bytesToHexSha256(PRETTY_CDX.getBytes(StandardCharsets.UTF_8)),
            asUploaded(dto), "AS_UPLOADED must be the digest of the file as uploaded");
    }

    /**
     * The whole point of a separate scope: everything that existed before keeps its old value
     * and its old source, so no existing row or reader has to change.
     */
    @Test
    void nothingRecordedBeforeIsDisturbed() {
        stubPush("bbbb2222", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(artifactUuid, BomFormat.CYCLONEDX);

        OASResponseDto response = retain(PRETTY_CDX, dto);

        assertEquals("digest-of-reboms-processed-copy", response.getFileSHA256Digest(),
            "the value the caller files as ORIGINAL_FILE must be left alone");
        assertTrue(dto.getDigestRecords().stream().noneMatch(dr -> dr.scope() == DigestScope.ORIGINAL_FILE),
            "retention writes no ORIGINAL_FILE record of its own");
    }

    @Test
    void blobPointerIsRecordedSoTheRawDownloadCanFindIt() {
        stubPush("cccc3333", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(artifactUuid, BomFormat.CYCLONEDX);

        retain(PRETTY_CDX, dto);

        assertEquals("downloadable-artifacts-2026-09", dto.getRawOciRepositoryName());
        DigestRecord rawBlob = dto.getDigestRecords().stream()
            .filter(dr -> dr.scope() == DigestScope.RAW_OCI_STORAGE)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a RAW_OCI_STORAGE digest record"));
        assertEquals("cccc3333", rawBlob.digest());
        assertEquals(TeaChecksumType.SHA_256, rawBlob.algo());
    }

    @Test
    void contentAddressesTheBlobSoAnIdenticalReuploadReusesIt() {
        stubPush("dddd4444", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(artifactUuid, BomFormat.CYCLONEDX);

        retain(PRETTY_CDX, dto);

        String uploadedSha = Utils.bytesToHexSha256(PRETTY_CDX.getBytes(StandardCharsets.UTF_8));
        verify(artifactService).uploadFileToConfiguredOci(any(Resource.class),
            eq("raw-" + uploadedSha), eq(uploadedSha));
    }

    /**
     * D5. Nothing pinned this before: a document carrying a number in exponential form comes
     * back differently rendered from any re-serializing path, so serving a re-serialization
     * fails the advertised checksum even when the content is unchanged.
     */
    @Test
    void exponentialNumbersSurviveOnlyBecauseTheBytesAreKept() {
        stubPush("eeee5555", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(artifactUuid, BomFormat.CYCLONEDX);
        byte[] uploaded = EXPONENTIAL_CDX.getBytes(StandardCharsets.UTF_8);

        retain(EXPONENTIAL_CDX, dto);

        String reSerialized = Utils.OM.readTree(uploaded).toString();
        assertNotEquals(Utils.bytesToHexSha256(uploaded),
            Utils.bytesToHexSha256(reSerialized.getBytes(StandardCharsets.UTF_8)),
            "re-serializing this document changes its bytes -- that is the defect being fixed");
        assertTrue(EXPONENTIAL_CDX.contains("1e-7"), "the uploaded form");
        assertTrue(reSerialized.contains("1.0E-7"), "the re-serialized form differs");

        assertEquals(Utils.bytesToHexSha256(uploaded), asUploaded(dto),
            "AS_UPLOADED must match the uploaded bytes, not any re-rendering");
    }

    @Test
    void retentionAppliesToSpdxOnTheSameTerms() {
        stubPush("ffff6666", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(artifactUuid, BomFormat.SPDX);

        retain(PRETTY_SPDX, dto);

        assertEquals(Utils.bytesToHexSha256(PRETTY_SPDX.getBytes(StandardCharsets.UTF_8)),
            asUploaded(dto));
        assertNotNull(dto.getRawOciRepositoryName());
    }

    /**
     * DigestScope backs both the GraphQL output type and DigestRecordInput, so every value
     * added to it becomes settable by a caller. AS_UPLOADED is the change's entire trust
     * signal -- "we hashed these bytes" -- and RAW_OCI_STORAGE picks which blob the raw
     * download fetches from a repository shared across organizations. Neither may be declared.
     */
    @Test
    void callerSuppliedAsUploadedIsRefused() {
        Set<DigestRecord> declared = new LinkedHashSet<>(List.of(
            new DigestRecord(TeaChecksumType.SHA_256, "forged-by-the-caller", DigestScope.AS_UPLOADED)));

        RelizaException e = assertThrows(RelizaException.class,
            () -> Utils.rejectServerDerivedDigestScopes(declared));
        assertTrue(e.getMessage().contains("AS_UPLOADED"), e.getMessage());
    }

    @Test
    void callerSuppliedRawOciStorageIsRefused() {
        Set<DigestRecord> declared = new LinkedHashSet<>(List.of(
            new DigestRecord(TeaChecksumType.SHA_256, "someone-elses-blob", DigestScope.RAW_OCI_STORAGE)));

        RelizaException e = assertThrows(RelizaException.class,
            () -> Utils.rejectServerDerivedDigestScopes(declared));
        assertTrue(e.getMessage().contains("RAW_OCI_STORAGE"), e.getMessage());
    }

    /**
     * The guard has to sit on createArtifact, not only on the upload path.
     *
     * addArtifactManual chooses between the two by whether the mutation carries a file, and the
     * no-file branch calls createArtifact DIRECTLY. A check placed only on uploadArtifact
     * therefore leaves a second route to the same rows -- one where the caller supplies the
     * digest records outright, and where a forged AS_UPLOADED becomes the checksum TEA
     * publishes on their behalf. createArtifact is the entry for caller-supplied records, so
     * that is where this guarantee belongs; uploadArtifact checks its own caller's records and
     * then writes through persistArtifact, see theUploadPathPersistsTheRecordsTheServerDerived.
     */
    @Test
    void theNoFileArtifactPathCannotDeclareServerDerivedScopesEither() {
        ArtifactDto dto = bomDto(UUID.randomUUID(), BomFormat.CYCLONEDX);
        dto.setDigestRecords(new LinkedHashSet<>(List.of(
            new DigestRecord(TeaChecksumType.SHA_256, "forged-by-the-caller", DigestScope.AS_UPLOADED))));

        RelizaException e = assertThrows(RelizaException.class,
            () -> artifactService.createArtifact(dto, null));
        assertTrue(e.getMessage().contains("AS_UPLOADED"), e.getMessage());
    }

    @Test
    void theNoFileArtifactPathCannotDeclareABlobPointerEither() {
        ArtifactDto dto = bomDto(UUID.randomUUID(), BomFormat.CYCLONEDX);
        dto.setDigestRecords(new LinkedHashSet<>(List.of(
            new DigestRecord(TeaChecksumType.SHA_256, "someone-elses-blob", DigestScope.RAW_OCI_STORAGE))));

        RelizaException e = assertThrows(RelizaException.class,
            () -> artifactService.createArtifact(dto, null));
        assertTrue(e.getMessage().contains("RAW_OCI_STORAGE"), e.getMessage());
    }

    /**
     * The regression that refused every CycloneDX and SPDX upload once the guard shipped.
     *
     * uploadArtifact checks the caller's records, retainRawUpload then adds the server's own
     * AS_UPLOADED and RAW_OCI_STORAGE, and the write used to go through createArtifact -- whose
     * guard cannot tell the server's records from a caller's and refused them with the very
     * message meant for a forger. The write now goes through persistArtifact. This drives the
     * whole of uploadArtifact with rebom and the registry stubbed, which the tests above never
     * did: they reached retainRawUpload by reflection and stopped short of the write.
     */
    @Test
    void theUploadPathPersistsTheRecordsTheServerDerived() throws Exception {
        stubPush("abcd1234", "downloadable-artifacts-2026-09");
        stubRebomAccepting();
        Artifact saved = new Artifact();
        saved.setUuid(UUID.randomUUID());
        doReturn(saved).when(artifactService).persistArtifact(any(), any());

        UUID result = artifactService.uploadArtifact(uploadDto(), upload(PRETTY_CDX), new RebomOptions(), null);

        assertEquals(saved.getUuid(), result);
        ArgumentCaptor<ArtifactDto> persisted = ArgumentCaptor.forClass(ArtifactDto.class);
        verify(artifactService).persistArtifact(persisted.capture(), any());
        ArtifactDto written = persisted.getValue();
        assertEquals(Utils.bytesToHexSha256(PRETTY_CDX.getBytes(StandardCharsets.UTF_8)), asUploaded(written),
            "the checksum over the uploaded bytes must reach the write");
        assertTrue(written.getDigestRecords().stream()
                .anyMatch(dr -> dr.scope() == DigestScope.RAW_OCI_STORAGE && "abcd1234".equals(dr.digest())),
            "the blob pointer retainRawUpload recorded must reach the write: " + written.getDigestRecords());
        assertEquals("downloadable-artifacts-2026-09", written.getRawOciRepositoryName());
    }

    /**
     * With the write no longer passing createArtifact, the check at the top of uploadArtifact
     * is the only one a file upload meets. It has to refuse a caller's server-derived record
     * before anything reaches the registry, or the fix above would have reopened the hole the
     * guard was added for.
     */
    @Test
    void theUploadPathStillRefusesACallerSuppliedBlobPointer() throws Exception {
        stubPush("abcd1234", "downloadable-artifacts-2026-09");
        stubRebomAccepting();
        ArtifactDto dto = uploadDto();
        dto.setDigestRecords(new LinkedHashSet<>(List.of(
            new DigestRecord(TeaChecksumType.SHA_256, "someone-elses-blob", DigestScope.RAW_OCI_STORAGE))));

        RelizaException e = assertThrows(RelizaException.class,
            () -> artifactService.uploadArtifact(dto, upload(PRETTY_CDX), new RebomOptions(), null));

        assertTrue(e.getMessage().contains("RAW_OCI_STORAGE"), e.getMessage());
        verify(artifactService, never()).uploadFileToConfiguredOci(any(Resource.class), anyString(), anyString());
        verify(artifactService, never()).persistArtifact(any(), any());
    }

    /** A fresh BOM upload as addArtifactManual and the CLI hand it in: no uuid yet, stored on ReARM. */
    private static ArtifactDto uploadDto() {
        return ArtifactDto.builder()
            .org(UUID.randomUUID())
            .type(ArtifactData.ArtifactType.BOM)
            .bomFormat(BomFormat.CYCLONEDX)
            .storedIn(ArtifactData.StoredIn.REARM)
            .build();
    }

    private static Resource upload(String documentText) {
        return new ByteArrayResource(documentText.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "sbom.json"; }
        };
    }

    /**
     * rebom accepts the document and answers as it does in production: its processed copy,
     * behind an OCI digest of its own, which uploadArtifact records as OCI_STORAGE.
     */
    private void stubRebomAccepting() throws Exception {
        ArtifactUploadResponseDto oci = new ArtifactUploadResponseDto();
        oci.setDigest("sha256:reboms-processed-copy");
        oci.setSize("709");
        oci.setMediaType("application/json");
        OASResponseDto rebomsCopy = new OASResponseDto();
        rebomsCopy.setOciResponse(oci);
        rebomsCopy.setOriginalSize(709L);
        rebomsCopy.setFileSHA256Digest("digest-of-reboms-processed-copy");
        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + UUID.randomUUID(), "rebom-semantic-digest",
            "digest-of-reboms-processed-copy", 709L, "application/json",
            null, null, "1");
        RebomResponse rebomResponse = new RebomResponse(UUID.randomUUID(), rebomsCopy, meta, false);
        BomLifecycleService bomLifecycleService = mock(BomLifecycleService.class);
        when(bomLifecycleService.processBomArtifact(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false));
        ReflectionTestUtils.setField(artifactService, "bomLifecycleService", bomLifecycleService);
    }

    /**
     * ORIGINAL_FILE is declarable on purpose -- it means "someone told us this digest", which
     * is exactly what an external or user-entered artifact carries. Refusing it would break
     * the existing upload API.
     */
    @Test
    void callerSuppliedOriginalFileIsStillAccepted() throws Exception {
        Set<DigestRecord> declared = new LinkedHashSet<>(List.of(
            new DigestRecord(TeaChecksumType.SHA_256, "declared-by-the-publisher", DigestScope.ORIGINAL_FILE),
            new DigestRecord(TeaChecksumType.SHA_256, "an-oci-manifest", DigestScope.OCI_STORAGE)));

        Utils.rejectServerDerivedDigestScopes(declared);
    }

    /**
     * A document that will not parse costs no storage.
     *
     * The tag is content-addressed, so a retry of the SAME bytes reuses one blob -- but a
     * caller who varies the bytes gets a fresh blob per attempt, nothing reclaims them, and
     * they sit in a registry shared across organizations. Parsing first keeps what is retained
     * to a subset of what was accepted as a document at all.
     */
    @Test
    void anUnparseableDocumentIsRetainedNowhere() {
        stubPush("3333cccc", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(UUID.randomUUID(), BomFormat.CYCLONEDX);
        byte[] notJson = "this is not a bom".getBytes(StandardCharsets.UTF_8);
        Resource file = new ByteArrayResource(notJson) {
            @Override public String getFilename() { return "broken.json"; }
        };

        assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(
            artifactService, "storeArtifactOnRebom", dto, file, null, new RebomOptions()),
            "an unparseable document must still fail the upload");

        verify(artifactService, never()).uploadFileToConfiguredOci(any(Resource.class),
            anyString(), anyString());
        assertTrue(null == dto.getDigestRecords() || dto.getDigestRecords().isEmpty(),
            "nothing was retained, so nothing may claim to have been");
    }

    /**
     * The retention still happens BEFORE rebom, which is the ordering that carries a cost
     * either way. Reversing THAT looks tidier -- a rebom refusal would leave no unreferenced
     * blob -- but if the push fails after rebom has kept the serial and version, the user's
     * retry of the same file comes back as a version conflict that only a version bump clears.
     */
    @Test
    void bytesAreRetainedBeforeRebomIsAskedToAcceptThem() throws Exception {
        stubPush("7777dddd", "downloadable-artifacts-2026-09");
        byte[] uploaded = PRETTY_CDX.getBytes(StandardCharsets.UTF_8);

        BomLifecycleService bomLifecycleService = mock(BomLifecycleService.class);
        when(bomLifecycleService.processBomArtifact(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RelizaException("rebom refused this upload"));
        ReflectionTestUtils.setField(artifactService, "bomLifecycleService", bomLifecycleService);

        ArtifactDto dto = bomDto(UUID.randomUUID(), BomFormat.CYCLONEDX);
        Resource file = new ByteArrayResource(uploaded) {
            @Override public String getFilename() { return "sbom.json"; }
        };

        assertThrows(Exception.class, () -> ReflectionTestUtils.invokeMethod(
            artifactService, "storeArtifactOnRebom", dto, file, null, new RebomOptions()));

        verify(artifactService).uploadFileToConfiguredOci(any(Resource.class),
            eq("raw-" + Utils.bytesToHexSha256(uploaded)), eq(Utils.bytesToHexSha256(uploaded)));
    }

    /**
     * D4. The advertised checksum is derived from the upload alone, so re-pushing rebom's
     * processed copy -- which enrichment does, stamping a fresh metadata.timestamp each time --
     * cannot move it. Two artifacts built from the same file agree.
     */
    @Test
    void repushingTheProcessedCopyCannotMoveTheAdvertisedChecksum() {
        stubPush("1111aaaa", "downloadable-artifacts-2026-09");
        ArtifactDto first = bomDto(UUID.randomUUID(), BomFormat.CYCLONEDX);
        retain(PRETTY_CDX, first);

        // Same uploaded file, later ingest, rebom's processed copy has since been re-pushed.
        stubPush("2222bbbb", "downloadable-artifacts-2026-10");
        ArtifactDto second = bomDto(UUID.randomUUID(), BomFormat.CYCLONEDX);
        OASResponseDto secondResponse = new OASResponseDto();
        secondResponse.setFileSHA256Digest("a-different-processed-copy-digest");
        secondResponse.setOriginalSize(9999L);
        byte[] uploaded = PRETTY_CDX.getBytes(StandardCharsets.UTF_8);
        Resource file = new ByteArrayResource(uploaded) {
            @Override public String getFilename() { return "sbom.json"; }
        };
        ReflectionTestUtils.invokeMethod(artifactService, "retainRawUpload",
            second, file, uploaded);

        assertEquals(asUploaded(first), asUploaded(second),
            "two artifacts over the same uploaded file must advertise the same checksum");
    }

    /**
     * Drives the whole of storeArtifactOnRebom with rebom stubbed, so the assertions below can
     * land on the response the caller actually receives.
     *
     * @param rebomReportedSize the size rebom claims for its own copy, deliberately unlike the
     *        upload's so the two can never be mistaken for each other
     */
    private OASResponseDto processUpload(String documentText, BomFormat format, long rebomReportedSize)
            throws Exception {
        byte[] uploaded = documentText.getBytes(StandardCharsets.UTF_8);

        OASResponseDto rebomsCopy = new OASResponseDto();
        rebomsCopy.setOriginalSize(rebomReportedSize);
        rebomsCopy.setFileSHA256Digest("digest-of-reboms-processed-copy");

        RebomOptions meta = new RebomOptions(
            null, null, null, null, null, false, false, null, null, null,
            "urn:uuid:" + UUID.randomUUID(), "rebom-semantic-digest",
            "digest-of-reboms-processed-copy", rebomReportedSize, "application/json",
            null, null, "1");
        RebomResponse rebomResponse = new RebomResponse(UUID.randomUUID(), rebomsCopy, meta, false);

        BomLifecycleService bomLifecycleService = mock(BomLifecycleService.class);
        when(bomLifecycleService.processBomArtifact(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new BomLifecycleService.BomLifecycleResult(rebomResponse, Optional.empty(), false));
        ReflectionTestUtils.setField(artifactService, "bomLifecycleService", bomLifecycleService);

        Resource file = new ByteArrayResource(uploaded) {
            @Override public String getFilename() { return "sbom.json"; }
        };
        return ReflectionTestUtils.invokeMethod(artifactService, "storeArtifactOnRebom",
            bomDto(artifactUuid, format), file, null, new RebomOptions());
    }

    /**
     * The size tag is the one pre-existing value this change corrects rather than leaves alone:
     * it described rebom's copy, and now describes the file the user sent.
     *
     * Asserted on the returned response rather than with a verify() on the setter, because an
     * interaction check cannot see this. The SPDX branch genuinely does call setOriginalSize
     * with rebom's number and this overwrites it afterwards, so a verify() passes while the
     * value the UI reads is the other one.
     */
    @Test
    void theSizeTagDescribesTheUploadedFileAndNotRebomsCopy() throws Exception {
        stubPush("cccc3333", "downloadable-artifacts-2026-09");
        long uploadedLength = PRETTY_CDX.getBytes(StandardCharsets.UTF_8).length;

        OASResponseDto response = processUpload(PRETTY_CDX, BomFormat.CYCLONEDX, 4321L);

        assertEquals(uploadedLength, response.getOriginalSize().longValue());
        assertNotEquals(4321L, response.getOriginalSize().longValue(),
            "rebom's copy is a different document and a different length");
    }

    /**
     * SPDX is the sharper case. applySpdxResponse sets the size from rebom's metadata on
     * purpose -- that was the whole point of the override -- so this branch has to be pinned
     * separately to catch the correction being lost or reordered behind it.
     */
    @Test
    void theSizeTagDescribesTheUploadedFileOnSpdxToo() throws Exception {
        stubPush("dddd4444", "downloadable-artifacts-2026-09");
        long uploadedLength = PRETTY_SPDX.getBytes(StandardCharsets.UTF_8).length;

        OASResponseDto response = processUpload(PRETTY_SPDX, BomFormat.SPDX, 810L);

        assertEquals(uploadedLength, response.getOriginalSize().longValue());
        assertNotEquals(810L, response.getOriginalSize().longValue(),
            "the SPDX branch writes rebom's size first; the correction must come after it");
    }

    /**
     * The size and the checksum have to describe the same bytes. Advertising a digest over the
     * upload next to a length taken from a different document is its own small lie, and one no
     * consumer can detect without downloading the file.
     */
    @Test
    void theSizeAndTheAdvertisedChecksumDescribeTheSameBytes() throws Exception {
        stubPush("eeee5555", "downloadable-artifacts-2026-09");
        ArtifactDto dto = bomDto(artifactUuid, BomFormat.CYCLONEDX);
        retain(PRETTY_CDX, dto);

        stubPush("eeee5555", "downloadable-artifacts-2026-09");
        OASResponseDto response = processUpload(PRETTY_CDX, BomFormat.CYCLONEDX, 4321L);

        assertEquals(Utils.bytesToHexSha256(PRETTY_CDX.getBytes(StandardCharsets.UTF_8)),
            asUploaded(dto));
        assertEquals(PRETTY_CDX.getBytes(StandardCharsets.UTF_8).length,
            response.getOriginalSize().longValue());
    }
}
