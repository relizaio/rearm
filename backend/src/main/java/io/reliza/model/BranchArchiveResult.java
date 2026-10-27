/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.UUID;

/**
 * One branch's outcome from a bulk archive, returned per item rather than aggregated.
 *
 * @param branchUuid the branch this outcome is about
 * @param outcome    what happened to it
 * @param message    why, when the outcome is {@link BranchArchiveOutcome#FAILED}; null
 *                   otherwise. Never a database or driver message.
 */
public record BranchArchiveResult(UUID branchUuid, BranchArchiveOutcome outcome, String message) {

	public static BranchArchiveResult archived(UUID uuid) {
		return new BranchArchiveResult(uuid, BranchArchiveOutcome.ARCHIVED, null);
	}

	/** An outcome that wrote nothing and needs no message: a skip, NOT_FOUND or FORBIDDEN. */
	public static BranchArchiveResult skipped(UUID uuid, BranchArchiveOutcome outcome) {
		return new BranchArchiveResult(uuid, outcome, null);
	}

	public static BranchArchiveResult failed(UUID uuid, String message) {
		return new BranchArchiveResult(uuid, BranchArchiveOutcome.FAILED, message);
	}
}
