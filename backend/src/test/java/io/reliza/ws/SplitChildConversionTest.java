/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskInput.SplitChild;

/**
 * The split-children conversion, which turns the GraphQL input maps into the record the
 * service takes.
 *
 * <p>This exists because moving the parse from the service to the fetcher moved it OUT of
 * the integration tests' path: those now build {@link SplitChild} directly, so nothing else
 * in the suite sends the shape a coordinator actually sends. A plain JUnit test, because a
 * map-to-record conversion needs no Spring context.
 */
class SplitChildConversionTest {

	@Test
	void aFullChildConvertsEveryField() throws RelizaException {
		UUID component = UUID.randomUUID();
		Map<String, Object> m = new HashMap<>();
		m.put("title", "part 1");
		m.put("externalRef", "github:acme/widget#42");
		m.put("sourceUrl", "https://github.com/acme/widget/issues/42");
		m.put("workLevel", 20);
		m.put("producesComponent", component.toString());   // arrives as a String over the wire
		m.put("dependsOnSiblingIndexes", List.of(0, 2));

		SplitChild c = AgentTaskDataFetcher.toSplitChildren(List.of(m)).get(0);
		assertEquals("part 1", c.title());
		assertEquals("github:acme/widget#42", c.externalRef());
		assertEquals("https://github.com/acme/widget/issues/42", c.sourceUrl());
		assertEquals(20, c.workLevel());
		assertEquals(component, c.producesComponent(), "a uuid string becomes a UUID");
		assertEquals(List.of(0, 2), c.dependsOnSiblingIndexes());
	}

	@Test
	void titleAloneIsEnoughAndTheRestComeBackNull() throws RelizaException {
		SplitChild c = AgentTaskDataFetcher.toSplitChildren(List.of(Map.of("title", "part 1"))).get(0);
		assertEquals("part 1", c.title());
		assertNull(c.workLevel());
		assertNull(c.producesComponent());
		assertTrue(c.dependsOnSiblingIndexes().isEmpty(),
				"the compact constructor normalizes the absent list, so callers never null-check it");
	}

	@Test
	void anExplicitNullDependencyListIsNormalizedToo() throws RelizaException {
		// Map.of rejects null values, so this shape needs a HashMap -- and it is the shape a
		// client sends when it writes the field out as null rather than omitting it.
		Map<String, Object> m = new HashMap<>();
		m.put("title", "part 1");
		m.put("dependsOnSiblingIndexes", null);
		assertTrue(AgentTaskDataFetcher.toSplitChildren(List.of(m)).get(0)
				.dependsOnSiblingIndexes().isEmpty());
	}

	@Test
	void nothingInMeansNothingOut() throws RelizaException {
		assertTrue(AgentTaskDataFetcher.toSplitChildren(null).isEmpty());
		assertTrue(AgentTaskDataFetcher.toSplitChildren(new ArrayList<>()).isEmpty());
	}

	@Test
	void aMalformedFieldIsReportedAsAReadFailureRatherThanEscapingAsAClassCast() {
		Map<String, Object> m = new HashMap<>();
		m.put("title", "part 1");
		m.put("producesComponent", "not-a-uuid");
		RelizaException e = assertThrows(RelizaException.class,
				() -> AgentTaskDataFetcher.toSplitChildren(List.of(m)));
		assertTrue(e.getMessage().contains("split children"), e.getMessage());

		Map<String, Object> m2 = new HashMap<>();
		m2.put("title", "part 1");
		m2.put("dependsOnSiblingIndexes", List.of("first"));
		assertThrows(RelizaException.class, () -> AgentTaskDataFetcher.toSplitChildren(List.of(m2)));
	}
}
