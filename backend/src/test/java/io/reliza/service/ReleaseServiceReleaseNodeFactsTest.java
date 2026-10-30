/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.VariantData;
import io.reliza.model.Rebom.BomHash;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.model.DigestAlgorithm;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-39 T-10 (ADR-3, ADR-5): the release node facts carry the declared licence DeclaredLicenses
 * resolves at each level and the component supplier; blank licence fields are dropped like a blank
 * supplier; and the facts key is a fixed SHA-256 of the normalised facts that moves with every field
 * and with the hash order, and is the same for no facts, empty facts and facts of blanks.
 */
class ReleaseServiceReleaseNodeFactsTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID RELEASE = UUID.randomUUID();
	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID DELIVERABLE = UUID.randomUUID();
	private static final String SHA_256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	private static final String SHA_1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
	private static final String ACME_URL = "https://acme.example/licence";
	private static final String ORG_URL = "https://org.example/licence";

	private ReleaseService svc;
	private VariantService variantService;
	private GetDeliverableService getDeliverableService;
	private OrganizationData od;
	private ComponentData cd;
	private ReleaseData rd;

	@BeforeEach
	void wire() {
		svc = new ReleaseService(null);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		GetComponentService getComponentService = mock(GetComponentService.class);
		variantService = mock(VariantService.class);
		getDeliverableService = mock(GetDeliverableService.class);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "variantService", variantService);
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		od = new OrganizationData();
		od.setUuid(ORG);
		od.setName("Example Org");
		od.setSettings(new OrganizationData.Settings());
		when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));
		cd = new ComponentData();
		cd.setIsInternal(BelongsToOrganization.INTERNAL);
		when(getComponentService.getComponentData(COMPONENT)).thenReturn(Optional.of(cd));
		rd = mock(ReleaseData.class);
		when(rd.getUuid()).thenReturn(RELEASE);
		when(rd.getOrg()).thenReturn(ORG);
		when(rd.getComponent()).thenReturn(COMPONENT);
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.of(new VariantData()));
	}

	private void orgDefault(String expression, String url) {
		od.getSettings().setDefaultLicenseExpression(expression);
		od.getSettings().setDefaultLicenseUrl(url);
	}

	@Test
	void theOrganizationDefaultReachesTheFacts() {
		orgDefault("AGPL-3.0-only", ORG_URL);

		assertEquals(new ReleaseNodeFacts("Example Org", List.of(), "AGPL-3.0-only", ORG_URL), svc.releaseNodeFacts(rd));
	}

	@Test
	void theComponentLicenceAndSupplierReachTheFacts() {
		orgDefault("AGPL-3.0-only", ORG_URL);
		cd.setLicenseExpression("LicenseRef-Acme-Commercial");
		cd.setLicenseUrl(ACME_URL);
		cd.setSupplierName("Acme Corp");

		assertEquals(new ReleaseNodeFacts("Acme Corp", List.of(), "LicenseRef-Acme-Commercial", ACME_URL),
				svc.releaseNodeFacts(rd));
	}

	@Test
	void theReleaseOverrideReachesTheFactsWithoutAUrl() {
		orgDefault("AGPL-3.0-only", ORG_URL);
		cd.setLicenseExpression("LicenseRef-Acme-Commercial");
		cd.setLicenseUrl(ACME_URL);
		when(rd.getLicenseExpression()).thenReturn("MIT");

		assertEquals(new ReleaseNodeFacts("Example Org", List.of(), "MIT", null), svc.releaseNodeFacts(rd));
	}

	@Test
	void anExternalComponentWithASupplierNamesItAndCarriesItsLicence() {
		cd.setIsInternal(BelongsToOrganization.EXTERNAL);
		cd.setSupplierName("Upstream Vendor");
		cd.setLicenseExpression("Apache-2.0");

		assertEquals(new ReleaseNodeFacts("Upstream Vendor", List.of(), "Apache-2.0", null), svc.releaseNodeFacts(rd));
	}

	@Test
	void blankLicenceFieldsAreDroppedAndFactsOfNothingAreNone() {
		RebomOptions merge = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, BomStructureType.FLAT);
		assertEquals(new ReleaseNodeFacts(null, null, "MIT", null),
				merge.withReleaseNode(new ReleaseNodeFacts(" ", List.of(), "MIT", " ")).releaseNode());
		assertEquals(new ReleaseNodeFacts(null, null, null, ACME_URL),
				merge.withReleaseNode(new ReleaseNodeFacts("", null, "", ACME_URL)).releaseNode());
		assertNull(merge.withReleaseNode(new ReleaseNodeFacts(" ", List.of(), " ", "")).releaseNode());
		assertNull(merge.withReleaseNode(new ReleaseNodeFacts(null, null, null, null)).releaseNode());
	}

	@Test
	void equalFactsGiveEqualKeysOfSixtyFourLowerCaseHexCharacters() {
		ReleaseNodeFacts facts = new ReleaseNodeFacts("Acme Corp", List.of(new BomHash("SHA-256", SHA_256)),
				"LicenseRef-Acme-Commercial", ACME_URL);
		String key = ReleaseNodeFacts.key(facts);
		assertEquals(key, ReleaseNodeFacts.key(new ReleaseNodeFacts("Acme Corp", List.of(new BomHash("SHA-256", SHA_256)),
				"LicenseRef-Acme-Commercial", ACME_URL)));
		assertTrue(key.matches("[0-9a-f]{64}"), key);
		// SHA-256 of {"hashes":[{"alg":"SHA-256","content":"9f86...0a08"}],"licenseExpression":
		// "LicenseRef-Acme-Commercial","licenseUrl":"https://acme.example/licence","supplierName":"Acme Corp"}
		assertEquals("165fa8c37951a173bf363f4dea3c66a6e1087a07278d70a675664aa543505835", key);
	}

	@Test
	void everyFieldAndTheHashOrderMoveTheKey() {
		List<BomHash> two = List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-1", SHA_1));
		ReleaseNodeFacts base = new ReleaseNodeFacts("Acme Corp", two, "MIT", ACME_URL);
		String key = ReleaseNodeFacts.key(base);
		assertNotEquals(key, ReleaseNodeFacts.key(new ReleaseNodeFacts("Acme Corp", two, "Apache-2.0", ACME_URL)), "expression");
		assertNotEquals(key, ReleaseNodeFacts.key(new ReleaseNodeFacts("Acme Corp", two, "MIT", ORG_URL)), "url");
		assertNotEquals(key, ReleaseNodeFacts.key(new ReleaseNodeFacts("Acme Corp", two, "MIT", null)), "url dropped");
		assertNotEquals(key, ReleaseNodeFacts.key(new ReleaseNodeFacts("Example Org", two, "MIT", ACME_URL)), "supplier");
		assertNotEquals(key, ReleaseNodeFacts.key(new ReleaseNodeFacts("Acme Corp",
				List.of(new BomHash("SHA-256", SHA_1), new BomHash("SHA-1", SHA_1)), "MIT", ACME_URL)), "hash content");
		assertNotEquals(key, ReleaseNodeFacts.key(new ReleaseNodeFacts("Acme Corp",
				List.of(new BomHash("SHA-1", SHA_1), new BomHash("SHA-256", SHA_256)), "MIT", ACME_URL)), "hash order");
	}

	@Test
	void noFactsEmptyFactsAndFactsOfBlanksShareTheKeyOfNothing() {
		String nothing = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"; // SHA-256 of {}
		assertEquals(nothing, ReleaseNodeFacts.key(null));
		assertEquals(nothing, ReleaseNodeFacts.key(new ReleaseNodeFacts(null, null, null, null)));
		assertEquals(nothing, ReleaseNodeFacts.key(new ReleaseNodeFacts(" ", List.of(), "", " ")));
	}

	@Test
	void theFactsOfAReleaseCarryItsShippedDigestsBesideTheLicence() {
		DeliverableData dd = new DeliverableData();
		dd.setSoftwareMetadata(SoftwareDeliverableMetadata.builder()
				.digestRecords(new LinkedHashSet<>(List.of(new DigestRecord(DigestAlgorithm.SHA_256, SHA_256)))).build());
		when(getDeliverableService.getDeliverableData(DELIVERABLE)).thenReturn(Optional.of(dd));
		VariantData base = new VariantData();
		base.setOutboundDeliverables(new LinkedHashSet<>(List.of(DELIVERABLE)));
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.of(base));
		cd.setLicenseExpression("MIT");

		assertEquals(new ReleaseNodeFacts("Example Org", List.of(new BomHash("SHA-256", SHA_256)), "MIT", null),
				svc.releaseNodeFacts(rd));
	}
}
