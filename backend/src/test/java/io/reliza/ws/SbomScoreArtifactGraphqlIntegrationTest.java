/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.SbomScoreException;
import io.reliza.exceptions.SbomScoreException.SbomScoreErrorReason;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.ArtifactVersionSnapshot;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.repositories.ArtifactRepository;
import reactor.core.publisher.Mono;

/**
 * {@code artifactSbomScore} (task SCORE-5, design 3.3 R-7, 3.5): the download's checks in the
 * download's order, only stored SBOMs, the downloaded bytes as they were served, and no writes.
 */
class SbomScoreArtifactGraphqlIntegrationTest extends SbomScoreGraphqlTestBase {

	@Autowired private ArtifactRepository artifactRepository;

	private void verifyNoDownloadAndNoScore() throws Exception {
		verify(sharedArtifactService, never()).downloadArtifact(any(), any(), any());
		verify(sharedArtifactService, never()).downloadRawArtifact(any());
		verify(rebomService, never()).scoreBom(any(), any());
	}

	private static SbomScoreErrorReason reason(ExecutionResult r) {
		GraphQLError e = onlyError(r);
		assertEquals(SbomScoreException.ERROR_CODE, e.getExtensions().get("code"), e.toString());
		return SbomScoreErrorReason.valueOf((String) e.getExtensions().get("reason"));
	}

