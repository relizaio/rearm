/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/** Signature verification result for an inbound VEX document. Always set to UNSIGNED today; real verification is a future addition — see ai-plans/vex_imports/open_questions.md (trusted-vendor signature verification). */
public enum SignatureStatus {
    UNSIGNED,
    VERIFIED_TRUSTED,
    VERIFIED_UNTRUSTED,
    INVALID
}
