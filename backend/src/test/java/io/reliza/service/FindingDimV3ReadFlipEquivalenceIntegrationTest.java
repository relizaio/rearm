/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.FindingDim;
import io.reliza.repositories.FindingChangeEventV3Repository;
import io.reliza.repositories.FindingDimRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * S4-class PROOF against live Postgres (board task #38 follow-on, fact-row dedup): the BRANCH-CHAINED v3
 * backfill and the live v3 emit produce correctly DEDUPED rows on real releases (v3 is now the sole store;
 * v1/v2 dropped in V64), and {@code hydrateInRangeV3} stitches the v3 facts back correctly.
 *
 * <p>Seeds releases on one branch via the real {@code saveReleaseMetrics} path (populates metrics_audit +
 * live-emits v3), runs {@link FindingChangeEventBackfillService#backfillOrgV3}, and asserts the inherited
 * APPEARED drop (the ~148x fan-out collapse) across live-emit, single-window, multi-window, trickle-in and
 * predecessor-rescan shapes.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FindingDimV3ReadFlipEquivalenceIntegrationTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private FindingChangeEventBackfillService backfillService;
	@Autowired private FindingDimBackfillService findingDimBackfillService;
	@Autowired private FindingChangeEventEmitter findingChangeEventEmitter;
	@Autowired private FindingChangeEventV3Repository v3Repository;
	@Autowired private FindingDimRepository findingDimRepository;
	@Autowired private TestInitializer testInitializer;

	/** Resolve a v3 fact row's finding_dim UUID to its vulnId (for per-finding APPEARED assertions). */
	private String dimVulnId(UUID dim) {
		return findingDimRepository.findById(dim).map(FindingDim::getVulnId).orElse(null);
	}

	private static final String PURL_A = "pkg:npm/a@1";
	private static final String PURL_C = "pkg:npm/c@1";

	/**
	 * LIVE-EMIT proof: the emitter is v3-native (V3_ONLY is the only mode), so a release's first scan writes
	 * v3 directly and DROPS the inherited-from-predecessor APPEARED -- no backfill involved.
	 */
	@Test
	public void liveEmitV3DropsInheritedFirstScanAppeared() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		UUID r1 = createRelease(org, component, branch, "2.0.0");
		saveMetrics(r1, metrics());
		saveMetrics(r1, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH))); // first scan, no predecessor
		UUID r2 = createRelease(org, component, branch, "2.1.0");
		saveMetrics(r2, metrics());
		saveMetrics(r2, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH),
				vuln("CVE-C", PURL_C, VulnerabilitySeverity.LOW))); // first scan, inherits CVE-A from R1

		ZonedDateTime from = ZonedDateTime.now().minusDays(1);
		ZonedDateTime to = ZonedDateTime.now().plusMinutes(5);
		Set<String> r1v3 = findingDimBackfillService.hydrateInRangeV3(orgUuid, List.of(r1), from, to)
				.stream().map(FindingDimV3ReadFlipEquivalenceIntegrationTest::key).collect(Collectors.toSet());
		Set<String> r2v3 = findingDimBackfillService.hydrateInRangeV3(orgUuid, List.of(r2), from, to)
				.stream().map(FindingDimV3ReadFlipEquivalenceIntegrationTest::key).collect(Collectors.toSet());

		assertTrue(r1v3.contains("APPEARED#CVE-A|" + PURL_A), "R1 (first on branch) live-emits its own CVE-A APPEARED");
		assertTrue(r2v3.contains("APPEARED#CVE-C|" + PURL_C), "R2 live-emits the new CVE-C APPEARED");
		assertFalse(r2v3.contains("APPEARED#CVE-A|" + PURL_A),
				"R2's live emit DROPS the inherited CVE-A APPEARED (first-scan predecessor dedup)");
	}

	/**
	 * BACKFILL + HYDRATION proof: the branch-chained v3 backfill dedupes R2's inherited CVE-A APPEARED while
	 * keeping the genuinely-new CVE-C APPEARED and the within-R2 severity bump; hydrateInRangeV3 stitches
	 * every stored fact back with releaseUuid = first_release_uuid so the engine groups them as R2's.
	 */
	@Test
	public void v3BackfillDedupesAndHydratesR2() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		// R1 (earlier): empty -> {A}. R2 (later): empty -> {A(HIGH),C} -> {A(CRITICAL),C} (within-R2 bump).
		UUID r1 = createRelease(org, component, branch, "1.0.0");
		saveMetrics(r1, metrics());
		saveMetrics(r1, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH)));

		UUID r2 = createRelease(org, component, branch, "1.1.0");
		saveMetrics(r2, metrics());
		saveMetrics(r2, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH),
				vuln("CVE-C", PURL_C, VulnerabilitySeverity.LOW)));
		saveMetrics(r2, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.CRITICAL),
				vuln("CVE-C", PURL_C, VulnerabilitySeverity.LOW)));

		ZonedDateTime from = ZonedDateTime.now().minusDays(1);
		ZonedDateTime to = ZonedDateTime.now().plusMinutes(5);

		clearV3ForOrg(orgUuid); // re-seed from empty so the backfill dedup is measured in isolation
		backfillService.backfillOrgV3(orgUuid);
		assertFalse(findingDimBackfillService.needsV3Backfill(orgUuid), "org must be v3-certified after a clean backfill");

		List<FindingChangeEvent> hydrated = findingDimBackfillService.hydrateInRangeV3(orgUuid, List.of(r2), from, to);
		long rawV3Count = StreamSupport.stream(v3Repository.findAll().spliterator(), false)
				.filter(f -> orgUuid.equals(f.getOrg()) && r2.equals(f.getFirstReleaseUuid())).count();
		assertEquals(rawV3Count, hydrated.size(), "hydration returns every stored v3 row (no dangling dim)");
		assertTrue(hydrated.stream().allMatch(e -> r2.equals(e.getReleaseUuid())),
				"hydrated events carry releaseUuid = first_release_uuid so the engine groups them as R2's");

		Set<String> v3R2 = hydrated.stream().map(FindingDimV3ReadFlipEquivalenceIntegrationTest::key).collect(Collectors.toSet());
		assertFalse(v3R2.contains("APPEARED#CVE-A|" + PURL_A),
				"v3 DROPS the inherited CVE-A APPEARED on R2 (the dedup)");
		assertTrue(v3R2.contains("APPEARED#CVE-C|" + PURL_C), "v3 keeps the genuinely new CVE-C APPEARED on R2");
		assertTrue(v3R2.contains("SEVERITY_INCREASED#CVE-A|" + PURL_A), "v3 keeps the within-R2 severity bump");
	}

	/**
	 * MULTI-WINDOW proof: with the backfill revision page shrunk to force several windows over one release's
	 * audit history, the windowed v3 backfill keeps cross-window continuity AND fires the inherited-drop
	 * exactly once (in the first window), across the window boundary.
	 */
	@Test
	public void windowedV3Backfill_multiWindow_dropsInheritedOnceAndKeepsAll() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		UUID r1 = createRelease(org, component, branch, "3.0.0");
		saveMetrics(r1, metrics());
		saveMetrics(r1, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH))); // predecessor carries CVE-A

		// R2 accumulates a deep audit history: empty -> {A,B} -> {A,B,C} -> {A,B,C,D}. CVE-A is inherited.
		UUID r2 = createRelease(org, component, branch, "3.1.0");
		saveMetrics(r2, metrics());
		saveMetrics(r2, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH), vuln("CVE-B", "pkg:npm/b@1", VulnerabilitySeverity.LOW)));
		saveMetrics(r2, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH), vuln("CVE-B", "pkg:npm/b@1", VulnerabilitySeverity.LOW), vuln("CVE-C", PURL_C, VulnerabilitySeverity.MEDIUM)));
		saveMetrics(r2, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH), vuln("CVE-B", "pkg:npm/b@1", VulnerabilitySeverity.LOW), vuln("CVE-C", PURL_C, VulnerabilitySeverity.MEDIUM), vuln("CVE-D", "pkg:npm/d@1", VulnerabilitySeverity.CRITICAL)));

		Object priorPage = ReflectionTestUtils.getField(backfillService, "backfillRevisionPage");
		try {
			ReflectionTestUtils.setField(backfillService, "backfillRevisionPage", 2); // force multiple windows
			clearV3ForOrg(orgUuid); // re-seed from empty so the backfill dedup is measured in isolation
			backfillService.backfillOrgV3(orgUuid);

			ZonedDateTime from = ZonedDateTime.now().minusDays(1);
			ZonedDateTime to = ZonedDateTime.now().plusMinutes(5);
			Set<String> r2v3 = findingDimBackfillService.hydrateInRangeV3(orgUuid, List.of(r2), from, to)
					.stream().map(FindingDimV3ReadFlipEquivalenceIntegrationTest::key).collect(Collectors.toSet());

			assertFalse(r2v3.contains("APPEARED#CVE-A|" + PURL_A),
					"inherited CVE-A initial APPEARED dropped ONCE, even though it's in the first of several windows");
			assertTrue(r2v3.contains("APPEARED#CVE-B|pkg:npm/b@1"), "CVE-B kept (window 1)");
			assertTrue(r2v3.contains("APPEARED#CVE-C|" + PURL_C), "CVE-C kept (later window -- cross-window continuity)");
			assertTrue(r2v3.contains("APPEARED#CVE-D|pkg:npm/d@1"), "CVE-D kept (last window)");
		} finally {
			ReflectionTestUtils.setField(backfillService, "backfillRevisionPage", priorPage);
		}
	}

	// ---- helpers ----

	private UUID createRelease(Organization org, Component component, Branch branch, String version)
			throws RelizaException {
		io.reliza.model.dto.ReleaseDto dto = io.reliza.model.dto.ReleaseDto.builder()
				.component(component.getUuid())
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(io.reliza.model.ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(io.reliza.model.ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version(version)
				.build();
		return ossReleaseService.createRelease(dto, WhoUpdated.getTestWhoUpdated()).getUuid();
	}

	private void saveMetrics(UUID releaseUuid, ReleaseMetricsDto metrics) {
		Release r = sharedReleaseService.getRelease(releaseUuid).orElseThrow();
		sharedReleaseService.saveReleaseMetrics(r, metrics);
	}

	/**
	 * Deletes the org's live-emitted v3 rows so a subsequent {@code backfillOrgV3} measures the BACKFILL
	 * producer's dedup in isolation. Since v3 is now the sole store, {@code saveReleaseMetrics} live-emits
	 * v3 (first-scan-only inherited drop); the backfill's fuller trickle-in drop is realized only on a
	 * re-seed of an empty table (insert-only ON CONFLICT can't remove the live rows) -- exactly what these
	 * backfill-dedup fixtures assert, so they re-seed from empty here.
	 */
	private void clearV3ForOrg(UUID orgUuid) {
		v3Repository.deleteAll(StreamSupport.stream(v3Repository.findAll().spliterator(), false)
				.filter(f -> orgUuid.equals(f.getOrg()))
				.collect(Collectors.toList()));
	}

	private static VulnerabilityDto vuln(String id, String purl, VulnerabilitySeverity sev) {
		return new VulnerabilityDto(purl, id, sev, Set.of(), Set.of(), Set.of(),
				null, null, ZonedDateTime.now(), null, null, null, null, null, null);
	}

	private static ReleaseMetricsDto metrics(VulnerabilityDto... vulns) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
		m.setLastScanned(ZonedDateTime.now());
		return m;
	}

	/** Stable change-key: kind + finding identity (vulnId|purl), engine-visible after hydration. */
	private static String key(FindingChangeEvent e) {
		return e.getChangeKind() + "#" + e.getVulnId() + "|" + e.getPurl();
	}

	/**
	 * ROOT-CAUSE reproduction of the demo dedup miss: the branch-chained backfill sources a release's
	 * inheritedKeys from its predecessor's CURRENT (live) metrics. On demo, predecessor releases keep getting
	 * re-scanned for months AFTER a child forked, so their CURRENT findings DRIFT (shrink) away from what the
	 * child actually inherited -- and the inherited-APPEARED drop, matching the child's first-scan against the
	 * predecessor's (now-shrunken) current set, no longer matches, so nothing is dropped. Here R1 carries
	 * {A,B}, R2 forks inheriting {A,B}, then R1 is RE-SCANNED to {} (its vulns resolved later). Correct dedup
	 * must STILL drop R2's inherited A,B (they WERE inherited at fork); if the drop keys off R1's drifted
	 * current metrics it wrongly keeps them -- the demo bug.
	 */
	@Test
	public void v3BranchChain_predecessorRescannedAfterFork_stillDropsInherited() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		UUID r1 = createRelease(org, component, branch, "1.0.0");
		saveMetrics(r1, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH),
				vuln("CVE-B", PURL_C, VulnerabilitySeverity.LOW)));
		UUID r2 = createRelease(org, component, branch, "1.1.0");
		saveMetrics(r2, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH),
				vuln("CVE-B", PURL_C, VulnerabilitySeverity.LOW))); // inherits A,B at fork
		// R1 re-scanned LATER (after R2 forked): its vulns are resolved -> R1 CURRENT metrics drift to {}.
		saveMetrics(r1, metrics());

		clearV3ForOrg(orgUuid); // re-seed from empty so the backfill dedup is measured in isolation
		backfillService.backfillOrgV3(orgUuid);

		// A,B appeared genuinely on R1 (2 APPEAREDs); on R2 they are INHERITED and must be dropped -> total 2.
		long v3Appeared = StreamSupport.stream(v3Repository.findAll().spliterator(), false)
				.filter(f -> orgUuid.equals(f.getOrg()) && f.getChangeKind() == FindingChangeKind.APPEARED)
				.count();
		assertEquals(2L, v3Appeared,
				"A,B appear once on R1 and are inherited (dropped) on R2 -> 2 APPEAREDs total (got " + v3Appeared
						+ "; == 4 means R2's inherited APPEAREDs were NOT dropped -- the demo drift bug)");
	}

	/**
	 * DEMO-SHAPE regression (board task F1): a branch of releases whose findings TRICKLE IN across re-scans --
	 * each release grows {A} -> {A,B} -> {A,B,C}, so B and C first appear at pairs whose OLDER side is NON-empty
	 * (no clean empty->full "initial scan"). The BORN-WITH gate distinguishes:
	 * <ul>
	 *   <li>CVE-A is present in every release's BIRTH scan ({A}); on R1/R2 it is inherited AND born-with, so its
	 *       redundant re-declaration is dropped -> A declared ONCE on the branch (on R0).</li>
	 *   <li>CVE-B / CVE-C TRICKLE IN on every release (absent from the {A} birth scan); they are NOT born-with,
	 *       so each release KEEPS its own dated APPEARED -- reverse-replay needs it to show B/C absent before
	 *       they arrived on THAT release. So B and C are each declared 3x (once per release), NOT once-per-branch.</li>
	 * </ul>
	 * The earlier rule dropped B/C's per-release APPEAREDs too (once-per-branch), which pinned them open back to
	 * each release's birth and over-counted historical posture (F1). Total = A(1) + B(3) + C(3) = 7.
	 */
	@Test
	public void v3BranchChain_bornWithInheritedDedups_trickleInsKeptPerRelease() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		VulnerabilityDto a = vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH);
		VulnerabilityDto b = vuln("CVE-B", PURL_C, VulnerabilitySeverity.MEDIUM);
		VulnerabilityDto c = vuln("CVE-C", "pkg:npm/c@1", VulnerabilitySeverity.LOW);
		for (int i = 0; i < 3; i++) {
			UUID r = createRelease(org, component, branch, "1." + i + ".0");
			// birth scan is {A}; B then C TRICKLE IN via later re-scans (NON-empty older on their first appearance).
			saveMetrics(r, metrics(a));
			saveMetrics(r, metrics(a, b));
			saveMetrics(r, metrics(a, b, c));
		}

		clearV3ForOrg(orgUuid); // re-seed from empty so the backfill dedup is measured in isolation
		backfillService.backfillOrgV3(orgUuid);

		Map<String, Long> appearedByVuln = StreamSupport.stream(v3Repository.findAll().spliterator(), false)
				.filter(f -> orgUuid.equals(f.getOrg()) && f.getChangeKind() == FindingChangeKind.APPEARED)
				.collect(Collectors.groupingBy(f -> dimVulnId(f.getFindingDim()), Collectors.counting()));
		long total = appearedByVuln.values().stream().mapToLong(Long::longValue).sum();

		assertEquals(1L, appearedByVuln.getOrDefault("CVE-A", 0L),
				"born-with inherited CVE-A declared once on the branch (R1/R2 re-declarations dropped)");
		assertEquals(3L, appearedByVuln.getOrDefault("CVE-B", 0L),
				"trickle-in CVE-B keeps its per-release APPEARED (once per release), not once-per-branch (F1)");
		assertEquals(3L, appearedByVuln.getOrDefault("CVE-C", 0L),
				"trickle-in CVE-C keeps its per-release APPEARED (once per release), not once-per-branch (F1)");
		assertEquals(7L, total, "A(1 born-with) + B(3 trickle) + C(3 trickle) = 7 APPEAREDs, got " + total);
	}

	/**
	 * SCALE-SHAPE regression: a branch of SEVERAL releases each carrying the same inherited finding, scanned
	 * DIRECTLY (no explicit empty saveMetrics) and re-scanned enough to span MULTIPLE backfill windows
	 * (backfillRevisionPage forced to 2). Proves the branch-chained inherited-APPEARED drop fires exactly
	 * once per branch across the windowed path -- i.e. the windowing itself does NOT defeat the dedup.
	 */
	@Test
	public void v3BranchChain_directScans_dropsInheritedAppearedOncePerBranch() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = componentService.createComponent(
				"comp_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());

		// 5 releases on ONE branch, each carrying the SAME inherited CVE-A, each RE-SCANNED several times so a
		// release spans MULTIPLE windows (mimicking demo's deep per-release audit history + the windowed path).
		int n = 5;
		for (int i = 0; i < n; i++) {
			UUID r = createRelease(org, component, branch, "1." + i + ".0");
			// several re-scans of ONLY CVE-A (varying severity) so a release spans multiple windows without
			// introducing any other finding -- every APPEARED row is then unambiguously CVE-A's.
			saveMetrics(r, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH)));
			saveMetrics(r, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.MEDIUM)));
			saveMetrics(r, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.HIGH)));
			saveMetrics(r, metrics(vuln("CVE-A", PURL_A, VulnerabilitySeverity.CRITICAL)));
		}

		// Force MULTIPLE windows per release (default page is 25; demo's deep histories page many times).
		Object priorPage = ReflectionTestUtils.getField(backfillService, "backfillRevisionPage");
		try {
			ReflectionTestUtils.setField(backfillService, "backfillRevisionPage", 2);
			clearV3ForOrg(orgUuid); // re-seed from empty so the backfill dedup is measured in isolation
			backfillService.backfillOrgV3(orgUuid);
		} finally {
			ReflectionTestUtils.setField(backfillService, "backfillRevisionPage", priorPage);
		}

		// Only CVE-A exists in this fixture, so every APPEARED row is CVE-A's.
		long v3AppearedA = StreamSupport.stream(v3Repository.findAll().spliterator(), false)
				.filter(f -> orgUuid.equals(f.getOrg())
						&& f.getChangeKind() == FindingChangeKind.APPEARED)
				.count();
		// CVE-A is genuinely NEW only on the FIRST release of the branch; the other n-1 inherit it and must be
		// deduped. Correct v3 => exactly ONE APPEARED for CVE-A across the whole branch.
		assertEquals(1L, v3AppearedA,
				"inherited CVE-A must APPEAR exactly once on the branch (got " + v3AppearedA
						+ " -- if == " + n + ", the inherited-drop is not firing, the demo bug)");
	}
}
