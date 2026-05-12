/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.Data;

@Data
public class VexImportResult {
    private UUID sourceArtifact;
    private int statementsTotal;
    private int proposalsCreated;
    private int proposalsAutoAccepted;
    private int proposalsAutoRejected;
    private int proposalsDemoted;
    private int statementsErrored;
    private int statementsUnmatched;
    private List<UUID> createdProposalUuids = new LinkedList<>();
    private List<String> errorMessages = new LinkedList<>();
    /** Counter map: demotion reason → number of proposals demoted with that reason. */
    private Map<String, Integer> demotionReasons = new LinkedHashMap<>();
}
