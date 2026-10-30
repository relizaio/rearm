/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.GraphQLError;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;

/**
 * SCORE-52 ADR-4 (carried from SCORE-47 T-16): a rule B refusal reaches a GraphQL client as one
 * BAD_REQUEST error whose extensions carry the code {@value SbomUpdateNotPermittedException#ERROR_CODE}
 * and nothing else, with the 3.4 text naming the serialNumber as sent and no holder; a plain refusal
 * from rebom carries no code.
 */
class SbomUpdateGuardGraphqlErrorTest extends SbomUpdateGuardTestBase {

	@Test
	void aRuleBRefusalAnswersTheCodeAndTheMessage() throws Exception {
		World w = world();
		UserData writer = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		String serial = "urn:uuid:" + w.serial();

		GraphQLError error = onlyError(manualUpload(writer, w.releaseB(), cdx(serial, 2)));

		assertEquals(SbomUpdateNotPermittedException.messageFor(serial), error.getMessage());
		assertEquals("BAD_REQUEST", error.getErrorType().toString());
		assertEquals(Map.of("code", SbomUpdateNotPermittedException.ERROR_CODE), error.getExtensions());
		assertFalse(error.getMessage().contains(w.a().getUuid().toString()));
		assertFalse(error.getMessage().contains(w.b().getUuid().toString()));
		assertEquals(0, rebom.addBomCalls(w.serial()));
	}

	@Test
	void aPlainRefusalAnswersNoCode() throws Exception {
		World w = world();
		UserData writer = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		when(rebomService.uploadSbom(any(), any(), any(), any(), any()))
				.thenThrow(new RelizaException("plain refusal"));

		GraphQLError error = onlyError(manualUpload(writer, w.releaseB(), cdx("urn:uuid:" + UUID.randomUUID(), 1)));

		assertEquals("BAD_REQUEST", error.getErrorType().toString());
		assertNull(null == error.getExtensions() ? null : error.getExtensions().get("code"));
	}
}
