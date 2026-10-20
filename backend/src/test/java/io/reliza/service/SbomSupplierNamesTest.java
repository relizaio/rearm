/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.OrganizationData;

/**
 * SCORE-30 (T-19, ADR-2, ADR-12): the supplier ReARM names for a node it writes. The publisher a
 * deliverable records wins; else the organization, for its own software only.
 *
 * <p>SCORE-39 (T-9, ADR-3): the supplier name recorded on the component comes after the publisher
 * and before every other rule, whatever the classifications and the organization.
 */
class SbomSupplierNamesTest {

	private static final String ORG_NAME = "Example Org";
	private static final String PUBLISHER = "Example Devices Inc.";

	private static OrganizationData org(UUID uuid) {
		OrganizationData od = new OrganizationData();
		od.setUuid(uuid);
		od.setName(ORG_NAME);
		return od;
	}

	private static OrganizationData org() {
		return org(UUID.randomUUID());
	}

	private static ComponentData component(BelongsToOrganization isInternal) {
		ComponentData cd = new ComponentData();
		cd.setIsInternal(isInternal);
		return cd;
	}

	private static DeliverableData deliverable(String publisher, BelongsToOrganization isInternal) {
		DeliverableData dd = new DeliverableData();
		dd.setPublisher(publisher);
		dd.setIsInternal(isInternal);
		return dd;
	}

	@Test
	void aPublisherIsTheSupplierWhateverTheClassifications() {
		for (BelongsToOrganization c : BelongsToOrganization.values()) {
			for (BelongsToOrganization d : BelongsToOrganization.values()) {
				assertEquals(PUBLISHER, SbomSupplierNames.forUpload(org(), component(c), deliverable(PUBLISHER, d)), c + "/" + d);
			}
		}
		assertEquals(PUBLISHER, SbomSupplierNames.forUpload(org(CommonVariables.EXTERNAL_PROJ_ORG_UUID),
				component(BelongsToOrganization.EXTERNAL), deliverable(" " + PUBLISHER + " ", BelongsToOrganization.EXTERNAL)));
	}

	@Test
	void withoutAPublisherAndEverythingInternalTheOrganizationIsTheSupplier() {
		assertEquals(ORG_NAME, SbomSupplierNames.forUpload(org(), component(BelongsToOrganization.INTERNAL),
				deliverable(" ", BelongsToOrganization.INTERNAL)));
		assertEquals(ORG_NAME, SbomSupplierNames.forUpload(org(), component(BelongsToOrganization.INTERNAL),
				deliverable(null, BelongsToOrganization.INTERNAL)));
	}

	@Test
	void anExternalComponentHasNoSupplier() {
		assertNull(SbomSupplierNames.forUpload(org(), component(BelongsToOrganization.EXTERNAL),
				deliverable(null, BelongsToOrganization.INTERNAL)));
		assertNull(SbomSupplierNames.forUpload(org(), component(BelongsToOrganization.EXTERNAL), null));
		assertNull(SbomSupplierNames.forRelease(org(), component(BelongsToOrganization.EXTERNAL)));
	}

	@Test
	void anExternalDeliverableOfAnInternalComponentHasNoSupplier() {
		assertNull(SbomSupplierNames.forUpload(org(), component(BelongsToOrganization.INTERNAL),
				deliverable(null, BelongsToOrganization.EXTERNAL)));
		assertNull(SbomSupplierNames.forDeliverable(org(), component(BelongsToOrganization.INTERNAL), "",
				BelongsToOrganization.EXTERNAL));
	}

	@Test
	void theExternalSentinelOrganizationIsNoSupplier() {
		OrganizationData sentinel = org(CommonVariables.EXTERNAL_PROJ_ORG_UUID);

		assertNull(SbomSupplierNames.forUpload(sentinel, component(BelongsToOrganization.INTERNAL), null));
		assertNull(SbomSupplierNames.forRelease(sentinel, component(BelongsToOrganization.INTERNAL)));
	}

	@Test
	void withoutADeliverableAnInternalComponentGetsTheOrganization() {
		assertEquals(ORG_NAME, SbomSupplierNames.forUpload(org(), component(BelongsToOrganization.INTERNAL), null));
		assertEquals(ORG_NAME, SbomSupplierNames.forRelease(org(), component(BelongsToOrganization.INTERNAL)));
	}

	@Test
	void anUnsetClassificationCountsAsInternal() {
		assertEquals(ORG_NAME, SbomSupplierNames.forRelease(org(), component(null)));
		assertEquals(ORG_NAME, SbomSupplierNames.forUpload(org(), component(null), deliverable(null, null)));
	}

	@Test
	void withoutAnOrganizationOrAComponentThereIsNoSupplier() {
		assertNull(SbomSupplierNames.forRelease(null, component(BelongsToOrganization.INTERNAL)));
		assertNull(SbomSupplierNames.forRelease(org(), null));
	}

	private static ComponentData component(BelongsToOrganization isInternal, String supplierName) {
		ComponentData cd = component(isInternal);
		cd.setSupplierName(supplierName);
		return cd;
	}

	@Test
	void theComponentSupplierNamesAReleaseNodeWhateverTheClassificationAndOrganization() {
		for (BelongsToOrganization c : BelongsToOrganization.values()) {
			assertEquals("Acme Corp", SbomSupplierNames.forRelease(org(), component(c, "Acme Corp")), c.name());
			assertEquals("Acme Corp", SbomSupplierNames.forRelease(org(CommonVariables.EXTERNAL_PROJ_ORG_UUID),
					component(c, " Acme Corp ")), c.name());
		}
		assertEquals("Acme Corp", SbomSupplierNames.forRelease(null, component(BelongsToOrganization.EXTERNAL, "Acme Corp")));
	}

	@Test
	void aBlankComponentSupplierFallsBackToTheOrganizationRule() {
		assertEquals(ORG_NAME, SbomSupplierNames.forRelease(org(), component(BelongsToOrganization.INTERNAL, "  ")));
		assertNull(SbomSupplierNames.forRelease(org(), component(BelongsToOrganization.EXTERNAL, "")));
	}

	@Test
	void aDeliverableNamesThePublisherThenTheComponentSupplierThenTheOrganizationRule() {
		ComponentData acme = component(BelongsToOrganization.INTERNAL, "Acme Corp");
		assertEquals(PUBLISHER, SbomSupplierNames.forDeliverable(org(), acme, PUBLISHER, BelongsToOrganization.EXTERNAL));
		assertEquals("Acme Corp", SbomSupplierNames.forDeliverable(org(), acme, " ", BelongsToOrganization.INTERNAL));
		assertEquals("Acme Corp", SbomSupplierNames.forDeliverable(org(), acme, null, BelongsToOrganization.EXTERNAL),
				"an EXTERNAL deliverable of a component that names its supplier names it");
		assertNull(SbomSupplierNames.forDeliverable(org(), component(BelongsToOrganization.INTERNAL, null), null,
				BelongsToOrganization.EXTERNAL));
		assertEquals(ORG_NAME, SbomSupplierNames.forDeliverable(org(), component(BelongsToOrganization.INTERNAL, null), null,
				BelongsToOrganization.INTERNAL));
		assertEquals("Acme Corp", SbomSupplierNames.forUpload(org(), acme, null));
		assertEquals("Acme Corp", SbomSupplierNames.forUpload(org(), acme, deliverable(null, BelongsToOrganization.EXTERNAL)));
	}
}
