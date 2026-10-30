/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * SCORE-39 T-7 (ADR-2): the declared-licence URL guard. rebom stores what it is given (SCORE-40
 * ADR-7), so this is the only check: an absolute http or https URL with a host, java.net.URI syntax,
 * at most 2048 characters. The six values the SCORE-40 tester stored through rebom are refused.
 */
class LicenseUrlsTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"https://acme.example/licence|https://acme.example/licence",
		"http://x.example/a?b=c#d|http://x.example/a?b=c#d",
		"HTTPS://X.example/|HTTPS://X.example/",
		"'  https://acme.example/licence  '|https://acme.example/licence",
	})
	void acceptedUrlsComeBackTrimmed(String input, String canonical) {
		assertEquals(canonical, LicenseUrls.canonical(input));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"https://x.example/a b|not a valid URL",
		"https://x.example/\"><script>|not a valid URL",
		"not a url|not a valid URL",
		"javascript:alert(1)|scheme must be http or https",
		"file:///etc/passwd|scheme must be http or https",
		"ftp://x.example/l|scheme must be http or https",
		"https://|not a valid URL",
		"//x.example/l|scheme must be http or https",
		"x.example/l|scheme must be http or https",
		// SCORE-41 T-5: URI parses these, the host check refuses them
		"https:x.example/l|host is missing",
		"https:///x|host is missing",
		"http:/x.example/l|host is missing",
	})
	void refusedUrlsGiveTheReason(String input, String reason) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LicenseUrls.canonical(input));
		assertEquals(reason, e.getMessage());
	}

	@Test
	void aNewlineInsideIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> LicenseUrls.canonical("https://x.example/\nb"));
		assertEquals("not a valid URL", e.getMessage());
	}

	@Test
	void theBoundIs2048Characters() {
		String prefix = "https://x.example/";
		String atBound = prefix + "a".repeat(LicenseUrls.MAX_LENGTH - prefix.length());
		assertEquals(atBound, LicenseUrls.canonical(atBound));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LicenseUrls.canonical(atBound + "a"));
		assertEquals("at most 2048 characters", e.getMessage());
	}

	@Test
	void emptyIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LicenseUrls.canonical(""));
		assertEquals("must not be empty", e.getMessage());
	}

	// security review: no user information, printable ASCII only
	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"https://spdx.org@evil.example/MIT|must not carry user information",
		"https://user:pass@acme.example/licence|must not carry user information",
		"https://acme.example/\u202Elicence|must be ASCII: write an international host in punycode and percent-encode the rest",
		"https://acme.example/li\u200Bcence|must be ASCII: write an international host in punycode and percent-encode the rest",
		"https://b\u00fccher.example/l|must be ASCII: write an international host in punycode and percent-encode the rest",
	})
	void userInformationAndNonAsciiAreRefused(String input, String reason) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LicenseUrls.canonical(input));
		assertEquals(reason, e.getMessage());
	}

	@Test
	void aPunycodeHostAndPercentEncodingAreAccepted() {
		assertEquals("https://xn--bcher-kva.example/l%C3%BC", LicenseUrls.canonical("https://xn--bcher-kva.example/l%C3%BC"));
	}
}
