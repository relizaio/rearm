/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@link ModelAssertionState} Java enum against silent drift from
 * the GraphQL {@code enum ModelAssertionState} in
 * {@code src/main/resources/schema/schema.graphqls}. The two are coupled by
 * string only — the UI string-matches the value names and CEL/policy reads
 * the persisted assertion — so a rename or added member on one side that the
 * other misses ships a provenance-mislabel bug with no compiler signal. This
 * repo has a documented history of exactly that GraphQL-enum ↔ Java-enum
 * drift, so the values are asserted equal in both directions.
 */
class ModelAssertionStateSchemaSyncTest {

	private static final String SCHEMA_PATH = "/schema/schema.graphqls";

	// enum bodies carry no nested braces, so a non-greedy up-to-brace match is
	// sufficient to grab the block.
	private static final Pattern ENUM_BLOCK =
			Pattern.compile("enum\\s+ModelAssertionState\\s*\\{([^}]*)\\}");
	// """ ... """ GraphQL docstrings, possibly multi-line, stripped before
	// tokenising so description prose can't be mistaken for an enum value.
	private static final Pattern DOCSTRING =
			Pattern.compile("\"\"\".*?\"\"\"", Pattern.DOTALL);
	private static final Pattern ENUM_VALUE = Pattern.compile("[A-Z_][A-Z0-9_]*");

	@Test
	void javaEnumMatchesGraphqlSchemaEnum() {
		Set<String> schemaValues = parseSchemaEnumValues();
		Set<String> javaValues = new LinkedHashSet<>();
		for (ModelAssertionState v : ModelAssertionState.values()) {
			javaValues.add(v.name());
		}

		assertEquals(javaValues, schemaValues,
				"ModelAssertionState drifted between the Java enum and schema.graphqls. "
				+ "Java=" + javaValues + " schema=" + schemaValues
				+ ". Update both sides (and the UI label map in AiAgentSessionView.vue) together.");
	}

	private static Set<String> parseSchemaEnumValues() {
		String schema;
		// The SDL is three files -- shared, user-facing and programmatic. Which one a type sits
		// in says who consumes it and has nothing to do with this enum, so read them all.
		StringBuilder sb = new StringBuilder();
		for (String resource : new String[] {"/schema/schema.graphqls", "/schema/user.graphqls",
				"/schema/programmatic.graphqls"}) {
			try (InputStream in = ModelAssertionStateSchemaSyncTest.class.getResourceAsStream(resource)) {
				if (in == null) throw new IllegalStateException(resource + " not on test classpath");
				sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
			} catch (java.io.IOException e) {
				throw new RuntimeException(e);
			}
		}
		schema = sb.toString();

		Matcher block = ENUM_BLOCK.matcher(schema);
		assertTrue(block.find(), "enum ModelAssertionState not found in " + SCHEMA_PATH);

		String body = DOCSTRING.matcher(block.group(1)).replaceAll(" ");
		Set<String> values = new LinkedHashSet<>();
		Matcher m = ENUM_VALUE.matcher(body);
		while (m.find()) {
			values.add(m.group());
		}
		assertTrue(!values.isEmpty(), "parsed no enum values from the schema block: " + body);
		return values;
	}
}
