/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.ArtifactServiceSpies;
import io.reliza.service.RebomService.MalformedRebomAnswerException;
import io.reliza.service.SbomWriteAuthority;

/**
 * SCORE-52 T-11, T-12 and T-29 through {@code addArtifactManual} and the upload itself (ADR-3,
 * rule B before any byte leaves): a writer on B only is refused a higher version of A's S, also
 * upper-cased, with nothing retained, written or sent; the same or a lower version passes as a
 * copy; A's writer passes the bump; an organization writer passes on B's release without reading
 * anything for the rule; an upload without a principal is refused above the holders. With no
 * artifact row, rebom's own record decides: a failed read reaches the caller as itself, an
 * unreadable one and a higher version than rebom stores are refused.
 */
class SbomUpdateGuardDefenceInDepthTest extends SbomUpdateGuardTestBase {

	private static String urn(UUID serial) {
		return "urn:uuid:" + serial;
	}

	private void assertRefusedLocally(ExecutionResult r, World w) throws Exception {
		GraphQLError error = onlyError(r);
		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"), error.toString());
		assertFalse(error.getMessage().contains(w.a().getUuid().toString()));
		ArtifactServiceSpies.nothingRetainedOrWritten(uploads);
		verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
	}

	private static void passes(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
	}

	private void reset() {
		clearInvocations(uploads, rebomService, guard);
	}

	@Test
	void aWriterOnBOnlyIsRefusedAHigherVersionOfAsSerialNumber() throws Exception {
		World w = world();
		UserData writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		long rows = artifactRowsWithSerial(w.org().getUuid(), w.serial());
		reset();

		assertRefusedLocally(manualUpload(writerB, w.releaseB(), cdx(urn(w.serial()), 2)), w);
		reset();
		assertRefusedLocally(manualUpload(writerB, w.releaseB(),
				cdx("URN:UUID:" + w.serial().toString().toUpperCase(), 2)), w);
		verify(rebomService, never()).latestStoredBomVersion(any(), any());

		assertEquals(rows, artifactRowsWithSerial(w.org().getUuid(), w.serial()), "no artifact row written");
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()));
		assertEquals(0, rebom.addBomCalls(w.serial()));
	}

	@Test
	void aSoleHoldersBumpPassesAndTheSameOrALowerVersionByBPassesAsACopy() throws Exception {
		World w = world();
		UserData writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		UserData writerA = writer(w.org(), PermissionScope.COMPONENT, w.a().getUuid());

		passes(manualUpload(writerA, w.releaseA(), cdx(urn(w.serial()), 2)));
		assertFalse(isCopy(addedRow(w.releaseA(), List.of(w.x()))));
		assertEquals(1, rebom.versionsStored(w.serial()));

		passes(manualUpload(writerB, w.releaseB(), cdx(urn(w.serial()), 1)));
		UUID copy = addedRow(w.releaseB(), List.of(w.y()));
		assertTrue(isCopy(copy));
		assertEquals(1, rebom.versionsStored(w.serial()), "deduplicated, nothing new stored");
		List<UUID> before = artifactsOf(w.releaseB());
		passes(manualUpload(writerB, w.releaseB(), cdx(urn(w.serial()), 2)));
		assertTrue(isCopy(addedRow(w.releaseB(), before)));

		// B now holds S, so A's writer alone is refused the next version.
		reset();
		assertRefusedLocally(manualUpload(writerA, w.releaseA(), cdx(urn(w.serial()), 3)), w);
	}

	@Test
	void anOrganizationWriterPassesOnBsReleaseWithoutAReadForTheRule() throws Exception {
		World w = world();
		UserData orgWriter = orgWriter(w.org());
		reset();

		passes(manualUpload(orgWriter, w.releaseB(), cdx(urn(w.serial()), 2)));

		assertFalse(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		verify(guard, never()).holdersOfArtifact(any(), any());
		verify(rebomService, never()).latestStoredBomVersion(any(), any());
		assertEquals(1, rebom.versionsStored(w.serial()));
	}

	private UUID uploadDirect(World w, String doc, SbomWriteAuthority authority) throws Exception {
		ArtifactDto dto = ArtifactDto.builder().org(w.org().getUuid()).type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).storedIn(StoredIn.REARM).displayIdentifier("score52-" + UUID.randomUUID())
				.build();
		Resource file = new ByteArrayResource(doc.getBytes(StandardCharsets.UTF_8)) {
			@Override public String getFilename() { return "bom.json"; }
		};
		return uploads.uploadArtifact(dto, file, new RebomOptions(), authority, WU);
	}

	@Test
	void anUploadWithoutAPrincipalIsRefusedAboveTheHolders() throws Exception {
		World w = world();
		reset();

		assertThrows(SbomUpdateNotPermittedException.class,
				() -> uploadDirect(w, cdx(urn(w.serial()), 2), SbomWriteAuthority.NO_PRINCIPAL));

		ArtifactServiceSpies.nothingRetainedOrWritten(uploads);
		verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
	}

	// T-29 (g)
	@Test
	void aFailedStepTwoReadReachesTheCallerAsItselfAndNothingIsRetained() throws Exception {
		World w = world();
		RelizaException unavailable = new RelizaException("rebom unavailable");
		when(rebomService.latestStoredBomVersion(any(), any())).thenThrow(unavailable);
		SbomWriteAuthority writerB = authorities.forUser(writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid()),
				w.org().getUuid());
		reset();

		RelizaException e = assertThrows(RelizaException.class,
				() -> uploadDirect(w, cdx(urn(UUID.randomUUID()), 1), writerB));

		assertSame(unavailable, e);
		ArtifactServiceSpies.nothingRetainedOrWritten(uploads);
		verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
	}

	// T-29 (g)
	@Test
	void anUnreadableStepTwoAnswerIsRefused() throws Exception {
		World w = world();
		when(rebomService.latestStoredBomVersion(any(), any()))
				.thenThrow(new MalformedRebomAnswerException("bomMetaBySerialNumber answered bomVersion x"));
		UserData writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		reset();

		assertRefusedLocally(manualUpload(writerB, w.releaseB(), cdx(urn(UUID.randomUUID()), 1)), w);
	}

	// T-29 (e)
	@Test
	void aVersionAboveWhatRebomAloneStoresIsRefusedAndItsRetryAuthors() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		rebom.storeWithoutArtifact(s, 1, cdx(urn(s), 1));
		UserData writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		UserData writerA = writer(w.org(), PermissionScope.COMPONENT, w.a().getUuid());
		reset();

		assertRefusedLocally(manualUpload(writerB, w.releaseB(), cdx(urn(s), 2)), w);
		verify(rebomService).latestStoredBomVersion(w.org().getUuid(), urn(s));

		passes(manualUpload(writerA, w.releaseA(), cdx(urn(s), 1)));
		assertFalse(isCopy(addedRow(w.releaseA(), List.of(w.x()))), "the retry is the first row: it authors");
		List<UUID> before = artifactsOf(w.releaseA());
		passes(manualUpload(writerA, w.releaseA(), cdx(urn(s), 2)));
		assertFalse(isCopy(addedRow(w.releaseA(), before)));
		assertEquals(1, rebom.versionsStored(s));
		assertEquals(OptionalInt.of(2), rebomService.latestStoredBomVersion(w.org().getUuid(), urn(s)));
	}
}
