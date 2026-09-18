package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

public class CliSessionCodesTest {

	@Test
	void userCodesAreReadableAndNormalise() {
		String c = CliSessionCodes.newUserCode();
		assertTrue(c.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}"), c);
		assertEquals(c, CliSessionCodes.normalizeUserCode(" " + c.toLowerCase().replace("-", "") + "\n"));
	}

	@Test
	void opaqueSecretsAreLongRandomAndHashedConsistently() {
		String a = CliSessionCodes.newOpaqueSecret(), b = CliSessionCodes.newOpaqueSecret();
		assertNotEquals(a, b);
		assertTrue(a.length() >= 43, "256 bits base64url");
		assertEquals(CliSessionCodes.sha256Hex(a), CliSessionCodes.sha256Hex(a));
		assertNotEquals(CliSessionCodes.sha256Hex(a), CliSessionCodes.sha256Hex(b));
	}

	@Test
	void expirySlidesThirtyDaysButNeverPastNinety() {
		ZonedDateTime approved = ZonedDateTime.parse("2026-09-12T10:00:00Z");
		assertEquals(approved.plusDays(30), CliSessionCodes.slidExpiry(approved, approved), "first expiry: 30 days out");
		assertEquals(approved.plusDays(70), CliSessionCodes.slidExpiry(approved, approved.plusDays(40)), "a refresh on day 40 slides to day 70");
		assertEquals(approved.plusDays(90), CliSessionCodes.slidExpiry(approved, approved.plusDays(80)), "a refresh on day 80 stops at the 90-day cap");
	}
}
