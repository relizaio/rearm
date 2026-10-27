/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.UserData;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.service.ArtifactServiceSpies;

/**
 * SCORE-52 T-13 and T-31 (ADR-3, design 3.11): SPDX. An SPDX update is always an update of its
 * row's serialNumber: a writer on A only is refused when B also holds S, with a GUARD event naming
 * the serialNumber without the {@code urn:uuid:} prefix; a writer on both passes. An SPDX first
 * upload is never refused: rebom's namespace deduplication answers an existing serialNumber, and the
 * row is a copy when its uploader could not write an authorizing holder (none, for a session-only
 * S), an authorizing row when it could, or when the serialNumber is new.
 */
class SbomUpdateGuardSpdxTest extends SbomUpdateGuardProgrammaticTestBase {

	private static String spdx(String namespace, String name) {
		return "{\"spdxVersion\":\"SPDX-2.3\",\"SPDXID\":\"SPDXRef-DOCUMENT\",\"name\":\"" + name + "\","
				+ "\"dataLicense\":\"CC0-1.0\",\"documentNamespace\":\"" + namespace + "\","
				+ "\"creationInfo\":{\"created\":\"2026-10-19T00:00:00Z\",\"creators\":[\"Tool: score52\"]},"
				+ "\"packages\":[]}";
	}

	private static String namespace() {
		return "https://example.com/score52/" + UUID.randomUUID();
	}

	private UUID spdxRow(UUID org, UUID serial, ArtifactBelongsTo belongsTo) throws RelizaException {
		return artifactService.createArtifact(ArtifactDto.builder().org(org).type(ArtifactType.BOM)
				.bomFormat(BomFormat.SPDX).displayIdentifier("score52-spdx-" + UUID.randomUUID())
				.internalBom(new InternalBom(serial, belongsTo)).version("1").build(), WU).getUuid();
	}

	private static void passes(ExecutionResult r) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
	}

	// T-13
	@Test
	void anSpdxUpdateNeedsWriteOnEveryHolder() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		UUID onA = spdxRow(w.org().getUuid(), s, ArtifactBelongsTo.RELEASE);
		listDirectly(w.releaseA(), onA);
		listDirectly(w.releaseB(), spdxRow(w.org().getUuid(), s, ArtifactBelongsTo.RELEASE));
		String doc = spdx(namespace(), "score52-update");
		clearInvocations(uploads, rebomService);

		GraphQLError error = onlyError(manualUpdate(writerOn(w.org(), w.a().getUuid()), w.releaseA(), onA,
				BomFormat.SPDX, doc));

		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"), error.toString());
		ArtifactServiceSpies.nothingRetainedOrWritten(uploads);
		verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
		List<ReleaseUpdateEvent> events = guardEvents(w.releaseA());
		assertEquals(1, events.size(), events.toString());
		assertEquals(s.toString(), events.get(0).newValue());

		passes(manualUpdate(writerOn(w.org(), w.a().getUuid(), w.b().getUuid()), w.releaseA(), onA, BomFormat.SPDX,
				doc));
		assertEquals(1, rebom.versionsStored(s));
	}

	// T-13, T-31
	@Test
	void aFreshSpdxFirstUploadIsNeverCheckedAndAuthors() throws Exception {
		World w = world();
		UserData writerB = writerOn(w.org(), w.b().getUuid());
		passes(manualUpload(writerB, w.releaseB(), BomFormat.SPDX, spdx(namespace(), "score52-fresh")));
		assertFalse(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
	}

	// T-31
	@Test
	void aSessionOnlySpdxSerialNumberCopiedByBIsACopyAndItsUpdateIsRefused() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		String ns = namespace();
		String doc = spdx(ns, "score52-session");
		spdxRow(w.org().getUuid(), s, ArtifactBelongsTo.AGENT_SESSION);
		rebom.storeSpdx(ns, s, doc);
		UserData writerB = writerOn(w.org(), w.b().getUuid());

		passes(manualUpload(writerB, w.releaseB(), BomFormat.SPDX, doc));
		UUID copy = addedRow(w.releaseB(), List.of(w.y()));
		assertTrue(isCopy(copy));
		assertEquals(s, artifactService.getArtifactData(copy).orElseThrow().getInternalBom().id());

		GraphQLError error = onlyError(manualUpdate(writerB, w.releaseB(), copy, BomFormat.SPDX,
				spdx(ns, "score52-session-v2")));
		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"), error.toString());
		assertEquals(s.toString(), guardEvents(w.releaseB()).get(0).newValue());
	}

	// T-31, round 4
	@Test
	void asWritersFirstUploadOfAnSpdxSerialNumberAHoldsAuthors() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		String ns = namespace();
		String doc = spdx(ns, "score52-held");
		listDirectly(w.releaseA(), spdxRow(w.org().getUuid(), s, ArtifactBelongsTo.RELEASE));
		rebom.storeSpdx(ns, s, doc);
		UUID secondA = release(w.org(), w.a(), List.of());

		passes(manualUpload(writerOn(w.org(), w.a().getUuid()), secondA, BomFormat.SPDX, doc));
		assertFalse(isCopy(addedRow(secondA, List.of())));
	}

	// T-31
	@Test
	void anOrganizationWideFirstUploadAuthorsWithoutARead() throws Exception {
		World w = world();
		UUID s = UUID.randomUUID();
		String ns = namespace();
		String doc = spdx(ns, "score52-org");
		spdxRow(w.org().getUuid(), s, ArtifactBelongsTo.AGENT_SESSION);
		rebom.storeSpdx(ns, s, doc);
		clearInvocations(guard);

		passes(manualUpload(orgWriter(w.org()), w.releaseB(), BomFormat.SPDX, doc));
		assertFalse(isCopy(addedRow(w.releaseB(), List.of(w.y()))));
		verify(guard, never()).holdersOfArtifact(any(), any());
	}
}
