/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.exceptions.ActionRefusedException;
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
import io.reliza.service.ComponentLockService;
import io.reliza.service.ComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * SCORE-39 T-16 (ADR-1, ADR-5): the per-release declared licence override. PATCH like the narrative
 * (omitted leaves, "" clears, canonical form, refused before any write), audited as a
 * DECLARED_LICENSE update event, and a change rebuilds the products above the release once the
 * write is committed: the reconcile sees the new value from another thread, as its asynchronous
 * run would. An unchanged value or an unrelated update starts no reconcile.
 */
@SpringBootTest(classes = {App.class})
class ReleaseDeclaredLicenseOverrideTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ComponentLockService componentLockService;
	@Autowired private TestInitializer testInitializer;
	@MockitoSpyBean private ReleaseService releaseService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	/** For each reconcile started: the release it was given and the override another thread read then. */
	private record Reconcile(UUID release, String committedOverride) {}

	private final List<Reconcile> reconciles = new CopyOnWriteArrayList<>();
	private UUID componentUuid;

	@BeforeEach
	void recordTheReconcile() {
		reconciles.clear();
		doAnswer(inv -> {
			ReleaseData rd = inv.getArgument(0);
			String committed = CompletableFuture.supplyAsync(() -> sharedReleaseService.getReleaseData(rd.getUuid())
					.orElseThrow().getLicenseExpression()).get(10, TimeUnit.SECONDS);
			reconciles.add(new Reconcile(rd.getUuid(), committed));
			return null;
		}).when(releaseService).reconcileMergedSbomRoutine(any(), any());
	}

	private UUID createRelease(UUID org, String override) throws RelizaException {
		componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(org)
				.name("it-licence-" + UUID.randomUUID().toString().substring(0, 8))
				.type(ComponentType.COMPONENT)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU).getUuid();
		var branch = branchService.findBranchByName(componentUuid, "main", true, WU).get();
		return ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid)
				.branch(branch.getUuid())
				.org(org)
				.status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0")
				.licenseExpression(override)
				.build(), WU).getUuid();
	}

	private ReleaseData current(UUID releaseUuid) {
		return sharedReleaseService.getReleaseData(releaseUuid).orElseThrow();
	}

	private List<ReleaseUpdateEvent> licenceEvents(UUID releaseUuid) {
		return current(releaseUuid).getUpdateEvents().stream()
				.filter(ue -> ue.rus() == ReleaseUpdateScope.DECLARED_LICENSE).toList();
	}

	private void setOverride(UUID releaseUuid, String value) throws RelizaException {
		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(releaseUuid).licenseExpression(value).build(), WU);
	}

	// ---- create --------------------------------------------------------------------------------

	@Test
	void createStoresTheCanonicalForm() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		assertEquals("MIT OR Apache-2.0", current(createRelease(org.getUuid(), " mit  OR apache-2.0 ")).getLicenseExpression());
	}

	@Test
	void createWithBlankStoresNull() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		assertNull(current(createRelease(org.getUuid(), "  ")).getLicenseExpression());
	}

	@Test
	void createWithAnInvalidOverrideIsRefused() {
		Organization org = testInitializer.obtainOrganization();
		RelizaException e = assertThrows(RelizaException.class, () -> createRelease(org.getUuid(), "Apache License 2.0"));
		assertEquals("licenseExpression invalid: unknown licence id \"Apache\"", e.getMessage());
	}

	// ---- update --------------------------------------------------------------------------------

	@Test
	void omittingTheOverrideLeavesItAndStartsNoReconcile() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = createRelease(org.getUuid(), "MIT");

		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(release).notes("unrelated").build(), WU);

		assertEquals("MIT", current(release).getLicenseExpression());
		assertTrue(licenceEvents(release).isEmpty());
		verify(releaseService, after(500).never()).reconcileMergedSbomRoutine(any(), any());
	}

	@Test
	void settingTheOverrideAuditsItAndReconcilesOnceAfterTheCommit() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = createRelease(org.getUuid(), null);

		setOverride(release, "apache-2.0");

		assertEquals("Apache-2.0", current(release).getLicenseExpression());
		List<ReleaseUpdateEvent> events = licenceEvents(release);
		assertEquals(1, events.size());
		assertEquals(ReleaseUpdateAction.ADDED, events.get(0).rua());
		assertNull(events.get(0).oldValue());
		assertEquals("Apache-2.0", events.get(0).newValue());
		verify(releaseService, after(500).times(1)).reconcileMergedSbomRoutine(any(), any());
		assertEquals(List.of(new Reconcile(release, "Apache-2.0")), reconciles,
				"the reconcile starts once, for this release, and the write is already visible to another thread");
	}

	@Test
	void changingTheOverrideAuditsTheOldAndNewValues() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = createRelease(org.getUuid(), "MIT");

		setOverride(release, "GPL-2.0-only");

		ReleaseUpdateEvent e = licenceEvents(release).get(0);
		assertEquals(ReleaseUpdateAction.CHANGED, e.rua());
		assertEquals("MIT", e.oldValue());
		assertEquals("GPL-2.0-only", e.newValue());
		verify(releaseService, after(500).times(1)).reconcileMergedSbomRoutine(any(), any());
		assertEquals(List.of(new Reconcile(release, "GPL-2.0-only")), reconciles);
	}

	@Test
	void anEmptyStringClearsTheOverrideAndAuditsTheRemoval() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = createRelease(org.getUuid(), "MIT");

		setOverride(release, "");

		assertNull(current(release).getLicenseExpression());
		ReleaseUpdateEvent e = licenceEvents(release).get(0);
		assertEquals(ReleaseUpdateAction.REMOVED, e.rua());
		assertEquals("MIT", e.oldValue());
		assertNull(e.newValue());
		verify(releaseService, after(500).times(1)).reconcileMergedSbomRoutine(any(), any());
		assertEquals(List.of(new Reconcile(release, null)), reconciles);
	}

	@Test
	void theSameValueAgainPaddedOrInAnotherCaseIsNoChange() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = createRelease(org.getUuid(), "MIT OR Apache-2.0");

		setOverride(release, "  mit OR   apache-2.0 ");

		assertEquals("MIT OR Apache-2.0", current(release).getLicenseExpression());
		assertTrue(licenceEvents(release).isEmpty());
		verify(releaseService, after(500).never()).reconcileMergedSbomRoutine(any(), any());
	}

	@Test
	void anInvalidOverrideIsRefusedAndNothingChanges() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = createRelease(org.getUuid(), "MIT");

		RelizaException e = assertThrows(RelizaException.class, () -> setOverride(release, "NOASSERTION"));

		assertTrue(e.getMessage().startsWith("licenseExpression invalid: NOASSERTION and NONE"), e.getMessage());
		assertEquals("MIT", current(release).getLicenseExpression());
		verify(releaseService, after(500).never()).reconcileMergedSbomRoutine(any(), any());
	}

	@Test
	void aLockedComponentRefusesTheOverride() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = createRelease(org.getUuid(), "MIT");
		componentLockService.lockComponent(componentUuid, "freeze", null, null, WU);

		assertThrows(ActionRefusedException.class, () -> setOverride(release, "Apache-2.0"));

		assertEquals("MIT", current(release).getLicenseExpression());
		verify(releaseService, after(500).never()).reconcileMergedSbomRoutine(any(), any());
	}

	// ---- the backstop for every writer ---------------------------------------------------------

	@Test
	void validateReleaseDataRefusesAStoredValueTheValidatorRefuses() {
		ReleaseData rd = new ReleaseData();
		rd.setLicenseExpression("NOASSERTION");
		assertTrue(ReleaseData.validateReleaseData(rd).getErrors().stream()
				.anyMatch(err -> err.startsWith("Release licenseExpression invalid")),
				ReleaseData.validateReleaseData(rd).getErrors().toString());
		rd.setLicenseExpression("MIT");
		assertTrue(ReleaseData.validateReleaseData(rd).getErrors().isEmpty());
	}
}
