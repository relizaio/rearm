/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.DeviceClass;
import io.reliza.model.ComponentData.DeviceSupportWindow;
import io.reliza.model.ComponentData.MedicalProfile;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;

/**
 * The D7 patch rules for the device support window: what is a change, what is a clear, and
 * what is rejected outright.
 */
class DeviceSupportWindowPatchTest {

	private static final LocalDate EOS = LocalDate.of(2031, 1, 31);
	private static final LocalDate EOL = LocalDate.of(2033, 6, 30);
	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();

	private static DeviceSupportWindow window(LocalDate eos, LocalDate eol) {
		DeviceSupportWindow w = new DeviceSupportWindow();
		w.setEos(eos);
		w.setEol(eol);
		w.setAssertedBy(UUID.randomUUID());
		w.setAssessedAt("2026-09-10T00:00:00Z");
		w.setSource(ProgrammaticType.MANUAL);
		return w;
	}

	private static ComponentData device() {
		ComponentData cd = new ComponentData();
		cd.setDeviceClass(DeviceClass.MEDICAL_TRACKED);
		cd.setMedicalProfile(new MedicalProfile());
		return cd;
	}

	private static ComponentDto patch(DeviceSupportWindow w, Boolean clear) {
		return ComponentDto.builder().deviceSupportWindow(w).clearDeviceSupportWindow(clear).build();
	}

	private static DeviceSupportWindow stored(ComponentData cd) {
		return cd.getMedicalProfile() == null ? null : cd.getMedicalProfile().getDeviceSupportWindow();
	}

