/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

/**
 * The rights of the principal behind an SBOM upload, as rule B reads them (SCORE-52 ADR-2): write
 * on the whole organization, or write on one component. Built by {@link SbomWriteAuthorityService}
 * after the entry point's own authorization passed, from the same principal, and bound to one
 * organization: a component of another organization, or a missing one, is never writable. Lives
 * for one request and is never stored in a bean or a cache.
 */
public interface SbomWriteAuthority {

	/** Write on everything of the organization: rule B passes without a holder read. */
	boolean orgWideWrite();

	/** Write on this component, which must be of the authority's organization. */
	boolean canWrite(UUID component);

	/** For logs only: "USER:&lt;uuid&gt;", "KEY:&lt;type&gt;:&lt;key uuid&gt;", "NONE"; never a secret. */
	String describe();

	/**
	 * No principal: false for both questions. The VDR snapshot only (SCORE-52 ADR-5), whose upload
	 * never reaches rule B; if it ever did with an existing serialNumber, it would be refused.
	 */
	SbomWriteAuthority NO_PRINCIPAL = new SbomWriteAuthority() {
		@Override
		public boolean orgWideWrite() {
			return false;
		}

		@Override
		public boolean canWrite(UUID component) {
			return false;
		}

		@Override
		public String describe() {
			return "NONE";
		}
	};
}
