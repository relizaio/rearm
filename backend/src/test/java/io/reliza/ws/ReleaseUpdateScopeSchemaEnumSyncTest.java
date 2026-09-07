/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.model.ReleaseData.ReleaseUpdateScope;

/**
 * Regression guard for {@code ReleaseUpdateScope} drift between Java and the GraphQL schema.
 *
 * <p>Written because the drift shipped and the failure was not the one you would expect.
 * {@code SUPPORT_WINDOW} was added to the Java enum when the device support window landed and
 * never added to {@code ReleaseUpdateScopeEnum}. Nothing failed at startup, nothing failed on
 * write, and the audit row was recorded correctly. It failed on READ, and not on the field:
 * DGS could not serialize the enum value, so the WHOLE {@code release} query errored and the
 * release page would not load at all.
 *
 * <p>So the blast radius of one missing enum value is every consumer of the release, for any
 * release that has ever had its support window set. Found by setting a window through the
 * API and watching the page go blank.
 *
 * <p>Equality, not subset: an update scope the schema declares but Java cannot produce is
 * dead wire surface, and one Java produces but the schema lacks is the bug above. Both fail.
 */
class ReleaseUpdateScopeSchemaEnumSyncTest {

	/** "enum Foo { ... }" block -- captures the body between the braces. */
	private static final Pattern ENUM_BLOCK = Pattern.compile(
			"enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

	private static final Pattern DOCSTRING = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"");

	@Test
	void releaseUpdateScopeEnumIsInSync() {
		Set<String> schemaValues = readSchemaEnum("ReleaseUpdateScopeEnum");
		Set<String> javaValues = new TreeSet<>(
				Arrays.stream(ReleaseUpdateScope.values()).map(Enum::name).toList());

		assertEquals(javaValues, schemaValues,
				"ReleaseUpdateScope and ReleaseUpdateScopeEnum have drifted."
						+ " Missing in schema: " + diff(javaValues, schemaValues)
						+ "; extra in schema: " + diff(schemaValues, javaValues)
						+ ". A value Java can produce but the schema lacks does NOT fail on"
						+ " write -- it fails when the release is READ, and it fails the whole"
						+ " query, so the release page stops loading for every release that"
						+ " ever recorded it.");
	}

	private static Set<String> diff(Set<String> a, Set<String> b) {
		Set<String> r = new LinkedHashSet<>(a);
		r.removeAll(b);
		return r;
	}

	/** Returns the enum values declared in the GraphQL schema file. */
	private static Set<String> readSchemaEnum(String enumName) {
		Matcher m = ENUM_BLOCK.matcher(readSchema());
		while (m.find()) {
			if (!enumName.equals(m.group(1))) continue;
			Set<String> out = new TreeSet<>();
			// Strip """...""" descriptions before splitting, and trailing # comments as well
			// as whole-line ones -- the parse SupportEnumsSchemaEnumSyncTest arrived at after
			// the first enum to document its MEMBERS had each doc line read as a value.
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

	/**
	 * From the CLASSPATH, like every sibling sync test -- not a CWD-relative path. The
	 * taxonomy guard had to be fixed for exactly that two commits ago (#489): a relative
	 * path resolves against the maven basedir and silently finds nothing anywhere else.
	 */
	private static String readSchema() {
		try (InputStream in = ReleaseUpdateScopeSchemaEnumSyncTest.class.getResourceAsStream(
				"/schema/schema.graphqls")) {
			if (in == null) throw new IllegalStateException("schema.graphqls not on test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
