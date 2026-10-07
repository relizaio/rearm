package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

	// Task RD3-7: the key's sessionMaxMinutes is a hard end for a device-login session.

	private static final ZonedDateTime APPROVED = ZonedDateTime.parse("2026-09-28T10:00:00Z");
	/** The CLI collects the approval within seconds: it polls every five. */
	private static final ZonedDateTime COLLECTED = APPROVED.plusSeconds(CliSessionCodes.POLL_INTERVAL_SECONDS);

	@Test
	void theKnobGivesTheHardEndTheFirstTokenAndWhetherARefreshTokenCanMatter() {
		// null: today's behaviour -- no hard end, a one-hour token, a refresh token, 30 days sliding
		assertNull(CliSessionCodes.hardEnd(APPROVED, null));
		assertEquals(APPROVED.plusDays(30), CliSessionCodes.slidExpiry(APPROVED, APPROVED, null));
		assertTrue(CliSessionCodes.refreshTokenUseful(COLLECTED, null));
		assertEquals(COLLECTED.plusHours(1), CliSessionCodes.accessTokenExpiry(COLLECTED, APPROVED.plusDays(30)));

		// 30: one 30-minute token and the end, no refresh token
		ZonedDateTime end30 = CliSessionCodes.hardEnd(APPROVED, 30);
		assertEquals(APPROVED.plusMinutes(30), end30);
		assertEquals(end30, CliSessionCodes.slidExpiry(APPROVED, APPROVED, end30), "the session expiry is the hard end");
		assertEquals(end30, CliSessionCodes.accessTokenExpiry(COLLECTED, end30), "the token dies with the session");
		assertEquals(30 * 60 - CliSessionCodes.POLL_INTERVAL_SECONDS, CliSessionCodes.expiresInSeconds(COLLECTED, end30));
		assertFalse(CliSessionCodes.refreshTokenUseful(COLLECTED, end30));

		// 60: the first token already reaches the end
		ZonedDateTime end60 = CliSessionCodes.hardEnd(APPROVED, 60);
		assertEquals(end60, CliSessionCodes.accessTokenExpiry(COLLECTED, end60));
		assertFalse(CliSessionCodes.refreshTokenUseful(COLLECTED, end60), "at 60 no refresh token");

		// 61: the session outlives the first token by a minute, so a refresh can matter
		ZonedDateTime end61 = CliSessionCodes.hardEnd(APPROVED, 61);
		assertEquals(COLLECTED.plusHours(1), CliSessionCodes.accessTokenExpiry(COLLECTED, end61));
		assertTrue(CliSessionCodes.refreshTokenUseful(COLLECTED, end61));

		// 90: a 60-minute token, one refresh for the remaining 30, then the end
		ZonedDateTime end90 = CliSessionCodes.hardEnd(APPROVED, 90);
		assertEquals(3600, CliSessionCodes.expiresInSeconds(COLLECTED, end90));
		assertTrue(CliSessionCodes.refreshTokenUseful(COLLECTED, end90));
		ZonedDateTime refreshedAt = COLLECTED.plusHours(1);
		ZonedDateTime slid = CliSessionCodes.slidExpiry(APPROVED, refreshedAt, end90);
		assertEquals(end90, slid, "the refresh does not slide past the end");
		assertEquals(end90, CliSessionCodes.accessTokenExpiry(refreshedAt, slid), "the refreshed token is clipped to the end");
		assertEquals(30 * 60 - CliSessionCodes.POLL_INTERVAL_SECONDS, CliSessionCodes.expiresInSeconds(refreshedAt, slid));

		// 129600: the 90-day cap itself
		ZonedDateTime endMax = CliSessionCodes.hardEnd(APPROVED, CliSessionCodes.MAX_SESSION_MINUTES);
		assertEquals(APPROVED.plusDays(90), endMax);
		assertEquals(APPROVED.plusDays(30), CliSessionCodes.slidExpiry(APPROVED, APPROVED, endMax), "still slides 30 days at a time");
		assertEquals(APPROVED.plusDays(90), CliSessionCodes.slidExpiry(APPROVED, APPROVED.plusDays(80), endMax));
	}

	@Test
	void aHardEndInsideTheNinetyDaysCapsEverySlide() {
		ZonedDateTime end = APPROVED.plusDays(10);
		assertEquals(end, CliSessionCodes.slidExpiry(APPROVED, APPROVED, end));
		assertEquals(end, CliSessionCodes.slidExpiry(APPROVED, APPROVED.plusDays(5), end));
		// a hard end beyond the 90-day cap does not lift the cap
		assertEquals(APPROVED.plusDays(90), CliSessionCodes.slidExpiry(APPROVED, APPROVED.plusDays(80), APPROVED.plusDays(200)));
	}

	@Test
	void theApproverMayShortenTheKeysBoundButNeverLengthenIt() {
		assertEquals(90, CliSessionCodes.sessionMinutes(90, null), "no choice: the key's bound");
		assertNull(CliSessionCodes.sessionMinutes(null, null), "no bound and no choice: none");
		assertEquals(30, CliSessionCodes.sessionMinutes(90, 30), "shorter is taken");
		assertEquals(90, CliSessionCodes.sessionMinutes(90, 90), "equal is taken");
		IllegalArgumentException longer = assertThrows(IllegalArgumentException.class, () -> CliSessionCodes.sessionMinutes(90, 91));
		assertEquals("This key bounds its sessions to 90 minutes; choose 90 or fewer", longer.getMessage());
		assertEquals(600, CliSessionCodes.sessionMinutes(null, 600), "a key with no bound: any lifetime up to the cap");
		assertThrows(IllegalArgumentException.class, () -> CliSessionCodes.sessionMinutes(null, CliSessionCodes.MAX_SESSION_MINUTES + 1));
		assertThrows(IllegalArgumentException.class, () -> CliSessionCodes.sessionMinutes(90, 0));
		assertThrows(IllegalArgumentException.class, () -> CliSessionCodes.sessionMinutes(90, -5));
	}

	@Test
	void theKnobIsOneMinuteToNinetyDaysOrNothing() {
		assertEquals(129600, CliSessionCodes.MAX_SESSION_MINUTES);
		assertTrue(CliSessionCodes.isValidSessionMinutes(null));
		assertTrue(CliSessionCodes.isValidSessionMinutes(1));
		assertTrue(CliSessionCodes.isValidSessionMinutes(129600));
		assertFalse(CliSessionCodes.isValidSessionMinutes(0));
		assertFalse(CliSessionCodes.isValidSessionMinutes(-1));
		assertFalse(CliSessionCodes.isValidSessionMinutes(129601));
		assertEquals(io.reliza.service.ApiTokenService.TTL_SECONDS, CliSessionCodes.ACCESS_TOKEN_TTL.getSeconds(),
				"the refresh-token rule and the token's life are the same hour");
	}
}
