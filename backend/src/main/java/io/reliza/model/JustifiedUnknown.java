/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One justified unknown (FDAW-1 D5, FDAW-14 3.2): why an SBOM element cannot be given. Stored in
 * {@code SupportData.unknowns} for a component's end of support and in
 * {@code ReleaseSbomJustificationData.hashes} for a hashless node of a release SBOM.
 *
 * <p>Only {@code code} reaches a served document (the {@code reliza:support:justifiedUnknown:*}
 * properties); {@code text} stays in ReARM. {@code bulkAct} is the {@code support_bulk_acts.uuid} of
 * the server-minted act that wrote it, null for a single write; a client-supplied id never reaches it.
 * {@code assertedAt} is the caller's {@code assessedAt} (else the write's now), an RFC-3339 UTC
 * instant like {@code SupportData.assessedAt}: the human's date, not the record time, which is the
 * audit row's {@code asserted_date}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JustifiedUnknown(JustifiedUnknownReason code, String text, UUID bulkAct, UUID assertedBy,
		String assertedAt) implements Serializable {
}
