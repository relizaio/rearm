/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import io.reliza.model.KevSource;

/**
 * One known-exploited-vulnerability source. {@code KevCatalogSyncService}
 * injects every connector bean and, for each org with that source enabled,
 * runs {@link #fetchCatalog(String)} with the org's credential (or null if
 * the source doesn't need one) and writes the result under that org. So
 * adding a source is a new bean + an {@code IntegrationType} value, no
 * orchestration change.
 */
public interface KevSourceConnector {

	/** Which source this connector speaks for; the reconcile key. */
	KevSource source();

	/**
	 * Fetch and normalize the source's complete current catalog, with its
	 * header when the source publishes one (FDAW-2: the catalog version and
	 * release date a VDR names). The {@code credential} is the per-org token
	 * (e.g. VulnCheck Bearer) for sources that need one; sources that read a
	 * public feed (CISA) ignore it. Returns empty entries to signal a failed
	 * or empty fetch -- the orchestrator then skips reconciliation for that
	 * (org, source), so a bad fetch never revokes live assertions.
	 * Implementations must not throw.
	 */
	KevCatalogFetch fetchCatalog(String credential);
}
