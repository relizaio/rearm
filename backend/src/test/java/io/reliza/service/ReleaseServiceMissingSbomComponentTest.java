/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.MissingSbomComponent;
import io.reliza.model.tea.Rebom.MissingSbomReason;

/**
 * SCORE-15 (T-1, ADR-2): what ReleaseService tells rebom about a component release of a product
 * that yields no BOM, field by field, and why it yields none.
 */
class ReleaseServiceMissingSbomComponentTest {

	private static final UUID RELEASE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000b1");
	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID ARTIFACT = UUID.randomUUID();
	private static final UUID SECOND_ARTIFACT = UUID.randomUUID();

	private ReleaseService svc;
	private SharedReleaseService sharedReleaseService;
	private ArtifactService artifactService;
	private ComponentData cd;
	private ReleaseData rd;

	@BeforeEach
	void wire() {
		svc = new ReleaseService(null);
		GetComponentService getComponentService = mock(GetComponentService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		artifactService = mock(ArtifactService.class);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);

		cd = mock(ComponentData.class);
		when(cd.getName()).thenReturn("httpd");
		when(cd.getType()).thenReturn(ComponentType.COMPONENT);
		when(cd.getKind()).thenReturn(ComponentKind.GENERIC);
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
				"Example Org", "Example Org", "pkg:oci/httpd@2.4.68", MissingSbomReason.NO_SBOM_ARTIFACT), built);
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
}
