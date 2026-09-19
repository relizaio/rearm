/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Pins the SupportStatus.derive contract -- the single source of truth every read /
 * export / snapshot surface calls. Covers the inclusive-boundary and EOL/EOS/EOGS
 * precedence decisions, the SECURITY_ONLY milestone, and the null-asOf guard so
 * downstream slices cannot silently change them.
 */
class SupportStatusTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

	@Test
	void unknownWhenNoDates() {
		assertEquals(SupportStatus.UNKNOWN, SupportStatus.derive(null, null, TODAY));
	}

	/**
	 * A FUTURE end-of-support derives UNKNOWN, not ACTIVELY_SUPPORTED. This is the defect
	 * this test previously pinned in place. A published horizon says only that the maintainer
	 * declared one; it establishes nothing about whether anyone is maintaining the component
	 * today, and asserting otherwise put a claim about a third party in ReARM's mouth.
	 * ACTIVELY_SUPPORTED is now attested by a human (SupportData#levelOfSupport), never derived.
	 */
	@Test
	void unknownWhenEosIsStillInFuture() {
		assertEquals(SupportStatus.UNKNOWN,
				SupportStatus.derive(null, TODAY.plusYears(1), TODAY));
	}

	@Test
	void endOfSupportOnceEosPast() {
		assertEquals(SupportStatus.END_OF_SUPPORT,
				SupportStatus.derive(null, TODAY.minusDays(1), TODAY));
	}

	@Test
	void boundaryEosIsInclusive() {
		// asOf exactly on the EOS date -> the milestone takes effect that day.
		assertEquals(SupportStatus.END_OF_SUPPORT,
				SupportStatus.derive(null, TODAY, TODAY));
	}

	@Test
	void securityOnlyOnceEogsPastButEosStillFuture() {
		assertEquals(SupportStatus.SECURITY_ONLY,
				SupportStatus.derive(TODAY.minusDays(1), TODAY.plusYears(1), TODAY));
	}

	@Test
	void boundaryEogsIsInclusive() {
		assertEquals(SupportStatus.SECURITY_ONLY,
				SupportStatus.derive(TODAY, TODAY.plusYears(1), TODAY));
	}

	@Test
	void eosOutranksEogsWhenBothPast() {
		assertEquals(SupportStatus.END_OF_SUPPORT,
				SupportStatus.derive(TODAY.minusDays(10), TODAY.minusDays(5), TODAY));
	}

	@Test
	void unknownWhenOnlyEogsDeclaredAndFuture() {
		assertEquals(SupportStatus.UNKNOWN,
				SupportStatus.derive(TODAY.plusYears(1), null, TODAY));
	}

	/**
	 * derive() must NEVER return a retired or attested-only value, whatever combination of
	 * dates it is given. Enumerates past/absent/future across both participating milestones,
	 * so an edit that reintroduces a derived ACTIVELY_SUPPORTED -- or resurrects END_OF_LIFE
	 * after D5 retired it -- fails here rather than in an export.
	 */
	@Test
	void deriveNeverReturnsAnAttestedOnlyValue() {
		LocalDate[] options = {null, TODAY.minusDays(30), TODAY.plusDays(30)};
		for (LocalDate eogs : options) {
			for (LocalDate eos : options) {
				SupportStatus got = SupportStatus.derive(eogs, eos, TODAY);
				assertTrue(got != SupportStatus.ACTIVELY_SUPPORTED && got != SupportStatus.ABANDONED
								&& got != SupportStatus.END_OF_LIFE,
						"derive returned a retired or attested-only value " + got
								+ " for eogs=" + eogs + " eos=" + eos);
			}
		}
	}

	@Test
	void nullAsOfIsRejected() {
		assertThrows(NullPointerException.class,
				() -> SupportStatus.derive(null, TODAY, null));
	}

	/**
	 * Every member's wire value is FDA's phrase verbatim and lowercase. Pinned for all three,
	 * not just the one the export test happens to use: the equality with a mechanical
	 * name-to-lowercase transform is a coincidence of the current wording, not a rule, and a
	 * guidance revision is meant to change this table alone.
	 */
	@Test
	void levelOfSupportWireValuesAreFdaPhrasesVerbatim() {
		assertEquals("actively maintained", LevelOfSupport.ACTIVELY_MAINTAINED.getWireValue());
		assertEquals("no longer maintained", LevelOfSupport.NO_LONGER_MAINTAINED.getWireValue());
		assertEquals("abandoned", LevelOfSupport.ABANDONED.getWireValue());
		assertFalse(LevelOfSupport.ACTIVELY_MAINTAINED.requiresJustification(),
				"a positive claim needs no stated basis");
		assertTrue(LevelOfSupport.NO_LONGER_MAINTAINED.requiresJustification());
		assertTrue(LevelOfSupport.ABANDONED.requiresJustification());
	}
}
