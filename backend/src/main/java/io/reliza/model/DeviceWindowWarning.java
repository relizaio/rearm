/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.List;

/**
 * One derived observation about a declared device support window (FDAW-3, B3).
 *
 * <p>DERIVED ON READ, NEVER STORED. Served by the {@code DeviceSupportWindow.warnings}
 * field resolver for the component's window and the batch override alike, so one rule
 * covers both writers and every reader. Do not add a {@code getWarnings()} to
 * {@link ComponentData.DeviceSupportWindow}: that class is serialised into the record's
 * JSONB, and a getter would persist the warning as data.
 *
 * <p>The order is a warning, never a refusal. Under IMDRF N70 end of life is the last sale
 * date and normally precedes end of support (the last security update); the reverse is
 * stored as declared and reported here. Equal dates, either date alone, or no window warn
 * nothing: "not declared" is a fact in its own right.
 */
public record DeviceWindowWarning(DeviceWindowWarningCode code, String message) {

	/** The warnings for a window; empty when there is nothing unusual about it. */
	public static List<DeviceWindowWarning> of(ComponentData.DeviceSupportWindow w) {
		if (null == w || null == w.getEos() || null == w.getEol()
				|| !w.getEos().isBefore(w.getEol())) {
			return List.of();
		}
		return List.of(new DeviceWindowWarning(DeviceWindowWarningCode.EOS_BEFORE_EOL,
				"End of support (" + w.getEos() + ", last security update) is before end of life ("
						+ w.getEol() + ", last sale date): units sold after " + w.getEos()
						+ " would have no security support. Saved as declared."));
	}
}
