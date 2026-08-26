package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.FindingChangeEventV3;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.FindingChangeEventV3Repository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * REGRESSION GUARD for the live-emit / repair-sweep inherited-key drift (the fix PR #280 promised and did
 * not land).
 *
 * <p>Both producers decide "which findings did this release INHERIT from its branch predecessor" with the
 * same {@link FindingComparisonService#firstScanInheritedKeys}. The live emit asks at the successor's first
 * scan; the daily {@code repairSweepV3} asks again up to two days later. While that answer was read off the
 * predecessor's CURRENT metrics, a predecessor re-scanned in between changed it -- so the sweep re-added an
 * APPEARED the live emit had correctly dropped, permanently (v3 writes are insert-only) and while logging
 * that the live emitter had dropped a row.
 *
 * <p>The fixture reproduces exactly that sequence:
 * <pre>
 *   pred  {}  -&gt; {CVE}    first scan of the branch; APPEARED recorded against pred
 *   rel   {}  -&gt; {CVE}    first-scanned while pred still carries CVE, so it is INHERITED and dropped
 *   pred  {CVE} -&gt; {}     pred is re-scanned and loses CVE; RESOLVED recorded against pred
 *   sweep                 must still see rel's inherited set as {CVE} -- as it was at rel's birth
 * </pre>
 *
 * <p>Note the leading empty save on each release: {@code SharedReleaseService.saveReleaseMetrics} writes no
 * audit row and schedules NO emit when the pre-image is null, so a release's first-ever save is invisible to
 * the emitter. Without it the second save would see a non-empty {@code oldLive}, {@code firstScan} would be
 * false, and the inherited-drop under test would never run -- the assertion would then hold vacuously. The
 * test asserts that the live emit really did drop the APPEARED before it asserts anything about the sweep,
 * so that trap cannot silently reopen.
 *
 * <p>Counting is scoped to the two releases this test creates: the sweep processes every release re-scanned
 * across the whole database, so an org-wide or global row count would be perturbed by unrelated fixtures.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FindingChangeV3LiveVsSweepDriftTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private FindingChangeEventV3Repository findingChangeEventV3Repository;
	@Autowired private FindingChangeEventBackfillService findingChangeEventBackfillService;
	@Autowired private FindingComparisonService findingComparisonService;
	@Autowired private TestInitializer testInitializer;

	private static final String CVE = "CVE-2026-9100";
	private static final String PURL = "pkg:npm/divergence@1.0.0";
	private static final String INHERITED_KEY = CVE + "|" + PURL;

	private static VulnerabilityDto vuln() {
		return new VulnerabilityDto(PURL, CVE, VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, false);
	}

	private static ReleaseMetricsDto metricsWith(VulnerabilityDto... vulns) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
		m.setLastScanned(ZonedDateTime.now());
		return m;
	}

	private UUID createRelease(Organization org, Component component, Branch branch, String version)
			throws RelizaException {
		ReleaseDto dto = ReleaseDto.builder()
				.component(component.getUuid()).branch(branch.getUuid()).org(org.getUuid())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).version(version)
				.build();
		return ossReleaseService.createRelease(dto, WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	private void saveMetrics(UUID releaseUuid, ReleaseMetricsDto metrics) {
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		sharedReleaseService.saveReleaseMetrics(r, metrics);
	}

	/** Rows attributed to ONE release, so unrelated fixtures swept in the same run cannot skew the count. */
	private List<FindingChangeEventV3> rowsForRelease(UUID releaseUuid) {
		return StreamSupport.stream(findingChangeEventV3Repository.findAll().spliterator(), false)
				.filter(e -> releaseUuid.equals(e.getFirstReleaseUuid()))
				.collect(Collectors.toList());
	}

	@Test
	void sweepMustNotReAddWhatTheLiveEmitDroppedWhenThePredecessorDrifts() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		// The predecessor acquires the finding. Its own APPEARED is recorded (nothing to inherit from).
		UUID pred = createRelease(org, component, branch, "1.0.0");
		saveMetrics(pred, metricsWith());
		saveMetrics(pred, metricsWith(vuln()));

		// The release under test is first-scanned WHILE the predecessor still carries the finding, so the
		// live emit sees inherited = {CVE} and drops the redundant APPEARED.
		UUID rel = createRelease(org, component, branch, "1.0.1");
		saveMetrics(rel, metricsWith());
		saveMetrics(rel, metricsWith(vuln()));
		assertEquals(List.of(), rowsForRelease(rel),
				"precondition: the live emit should have dropped the inherited APPEARED. If rows are "
				+ "present the first-scan path did not run and the rest of this test proves nothing");

		// The predecessor is re-scanned and loses the finding, changing what its CURRENT metrics say it
		// held when the release under test was born.
		saveMetrics(pred, metricsWith());

		var rd = sharedReleaseService.getReleaseData(rel).orElseThrow();
		assertEquals(Set.of(INHERITED_KEY), findingComparisonService.firstScanInheritedKeys(rd),
				"the inherited set must still be the predecessor's state AT THE RELEASE'S BIRTH; reading "
				+ "its current metrics instead yields an empty set and the sweep then re-adds the APPEARED");

		// The nightly sweep runs over the same, otherwise-unchanged data.
		findingChangeEventBackfillService.repairSweepV3(1);

		assertEquals(List.of(), rowsForRelease(rel),
				"the repair sweep re-added an APPEARED the live emit correctly dropped -- permanently, "
				+ "because v3 writes are insert-only");
	}

	/**
	 * The OPPOSITE drift direction, which the anchoring must not over-correct: the predecessor GAINS a
	 * finding after the successor was born. That finding is genuinely new to the branch at the successor's
	 * birth, so its APPEARED must be KEPT -- reading the predecessor's current metrics would call it
	 * inherited and silently swallow a real first appearance.
	 *
	 * <p>This exercises the APPEARED-inversion arm of the reverse-replay (the other test only exercises the
	 * RESOLVED arm), so between them both directions of {@code reconstructLiveMetricsAt} are covered.
	 */
	@Test
	void inheritedSetMustNotAbsorbFindingsThePredecessorGainedAfterTheReleaseWasBorn() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), org.getUuid(), ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		// Predecessor starts clean.
		UUID pred = createRelease(org, component, branch, "2.0.0");
		saveMetrics(pred, metricsWith());

		// The release under test is born and first-scanned while the predecessor is still clean.
		UUID rel = createRelease(org, component, branch, "2.0.1");
		saveMetrics(rel, metricsWith());

		// Only NOW does the predecessor acquire the finding.
		saveMetrics(pred, metricsWith(vuln()));

		var rd = sharedReleaseService.getReleaseData(rel).orElseThrow();
		assertEquals(Set.of(), findingComparisonService.firstScanInheritedKeys(rd),
				"the predecessor held nothing when this release was born, so nothing is inherited; taking "
				+ "its CURRENT metrics would wrongly absorb a finding it only acquired afterwards");
	}
}
