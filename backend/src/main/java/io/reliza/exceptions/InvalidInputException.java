/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

/**
 * A resolver's own argument that does not fit the type it is read into (task TPF-63): thrown by
 * {@link io.reliza.common.InputConversion} alone, and answered by
 * {@code GraphQLExceptionHandlers.handleInvalidInput} as BAD_REQUEST with this message.
 *
 * <p>The message names the field and what it must be, never the value sent; there is no cause, so
 * the library's own text (which carries the value and class names) never travels with it.
 *
 * <p>Unchecked on purpose: a conversion inside a {@code @Transactional} fetcher must roll back what
 * the transaction wrote before it, as the library's unchecked exception did (a checked exception
 * commits under Spring's default rollback rule), and a site becomes a one-token edit, inside stream
 * lambdas too.
 */
public class InvalidInputException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public InvalidInputException(String message) {
		super(message);
	}
}
