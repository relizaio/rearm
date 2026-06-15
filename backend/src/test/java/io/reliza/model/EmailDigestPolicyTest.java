/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.model.EmailDigestPolicy.EmailDigestMode;

/**
 * Parsing matrix for {@link EmailDigestPolicy#fromParameters}. The
 * contract under test: tolerant read — no input shape may throw, and
 * every malformed field falls back to its default (digest ON, 24h)
 * rather than disabling batching or failing fan-out.
 */
class EmailDigestPolicyTest {

	@Test
	void nullParametersFallsBackToDefault() {
		assertSame(EmailDigestPolicy.DEFAULT, EmailDigestPolicy.fromParameters(null));
	}

	@Test
	void emptyParametersFallsBackToDefault() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of());
		assertEquals(EmailDigestMode.ROLLING, policy.mode());
		assertEquals(EmailDigestPolicy.DEFAULT_INTERVAL, policy.interval());
	}

	@Test
	void immediateModeShortCircuits() {
		// interval must be irrelevant when mode is IMMEDIATE
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_MODE_KEY, "IMMEDIATE",
				EmailDigestPolicy.DIGEST_INTERVAL_KEY, "PT1H"));
		assertEquals(EmailDigestMode.IMMEDIATE, policy.mode());
	}

	@Test
	void immediateModeToleratesSurroundingWhitespace() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_MODE_KEY, " IMMEDIATE "));
		assertEquals(EmailDigestMode.IMMEDIATE, policy.mode());
	}

	@Test
	void unknownModeStringFallsBackToRolling() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_MODE_KEY, "HOURLY"));
		assertEquals(EmailDigestMode.ROLLING, policy.mode());
		assertEquals(EmailDigestPolicy.DEFAULT_INTERVAL, policy.interval());
	}

	@Test
	void nonStringModeFallsBackToRolling() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_MODE_KEY, 42));
		assertEquals(EmailDigestMode.ROLLING, policy.mode());
	}

	@Test
	void validCustomIntervalIsUsed() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_MODE_KEY, "ROLLING",
				EmailDigestPolicy.DIGEST_INTERVAL_KEY, "PT4H"));
		assertEquals(Duration.ofHours(4), policy.interval());
	}

	@Test
	void boundaryIntervalsAreAccepted() {
		assertEquals(EmailDigestPolicy.MIN_INTERVAL, EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_INTERVAL_KEY, "PT5M")).interval());
		assertEquals(EmailDigestPolicy.MAX_INTERVAL, EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_INTERVAL_KEY, "P7D")).interval());
	}

	@Test
	void belowMinIntervalFallsBackToDefault() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_INTERVAL_KEY, "PT1M"));
		assertEquals(EmailDigestPolicy.DEFAULT_INTERVAL, policy.interval());
	}

	@Test
	void aboveMaxIntervalFallsBackToDefault() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_INTERVAL_KEY, "P30D"));
		assertEquals(EmailDigestPolicy.DEFAULT_INTERVAL, policy.interval());
	}

	@Test
	void unparseableIntervalFallsBackToDefault() {
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(Map.of(
				EmailDigestPolicy.DIGEST_INTERVAL_KEY, "24 hours"));
		assertEquals(EmailDigestPolicy.DEFAULT_INTERVAL, policy.interval());
	}

	@Test
	void nonStringIntervalFallsBackToDefault() {
		Map<String, Object> params = new HashMap<>();
		params.put(EmailDigestPolicy.DIGEST_INTERVAL_KEY, 86400);
		assertEquals(EmailDigestPolicy.DEFAULT_INTERVAL,
				EmailDigestPolicy.fromParameters(params).interval());
	}

	@Test
	void nullMapValuesFallBackToDefault() {
		// JSONB round-trips can yield explicit nulls in the map
		Map<String, Object> params = new HashMap<>();
		params.put(EmailDigestPolicy.DIGEST_MODE_KEY, null);
		params.put(EmailDigestPolicy.DIGEST_INTERVAL_KEY, null);
		EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(params);
		assertEquals(EmailDigestMode.ROLLING, policy.mode());
		assertEquals(EmailDigestPolicy.DEFAULT_INTERVAL, policy.interval());
	}
}
