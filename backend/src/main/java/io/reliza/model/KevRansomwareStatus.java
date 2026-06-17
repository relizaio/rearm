/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Whether a source reports a vulnerability as used in known ransomware
 * campaigns. A three-state enum rather than a boolean so the "source
 * looked and doesn't know of ransomware use" ({@link #UNKNOWN}) and "the
 * source carries no opinion" ({@link #UNSPECIFIED}) cases stay distinct —
 * they collapse under a boolean and matter once multiple sources disagree.
 *
 * <p>CISA publishes the strings {@code "Known"} / {@code "Unknown"};
 * anything absent maps to {@link #UNSPECIFIED}. Top-level so DGS maps the
 * GraphQL {@code KevRansomwareStatus} enum by simple name.
 */
public enum KevRansomwareStatus {
	/** Source asserts the vulnerability is used in ransomware campaigns. */
	KNOWN,
	/** Source explicitly reports no known ransomware use. */
	UNKNOWN,
	/** Source provides no ransomware signal. */
	UNSPECIFIED
}
