/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Pins the SupportStatus.derive contract -- the single source of truth every read /
 * export / snapshot surface calls. Covers the inclusive-boundary and EOL-precedence
 * decisions and the null-asOf guard so downstream slices cannot silently change them.
 */
class SupportStatusTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

	@Test
	void unknownWhenNoDates() {
		assertEquals(SupportStatus.UNKNOWN, SupportStatus.derive(null, null, null, TODAY));
	}

	@Test
	void activelySupportedWhenEosInFuture() {
		assertEquals(SupportStatus.ACTIVELY_SUPPORTED,
				SupportStatus.derive(TODAY.plusYears(1), null, SupportSource.MANUAL, TODAY));
	}

	@Test
	void endOfSupportOnceEosPast() {
		assertEquals(SupportStatus.END_OF_SUPPORT,
				SupportStatus.derive(TODAY.minusDays(1), null, SupportSource.MANUAL, TODAY));
	}

	@Test
	void boundaryEosIsInclusive() {
		// asOf exactly on the EOS date -> the milestone takes effect that day.
		assertEquals(SupportStatus.END_OF_SUPPORT,
				SupportStatus.derive(TODAY, null, SupportSource.MANUAL, TODAY));
	}

	@Test
	void boundaryEolIsInclusive() {
		assertEquals(SupportStatus.END_OF_LIFE,
				SupportStatus.derive(TODAY.minusYears(1), TODAY, SupportSource.MANUAL, TODAY));
	}

	@Test
	void eolOutranksEosWhenBothPast() {
		assertEquals(SupportStatus.END_OF_LIFE,
				SupportStatus.derive(TODAY.minusDays(10), TODAY.minusDays(5), SupportSource.MANUAL, TODAY));
	}

	@Test
	void nullAsOfIsRejected() {
		assertThrows(NullPointerException.class,
				() -> SupportStatus.derive(TODAY, null, SupportSource.MANUAL, null));
	}
}
