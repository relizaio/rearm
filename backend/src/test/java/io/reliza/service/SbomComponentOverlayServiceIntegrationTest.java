/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.VendoredIdentifier;
import io.reliza.model.OverlayKind;
import io.reliza.model.OverlayProvenance;
import io.reliza.model.ReleaseData;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentOverlay;
import io.reliza.model.SbomComponentOverlayAudit;
import io.reliza.model.SbomComponentOverlayPatch;

/** FDAW-15 row 3 (REQ-3, REQ-9): the overlay writes. */
class SbomComponentOverlayServiceIntegrationTest extends SbomComponentOverlayTestBase {

	private static final String SUPPLIER_CONTENT = "supplier holds an e-mail address or a URL credential";

	private final AtomicReference<CompletableFuture<SbomComponentOverlay>> writerAfterCommit = new AtomicReference<>();

	private SbomComponent row() {
		return components(org, null, List.of("pkg:npm/overlay-" + random() + "@1"), false).get(0);
	}

	private String refusal(UUID uuid, SbomComponentOverlayPatch patch) {
		return assertThrows(IllegalArgumentException.class,
				() -> overlayService.setComponentOverlay(uuid, patch, null, random())).getMessage();
	}

	@Test
	void aFirstWriteCreatesAComponentRowAndALaterOnePatchesIt() throws Exception {
		SbomComponent sc = row();
		String key = sc.getCanonicalPurl();
		UUID writer = admin().getUuid();
		overlayService.setComponentOverlay(sc.getUuid(), supplier("Acme"), "first", writer);

		SbomComponentOverlay first = overlayOf(key);
		assertEquals(OverlayKind.COMPONENT, first.getKind());
		assertEquals(OverlayProvenance.MANUAL, first.getProvenance());
		assertEquals(1, first.getRevision());
		assertEquals("Acme", first.getRecordData().supplier());
		assertEquals(writer, first.getRecordData().assertedBy());
		assertNotNull(first.getRecordData().assertedAt());
		List<SbomComponentOverlayAudit> audit = auditOf(key);
		assertEquals(1, audit.size());
		assertEquals("Acme", audit.get(0).getRecordData().supplier());
		assertEquals("first", audit.get(0).getReason());
		assertEquals(writer, audit.get(0).getAssertedBy());
		assertNull(audit.get(0).getBulkAct());
		assertEquals(first.getUuid(), audit.get(0).getOverlay());

		overlayService.setComponentOverlay(sc.getUuid(), licence("MIT"), null, writer);
		SbomComponentOverlay second = overlayOf(key);
		assertEquals(2, second.getRevision());
		assertEquals("Acme", second.getRecordData().supplier());
		assertEquals("MIT", second.getRecordData().licence());
	}

	@Test
	void aLicenceIsStoredCanonicalOrRefused() throws Exception {
		SbomComponent sc = row();
		String key = sc.getCanonicalPurl();
		overlayService.setComponentOverlay(sc.getUuid(), licence("mit AND apache-2.0"), null, random());
		assertEquals("MIT AND Apache-2.0", overlayOf(key).getRecordData().licence());
		overlayService.setComponentOverlay(sc.getUuid(), licence("GPL-2.0 WITH Foo"), null, random());
		assertEquals("GPL-2.0 WITH Foo", overlayOf(key).getRecordData().licence());
		int revision = overlayOf(key).getRevision();
		for (String refused : List.of("NOASSERTION", "MIT OR")) {
			assertTrue(refusal(sc.getUuid(), licence(refused)).startsWith("licence invalid: "), refused);
		}
		assertEquals(revision, overlayOf(key).getRevision());
		assertEquals(revision, auditOf(key).size());
	}

	@Test
	void anEmptyStringClearsNullLeavesAndAnIdenticalPatchWritesNothing() throws Exception {
		SbomComponent sc = row();
		String key = sc.getCanonicalPurl();
		overlayService.setComponentOverlay(sc.getUuid(), new SbomComponentOverlayPatch("Acme", "MIT", null, null), null, random());
		overlayService.setComponentOverlay(sc.getUuid(), supplier(""), null, random());
		assertNull(overlayOf(key).getRecordData().supplier());
		assertEquals("MIT", overlayOf(key).getRecordData().licence());
		assertEquals(2, overlayOf(key).getRevision());
		assertNull(auditOf(key).get(1).getRecordData().supplier());

		overlayService.setComponentOverlay(sc.getUuid(), licence("MIT"), null, random());
		overlayService.setComponentOverlay(sc.getUuid(), supplier(null), null, random());
		assertEquals(2, overlayOf(key).getRevision());
		assertEquals(2, auditOf(key).size());

		SbomComponent untouched = row();
		assertNull(overlayService.setComponentOverlay(untouched.getUuid(), supplier(""), null, random()));
		assertFalse(hasOverlay(untouched.getCanonicalPurl()));
	}

