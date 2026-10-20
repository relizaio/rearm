/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.service.DeclaredLicenses.DeclaredLicenseLevel;

/**
 * SCORE-39 (ADR-1, ADR-3): the GraphQL enums that mirror the Java enums this change touches stay in
 * sync. A Java ReleaseUpdateScope value missing from ReleaseUpdateScopeEnum fails the serialisation
 * of every release that carries an event of it (the SUPPORT_WINDOW incident noted in the schema),
 * and the DECLARED_LICENSE event is new here.
 */
class DeclaredLicenseSchemaEnumSyncTest {

	private static final Pattern ENUM_BLOCK = Pattern.compile("enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

	private static Set<String> schemaEnum(String name) throws IOException {
		String schema;
		try (InputStream in = DeclaredLicenseSchemaEnumSyncTest.class.getResourceAsStream("/schema/schema.graphqls")) {
			assertNotNull(in, "schema.graphqls not on the classpath");
			schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Matcher m = ENUM_BLOCK.matcher(schema);
		while (m.find()) {
			if (!name.equals(m.group(1))) continue;
			Set<String> values = new TreeSet<>();
			for (String raw : m.group(2).split("\\R")) {
				String line = raw.trim();
				int hash = line.indexOf('#');
				if (hash >= 0) line = line.substring(0, hash).trim();
				if (!line.isEmpty() && !line.startsWith("\"")) values.add(line);
			}
			return values;
		}
		throw new AssertionError("enum " + name + " not found in schema.graphqls");
	}

	private static Set<String> names(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
	}

	@Test
	void releaseUpdateScopeEnumIsInSync() throws IOException {
		assertEquals(names(ReleaseUpdateScope.values()), schemaEnum("ReleaseUpdateScopeEnum"));
	}

	@Test
	void declaredLicenseLevelIsInSync() throws IOException {
		assertEquals(names(DeclaredLicenseLevel.values()), schemaEnum("DeclaredLicenseLevel"));
	}
}
