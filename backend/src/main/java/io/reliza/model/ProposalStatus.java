/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/** State of a VEX statement proposal awaiting human review. See ai-plans/vex_imports/05_staging_entity_proposal.md §B. */
public enum ProposalStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    SUPERSEDED,
    ERRORED
}