	@Test
	void aRootRowAndADescriptionOnAComponentRowAreRefused() throws Exception {
		SbomComponent root = components(org, null, List.of("pkg:npm/root-" + random() + "@1"), true).get(0);
		assertEquals("a root component's supplier and licence are declared on the component, not overlaid",
				refusal(root.getUuid(), supplier("Acme")));
		SbomComponent sc = row();
		String vendoredOnly = "description and origin are for vendored components; this component is identified by its"
				+ " uploaded SBOM";
		assertEquals(vendoredOnly, refusal(sc.getUuid(), description("d")));
		overlayService.setComponentOverlay(sc.getUuid(), supplier("Acme"), null, random());
		assertEquals(vendoredOnly, refusal(sc.getUuid(), new SbomComponentOverlayPatch(null, null, null, "origin")));
		assertEquals("sbom component not found", refusal(random(), supplier("Acme")));
	}

	/** Every collaborator of this service is null: a bound that fired after a read would be an NPE. */
	@Test
	void theServiceReBoundsBeforeAnyRead() throws Exception {
		SbomComponentOverlayService unwired = new SbomComponentOverlayService();
		ReleaseData rd = null;
		for (var c : List.of(
				List.of(supplier("x".repeat(513)), "supplier is longer than 512 characters"),
				List.of(supplier("a\nb"), "supplier holds a control character"),
				List.of(description("x".repeat(2049)), "description is longer than 2048 characters"),
				List.of(description("a\u0007b"), "description holds a control character"),
				List.of(supplier("Jane Doe <jane@vendor.example>"), SUPPLIER_CONTENT),
				List.of(supplier("jane@vendor.example"), SUPPLIER_CONTENT),
				List.of(supplier("https://deploy:s3cret@vendor.example/"), SUPPLIER_CONTENT),
				List.of(supplier("https://ghp_abc@vendor.example/x"), SUPPLIER_CONTENT))) {
			SbomComponentOverlayPatch patch = (SbomComponentOverlayPatch) c.get(0);
			assertEquals(c.get(1), assertThrows(IllegalArgumentException.class,
					() -> unwired.setComponentOverlay(random(), patch, null, random())).getMessage());
			assertEquals(c.get(1), assertThrows(IllegalArgumentException.class,
					() -> unwired.describeVendored(rd, "x", null, patch, null, random())).getMessage());
		}
		assertEquals("version must not be blank", assertThrows(IllegalArgumentException.class,
				() -> unwired.describeVendored(rd, "x", "", supplier("Acme"), null, random())).getMessage());
	}

	@Test
	void aDescriptionHoldingTabAndLfAndAPlainSupplierAreStoredAsSent() throws Exception {
		SbomComponent sc = row();
		for (String clean : List.of("Acme Corp (https://acme.example)", "Acme GmbH, Berlin")) {
			overlayService.setComponentOverlay(sc.getUuid(), supplier(clean), null, random());
			assertEquals(clean, overlayOf(sc.getCanonicalPurl()).getRecordData().supplier());
		}
		String name = "tabbed-" + random();
		overlayService.describeVendored(releaseOfOrg(), name, "1", description("a\tb\nc"), null, random());
		assertEquals("a\tb\nc", overlayOf(VendoredIdentifier.mint(org.getUuid(), name, "1")).getRecordData().description());
	}

	@Test
	void twoConcurrentFirstWritesToOneKeyBothSucceedOnOneRow() throws Exception {
		SbomComponent sc = row();
		together(() -> overlayService.setComponentOverlay(sc.getUuid(), supplier("Acme"), null, random()),
				() -> overlayService.setComponentOverlay(sc.getUuid(), licence("MIT"), null, random()));
		SbomComponentOverlay row = overlayOf(sc.getCanonicalPurl());
		assertEquals(2, row.getRevision());
		assertEquals("Acme", row.getRecordData().supplier());
		assertEquals("MIT", row.getRecordData().licence());
		assertEquals(2, auditOf(sc.getCanonicalPurl()).size());
		assertEquals(1, count("sbom_component_overlays", "org = ? AND canonical_purl = ?", org.getUuid(), sc.getCanonicalPurl()));
	}

