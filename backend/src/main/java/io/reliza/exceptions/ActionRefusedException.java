/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

/**
 * An action was refused by a policy the organization configured, rather than failing.
 *
 * <p>Unchecked so it can be raised from deep inside call chains that were never declared to
 * throw -- a refusal is a new outcome for those paths, and threading a checked exception through
 * every caller would say nothing extra. The message names what refused and is written to be read
 * by the person who asked for the action, so {@code GraphQLExceptionHandlers} surfaces it as a
 * client error instead of the generic "Internal server error" an unmapped runtime exception gets.
 */
public class ActionRefusedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ActionRefusedException(String message) {
		super(message);
	}

	public ActionRefusedException(String message, Throwable cause) {
		super(message, cause);
	}
}
