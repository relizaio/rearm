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
		ZonedDateTime slid = now.plus(REFRESH_SLIDE);
		ZonedDateTime cap = approvedDate.plus(HARD_CAP);
		return slid.isBefore(cap) ? slid : cap;
	}
}
