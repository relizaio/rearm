/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.reliza.model.JustifiedUnknownReason;

/**
 * FDAW-13 T-12: the reason codes of the two justified-unknown rows in cyclonedx-taxonomy.md are
 * exactly {@link JustifiedUnknownReason}'s names, in the enum's order, so the published list and
 * the enum ReARM writes from cannot drift. The CLI's fda profile matches these names exactly, so
 * the order of the six is fixed here too (FDAW-1 D5).
 */
class JustifiedUnknownReasonTaxonomyTest {

	/** Same resolution as {@link CyclonedxTaxonomyDocSyncTest}: backend module here, repository root in CE. */
	private static final Path DOC = Stream.of(Path.of("cyclonedx-taxonomy.md"),
					Path.of("..", "cyclonedx-taxonomy.md"))
			.filter(Files::exists)
			.findFirst()
			.orElse(Path.of("cyclonedx-taxonomy.md"));

	private static final List<String> D5_NAMES = List.of("NO_COMMITTED_END_OF_SUPPORT", "COMMUNITY_PROJECT",
			"SUPPLIER_PUBLISHES_NO_DATE", "DISTRIBUTION_PUBLISHES_NO_HASH", "VENDORED_SOURCE_NO_HASH", "OTHER");

	@Test
	void theEnumHoldsTheSixNamesOfD5InOrder() {
		assertEquals(D5_NAMES, Arrays.stream(JustifiedUnknownReason.values()).map(Enum::name).toList());
	}

	@Test
	void everyCodeTheTwoRowsListResolvesAndTheyListTheWholeEnum() throws IOException {
		List<String> rows = Files.readAllLines(DOC).stream()
				.filter(l -> l.startsWith("| `reliza:support:justifiedUnknown:"))
				.toList();
		assertEquals(2, rows.size(), "the two justified-unknown rows of cyclonedx-taxonomy.md: " + rows);
		Pattern code = Pattern.compile("`([A-Z][A-Z_]+)`");
		for (String row : rows) {
			List<String> codes = new ArrayList<>();
			Matcher m = code.matcher(row);
			while (m.find()) {
				codes.add(m.group(1));
			}
			for (String c : codes) {
				assertDoesNotThrow(() -> JustifiedUnknownReason.valueOf(c), "the row lists " + c + ", not a JustifiedUnknownReason: " + row);
			}
			assertEquals(D5_NAMES, codes, "the row lists every reason code once, in the enum's order: " + row);
			assertTrue(row.contains("`JustifiedUnknownReason`"), "the row names the enum: " + row);
		}
	}
}
