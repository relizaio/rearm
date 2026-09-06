/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.LocalDate;

/**
 * The device's declared support window -- its end-of-support and end-of-life dates
 * ({@code ReleaseData.eos}/{@code ReleaseData.eol} of the enclosing PRODUCT release,
 * both tagged FDA section-524B). Passed as one value (rather than two adjacent
 * {@code LocalDate}s) so the caller cannot transpose them and so the
 * horizon rule lives in exactly one place ({@link #supportHorizon()}).
 *
 * @param endOfSupport when the manufacturer stops supporting the device (patches cease), or null
 * @param endOfLife    when the device reaches end of life, or null
 */
public record DeviceLifecycle(LocalDate endOfSupport, LocalDate endOfLife) {

	/**
	 * The single date a component's support window is measured against: the device's
	 * end-of-support, the section-524B patch-commitment horizon. Null when the device does
	 * not declare one -- then no risk can be assessed.
	 *
	 * <p><b>End-of-life is NOT a fallback here</b> (operator decision D5, 2026-09-03).
	 * CycloneDX defines end-of-life as when the manufacturer stops SELLING, which routinely
	 * precedes end of support by years. Falling back to it would answer "how long is this
	 * device supported?" with a sales date -- overstating or understating the window
	 * depending on the vendor, and doing so silently. A device with no declared
	 * end-of-support has no horizon, and saying so is the honest answer.
	 */
	public LocalDate supportHorizon() {
		return endOfSupport;
	}

	/** Whether the device declares a support horizon to compare component dates against. */
	public boolean hasHorizon() {
		return supportHorizon() != null;
	}

	/**
	 * Whether the device declares ANY lifecycle date, horizon or not.
	 *
	 * <p>Distinct from {@link #hasHorizon()} on purpose. Once D5 narrowed the horizon to
	 * end-of-support, gating on it would have deleted a device's declared END-OF-LIFE from
	 * exports entirely: a device that publishes only an end-of-life date has no horizon, but
	 * it does have a date a reader is entitled to see. Suppressing the RISK VERDICT for such
	 * a device is D5 working as intended; suppressing the date itself would be silent removal
	 * of a disclosure from a regulatory artifact, which D5 never asked for.
	 */
	public boolean declaresAnyDate() {
		return endOfSupport != null || endOfLife != null;
	}
}
