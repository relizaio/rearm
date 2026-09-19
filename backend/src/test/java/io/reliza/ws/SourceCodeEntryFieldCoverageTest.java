/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.reliza.model.SourceCodeEntryData;

/**
 * Every field the schema declares on {@code SourceCodeEntry} must be resolvable.
 *
 * <p>Written after {@code recognized} and {@code attestation} were declared in the SDL with no
 * resolver behind them: DGS returns null for a field with no resolver and no matching property,
 * null is falsy, and so every commit in the UI read "unclaimed" no matter how many attestations
 * it carried. Nothing failed -- not the build, not the schema tests, not a GraphQL error -- the
 * answer was just quietly wrong, which is the worst way for a field to be missing.
 *
 * <p>A field passes if {@code SourceCodeEntryData} exposes it as a property (DGS's default
 * resolution) or if some data fetcher declares {@code @DgsData(parentType = "SourceCodeEntry",
 * field = "...")} for it.
 */
public class SourceCodeEntryFieldCoverageTest {

	/**
	 * Fields that predate this test and have always returned null: {@code SourceCodeEntryData}
	 * calls the repository reference {@code vcs}, not {@code vcsUuid}, and has never had a
	 * {@code commits} property. Nothing in the UI asks for either. They are listed rather than
	 * fixed because deleting a declared field is a change to a published contract, which is a
	 * decision to take deliberately -- this test exists to stop the list growing.
	 */
	private static final List<String> KNOWN_NULL = List.of("vcsUuid", "commits");

	private static final Path SCHEMA = Path.of("src/main/resources/schema/schema.graphqls");
	private static final Path SOURCES = Path.of("src/main/java");

	@Test
	public void everySourceCodeEntryFieldHasSomethingBehindIt() throws IOException {
		List<String> fields = schemaFieldsOf("SourceCodeEntry");
		assertTrue(fields.size() > 5, "failed to parse the type at all: " + fields);
		String resolvers = resolverDeclarations();

		List<String> unresolvable = new ArrayList<>();
		for (String field : fields) {
			if (hasProperty(field)) continue;
			if (resolvers.contains("field = \"" + field + "\"")) continue;
			if (KNOWN_NULL.contains(field)) continue;
			unresolvable.add(field);
		}
		assertTrue(unresolvable.isEmpty(), "SourceCodeEntry fields declared in the schema with "
				+ "no property on SourceCodeEntryData and no @DgsData resolver -- they will "
				+ "silently return null: " + unresolvable);
	}

	/** Field names of one SDL type, ignoring descriptions, comments and arguments. */
	private static List<String> schemaFieldsOf(String typeName) throws IOException {
		String sdl = Files.readString(SCHEMA);
		Matcher start = Pattern.compile("(?m)^type\\s+" + typeName + "\\s*\\{").matcher(sdl);
		assertTrue(start.find(), "type " + typeName + " not found in the schema");
		int from = start.end();
		int to = sdl.indexOf("\n}", from);
		String body = sdl.substring(from, to);
		// Drop block descriptions, which contain colons and would otherwise parse as fields.
		body = body.replaceAll("(?s)\"\"\".*?\"\"\"", "");
		List<String> fields = new ArrayList<>();
		for (String line : body.split("\n")) {
			String l = line.strip();
			if (l.isEmpty() || l.startsWith("#") || l.startsWith("\"")) continue;
			Matcher m = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(\\(.*\\))?\\s*:").matcher(l);
			if (m.find()) fields.add(m.group(1));
		}
		return fields;
	}

	private static boolean hasProperty(String field) {
		String suffix = Character.toUpperCase(field.charAt(0)) + field.substring(1);
		for (var m : SourceCodeEntryData.class.getMethods()) {
			if (m.getParameterCount() != 0) continue;
			if (m.getName().equals("get" + suffix) || m.getName().equals("is" + suffix)) return true;
		}
		return false;
	}

	/** Every @DgsData declaration for this parent type, across the data fetchers. */
	private static String resolverDeclarations() throws IOException {
		StringBuilder sb = new StringBuilder();
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				String src = Files.readString(p);
				if (!src.contains("parentType = \"SourceCodeEntry\"")) continue;
				Matcher m = Pattern.compile("parentType = \"SourceCodeEntry\",\\s*field = \"([A-Za-z0-9_]+)\"")
						.matcher(src);
				while (m.find()) sb.append("field = \"").append(m.group(1)).append("\" ");
			}
		}
		return sb.toString();
	}
}
