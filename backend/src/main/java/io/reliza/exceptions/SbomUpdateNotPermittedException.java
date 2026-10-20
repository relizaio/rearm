/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

/**
 * An SBOM upload was refused because it would store a new version of a serialNumber that already
 * exists in the organization, and the caller has neither organization-wide write nor write on every
 * component that holds it (SCORE-52 rule B). Raised by rearm-saas before any byte of the upload
 * leaves ({@code SbomUpdateGuard.refuseUnauthorizedUpdate}).
 *
 * <p>{@code GraphQLExceptionHandlers} surfaces it as {@code extensions.code} =
 * {@value #ERROR_CODE}. No holder is ever in the message, the extensions or the refusal record:
 * the serialNumber is the caller's own input, the holders are not.
 *
 * <p>Unchecked, as {@link SbomScoreException} is: it has exactly one handler, and every
 * {@code @Transactional} boundary it crosses rolls back by Spring's default rule, so no resolver
 * needs a {@code rollbackFor}.
 */
public class SbomUpdateNotPermittedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public static final String ERROR_CODE = "SBOM_SERIAL_NUMBER_UPDATE_NOT_PERMITTED";

	private final String serialNumber;

	private final int version;

	public SbomUpdateNotPermittedException(String message, String serialNumber, int version) {
		super(message);
		this.serialNumber = serialNumber;
		this.version = version;
	}

	/** The refusal text, with the serialNumber as the caller sent it; it names nothing else. */
	public static String messageFor(String serialNumber) {
		return "BOM with serialNumber " + serialNumber + " already exists in this organization. "
				+ "Uploading a new version of it needs write permission on every component that holds it, "
				+ "or write permission on the whole organization. "
				+ "Upload an SBOM with its own serialNumber, or ask an organization administrator.";
	}

	public String getSerialNumber() {
		return serialNumber;
	}

	/** The uploaded version rule B refused. */
	public int getVersion() {
		return version;
	}
}
