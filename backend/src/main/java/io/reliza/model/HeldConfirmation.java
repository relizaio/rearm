/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The confirmation a draft holds between Confirm and Finalize, of the build it names by
 * {@code buildFingerprint}; cleared by Return to draft and by any Rebuild.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HeldConfirmation(UUID actor, String actorName, String actorEmail, ConfirmationCapacity capacity,
		String statementId, int statementVersion, ZonedDateTime confirmedAt, String buildFingerprint)
		implements Serializable {}
