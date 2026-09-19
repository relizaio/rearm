/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Whether the manufacturer is the first party for a component's support
 * facts (it is the org's own device/release) or a third party making a
 * claim about a component it has no operational control over (e.g. log4j).
 * Orthogonal to {@link SupportSource}: a supplier-provided BOM asserting a
 * date for the org's OWN in-house library is a first-party fact learned
 * through a supplier channel, not a third-party one.
 *
 * <p>Nullable on {@link SbomComponent} means UNKNOWN -- never infer an
 * implicit {@code THIRD_PARTY}. {@code isRoot} may prefill a UI suggestion
 * but never silently derives the stored fact (a non-root first-party
 * in-house library would be wrong under pure derivation).
 */
public enum SupportParty {
	FIRST_PARTY("manufacturer"),
	THIRD_PARTY("supplier");

	private final String cycloneDxPartyRole;

	SupportParty(String cycloneDxPartyRole) {
		this.cycloneDxPartyRole = cycloneDxPartyRole;
	}

	/**
	 * The CycloneDX party role this maps to, for the {@code declarations} block.
	 *
	 * <p>Fixed NOW, while there is one writer, rather than when CycloneDX 2.0's perspectival
	 * {@code evidence.assertion} becomes usable. The stored JSONB keeps the ReARM names, so
	 * pinning the wire mapping here is what lets 2.0 arrive without a second payload shape or
	 * a migration -- the value on the wire is derived, never stored.
	 *
	 * <p>NOT the same distinction as {@code assessors[].thirdParty}, which this also drives.
	 * That boolean says who ASSESSED; this role says what the party IS to the component. They
	 * agree today because one field feeds both, and they are separate concepts the moment a
	 * third party assesses a first-party component.
	 */
	public String getCycloneDxPartyRole() {
		return cycloneDxPartyRole;
	}

	/** Whether an assessment by this party is third-party, for {@code assessors[].thirdParty}. */
	public boolean isThirdParty() {
		return this == THIRD_PARTY;
	}
}
