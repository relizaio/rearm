/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/** Lifecycle of a consumer-side mitigation attestation. See ai-plans/vex_imports/06_conditional_mitigation_workflow.md. */
public enum AttestationStatus {
    PENDING,
    ATTESTED,
    WAIVED,
    EXPIRED
}
