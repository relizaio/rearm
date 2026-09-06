/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.LocalDate;

/**
 * Whether a component's declared support window ends before the enclosing device's own
 * support horizon (FDA-Readiness-1 PR5). The Feb-2026 premarket guidance flags any component
 * whose end-of-support precedes the device's support window -- such a component goes unsupported while
 * the device is still fielded (the "log4j in a 15-year device" case), which the manufacturer
 * must disclose and address.
 *
 * <p>The device horizon is the device's END-OF-SUPPORT, the section-524B patch-commitment
 * window -- see {@link DeviceLifecycle#supportHorizon()}. There is no end-of-life fallback:
 * since D5 that means end of SALE, and measuring support against a sales date would state a
 * support window the manufacturer never declared. This is computed only for
 * PRODUCT (device) releases; on a plain component/library release the "device" concept does not
 * apply and the caller passes no {@link DeviceLifecycle}, yielding {@link #UNKNOWN}.
 *
 * <p>DERIVED, never persisted -- a sibling of {@link SupportStatus}. Computed by {@link #derive}
 * from the component's attested dates ({@code sbom_components} support columns) and the device's
 * {@link DeviceLifecycle}. Every surface that shows the flag calls this one method so it can
 * never drift. There is no {@code asOf} clock: the flag is a static comparison of two declared
 * horizons, not a "has it happened yet" question, so it reads the same whenever evaluated.
 */
public enum DeviceSupportRisk {
	/** Component support/life extends to or beyond the device's support horizon, or the flag does not apply. */
	OK,
	/** Component's end-of-support falls before the device's support horizon (support stops while device still fielded). */
	EOS_BEFORE_DEVICE,
	/** Cannot assess: the device declares no support horizon, or the component has no declared support window. */
	UNKNOWN;

	/** Whether this is an actionable at-risk verdict (i.e. an assessed component that fails the check). */
	public boolean isFlagged() {
		return this == EOS_BEFORE_DEVICE;
	}

	/**
	 * Single source of truth for the device-support-risk flag. Pure: identical inputs always
	 * yield the same value. Boundaries are exclusive -- a component whose support ends exactly ON
	 * the device's support horizon is {@link #OK} (it lasts through the device's declared support).
	 * Only end-of-support participates. A component's end-of-life is deliberately absent:
	 * since D5 it means end of SALE, and a component that left the price list before the
	 * device horizon may still be patched, so it carries no risk to disclose.
	 *
	 * @param endOfSupportDate component's end-of-support (all support ceases), or null
	 * @param device           the device's declared support window, or null when the concept does
	 *                         not apply (non-PRODUCT release) / no device dates
	 * @return the derived risk; {@link #UNKNOWN} when the device declares no horizon or the
	 *         component declares no end-of-support
	 */
	public static DeviceSupportRisk derive(LocalDate endOfSupportDate, DeviceLifecycle device) {
		LocalDate horizon = device == null ? null : device.supportHorizon();
		if (horizon == null) {
			// No device support horizon -> the comparison is undefined, not benign.
			return UNKNOWN;
		}
		if (endOfSupportDate == null) {
			// No declared end-of-support -> nothing to compare. An end-of-life date does NOT
			// substitute: since D5 it means end of SALE, and a component still being patched
			// after it left the price list carries no risk to disclose.
			return UNKNOWN;
		}
		if (endOfSupportDate.isBefore(horizon)) {
			return EOS_BEFORE_DEVICE;
		}
		return OK;
	}
}
