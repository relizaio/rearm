/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

/**
 * An SBOM could not be scored, for the reason {@link #getReason()} names.
 *
 * <p>The caller decides on the reason, never on the message: {@code GraphQLExceptionHandlers}
 * surfaces it as {@code extensions.code = SBOM_SCORE_ERROR} with {@code extensions.reason}. The
 * message is written for a person; only the caller-error reasons carry rebom's own words.
 *
 * <p>Unchecked because it is raised from inside the rebom exchange and has exactly one handler.
 */
public class SbomScoreException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public static final String ERROR_CODE = "SBOM_SCORE_ERROR";

	public enum SbomScoreErrorReason {
		/** A profile rebom does not know, or no profile at all. */
		UNKNOWN_PROFILE,
		/** The document is over the scoring service's request limit. */
		INPUT_TOO_LARGE,
		/** The engine refused the document: a format or spec version it cannot score. */
		INPUT_REFUSED,
		/** The artifact is not an SBOM, or has no stored SBOM to fetch. */
		NOT_SCORABLE,
		/** The scoring service did not answer in time. */
		TIMEOUT,
		/** This server's scoring service cannot score: no engine, or no probe at all. */
		SCORING_UNAVAILABLE,
		/** Anything else: the scoring service failed or answered something unusable. */
		SCORING_FAILED
	}

	private final SbomScoreErrorReason reason;

	public SbomScoreException(SbomScoreErrorReason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public SbomScoreErrorReason getReason() {
		return reason;
	}
}