	@Test
	void aWriterBlocksOnTheKeysLockUntilItsHolderCommits() throws Exception {
		SbomComponent sc = row();
		String key = sc.getCanonicalPurl();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			overlayRepository.lockKey(AdvisoryLockKey.SBOM_COMPONENT_KEY_WRITE.getQueryVal(), org.getUuid() + "|" + key);
			assertEquals(52, AdvisoryLockKey.SBOM_COMPONENT_KEY_WRITE.getQueryVal());
			CompletableFuture<SbomComponentOverlay> writer = CompletableFuture.supplyAsync(
					() -> overlayService.setComponentOverlay(sc.getUuid(), supplier("Acme"), null, random()));
			assertThrows(TimeoutException.class, () -> writer.get(700, TimeUnit.MILLISECONDS));
			assertFalse(hasOverlay(key));
			writerAfterCommit.set(writer);
		});
		assertEquals(1, writerAfterCommit.get().join().getRevision());
	}

	@Test
	void describeCreatesAVendoredRowOfNormalisedValuesAndNoComponentRow() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libdesc-" + random();
		overlayService.describeVendored(rd, " " + name + " ", null,
				new SbomComponentOverlayPatch("Acme", "MIT", "dose", "from a vendor"), "why", admin().getUuid());
		String key = VendoredIdentifier.mint(org.getUuid(), name, "");
		SbomComponentOverlay row = overlayOf(key);
		assertEquals(OverlayKind.VENDORED, row.getKind());
		assertEquals(name, row.getRecordData().name());
		assertNull(row.getRecordData().version());
		assertEquals("dose", row.getRecordData().description());
		assertEquals("from a vendor", row.getRecordData().origin());
		assertEquals(0, count("sbom_components", "org = ? AND canonical_purl = ?", org.getUuid(), key));

		assertEquals("a description must say something: supply an origin, a supplier, a licence or a description",
				assertThrows(IllegalArgumentException.class, () -> overlayService.describeVendored(rd, "empty-" + random(), "1",
						new SbomComponentOverlayPatch(null, "", " ", null), null, random())).getMessage());

		assertEquals("a description must say something: supply an origin, a supplier, a licence or a description",
				assertThrows(IllegalArgumentException.class, () -> overlayService.describeVendored(rd, name, null,
						new SbomComponentOverlayPatch("", "", "", ""), null, random())).getMessage());

		overlayService.describeVendored(rd, name, null, supplier("Other"), null, random());
		assertEquals(2, overlayOf(key).getRevision());
		assertEquals("Other", overlayOf(key).getRecordData().supplier());
		assertEquals("MIT", overlayOf(key).getRecordData().licence());
	}

	@Test
	void aRemovalNeedsAReasonIsRecordedAndTheRevisionContinues() throws Exception {
		ReleaseData rd = releaseOfOrg();
		String name = "libremove-" + random();
		String key = VendoredIdentifier.mint(org.getUuid(), name, "1");
		assertEquals("vendored description not found", assertThrows(IllegalArgumentException.class,
				() -> overlayService.removeVendoredDescription(rd, name, "1", "gone", random())).getMessage());
		overlayService.describeVendored(rd, name, "1", description("first"), null, random());
		assertEquals(1, overlayOf(key).getRevision());
		assertEquals("removing a description needs a reason", assertThrows(IllegalArgumentException.class,
				() -> overlayService.removeVendoredDescription(rd, name, "1", " ", random())).getMessage());

		overlayService.removeVendoredDescription(rd, name, "1", "wrong code", random());
		assertFalse(hasOverlay(key));
		SbomComponentOverlayAudit removal = auditOf(key).get(1);
		assertTrue(removal.isRemoved());
		assertEquals(2, removal.getRevision());
		assertEquals("first", removal.getRecordData().description());
		assertEquals("wrong code", removal.getReason());

		overlayService.describeVendored(rd, name, "1", description("second"), null, random());
		assertEquals(3, overlayOf(key).getRevision());
		assertEquals(3, auditOf(key).get(2).getRevision());
		assertFalse(auditOf(key).get(2).isRemoved());
	}
}
