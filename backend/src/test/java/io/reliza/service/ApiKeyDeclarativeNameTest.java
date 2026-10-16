/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A newly assigned declared key name refuses characters that draw nothing (task t20261013-182431-14570). */
public class ApiKeyDeclarativeNameTest {

	private static void refused(String name) {
		String problem = ApiKeyDeclarativeService.invisibleNameProblem(name);
		assertTrue(problem != null && problem.startsWith(ApiKeyDeclarativeService.INVISIBLE_NAME), name + ": " + problem);
	}

	private static void kept(String name) {
		assertNull(ApiKeyDeclarativeService.invisibleNameProblem(name), name);
	}

	@Test
	public void controlFormatBidiAndSeparatorCharactersAreRefused() {
		refused("a\u202Eb");
		refused("ci\u200Brelease");
		refused("tab\tthere");
		refused("soft\u00ADhyphen");
		refused("ci\uFEFFrelease");
		refused("a\u2028b");
		refused("pua\uE000x");
	}

	@Test
	public void strayWhitespaceIsRefused() {
		refused(" lead");
		refused("trail ");
		refused("two  spaces");
		refused("no\u00A0break");
		refused("ideographic\u3000space");
	}

	@Test
	public void joinersAreKeptOnlyWhereTheyShape() {
		refused("ci\u200Drelease");
		refused("\u0645\u200Dx");
		refused("x\u200D\u0645");
		refused("\uD55C\u200D\uAE00 key");
		kept("\uD83D\uDC69\u200D\uD83D\uDCBB ops");
		kept("\u0645\u200C\u0646");
		kept("\u0915\u094D\u200D\u0937");
		kept("\uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDCBB ops");
		kept("\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08 ops");
		kept("\uD83D\uDC69\u200D\u2695\uFE0F ops");
		kept("\u0645\u064E\u200C\u0646");
		kept("\u1004\u103A\u1039\u200D\u1000");
	}

	@Test
	public void variationSelectorsAreKeptOnlyOnEmoji() {
		refused("ci-deploy\uFE0F");
		refused("caf\u00E9\uFE0F key");
		refused("d\u00E9\uDB40\uDD00ploy");
		kept("\u2764\uFE0F ops");
		refused("\uD83D\uDE80\uFE0F ops");
		kept("deploy 1\uFE0F\u20E3");
		kept("ops #\u20E3");
	}

	@Test
	public void tagsAreKeptOnlyInAFlagSequence() {
		kept("\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F ops");
		refused("ci\uDB40\uDC67deploy");
		refused("ci\uDB40\uDC7Fdeploy");
		refused("\uD83C\uDFF4\uDB40\uDC67 ops");
	}

	@Test
	public void blankLettersAndMarksWithoutAVisibleBaseAreRefused() {
		refused("ci\u3164deploy");
		refused("ci\uFFA0deploy");
		refused("ci\u2800deploy");
		refused("ci\uD834\uDD59release");
		refused("caf\u00E9\u034F key");
		refused("\u0301a");
		refused("ab \u0301c");
		refused("\u0645\u200D\u0301");
		kept("g\u0303 ops");
		kept("n\u0304 key");
		kept("\u00E9t\u00E9 key");
	}

	@Test
	public void aNameNotInComposedFormIsRefused() {
		assertEquals(ApiKeyDeclarativeService.NOT_NFC_NAME, ApiKeyDeclarativeService.invisibleNameProblem("e\u0301te key"));
		assertEquals(ApiKeyDeclarativeService.NOT_NFC_NAME, ApiKeyDeclarativeService.invisibleNameProblem("\u212Aey"));
	}

	@Test
	public void theRefusalNamesTheCharacterAndItsPlace() {
		assertEquals(ApiKeyDeclarativeService.INVISIBLE_NAME + " (U+200B at code point 3)", ApiKeyDeclarativeService.invisibleNameProblem("ci\u200Brelease"));
		kept("ci release");
		kept("\uD55C\uAE00 key");
	}
}
