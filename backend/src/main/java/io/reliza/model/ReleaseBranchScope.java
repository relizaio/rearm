/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Which branches a release search lists ({@code searchReleasesByCveId} and
 * {@code releasesBySbomComponents}).
 *
 * <ul>
 *   <li>{@code ACTIVE_BRANCHES} (default) -- leaves out the releases of branches whose
 *       status is ARCHIVED; a branch with no status is active.</li>
 *   <li>{@code ALL_BRANCHES} -- lists every branch, as the searches did before the
 *       argument existed.</li>
 * </ul>
 *
 * The constant names are bound as SQL text in FIND_RELEASES_BY_CVE_ID; ReleaseBranchScopeSchemaTest
 * pins the literal.
 */
public enum ReleaseBranchScope {
	ACTIVE_BRANCHES,
	ALL_BRANCHES;

	/** The schema default, applied when a caller passes nothing (DGS hands the default in; the guard is the file idiom). */
	public static ReleaseBranchScope orDefault(ReleaseBranchScope scope) {
		return null == scope ? ACTIVE_BRANCHES : scope;
	}
}
