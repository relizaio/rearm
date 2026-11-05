/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The derived device window warning (FDAW-3, B3): under IMDRF N70 end of life is the last sale
 * date and normally precedes end of support, so only end of support BEFORE end of life warns.
 */
class DeviceWindowWarningTest {

	private static ComponentData.DeviceSupportWindow window(String eos, String eol) {
		ComponentData.DeviceSupportWindow w = new ComponentData.DeviceSupportWindow();
		w.setEos(null == eos ? null : LocalDate.parse(eos));
		w.setEol(null == eol ? null : LocalDate.parse(eol));
		return w;
	}

	@Test
	void noWindowWarnsNothing() {
		assertTrue(DeviceWindowWarning.of(null).isEmpty());
	}

	@Test
	void oneDateAloneWarnsNothing() {
		assertTrue(DeviceWindowWarning.of(window("2030-12-31", null)).isEmpty());
		assertTrue(DeviceWindowWarning.of(window(null, "2033-12-31")).isEmpty());
		assertTrue(DeviceWindowWarning.of(window(null, null)).isEmpty());
	}

	@Test
	void theImdrfOrderWarnsNothing() {
		assertTrue(DeviceWindowWarning.of(window("2034-06-30", "2033-12-31")).isEmpty());
	}

	@Test
	void equalDatesWarnNothing() {
		assertTrue(DeviceWindowWarning.of(window("2033-12-31", "2033-12-31")).isEmpty());
	}

	@Test
	void endOfSupportBeforeEndOfLifeWarnsOnceNamingBothDates() {
		List<DeviceWindowWarning> warnings = DeviceWindowWarning.of(window("2030-12-31", "2033-12-31"));
		assertEquals(1, warnings.size());
		DeviceWindowWarning w = warnings.get(0);
		assertEquals(DeviceWindowWarningCode.EOS_BEFORE_EOL, w.code());
		assertTrue(w.message().contains("2030-12-31"), w.message());
		assertTrue(w.message().contains("2033-12-31"), w.message());
		assertTrue(w.message().contains("last sale date"), w.message());
		assertTrue(w.message().contains("last security update"), w.message());
	}
}