	@Test
	void aWindowIsStoredOnADevice() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, EOL), null), WU);
		assertEquals(EOS, stored(cd).getEos());
		assertEquals(EOL, stored(cd).getEol());
	}

	/**
	 * Rejected, not stored-and-ignored. A window recorded against something that is not a
	 * device would be read back by the resolver, reach a generated document, and describe a
	 * device that does not exist.
	 */
	@Test
	void aWindowOnANonDeviceIsRejected() {
		ComponentData notADevice = new ComponentData();
		notADevice.setDeviceClass(DeviceClass.NONE);
		RelizaException e = assertThrows(RelizaException.class,
				() -> ComponentService.applyDeviceSupportWindowPatch(notADevice, patch(window(EOS, null), null), WU));
		assertTrue(e.getMessage().contains("device class"), "the message must say what to fix: " + e.getMessage());
	}

	@Test
	void aWindowOnAComponentWithNoDeviceClassAtAllIsRejected() {
		ComponentData cd = new ComponentData();
		cd.setDeviceClass(null);
		assertThrows(RelizaException.class,
				() -> ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, null), null), WU));
	}

	@Test
	void endOfSupportAfterEndOfLifeIsRejected() {
		ComponentData cd = device();
		RelizaException e = assertThrows(RelizaException.class,
				() -> ComponentService.applyDeviceSupportWindowPatch(cd,
						patch(window(LocalDate.of(2034, 1, 1), EOL), null), WU));
		assertTrue(e.getMessage().contains("2034-01-01") && e.getMessage().contains("2033-06-30"),
				"the message must name both dates: " + e.getMessage());
	}

	/** Either date alone is fine -- the ordering rule only applies when both are present. */
	@Test
	void oneDateAloneIsAccepted() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, null), null), WU);
		assertEquals(EOS, stored(cd).getEos());
		assertNull(stored(cd).getEol());
	}

	/** PATCH: omitting the field leaves what is stored alone. */
	@Test
	void omittingTheWindowChangesNothing() throws Exception {
		ComponentData cd = device();
		cd.getMedicalProfile().setDeviceSupportWindow(window(EOS, EOL));
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(null, null), WU);
		assertEquals(EOS, stored(cd).getEos());
	}

	@Test
	void clearRetractsTheWindow() throws Exception {
		ComponentData cd = device();
		cd.getMedicalProfile().setDeviceSupportWindow(window(EOS, EOL));
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(null, Boolean.TRUE), WU);
		assertNull(stored(cd), "clear must retract the declaration");
	}

	/**
	 * AN EMPTY OBJECT IS NOT A CLEAR. A form bound to two date pickers posts {} the moment
	 * both are blank; if that retracted the commitment, opening the page and saving any other
	 * field would silently withdraw a regulatory claim.
	 */
	@Test
	void anEmptyWindowObjectIsNotAClear() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(null, null), null), WU);
		assertNotNull(stored(cd), "an empty object must be stored as itself, never treated as a clear");
		assertNull(stored(cd).getEos());
	}

	/**
	 * A PATCH MERGES OVER WHAT IS STORED. eos and eol are independently optional on the input,
	 * so a caller sending one of them used to store {that date, null} -- withdrawing a declared
	 * end of sale by omission, which is the thing clearDeviceSupportWindow exists to make
	 * explicit. Retracting a regulatory commitment has to be asked for.
	 */
	@Test
	void apartialPatchKeepsTheDateItDidNotMention() throws Exception {
		ComponentData cd = device();
		cd.getMedicalProfile().setDeviceSupportWindow(window(EOS, EOL));
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(LocalDate.parse("2032-03-31"), null), null), WU);
		assertEquals(LocalDate.parse("2032-03-31"), stored(cd).getEos(), "the supplied date wins");
		assertEquals(EOL, stored(cd).getEol(), "the omitted date must survive, not be retracted");
	}

	/**
	 * The same rule seen from the form's side: two blank date pickers post {}, and over a
	 * declared window that must be a no-op. Storing {null, null} would read back through the
	 * resolver as "not declared", i.e. a retraction nobody asked for.
	 */
	@Test
	void anEmptyWindowObjectOverAStoredOneRetractsNothing() throws Exception {
		ComponentData cd = device();
		cd.getMedicalProfile().setDeviceSupportWindow(window(EOS, EOL));
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(null, null), null), WU);
		assertEquals(EOS, stored(cd).getEos());
		assertEquals(EOL, stored(cd).getEol());
	}

	/** Ordering is checked AFTER the merge, or a partial patch could store an inconsistent pair. */
	@Test
	void aPartialPatchThatCrossesTheStoredOtherDateIsRejected() throws Exception {
		ComponentData cd = device();
		cd.getMedicalProfile().setDeviceSupportWindow(window(EOS, EOL));
		RelizaException e = assertThrows(RelizaException.class,
				() -> ComponentService.applyDeviceSupportWindowPatch(cd,
						patch(window(EOL.plusYears(1), null), null), WU));
		assertTrue(e.getMessage().contains("cannot be after"), e.getMessage());
		assertEquals(EOS, stored(cd).getEos(), "a rejected patch must leave the record alone");
	}

	/** The commitment's history is on the record, not just its current value. */
	@Test
	void aChangeIsRecordedAsAnUpdateEvent() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, null), null), WU);
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, EOL), null), WU);

		var events = cd.getUpdateEvents().stream()
				.filter(e -> e.cus() == ComponentData.ComponentUpdateScope.DEVICE_SUPPORT_WINDOW)
				.toList();
		assertEquals(2, events.size());
		assertEquals(ComponentData.ComponentUpdateAction.ADDED, events.get(0).cua());
		assertEquals(ComponentData.ComponentUpdateAction.CHANGED, events.get(1).cua());
		assertEquals("eos=2031-01-31, eol=null", events.get(1).oldValue());
		assertEquals("eos=2031-01-31, eol=2033-06-30", events.get(1).newValue());
	}

	/** Re-saving the same dates is not a change and must not litter the record. */
	@Test
	void anIdenticalRewriteRecordsNothing() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, EOL), null), WU);
		int after = cd.getUpdateEvents().size();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, EOL), null), WU);
		assertEquals(after, cd.getUpdateEvents().size());
	}

	/**
	 * Setting deviceClass to NONE retracts the window with it.
	 *
	 * <p>Left behind, the window stays RESOLVABLE -- the resolver reads the profile, not the
	 * class -- so a component that is no longer a device would keep stamping a device
	 * commitment onto its releases' exports. Covered here because the rule lives in
	 * updateComponent, one line above the patch this class tests.
	 */
	@Test
	void theWindowCannotSurviveTheComponentCeasingToBeADevice() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, EOL), null), WU);
		assertNotNull(stored(cd));

		// What updateComponent does when deviceClass arrives as NONE.
		ComponentService.retractWindowOnDeviceClassNone(cd, DeviceClass.NONE, WU);

		assertNull(stored(cd), "an orphaned window stayed resolvable after the component stopped being a device");
		assertTrue(cd.getUpdateEvents().stream().anyMatch(e ->
				e.cus() == ComponentData.ComponentUpdateScope.DEVICE_SUPPORT_WINDOW
						&& e.cua() == ComponentData.ComponentUpdateAction.REMOVED),
				"withdrawing a regulatory claim is itself a fact and must be recorded");
	}

	@Test
	void aNonNoneDeviceClassLeavesTheWindowAlone() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(window(EOS, EOL), null), WU);
		ComponentService.retractWindowOnDeviceClassNone(cd, DeviceClass.MEDICAL_UNTRACKED, WU);
		assertNotNull(stored(cd));
	}

	@Test
	void clearingWhenNothingIsDeclaredRecordsNothing() throws Exception {
		ComponentData cd = device();
		ComponentService.applyDeviceSupportWindowPatch(cd, patch(null, Boolean.TRUE), WU);
		assertTrue(cd.getUpdateEvents().isEmpty());
	}
}
