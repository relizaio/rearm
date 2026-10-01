/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import us.springett.cvss.Cvss;
import us.springett.cvss.CvssV2;
import us.springett.cvss.CvssV3;
import us.springett.cvss.CvssV4;
import us.springett.cvss.Score;

/**
 * Computes a CVSS base score from a vector string. Dependency-Track 4.x
 * stores the vector it receives from OSV but never the score, so a
 * vulnerability sourced only from OSV reaches ReARM with a vector and a
 * null score. The arithmetic is fully specified by FIRST, so we do it here.
 *
 * <p>Accepts whatever {@link Cvss#fromVector(String)} recognises: CVSS 2
 * ({@code AV:N/AC:L/...}), 3.0 / 3.1 ({@code CVSS:3.x/...}) and 4.0
 * ({@code CVSS:4.0/...}). Anything else yields an empty result.
 */
public final class CvssVectorScorer {

	private static final String CVSS_V2_PREFIX = "CVSS:2.0/";

	private CvssVectorScorer() {}

	/**
	 * @return the base score rounded to one decimal, or empty when the
	 *         vector is null, blank, unparseable or of an unknown version.
	 *         Never throws.
	 */
	public static Optional<Double> baseScore(String vector) {
		return score(vector, Cvss.class);
	}

	/**
	 * As {@link #baseScore}, but empty unless the vector is CVSS 2 (the
	 * {@code AV:N/AC:L/Au:N/...} form, with or without a {@code CVSS:2.0/}
	 * prefix).
	 */
	public static Optional<Double> v2BaseScore(String vector) {
		return score(vector, CvssV2.class);
	}

	/** As {@link #baseScore}, but empty unless the vector is CVSS 3.0 or 3.1. */
	public static Optional<Double> v3BaseScore(String vector) {
		return score(vector, CvssV3.class);
	}

	/** As {@link #baseScore}, but empty unless the vector is CVSS 4.0. */
	public static Optional<Double> v4BaseScore(String vector) {
		return score(vector, CvssV4.class);
	}

	private static Optional<Double> score(String vector, Class<? extends Cvss> expectedVersion) {
		if (vector == null || vector.isBlank()) return Optional.empty();
		String v = vector.strip();
		// The library's v2 parser rejects the CVSS:2.0/ prefix that some feeds carry.
		if (v.regionMatches(true, 0, CVSS_V2_PREFIX, 0, CVSS_V2_PREFIX.length())) {
			v = v.substring(CVSS_V2_PREFIX.length());
		}
		try {
			Cvss cvss = Cvss.fromVector(v);
			if (!expectedVersion.isInstance(cvss)) return Optional.empty();
			Score score = cvss.calculateScore();
			if (score == null) return Optional.empty();
			double base = score.getBaseScore();
			if (Double.isNaN(base) || base < 0) return Optional.empty();
			return Optional.of(BigDecimal.valueOf(base).setScale(1, RoundingMode.HALF_UP).doubleValue());
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}
}
