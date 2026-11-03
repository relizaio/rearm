/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import io.reliza.model.ArtifactScanState;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.ScanBlockerEnrichmentState;

/**
 * Why one artifact is or is not scanned (TPF-121), the answer of the browser query
 * {@code artifactScanStatus}, computed read-only by SyntheticSbomService.explainArtifactScanStatus with the
 * fan-out gate's own predicate. Every field is served to every caller who clears the artifact's read
 * permission (one reader tier, the operator's decision of 2026-10-29). Dates are UTC.
 */
public record ArtifactScanStatusDto(UUID artifactUuid, String type, ArtifactScanState state, boolean mapped,
		UUID canonicalArtifactUuid, ZonedDateTime mappedAt, ZonedDateTime firstScanned, ZonedDateTime lastScanned,
		ZonedDateTime fanOutAt, ZonedDateTime fanOutRequeuedAt, ZonedDateTime newestIngestedBucketUpdate,
		int ingestedBucketCount, Boolean enrichmentGated, boolean inCandidateSet, String candidateRule,
		int matchableComponentCount, int rootComponentCount, int terminalComponentCount,
		int unmatchableComponentCount, NoMatchableComponentsReason noMatchableComponentsReason,
		ZonedDateTime notScannableAt, int blockerTotal, boolean blockersTruncated, List<ScanBlockerDto> blockers) {

	/**
	 * One gate-matchable component of the artifact's canonical that no INGESTED bucket covers. No
	 * Dependency-Track project uuid: it is the one identifier usable outside ReARM (TPF-121 section 2 item 3).
	 */
	public record ScanBlockerDto(String purl, ScanBlockerEnrichmentState enrichment, String terminalReason,
			Integer bucketIndex, String bucketIngestState, ZonedDateTime bucketLastUpdatedDate, String reason) {}
}
