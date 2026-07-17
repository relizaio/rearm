/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.FindingChangeEventV3Repository;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * End-to-end integration test for the board task #38 (phase 1) write-time emit in
 * {@link SharedReleaseService#saveReleaseMetrics}: each re-scan that changes findings persists the
 * corresponding {@link FindingChangeEvent} rows in the same transaction as the metrics_audit write.
 *
 * <p>Asserts: APPEARED + KEV_ADDED rows are written across two re-scans; the first scan (live
 * metrics null) emits nothing; and CANCELLED / REJECTED releases skip emission.
 *
 * <p>Also asserts the decoupling contract (the emit is afterCommit + REQUIRES_NEW, best-effort):
 * a thrown diff-emit failure does NOT roll back the metrics + metrics_audit write and is swallowed;
 * and when the metrics transaction itself rolls back, NO finding-change row is emitted
 * (no orphans — the afterCommit synchronization never fires).
 *
 * <p>v3 is now the sole finding-change store (v1/v2 dropped in V64); the emit writes the branch-grain
 * {@code finding_change_events_v3} directly. Each fixture uses a single first-on-branch release, so no
 * inherited-APPEARED drop applies and the emitted set matches the per-release transitions.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FindingChangeEventEmitIntegrationTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private FindingChangeEventV3Repository findingChangeEventV3Repository;
	@Autowired private FindingDimBackfillService findingDimBackfillService;
	@Autowired private MetricsAuditRepository metricsAuditRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private TestInitializer testInitializer;

	// Spy so individual tests can force the REQUIRES_NEW emit to throw, proving the failure is
	// isolated from (and swallowed by) the metrics save. Unset by default -> real behaviour.
	@MockitoSpyBean private FindingChangeEventEmitter findingChangeEventEmitter;

	private static final String CVE = "CVE-2026-8001";
	private static final String PURL = "pkg:npm/example@1.0.0";

	private static VulnerabilityDto vuln(VulnerabilitySeverity sev, Boolean kev) {
		return new VulnerabilityDto(PURL, CVE, sev, Set.of(), Set.of(), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, kev);
	}

	private static ReleaseMetricsDto metricsWith(VulnerabilityDto... vulns) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
		m.setLastScanned(ZonedDateTime.now());
		return m;
	}

	private void saveMetrics(UUID releaseUuid, ReleaseMetricsDto metrics) {
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		sharedReleaseService.saveReleaseMetrics(r, metrics);
	}

	// v3 facts key the producing release as first_release_uuid. Hydrate them (fact + dimension) into full
	// FindingChangeEvents so the tests can assert the finding identity (vulnId etc.) that lives on the dim.
	private List<FindingChangeEvent> eventsForRelease(UUID releaseUuid) {
		UUID org = StreamSupport.stream(findingChangeEventV3Repository.findAll().spliterator(), false)
				.filter(e -> releaseUuid.equals(e.getFirstReleaseUuid()))
				.map(io.reliza.model.FindingChangeEventV3::getOrg)
				.findFirst().orElse(null);
		if (org == null) {
			return List.of();
		}
		return findingDimBackfillService.hydrateInRangeV3(org, List.of(releaseUuid),
				ZonedDateTime.now().minusYears(1), ZonedDateTime.now().plusYears(1));
	}

	private UUID createRelease(Organization org, Component component, Branch branch,
			ReleaseLifecycle lifecycle, String version) throws RelizaException {
		ReleaseDto dto = ReleaseDto.builder()
				.component(component.getUuid())
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseStatus.ACTIVE)
				.lifecycle(lifecycle)
				.version(version)
				.build();
		return ossReleaseService.createRelease(dto, WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	@Test
	public void rescanDrivenChanges_emitFindingChangeEventRows() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		UUID releaseUuid = createRelease(org, component, branch, ReleaseLifecycle.ASSEMBLED, "1.0.0");

		// rev0: first scan -- live metrics column is null, so NO audit row and NO events.
		saveMetrics(releaseUuid, metricsWith());
		assertTrue(eventsForRelease(releaseUuid).isEmpty(),
				"First metrics save (null live metrics) must emit no finding_change_events");

		// re-scan #1: CVE appears (HIGH, not KEV).
		saveMetrics(releaseUuid, metricsWith(vuln(VulnerabilitySeverity.HIGH, false)));
		List<FindingChangeEvent> afterAppear = eventsForRelease(releaseUuid);
		assertEquals(1, afterAppear.size(), "One APPEARED row expected after the CVE first appears");
		FindingChangeEvent appeared = afterAppear.get(0);
		assertEquals(FindingChangeKind.APPEARED, appeared.getChangeKind());
		assertEquals(CVE, appeared.getVulnId());
		assertEquals(org.getUuid(), appeared.getOrg());
		assertEquals(component.getUuid(), appeared.getComponentUuid());
		assertEquals("HIGH", appeared.getSeverity());
		int appearedRevision = appeared.getToMetricsRevision();

		// re-scan #2: same CVE now KEV-listed.
		saveMetrics(releaseUuid, metricsWith(vuln(VulnerabilitySeverity.HIGH, true)));
		List<FindingChangeEvent> afterKev = eventsForRelease(releaseUuid);
		assertTrue(afterKev.stream().anyMatch(e -> e.getChangeKind() == FindingChangeKind.KEV_ADDED),
				"KEV-listing the CVE must emit a KEV_ADDED row");
		assertEquals(2, afterKev.size(), "APPEARED + KEV_ADDED rows expected after two re-scans");
		assertTrue(appearedRevision >= 0);
	}

	@Test
	public void cancelledRelease_skipsEmission() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		UUID releaseUuid = createRelease(org, component, branch, ReleaseLifecycle.CANCELLED, "9.9.9");

		// Establish a non-null baseline, then a re-scan that WOULD emit APPEARED on a live release.
		saveMetrics(releaseUuid, metricsWith());
		saveMetrics(releaseUuid, metricsWith(vuln(VulnerabilitySeverity.HIGH, false)));

		assertTrue(eventsForRelease(releaseUuid).isEmpty(),
				"CANCELLED release must skip finding_change_events emission (audit row still written)");
	}

	/**
	 * Decoupling contract #1 — a thrown diff-emit failure must NOT roll back the metrics +
	 * metrics_audit write, and must be swallowed (logged, not propagated). The emit is now an
	 * afterCommit + REQUIRES_NEW call, so by the time it fails the metrics/audit are already
	 * committed; {@code saveReleaseMetrics} catches and swallows the failure.
	 */
	@Test
	public void emitFailure_doesNotRollBackMetricsOrAudit_andIsSwallowed() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		UUID releaseUuid = createRelease(org, component, branch, ReleaseLifecycle.ASSEMBLED, "1.0.0");

		// rev0 baseline (null live metrics -> no audit, no emit).
		saveMetrics(releaseUuid, metricsWith());

		// Force the REQUIRES_NEW emit to blow up on the next save (which WOULD emit APPEARED).
		doThrow(new RuntimeException("simulated diff-emit failure"))
				.when(findingChangeEventEmitter).emit(any(), any(), any(), any(), anyInt());

		ReleaseMetricsDto newMetrics = metricsWith(vuln(VulnerabilitySeverity.HIGH, false));
		// Must NOT propagate -- the failure is swallowed (logged at ERROR).
		saveMetrics(releaseUuid, newMetrics);

		// Metrics write persisted: the release's live metrics now reflect the new (failing) save.
		Release reloaded = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		ReleaseData rd = ReleaseData.dataFromRecord(reloaded);
		assertFalse(rd.getMetrics() == null
						|| rd.getMetrics().getVulnerabilityDetails() == null
						|| rd.getMetrics().getVulnerabilityDetails().isEmpty(),
				"Metrics write must persist even though the diff-emit failed");
		assertEquals(CVE, rd.getMetrics().getVulnerabilityDetails().get(0).vulnId());

		// metrics_audit write persisted: an audit revision exists for this release.
		int maxAuditRevision = metricsAuditRepository.findMaxRevision(
				MetricsEntityType.RELEASE.name(), releaseUuid);
		assertTrue(maxAuditRevision >= 0,
				"metrics_audit row must persist even though the diff-emit failed");

		// And no change rows were written (the emit threw before persisting any).
		assertTrue(eventsForRelease(releaseUuid).isEmpty(),
				"A failed diff-emit must not leave finding_change_events rows");
	}

	/**
	 * Decoupling contract #2 — with the afterCommit shape, when the metrics transaction itself ROLLS
	 * BACK, the emit synchronization never fires, so NO {@code finding_change_events} row is emitted
	 * (no orphans). The real emitter runs in REQUIRES_NEW; the only guard against orphan rows when the
	 * outer tx rolls back after an inner commit is the afterCommit gating, which this exercises.
	 */
	@Test
	public void outerTxRollback_emitsNoOrphanFindingChangeEvents() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		UUID releaseUuid = createRelease(org, component, branch, ReleaseLifecycle.ASSEMBLED, "1.0.0");

		// rev0 baseline committed normally so the next save would WANT to emit APPEARED.
		saveMetrics(releaseUuid, metricsWith());

		// Run a save that WOULD emit, inside an OUTER transaction that we force to roll back. The
		// nested saveReleaseMetrics @Transactional joins this outer tx (REQUIRED), so its afterCommit
		// is bound to the outer tx -- which never commits.
		TransactionTemplate tt = new TransactionTemplate(transactionManager);
		ReleaseMetricsDto newMetrics = metricsWith(vuln(VulnerabilitySeverity.HIGH, false));
		tt.execute(status -> {
			Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
			sharedReleaseService.saveReleaseMetrics(r, newMetrics);
			status.setRollbackOnly();
			return null;
		});

		// The metrics tx rolled back -> afterCommit never fired -> no change rows (no orphans).
		assertTrue(eventsForRelease(releaseUuid).isEmpty(),
				"A rolled-back metrics tx must leave NO finding_change_events rows (afterCommit must not fire)");

		// And the rolled-back metrics save did not persist either (live metrics still empty).
		Release reloaded = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		ReleaseData rd = ReleaseData.dataFromRecord(reloaded);
		assertTrue(rd.getMetrics() == null
						|| rd.getMetrics().getVulnerabilityDetails() == null
						|| rd.getMetrics().getVulnerabilityDetails().isEmpty(),
				"Rolled-back metrics save must not persist the new vulnerability");
	}
}
