/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * TPF-162 mutation round: the three schema types of a carried design promise a value where the server always has one.
 * AgentSchemaFieldsAreBackedTest checks that each field is backed, not that it is non-null, so a client generated from
 * the schema would read a nullable where the server never returns one if a {@code !} were dropped.
 */
public class AgentCarriedDesignSchemaNullabilityTest {

	private static String schema() throws IOException {
		try (InputStream in = AgentCarriedDesignSchemaNullabilityTest.class.getResourceAsStream("/schema/schema.graphqls")) {
			assertNotNull(in, "schema/schema.graphqls is on the classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** The type's field name -> declared type, with the descriptions skipped. */
	private static Map<String, String> fieldsOf(String schema, String type) {
		Matcher m = Pattern.compile("(?m)^type " + type + " \\{\\n(.*?)\\n\\}", Pattern.DOTALL).matcher(schema);
		assertEquals(true, m.find(), type);
		Map<String, String> out = new LinkedHashMap<>();
		for (String line : m.group(1).split("\\n")) {
			Matcher f = Pattern.compile("^\\t(\\w+): (\\S+)$").matcher(line);
			if (f.matches()) out.put(f.group(1), f.group(2));
		}
		return out;
	}

	@Test
	public void theFieldsTheServerAlwaysFillsAreNonNull() throws IOException {
		String schema = schema();
		assertEquals(Map.of("specification", "SpecificationType!", "release", "ID!", "round", "Int", "path", "String"),
				fieldsOf(schema, "AgentTaskCarriedRound"));
		assertEquals(Map.of("role", "String!", "roleUuid", "ID", "signedOffAt", "DateTime", "session", "ID",
				"reviewedBy", "AgentActor", "decidedOver", "Boolean!", "live", "Boolean!"),
				fieldsOf(schema, "AgentTaskCarriedRole"));
		assertEquals(Map.of("parentTask", "ID!", "parentKey", "String!", "rounds", "[AgentTaskCarriedRound!]!",
				"roles", "[AgentTaskCarriedRole!]!", "at", "DateTime!", "by", "AgentActor", "standing", "Boolean!"),
				fieldsOf(schema, "AgentTaskCarriedDesign"));
	}
}
