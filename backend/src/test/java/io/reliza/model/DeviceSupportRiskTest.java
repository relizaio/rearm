/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Exhaustive branch coverage for the pure {@link DeviceSupportRisk#derive} comparator
 * (FDA-Readiness-1 PR5). Device horizon is EOS-primary / EOL-fallback; boundaries exclusive;
 * EOS outranks EOL; missing device horizon or missing component window is UNKNOWN; the
 * {@code source} param is reserved and must not affect the result.
 */
class DeviceSupportRiskTest {

	private static DeviceLifecycle device(LocalDate eos, LocalDate eol) {
		return new DeviceLifecycle(eos, eol);
	}

	@Test
	void nullDeviceIsUnknown() {
		assertEquals(DeviceSupportRisk.UNKNOWN,
				DeviceSupportRisk.derive(LocalDate.of(2025, 1, 1), null));
	}

	@Test
	void deviceWithNoHorizonIsUnknown() {
		assertEquals(DeviceSupportRisk.UNKNOWN,
				DeviceSupportRisk.derive(LocalDate.of(2025, 1, 1), device(null, null)));
	}

	@Test
	void noComponentEndOfSupportIsUnknown() {
		assertEquals(DeviceSupportRisk.UNKNOWN,
				DeviceSupportRisk.derive(null, device(LocalDate.of(2030, 1, 1), null)));
	}

	@Test
	void componentEosBeforeDeviceHorizonFlagsEos() {
		assertEquals(DeviceSupportRisk.EOS_BEFORE_DEVICE,
				DeviceSupportRisk.derive(LocalDate.of(2028, 6, 1), device(LocalDate.of(2030, 1, 1), null)));
	}

	/**
	 * D5: the device horizon is end-of-support ONLY. Device EOS 2028 with EOL 2035;
	 * component EOS 2029 is past the horizon, so OK. Under the old EOL fallback the
	 * horizon would have been 2035 and this would have flagged.
	 */
	@Test
	void deviceHorizonIsEndOfSupportOnly() {
		assertEquals(DeviceSupportRisk.OK,
				DeviceSupportRisk.derive(LocalDate.of(2029, 1, 1),
						device(LocalDate.of(2028, 1, 1), LocalDate.of(2035, 1, 1))));
	}

	/**
	 * D5, the change that matters most here: a device declaring ONLY end-of-life has NO
	 * horizon. End-of-life means end of SALE, so using it as a support horizon would
	 * measure component support against a sales date and report a risk verdict that
	 * nothing on record supports. Previously this returned EOS_BEFORE_DEVICE.
	 */
	@Test
	void deviceWithOnlyEndOfLifeHasNoHorizon() {
		assertEquals(DeviceSupportRisk.UNKNOWN,
				DeviceSupportRisk.derive(LocalDate.of(2028, 1, 1), device(null, LocalDate.of(2030, 1, 1))));
	}

	/**
	 * D5: a component's own end-of-life no longer produces a verdict. A component that
	 * left the price list before the device's support horizon is not a support risk --
	 * it may still be patched. Previously this returned EOL_BEFORE_DEVICE.
	 */
	@Test
	void componentEndOfLifeAloneYieldsNoVerdict() {
		assertEquals(DeviceSupportRisk.UNKNOWN,
				DeviceSupportRisk.derive(null, device(LocalDate.of(2030, 1, 1), null)));
	}

	@Test
	void componentEosOnHorizonIsOk() {
		// Exclusive boundary: support lasting through the device's horizon day is not a risk.
		LocalDate d = LocalDate.of(2030, 1, 1);
		assertEquals(DeviceSupportRisk.OK, DeviceSupportRisk.derive(d, device(d, null)));
	}

	@Test
	void componentBeyondHorizonIsOk() {
		assertEquals(DeviceSupportRisk.OK,
				DeviceSupportRisk.derive(LocalDate.of(2031, 1, 1), device(LocalDate.of(2030, 1, 1), null)));
	}

	@Test
	void isFlaggedReflectsAtRiskVerdicts() {
		assertTrue(DeviceSupportRisk.EOS_BEFORE_DEVICE.isFlagged());
		assertFalse(DeviceSupportRisk.OK.isFlagged());
		assertFalse(DeviceSupportRisk.UNKNOWN.isFlagged());
	}
}
