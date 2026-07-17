/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import io.reliza.model.FindingChangeEvent;

/**
 * FROZEN canonical key for the {@code finding_dim} dimension (board task #38 normalization).
 *
 * <p>Computes the 16-byte {@code dim_hash} identifying a distinct finding identity. This is a
 * PERMANENT wire contract: the same logical finding must hash identically forever and across every
 * producer (live emit, backfill, repair sweep), or the dimension mints duplicate rows and the dedup
 * index stops deduping. Do NOT change the canonical form without bumping {@link #KEY_VERSION} (which
 * makes the change detectable and lets old/new keys coexist for a coordinated re-key).
 *
 * <p><b>Key = {@code (findingKind, findingKey)} ONLY.</b> {@code findingKey} is v1's finding identity
 * (built by {@code FindingComparisonService}'s {@code VULN_KEY} / {@code VIOLATION_KEY} /
 * {@code WEAKNESS_KEY} -- those builders are part of THIS frozen preimage; see their FROZEN markers).
 * Keying the dimension on exactly {@code (kind, findingKey)} makes {@code distinct-dim ==
 * distinct-findingKey} a STRUCTURAL invariant (not an empirical accident) and gives EXACT v1 dedup
 * parity: the v1 unique index already deduped on {@code findingKey}, so the dimension collapses
 * precisely what v1 collapsed -- no more, no less. This is the shape validated on real data
 * (605 MB -> 304 MB, 6,032 dims == distinct findingKeys).
 *
 * <p><b>Everything else is NON-KEY dimension payload</b> (representative variant): {@code purl},
 * {@code vulnId}, {@code cweId}, {@code ruleId}, {@code location}, {@code violationType}, and
 * {@code aliases}. These are all functionally covered by {@code findingKey} for identity, or (aliases)
 * are the sole historically-drifting field; carrying them as payload keeps the key stable while
 * reconstruction still renders them.
 *
 * <p><b>Canonical form:</b> {@code KEY_VERSION + NUL + findingKind.name() + NUL + findingKey}, UTF-8,
 * SHA-256 truncated to 16 bytes. All three parts are NON-NULL (version is a constant, findingKind and
 * findingKey are {@code NOT NULL} columns), and NUL (0x00) cannot occur in an enum name or in a
 * Postgres-persisted text/jsonb value -- so the NUL delimiter is unambiguous and no null sentinel or
 * field escaping is needed. SHA-256 (not MD5): FIPS-approved -- MD5 throws on FIPS-mode JVMs, which
 * self-hosted Pro instances may run -- and matches this codebase's uniform SHA-256 usage.
 */
public final class FindingDimKey {

	private FindingDimKey() {}

	/**
	 * Canonical-form version. BUMP THIS (and coordinate a re-key) if the preimage encoding ever
	 * changes -- a different version yields different hashes, so old and new dimension rows coexist
	 * rather than silently colliding. Also stored as {@code finding_dim.key_version} for detectability.
	 */
	public static final short KEY_VERSION = 1;

	/** NUL (0x00) field delimiter -- cannot occur in an enum name or a Postgres-persisted text value. */
	private static final String DELIM = "\u0000";

	/**
	 * The 16-byte content digest of a finding-change event's identity ({@code findingKind},
	 * {@code findingKey}). Ignores all payload fields (purl/vulnId/.../aliases) and every event-specific
	 * field.
	 */
	public static byte[] hash(FindingChangeEvent ev) {
		return hash(ev.getFindingKind() == null ? null : ev.getFindingKind().name(), ev.getFindingKey());
	}

	/**
	 * Field-level overload (the backfill builds the key from stored columns). {@code findingKind} and
	 * {@code findingKey} are {@code NOT NULL} by schema; a null here is a programming error and fails
	 * loud rather than silently minting a degenerate dimension row.
	 */
	public static byte[] hash(String findingKind, String findingKey) {
		if (findingKind == null || findingKey == null) {
			throw new IllegalArgumentException(
					"finding_dim key requires non-null findingKind + findingKey (both are NOT NULL columns)");
		}
		String canonical = KEY_VERSION + DELIM + findingKind + DELIM + findingKey;
		return Arrays.copyOf(sha256(canonical.getBytes(StandardCharsets.UTF_8)), 16);
	}

	private static byte[] sha256(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is mandated on every JRE (and FIPS-approved); this is unreachable.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
