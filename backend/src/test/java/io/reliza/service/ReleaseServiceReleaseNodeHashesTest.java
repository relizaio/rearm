/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.OrganizationData;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.VariantData;
import io.reliza.model.Rebom.BomHash;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.model.DigestAlgorithm;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-30 (T-11, ADR-2, ADR-3, ADR-11): what ReleaseService knows about the node that describes a
 * release in a merged BOM. The hashes are the digest records of the one deliverable it ships (an
 * outbound deliverable of its base variant), never of one it consumes, and none when the two purl
 * types disagree; the supplier is its organization when the component is the organization's own.
 */
class ReleaseServiceReleaseNodeHashesTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID RELEASE = UUID.randomUUID();
	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID CONSUMED = UUID.randomUUID();
	private static final UUID FIRST = UUID.randomUUID();
	private static final UUID SECOND = UUID.randomUUID();
	private static final UUID MISSING = UUID.randomUUID();
	private static final String SHA_256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	private static final String OTHER_256 = "60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";
	private static final String SHA_512 = "ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db2"
			+ "7ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff";
	private static final String SHA_1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";

	private ReleaseService svc;
	private GetDeliverableService getDeliverableService;
	private GetOrganizationService getOrganizationService;
	private GetComponentService getComponentService;
	private VariantService variantService;
	private ComponentData cd;
	private ReleaseData rd;

	@BeforeEach
	void wire() {
		svc = new ReleaseService(null);
		getDeliverableService = mock(GetDeliverableService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		getComponentService = mock(GetComponentService.class);
		variantService = mock(VariantService.class);
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "variantService", variantService);
		OrganizationData od = new OrganizationData();
		od.setUuid(ORG);
		od.setName("Example Org");
		when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));
		cd = new ComponentData();
		cd.setIsInternal(BelongsToOrganization.INTERNAL);
		when(getComponentService.getComponentData(COMPONENT)).thenReturn(Optional.of(cd));
		rd = mock(ReleaseData.class);
		when(rd.getUuid()).thenReturn(RELEASE);
		when(rd.getOrg()).thenReturn(ORG);
		when(rd.getComponent()).thenReturn(COMPONENT);
		// a release consumes a deliverable that is never its own (run 1 item T-1)
		deliverable(CONSUMED, record(DigestAlgorithm.SHA_256, OTHER_256));
		when(rd.getInboundDeliverables()).thenReturn(new ArrayList<>(List.of(CONSUMED)));
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.of(new VariantData()));
	}

	private DeliverableData deliverable(UUID uuid, DigestRecord... records) {
		DeliverableData dd = new DeliverableData();
		dd.setSoftwareMetadata(SoftwareDeliverableMetadata.builder()
				.digestRecords(new LinkedHashSet<>(Arrays.asList(records))).build());
		when(getDeliverableService.getDeliverableData(uuid)).thenReturn(Optional.of(dd));
		return dd;
	}

	/** The release ships {@code deliverables}: the outbound deliverables of its base variant. */
	private void outbound(UUID... deliverables) {
		VariantData base = new VariantData();
		base.setOutboundDeliverables(new LinkedHashSet<>(List.of(deliverables)));
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.of(base));
	}

	private static List<RearmIdentifier> purl(String value) {
		return new ArrayList<>(List.of(new RearmIdentifier(RearmIdentifierType.PURL, value)));
	}

	private static DigestRecord record(DigestAlgorithm algo, String digest) {
		return new DigestRecord(algo, digest);
	}

	@Test
	void oneDeliverableGivesItsRecordsInOrderUnderTheirCycloneDxNames() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512));
		outbound(FIRST);

		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), svc.releaseNodeHashes(rd));
	}

	@Test
	void theOrderIsTheRecordOrder() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_512, SHA_512), record(DigestAlgorithm.SHA_1, SHA_1),
				record(DigestAlgorithm.SHA_256, SHA_256));
		outbound(FIRST);

		assertEquals(List.of(new BomHash("SHA-512", SHA_512), new BomHash("SHA-1", SHA_1), new BomHash("SHA-256", SHA_256)),
				svc.releaseNodeHashes(rd));
	}

	@Test
	void recordsOfAnotherScopeDoNotDescribeTheDeliverable() {
		deliverable(FIRST, new DigestRecord(DigestAlgorithm.SHA_256, SHA_256, DigestScope.REARM));
		outbound(FIRST);

		assertEquals(List.of(), svc.releaseNodeHashes(rd));
	}

	@Test
	void onlyOriginalFileRecordsAndRecordsWithoutAScopeCount() {
		deliverable(FIRST, new DigestRecord(DigestAlgorithm.SHA_256, OTHER_256, DigestScope.OCI_STORAGE),
				new DigestRecord(DigestAlgorithm.SHA_256, SHA_256, DigestScope.ORIGINAL_FILE),
				new DigestRecord(DigestAlgorithm.SHA_512, SHA_512, null),
				new DigestRecord(DigestAlgorithm.SHA_1, SHA_1, DigestScope.AS_UPLOADED));
		outbound(FIRST);

		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), svc.releaseNodeHashes(rd));
	}

	@Test
	void twoDeliverablesWithDigestsGiveNone() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512));
		deliverable(SECOND, record(DigestAlgorithm.SHA_256, OTHER_256), record(DigestAlgorithm.SHA_1, SHA_1));
		outbound(FIRST, SECOND);

		assertEquals(List.of(), svc.releaseNodeHashes(rd));
	}

	@Test
	void aDeliverableWithoutRecordsDoesNotCountSoTheOtherOnesAreTaken() {
		deliverable(FIRST);
		deliverable(SECOND, record(DigestAlgorithm.SHA_256, OTHER_256), record(DigestAlgorithm.SHA_1, SHA_1));
		outbound(FIRST, SECOND);

		assertEquals(List.of(new BomHash("SHA-256", OTHER_256), new BomHash("SHA-1", SHA_1)), svc.releaseNodeHashes(rd));
	}

	@Test
	void aDeliverableWithoutSoftwareMetadataOrOnlyBlankDigestsDoesNotCount() {
		when(getDeliverableService.getDeliverableData(FIRST)).thenReturn(Optional.of(new DeliverableData()));
		deliverable(MISSING, record(DigestAlgorithm.SHA_256, " "), record(DigestAlgorithm.SHA_512, null));
		deliverable(SECOND, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512));
		outbound(FIRST, MISSING, SECOND);

		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), svc.releaseNodeHashes(rd));
	}

	@Test
	void noDeliverablesGiveNone() {
		assertEquals(List.of(), svc.releaseNodeHashes(rd));
	}

	@Test
	void aReleaseThatOnlyConsumesADeliverableGetsNone() {
		// run 1 item T-1, the tester's second probe: the inbound deliverable's digest is not written
		assertEquals(List.of(), svc.releaseNodeHashes(rd));
		assertEquals(new ReleaseNodeFacts("Example Org", List.of(), null, null), svc.releaseNodeFacts(rd));
		verify(rd, never()).getInboundDeliverables();
	}

	@Test
	void aShippedAndAConsumedDeliverableGiveTheShippedOnesDigestsOnly() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512));
		outbound(FIRST);

		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), svc.releaseNodeHashes(rd));
	}

	@Test
	void aReleaseWithoutABaseVariantGetsNone() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256));
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.empty());

		assertEquals(List.of(), svc.releaseNodeHashes(rd));
	}

	// ---- ADR-11: the release purl and the deliverable purl name the same type -----------------

	@Test
	void aJarReleaseWithAnImageDeliverableGetsNone() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256)).setIdentifiers(purl("pkg:oci/a@sha256%3A" + SHA_256));
		outbound(FIRST);
		when(rd.getIdentifiers()).thenReturn(purl("pkg:maven/g/a@1?type=jar"));

		assertEquals(List.of(), svc.releaseNodeHashes(rd));
	}

	@Test
	void anImageReleaseWithAnImageDeliverableGetsItsDigests() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256)).setIdentifiers(purl("pkg:oci/a@sha256%3A" + SHA_256));
		outbound(FIRST);
		when(rd.getIdentifiers()).thenReturn(purl("pkg:oci/a?tag=1.0.0"));

		assertEquals(List.of(new BomHash("SHA-256", SHA_256)), svc.releaseNodeHashes(rd));
	}

	@Test
	void aReleaseWithoutAPurlTakesTheDeliverablesDigests() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256)).setIdentifiers(purl("pkg:oci/a@sha256%3A" + SHA_256));
		outbound(FIRST);
		when(rd.getIdentifiers()).thenReturn(new ArrayList<>());

		assertEquals(List.of(new BomHash("SHA-256", SHA_256)), svc.releaseNodeHashes(rd));
	}

	@Test
	void aDeliverableWithoutAPurlGivesItsDigestsToAReleaseWithOne() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256));
		outbound(FIRST);
		when(rd.getIdentifiers()).thenReturn(purl("pkg:maven/g/a@1?type=jar"));

		assertEquals(List.of(new BomHash("SHA-256", SHA_256)), svc.releaseNodeHashes(rd));
	}

	@Test
	void anUnparsablePurlOnEitherSideKeepsTheDigests() {
		DeliverableData dd = deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256));
		dd.setIdentifiers(purl("pkg:oci/a@sha256%3A" + SHA_256));
		outbound(FIRST);
		when(rd.getIdentifiers()).thenReturn(purl("not a purl"));
		assertEquals(List.of(new BomHash("SHA-256", SHA_256)), svc.releaseNodeHashes(rd));

		dd.setIdentifiers(purl("not a purl"));
		when(rd.getIdentifiers()).thenReturn(purl("pkg:maven/g/a@1?type=jar"));
		assertEquals(List.of(new BomHash("SHA-256", SHA_256)), svc.releaseNodeHashes(rd));
	}

	@Test
	void aDeliverableThatDoesNotResolveIsSkipped() {
		when(getDeliverableService.getDeliverableData(MISSING)).thenReturn(Optional.empty());
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512));
		outbound(MISSING, FIRST);

		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), svc.releaseNodeHashes(rd));
	}

	@Test
	void theSameDeliverableListedTwiceIsOneDeliverable() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512));
		outbound(FIRST, FIRST);

		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), svc.releaseNodeHashes(rd));
	}

	@Test
	void aRepeatedAlgorithmKeepsTheFirstRecord() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512),
				record(DigestAlgorithm.SHA_256, OTHER_256));
		outbound(FIRST);

		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), svc.releaseNodeHashes(rd));
	}

	// ---- facts -----------------------------------------------------------------------------------

	@Test
	void theFactsNameTheOrganizationAndCarryTheHashes() {
		deliverable(FIRST, record(DigestAlgorithm.SHA_256, SHA_256), record(DigestAlgorithm.SHA_512, SHA_512));
		outbound(FIRST);

		assertEquals(new ReleaseNodeFacts("Example Org", List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), null, null),
				svc.releaseNodeFacts(rd));
	}

	@Test
	void anExternalComponentNamesNoSupplier() {
		cd.setIsInternal(BelongsToOrganization.EXTERNAL);

		assertNull(svc.releaseNodeFacts(rd).supplierName());
	}

	@Test
	void aComponentWithoutAClassificationCountsAsInternal() {
		cd.setIsInternal(null);

		assertEquals("Example Org", svc.releaseNodeFacts(rd).supplierName());
	}

	@Test
	void aReleaseOfTheExternalSentinelOrganizationNamesNoSupplier() {
		when(rd.getOrg()).thenReturn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		OrganizationData external = new OrganizationData();
		external.setUuid(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		external.setName("External");
		when(getOrganizationService.getOrganizationData(CommonVariables.EXTERNAL_PROJ_ORG_UUID)).thenReturn(Optional.of(external));

		assertNull(svc.releaseNodeFacts(rd).supplierName());
	}

	@Test
	void anOrganizationThatDoesNotResolveNamesNoSupplier() {
		when(rd.getOrg()).thenReturn(UUID.randomUUID());

		assertNull(svc.releaseNodeFacts(rd).supplierName());
	}

	@Test
	void withReleaseNodeOfEmptyFactsLeavesTheRequestWithoutThem() {
		RebomOptions merge = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION);

		assertNull(merge.withReleaseNode(new ReleaseNodeFacts(null, List.of(), null, null)).releaseNode());
		assertNull(merge.withReleaseNode(new ReleaseNodeFacts(" ", null, null, null)).releaseNode());
		assertNull(merge.withReleaseNode(null).releaseNode());
		assertEquals(merge, merge.withReleaseNode(svc.releaseNodeFacts(externalRelease())));
	}

	@Test
	void withReleaseNodeCarriesAnEmptyHashListAsNullAndKeepsTheName() {
		RebomOptions merge = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT);

		assertEquals(new ReleaseNodeFacts("Example Org", null, null, null), merge.withReleaseNode(svc.releaseNodeFacts(rd)).releaseNode());
		assertEquals(new ReleaseNodeFacts(null, List.of(new BomHash("SHA-256", SHA_256)), null, null),
				merge.withReleaseNode(new ReleaseNodeFacts("", List.of(new BomHash("SHA-256", SHA_256)), null, null)).releaseNode());
	}

	private ReleaseData externalRelease() {
		ReleaseData external = mock(ReleaseData.class);
		when(external.getUuid()).thenReturn(UUID.randomUUID());
		when(external.getOrg()).thenReturn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		when(external.getComponent()).thenReturn(COMPONENT);
		return external;
	}
}
