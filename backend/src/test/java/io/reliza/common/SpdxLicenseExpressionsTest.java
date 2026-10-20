/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SCORE-39 T-7 (ADR-2): the declared-licence expression validator. Accepted values come back in
 * canonical form (list casing, one space between tokens, parentheses as written); refused values
 * throw with the reason, naming the token where there is one.
 */
class SpdxLicenseExpressionsTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"Apache-2.0|Apache-2.0",
		"apache-2.0|Apache-2.0",
		"GPL-2.0-or-later|GPL-2.0-or-later",
		"GPL-2.0+|GPL-2.0+",
		"MIT OR Apache-2.0|MIT OR Apache-2.0",
		"(MIT AND BSD-3-Clause) OR GPL-2.0-only|(MIT AND BSD-3-Clause) OR GPL-2.0-only",
		"GPL-2.0-only WITH Classpath-exception-2.0|GPL-2.0-only WITH Classpath-exception-2.0",
		"MIT WITH Unknown-exception-1.0|MIT WITH Unknown-exception-1.0",
		"LicenseRef-Acme-Commercial|LicenseRef-Acme-Commercial",
		"LicenseRef-Acme.1-2 OR MIT|LicenseRef-Acme.1-2 OR MIT",
		"MIT  OR   Apache-2.0|MIT OR Apache-2.0",
		"'  MIT  '|MIT",
		"MIT AND GPL-2.0-only OR BSD-3-Clause|MIT AND GPL-2.0-only OR BSD-3-Clause",
		"GPL-2.0+ WITH Classpath-exception-2.0|GPL-2.0+ WITH Classpath-exception-2.0",
		"( mit AND  bsd-3-clause )|(MIT AND BSD-3-Clause)",
		// SCORE-41 T-3: deprecated licence ids whose names carry 'exception' are licence ids
		"GPL-2.0-with-classpath-exception|GPL-2.0-with-classpath-exception",
		"GPL-2.0-with-autoconf-exception|GPL-2.0-with-autoconf-exception",
		"gpl-2.0+|GPL-2.0+",
	})
	void acceptedValuesComeBackCanonical(String input, String canonical) {
		assertEquals(canonical, SpdxLicenseExpressions.canonical(input));
	}

	@Test
	void tabsAndNewlinesCollapseLikeSpaces() {
		assertEquals("MIT OR Apache-2.0", SpdxLicenseExpressions.canonical("\tMIT\nOR\r\nApache-2.0 "));
	}

	@ParameterizedTest
	@ValueSource(strings = {"NOASSERTION", "NONE", "none", "noassertion", "MIT OR NONE"})
	void noassertionAndNoneAreRefusedWithTheMessage(String input) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SpdxLicenseExpressions.canonical(input));
		assertEquals("NOASSERTION and NONE are not a declared licence; leave the field empty instead", e.getMessage());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', value = {
		"Apache-2|unknown licence id \"Apache-2\"",
		"Apache License 2.0|unknown licence id \"Apache\"",
		"mit or apache-2.0|unexpected token \"or\"",
		"MIT OR|expected a licence after \"OR\"",
		"OR MIT|unexpected token \"OR\"",
		"(MIT|unbalanced parenthesis: missing ')'",
		"MIT)|unexpected token \")\"",
		"()|unexpected token \")\"",
		"LicenseRef-|invalid LicenseRef \"LicenseRef-\": LicenseRef- must be followed by letters, digits, dots or hyphens",
		"LicenseRef-Acme Commercial|unexpected token \"Commercial\"",
		"licenseref-acme|unknown licence id \"licenseref-acme\"",
		"LicenseRef-a_b|invalid LicenseRef \"LicenseRef-a_b\": LicenseRef- must be followed by letters, digits, dots or hyphens",
		"DocumentRef-x:LicenseRef-y|DocumentRef is not supported: \"DocumentRef-x:LicenseRef-y\"",
		"MIT WITH|missing exception after WITH",
		"MIT WITH OR Apache-2.0|missing exception after WITH",
		"(MIT) WITH Classpath-exception-2.0|unexpected token \"WITH\"",
		"MIT Apache-2.0|unexpected token \"Apache-2.0\"",
		// SCORE-41 T-5: the exception after WITH is an idstring
		"MIT WITH a_b|missing exception after WITH",
		"MIT WITH <x>|missing exception after WITH",
		"MIT WITH (x)|missing exception after WITH",
	})
	void refusedValuesNameTheToken(String input, String reason) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SpdxLicenseExpressions.canonical(input));
		assertEquals(reason, e.getMessage());
	}

	/**
	 * SCORE-41 T-3: an SPDX exception id is no licence id, although the CycloneDX list holds both;
	 * and a '+' is taken once, so an id that is on the list with its own '+' takes no second one.
	 */
	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', value = {
		"Classpath-exception-2.0|unknown licence id \"Classpath-exception-2.0\"",
		"classpath-exception-2.0|unknown licence id \"classpath-exception-2.0\"",
		"MIT OR Classpath-exception-2.0|unknown licence id \"Classpath-exception-2.0\"",
		"LLGPL|unknown licence id \"LLGPL\"",
		"SHL-2.0|unknown licence id \"SHL-2.0\"",
		"SHL-2.1|unknown licence id \"SHL-2.1\"",
		"Linux-syscall-note|unknown licence id \"Linux-syscall-note\"",
		"GPL-CC-1.0|unknown licence id \"GPL-CC-1.0\"",
		"Classpath-exception-2.0+|unknown licence id \"Classpath-exception-2.0+\"",
		"GPL-2.0++|unknown licence id \"GPL-2.0++\"",
		"LGPL-2.1++|unknown licence id \"LGPL-2.1++\"",
		"gpl-2.0++|unknown licence id \"gpl-2.0++\"",
	})
	void exceptionIdsAndADoubledPlusAreNoLicenceId(String input, String reason) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SpdxLicenseExpressions.canonical(input));
		assertEquals(reason, e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   "})
	void emptyAndBlankAreRefused(String input) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SpdxLicenseExpressions.canonical(input));
		assertEquals("must not be empty", e.getMessage());
	}

	@Test
	void theBoundIs512CharactersAfterTrimming() {
		String atBound = "LicenseRef-" + "a".repeat(SpdxLicenseExpressions.MAX_LENGTH - "LicenseRef-".length());
		assertEquals(512, atBound.length());
		assertEquals(atBound, SpdxLicenseExpressions.canonical("  " + atBound + "  "));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SpdxLicenseExpressions.canonical(atBound + "a"));
		assertEquals("at most 512 characters", e.getMessage());
	}

	@Test
	void aLongRefusedTokenIsShortenedInTheReason() {
		String token = "x".repeat(100);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SpdxLicenseExpressions.canonical(token));
		assertEquals("unknown licence id \"" + "x".repeat(64) + "...\"", e.getMessage());
	}

	@Test
	void singleLicenseRef() {
		assertTrue(SpdxLicenseExpressions.isSingleLicenseRef("LicenseRef-Acme-Commercial"));
		assertFalse(SpdxLicenseExpressions.isSingleLicenseRef("LicenseRef-A+"));
		assertFalse(SpdxLicenseExpressions.isSingleLicenseRef("(LicenseRef-A)"));
		assertFalse(SpdxLicenseExpressions.isSingleLicenseRef("LicenseRef-A OR MIT"));
		assertFalse(SpdxLicenseExpressions.isSingleLicenseRef("MIT"));
		assertFalse(SpdxLicenseExpressions.isSingleLicenseRef(null));
	}

	// security review: the bound holds for the canonical form, which can be longer than the input
	@Test
	void aCanonicalFormLongerThanTheBoundIsRefused() {
		String input = "(MIT)OR(MIT)" + "OR(MIT)".repeat(70);
		assertTrue(input.length() <= SpdxLicenseExpressions.MAX_LENGTH, String.valueOf(input.length()));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SpdxLicenseExpressions.canonical(input));
		assertEquals("at most 512 characters", e.getMessage());
	}
}
