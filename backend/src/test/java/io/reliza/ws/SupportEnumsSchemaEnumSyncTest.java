/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.model.SupportSource;
import io.reliza.model.SupportStatus;

/**
 * Regression guard for the FDA-Readiness-1 support enums (sibling of
 * {@code FindingChangeKindSchemaEnumSyncTest}). {@code SupportStatus} and
 * {@code SupportSource} are declared in BOTH the Java model and the GraphQL
 * schema and round-tripped over the wire on {@code SbomComponent}, so a value
 * present on one side but not the other silently breaks serialization. Parses
 * the raw {@code schema.graphqls} (no Spring / DGS bootstrap) and asserts each
 * schema enum's value set equals the Java enum's.
 */
class SupportEnumsSchemaEnumSyncTest {

	/** "enum Foo { ... }" block -- captures the body between the braces. */
	private static final Pattern ENUM_BLOCK = Pattern.compile(
			"enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

	@Test
	void supportStatusEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportStatus.values()).map(Enum::name)
				.collect(java.util.stream.Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportStatus");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportStatus drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void supportSourceEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportSource.values()).map(Enum::name)
				.collect(java.util.stream.Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportSource");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportSource drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	private static Set<String> diff(Set<String> a, Set<String> b) {
		Set<String> r = new LinkedHashSet<>(a);
		r.removeAll(b);
		return r;
	}

	/** Returns the enum values declared in the GraphQL schema file. */
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
		try (InputStream in = SupportEnumsSchemaEnumSyncTest.class.getResourceAsStream(
				"/schema/schema.graphqls")) {
			if (in == null) throw new IllegalStateException("schema.graphqls not on test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}
}
