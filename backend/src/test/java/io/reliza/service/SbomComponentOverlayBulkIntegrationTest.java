/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentOverlayAudit;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkOutcome;
import io.reliza.model.SupportBulkResult;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportBulkSelectorForm;
import io.reliza.service.SbomComponentService.SupportBulkRun;

/** FDAW-15 row 7 (REQ-7): the supplier sweep, its skip, its one receipt, and what it never touches. */
class SbomComponentOverlayBulkIntegrationTest extends SbomComponentOverlayTestBase {

	private static final SupportBulkSelector DEBIAN =
			SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "deb", "debian");

	private SupportBulkRun sweep(SupportBulkSelector selector, UUID release, Set<UUID> scope, String supplier)
			throws Exception {
		return overlayService.bulkSetSupplierBySelector(org.getUuid(), selector, release, scope, supplier, "sweep",
				admin().getUuid());
	}

	private static Map<UUID, SupportBulkOutcome> outcomes(SupportBulkRun run) {
		Map<UUID, SupportBulkOutcome> out = new HashMap<>();
		for (SupportBulkResult r : run.results()) out.put(r.sbomComponentUuid(), r.outcome());
		return out;
	}

	private String support(List<SbomComponent> rows) {
		StringBuilder sb = new StringBuilder();
		for (SbomComponent c : rows) {
			sb.append(jdbc.queryForList("SELECT *, support_data::text AS support_text FROM rearm.sbom_component_support WHERE"
					+ " sbom_component_uuid = ?", c.getUuid()));
			sb.append(count("sbom_component_support_audit", "sbom_component_uuid = ?", c.getUuid()));
		}
		return sb.toString();
	}

	@Test
	void aSweepWritesTheRowsWithoutASupplierUnderOneActAndTouchesNoSupportRow() throws Exception {
		List<SbomComponent> rows = components(org, null, List.of("pkg:deb/debian/a@1", "pkg:deb/debian/b@1",
				"pkg:deb/debian/held@1"), false);
		SbomComponent root = components(org, null, List.of("pkg:deb/debian/root@1"), true).get(0);
		UUID writer = admin().getUuid();
		overlayService.setComponentOverlay(rows.get(2).getUuid(), supplier("Acme"), null, writer);
		String heldBefore = jdbc.queryForObject("SELECT record_data::text FROM rearm.sbom_component_overlays WHERE org = ?"
				+ " AND canonical_purl = ?", String.class, org.getUuid(), "pkg:deb/debian/held@1");
		SupportAttestationRequest level = new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null,
				null, null, null, null, null, null, Set.of(), null);
		sbomComponentService.setSbomComponentSupport(rows.get(0).getUuid(), level, writer);
		sbomComponentService.setSbomComponentSupport(rows.get(2).getUuid(), level, writer);
		String supportBefore = support(rows);

		SupportBulkRun run = sweep(DEBIAN, null, null, "Debian");

		assertEquals(3, run.matchedCount());
		Map<UUID, SupportBulkOutcome> o = outcomes(run);
		assertEquals(SupportBulkOutcome.APPLIED, o.get(rows.get(0).getUuid()));
		assertEquals(SupportBulkOutcome.APPLIED, o.get(rows.get(1).getUuid()));
		assertEquals(SupportBulkOutcome.SKIPPED_HAS_SUPPLIER, o.get(rows.get(2).getUuid()));
		assertFalse(o.containsKey(root.getUuid()));
		assertEquals(heldBefore, jdbc.queryForObject("SELECT record_data::text FROM rearm.sbom_component_overlays WHERE org = ?"
				+ " AND canonical_purl = ?", String.class, org.getUuid(), "pkg:deb/debian/held@1"));
		assertEquals(supportBefore, support(rows));

		for (SbomComponent c : rows.subList(0, 2)) {
			var row = overlayOf(c.getCanonicalPurl());
			assertEquals("Debian", row.getRecordData().supplier());
			assertEquals(run.bulkAct(), row.getRecordData().supplierBulkAct());
			List<SbomComponentOverlayAudit> audit = auditOf(c.getCanonicalPurl());
			assertEquals(1, audit.size());
			assertEquals(run.bulkAct(), audit.get(0).getBulkAct());
		}
		Map<String, Object> act = jdbc.queryForMap("SELECT kind, release, matched_count, applied_count, list_hash,"
				+ " selector::text AS sel, record_data::text AS rec FROM rearm.support_bulk_acts WHERE uuid = ?", run.bulkAct());
		assertEquals("SUPPLIER", act.get("kind"));
		assertNull(act.get("release"));
		assertEquals(3, act.get("matched_count"));
		assertEquals(2, act.get("applied_count"));
		assertEquals(SupportBulkActService.listHash(rows.stream().map(c -> c.getUuid().toString()).toList()), act.get("list_hash"));
		assertTrue(((String) act.get("sel")).contains("\"namespace\": \"debian\""), (String) act.get("sel"));
		assertTrue(((String) act.get("rec")).contains("\"supplier\": \"Debian\""), (String) act.get("rec"));
		assertTrue(((String) act.get("rec")).contains("\"reason\": \"sweep\""), (String) act.get("rec"));

		SupportBulkRun second = sweep(DEBIAN, null, null, "Debian");
		assertEquals(0, jdbc.queryForObject("SELECT applied_count FROM rearm.support_bulk_acts WHERE uuid = ?", Integer.class,
				second.bulkAct()));

		overlayService.setComponentOverlay(rows.get(0).getUuid(), supplier("Hand"), null, writer);
		assertEquals("Hand", overlayOf(rows.get(0).getCanonicalPurl()).getRecordData().supplier());
		assertNull(overlayOf(rows.get(0).getCanonicalPurl()).getRecordData().supplierBulkAct());
	}

	@Test
	void theSkipIsDecidedInsideTheRowsTransaction() throws Exception {
		List<SbomComponent> rows = components(org, null, List.of("pkg:deb/debian/raced@1", "pkg:deb/debian/quiet@1"), false);
		UUID writer = admin().getUuid();
		SupportBulkRun run = overlayService.bulkSetSupplierBySelector(org.getUuid(), DEBIAN, null, null, "Debian", null, writer,
				() -> overlayService.setComponentOverlay(rows.get(0).getUuid(), supplier("Hand"), null, writer));
		assertEquals(SupportBulkOutcome.SKIPPED_HAS_SUPPLIER, outcomes(run).get(rows.get(0).getUuid()));
		assertEquals(SupportBulkOutcome.APPLIED, outcomes(run).get(rows.get(1).getUuid()));
		assertEquals("Hand", overlayOf("pkg:deb/debian/raced@1").getRecordData().supplier());
	}

	@Test
	void theSelectorIsMatchedLiterally() throws Exception {
		List<SbomComponent> rows = components(org, null, List.of("pkg:npm/%40acme_/x@1", "pkg:npm/%40acmeX/y@1"), false);
		assertEquals(0, sweep(SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "%"), null, null,
				"Acme").matchedCount());
		SupportBulkRun exact = sweep(SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "@acme_"), null,
				null, "Acme");
		assertEquals(List.of(rows.get(0).getUuid()), exact.results().stream().map(SupportBulkResult::sbomComponentUuid).toList());
	}

	@Test
	void theReleaseScopeIntersectsWithTheReleasesSet() throws Exception {
		UUID release = componentReleaseIn(org, null).release();
		SbomComponent in = components(org, release, List.of("pkg:deb/debian/in@1"), false).get(0);
		components(org, null, List.of("pkg:deb/debian/out@1"), false);
		SupportBulkRun run = sweep(DEBIAN, release, Set.of(in.getUuid()), "Debian");
		assertEquals(List.of(in.getUuid()), run.results().stream().map(SupportBulkResult::sbomComponentUuid).toList());
		assertEquals(release, jdbc.queryForObject("SELECT release FROM rearm.support_bulk_acts WHERE uuid = ?", UUID.class,
				run.bulkAct()));
		assertFalse(hasOverlay("pkg:deb/debian/out@1"));
	}

	@Test
	void aSelectorOverTheCapIsRefusedWithNothingWritten() throws Exception {
		List<String> purls = new ArrayList<>();
		for (int i = 0; i <= SbomComponentService.BULK_SUPPORT_MAX_IDS; i++) purls.add("pkg:deb/debian/many" + i + "@1");
		components(org, null, purls, false);
		String message = assertThrows(IllegalArgumentException.class, () -> sweep(DEBIAN, null, null, "Debian")).getMessage();
		assertEquals("a bulk supplier assignment is limited to 1000 components per call; the selector NAMESPACE deb/debian"
				+ " matches more than 1000 in the organization. Narrow it by namespace or to this release.", message);
		assertFalse(message.contains("Shown components"));
		assertEquals(0, count("sbom_component_overlays", "org = ?", org.getUuid()));
		assertEquals(0, count("sbom_component_overlay_audit", "org = ?", org.getUuid()));
		assertEquals(0, count("support_bulk_acts", "org = ?", org.getUuid()));
	}

	@Test
	void aBlankSupplierIsRefusedBeforeTheCountQuery() throws Exception {
		// an unwired service: reaching the selector query would be an NPE
		assertEquals("supplier is required", assertThrows(IllegalArgumentException.class,
				() -> new SbomComponentOverlayService().bulkSetSupplierBySelector(random(), DEBIAN, null, null, " ", null,
						random())).getMessage());
	}
}
