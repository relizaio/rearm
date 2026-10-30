/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import java.util.UUID;

import io.reliza.model.SubmissionRefusal;

/**
 * A submission act was refused, and why (task TEA-35, the {@link TeaPublishRefusedException}
 * shape). Callers switch on {@link #reason()}, never on the message; the message is one line
 * naming the release and the fact. A RelizaException, so the GraphQL handlers carry the message to
 * the caller as BAD_REQUEST.
 */
public class SubmissionRefusedException extends RelizaException {

	private static final long serialVersionUID = 2026102001L;

	private final SubmissionRefusal reason;
	private final UUID release;

	public SubmissionRefusedException(SubmissionRefusal reason, UUID release, String message) {
		super(message);
		this.reason = reason;
		this.release = release;
	}

	public SubmissionRefusal reason() {
		return reason;
	}

	public UUID release() {
		return release;
	}
}
