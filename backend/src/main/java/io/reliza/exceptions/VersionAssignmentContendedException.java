/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

/**
 * A version could not be assigned because concurrent assignments kept taking the version it
 * computed. Nothing is wrong with the request; asking again succeeds. Distinct from a refusal so
 * callers that report "could not mint" themselves can tell the two apart.
 */
public class VersionAssignmentContendedException extends RelizaException {

	private static final long serialVersionUID = 1L;

	public VersionAssignmentContendedException(String message) {
		super(message);
	}
}
