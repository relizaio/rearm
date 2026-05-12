/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/** Signature verification result for an inbound VEX document. v1 always sets UNSIGNED — see ai-plans/vex_imports/open_questions.md §7.2. */
public enum SignatureStatus {
    UNSIGNED,
    VERIFIED_TRUSTED,
    VERIFIED_UNTRUSTED,
    INVALID
}
