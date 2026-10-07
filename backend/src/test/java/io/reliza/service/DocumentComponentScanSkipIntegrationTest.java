/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DtrackFetchStatus;
import io.reliza.model.FlowControl;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task RD4-11: a BOARD_DOCUMENT component is skipped by vulnerability scanning at every entry point, and a
 * software component still goes through each of them.
 *
 * <p>Entry points covered here: the SBOM-reconcile enqueue on reaching ASSEMBLED (the flow_control
 * marker), the reconcile dequeue, the metrics compute (rescan and triage paths), the product rollup
 * over document children, and the legacy GENERIC component carrying a board document.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class DocumentComponentScanSkipIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final java.util.concurrent.atomic.AtomicInteger VERSION = new java.util.concurrent.atomic.AtomicInteger();

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private ReleaseMetricsComputeService releaseMetricsComputeService;
	@Autowired private ComponentKindPolicy componentKindPolicy;
	@Autowired private JdbcTemplate jdbcTemplate;

	private Component documentComponent(Organization org) throws RelizaException {
		return componentService.createBoardDocumentComponent(CreateComponentDto.builder()
				.name("rd4-11-doc-" + UUID.randomUUID())
				.organization(org.getUuid())
				.type(ComponentType.COMPONENT)
				.kind(ComponentKind.BOARD_DOCUMENT)
				.versionSchema("Micro")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU);
	}

	private Component softwareComponent(Organization org, ComponentType type) throws RelizaException {
		return componentService.createComponent("rd4-11-sw-" + UUID.randomUUID(), org.getUuid(), type,
				"semver", "Branch.Micro", null, WU);
	}

	private UUID release(Organization org, Component c, ReleaseLifecycle lifecycle, DocumentRef doc,
			UUID... children) throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		List<ParentRelease> parents = java.util.Arrays.stream(children)
				.map(p -> ParentRelease.minimalParentReleaseFactory(p, null)).toList();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("1.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(lifecycle).document(doc)
				.parentReleases(parents).build(), WU).getUuid();
	}

	private static DocumentRef doc() {
		return new DocumentRef(RearmSpecificationType.ARCHITECTURE, "design/rd4-11.md", "d-" + UUID.randomUUID(),
				"text/markdown", null, null, null, null, 1, null, null, null);
	}

	private Release row(UUID uuid) {
		return releaseRepository.findById(uuid).orElseThrow();
	}

	private String reconcileMarker(UUID uuid) {
		FlowControl fc = row(uuid).getFlowControl();
		return null == fc ? null : fc.sbomReconcileRequestedAt();
	}

	private static Object metric(Release r, String key) {
		Map<String, Object> m = r.getMetrics();
		return null == m ? null : m.get(key);
	}

	private int auditRows(UUID release) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM rearm.metrics_audit WHERE entity_type = 'RELEASE' AND entity_uuid = ?",
				Integer.class, release);
	}

	@Test
	public void theKindPredicateSkipsDocumentsOnly() {
		assertFalse(ComponentKindPolicy.isScannable(ComponentKind.BOARD_DOCUMENT));
		assertTrue(ComponentKindPolicy.isScannable(ComponentKind.GENERIC));
		assertTrue(ComponentKindPolicy.isScannable(ComponentKind.HELM));
		assertTrue(ComponentKindPolicy.isScannable((ComponentKind) null), "no kind is GENERIC");
		assertEquals(ComponentKind.GENERIC, ComponentKindPolicy.kindOf(null));
		assertEquals(ComponentKind.GENERIC, ComponentKindPolicy.kindOf("NO_SUCH_KIND"));
		assertEquals(ComponentKind.BOARD_DOCUMENT, ComponentKindPolicy.kindOf("BOARD_DOCUMENT"));
	}

	@Test
	public void aDocumentReleaseReachingAssembledQueuesNoReconcile() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID doc = release(org, documentComponent(org), ReleaseLifecycle.DRAFT, null);
		ossReleaseService.updateReleaseLifecycle(doc, ReleaseLifecycle.ASSEMBLED, WU);
		assertNull(reconcileMarker(doc), "a document round is never queued for SBOM reconcile");

		UUID sw = release(org, softwareComponent(org, ComponentType.COMPONENT), ReleaseLifecycle.DRAFT, null);
		ossReleaseService.updateReleaseLifecycle(sw, ReleaseLifecycle.ASSEMBLED, WU);
		assertNotNull(reconcileMarker(sw), "a software release still is");
	}

	@Test
	public void aQueuedDocumentReleaseIsDroppedOnDequeue() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID doc = release(org, documentComponent(org), ReleaseLifecycle.ASSEMBLED, null);
		UUID sw = release(org, softwareComponent(org, ComponentType.COMPONENT), ReleaseLifecycle.ASSEMBLED, null);
		// Queued the way a release from before RD4-11 was: the marker written directly.
		releaseRepository.markSbomReconcileRequested(doc);
		releaseRepository.markSbomReconcileRequested(sw);
		assertNotNull(reconcileMarker(doc));

		assertTrue(sbomComponentService.dropIfNotScannable(doc));
		assertNull(reconcileMarker(doc), "the dequeue drops the document's marker");
		FlowControl fc = row(doc).getFlowControl();
		assertTrue(null == fc || (null == fc.sbomReconcileSkipUntil() && null == fc.sbomReconcileFailureCount()),
				"and leaves no reconcile state behind");

		assertFalse(sbomComponentService.dropIfNotScannable(sw));
		assertNotNull(reconcileMarker(sw), "a software release stays queued for its reconcile");
	}

	@Test
	public void aDocumentReleaseGetsNoMetrics() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID doc = release(org, documentComponent(org), ReleaseLifecycle.ASSEMBLED, null);
		// A fence from an earlier incomplete compute is dropped with the settle.
		releaseRepository.recordMetricsComputeIncomplete(doc, 0);
		int revision = row(doc).getMetricsRevision();

		assertFalse(releaseMetricsComputeService.computeReleaseMetricsOnRescan(row(doc)));
		Release after = row(doc);
		assertNull(metric(after, "firstScanned"), "no scan state is derived for a document");
		Object vulns = metric(after, "vulnerabilityDetails");
		assertTrue(null == vulns || ((List<?>) vulns).isEmpty(), "and no findings");
		assertNotNull(metric(after, "lastScanned"), "only the finder's watermark, so it leaves the queue");
		assertEquals(revision, after.getMetricsRevision(), "no metrics revision");
		assertEquals(0, auditRows(doc), "no metrics audit row");
		FlowControl fc = after.getFlowControl();
		assertTrue(null == fc || (null == fc.metricsComputeSkipUntil() && null == fc.metricsComputeFailureCount()),
				"the metrics fence is dropped");
		assertFalse(releaseRepository.findReleasesForMetricsComputeByUpdate(1000).stream()
				.anyMatch(r -> r.getUuid().equals(doc)), "the document is out of the BY_UPDATE pool");

		// The triage path does nothing for it either.
		assertFalse(releaseMetricsComputeService.computeReleaseMetricsOnNonRescan(row(doc)));
		assertEquals(revision, row(doc).getMetricsRevision());

		UUID sw = release(org, softwareComponent(org, ComponentType.COMPONENT), ReleaseLifecycle.ASSEMBLED, null);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(row(sw));
		assertNotNull(metric(row(sw), "firstScanned"),
				"a software release without a BOM still settles scanned through the no-BOM anchor");
	}

	@Test
	public void aProductOfDocumentsIsNotHeldPendingByThem() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID doc = release(org, documentComponent(org), ReleaseLifecycle.ASSEMBLED, null);
		UUID product = release(org, softwareComponent(org, ComponentType.PRODUCT), ReleaseLifecycle.ASSEMBLED,
				null, doc);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(row(product));
		Release after = row(product);
		assertNotNull(metric(after, "firstScanned"),
				"a document child never scans, so it must not hold its product in Scan pending");
		assertNotNull(metric(after, "lastScanned"));

		// A software child still counts: unscanned, it holds the product; the document beside it does not.
		UUID sw = release(org, softwareComponent(org, ComponentType.COMPONENT), ReleaseLifecycle.ASSEMBLED, null);
		UUID mixed = release(org, softwareComponent(org, ComponentType.PRODUCT), ReleaseLifecycle.ASSEMBLED,
				null, doc, sw);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(row(mixed));
		assertNull(metric(row(mixed), "firstScanned"), "the unscanned software child still holds the product");
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(row(sw));
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(row(mixed));
		assertNotNull(metric(row(mixed), "firstScanned"), "and releases it once scanned");
	}

	@Test
	public void aDocumentOnALegacyGenericComponentIsNotScanned() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		// A board from before the document map publishes onto a GENERIC-kind component (RD4-10).
		UUID legacy = release(org, softwareComponent(org, ComponentType.COMPONENT), ReleaseLifecycle.ASSEMBLED, doc());
		ReleaseData rd = sharedReleaseService.getReleaseData(legacy).orElseThrow();
		assertFalse(componentKindPolicy.isScannableRelease(rd));
		assertFalse(componentKindPolicy.isScannableRelease(legacy));
		releaseRepository.markSbomReconcileRequested(legacy);
		assertTrue(sbomComponentService.dropIfNotScannable(legacy));

		UUID sw = release(org, softwareComponent(org, ComponentType.COMPONENT), ReleaseLifecycle.ASSEMBLED, null);
		assertTrue(componentKindPolicy.isScannableRelease(sw));
		assertTrue(componentKindPolicy.isScannableRelease(sharedReleaseService.getReleaseData(sw).orElseThrow()));
		assertTrue(componentKindPolicy.isScannableRelease(UUID.randomUUID()), "an unknown release is scannable");
	}

	@Test
	public void notApplicableMetricsCarryNoScanState() {
		var m = ComponentKindPolicy.notApplicableMetrics();
		assertEquals(DtrackFetchStatus.NOT_APPLICABLE, m.getDtrackFetchStatus());
		assertNull(m.getFirstScanned());
		assertNull(m.getLastScanned());
		assertEquals(0, m.getCritical());
		assertNull(m.getDtrackSubmissionFailed());
	}
}
