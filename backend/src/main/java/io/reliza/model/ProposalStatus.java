/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * State of an imported statement proposal (VEX today) awaiting human review. Shared by import
 * types. See ai-plans/vex_imports/05_staging_entity_proposal.md section B.
 */
public enum ProposalStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    SUPERSEDED,
    ERRORED
}
