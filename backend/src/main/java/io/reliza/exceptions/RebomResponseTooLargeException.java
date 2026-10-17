/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import io.reliza.service.RebomService;

/**
 * An answer from rebom was larger than the backend buffers for one read
 * ({@link RebomService#REBOM_RESPONSE_LIMIT}), so the document it carried was not read.
 *
 * <p>Raised in place of the decoder's buffer-limit failure, which otherwise reaches the GraphQL
 * layer as "Internal server error". The score path turns it into {@code INPUT_TOO_LARGE}; any
 * other caller gets the message {@code GraphQLExceptionHandlers} gives it. Unchecked because it
 * crosses the reactive boundary of every rebom read.
 */
public class RebomResponseTooLargeException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public static final String MESSAGE = "Answer from rebom is over the " + RebomService.REBOM_RESPONSE_LIMIT_MB + " MB read limit";

	public RebomResponseTooLargeException(Throwable cause) {
		super(MESSAGE, cause);
	}
}
