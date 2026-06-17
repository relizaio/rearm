/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * A source that asserts vulnerabilities are known to be exploited. Stored
 * as the {@code source} column on {@link KevAssertion} (one row per
 * source per CVE), so the same CVE can be asserted independently by
 * several sources.
 *
 * <p>Top-level so DGS can map a GraphQL {@code KevSource} enum onto this
 * Java type by simple-name lookup. Each constant is wired by a
 * {@code KevSourceConnector} bean; ENISA EUVD etc. slot in the same way,
 * no schema change.
 */
public enum KevSource {
	CISA("CISA Known Exploited Vulnerabilities Catalog"),
	VULNCHECK("VulnCheck Known Exploited Vulnerabilities");

	private final String displayName;

	KevSource(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}
}
