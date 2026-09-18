/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only
*/

package io.reliza.model;

/**
 * Whether this organization's exports CARRY its support attestations.
 *
 * <p>Two values, and deliberately not three. {@link SupportExportState} has PARTIAL and
 * UNKNOWN members, but those are things that are OBSERVED about a running system, not things
 * an operator chooses: PARTIAL described injection reaching some egresses and not others, and
 * UNKNOWN describes a settings row that could not be read. Storing either would let an
 * operator configure a state that means "we are not sure what we do".
 *
 * <p>NULL MEANS DISABLED (decision D3). The default is off: support properties do not appear
 * in an export unless someone asks for them. The consequence that has to be designed around
 * -- and is, in the coverage gauge -- is that a manufacturer preparing a submission must
 * deliberately turn this on, so a gauge reporting full attestation coverage beside an export
 * carrying nothing would be a lie by omission.
 *
 * <p>This switch does NOT govern the forged-provenance strip. The strip runs on every egress
 * whatever this says, because a security control and a content choice are not the same
 * switch: turning injection off must never turn off the thing that stops an uploader forging
 * our attribution.
 */
public enum SupportInjectionSetting {
	/**
	 * Attestations are injected into exports.
	 *
	 * <p>THREE egresses, not the four the strip covers: the native single-artifact download,
	 * the SPDX-augmented download and the merged release export. The raw artifact download is
	 * swept for forged {@code reliza:*} provenance and marked, but never injected into, on any
	 * setting -- so it is NOT served as uploaded, and describing it that way is how someone
	 * concludes the anti-spoofing sweep skips it.
	 */
	ENABLED,
	/** Exports carry no support properties. The default, including when unset. */
	DISABLED
}
