/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.model.ArtifactData.DigestScope;

/**
 * Regression guard for {@code DigestScope} (sibling of
 * {@code ComponentOwnershipSchemaEnumSyncTest} / {@code NotificationSchemaEnumSyncTest}).
 *
 * <p>DGS binds the GraphQL enum to the Java enum of the same name, so a value added on the
 * Java side alone breaks serialization the moment a real artifact carries it -- every query
 * selecting {@code digestRecords { scope }} on that artifact fails, not just the new field.
 * Adding RAW_OCI_STORAGE for raw-upload retention hit exactly that gap, which nothing caught.
 */
class DigestScopeSchemaEnumSyncTest {

	/** "enum Foo { ... }" block -- captures the body between the braces. */
	private static final Pattern ENUM_BLOCK = Pattern.compile(
			"enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

	@Test
	void digestScopeEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(DigestScope.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("DigestScope");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum DigestScope drifted from Java enum;"
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
		throw new AssertionError("Did not find enum " + enumName + " in any of the schema files");
	}

	private static String readSchema() {
		StringBuilder sb = new StringBuilder();
		for (String resource : new String[] {"/schema/schema.graphqls", "/schema/user.graphqls",
				"/schema/programmatic.graphqls"}) {
			try (InputStream in = DigestScopeSchemaEnumSyncTest.class.getResourceAsStream(resource)) {
				if (in == null) throw new IllegalStateException(resource + " not on test classpath");
				sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return sb.toString();
	}
}
