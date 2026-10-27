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
 * The programmatic lane of the ws/saas client-input sites at resolver level (task TPF-65,
 * architecture-1 section 4.3): createComponentInPerspectiveProgrammatic converts its component after
 * the key check and before the perspective lookup, so a vcs that is not a uuid is refused naming the
 * field and nothing is written.
 */
public class SaasProgrammaticInputConversionTest extends PerspectiveCreatePathsTestBase {

	@Test
	void aProgrammaticComponentVcsThatIsNotAUuid() throws Exception {
		World w = world();
		Snapshot before = snapshot(w);
		Map<String, Object> input = new HashMap<>(componentInput("tpf65-" + tag(), null, null));
		input.put("vcs", "not-a-uuid");
		InvalidInputException e = assertThrows(InvalidInputException.class,
				() -> saasComponentDataFetcher.createComponentInPerspectiveProgrammatic(
						as(w.freeformA(), "component", input), w.pA()));
		assertEquals("component.vcs: not a uuid", e.getMessage());
		assertNothingWritten(w, before);
	}
}