	// I-3 (R-7, R-8, R-13, R-25)
	@Test
	void theDownloadsChecksRunFirstAndInItsOrder() throws Exception {
		Fixture f = fixture();
		UUID notBom = artifact(f.org().getUuid(), ArtifactType.ATTESTATION, null, false);
		for (UUID a : List.of(f.bomArtifact(), notBom)) {
			ExecutionResult r = as(f.reader(), SCORE_ARTIFACT, artifactVars(a, null, null));
			assertEquals("Not authorized", errorMessage(r), "refused before the type is looked at");
			assertNull(fieldOf(r, "artifactSbomScore"));
		}
		verifyNoDownloadAndNoScore();

		assertEquals("Artifact not found",
				errorMessage(as(f.downloader(), SCORE_ARTIFACT, artifactVars(UUID.randomUUID(), null, null))));
		assertEquals("Artifact version not found",
				errorMessage(as(f.downloader(), SCORE_ARTIFACT, artifactVars(f.bomArtifact(), null, 99))));
		verifyNoDownloadAndNoScore();

		// Component scope applies to an artifact on a release ...
		assertEquals(REPORT, data(as(f.componentDownloader(), SCORE_ARTIFACT,
				artifactVars(f.bomArtifact(), null, null)), "artifactSbomScore"));
		// ... and organisation scope to one on no release.
		UUID orphan = artifact(f.org().getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		assertEquals("Not authorized", errorMessage(as(f.componentDownloader(), SCORE_ARTIFACT,
				artifactVars(orphan, null, null))));
		assertEquals(REPORT, data(as(f.downloader(), SCORE_ARTIFACT, artifactVars(orphan, null, null)),
				"artifactSbomScore"));
	}

	// I-7 (R-13)
	@Test
	void onlyAStoredSbomIsScorable() throws Exception {
		Fixture f = fixture();
		UUID attestation = artifact(f.org().getUuid(), ArtifactType.ATTESTATION, null, false);
		UUID unstored = artifact(f.org().getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, false);
		UUID vdr = artifact(f.org().getUuid(), ArtifactType.VDR, BomFormat.CYCLONEDX, true);

		ExecutionResult r = as(f.downloader(), SCORE_ARTIFACT, artifactVars(attestation, null, null));
		assertEquals(SbomScoreErrorReason.NOT_SCORABLE, reason(r));
		assertEquals("Artifact " + attestation + " is not an SBOM", errorMessage(r));

		r = as(f.downloader(), SCORE_ARTIFACT, artifactVars(unstored, null, null));
		assertEquals(SbomScoreErrorReason.NOT_SCORABLE, reason(r));
		assertEquals("Artifact " + unstored + " has no stored SBOM to score", errorMessage(r));

		r = as(f.downloader(), SCORE_ARTIFACT, artifactVars(vdr, null, null));
		assertEquals(SbomScoreErrorReason.NOT_SCORABLE, reason(r), "a CycloneDX VDR is not an SBOM");
		assertEquals("Artifact " + vdr + " is not an SBOM", errorMessage(r));

		verifyNoDownloadAndNoScore();
	}

	// I-8 (R-14, R-15)
	@Test
	void theScoredTextIsTheDownloadedBodyOfTheChosenDocumentAndVersion() throws Exception {
		Fixture f = fixture();
		List<byte[]> served = Collections.synchronizedList(new ArrayList<>());
		doAnswer(inv -> recordBody(inv.callRealMethod(), served)).when(sharedArtifactService).downloadRawArtifact(any());
		doAnswer(inv -> recordBody(inv.callRealMethod(), served)).when(sharedArtifactService)
				.downloadArtifact(any(), any(), any());

		// raw: the uploaded document, and only that path.
		data(as(f.downloader(), SCORE_ARTIFACT, artifactVars(f.bomArtifact(), true, null)), "artifactSbomScore");
		verify(sharedArtifactService).downloadRawArtifact(any());
		verify(sharedArtifactService, never()).downloadArtifact(any(), any(), any());
		assertScoredIsLastServed(served, RAW_DOC);

		// absent and false: the augmented download with no optional parameter.
		for (Boolean raw : new Boolean[] {null, false}) {
			clearInvocations(sharedArtifactService, rebomService);
			data(as(f.downloader(), SCORE_ARTIFACT, artifactVars(f.bomArtifact(), raw, null)), "artifactSbomScore");
			verify(sharedArtifactService).downloadArtifact(any(), any(), eq(ExportMetadataOptions.fromCallerInput(null, null)));
			verify(sharedArtifactService, never()).downloadRawArtifact(any());
			assertScoredIsLastServed(served, null);
		}

		// version 1 of an artifact that has two.
		UUID firstBomId = UUID.randomUUID();
		UUID twoVersions = versionedArtifact(f, firstBomId);
		clearInvocations(sharedArtifactService, rebomService);
		data(as(f.downloader(), SCORE_ARTIFACT, artifactVars(twoVersions, null, 1)), "artifactSbomScore");
		ArgumentCaptor<ArtifactData> downloaded = ArgumentCaptor.forClass(ArtifactData.class);
		verify(sharedArtifactService).downloadArtifact(downloaded.capture(), any(), any());
		assertEquals("1", downloaded.getValue().getVersion());
		assertEquals(firstBomId, downloaded.getValue().getInternalBom().id());
		assertScoredIsLastServed(served, null);
		assertTrue(new String(served.get(served.size() - 1), StandardCharsets.UTF_8).contains("0.9-first-version"));

		// A download that serves nothing, or answers non-2xx even with a body, is not scored.
		byte[] sbomLike = DOC.getBytes(StandardCharsets.UTF_8);
		for (ResponseEntity<byte[]> nothing : List.<ResponseEntity<byte[]>>of(ResponseEntity.notFound().build(),
				ResponseEntity.ok().body(new byte[0]), ResponseEntity.status(HttpStatus.NOT_FOUND).body(sbomLike),
				ResponseEntity.internalServerError().body(sbomLike))) {
			clearInvocations(rebomService);
			doReturn(Mono.just(nothing)).when(sharedArtifactService).downloadArtifact(any(), any(), any());
			ExecutionResult r = as(f.downloader(), SCORE_ARTIFACT, artifactVars(f.bomArtifact(), null, null));
			assertEquals(SbomScoreErrorReason.NOT_SCORABLE, reason(r));
			assertEquals("Artifact " + f.bomArtifact() + " has no stored SBOM to score", errorMessage(r));
			verify(rebomService, never()).scoreBom(any(), any());
		}
	}

	@SuppressWarnings("unchecked")
	private static Mono<ResponseEntity<byte[]>> recordBody(Object realResult, List<byte[]> served) {
		ResponseEntity<byte[]> entity = ((Mono<ResponseEntity<byte[]>>) realResult).block();
		served.add(entity.getBody());
		return Mono.just(entity);
	}

	/** The text handed to rebom is the UTF-8 decoding of the body the download just served. */
	private void assertScoredIsLastServed(List<byte[]> served, String expectedDocument) throws Exception {
		ArgumentCaptor<String> scored = ArgumentCaptor.forClass(String.class);
		verify(rebomService).scoreBom(scored.capture(), eq(PROFILES));
		String body = new String(served.get(served.size() - 1), StandardCharsets.UTF_8);
		assertEquals(body, scored.getValue());
		assertTrue(body.contains("\u00e9\u2192"), "the body carries non-ASCII text");
		if (null != expectedDocument) {
			assertEquals(Utils.OM.readTree(expectedDocument), Utils.OM.readTree(scored.getValue()));
		}
	}

	/**
	 * A BOM artifact at version 2 whose history holds version 1 with its own rebom id, written to the
	 * row directly: a new version through the service needs an uploaded file.
	 */
	private UUID versionedArtifact(Fixture f, UUID firstBomId) throws Exception {
		UUID uuid = artifact(f.org().getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		Artifact row = artifactRepository.findById(uuid).orElseThrow();
		ArtifactData ad = ArtifactData.dataFromRecord(row);
		ad.setVersion("2");
		ArtifactData first = ArtifactData.dataFromRecord(row);
		first.setVersion("1");
		first.setInternalBom(new InternalBom(firstBomId, ArtifactBelongsTo.RELEASE));
		ArtifactVersionSnapshot snapshot = ArtifactVersionSnapshot.fromArtifactData(first);
		assertEquals("1", snapshot.version());
		ad.addVersionSnapshot(snapshot);
		row.setRecordData(Utils.dataToRecord(ad));
		row.setLastUpdatedDate(ZonedDateTime.now());
		artifactRepository.save(row);
		release(f.org(), f.component(), List.of(uuid));
		return uuid;
	}

	// I-9 (R-10, R-16)
	@Test
	void anArtifactScoreWritesNothing() throws Exception {
		Fixture f = fixture();
		for (Boolean raw : new Boolean[] {true, null}) {
			clearInvocations(downloadLogService);
			Map<String, Long> change = changeMadeBy(() -> data(as(f.downloader(), SCORE_ARTIFACT,
					artifactVars(f.bomArtifact(), raw, null)), "artifactSbomScore"));
			assertEquals(Map.of(), change, "raw=" + raw + " wrote rows");
			verify(downloadLogService, never()).createDownloadLog(any(), any(), any(), any(), any(), any());
		}
	}
}
