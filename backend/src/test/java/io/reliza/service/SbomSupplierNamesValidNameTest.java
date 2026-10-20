/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/**
 * SCORE-41 T-15 (T-4, ADR-1 and ADR-3 of round 2): the supplier name a component stores is checked
 * over code points, so a format character outside the BMP is refused like one inside it.
 */
class SbomSupplierNamesValidNameTest {

	private static final String REFUSED = "supplierName invalid: must not contain HTML or control characters";

	private static String around(int codePoint) {
		return "Acme" + new String(Character.toChars(codePoint)) + "Corp";
	}

	@Test
	void everyFormatCodePointIsRefused() {
		List<String> accepted = new ArrayList<>();
		int seen = 0;
		for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
			if (Character.FORMAT != Character.getType(cp)) continue;
			seen++;
			try {
				SbomSupplierNames.validName(around(cp));
				accepted.add(Integer.toHexString(cp));
			} catch (RelizaException e) {
				assertEquals(REFUSED, e.getMessage());
			}
		}
		assertEquals(List.of(), accepted);
		// the BMP alone has fewer than 50; the tag characters alone are 97
		assertTrue(seen > 150, String.valueOf(seen));
	}

	@Test
	void loneSurrogatesAreRefused() {
		for (String name : new String[] {"Acme\uD800Corp", "Acme\uDC00Corp", "Acme Corp\uD83D"}) {
			RelizaException e = assertThrows(RelizaException.class, () -> SbomSupplierNames.validName(name));
			assertEquals(REFUSED, e.getMessage());
		}
	}

	@Test
	void aValidPairOutsideTheBmpIsAccepted() throws Exception {
		String emoji = "Acme" + new String(Character.toChars(0x1F600)) + " Corp";
		assertEquals(emoji, SbomSupplierNames.validName(emoji));
		assertEquals("Acme\u00a0Corp \u2003M\u00fcller", SbomSupplierNames.validName(" Acme\u00a0Corp \u2003M\u00fcller "));
		assertNull(SbomSupplierNames.validName("   "));
	}
}
