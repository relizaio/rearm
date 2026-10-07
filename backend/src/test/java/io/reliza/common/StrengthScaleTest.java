/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/**
 * The strength scale's arithmetic. Pure functions, so every boundary is pinned here rather than
 * rediscovered through a board.
 */
public class StrengthScaleTest {

	@Test
	public void noRequirementAdmitsEverythingIncludingUnrated() {
		assertTrue(StrengthScale.admits(null, null, 0));
		assertTrue(StrengthScale.admits(1.0, null, 0));
	}

	@Test
	public void unratedFailsARealRequirement() {
		assertFalse(StrengthScale.admits(null, 3.0, 0));
	}

	@Test
	public void theFloorIsMetWithinTheTolerance() {
		assertTrue(StrengthScale.admits(4.0, 4.0, 0), "at the floor");
		// 0.1 + 0.2 is the classic: 0.30000000000000004 against 0.3 must count as equal
		assertTrue(StrengthScale.admits(0.1 + 0.2, 0.3, 0));
		assertTrue(StrengthScale.admits(0.3, 0.1 + 0.2, 0));
		assertTrue(StrengthScale.admits(3.9985, 4.0, 0), "0.0015 below: inside the two-sided 0.002");
		assertFalse(StrengthScale.admits(3.99, 4.0, 0), "one input step below is below");
	}

	@Test
	public void theCeilingIsTheFloorPlusHeadroomWithinTheTolerance() {
		assertTrue(StrengthScale.admits(4.5, 4.0, 0.5));
		assertTrue(StrengthScale.admits(4.5015, 4.0, 0.5));
		assertFalse(StrengthScale.admits(4.51, 4.0, 0.5), "one input step above the headroom");
		assertFalse(StrengthScale.admits(4.01, 4.0, 0), "no headroom is an exact match");
	}

	@Test
	public void distanceIsOnTheInputGridSoNoiseDoesNotSeparateEqualValues() {
		assertEquals(0, StrengthScale.distanceAbove(0.1 + 0.2, 0.3));
		assertEquals(0, StrengthScale.distanceAbove(4.0, 4.0));
		assertEquals(25, StrengthScale.distanceAbove(4.25, 4.0));
		assertEquals(0, StrengthScale.distanceAbove(3.9995, 4.0), "a match within tolerance is a match");
		assertEquals(Long.MAX_VALUE, StrengthScale.distanceAbove(4.0, null),
				"no requirement ranks after every requirement a model meets");
	}

	@Test
	public void strengthsReadWithoutRoundingNoise() {
		assertEquals("3.7", StrengthScale.format(3.5 + 0.2));
		assertEquals("0.3", StrengthScale.format(0.1 + 0.2));
		assertEquals("4", StrengthScale.format(4.0));
		assertEquals("4.25", StrengthScale.format(4.25));
	}

	@Test
	public void writesAreCappedAtTwoDecimalsNonNegativeAndFinite() throws RelizaException {
		assertEquals(4.25, StrengthScale.validate("s", 4.25));
		assertEquals(4.0, StrengthScale.validate("s", 4.0));
		assertEquals(null, StrengthScale.validate("s", null));
		assertThrows(RelizaException.class, () -> StrengthScale.validate("s", 4.255));
		assertThrows(RelizaException.class, () -> StrengthScale.validate("s", -1.0));
		assertThrows(RelizaException.class, () -> StrengthScale.validate("s", Double.NaN));
		assertThrows(RelizaException.class, () -> StrengthScale.validate("s", Double.POSITIVE_INFINITY));
	}
}
