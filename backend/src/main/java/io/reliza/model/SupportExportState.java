/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Whether an org's BOM exports carry the FDA support-disclosure properties.
 *
 * <p>An enum rather than a boolean because an export setting is the kind of field that grows
 * a third state -- inherited from a parent scope, a per-release override, enabled but with
 * nothing to inject -- and this type is published verbatim to the Community Edition, where a
 * Boolean could not grow one without breaking clients. {@code SidPurlMode} models org-level
 * configuration the same way.
 */
public enum SupportExportState {
	/**
	 * The org's exports carry the support properties.
	 *
	 * <p>Three egresses, not every path: the native single-artifact download, the
	 * SPDX-augmented download and the merged release export. The raw artifact download is
	 * swept for forged {@code reliza:*} provenance and marked, but never injected into, on
	 * any setting.
	 */
	ENABLED,
	/** No export path carries them: the counts describe data that is not being shipped. */
	DISABLED,
	/**
	 * RETIRED. Never returned.
	 *
	 * <p>It described a real state that no longer exists: injection reaching some egresses and
	 * not others, back when the native artifact download injected while the SPDX-augmented
	 * form and the release-level export did not. One org setting now gates all three, so an
	 * export either carries the properties or it does not.
	 *
	 * <p>RETAINED because this enum is published verbatim to the Community Edition and a
	 * client built against it must keep parsing. A client still branching on it should read it
	 * as it always meant: do not assume this export carries the disclosure.
	 */
	@Deprecated
	PARTIAL,
	/**
	 * The server could not determine the setting -- absent or unreadable once this reads a
	 * stored value. Never collapse to ENABLED or DISABLED; saying so beats guessing.
	 */
	UNKNOWN
}
