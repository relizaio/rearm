/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.common.LogSafe;

/**
 * Task TEA-21, design section 5 rows 1 to 5: the name of the first pair of a raw query string that Tomcat's
 * decoding rules refuse, found over the raw text.
 */
class TeaQueryStringsTest {

	private static Optional<String> name(String q) {
		return TeaQueryStrings.undecodableName(q);
	}

	// 1
	@Test
	void theFirstFailingPairIsNamed() {
		assertEquals(Optional.of("idValue"), name("idValue=%C3%28"));
		assertEquals(Optional.of("mediaType"), name("a=1&mediaType=%C3%28"));
		assertEquals(Optional.of("mediaType"), name("mediaType=%C3%28&idValue=%C3%28"));
	}

	// 2
	@Test
	void aNameThatIsItselfTheOffenderIsDecodedWithReplacement() {
		Optional<String> n = name("%C3%28=x");
		assertEquals("\uFFFD(", n.orElseThrow());
		assertEquals("?(", LogSafe.text(n.get()));
	}

	// 3: the bad percent sequences, at unit level only (F-3)
	@ParameterizedTest
	@ValueSource(strings = { "a=%ZZ", "a=%C", "a=%", "a=%E2%82", "a=%ED%A0%80", "a=%G1", "a=%1" })
	void aBadSequenceOrBytesThatAreNotUtf8NameThePair(String q) {
		assertEquals(Optional.of("a"), name(q), q);
	}

	// 4
	@ParameterizedTest
	@ValueSource(strings = { "idValue=%C3%A9", "a=b+c", "a=%2B", "a", "a=", "&&", "", "=x", "a=1&=x", "a=%e2%82%ac" })
	void aQueryThatDecodesNamesNothing(String q) {
		assertEquals(Optional.empty(), name(q), q);
	}

	// design 3.5 (run-1 T-2): a pair without = ends at its &, so its name never runs into a later pair's =, and the
	// later pair is the one named; the same bound holds when the pair without = is itself the failing one
	@Test
	void aPairWithoutEqualsEndsAtItsAmpersand() {
		assertEquals(Optional.of("idValue"), name("flag&idValue=%C3%28"));
		assertEquals(Optional.of("\uFFFD"), name("%FF&b=1"));
		assertEquals(Optional.empty(), name("flag&a=1"));
	}

	// design 3.5 (run-1 T-2): + in a name is a space, as in Tomcat's decoded name
	@Test
	void aPlusInTheNameIsASpace() {
		assertEquals(Optional.of("a b"), name("a+b=%FF"));
		assertEquals(Optional.of("a b"), name("x=1&a+b=%C3%28"));
	}

	@Test
	void aNullQueryNamesNothing() {
		assertEquals(Optional.empty(), name(null));
	}

	// 5
	@Test
	void aLongQueryIsReadToItsFailingPair() {
		StringBuilder q = new StringBuilder();
		for (int i = 0; i < 2000; i++) q.append("p").append(i).append("=v%C3%A9").append('&');
		q.append("z=%C3%28");
		assertEquals(Optional.of("z"), name(q.toString()));

		String longName = "n".repeat(300);
		assertEquals(Optional.of(longName), name(longName + "=%FF"), "the helper answers the whole name; LogSafe bounds it");
		assertEquals("n".repeat(LogSafe.MAX_LENGTH) + "...", LogSafe.text(name(longName + "=%FF").get()));
	}

	@Test
	void theCauseWordIsAClassNameNeverAMessage() {
		assertEquals("MalformedInputException", TeaQueryStrings.causeWord(
				new org.apache.tomcat.util.http.InvalidParameterException("idValue=\u00e9", new java.nio.charset.MalformedInputException(1))));
		assertEquals("InvalidParameterException", TeaQueryStrings.causeWord(
				new org.apache.tomcat.util.http.InvalidParameterException("no cause")));
	}
}
