/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.dto;

/**
 * GraphQL {@code LatestFixVerdict}: whether moving a finding's SBOM component to the latest
 * version of its package (Dependency-Track's repository metadata) takes it out of the
 * finding's affected ranges, compared as {@link FixedIn} compares. The advisory's claim.
 */
public enum LatestFixVerdict {
	/** The latest version is out of the finding's ranges. */
	FIXES,
	/** The latest version is still inside the finding's ranges. */
	DOES_NOT_FIX,
	/**
	 * The latest version is not above the component's own: a registry can report one below
	 * a private or pre-release build, and a downgrade is no fix.
	 */
	NOT_ABOVE_CURRENT,
	/** The advisory has ranges for the package, but none contains the component's version. */
	NOT_IN_ADVISORY_RANGE,
	/** No ranges for the finding's package. */
	NO_RANGE_DATA,
	/**
	 * The component's version, the latest version or a range cannot be placed under the
	 * package ecosystem's rules.
	 */
	UNCOMPARABLE
}
