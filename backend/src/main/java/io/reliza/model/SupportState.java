/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Lifecycle of a support attestation record itself, as distinct from the
 * support lifecycle of the component it describes ({@link SupportStatus}).
 *
 * <p>{@code WITHDRAWN} exists because a regulatory record is corrected by
 * SUPERSEDING it, never by erasing it. A manufacturer who discovers an
 * attestation was made in error must be able to say so on the record; deleting
 * the row would destroy the ALCOA history that makes the correction credible.
 * A withdrawn row still carries its milestones and its justification -- it is
 * evidence of what was believed and when, plus the retraction.
 */
public enum SupportState {
	ATTESTED,
	WITHDRAWN
}
