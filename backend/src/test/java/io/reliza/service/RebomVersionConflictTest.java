/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.service.RebomService.GraphQLResponse;

/**
 * A rebom refusal has to reach the person who typed the command.
 *
 * <p>rebom now refuses an upload that reuses a serialNumber without incrementing the version,
 * because the alternative was rewriting a stored raw artifact and the bytes behind an artifact
 * ReARM had already scanned. Its message names the serial number, both versions and the way out,
 * so the only useful thing this side can do is carry it through intact -- a generic wrapper here
 * would leave the CLI printing "Internal server error" for a mistake with an obvious fix.
 */
public class RebomVersionConflictTest {

	private static final String MESSAGE =
			"BOM with serialNumber urn:uuid:1111 already exists at version 3; "
			+ "uploaded version 3 must be greater. Raw artifacts are immutable and are never replaced.";

	private static GraphQLResponse<Object> conflictResponse() {
		return new GraphQLResponse<>(null, List.of(Map.of(
				"message", MESSAGE,
				"extensions", Map.of("code", "BOM_VERSION_CONFLICT",
						"details", Map.of("serialNumber", "urn:uuid:1111",
								"storedVersion", 3, "newVersion", 3)))));
	}

	/** handleRebomError is private and has no reason not to be; reach it the same way DGS would. */
	private static void handle(GraphQLResponse<?> response) throws Throwable {
		Method m = RebomService.class.getDeclaredMethod("handleRebomError", GraphQLResponse.class);
		m.setAccessible(true);
		try {
			// The constructor builds a WebClient from this; it is never called here.
			m.invoke(new RebomService("http://rebom-test:4000/"), response);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@Test
	public void aVersionConflictArrivesAsARelizaExceptionWithItsMessageIntact() {
		Throwable thrown = assertThrows(Throwable.class, () -> handle(conflictResponse()));
		assertInstanceOf(RelizaException.class, thrown);
		// Verbatim: no prefix, because every word of it is the answer.
		assertEquals(MESSAGE, thrown.getMessage());
	}

	@Test
	public void otherRebomErrorsKeepTheirPrefixes() throws Throwable {
		GraphQLResponse<Object> validation = new GraphQLResponse<>(null, List.of(Map.of(
				"message", "bad bom",
				"extensions", Map.of("code", "BOM_VALIDATION_ERROR"))));
		Throwable thrown = assertThrows(Throwable.class, () -> handle(validation));
		assertTrue(thrown.getMessage().startsWith("BOM validation failed: "), thrown.getMessage());
	}

	@Test
	public void anUnknownCodeStillCarriesTheMessage() {
		GraphQLResponse<Object> unknown = new GraphQLResponse<>(null, List.of(Map.of(
				"message", "something new",
				"extensions", Map.of("code", "SOMETHING_WE_HAVE_NOT_SEEN"))));
		Throwable thrown = assertThrows(Throwable.class, () -> handle(unknown));
		assertTrue(thrown.getMessage().contains("something new"), thrown.getMessage());
	}
}
