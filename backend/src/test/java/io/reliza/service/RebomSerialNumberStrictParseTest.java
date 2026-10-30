/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.RebomResponse;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-52 T-24 (carried from SCORE-47 T-4): the serialNumber rebom answers an upload with becomes the artifact's internalBom.id,
 * the holder record of its lineage, only in rebom's exact lineage form: {@code urn:uuid:} and the
 * canonical lowercase uuid, or that uuid alone. Every other shape is a lineage of its own in rebom
 * and is refused at both places the answer is read, {@link BomLifecycleService} and
 * {@link ArtifactService}, so it never stands for the uuid it resembles.
 */
class RebomSerialNumberStrictParseTest {

	/** A uuid whose groups start with a zero, so each has a short form one character shorter. */
	private static final String S = "0a9d9c88-08bb-4c2e-9f1a-0b2c3d4e5f60";
	private static final UUID UUID_S = UUID.fromString(S);
	private static final String NL = Character.toString(0x0A);
	private static final String NBSP = Character.toString(0x00A0);

	/** The two forms rebom keys to the lineage urn:uuid:S and reads back by S. */
	private static final List<String> ACCEPTED = List.of("urn:uuid:" + S, S);

	/** Shapes that are not rebom's key of S, each named for the failure message. */
	private static final List<String[]> REFUSED = List.of(
			new String[] { "doubled prefix", "urn:uuid:urn:uuid:" + S },
			new String[] { "tripled prefix", "urn:uuid:urn:uuid:urn:uuid:" + S },
			new String[] { "doubled prefix, bare inner", "urn:uuid:" + S + "urn:uuid:" },
			new String[] { "short first group", "urn:uuid:" + S.substring(1) },
			new String[] { "short first group, no prefix", S.substring(1) },
			new String[] { "short second group", "urn:uuid:0a9d9c88-8bb-4c2e-9f1a-0b2c3d4e5f60" },
			new String[] { "short last group", "urn:uuid:0a9d9c88-08bb-4c2e-9f1a-b2c3d4e5f60" },
			new String[] { "short groups throughout", "urn:uuid:1-2-3-4-5" },
			new String[] { "long first group", "urn:uuid:00a9d9c88-08bb-4c2e-9f1a-0b2c3d4e5f60" },
			new String[] { "upper case uuid", "urn:uuid:" + S.toUpperCase() },
			new String[] { "upper case uuid, no prefix", S.toUpperCase() },
			new String[] { "one upper case letter", "urn:uuid:0A9d9c88-08bb-4c2e-9f1a-0b2c3d4e5f60" },
			new String[] { "upper case prefix", "URN:UUID:" + S },
			new String[] { "mixed case prefix", "Urn:uuid:" + S },
			new String[] { "braces", "urn:uuid:{" + S + "}" },
			new String[] { "braces, no prefix", "{" + S + "}" },
			new String[] { "partial prefix urn:", "urn:" + S },
			new String[] { "partial prefix uuid:", "uuid:" + S },
			new String[] { "prefix without its colon", "urn:uuid" + S },
			new String[] { "no dashes", "urn:uuid:" + S.replace("-", "") },
			new String[] { "trailing text", "urn:uuid:" + S + "0" },
			new String[] { "leading space", " urn:uuid:" + S },
			new String[] { "trailing line feed", "urn:uuid:" + S + NL },
			new String[] { "leading no-break space", NBSP + "urn:uuid:" + S },
			new String[] { "space after prefix", "urn:uuid: " + S },
			new String[] { "prefix only", "urn:uuid:" },
			new String[] { "empty", "" });

	@Test
	void theParserAcceptsRebomsTwoFormsOfTheLineageAndNothingElse() {
		for (String s : ACCEPTED) {
			assertEquals(Optional.of(UUID_S), SbomUpdateGuard.lineageOfRebomSerialNumber(s), s);
		}
		assertEquals(Optional.empty(), SbomUpdateGuard.lineageOfRebomSerialNumber(null));
		List<String> wrong = new ArrayList<>();
		for (String[] shape : REFUSED) {
			if (SbomUpdateGuard.lineageOfRebomSerialNumber(shape[1]).isPresent()) wrong.add(shape[0]);
		}
		assertEquals(List.of(), wrong);
	}

	@Test
	void artifactServiceStoresNoInternalBomForAShapeOtherThanRebomsKey() throws Exception {
		for (String s : ACCEPTED) {
			assertEquals(UUID_S, ArtifactService.extractInternalBomId(answer(s)), s);
		}
		List<String> wrong = new ArrayList<>();
		for (String[] shape : REFUSED) {
			try {
				UUID id = ArtifactService.extractInternalBomId(answer(shape[1]));
				wrong.add(shape[0] + " -> " + id);
			} catch (RelizaException expected) {
				// refused
			}
		}
		assertEquals(List.of(), wrong);
		assertThrows(RelizaException.class, () -> ArtifactService.extractInternalBomId(answer(null)));
	}

	@Test
	void bomLifecycleServiceRefusesAnAnswerOtherThanRebomsKey() throws Exception {
		RebomService rebom = mock(RebomService.class);
		BomLifecycleService lifecycle = new BomLifecycleService();
		ReflectionTestUtils.setField(lifecycle, "rebomService", rebom);
		ReflectionTestUtils.setField(lifecycle, "sharedArtifactService", mock(SharedArtifactService.class));
		JsonNode doc = Utils.OM.readTree("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"2.0\",\"version\":2}");

		for (String s : ACCEPTED) {
			when(rebom.uploadSbom(any(), any(), any(), any(), any())).thenReturn(answer(s));
			assertEquals(s, lifecycle.processBomArtifact(doc, new RebomOptions(), BomFormat.CYCLONEDX,
					UUID.randomUUID(), UUID.randomUUID(), null, null).rebomResponse().meta().serialNumber());
		}
		List<String> wrong = new ArrayList<>();
		for (String[] shape : REFUSED) {
			when(rebom.uploadSbom(any(), any(), any(), any(), any())).thenReturn(answer(shape[1]));
			try {
				lifecycle.processBomArtifact(doc, new RebomOptions(), BomFormat.CYCLONEDX, UUID.randomUUID(),
						UUID.randomUUID(), null, null);
				wrong.add(shape[0]);
			} catch (RelizaException expected) {
				// refused
			}
		}
		assertEquals(List.of(), wrong);
	}

	/** rebom's answer to an upload: the serialNumber as the document carried it. */
	private static RebomResponse answer(String serialNumber) {
		RebomOptions meta = new RebomOptions(null, null, null, null, null, false, null, null, null, serialNumber,
				"digest", null);
		return new RebomResponse(UUID.randomUUID(), null, meta, false);
	}
}
