/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RebomResponseTooLargeException;
import io.reliza.exceptions.SbomScoreException;
import io.reliza.exceptions.SbomScoreException.SbomScoreErrorReason;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.InternalBom;

/**
 * What a score gets when the document it scores was too large to read back from rebom (task
 * SCORE-19, design ADR-2 and ADR-5): {@code INPUT_TOO_LARGE} with the SCORE-5 message, and no score
 * request.
 *
 * <p>rebom is a spy over a real {@link RebomService} at an address nothing listens on: the R-18
 * check runs for real, and any request it sent would fail as something other than too large. The
 * overflow is raised with a cause whose message says nothing about size, so a mapping decided on
 * message text fails here.
 */
class SbomScoreServiceReleaseTest {

	private static final List<String> PROFILES = List.of("fda");

	private SbomScoreService service;
	private ReleaseService releaseService;
	private SharedArtifactService sharedArtifactService;
	private RebomService rebomService;

	@BeforeEach
	void setUp() {
		service = new SbomScoreService();
		releaseService = mock(ReleaseService.class);
		sharedArtifactService = mock(SharedArtifactService.class);
		rebomService = spy(new RebomService("http://127.0.0.1:1/"));
		ReflectionTestUtils.setField(service, "releaseService", releaseService);
		ReflectionTestUtils.setField(service, "sharedArtifactService", sharedArtifactService);
		ReflectionTestUtils.setField(service, "rebomService", rebomService);
		ReflectionTestUtils.setField(service, "artifactDownloadAccessService", mock(ArtifactDownloadAccessService.class));
	}

	private static RebomResponseTooLargeException overflow() {
		return new RebomResponseTooLargeException(new IllegalStateException("connection reset by peer"));
	}

	private SbomScoreException scoreRelease() {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", UUID.randomUUID());
		return assertThrows(SbomScoreException.class, () -> service.scoreRelease(rd, false, false, false,
				ArtifactBelongsTo.RELEASE, null, null, null, WhoUpdated.getTestWhoUpdated(), PROFILES));
	}

	// T-4 (ADR-2)
	@Test
	void aReleaseExportTooLargeToReadIsInputTooLarge() throws Exception {
		when(releaseService.exportReleaseSbom(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(overflow());
		SbomScoreException e = scoreRelease();
		assertEquals(SbomScoreErrorReason.INPUT_TOO_LARGE, e.getReason());
		assertEquals(RebomService.SCORE_TOO_LARGE_MESSAGE, e.getMessage());
		verify(rebomService, never()).scoreBom(anyString(), anyList());
	}

	// T-9 (ADR-2, the artifact path)
	@Test
	void anArtifactDownloadTooLargeToReadIsInputTooLarge() throws Exception {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", UUID.randomUUID());
		ad.setOrg(UUID.randomUUID());
		ad.setType(ArtifactType.BOM);
		ad.setBomFormat(BomFormat.CYCLONEDX);
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		when(sharedArtifactService.downloadArtifact(any(), any(), any())).thenThrow(overflow());
		SbomScoreException e = assertThrows(SbomScoreException.class,
				() -> service.scoreArtifact(ad, List.of(), false, PROFILES));
		assertEquals(SbomScoreErrorReason.INPUT_TOO_LARGE, e.getReason());
		assertEquals(RebomService.SCORE_TOO_LARGE_MESSAGE, e.getMessage());
		verify(rebomService, never()).scoreBom(anyString(), anyList());
	}

	// T-3 (tests/SCORE-19/run-1.md, ADR-4): the message a person reads, with its remedy, as a literal;
	// every other test compares with the constant, so this is the one that holds the text
	@Test
	void theTooLargeMessageNamesTheRequestLimitAndTheRemedy() {
		assertEquals("SBOM is too large to score: the request to the scoring service is limited to 50 MB; "
				+ "export with fewer components (top-level only)", RebomService.SCORE_TOO_LARGE_MESSAGE);
	}

	// T-5 (ADR-5: read succeeds, R-18 refuses without a request)
	@Test
	void aReleaseExportOverTheRequestLimitIsRefusedBeforeAnyRequest() throws Exception {
		when(releaseService.exportReleaseSbom(any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn("a".repeat(52428801));
		SbomScoreException e = scoreRelease();
		assertEquals(SbomScoreErrorReason.INPUT_TOO_LARGE, e.getReason());
		assertEquals(RebomService.SCORE_TOO_LARGE_MESSAGE, e.getMessage());
	}
}
