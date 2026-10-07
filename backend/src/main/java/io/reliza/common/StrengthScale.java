/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.math.BigDecimal;

import io.reliza.exceptions.RelizaException;

/**
 * The model strength scale: every comparison between a model's strength and what a role or task
 * requires goes through here, so no call site can forget the tolerance or apply it differently.
 *
 * <p>Strengths are decimals with at most {@link #MAX_DECIMALS} places, refused on write
 * otherwise. That cap is what makes the tolerance safe: two values an operator can enter differ by
 * at least 0.01, while {@link #EPSILON} only has to absorb floating-point noise, so it can never
 * merge two real values.
 *
 * <p>Admission uses the tolerance literally. Ranking does not: a comparator that calls two values
 * equal when they are within a tolerance is not transitive, so which model wins would depend on
 * list order. Ranking compares {@link #units} instead -- values rounded to the input grid, which
 * are exact and totally ordered.
 */
public final class StrengthScale {

	private StrengthScale() {}

	/** Tolerance for floating-point noise on either side of a comparison. */
	public static final double EPSILON = 0.001;

	/** Decimal places an operator may enter. */
	public static final int MAX_DECIMALS = 2;

	private static final double UNITS_PER_POINT = 100;

	/**
	 * Check a strength on its way in. Null passes, meaning unrated or unset; anything else must be
	 * finite, non-negative and have at most {@link #MAX_DECIMALS} decimal places.
	 */
	public static Double validate(String field, Double value) throws RelizaException {
		if (null == value) return null;
		if (!Double.isFinite(value) || value < 0) {
			throw new RelizaException(field + " must be a non-negative number, got " + value);
		}
		if (BigDecimal.valueOf(value).stripTrailingZeros().scale() > MAX_DECIMALS) {
			throw new RelizaException(field + " may have at most " + MAX_DECIMALS
					+ " decimal places, got " + value);
		}
		return value;
	}

	/** A strength on the input grid: 4.25 is 425. Exact, so safe to order and compare for equality. */
	public static long units(double value) {
		return Math.round(value * UNITS_PER_POINT);
	}

	/** Whether {@code value} is at least {@code minimum}, within the tolerance either side. */
	public static boolean atLeast(double value, double minimum) {
		return value + EPSILON >= minimum - EPSILON;
	}

	/**
	 * A strength for people to read: on the input grid, without trailing zeros, so 3.5 + 0.2
	 * reads "3.7" rather than "3.7000000000000002".
	 */
	public static String format(double value) {
		return BigDecimal.valueOf(units(value), MAX_DECIMALS).stripTrailingZeros().toPlainString();
	}

	/**
	 * Whether a model of {@code strength} may take work requiring {@code required}, at most
	 * {@code headroom} above it. A null requirement admits every model; a null strength -- an
	 * unrated model -- fails a real requirement, because unrated is unknown, not weak.
	 */
	public static boolean admits(Double strength, Double required, double headroom) {
		if (null == required) return true;
		if (null == strength) return false;
		boolean floor = atLeast(strength, required);
		boolean ceiling = strength - EPSILON <= required + headroom + EPSILON;
		return floor && ceiling;
	}

	/**
	 * How far above the requirement a model sits, on the input grid, for ranking the cheapest
	 * model that suffices first. Zero is a match. No requirement ranks after every requirement a
	 * model meets: work that needs a strong model should go to one before work that needs nothing.
	 */
	public static long distanceAbove(Double strength, Double required) {
		if (null == required || null == strength) return Long.MAX_VALUE;
		return Math.max(0, units(strength) - units(required));
	}
}
