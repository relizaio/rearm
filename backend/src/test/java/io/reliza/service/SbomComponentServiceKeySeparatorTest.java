/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * TPF-128: the merge keys built in SbomComponentService keep U+0000 as their separator
 * after the raw NUL bytes in the source were replaced by the unicode escape.
 */
class SbomComponentServiceKeySeparatorTest {

	private static final String ARTIFACT = "2f1c6a0e-9b7d-4c3e-8a51-7d0e4b6f9a12";

	@Test
	void parentEdgeKeyKeepsNulSeparator() {
		String key = SbomComponentService.parentEdgeKey("pkg:maven/a/b@1", "DEPENDS_ON");
		assertEquals("pkg:maven/a/b@1\u0000DEPENDS_ON", key);
		assertEquals(0, key.charAt(15));
		assertEquals(1L, key.chars().filter(c -> c == 0).count());
		assertEquals(0, key.getBytes(StandardCharsets.UTF_8)[15]);
	}

	@Test
	void declarationKeyKeepsNulSeparators() {
		String key = SbomComponentService.declarationKey(Map.of(
				"artifact", ARTIFACT,
				"sourceExactPurl", "pkg:s",
				"targetExactPurl", "pkg:t"));
		assertEquals(ARTIFACT + "\u0000pkg:s\u0000pkg:t", key);
		int first = ARTIFACT.length();
		int second = first + 1 + "pkg:s".length();
		assertEquals(0, key.charAt(first));
		assertEquals(0, key.charAt(second));
		assertEquals(2L, key.chars().filter(c -> c < 0x20).count());
	}

	@Test
	void declarationKeyDistinguishesPartsThatConcatenateEqually() {
		String left = SbomComponentService.declarationKey(Map.of(
				"artifact", "a", "sourceExactPurl", "bc", "targetExactPurl", "d"));
		String right = SbomComponentService.declarationKey(Map.of(
				"artifact", "ab", "sourceExactPurl", "c", "targetExactPurl", "d"));
		assertNotEquals(left, right);
	}
}
