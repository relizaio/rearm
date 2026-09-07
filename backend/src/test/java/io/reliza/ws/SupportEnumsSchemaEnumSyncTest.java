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
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.model.DeviceSupportRisk;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SupportAttestationFilter;
import io.reliza.model.SupportBulkOutcome;
import io.reliza.model.SupportExportState;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
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
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportStatus");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportStatus drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void levelOfSupportEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(LevelOfSupport.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("LevelOfSupport");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum LevelOfSupport drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	/**
	 * The only INPUT enum in this family, and the one whose drift is silent.
	 *
	 * <p>An output enum that drifts fails loudly at serialization. This one is an argument,
	 * and its values are also SQL literals in the release-page filter: a schema value with no
	 * Java constant is a coercion error at request time rather than at build time, and the
	 * SQL branches on the names with no ELSE, so the failure mode is an empty page rather
	 * than an error. Pinned here as well as in the service test that asserts the constants
	 * equal the names.
	 */
	/** Wire enum, so a member added on one side and not the other is a coercion error. */
	@Test
	void supportExportStateEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportExportState.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportExportState");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportExportState drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void supportAttestationFilterEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportAttestationFilter.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportAttestationFilter");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportAttestationFilter drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void supportBulkOutcomeEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportBulkOutcome.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportBulkOutcome");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportBulkOutcome drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void supportStateEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportState.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportState");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportState drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void supportSourceEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportSource.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportSource");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportSource drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void deviceSupportRiskEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(DeviceSupportRisk.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("DeviceSupportRisk");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum DeviceSupportRisk drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void supportMilestoneTypeEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportMilestoneType.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportMilestoneType");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportMilestoneType drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	@Test
	void supportPartyEnumIsInSync() {
		Set<String> javaValues = Arrays.stream(SupportParty.values()).map(Enum::name)
				.collect(Collectors.toCollection(TreeSet::new));
		Set<String> schemaValues = readSchemaEnum("SupportParty");
		assertEquals(javaValues, schemaValues,
				"GraphQL enum SupportParty drifted from Java enum;"
						+ " missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues));
	}

	private static Set<String> diff(Set<String> a, Set<String> b) {
		Set<String> r = new LinkedHashSet<>(a);
		r.removeAll(b);
		return r;
	}

	private static final Pattern DOCSTRING = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"");

	/** Returns the enum values declared in the GraphQL schema file. */
	private static Set<String> readSchemaEnum(String enumName) {
		String schema = readSchema();
		Matcher m = ENUM_BLOCK.matcher(schema);
		while (m.find()) {
			if (!enumName.equals(m.group(1))) continue;
			Set<String> out = new TreeSet<>();
			// Strip """...""" descriptions before splitting. The parser previously handled
			// only # comments, because every support enum used them -- so the first enum to
			// document its MEMBERS (which is how a description reaches introspection, where
			// # comments never do) had each documentation line read as an enum value.
			String body = DOCSTRING.matcher(m.group(2)).replaceAll("");
			for (String raw : body.split("\\R")) {
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
