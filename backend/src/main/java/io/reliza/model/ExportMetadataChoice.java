/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * What an export CALLER asked for about one class of metadata, as three states rather than two.
 *
 * <p>A nullable Boolean carries exactly this domain, and carrying it as one is how the third
 * state gets lost: DEFAULT is not "false", it is "the caller said nothing and today's behaviour
 * applies". Every existing caller -- the CLI, the TEA endpoints, an API consumer written before
 * these arguments existed -- is DEFAULT, and the one guarantee this feature owes them is that
 * their exports do not change. An enum makes that guarantee legible at the comparison site;
 * {@code Boolean.TRUE.equals(flag)} scattered across three services does not.
 *
 * <p>Parsed ONCE, at the GraphQL/REST boundary, via {@link #fromCallerInput}. Nothing below the
 * boundary sees a Boolean.
 */
public enum ExportMetadataChoice {
	/** The caller did not say. Today's behaviour applies -- org setting for support, keep for internal. */
	DEFAULT,
	/** The caller explicitly asked for this metadata. */
	INCLUDE,
	/** The caller explicitly asked for it to be left out. */
	EXCLUDE;

	/**
	 * The boundary parser: a wire Boolean (nullable) becomes the choice it means.
	 *
	 * @param raw the caller's argument; null when the argument was omitted
	 */
	public static ExportMetadataChoice fromCallerInput(Boolean raw) {
		if (null == raw) return DEFAULT;
		return raw ? INCLUDE : EXCLUDE;
	}

	/** The wire form, for the download log and for round-tripping back to a client. */
	public Boolean toCallerInput() {
		return switch (this) {
			case DEFAULT -> null;
			case INCLUDE -> Boolean.TRUE;
			case EXCLUDE -> Boolean.FALSE;
		};
	}
}
