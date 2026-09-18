/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.UUID;

/**
 * One component's outcome from a bulk attestation, returned per item rather than aggregated.
 *
 * @param sbomComponentUuid the component this outcome is about
 * @param outcome           what happened to it
 * @param message           why, when the outcome is {@link SupportBulkOutcome#FAILED}; null
 *                          otherwise. Never carries the attestation content back -- the
 *                          caller already has it, and echoing it invites a client to treat
 *                          the response as confirmation of what was stored rather than
 *                          re-reading.
 */
public record SupportBulkResult(UUID sbomComponentUuid, SupportBulkOutcome outcome, String message) {

	public static SupportBulkResult applied(UUID uuid) {
		return new SupportBulkResult(uuid, SupportBulkOutcome.APPLIED, null);
	}

	public static SupportBulkResult skipped(UUID uuid, SupportBulkOutcome outcome) {
		return new SupportBulkResult(uuid, outcome, null);
	}

	public static SupportBulkResult failed(UUID uuid, String message) {
		return new SupportBulkResult(uuid, SupportBulkOutcome.FAILED, message);
	}
}
