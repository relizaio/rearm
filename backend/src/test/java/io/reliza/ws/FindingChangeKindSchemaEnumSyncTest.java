/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
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

import io.reliza.dto.ChangelogRecords.FindingChangeKind;

/**
 * Regression guard for the {@code FindingChangeKind} GraphQL/Java enum drift
 * (sibling of {@code NotificationSchemaEnumSyncTest} /
 * {@code IntegrationTypeSchemaEnumSyncTest}).
 *
 * <p>{@code FindingChangeKind} is declared in BOTH the Java DTO
 * ({@link io.reliza.dto.ChangelogRecords.FindingChangeKind}) and the GraphQL
 * schema ({@code enum FindingChangeKind} in {@code schema.graphqls}). It is
 * round-tripped over the wire as the {@code changeKind} field of
 * {@code MetricsRevisionFindingChange} (over-time finding changes, board task
 * #37), so a value present on one side but not the other silently breaks
 * serialization. Unlike the {@code IntegrationType} subset case, this enum
 * must match EXACTLY in both directions -- there is no boundary mapping.
 *
 * <p>This test parses the raw {@code schema.graphqls} file (no Spring context,
 * no DGS bootstrap) and asserts the schema enum's value set equals the Java
 * enum's. Extra or missing values fail loudly with a diff in the message.
 */
class FindingChangeKindSchemaEnumSyncTest {

	/** "enum Foo { ... }" block -- captures the body between the braces. */
	private static final Pattern ENUM_BLOCK = Pattern.compile(
			"enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

	@Test
	void findingChangeKindEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(FindingChangeKind.values()).map(Enum::name)
				.collect(java.util.stream.Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("FindingChangeKind");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum FindingChangeKind drifted from Java enum;"
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
		try (InputStream in = FindingChangeKindSchemaEnumSyncTest.class.getResourceAsStream(
				"/schema/schema.graphqls")) {
			if (in == null) throw new IllegalStateException("schema.graphqls not on test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}
}
