/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.InvalidInputException;

/**
 * The programmatic lane of the ws/ top-level client-input sites at resolver level, with the real
 * authorization (task TPF-64, architecture-2 section 4 row 6): createComponentProgrammatic converts
 * its component after the key's authorization and before the organization is set, so a vcs that is
 * not a uuid is refused naming the field and nothing is written.
 */
public class ProgrammaticInputConversionTest extends PerspectiveCreatePathsTestBase {

	@Test
	void aProgrammaticComponentVcsThatIsNotAUuid() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Map<String, Object> input = new HashMap<>(componentInput("tpf64-" + tag(), null, null));
		input.put("vcs", "not-a-uuid");
		InvalidInputException e = assertThrows(InvalidInputException.class,
				() -> componentDataFetcher.createComponentProgrammatic(as(w.freeformA(), "component", input)));
		assertEquals("component.vcs: not a uuid", e.getMessage());
		assertNothingWritten(w, before);
	}
}
