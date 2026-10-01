/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.dto;

import java.util.List;

/**
 * GraphQL {@code ComponentFixTargets}: the versions a component of a findings
 * list could move to, among those the advisories of its findings name as
 * fixes, and which of its findings each one fixes. Built by
 * {@code FixTargetResolver} at read time from the org's vulnerability
 * records, never stored, and, like {@link FixedIn}, the advisories' claim.
 *
 * @param purl    the component's package URL with its version, as its findings carry it
 * @param major   the component's major version, its first number; null when it has none
 * @param targets the versions, lowest first, each fixing at least one of the component's findings
 */
public record ComponentFixTargets(String purl, String major, List<FixTarget> targets) {

	/**
	 * GraphQL {@code FixTarget}: one of the versions.
	 *
	 * @param version   a fix version the advisory of one of the component's findings names
	 * @param sameMajor whether the version is on the component's major version
	 * @param fixes     the vulnerability ids of the component's findings whose ranges the version is out of
	 */
	public record FixTarget(String version, boolean sameMajor, List<String> fixes) {}
}
