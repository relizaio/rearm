/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every {@code reliza:} property this codebase emits must be defined in
 * {@code cyclonedx-taxonomy.md}.
 *
 * <p><b>Why this is a test and not a convention.</b> The {@code reliza} namespace is
 * REGISTERED in the CycloneDX property taxonomy, and the registry resolves it to that
 * document. Eight properties were shipped into it with no published definition, which
 * nobody noticed because the document lived only in the CE mirror -- a repository the
 * Pro build never touches. A consumer could see the keys in a served BOM and had
 * nothing to resolve them against.
 *
 * <p>Documenting them once fixes the instance; this fixes the class. Adding a
 * {@code PROP_*} constant without defining it now fails the build, in the same diff,
 * rather than becoming a note somebody has to remember.
 *
 * <p>Deliberately a SUBSTRING check. A constant that is a prefix
 * ({@code reliza:support:source:}) is satisfied by the documented parameterised form
 * ({@code reliza:support:source:<milestone>}), which is how the doc should describe it.
 */
class CyclonedxTaxonomyDocSyncTest {

	/** String literals starting with the reserved namespace, in any emitting class. */
	/**
	 * Deliberately {@code [^"]*} rather than a character class. An earlier revision used
	 * {@code [A-Za-z:]*}, which silently matched NOTHING for a name containing a hyphen,
	 * a digit or a dot -- and {@code cdx:fda:level-of-support} is the CycloneDX house style
	 * this project's own comments cite, so a future {@code reliza:support:level-of-support}
	 * would have escaped the guard entirely rather than merely being reported oddly.
	 */
	private static final Pattern RELIZA_PROPERTY = Pattern.compile("\"(reliza:[^\"]*)\"");

	/**
	 * SCANNED, not listed. An earlier revision named {@code SupportBomInjector} explicitly,
	 * which is the only emitter today -- and that is exactly how this guard would have been
	 * defeated: a {@code reliza:} constant added in any other class would have escaped it
	 * silently, which is the same failure the guard exists to prevent, one level up.
	 */
	private static final Path MAIN_SOURCES = Path.of("src/main/java");

	/**
	 * Module-relative, NOT repo-relative. The backend image builds with `backend/` as its
	 * Docker context and runs the suite inside that build, so a path escaping the module
	 * root resolves to nothing there: the test passed locally and would have failed every
	 * CI build touching the backend. The doc lives in the module for that reason.
	 */
	/**
	 * The doc, wherever this repo keeps it.
	 *
	 * <p>Two locations, because this test is SYNCED to the CE mirror by {@code copy-src.sh}
	 * and the doc is not put in the same place on both sides. Here it sits next to the code
	 * that emits the properties, in the backend module -- which is the maven basedir, so a
	 * bare relative path finds it. In CE the script deliberately writes it to the REPOSITORY
	 * root, because the CycloneDX registry resolves the registered {@code reliza} namespace to
	 * that copy, and the backend module is one level below it.
	 *
	 * <p>Resolved rather than hardcoded to this repo's layout: with a bare path the synced
	 * test fails in CE for a reason that has nothing to do with what it checks, and the
	 * obvious local fix -- editing it in the mirror -- is erased by the next sync. A guard
	 * that cannot survive being copied is not a guard on the thing being copied.
	 */
	private static final Path DOC = Stream.of(Path.of("cyclonedx-taxonomy.md"),
					Path.of("..", "cyclonedx-taxonomy.md"))
			.filter(Files::exists)
			.findFirst()
			.orElse(Path.of("cyclonedx-taxonomy.md"));

	/** Every {@code "reliza:..."} literal anywhere in main sources, with its source file. */
	private static Map<String, String> scanEmittedProperties() throws IOException {
		Map<String, String> found = new TreeMap<>();
		try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Matcher m = RELIZA_PROPERTY.matcher(Files.readString(f));
				while (m.find()) {
					found.putIfAbsent(m.group(1), f.getFileName().toString());
				}
			}
		}
		return found;
	}

	@BeforeEach
	void inputsArePresent() {
		assertTrue(Files.exists(DOC), "taxonomy doc not found at " + DOC.toAbsolutePath()
				+ " (looked in the backend module and in the repository root) -- it is the"
				+ " source of truth here and is carried to the CE mirror's repository ROOT by"
				+ " copy-src.sh");
		assertTrue(Files.isDirectory(MAIN_SOURCES), "main sources not found at " + MAIN_SOURCES
				+ " -- run this with the backend module directory as the working directory");
	}

	@Test
	void everyEmittedRelizaPropertyIsDocumented() throws IOException {
		String doc = Files.readString(DOC);

		List<String> undocumented = new ArrayList<>();
		scanEmittedProperties().forEach((property, file) -> {
			if (!doc.contains(property)) {
				undocumented.add(property + " (in " + file + ")");
			}
		});

		assertTrue(undocumented.isEmpty(),
				"These properties are emitted into the REGISTERED reliza CycloneDX namespace"
						+ " but are not defined in cyclonedx-taxonomy.md. Define them in the same"
						+ " change that emits them: " + undocumented);
	}

	/**
	 * The doc must not define a support/device property that nothing emits. A stale
	 * definition in a registered namespace is its own failure -- a consumer writes code
	 * against a key it will never see.
	 *
	 * <p>Scoped to {@code reliza:support:} and {@code reliza:device:} on purpose. The document
	 * also defines older namespaces ({@code rearmImport}, {@code devops},
	 * {@code componentMetadata}) whose producers are not in this file set, and asserting over
	 * those would fail for a reason this test has no business judging.
	 */
	@Test
	void theDocDefinesNoPropertyThatNothingEmits() throws IOException {
		String doc = Files.readString(DOC);
		Set<String> emitted = scanEmittedProperties().keySet();

		List<String> orphans = new ArrayList<>();
		Matcher m = Pattern.compile("`(reliza:(?:support|device):[A-Za-z:]+)(?:<[a-z]+>)?`")
				.matcher(doc);
		while (m.find()) {
			String documented = m.group(1);
			// DIRECTION MATTERS, and getting it backwards is what made this check vacuous:
			// asking whether a documented name starts with an emitted one always succeeded,
			// because every documented name starts with the bare namespace prefix constant.
			//
			// Ask instead whether SOME EMITTED literal is at or under the documented name.
			// That covers the exact case, the parameterised form ("...:source:<milestone>"
			// is covered by the emitted prefix "...:source:"), and the historical unsuffixed
			// form the applicability note documents -- while a name nothing emits is caught.
			boolean covered = emitted.stream().anyMatch(e -> e.startsWith(documented));
			if (!covered) {
				orphans.add(documented);
			}
		}
		assertTrue(orphans.isEmpty(),
				"cyclonedx-taxonomy.md defines properties nothing emits, so a consumer could"
						+ " write code against a key it will never see: " + orphans);
	}
}
