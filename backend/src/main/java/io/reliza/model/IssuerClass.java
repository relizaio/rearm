/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Classifies the relationship between the issuer of a VEX statement and the org receiving it.
 * Drives default trust policy: SELF is fully trusted, VENDOR is conditionally trusted,
 * THIRD_PARTY requires staging. See ai-plans/vex_imports/08_trust_policy.md.
 */
public enum IssuerClass {
    /** Self-issued: the org's own VEX about its own release/SCE/outbound deliverable. */
    SELF,
    /** Vendor-issued: VEX bundled with an inbound deliverable (vendor-supplied). */
    VENDOR,
    /** Anything else: standalone VEX from an external party not tied to a delivery. */
    THIRD_PARTY
}
