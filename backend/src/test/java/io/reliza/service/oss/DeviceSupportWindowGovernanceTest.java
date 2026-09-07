/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Governance for the section-524B device support window (board t20260830-164643-29472,
 * half A): a real write path for {@code ReleaseData.eos}/{@code eol} on an EXISTING
 * release, with a SUPPORT_WINDOW audit trail and explicit clear semantics.
 *
 * <p>PORTED from rearm-saas, not synced. Both this test and the code it covers live under
 * {@code oss/}, which {@code copy-src.sh} never copies -- so when the window landed upstream,
 * CE received the schema, the {@code ReleaseData} fields and the {@code ReleaseInput}
 * arguments, and none of the write handling. The result was an HTTP 200 that stored nothing:
 * a regulatory field that a caller could set, be told was saved, and find gone on reload.
 *
 * <p>That it went unnoticed is the point worth keeping. Nothing failed -- not the build, not
 * the sync, not a test -- because the only test that would have caught it lives in the same
 * unsynced tree as the code. See {@code roundTripThroughUpdateReleaseActuallyPersists} below,
 * which is the assertion that fails loudly if this ever regresses to a no-op again.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class DeviceSupportWindowGovernanceTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private TestInitializer testInitializer;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private UUID createRelease(UUID org, LocalDate eos, LocalDate eol) throws RelizaException {
		String slug = "it-support-window-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(org)
				.name(slug)
				.type(ComponentType.COMPONENT)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU).getUuid();
		var branch = branchService.findBranchByName(componentUuid, "main", true, WU).get();
		var builder = ReleaseDto.builder()
				.component(componentUuid)
				.branch(branch.getUuid())
				.org(org)
				.status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0");
		if (null != eos) builder.eos(eos);
		if (null != eol) builder.eol(eol);
		return ossReleaseService.createRelease(builder.build(), WU).getUuid();
	}

	private ReleaseData current(UUID releaseUuid) {
		return sharedReleaseService.getReleaseData(releaseUuid).orElseThrow();
	}

	private List<ReleaseUpdateEvent> supportWindowEvents(UUID releaseUuid) {
		return current(releaseUuid).getUpdateEvents().stream()
				.filter(ue -> ue.rus() == ReleaseUpdateScope.SUPPORT_WINDOW)
				.toList();
	}

	@Test
	public void aWindowSetAtCreationIsRecordedInHistory() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), LocalDate.parse("2030-01-01"), null);
		List<ReleaseUpdateEvent> events = supportWindowEvents(releaseUuid);
		Assertions.assertEquals(1, events.size(),
				"a window set at creation must not be invisible in history");
		Assertions.assertEquals("eos=2030-01-01, eol=null", events.get(0).newValue());
	}

	@Test
	public void settingAWindowOnAnExistingReleaseRecordsTheEventWithAttester() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), null, null);

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).eos(LocalDate.parse("2030-01-01")).eol(LocalDate.parse("2033-12-31")).build(), WU);

		ReleaseData after = current(releaseUuid);
		Assertions.assertEquals(LocalDate.parse("2030-01-01"), after.getEos());
		Assertions.assertEquals(LocalDate.parse("2033-12-31"), after.getEol());
		List<ReleaseUpdateEvent> events = supportWindowEvents(releaseUuid);
		Assertions.assertEquals(1, events.size());
		Assertions.assertEquals("eos=null, eol=null", events.get(0).oldValue());
		Assertions.assertEquals("eos=2030-01-01, eol=2033-12-31", events.get(0).newValue());
		Assertions.assertNotNull(events.get(0).wu(), "the attester must be recorded");
	}

	/**
	 * The exact defect the sibling NOTES/TAGS emitters had (PR #464): reading the old
	 * value back AFTER the setter ran yields oldValue == newValue, corrupting the
	 * correction trail. Pinned explicitly per the task notes' own request.
	 */
	@Test
	public void correctingAWindowRecordsTheTruePreviousValueNotANoOpDiff() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), LocalDate.parse("2030-01-01"), LocalDate.parse("2033-12-31"));

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).eos(LocalDate.parse("2031-06-15")).build(), WU);

		List<ReleaseUpdateEvent> events = supportWindowEvents(releaseUuid);
		Assertions.assertEquals(2, events.size(), "creation + one correction");
		ReleaseUpdateEvent correction = events.get(1);
		Assertions.assertNotEquals(correction.oldValue(), correction.newValue(),
				"a real change must never record oldValue == newValue");
		Assertions.assertEquals("eos=2030-01-01, eol=2033-12-31", correction.oldValue());
		Assertions.assertEquals("eos=2031-06-15, eol=2033-12-31", correction.newValue(),
				"eol was not part of this update and must be preserved, not dropped");
	}

	@Test
	public void clearingIsDistinguishableFromNeverSet() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), LocalDate.parse("2030-01-01"), null);

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).clearEos(true).build(), WU);

		ReleaseData after = current(releaseUuid);
		Assertions.assertNull(after.getEos(), "clearEos must actually unset the field");
		List<ReleaseUpdateEvent> events = supportWindowEvents(releaseUuid);
		Assertions.assertEquals(2, events.size(), "creation + one clear");
		Assertions.assertEquals("eos=2030-01-01, eol=null", events.get(1).oldValue());
		Assertions.assertEquals("eos=null, eol=null", events.get(1).newValue(),
				"a clear is distinguishable from never-set only through THIS event existing");
	}

	@Test
	public void clearEosWinsOverAConcurrentlySuppliedEosValue() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), LocalDate.parse("2030-01-01"), null);

		// A stale eos value riding along in the same payload as an explicit clear
		// must not silently win -- mirrors ComponentService's clearOwner precedence.
		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).eos(LocalDate.parse("2099-01-01")).clearEos(true).build(), WU);

		Assertions.assertNull(current(releaseUuid).getEos());
	}

	@Test
	public void eosAfterEolIsRejected() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), null, LocalDate.parse("2030-01-01"));

		Assertions.assertThrows(RelizaException.class, () -> ossReleaseService.updateRelease(
				ReleaseDto.builder().uuid(releaseUuid).eos(LocalDate.parse("2031-01-01")).build(), WU),
				"eos after the existing eol must be rejected even when eol itself is untouched");
	}

	@Test
	public void anUnrelatedUpdateLeavesTheWindowAndItsHistoryAlone() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), null, null);

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).notes("unrelated edit").build(), WU);

		Assertions.assertNull(current(releaseUuid).getEos());
		Assertions.assertTrue(supportWindowEvents(releaseUuid).isEmpty(),
				"a release that never declared a window must stay unaffected by unrelated updates");
	}

	@Test
	public void provenanceIsDerivedFromTheLatestSupportWindowEvent() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), null, null);
		Assertions.assertNull(current(releaseUuid).getSupportWindowSource(),
				"never-touched window must have no provenance");

		UUID apiKeyId = UUID.randomUUID();
		WhoUpdated apiCaller = WhoUpdated.getWhoUpdated(ProgrammaticType.API, apiKeyId, null);
		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).eos(LocalDate.parse("2030-01-01")).build(), apiCaller);

		ReleaseData after = current(releaseUuid);
		Assertions.assertEquals(ProgrammaticType.API, after.getSupportWindowSource(),
				"a programmatic setter must be distinguishable from a human one");
		Assertions.assertEquals(apiKeyId, after.getSupportWindowAssertedBy(),
				"assertedBy is NOT gated by source -- an API key id is still worth tracing, not nulled out");
		Assertions.assertNotNull(after.getSupportWindowLastAssessed());
	}

	/**
	 * The create path has no inline check of its own (unlike updateRelease) -- it
	 * is caught only by ReleaseData.validateReleaseData, called unconditionally by
	 * saveRelease for every writer. That path surfaces IllegalStateException, not
	 * RelizaException; the point of this test is that createRelease is rejected AT
	 * ALL, not the exact exception type.
	 */
	@Test
	public void eosAfterEolIsRejectedAtCreationToo() {
		Organization org = testInitializer.obtainOrganization();
		IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
				() -> createRelease(org.getUuid(), LocalDate.parse("2035-01-01"), LocalDate.parse("2030-01-01")),
				"createRelease must not persist an inverted window -- validateReleaseData is the "
				+ "single enforcement point for every writer, not just the update path");
		Assertions.assertTrue(e.getMessage().contains("eos") && e.getMessage().contains("eol"), e.getMessage());
	}

	@Test
	public void settingAWindowFromNothingIsRecordedAsAdded() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), null, null);

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).eos(LocalDate.parse("2030-01-01")).build(), WU);

		Assertions.assertEquals(ReleaseUpdateAction.ADDED, supportWindowEvents(releaseUuid).get(0).rua());
	}

	@Test
	public void clearingTheWholeWindowIsRecordedAsRemovedNotChanged() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), LocalDate.parse("2030-01-01"), LocalDate.parse("2033-12-31"));

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).clearEos(true).clearEol(true).build(), WU);

		List<ReleaseUpdateEvent> events = supportWindowEvents(releaseUuid);
		Assertions.assertEquals(ReleaseUpdateAction.REMOVED, events.get(events.size() - 1).rua(),
				"a full clear is a removal, not a value change -- an audit view filtered on REMOVED must see it");
	}

	@Test
	public void partiallyClearingTheWindowIsRecordedAsChangedNotRemoved() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), LocalDate.parse("2030-01-01"), LocalDate.parse("2033-12-31"));

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).clearEos(true).build(), WU);

		List<ReleaseUpdateEvent> events = supportWindowEvents(releaseUuid);
		Assertions.assertEquals(ReleaseUpdateAction.CHANGED, events.get(events.size() - 1).rua(),
				"eol is still set, so the window still exists -- this is a change, not a removal");
	}

	/**
	 * THE NO-OP GUARD. Write through the public update path, then read back from storage.
	 *
	 * <p>Deliberately the dumbest test in the file, because the failure it catches was not
	 * subtle and still survived: CE's updateRelease accepted eos, returned success, and
	 * dropped it. Every other test here asserts something more interesting -- precedence,
	 * audit shape, clear-vs-never-set -- and every one of them would have gone on passing
	 * in a fork where the field was simply never written, because they were not there.
	 *
	 * <p>Reads through sharedReleaseService rather than the returned object so a writer that
	 * mutates its in-memory copy without persisting cannot satisfy it.
	 */
	@Test
	public void roundTripThroughUpdateReleaseActuallyPersists() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID releaseUuid = createRelease(org.getUuid(), null, null);

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).eos(LocalDate.parse("2029-01-01")).build(), WU);
		Assertions.assertEquals(LocalDate.parse("2029-01-01"), current(releaseUuid).getEos(),
				"eos did not survive updateRelease -- the write path is ignoring the field");

		ossReleaseService.updateRelease(ReleaseDto.builder()
				.uuid(releaseUuid).eol(LocalDate.parse("2031-01-01")).build(), WU);
		ReleaseData after = current(releaseUuid);
		Assertions.assertEquals(LocalDate.parse("2031-01-01"), after.getEol(),
				"eol did not survive updateRelease");
		Assertions.assertEquals(LocalDate.parse("2029-01-01"), after.getEos(),
				"a later eol-only update must not disturb eos");

		Assertions.assertEquals(2, supportWindowEvents(releaseUuid).size(),
				"each write must leave its own SUPPORT_WINDOW row -- the trail is what a"
						+ " Device Support Statement is defended with");
	}
}
