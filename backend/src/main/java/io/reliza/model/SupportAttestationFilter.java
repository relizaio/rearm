/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Which slice of a release's non-root components a page request wants.
 *
 * <p>ATTESTED and UNATTESTED are evaluated with the SAME SQL body the coverage gauge counts
 * with ({@code SbomComponentSupportRepository.ATTESTED_PAYLOAD_PREDICATE}), and UNATTESTED is
 * its exact complement, not an independently written "no support row" test. That matters more
 * than it looks: the gauge is what tells the operator 1,206 of 1,240 components are
 * undisclosed, and this filter is how they then select those 1,206 to attest. If the two
 * definitions drift, the list will not contain the components the number is counting and the
 * gap can never be closed -- the failure this feature has already hit three times by other
 * routes (level, milestone keys, per-milestone source).
 *
 * <p>An enum rather than a boolean because the tri-state is real: ALL is the default the
 * unfiltered table needs, and a nullable Boolean would encode it as an absence.
 */
public enum SupportAttestationFilter {
	/** Every non-root component in the release. */
	ALL,
	/** Only those the gauge counts as disclosed. */
	ATTESTED,
	/** Only those the gauge counts as missing -- the bulk-attest work queue. */
	UNATTESTED;

	/**
	 * The same names as compile-time constants, because the page SQL has to branch on them
	 * and {@code @Query} takes only a constant expression.
	 *
	 * <p>Written as {@code X.name()}-equivalent constants rather than as bare literals in the
	 * SQL: a bare literal would let a rename compile cleanly and silently make every branch
	 * false, and a filter that matches nothing is indistinguishable on the wire from a filter
	 * that legitimately found nothing -- no error, no log, an empty page. These are asserted
	 * against {@code name()} in the enum-sync test, which is what makes the coupling
	 * mechanical instead of remembered.
	 */
	public static final String CODE_ALL = "ALL";
	public static final String CODE_ATTESTED = "ATTESTED";
	public static final String CODE_UNATTESTED = "UNATTESTED";
}
