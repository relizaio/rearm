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
	/** EVERY export path carries the support properties. */
	ENABLED,
	/** No export path carries them: the counts describe data that is not being shipped. */
	DISABLED,
	/**
	 * SOME export paths carry them and others do not -- the state today, and the reason this
	 * is not a boolean.
	 *
	 * <p>{@code injectCurrentSupport} has exactly one production call site: the native
	 * CycloneDX single-artifact download. The SPDX-augmented branch of that same download
	 * serves the BOM un-injected, and the release-level SBOM export never injects at all --
	 * it returns the stored BOM directly.
	 *
	 * <p>Reporting ENABLED would therefore be false: an org at full coverage can export a
	 * release SBOM carrying no disclosure whatsoever while a gauge says exports are on. That
	 * is the same lie-by-omission a default-off toggle creates, arrived at from the opposite
	 * direction, and it would ship on day one. Clients must treat anything other than
	 * ENABLED as "do not assume the export carries this".
	 */
	PARTIAL,
	/**
	 * The server could not determine the setting -- absent or unreadable once this reads a
	 * stored value. Never collapse to ENABLED or DISABLED; saying so beats guessing.
	 */
	UNKNOWN
}
