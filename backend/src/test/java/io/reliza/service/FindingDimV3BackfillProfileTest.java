/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.FindingChangeEventV3Repository;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * PROFILER (not a CI assertion) for the WINDOWED v3 backfill on a DEEP per-release audit history -- the
 * customer-upgrade shape (a release re-scanned for months, thousands of findings per snapshot). Guarded by
 * {@code -Dv3profile=true} so normal CI skips the heavy fixture. Seeds one release with {@code REVISIONS}
 * metrics_audit rows of {@code FINDINGS} vulnerabilities each (directly, not via saveReleaseMetrics), then
 * runs {@code backfillOrgV3} at several window sizes and logs wall-clock + peak heap delta so we can pick a
 * default {@code findingChangeBackfillRevisionPage} that seeds fast without OOM.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FindingDimV3BackfillProfileTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private FindingChangeEventBackfillService backfillService;
	@Autowired private FindingChangeEventV3Repository v3Repository;
	@Autowired private MetricsAuditRepository metricsAuditRepository;
	@Autowired private TestInitializer testInitializer;

	private static final int REVISIONS = Integer.getInteger("v3profile.revisions", 120);
	private static final int FINDINGS = Integer.getInteger("v3profile.findings", 600);
	private static final int[] PAGE_SIZES = {20, REVISIONS + 1}; // bounded window vs whole-history-in-one

	@Test
	public void profileWindowedV3Backfill() throws RelizaException {
		Assumptions.assumeTrue(Boolean.getBoolean("v3profile"),
				"set -Dv3profile=true to run the deep-history v3 backfill profiler");

		Organization org = testInitializer.obtainOrganization();
		UUID orgUuid = org.getUuid();
		Component component = componentService.createComponent(
				"prof_" + UUID.randomUUID(), orgUuid, ComponentType.COMPONENT,
				"semver", "Branch.Micro", null, WhoUpdated.getTestWhoUpdated());
		Branch branch = branchService.createBranch(
				"main", component.getUuid(), BranchType.BASE, WhoUpdated.getTestWhoUpdated());
		UUID release = ossReleaseService.createRelease(io.reliza.model.dto.ReleaseDto.builder()
				.component(component.getUuid()).branch(branch.getUuid()).org(orgUuid)
				.status(io.reliza.model.ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(io.reliza.model.ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0").build(), WhoUpdated.getTestWhoUpdated()).getUuid();

		// Seed REVISIONS audit rows: revision 0 empty, then a stable FINDINGS-sized snapshot whose severities
		// rotate a slice each revision -> big metrics + real per-pair diff work.
		ZonedDateTime base = ZonedDateTime.now().minusDays(400);
		List<MetricsAudit> batch = new java.util.ArrayList<>();
		batch.add(auditRow(orgUuid, release, 0, base, new ReleaseMetricsDto()));
		for (int rev = 1; rev < REVISIONS; rev++) {
			batch.add(auditRow(orgUuid, release, rev, base.plusHours(rev), snapshot(FINDINGS, rev)));
			if (batch.size() >= 20) { metricsAuditRepository.saveAll(batch); batch.clear(); }
		}
		if (!batch.isEmpty()) { metricsAuditRepository.saveAll(batch); }
		long auditBytes = estimateBytes(snapshot(FINDINGS, 1)) * (long) REVISIONS;
		System.out.printf("V3PROFILE fixture: release=%s revisions=%d findings/rev=%d approx_audit_json=%dMB%n",
				release, REVISIONS, FINDINGS, auditBytes / (1024 * 1024));

		Object priorPage = ReflectionTestUtils.getField(backfillService, "backfillRevisionPage");
		try {
			for (int page : PAGE_SIZES) {
				clearV3(orgUuid);
				ReflectionTestUtils.setField(backfillService, "backfillRevisionPage", page);
				System.gc();
				sleep(150);
				long heapBefore = usedHeap();
				PeakSampler sampler = new PeakSampler();
				sampler.start();
				long t0 = System.nanoTime();
				var result = backfillService.backfillOrgV3(orgUuid);
				long ms = (System.nanoTime() - t0) / 1_000_000;
				sampler.stop();
				long v3rows = StreamSupport.stream(v3Repository.findAll().spliterator(), false)
						.filter(f -> orgUuid.equals(f.getOrg()) && release.equals(f.getFirstReleaseUuid())).count();
				System.out.printf(
						"V3PROFILE page=%-5d time=%5dms v3rows=%d peakHeapDelta=%dMB (heapBefore=%dMB peak=%dMB)%n",
						page, ms, v3rows, Math.max(0, sampler.peak - heapBefore) / (1024 * 1024),
						heapBefore / (1024 * 1024), sampler.peak / (1024 * 1024));
			}
		} finally {
			ReflectionTestUtils.setField(backfillService, "backfillRevisionPage", priorPage);
		}
	}

	// ---- fixture + measurement helpers ----

	private static MetricsAudit auditRow(UUID org, UUID release, int rev, ZonedDateTime when, ReleaseMetricsDto m) {
		MetricsAudit a = new MetricsAudit();
		a.setEntityType(MetricsEntityType.RELEASE);
		a.setEntityUuid(release);
		a.setOrg(org);
		a.setMetricsRevision(rev);
		a.setRevisionCreatedDate(when);
		a.setEntityCreatedDate(when);
		@SuppressWarnings("unchecked")
		Map<String, Object> raw = Utils.OM.convertValue(m, Map.class);
		a.setMetrics(raw);
		return a;
	}

	private static ReleaseMetricsDto snapshot(int findings, int rev) {
		List<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < findings; i++) {
			// rotate ~2% of severities per revision so consecutive snapshots differ (real diff work).
			VulnerabilitySeverity sev = ((i + rev) % 50 == 0)
					? VulnerabilitySeverity.CRITICAL : VulnerabilitySeverity.HIGH;
			vulns.add(new VulnerabilityDto("pkg:npm/lib" + i + "@1", "CVE-2026-" + i, sev,
					Set.of(), Set.of(), Set.of(), null, null, ZonedDateTime.now(), null, null, null, null, null, null));
		}
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(vulns);
		return m;
	}

	private void clearV3(UUID org) {
		v3Repository.deleteAll(StreamSupport.stream(v3Repository.findAll().spliterator(), false)
				.filter(f -> org.equals(f.getOrg())).collect(Collectors.toList()));
	}

	private static long estimateBytes(ReleaseMetricsDto m) {
		try {
			return Utils.OM.writeValueAsString(m).length();
		} catch (Exception e) {
			return 0;
		}
	}

	private static long usedHeap() {
		Runtime r = Runtime.getRuntime();
		return r.totalMemory() - r.freeMemory();
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Background poller tracking peak used-heap during a run (indicative, JVM-noisy but comparable across runs). */
	private static final class PeakSampler {
		volatile long peak = 0;
		private volatile boolean running = true;
		private Thread t;

		void start() {
			t = new Thread(() -> {
				while (running) {
					peak = Math.max(peak, usedHeap());
					sleep(15);
				}
			});
			t.setDaemon(true);
			t.start();
		}

		void stop() {
			running = false;
			try {
				t.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
