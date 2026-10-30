/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import io.reliza.exceptions.InvalidInputException;

/**
 * The walk of {@link NulInputInstrumentation#refuse} over a field's coerced arguments and of
 * {@link NulInputInstrumentation#refuseDecodedJson} and {@link NulInputInstrumentation#refuseDecodedCycloneDxJson}
 * over a String argument parsed as a JSON document (task TPF-47, architecture-7 section 4 item 1): the first value
 * holding U+0000 is refused by its path, a map key through LogSafe, nothing else is touched, the walk is linear in
 * the input with the path rendered only on a refusal, and the CycloneDX site reads what the import's reader reads.
 */
class NulInputInstrumentationTest {

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static InvalidInputException refused(Map<String, Object> arguments) {
		return assertThrows(InvalidInputException.class, () -> NulInputInstrumentation.refuse(arguments));
	}

	private static InvalidInputException refusedDecoded(String argumentPath, String json) {
		return assertThrows(InvalidInputException.class,
				() -> NulInputInstrumentation.refuseDecodedJson(argumentPath, json));
	}

	private static InvalidInputException refusedCdx(String cdxJson) {
		return assertThrows(InvalidInputException.class,
				() -> NulInputInstrumentation.refuseDecodedCycloneDxJson("cdxJson", cdxJson));
	}

	private static final String CDX_VERSION_REFUSAL = "cdxJson.components[0].version: must not contain U+0000";

	/** Row 13's document: the JSON escape of a NUL in the first component's version. */
	private static final String D = "{\"bomFormat\":\"CycloneDX\",\"components\":[{\"name\":\"ok\",\"version\":\"1.0\\u0000\"}]}";

	private static final Duration BOUND = Duration.ofSeconds(5);

	// item 1 row 1
	@Test
	void theReproNamesTheLinkUri() {
		InvalidInputException e = refused(map("artifactInput", map("release", UUID.randomUUID().toString(),
				"artifact", map("displayIdentifier", "x",
						"downloadLinks", List.of(map("uri", "https://example.com/x\u0000.md"))))));
		assertEquals("artifactInput.artifact.downloadLinks[0].uri: must not contain U+0000", e.getMessage());
		assertNull(e.getCause());
	}

	// item 1 row 2
	@Test
	void aKeyHoldingNulIsRenderedThroughLogSafe() {
		Map<String, Object> tags = new LinkedHashMap<>();
		tags.put("a\u0000b", "v");
		InvalidInputException e = refused(map("tags", tags));
		assertEquals("tags.a?b: must not contain U+0000", e.getMessage());
	}

	// item 1 row 3
	@Test
	void theFirstOffendingValueInArgumentOrderIsNamed() {
		InvalidInputException e = refused(map("a", "ok", "b", List.of("x", "y\u0000"), "c", "\u0000"));
		assertEquals("b[1]: must not contain U+0000", e.getMessage());
	}

	// item 1 row 4
	@Test
	void everythingElseIsLeftAlone() {
		MultipartFile f = mock(MultipartFile.class);
		Map<String, Object> arguments = map("s", "tab\there", "t", "\u0001\u001f\u007f", "u", "é✓", "n", 5,
				"b", true, "id", UUID.randomUUID(), "d", OffsetDateTime.now(), "f", f, "nil", null,
				"e", Map.of(), "l", List.of("ok"));
		assertDoesNotThrow(() -> NulInputInstrumentation.refuse(arguments));
		verifyNoInteractions(f);
	}

	// item 1 row 5
	@Test
	void aDecodedValueIsNamedUnderTheArgumentPath() {
		InvalidInputException e = refusedDecoded("input.filter.presetConfigJson", "{\"k\":\"a\\u0000b\"}");
		assertEquals("input.filter.presetConfigJson.k: must not contain U+0000", e.getMessage());
		assertNull(e.getCause());
	}

	// item 1 row 6
	@Test
	void aDecodedKeyIsRenderedThroughLogSafe() {
		InvalidInputException e = refusedDecoded("input.filter.presetConfigJson", "{\"a\\u0000b\":\"ok\"}");
		assertEquals("input.filter.presetConfigJson.a?b: must not contain U+0000", e.getMessage());
	}

	// item 1 row 7
	@Test
	void aDecodedValueNestedInAListIsNamedInDocumentOrder() {
		InvalidInputException e = refusedDecoded("cdxJson", "{\"bomFormat\":\"CycloneDX\",\"components\":["
				+ "{\"name\":\"ok\",\"version\":\"1.0\\u0000\"},{\"name\":\"x\\u0000\"}]}");
		assertEquals("cdxJson.components[0].version: must not contain U+0000", e.getMessage());
	}

	// item 1 row 8
	@Test
	void theSixCharactersWrittenOutAreTextNotNul() {
		assertDoesNotThrow(() -> NulInputInstrumentation.refuseDecodedJson("cdxJson", "{\"name\":\"a\\\\u0000b\"}"));
	}

	// item 1 row 9
	@Test
	void textThatIsNotJsonNullAndBlankAreLeftAlone() {
		assertDoesNotThrow(() -> NulInputInstrumentation.refuseDecodedJson("elements", "{"));
		assertDoesNotThrow(() -> NulInputInstrumentation.refuseDecodedJson("elements", "a\u0000"));
		assertDoesNotThrow(() -> NulInputInstrumentation.refuseDecodedJson("elements", null));
		assertDoesNotThrow(() -> NulInputInstrumentation.refuseDecodedJson("elements", " "));
	}

