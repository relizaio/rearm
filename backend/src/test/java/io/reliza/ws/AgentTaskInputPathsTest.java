/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.InvalidInputException;
import io.reliza.exceptions.RelizaException;

/**
 * The path a refused conversion names where the fetcher helper takes it from its caller (task TPF-64,
 * architecture-2 section 4 row 3): the review item decisions and about of the person verbs, and the
 * commits and source code entry of the programmatic release inputs. Plain JUnit: a map-to-record
 * conversion needs no Spring context.
 */
class AgentTaskInputPathsTest {

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	// row 3 case 1
	@Test
	void aDecisionPriorityThatIsNotANumberIsNamedUnderTheCallersArgument() {
		InvalidInputException e = assertThrows(InvalidInputException.class,
				() -> AgentTaskDataFetcher.toReviewItemDecisions(
						List.of(map("action", "ACCEPT", "reviewItemId", "x", "priority", "high")), "decisions"));
		assertEquals("decisions[0].priority: must be a number", e.getMessage());
	}

	// row 3 case 2
	@Test
	void anAboutReleaseThatIsNotAUuid() {
		InvalidInputException e = assertThrows(InvalidInputException.class,
				() -> AgentTaskDataFetcher.toAbout(map("specification", "ARCHITECTURE", "release", "not-a-uuid"), "about"));
		assertEquals("about.release: not a uuid", e.getMessage());
	}

	// row 3 case 3
	@Test
	void aBatchCommitIsNamedByReleaseAndCommitIndex() {
		InvalidInputException e = assertThrows(InvalidInputException.class,
				() -> ReleaseDatafetcher.sceDtosToRecord(map("commits",
						List.of(map("commit", "a"), map("commit", "b", "vcs", "not-a-uuid"))), "releases[1]"));
		assertEquals("releases[1].commits[1].vcs: not a uuid", e.getMessage());
	}

	// row 3 case 3
	@Test
	void theSourceCodeEntryIsNamedUnderTheSingleRelease() {
		InvalidInputException e = assertThrows(InvalidInputException.class,
				() -> ReleaseDatafetcher.sceDtosToRecord(map("sourceCodeEntry", map("commit", "c", "branch", "x")),
						"release"));
		assertEquals("release.sourceCodeEntry.branch: not a uuid", e.getMessage());
	}

	// row 3 case 4
	@Test
	void nullHandlingIsKept() throws RelizaException {
		assertTrue(AgentTaskDataFetcher.toSplitChildren(null).isEmpty());
		assertNull(AgentTaskDataFetcher.toAbout(null, "about"));
	}
}
