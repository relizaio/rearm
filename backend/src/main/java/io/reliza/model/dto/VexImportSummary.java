/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * Compact, durable outcome of a VEX import, persisted on the VEX
 * {@link io.reliza.model.ArtifactData} that produced it and surfaced to the UI
 * (see the {@code Artifact.vexImportSummary} GraphQL field). Unlike
 * {@link VexImportResult} -- the transient, per-dispatch working object that
 * also carries the created proposal UUIDs -- this holds only the counts a user
 * needs to understand what happened, so an upload that matched nothing shows
 * "N statements, 0 matched" instead of a silently empty VEX tab.
 *
 * <p>{@code proposalsCreated} counts statements staged for review (PENDING);
 * {@code proposalsAutoAccepted} counts statements applied immediately. A
 * statement that matched no component in the release's SBOM inventory is
 * counted in {@code statementsUnmatched} and produces no proposal.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VexImportSummary {
    private int statementsTotal;
    private int proposalsCreated;
    private int proposalsAutoAccepted;
    private int proposalsAutoRejected;
    private int statementsUnmatched;
    private int statementsErrored;
    // Capped at MAX_ERROR_MESSAGES so a pathological VEX can't bloat the artifact record.
    private List<String> errorMessages;

    public static final int MAX_ERROR_MESSAGES = 20;

    public static VexImportSummary fromResult(VexImportResult r) {
        VexImportSummary s = new VexImportSummary();
        s.setStatementsTotal(r.getStatementsTotal());
        s.setProposalsCreated(r.getProposalsCreated());
        s.setProposalsAutoAccepted(r.getProposalsAutoAccepted());
        s.setProposalsAutoRejected(r.getProposalsAutoRejected());
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
