/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.dto;

import java.util.List;

import io.reliza.model.VulnerabilityRecordData.UpstreamSource;

/**
 * GraphQL {@code FixedIn}: which version fixes a finding, according to the
 * affected ranges the advisory publishes for the finding's package. Derived
 * at read time from the org's vulnerability record, never stored. It is the
 * advisory's claim; whether a release actually ships the fix is a separate
 * question that only its SBOM answers.
 *
 * @param version             the version the finding's range ends before; set only for
 *                            {@link FixedInVerdict#FIXED_IN}, and always above the finding's version
 * @param verdict             how the finding's version relates to the advisory's ranges
 * @param versionEndIncluding the last affected version of the finding's range; set only for
 *                            {@link FixedInVerdict#FIXED_AFTER}
 * @param sources             sources whose ranges contain the finding's version, in {@link UpstreamSource} order
 * @param identities          the identities of the advisory's ranges the verdict was taken from, as stored (for a
 *                            Debian finding, its own release's); empty when there was no verdict to take
 */
public record FixedIn(String version, FixedInVerdict verdict, String versionEndIncluding, List<UpstreamSource> sources,
		List<String> identities) {

	public enum FixedInVerdict {
		/** The finding's range ends before {@code version}, the first version outside it. */
		FIXED_IN,
		/**
		 * The finding's range ends at {@code versionEndIncluding}; a later
		 * version fixes it, but the advisory names none.
		 */
		FIXED_AFTER,
		/** The finding's range has no end: every later version is affected too. */
		NO_FIX_AVAILABLE,
		/**
		 * The advisory has ranges for the package, but none contains the
		 * finding's version: the finding was matched by some other rule.
		 */
		NOT_IN_ADVISORY_RANGE,
		/**
		 * No ranges for the finding's package: none fetched yet, none
		 * published, or the finding has no package URL.
		 */
		NO_RANGE_DATA,
		/**
		 * The finding's version cannot be placed in its package's ranges: it,
		 * or every bound, is not a version under the package ecosystem's rules,
		 * or the ranges differ per distribution release and the finding's
		 * release is not named or not listed.
		 */
		UNCOMPARABLE
	}

	public static FixedIn of(FixedInVerdict verdict) {
		return new FixedIn(null, verdict, null, List.of(), List.of());
	}
}
