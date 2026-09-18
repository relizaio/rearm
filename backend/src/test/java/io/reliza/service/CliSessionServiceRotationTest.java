/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import io.reliza.common.CliSessionCodes;
import io.reliza.model.CliSession;
import io.reliza.model.CliSession.Status;
import io.reliza.service.CliSessionService.RefreshVerdict;

public class CliSessionServiceRotationTest {

	private static CliSession session(String currentHash, String previousHash, ZonedDateTime rotated) {
		CliSession s = new CliSession();
		s.setStatus(Status.ACTIVE);
		s.setExpiresDate(ZonedDateTime.now().plusDays(10));
		s.setRefreshTokenHash(currentHash);
		s.setPreviousRefreshTokenHash(previousHash);
		s.setRotatedDate(rotated);
		return s;
	}

	@Test
	void currentTokenRefreshesAndRetiredOneIsHonouredOnlyInsideTheGrace() {
		ZonedDateTime now = ZonedDateTime.now();
		CliSession s = session("cur", "prev", now.minusSeconds(30));
		assertEquals(RefreshVerdict.CURRENT, CliSessionService.verdict(s, "cur", now));
		assertEquals(RefreshVerdict.PREVIOUS_IN_GRACE, CliSessionService.verdict(s, "prev", now));
		assertEquals(RefreshVerdict.REUSED, CliSessionService.verdict(s, "prev", now.plus(CliSessionCodes.ROTATION_GRACE).plusSeconds(1)));
		assertEquals(RefreshVerdict.UNKNOWN, CliSessionService.verdict(s, "other", now));
	}

	@Test
	void inactiveOrExpiredSessionsAnswerNothing() {
		ZonedDateTime now = ZonedDateTime.now();
		CliSession revoked = session("cur", null, null);
		revoked.setStatus(Status.REVOKED);
		assertEquals(RefreshVerdict.UNKNOWN, CliSessionService.verdict(revoked, "cur", now));
		CliSession expired = session("cur", null, null);
		expired.setExpiresDate(now.minusMinutes(1));
		assertEquals(RefreshVerdict.UNKNOWN, CliSessionService.verdict(expired, "cur", now));
		// a retired hash with no rotation date (should not happen) is treated as reuse, never as grace
		assertEquals(RefreshVerdict.REUSED, CliSessionService.verdict(session("cur", "prev", null), "prev", now));
	}
}
