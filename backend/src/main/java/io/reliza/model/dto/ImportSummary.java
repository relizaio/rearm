/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Compact, durable outcome of an inbound artifact import (VEX today), returned by
 * every {@code ArtifactImportHandler}, persisted on the
 * {@code ArtifactData} that produced it and surfaced to the UI
 * (see the {@code Artifact.importSummary} GraphQL field, and its deprecated
 * alias {@code vexImportSummary}). Unlike {@link VexImportResult} -- VEX's
 * transient, per-dispatch working object that also carries the created proposal
 * UUIDs -- this holds only the counts a user needs to understand what happened,
 * so an upload that matched nothing shows "N statements, 0 matched" instead of a
 * silently empty tab. Formerly VexImportSummary.
 *
 * <p>{@code proposalsCreated} counts statements staged for review (PENDING);
 * {@code proposalsAutoAccepted} counts statements applied immediately. A
 * statement that matched no component in the release's SBOM inventory is
 * counted in {@code statementsUnmatched} and produces no proposal.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImportSummary {
    private int statementsTotal;
    private int proposalsCreated;
    private int proposalsAutoAccepted;
    private int proposalsAutoRejected;
    // VEX only (0 for other types): subset of proposalsAutoAccepted whose analysis
    // write is deferred behind a PENDING MitigationAttestation (conditional claims).
    private int attestationsDeferred;
    private int statementsUnmatched;
    private int statementsErrored;
    // Capped at MAX_ERROR_MESSAGES so a pathological VEX can't bloat the artifact record.
    private List<String> errorMessages;

    public static final int MAX_ERROR_MESSAGES = 20;

    public static ImportSummary fromResult(VexImportResult r) {
        ImportSummary s = new ImportSummary();
        s.setStatementsTotal(r.getStatementsTotal());
        s.setProposalsCreated(r.getProposalsCreated());
        s.setProposalsAutoAccepted(r.getProposalsAutoAccepted());
        s.setProposalsAutoRejected(r.getProposalsAutoRejected());
        s.setAttestationsDeferred(r.getAttestationsDeferred());
        s.setStatementsUnmatched(r.getStatementsUnmatched());
        s.setStatementsErrored(r.getStatementsErrored());
        List<String> errors = r.getErrorMessages();
        if (errors != null && !errors.isEmpty()) {
            s.setErrorMessages(errors.size() > MAX_ERROR_MESSAGES
                ? List.copyOf(errors.subList(0, MAX_ERROR_MESSAGES))
                : List.copyOf(errors));
        }
        return s;
    }
}
