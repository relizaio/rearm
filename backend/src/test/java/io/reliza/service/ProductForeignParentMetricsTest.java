/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;
import io.reliza.ws.SchedulingConfig;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-6: a product release whose stored {@code parentReleases} names a release that does not resolve
 * in the product's organization (another org's release on a legacy row, or a deleted one) must not
 * throw out of the metrics pass. The child is left out of the rollup with one WARN, the product rolls
 * up from the children that do resolve, and it settles: the poison-pill fence is dropped and the
 * release leaves the BY_UPDATE finder, so the scheduler stops re-picking it every hour.
 *
 * <p>The rows are written straight through {@code releaseRepository.save}, the only way to mint a
 * cross-org parent now that the write side refuses one (TPF-5) -- and exactly what a legacy row looks
 * like. Scheduling is off so no scheduler tick computes the fixtures behind the test's back (it would
 * add a second WARN for the same product).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@TestPropertySource(properties = SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false")
public class ProductForeignParentMetricsTest {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ReleaseMetricsComputeService releaseMetricsComputeService;
	@Autowired private TestInitializer testInitializer;

	/** Minimal in-memory appender, as in SchedulerIsolationTest: only level and message matter. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	private CapturingAppender computeLog;

	@BeforeEach
	public void attachAppender() {
		computeLog = new CapturingAppender();
		computeLog.start();
		computeLogger().addAppender(computeLog);
	}

	@AfterEach
	public void detachAppender() {
		computeLogger().removeAppender(computeLog);
		computeLog.stop();
	}

	private static org.apache.logging.log4j.core.Logger computeLogger() {
		return (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseMetricsComputeService.class);
	}

	@Test
	public void productWithAForeignParent_rollsUpFromResolvingChildrenAndSettles() {
		Organization orgA = testInitializer.obtainOrganization();
		Organization orgB = testInitializer.obtainOrganization();
		Release childA = saveScannedReleaseWithFinding(orgA.getUuid(), "TPF6A");
		Release childB = saveScannedReleaseWithFinding(orgB.getUuid(), "TPF6B");
		Release product = saveScanPendingRelease(orgA.getUuid(),
				parentReleasesOf(childA.getUuid(), childB.getUuid()));
		seedPoisonPillFence(product.getUuid());

		assertDoesNotThrow(() -> releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(product.getUuid()).orElseThrow()),
				"a parent of another organization must be skipped, not thrown on");

		Release after = releaseRepository.findById(product.getUuid()).orElseThrow();
		assertRolledUpFromAOnlyAndSettled(after, product.getUuid());

		List<String> warns = warnsNaming(product.getUuid());
		assertEquals(1, warns.size(), "exactly one WARN names the product: " + warns);
		assertTrue(warns.get(0).contains(childB.getUuid().toString()),
				"the WARN names the skipped foreign child: " + warns.get(0));
		assertFalse(warns.get(0).contains(childA.getUuid().toString()),
				"the WARN does not name the child that resolved: " + warns.get(0));
	}

	@Test
	public void productWhoseOnlyParentIsForeign_settlesAsALeaf() {
		Organization orgA = testInitializer.obtainOrganization();
		Organization orgB = testInitializer.obtainOrganization();
		Release childB = saveScannedReleaseWithFinding(orgB.getUuid(), "TPF6B");
		Release product = saveScanPendingRelease(orgA.getUuid(), parentReleasesOf(childB.getUuid()));
		seedPoisonPillFence(product.getUuid());

		assertDoesNotThrow(() -> releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(product.getUuid()).orElseThrow()));

		Release after = releaseRepository.findById(product.getUuid()).orElseThrow();
		ReleaseMetricsDto m = metricsOf(after);
		assertNotNull(m.getFirstScanned(), "with no resolving child the product is a leaf and the no-BOM anchor sets firstScanned");
		assertTrue(Duration.between(after.getCreatedDate(), m.getFirstScanned()).abs().toMillis() < 1000,
				"firstScanned is the product's createdDate (no-BOM anchor), not the foreign child's scan: "
				+ m.getFirstScanned() + " vs " + after.getCreatedDate());
		assertNotNull(m.getLastScanned(), "the product settles: lastScanned stamped");
		assertFalse(metricsText(after).contains("TPF6B"), "the foreign child's finding never reaches the product");
		assertFenceCleared(after);
		assertFalse(byUpdateFinderUuids().contains(product.getUuid()), "the settled product leaves the BY_UPDATE finder");
		assertEquals(1, warnsNaming(product.getUuid()).size(), "one WARN names the product");
	}

	@Test
	public void productWithANonexistentParent_isSkippedTheSameWay() {
		Organization orgA = testInitializer.obtainOrganization();
		Release childA = saveScannedReleaseWithFinding(orgA.getUuid(), "TPF6A");
		UUID missing = UUID.randomUUID();
		Release product = saveScanPendingRelease(orgA.getUuid(), parentReleasesOf(childA.getUuid(), missing));
		seedPoisonPillFence(product.getUuid());

		assertDoesNotThrow(() -> releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(product.getUuid()).orElseThrow()),
				"a parent that does not exist must be skipped, not thrown on");

		Release after = releaseRepository.findById(product.getUuid()).orElseThrow();
		assertRolledUpFromAOnlyAndSettled(after, product.getUuid());

		List<String> warns = warnsNaming(product.getUuid());
		assertEquals(1, warns.size(), "exactly one WARN names the product: " + warns);
		assertTrue(warns.get(0).contains(missing.toString()), "the WARN names the missing child: " + warns.get(0));
		assertFalse(warns.get(0).contains(childA.getUuid().toString()), warns.get(0));
	}

	@Test
	public void fullyResolvingProduct_isUnchangedAndLogsNoWarning() {
		Organization orgA = testInitializer.obtainOrganization();
		Release childA = saveScannedReleaseWithFinding(orgA.getUuid(), "TPF6A");
		Release childC = saveScannedReleaseWithFinding(orgA.getUuid(), "TPF6C");
		Release product = saveScanPendingRelease(orgA.getUuid(),
				parentReleasesOf(childA.getUuid(), childC.getUuid()));

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(product.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(product.getUuid()).orElseThrow();
		ReleaseMetricsDto m = metricsOf(after);
		assertEquals(2, m.getVulnerabilityDetails().size(), "the rollup carries both children's findings");
		assertTrue(metricsText(after).contains("TPF6A-0") && metricsText(after).contains("TPF6C-0"));
		assertNotNull(m.getFirstScanned());
		assertNotNull(m.getLastScanned(), "and settles");
		assertFalse(byUpdateFinderUuids().contains(product.getUuid()));
		assertTrue(warnsNaming(product.getUuid()).isEmpty(),
				"a product whose children all resolve logs no WARN: " + warnsNaming(product.getUuid()));
	}

	// ---- assertions ----

	private void assertRolledUpFromAOnlyAndSettled(Release after, UUID productUuid) {
		ReleaseMetricsDto m = metricsOf(after);
		String text = metricsText(after);
		assertEquals(1, m.getVulnerabilityDetails().size(), "only the resolving child's finding rolls up: " + text);
		assertTrue(text.contains("TPF6A-0"), "the resolving child's finding is in the product rollup");
		assertFalse(text.contains("TPF6B"), "the foreign child's finding never reaches the product rollup");
		assertNotNull(m.getFirstScanned(), "every resolving child is scanned, so the product is scanned");
		assertNotNull(m.getLastScanned(), "the product settles: lastScanned stamped");
		assertFenceCleared(after);
		assertFalse(byUpdateFinderUuids().contains(productUuid),
				"the settled product leaves the BY_UPDATE finder, so it is re-picked no more");
	}

	private static void assertFenceCleared(Release after) {
		assertTrue(after.getFlowControl() == null
				|| (after.getFlowControl().metricsComputeSkipUntil() == null
						&& after.getFlowControl().metricsComputeFailureCount() == null),
				"the poison-pill fence and its failure count are dropped: " + after.getFlowControl());
	}

	private List<String> warnsNaming(UUID productUuid) {
		synchronized (computeLog.events) {
			return computeLog.events.stream()
					.filter(e -> e.getLevel() == Level.WARN)
					.map(e -> e.getMessage().getFormattedMessage())
					.filter(msg -> msg.contains(productUuid.toString()))
					.collect(Collectors.toList());
		}
	}

	// ---- fixtures (the ReleaseScanSelfHealTest shape) ----

	/** The fence the scheduler's poison-pill path leaves after failed attempts. */
	private void seedPoisonPillFence(UUID releaseUuid) {
		releaseRepository.recordMetricsComputeIncomplete(releaseUuid, 3600);
		releaseRepository.recordMetricsComputeIncomplete(releaseUuid, 3600);
		Release fenced = releaseRepository.findById(releaseUuid).orElseThrow();
		assertNotNull(fenced.getFlowControl().metricsComputeSkipUntil(), "sanity: fence seeded");
		assertEquals(2, fenced.getFlowControl().metricsComputeFailureCount(), "sanity: failure count seeded");
	}

	private Set<UUID> byUpdateFinderUuids() {
		return releaseRepository.findReleasesForMetricsComputeByUpdate(1000)
				.stream().map(Release::getUuid).collect(Collectors.toSet());
	}

	private static ReleaseMetricsDto metricsOf(Release r) {
		return Utils.OM.convertValue(r.getMetrics(), ReleaseMetricsDto.class);
	}

	private static String metricsText(Release r) {
		return String.valueOf(r.getMetrics());
	}

	private static List<Map<String, Object>> parentReleasesOf(UUID... childUuids) {
		List<Map<String, Object>> parents = new ArrayList<>();
		for (UUID childUuid : childUuids) {
			Map<String, Object> entry = new HashMap<>();
			entry.put("release", childUuid.toString());
			entry.put("deliverables", new ArrayList<>());
			parents.add(entry);
		}
		return parents;
	}

	/** An ASSEMBLED release with no firstScanned and empty metrics; parentReleases makes it a product. */
	private Release saveScanPendingRelease(UUID orgUuid, List<Map<String, Object>> parentReleases) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("lifecycle", "ASSEMBLED");
		recordData.put("artifacts", new ArrayList<String>());
		recordData.put("parentReleases", parentReleases);
		r.setRecordData(recordData);
		r.setMetrics(new HashMap<>());
		return releaseRepository.save(r);
	}

	/** A scanned ASSEMBLED release whose metrics carry one finding with id {@code <prefix>-0}. */
	private Release saveScannedReleaseWithFinding(UUID orgUuid, String prefix) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now().minusHours(2));
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("lifecycle", "ASSEMBLED");
		recordData.put("artifacts", new ArrayList<String>());
		r.setRecordData(recordData);
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		vulns.add(new VulnerabilityDto("pkg:maven/org.example/" + prefix.toLowerCase() + "@1.0.0",
				prefix + "-0", VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
				null, null, ZonedDateTime.now().minusHours(3), null, null, null, null, null, false));
		m.setVulnerabilityDetails(vulns);
		ZonedDateTime scanned = ZonedDateTime.now().minusHours(1);
		m.setFirstScanned(scanned);
		m.setLastScanned(scanned);
		r.setMetrics(Utils.OM.convertValue(m, LinkedHashMap.class));
		return releaseRepository.save(r);
	}
}
