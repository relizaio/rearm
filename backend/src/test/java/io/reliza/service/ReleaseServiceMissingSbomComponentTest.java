/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
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
import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.OrganizationData;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.VariantData;
import io.reliza.model.tea.Rebom.BomHash;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.MissingSbomComponent;
import io.reliza.model.tea.Rebom.MissingSbomReason;
import io.reliza.model.tea.TeaChecksumType;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-15 (T-1, ADR-2): what ReleaseService tells rebom about a component release of a product
 * that yields no BOM, field by field, and why it yields none. SCORE-30 (T-12): with the digests of
 * the one deliverable it ships (its base variant's outbound one), and no supplier for an EXTERNAL
 * component or the external sentinel organization.
 *
 * <p>SCORE-39 (T-11, ADR-3): with the declared licence DeclaredLicenses resolves and the supplier
 * name the component records; absent licence fields are null and never serialised.
 */
class ReleaseServiceMissingSbomComponentTest {

	private static final UUID RELEASE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000b1");
	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID ARTIFACT = UUID.randomUUID();
	private static final UUID SECOND_ARTIFACT = UUID.randomUUID();
	private static final UUID DELIVERABLE = UUID.randomUUID();
	private static final String SHA_256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	private static final String SHA_1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";

	private ReleaseService svc;
	private SharedReleaseService sharedReleaseService;
	private ArtifactService artifactService;
	private GetDeliverableService getDeliverableService;
	private GetOrganizationService getOrganizationService;
	private VariantService variantService;
	private ComponentData cd;
	private ReleaseData rd;

	@BeforeEach
	void wire() {
		svc = new ReleaseService(null);
		GetComponentService getComponentService = mock(GetComponentService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		artifactService = mock(ArtifactService.class);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		getDeliverableService = mock(GetDeliverableService.class);
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		variantService = mock(VariantService.class);
		ReflectionTestUtils.setField(svc, "variantService", variantService);
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.of(new VariantData()));

		cd = mock(ComponentData.class);
		when(cd.getName()).thenReturn("httpd");
		when(cd.getType()).thenReturn(ComponentType.COMPONENT);
		when(cd.getKind()).thenReturn(ComponentKind.GENERIC);
		when(cd.getIsInternalOrDefault()).thenReturn(BelongsToOrganization.INTERNAL);
		when(getComponentService.getComponentData(COMPONENT)).thenReturn(Optional.of(cd));
		OrganizationData od = mock(OrganizationData.class);
		when(od.getName()).thenReturn("Example Org");
		when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));

		rd = release(RELEASE);
	}

	private static ReleaseData release(UUID uuid) {
		ReleaseData release = mock(ReleaseData.class);
		when(release.getUuid()).thenReturn(uuid);
		when(release.getOrg()).thenReturn(ORG);
		when(release.getComponent()).thenReturn(COMPONENT);
		when(release.getVersion()).thenReturn("2.4.68");
		when(release.getArtifacts()).thenReturn(new ArrayList<>());
		when(release.getIdentifiers()).thenReturn(List.of());
		return release;
	}

	private static RearmIdentifier purl(String value) {
		return new RearmIdentifier(RearmIdentifierType.PURL, value);
	}

	/** A stored BOM artifact on the release, tagged with {@code coverage} when not null. */
	private void bomArtifact(ReleaseData release, UUID artifact, ArtifactCoverageType coverage) {
		ArtifactData ad = mock(ArtifactData.class);
		when(ad.getInternalBom()).thenReturn(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		when(ad.getTags()).thenReturn(null == coverage ? List.of()
				: List.of(new TagRecord(CommonVariables.ARTIFACT_COVERAGE_TYPE_TAG_KEY, coverage.name())));
		when(artifactService.getArtifactData(artifact)).thenReturn(Optional.of(ad));
		release.getArtifacts().add(artifact);
	}

	private String type(String... purls) {
		when(rd.getIdentifiers()).thenReturn(Arrays.stream(purls).map(ReleaseServiceMissingSbomComponentTest::purl).toList());
		return svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT).type();
	}

	@Test
	void everyFieldComesFromTheReleaseItsComponentAndItsOrganization() {
		when(rd.getIdentifiers()).thenReturn(List.of(purl("pkg:oci/httpd@2.4.68")));

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertEquals(new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000b1", "httpd", "2.4.68", "container",
				"Example Org", "Example Org", "pkg:oci/httpd@2.4.68", null, null, null, MissingSbomReason.NO_SBOM_ARTIFACT), built);
	}

	@Test
	void thePurlIsTheReleasesPreferredOne() {
		when(rd.getIdentifiers()).thenReturn(List.of(purl("pkg:npm/zeta@1.0.0"), purl("pkg:npm/alpha@1.0.0")));

		assertEquals("pkg:npm/alpha@1.0.0", svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT).purl());
	}

	@Test
	void aReleaseWithoutAPurlGetsNoneMadeUp() {
		when(rd.getIdentifiers()).thenReturn(List.of(new RearmIdentifier(RearmIdentifierType.CPE, "cpe:2.3:a:x:y:1:*:*:*:*:*:*:*")));

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.COVERAGE_EXCLUDED);

		assertNull(built.purl());
		assertEquals("library", built.type());
		assertEquals(MissingSbomReason.COVERAGE_EXCLUDED, built.reason());
	}

	@Test
	void theReleaseUuidRebomBuildsTheBomRefFromIsTheReleasesOwn() {
		// rebom makes it urn:rearm:release:<this>, pinned there (placeholderBomObjects.test.ts).
		assertEquals("0b0f4a52-6c55-4d1f-9a8e-0000000000b1",
				svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT).releaseUuid());
	}

	@Test
	void theReasonsAreNamedAsRebomWritesThemIntoTheMarker() {
		assertEquals(List.of("NO_SBOM_ARTIFACT", "COVERAGE_EXCLUDED"),
				Arrays.stream(MissingSbomReason.values()).map(Enum::name).toList());
	}

	// ---- type mapping ----------------------------------------------------------------------------

	@Test
	void aProductIsAnApplication() {
		when(cd.getType()).thenReturn(ComponentType.PRODUCT);
		assertEquals("application", type("pkg:oci/httpd@2.4.68"));
	}

	@Test
	void aHelmChartIsAnApplication() {
		when(cd.getKind()).thenReturn(ComponentKind.HELM);
		assertEquals("application", type("pkg:oci/charts/httpd@2.4.68"));
	}

	@Test
	void aReleaseIdentifiedByAnOciOrDockerPurlIsAContainer() {
		assertEquals("container", type("pkg:oci/httpd@2.4.68"));
		assertEquals("container", type("pkg:docker/library/httpd@2.4.68"));
		assertEquals("container", type("pkg:OCI/httpd@2.4.68"));
	}

	@Test
	void anythingElseIsALibrary() {
		assertEquals("library", type("pkg:maven/org.flywaydb/flyway-core@11.20.3"));
		assertEquals("library", type());
	}

	// ---- reason ----------------------------------------------------------------------------------

	@Test
	void withoutACoverageFilterTheReasonIsNoSbomArtifact() {
		assertEquals(MissingSbomReason.NO_SBOM_ARTIFACT, svc.missingSbomReason(rd, ArtifactBelongsTo.RELEASE, null));
		assertEquals(MissingSbomReason.NO_SBOM_ARTIFACT, svc.missingSbomReason(rd, ArtifactBelongsTo.RELEASE, List.of()));
	}

	@Test
	void aReleaseWhoseBomArtifactsTheFilterLeftOutIsCoverageExcluded() {
		bomArtifact(rd, ARTIFACT, ArtifactCoverageType.DEV);
		bomArtifact(rd, SECOND_ARTIFACT, ArtifactCoverageType.TEST);

		assertEquals(MissingSbomReason.COVERAGE_EXCLUDED, svc.missingSbomReason(rd, ArtifactBelongsTo.RELEASE,
				List.of(ArtifactCoverageType.DEV, ArtifactCoverageType.TEST)));
	}

	@Test
	void aReleaseWithoutBomArtifactsIsNoSbomArtifactEvenUnderAFilter() {
		assertEquals(MissingSbomReason.NO_SBOM_ARTIFACT, svc.missingSbomReason(rd, ArtifactBelongsTo.RELEASE,
				List.of(ArtifactCoverageType.DEV)));
	}

	@Test
	void aNestedProductIsJudgedByTheComponentReleasesItUnwindsTo() {
		when(cd.getType()).thenReturn(ComponentType.PRODUCT);
		ReleaseData bare = release(UUID.randomUUID());
		ReleaseData excluded = release(UUID.randomUUID());
		bomArtifact(excluded, ARTIFACT, ArtifactCoverageType.DEV);
		when(sharedReleaseService.unwindReleaseDependencies(rd)).thenReturn(new LinkedHashSet<>(List.of(bare)));
		assertEquals(MissingSbomReason.NO_SBOM_ARTIFACT, svc.missingSbomReason(rd, ArtifactBelongsTo.RELEASE, List.of(ArtifactCoverageType.DEV)));

		when(sharedReleaseService.unwindReleaseDependencies(rd)).thenReturn(new LinkedHashSet<>(List.of(bare, excluded)));
		assertEquals(MissingSbomReason.COVERAGE_EXCLUDED, svc.missingSbomReason(rd, ArtifactBelongsTo.RELEASE, List.of(ArtifactCoverageType.DEV)));
	}

	// ---- hashes and supplier (SCORE-30 T-12, ADR-2, ADR-3) ---------------------------------------

	/** The release ships one deliverable with digests: an outbound deliverable of its base variant. */
	private void oneDeliverableWithDigests() {
		DeliverableData dd = new DeliverableData();
		dd.setSoftwareMetadata(SoftwareDeliverableMetadata.builder().digestRecords(new LinkedHashSet<>(List.of(
				new DigestRecord(TeaChecksumType.SHA_256, SHA_256), new DigestRecord(TeaChecksumType.SHA_1, SHA_1)))).build());
		when(getDeliverableService.getDeliverableData(DELIVERABLE)).thenReturn(Optional.of(dd));
		VariantData base = new VariantData();
		base.setOutboundDeliverables(new LinkedHashSet<>(List.of(DELIVERABLE)));
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.of(base));
	}

	@Test
	void thePlaceholderCarriesTheDigestsOfTheReleasesOneDeliverable() {
		oneDeliverableWithDigests();
		when(rd.getIdentifiers()).thenReturn(List.of(purl("pkg:oci/httpd@2.4.68")));

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertEquals(new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000b1", "httpd", "2.4.68", "container",
				"Example Org", "Example Org", "pkg:oci/httpd@2.4.68",
				List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-1", SHA_1)), null, null, MissingSbomReason.NO_SBOM_ARTIFACT), built);
	}

	@Test
	void withoutADigestThePlaceholderCarriesNoHashesAndSendsNoSuchKey() throws Exception {
		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertNull(built.hashes());
		JsonNode sent = Utils.OM.readTree(Utils.OM.writeValueAsString(built));
		assertFalse(sent.has("hashes"), sent.toString());
	}

	@Test
	void theHashesAreSentInRebomsInputShape() throws Exception {
		oneDeliverableWithDigests();

		JsonNode sent = Utils.OM.readTree(Utils.OM.writeValueAsString(
				svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT)));

		assertEquals(Utils.OM.readTree("[{\"alg\":\"SHA-256\",\"content\":\"" + SHA_256 + "\"},"
				+ "{\"alg\":\"SHA-1\",\"content\":\"" + SHA_1 + "\"}]"), sent.get("hashes"));
	}

	@Test
	void aConsumedDeliverableGivesThePlaceholderNoHashes() {
		oneDeliverableWithDigests();
		when(variantService.findBaseVariantForRelease(RELEASE)).thenReturn(Optional.of(new VariantData()));
		when(rd.getInboundDeliverables()).thenReturn(new ArrayList<>(List.of(DELIVERABLE)));

		assertNull(svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT).hashes());
	}

	@Test
	void anExternalComponentsPlaceholderNamesNoSupplierAndKeepsTheGroupAndItsHashes() {
		oneDeliverableWithDigests();
		when(cd.getIsInternalOrDefault()).thenReturn(BelongsToOrganization.EXTERNAL);

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertNull(built.supplierName());
		assertEquals("Example Org", built.group());
		assertEquals(List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-1", SHA_1)), built.hashes());
	}

	@Test
	void aReleaseOfTheExternalSentinelOrganizationNamesNoSupplier() {
		when(rd.getOrg()).thenReturn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		OrganizationData external = mock(OrganizationData.class);
		when(external.getUuid()).thenReturn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		when(external.getName()).thenReturn("External");
		when(getOrganizationService.getOrganizationData(CommonVariables.EXTERNAL_PROJ_ORG_UUID)).thenReturn(Optional.of(external));

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertNull(built.supplierName());
		assertEquals("External", built.group());
	}

	@Test
	void thePlaceholderCarriesTheComponentLicenceItsUrlAndTheComponentSupplier() {
		when(cd.getLicenseExpression()).thenReturn("LicenseRef-Acme-Commercial");
		when(cd.getLicenseUrl()).thenReturn("https://acme.example/licence");
		when(cd.getSupplierName()).thenReturn("Acme Corp");

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertEquals("LicenseRef-Acme-Commercial", built.licenseExpression());
		assertEquals("https://acme.example/licence", built.licenseUrl());
		assertEquals("Acme Corp", built.supplierName());
		JsonNode json = Utils.OM.valueToTree(built);
		assertEquals("LicenseRef-Acme-Commercial", json.get("licenseExpression").asString());
		assertEquals("https://acme.example/licence", json.get("licenseUrl").asString());
	}

	@Test
	void thePlaceholderOfAnExternalComponentWithoutABomNamesItsSupplierAndTheOrganizationDefault() {
		when(cd.getIsInternalOrDefault()).thenReturn(BelongsToOrganization.EXTERNAL);
		when(cd.getSupplierName()).thenReturn("Upstream Vendor");
		OrganizationData od = getOrganizationService.getOrganizationData(ORG).orElseThrow();
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setDefaultLicenseExpression("AGPL-3.0-only");
		settings.setDefaultLicenseUrl("https://org.example/licence");
		when(od.getSettings()).thenReturn(settings);

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertEquals("AGPL-3.0-only", built.licenseExpression());
		assertEquals("https://org.example/licence", built.licenseUrl());
		assertEquals("Upstream Vendor", built.supplierName());
	}

	@Test
	void aReleaseOverrideReachesThePlaceholderWithoutAUrl() {
		when(cd.getLicenseExpression()).thenReturn("LicenseRef-Acme-Commercial");
		when(cd.getLicenseUrl()).thenReturn("https://acme.example/licence");
		when(rd.getLicenseExpression()).thenReturn("MIT");

		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertEquals("MIT", built.licenseExpression());
		assertNull(built.licenseUrl());
		assertFalse(Utils.OM.<JsonNode>valueToTree(built).has("licenseUrl"), "a null url is not serialised");
	}

	@Test
	void withoutADeclaredLicenceTheLicenceFieldsAreNullAndNotSerialised() {
		MissingSbomComponent built = svc.buildMissingSbomComponent(rd, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertNull(built.licenseExpression());
		assertNull(built.licenseUrl());
		JsonNode json = Utils.OM.valueToTree(built);
		assertFalse(json.has("licenseExpression"));
		assertFalse(json.has("licenseUrl"));
	}
}