	// item 1 row 10
	@Test
	void aKeyAtThePerNameLimitOverManyListElementsIsWalkedInLinearTime() {
		String key = "k".repeat(50_000);
		Map<String, Object> clean = map("a", map(key, Collections.nCopies(4_000_000, "ok")));
		assertTimeoutPreemptively(BOUND, () -> NulInputInstrumentation.refuse(clean));

		List<String> last = new ArrayList<>(Collections.nCopies(3_999_999, "ok"));
		last.add("\u0000");
		Map<String, Object> offending = map("a", map(key, last));
		InvalidInputException e = assertTimeoutPreemptively(BOUND, () -> refused(offending));
		assertEquals("a." + "k".repeat(254) + "...: must not contain U+0000", e.getMessage());
		assertNull(e.getCause());
	}

	// item 1 row 11
	@Test
	void aChainOfLongKeysIsWalkedInLinearTime() {
		String key = "n".repeat(50_000);
		Map<String, Object> clean = chain(key, "ok");
		assertTimeoutPreemptively(BOUND, () -> NulInputInstrumentation.refuse(map("d", clean)));

		Map<String, Object> offending = chain(key, "\u0000");
		InvalidInputException e = assertTimeoutPreemptively(BOUND, () -> refused(map("d", offending)));
		assertEquals("d." + "n".repeat(254) + "...: must not contain U+0000", e.getMessage());
		assertNull(e.getCause());
	}

	/** 500 nested maps (Jackson's DEFAULT_MAX_DEPTH), each holding the one entry key, built from the inside out. */
	private static Map<String, Object> chain(String key, String innermost) {
		Map<String, Object> v = Map.of(key, innermost);
		for (int i = 0; i < 499; i++) v = map(key, v);
		return v;
	}

	// item 1 row 12
	@Test
	void theCutIsLogSafesAndNotLower() {
		assertEquals("a".repeat(256) + ": must not contain U+0000",
				refused(map("a".repeat(256), "\u0000")).getMessage());
		assertEquals("a".repeat(256) + "...: must not contain U+0000",
				refused(map("a".repeat(257), "\u0000")).getMessage());
		assertEquals("p." + "q".repeat(254) + "...: must not contain U+0000",
				refused(map("p", map("q".repeat(300), map("r", "\u0000")))).getMessage());
	}

	// item 1 row 12, round 6 (T-4): paths of short segments, so a cut lower than LogSafe's shows
	@Test
	void aPathOfShortSegmentsIsRenderedWholeUpToLogSafesCut() {
		assertEquals("a" + ".kk".repeat(60) + ": must not contain U+0000",
				refused(map("a", nestedKeys(60))).getMessage());
		assertEquals("a" + ".kk".repeat(85) + "...: must not contain U+0000",
				refused(map("a", nestedKeys(100))).getMessage());
		Object v = "\u0000";
		for (int i = 0; i < 90; i++) v = List.of(v);
		assertEquals("l" + "[0]".repeat(85) + "...: must not contain U+0000", refused(map("l", v)).getMessage());
	}

	/** n nested maps, each holding the one key kk, the innermost value a NUL, built from the inside out. */
	private static Map<String, Object> nestedKeys(int n) {
		Map<String, Object> v = map("kk", "\u0000");
		for (int i = 1; i < n; i++) v = map("kk", v);
		return v;
	}

	// item 1 row 13 (T-3, decision 11)
	@Test
	void theCycloneDxSiteReadsTheFourFramingsTheImportReads() {
		for (String framed : List.of(D + " x", D + "{}", D + "}", "\uFEFF" + D, D)) {
			InvalidInputException e = refusedCdx(framed);
			assertEquals(CDX_VERSION_REFUSAL, e.getMessage());
			assertNull(e.getCause());
		}
	}

	// item 1 row 14
	@Test
	void whatTheImportCannotReadIsLeftAloneAndTheWrittenOutEscapeIsText() {
		for (String text : Arrays.asList("/* c */" + D, "{", null, " ", "{\"name\":\"a\\\\u0000b\"}")) {
			assertDoesNotThrow(() -> NulInputInstrumentation.refuseDecodedCycloneDxJson("cdxJson", text));
		}
	}

	// item 1 row 15
	@Test
	void theDepthBoundAtTheCycloneDxSiteIsTheImports() {
		InvalidInputException e = refusedCdx(nestedComponents(250));
		assertEquals("cdxJson" + ".components[0]".repeat(17) + ".components...: must not contain U+0000",
				e.getMessage());
		assertNull(e.getCause());
		assertDoesNotThrow(() -> NulInputInstrumentation.refuseDecodedCycloneDxJson("cdxJson", nestedComponents(500)));
	}

	/** A BOM of l nested components, JSON depth 2l + 1, the innermost version holding the JSON escape of a NUL. */
	private static String nestedComponents(int l) {
		return "{\"bomFormat\":\"CycloneDX\"," + "\"components\":[{\"name\":\"c\",".repeat(l)
				+ "\"version\":\"1.0\\u0000\"" + "}]".repeat(l) + "}";
	}
}
