/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.dto;

import java.util.UUID;

/**
 * GraphQL {@code FindingSbomMatch}: the component of a release's SBOM that a
 * finding's package URL names, or why none does. Matched at read time by
 * {@code FindingPurlBridge}, never stored.
 *
 * @param sbomComponentUuid    the matched {@code sbom_components} row; null when none matched
 * @param canonicalPurl        the matched component's canonical purl; null when none matched
 * @param missReason           why none matched; null when one did
 * @param latestVersion        the latest version of the matched component's package, from
 *                             Dependency-Track's repository metadata; null when none is known
 * @param latestVersionChecked when that was last asked, a UTC RFC-3339 instant; null when never
 */
public record FindingSbomMatch(UUID sbomComponentUuid, String canonicalPurl, FindingSbomMissReason missReason,
		String latestVersion, String latestVersionChecked) {

	public enum FindingSbomMissReason {
		/** The finding carries no package URL (none at all, or a CPE). */
		NO_PURL,
		/** The finding's package URL does not parse. */
		UNPARSEABLE_PURL,
		/**
		 * The release resolves to no SBOM components: no BOM artifact, a BOM
		 * stored elsewhere, or one not indexed yet.
		 */
		NO_INVENTORY,
		/**
		 * No component matched while an SBOM reconcile of the release (or of a
		 * product's dependency) is still queued, so its components may be stale.
		 */
		INVENTORY_PENDING,
		/**
		 * The release's components do not include the finding's package at its
		 * version, for instance a finding carried forward from a BOM the release
		 * no longer has.
		 */
		NOT_IN_INVENTORY
	}

	public static FindingSbomMatch matched(UUID sbomComponentUuid, String canonicalPurl, String latestVersion,
			String latestVersionChecked) {
		return new FindingSbomMatch(sbomComponentUuid, canonicalPurl, null, latestVersion, latestVersionChecked);
	}

	public static FindingSbomMatch missed(FindingSbomMissReason reason) {
		return new FindingSbomMatch(null, null, reason, null, null);
	}
}
