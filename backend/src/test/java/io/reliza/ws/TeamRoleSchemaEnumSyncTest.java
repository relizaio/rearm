/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.reliza.model.TeamRole;

/**
 * Regression guard for the Team-primitive role enum (sibling of
 * {@code ComponentOwnershipSchemaEnumSyncTest} /
 * {@code NotificationSchemaEnumSyncTest}).
 *
 * <p>{@code TeamRole} is declared in BOTH the Java model and
 * {@code schema.graphqls}, and DGS binds the GraphQL enum to the Java enum of the
 * same name -- so a value present on one side but not the other silently breaks
 * binding. Adding an org-facing role is exactly the kind of change that lands on
 * one side only, and the failure mode (a role that cannot be selected, or a
 * deserialization error at request time) is invisible until runtime. This test
 * parses the raw schema (no Spring / DGS) and asserts the value sets are equal.
 */
class TeamRoleSchemaEnumSyncTest {

	/** "enum Foo { ... }" block -- captures the body between the braces. */
	private static final Pattern ENUM_BLOCK = Pattern.compile(
			"enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

	@Test
	void teamRoleEnumIsInSync() {
		assertEnumInSync("TeamRole",
				Arrays.stream(TeamRole.values()).map(Enum::name)
						.collect(Collectors.toCollection(TreeSet::new)));
	}

	private void assertEnumInSync(String schemaEnumName, Set<String> javaValues) {
		Set<String> schemaValues = readSchemaEnum(schemaEnumName);
		assertEquals(javaValues, schemaValues,
				"GraphQL enum " + schemaEnumName + " drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	private static Set<String> diff(Set<String> a, Set<String> b) {
		Set<String> r = new LinkedHashSet<>(a);
		r.removeAll(b);
		return r;
	}

	private static Set<String> readSchemaEnum(String enumName) {
		String schema = readSchema();
		Matcher m = ENUM_BLOCK.matcher(schema);
		while (m.find()) {
			if (!enumName.equals(m.group(1))) continue;
			Set<String> out = new TreeSet<>();
			for (String raw : m.group(2).split("\\R")) {
				String line = raw.trim();
				int hash = line.indexOf('#');
				if (hash >= 0) line = line.substring(0, hash).trim();
				if (line.isEmpty()) continue;
				out.add(line);
			}
			return out;
		}
		throw new AssertionError("Did not find enum " + enumName + " in schema.graphqls");
	}

	private static String readSchema() {
		try (InputStream in = TeamRoleSchemaEnumSyncTest.class.getResourceAsStream(
				"/schema/schema.graphqls")) {
			if (in == null) throw new IllegalStateException("schema.graphqls not on test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
