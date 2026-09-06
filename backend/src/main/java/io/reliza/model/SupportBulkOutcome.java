/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * What a bulk attestation did to one component.
 *
 * <p>An enum on the wire rather than a count, because skip-and-report is only useful if the
 * caller can show WHICH rows were skipped and why. A bulk run over 450 components that
 * reports "312 applied" leaves the operator with no way to find the other 138, and the two
 * skip reasons need different responses: a ROOT component is the application itself and
 * never needs attesting, while an already-attested one is someone else's recorded judgement
 * that a bulk pass must not silently overwrite.
 */
public enum SupportBulkOutcome {

	/** The attestation was written. */
	APPLIED,

	/**
	 * Skipped: the component is the application itself, not a third-party dependency. Root
	 * components are excluded from the coverage denominator for the same reason.
	 */
	SKIPPED_ROOT,

	/**
	 * Skipped: an attestation already exists and a bulk pass never overwrites one. This is
	 * what makes a re-run idempotent, and it protects a considered per-component judgement
	 * from being flattened by a later sweep.
	 */
	SKIPPED_ATTESTED,

	/**
	 * This component's write was rejected or failed. The batch continues; the reason is on
	 * the result. Isolated in its own transaction so a failure here cannot roll back the
	 * components that already succeeded.
	 */
	FAILED
}
