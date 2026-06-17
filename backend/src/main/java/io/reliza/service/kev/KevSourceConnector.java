/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.util.List;

import io.reliza.model.KevAssertionData;
import io.reliza.model.KevSource;

/**
 * One known-exploited-vulnerability source. {@code KevCatalogSyncService}
 * injects every connector bean and reconciles each source's catalog
 * independently, so adding a source (e.g. VulnCheck KEV) is a new bean,
 * no orchestration change.
 */
public interface KevSourceConnector {

	/** Which source this connector speaks for; the reconcile key. */
	KevSource source();

	/**
	 * Fetch and normalize the source's complete current catalog. Returns an
	 * empty list to signal a failed or empty fetch — the orchestrator then
	 * skips reconciliation for this source (fail-closed), so a bad fetch
	 * never revokes live assertions. Implementations must not throw.
	 */
	List<KevAssertionData> fetchCatalog();
}
