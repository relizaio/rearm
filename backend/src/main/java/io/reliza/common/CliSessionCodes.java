/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Pure helpers for the CLI browser login: codes, hashing and the sliding refresh expiry.
 * Kept free of Spring so they are unit-testable and the lifetimes live in one place.
 */
public final class CliSessionCodes {

	private CliSessionCodes() {}

	/** A pending login request the user has not approved yet. */
	public static final Duration PENDING_TTL = Duration.ofMinutes(10);
	/** Each refresh slides the session forward by this much... */
	public static final Duration REFRESH_SLIDE = Duration.ofDays(30);
	/** ...but never past this much after approval. */
	public static final Duration HARD_CAP = Duration.ofDays(90);
	/** RFC 8628 polling interval the CLI is told to respect. */
	public static final int POLL_INTERVAL_SECONDS = 5;
	/** After a rotation the retired refresh token is still honoured this long (it returns the current token again); reuse after it revokes the session. */
	public static final Duration ROTATION_GRACE = Duration.ofMinutes(2);
	/** Delivered sessions that ended (revoked, denied, expired) are kept this long for the audit lists. */
	public static final Duration ENDED_RETENTION = Duration.ofDays(30);

	private static final SecureRandom RANDOM = new SecureRandom();
	/** No 0/O/1/I so the code survives being read aloud or typed. */
	private static final char[] USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

	/** The short code the user sees, e.g. {@code WDJB-MJHT}. */
	public static String newUserCode() {
		StringBuilder sb = new StringBuilder(9);
		for (int i = 0; i < 8; i++) {
			if (i == 4) sb.append('-');
			sb.append(USER_CODE_ALPHABET[RANDOM.nextInt(USER_CODE_ALPHABET.length)]);
		}
		return sb.toString();
	}

	/** Normalises what a user typed or pasted: case, whitespace and the optional dash. */
	public static String normalizeUserCode(String raw) {
		if (raw == null) return null;
		String s = raw.trim().toUpperCase().replaceAll("[\\s-]", "");
		if (s.length() != 8) return s;
		return s.substring(0, 4) + "-" + s.substring(4);
	}

	/** 256-bit opaque secret (device code or refresh token), URL-safe, no padding. */
	public static String newOpaqueSecret() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	public static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	/** The next expiry after a refresh: slide by {@link #REFRESH_SLIDE}, capped at {@link #HARD_CAP} from approval. */
	public static ZonedDateTime slidExpiry(ZonedDateTime approvedDate, ZonedDateTime now) {
		return slidExpiry(approvedDate, now, null);
	}

	/** The same, and never past the session's hard end when it has one (task RD3-7). */
	public static ZonedDateTime slidExpiry(ZonedDateTime approvedDate, ZonedDateTime now, ZonedDateTime hardEnd) {
		ZonedDateTime slid = now.plus(REFRESH_SLIDE);
		ZonedDateTime cap = approvedDate.plus(HARD_CAP);
		if (hardEnd != null && hardEnd.isBefore(cap)) cap = hardEnd;
		return slid.isBefore(cap) ? slid : cap;
	}

	/** The longest a key may bound its sessions to: the 90-day cap, 129600 minutes. */
	public static final int MAX_SESSION_MINUTES = (int) HARD_CAP.toMinutes();
	/** An access token's lifetime at most; the same hour as ApiTokenService.TTL_SECONDS. */
	public static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);

	/** A key's sessionMaxMinutes: null (no bound) or 1 to {@link #MAX_SESSION_MINUTES}. */
	public static boolean isValidSessionMinutes(Integer minutes) {
		return minutes == null || (minutes >= 1 && minutes <= MAX_SESSION_MINUTES);
	}

	/**
	 * The minutes a session is approved for: the approver's choice when given, which may shorten the
	 * key's bound but never lengthen it; the key's bound otherwise (null for none). Throws
	 * IllegalArgumentException with the words to show the approver.
	 */
	public static Integer sessionMinutes(Integer keyBound, Integer approverChoice) {
		if (approverChoice == null) return keyBound;
		if (!isValidSessionMinutes(approverChoice)) {
			throw new IllegalArgumentException("A session lasts 1 to " + MAX_SESSION_MINUTES + " minutes");
		}
		if (keyBound != null && approverChoice > keyBound) {
			throw new IllegalArgumentException("This key bounds its sessions to " + keyBound + " minutes; choose " + keyBound + " or fewer");
		}
		return approverChoice;
	}

	/** The session's hard end: approval plus the minutes, or null when nothing bounds it. */
	public static ZonedDateTime hardEnd(ZonedDateTime approvedDate, Integer minutes) {
		return minutes == null ? null : approvedDate.plusMinutes(minutes);
	}

	/** When an access token minted now ends: an hour out, or the session's expiry when that comes first. */
	public static ZonedDateTime accessTokenExpiry(ZonedDateTime now, ZonedDateTime sessionExpiry) {
		ZonedDateTime hour = now.plus(ACCESS_TOKEN_TTL);
		return sessionExpiry != null && sessionExpiry.isBefore(hour) ? sessionExpiry : hour;
	}

	/** Whole seconds from now to the token's end, for the token response's expires_in. */
	public static long expiresInSeconds(ZonedDateTime now, ZonedDateTime sessionExpiry) {
		return Math.max(0, Duration.between(now, accessTokenExpiry(now, sessionExpiry)).getSeconds());
	}

	/** Whether a refresh token can matter: only when the session outlives the access token minted now. */
	public static boolean refreshTokenUseful(ZonedDateTime now, ZonedDateTime hardEnd) {
		return hardEnd == null || hardEnd.isAfter(now.plus(ACCESS_TOKEN_TTL));
	}
}
