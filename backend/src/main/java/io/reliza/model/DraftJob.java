/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A draft's build job (FDAW-1 D3): the last request and how it stands. {@code attempt} counts the
 * drain's re-claims of one request and a user's request resets it. {@code failureText} holds a fixed
 * text and readable names only, never an exception's message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DraftJob(DraftBuildState state, ZonedDateTime requestedAt, DraftActor requestedBy,
		ZonedDateTime startedAt, ZonedDateTime finishedAt, int attempt, SubmissionRefusal failureRefusal,
		String failureText) implements Serializable {

	/** The job of a draft never rebuilt. */
	public static DraftJob none() {
		return new DraftJob(DraftBuildState.NONE, null, null, null, null, 0, null, null);
	}
}
