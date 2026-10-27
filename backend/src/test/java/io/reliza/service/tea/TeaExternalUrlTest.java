/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.reliza.service.tea.TeaExternalUrl.Problem;

/**
 * Task TEA-19, design 5.1 rows 1 to 25: the rule a url the TEA surface hands out must pass, as one pure function
 * whose checks run in a fixed order and answer the first that fails.
 */
class TeaExternalUrlTest {

	private static final String BASE = "https://files.example/";

	static Stream<Arguments> rows() {
		return Stream.of(
				// 1 to 9: admitted
				Arguments.of("1", "https://files.example/x.md", null),
				Arguments.of("2", "http://files.example/x.md", null),
				Arguments.of("3", "HTTPS://FILES.EXAMPLE/x.md", null),
				Arguments.of("4", "https://files.example/x.md?v=1&b=%2B#frag", null),
				Arguments.of("5", "https://files.example/x?#", null),
				Arguments.of("6", "https://files.example", null),
				Arguments.of("7 ipv6", "https://[::1]:8443/x", null),
				Arguments.of("7 port", "https://files.example:8443/x", null),
				Arguments.of("8 encoded", "https://files.example/na%C3%AFve.md", null),
				Arguments.of("8 punycode", "https://xn--r8jz45g.example/x", null),
				Arguments.of("9 4096", BASE + "a".repeat(4074), null),
				// 10: one octet over
				Arguments.of("10 4097", BASE + "a".repeat(4075), Problem.TOO_LONG),
				// 10, the order (decision 1): an over-length url failing an earlier check is named by that check
				Arguments.of("10 over and not ASCII", BASE + "\u00E9".repeat(4075), Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("10 over and a space", BASE + "a".repeat(4075) + " ", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("10 over and not a URI", BASE + "a".repeat(4075) + "|", Problem.NOT_A_URI),
				Arguments.of("10 over and relative", "/" + "a".repeat(4096), Problem.NO_SCHEME),
				Arguments.of("10 over and ftp", "ftp://files.example/" + "a".repeat(4077), Problem.SCHEME_NOT_HTTP),
				Arguments.of("10 over and user", "https://user@files.example/" + "a".repeat(4070), Problem.USERINFO),
				Arguments.of("10 over and no host", "https:///" + "a".repeat(4088), Problem.NO_HOST),
				// 11: relative, scheme-relative, bare host
				Arguments.of("11 absolute path", "/x.md", Problem.NO_SCHEME),
				Arguments.of("11 relative path", "x.md", Problem.NO_SCHEME),
				Arguments.of("11 scheme-relative", "//files.example/x.md", Problem.NO_SCHEME),
				Arguments.of("11 bare host", "files.example/x.md", Problem.NO_SCHEME),
				// 12: other schemes
				Arguments.of("12 ftp", "ftp://files.example/x.md", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 file", "file:///etc/passwd", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 javascript", "javascript:alert(1)", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 data", "data:text/plain,hi", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 mailto", "mailto:a@b", Problem.SCHEME_NOT_HTTP),
				// 12, a scheme that only starts with http is not http (equalsIgnoreCase, never a prefix test)
				Arguments.of("12 httpx", "httpx://files.example/x", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 https-evil", "https-evil://files.example/x", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 HTTPSX", "HTTPSX://files.example/x", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 http+unix", "http+unix://files.example/x", Problem.SCHEME_NOT_HTTP),
				Arguments.of("12 htt", "htt://files.example/x", Problem.SCHEME_NOT_HTTP),
				// 13: user information
				Arguments.of("13 user and password", "https://user:pw@files.example/x", Problem.USERINFO),
				Arguments.of("13 user", "https://user@files.example/x", Problem.USERINFO),
				// 14: no host
				Arguments.of("14 empty authority", "https:///x.md", Problem.NO_HOST),
				Arguments.of("14 opaque", "http:x.md", Problem.NO_HOST),
				Arguments.of("14 underscore", "https://my_host/x", Problem.NO_HOST),
				Arguments.of("14 port", "https://files.example:abc/x", Problem.NO_HOST),
				// 15 and 16: not a URI
				Arguments.of("15", "https://", Problem.NOT_A_URI),
				Arguments.of("16 escape", "https://files.example/%zz", Problem.NOT_A_URI),
				Arguments.of("16 angle", "https://files.example/<x>", Problem.NOT_A_URI),
				Arguments.of("16 pipe", "https://files.example/x|y", Problem.NOT_A_URI),
				Arguments.of("16 brace", "https://files.example/x{y}", Problem.NOT_A_URI),
				Arguments.of("16 backslash", "https://files.example/\\x", Problem.NOT_A_URI),
				// 17 to 22: outside printable US-ASCII
				Arguments.of("17 inner space", "https://files.example/a b", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("17 leading space", " https://files.example/x", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("17 trailing space", "https://files.example/x ", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("18 CR LF", "https://files.example/x\r\nSet-Cookie: a", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("19 tab", "https://files.example/x\t.md", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("19 DEL", "https://files.example/x\u007f", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("20 i diaeresis", "https://files.example/na\u00EFve.md", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("20 e acute", "https://files.example/x\u00E9", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("20 line separator", "https://files.example/x\u2028y", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("20 bidi override", "https://files.example/x\u202Ey", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("20 zero-width space", "https://files.example/x\u200By", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("20 emoji", "https://files.example/x\uD83D\uDE00.md", Problem.NOT_PRINTABLE_ASCII),
				Arguments.of("21 C1", "https://files.example/x\u0085y", Problem.NOT_PRINTABLE_ASCII),
				// 22: the ordinary branch; TPF-47 refuses U+0000 at input, so the rule has no case of its own for it
				Arguments.of("22 NUL", "https://files.example/x\u0000.md", Problem.NOT_PRINTABLE_ASCII),
				// 23
				Arguments.of("23 null", null, Problem.EMPTY),
				Arguments.of("23 empty", "", Problem.EMPTY));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rows")
	void problemOfAnswersTheFirstFailingCheck(String row, String url, Problem expected) {
		assertEquals(Optional.ofNullable(expected), TeaExternalUrl.problemOf(url), row);
	}

	static Stream<Arguments> indexRows() {
		return Stream.of(
				Arguments.of("https://files.example/a b", "at index 23 (U+0020)"),
				Arguments.of(" https://files.example/x", "at index 0 (U+0020)"),
				Arguments.of("https://files.example/x ", "at index 23 (U+0020)"),
				Arguments.of("https://files.example/x\r\nSet-Cookie: a", "at index 23 (U+000D)"),
				Arguments.of("https://files.example/x\u0000.md", "at index 23 (U+0000)"));
	}

	/** Rows 17, 18 and 22: the detail names the UTF-16 index and the code point. */
	@ParameterizedTest
	@MethodSource("indexRows")
	void theDetailNamesTheIndexAndTheCodePoint(String url, String expected) {
		String d = TeaExternalUrl.describe(url, Problem.NOT_PRINTABLE_ASCII);
		assertTrue(d.contains(": has a character outside printable US-ASCII " + expected + "; percent-encode it"), d);
	}

	/** Row 20: a supplementary character is one code point, named as itself at the index of its first unit. */
	@Test
	void anEmojiIsNamedByItsCodePointNotItsLeadSurrogate() {
		assertEquals("download link https://files.example/x??.md: has a character outside printable US-ASCII at index 23 "
				+ "(U+1F600); percent-encode it",
				TeaExternalUrl.describe("https://files.example/x\uD83D\uDE00.md", Problem.NOT_PRINTABLE_ASCII));
	}

	/** Row 24: the url inside the detail goes through LogSafe.text (controls as ?, cut at 256). */
	@Test
	void theDetailIsBounded() {
		assertEquals("download link https://files.example/x??Set-Cookie: a: has a character outside printable US-ASCII "
				+ "at index 23 (U+000D); percent-encode it",
				TeaExternalUrl.describe("https://files.example/x\r\nSet-Cookie: a", Problem.NOT_PRINTABLE_ASCII));
		String long300 = BASE + "b".repeat(300 - BASE.length());
		assertEquals(300, long300.length());
		assertEquals("download link " + long300.substring(0, 256) + "...: is relative; an absolute http or https url is required",
				TeaExternalUrl.describe(long300, Problem.NO_SCHEME));
		String over = BASE + "a".repeat(4075);
		assertTrue(TeaExternalUrl.describe(over, TeaExternalUrl.problemOf(over).orElseThrow())
				.endsWith("is 4097 octets, over the 4096 this server publishes"));
	}

	/**
	 * Row 10 and decision 1: the length is measured last, on a printable-ASCII string, so a 4097-character url of
	 * 8172 UTF-8 octets is named by its first character outside printable US-ASCII, never by a wrong octet count.
	 */
	@Test
	void anOverLengthNonAsciiUrlIsNamedByTheAsciiScan() {
		String url = BASE + "\u00E9".repeat(4075);
		assertEquals(4097, url.length());
		Problem p = TeaExternalUrl.problemOf(url).orElseThrow();
		assertEquals(Problem.NOT_PRINTABLE_ASCII, p);
		String d = TeaExternalUrl.describe(url, p);
		assertTrue(d.endsWith(": has a character outside printable US-ASCII at index 22 (U+00E9); percent-encode it"), d);
		assertFalse(d.contains("octets"), d);
	}

	/** The fixed text of every problem, the only words describe adds beside the url. */
	@Test
	void everyProblemHasItsFixedText() {
		assertEquals("download link -: is empty", TeaExternalUrl.describe(null, Problem.EMPTY));
		assertEquals("download link https://: is not an RFC 3986 URI", TeaExternalUrl.describe("https://", Problem.NOT_A_URI));
		assertEquals("download link /x.md: is relative; an absolute http or https url is required",
				TeaExternalUrl.describe("/x.md", Problem.NO_SCHEME));
		assertEquals("download link javascript:alert(1): has a scheme other than http or https",
				TeaExternalUrl.describe("javascript:alert(1)", Problem.SCHEME_NOT_HTTP));
		assertEquals("download link https://user@files.example/x: carries user information before the host, which is "
				+ "never published", TeaExternalUrl.describe("https://user@files.example/x", Problem.USERINFO));
		assertEquals("download link https:///x.md: has no host", TeaExternalUrl.describe("https:///x.md", Problem.NO_HOST));
	}

	/** Row 25: one bound; TeaArtifactResponses.MAX_LOCATION_OCTETS joins it when TEA-10 (PR 802) is on the branch. */
	@Test
	void theBoundIs4096() {
		assertEquals(4096, TeaExternalUrl.MAX_OCTETS);
	}
}
