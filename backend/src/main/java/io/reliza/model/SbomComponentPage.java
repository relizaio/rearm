/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.List;
import java.util.UUID;

/**
 * One page of a release's component ids, plus the total the filter matched and the cursor
 * that continues the walk.
 *
 * <p>{@code totalCount} is the FILTERED population, not the release's size and not the
 * coverage denominator. Those are three different numbers and the UI shows two of them at
 * once: the gauge says how much of the BOM is disclosed, the footer says how far through the
 * current filter the operator is. Conflating them is how "showing 1-50 of 1,240" ends up
 * over a filtered list of 34.
 *
 * <p>{@code endCursor} is the uuid of the last item, or null on an empty page.
 * {@code hasMore} is answered by over-fetching one row rather than by comparing counts
 * against {@code totalCount} -- under the UNATTESTED filter the population changes as the
 * caller writes, so a count comparison would go wrong exactly when it mattered.
 */
public record SbomComponentPage(List<UUID> componentUuids, long totalCount, int limit,
		UUID endCursor, boolean hasMore, List<ArtifactSbomComponent> resolvedArtifactRows) {

	/**
	 * {@code resolvedArtifactRows} is a hand-back, not page data: the artifact rows the
	 * release already resolved to while computing this page. The caller needs the merged
	 * component rows next, and re-deriving them would resolve the release a SECOND time --
	 * loading it, walking the PRODUCT dependency unwind, and fetching every
	 * artifact_sbom_components row for the whole release again. Paying that twice to serve
	 * fifty rows would make the paged query slower than the unpaged one on exactly the large
	 * product releases it exists for.
	 */
	public static SbomComponentPage empty(int limit) {
		return new SbomComponentPage(List.of(), 0, limit, null, false, List.of());
	}
}
