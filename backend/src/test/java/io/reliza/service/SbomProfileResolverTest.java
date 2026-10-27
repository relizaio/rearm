/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.reliza.model.SbomProfile;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.SettingsScope;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.service.SbomProfileResolver.SbomProfileCatalogEntry;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;

/**
 * Task TEA-27, design 4.3: which SBOM profile row a name resolves to, per name, at every scope and
 * mode, over the real engine and the real catalog service on an in-memory repository.
 */
class SbomProfileResolverTest extends SbomProfileServiceTestBase {

	private static final SbomDependencyDepth FULL = SbomDependencyDepth.FULL;
	private static final SbomDependencyDepth TOP = SbomDependencyDepth.TOP_LEVEL_ONLY;

	@Test
	void theOrganizationsDefaultIsOverriddenByAPerspectiveRowAndByAComponentRow() {
		SbomProfile o = orgRow("default", FULL);
		lenient().when(perspectives.teaPerspectivesOfComponent(any())).thenReturn(List.of(alpha));
		SbomProfileResolution fromOrg = resolver.resolveForComponent(cd, "default");
		assertEquals(ScopedSettingsSource.ORGANIZATION, fromOrg.source());
		assertEquals(o.getUuid(), fromOrg.profile().getUuid());

		SbomProfile p = perspectiveRow(alpha, "default", TOP);
		SbomProfileResolution fromPerspective = resolver.resolveForComponent(cd, "default");
		assertEquals(ScopedSettingsSource.PERSPECTIVE, fromPerspective.source());
		assertEquals(alpha.uuid(), fromPerspective.sourceObject());
		assertEquals(p.getUuid(), fromPerspective.profile().getUuid());
		assertEquals(TOP, fromPerspective.profile().getDependencyDepth());
		assertEquals(SettingsScope.PERSPECTIVE, fromPerspective.profile().getScope());

		SbomProfile c = componentRow("default", FULL);
		SbomProfileResolution fromComponent = resolver.resolveForComponent(cd, "default");
		assertEquals(ScopedSettingsSource.COMPONENT, fromComponent.source());
		assertEquals(cd.getUuid(), fromComponent.sourceObject());
		assertEquals(c.getUuid(), fromComponent.profile().getUuid());

		assertEquals(ScopedSettingsSource.ORGANIZATION,
				resolver.effectiveOfScope(org, SettingsScope.ORGANIZATION, null, "default").source());
		assertEquals(p.getUuid(),
				resolver.effectiveOfScope(org, SettingsScope.PERSPECTIVE, alpha.uuid(), "default").profile().getUuid());
		assertEquals(c.getUuid(),
				resolver.effectiveOfScope(org, SettingsScope.COMPONENT, cd.getUuid(), "default").profile().getUuid());
		assertEquals(ScopedResolutionStatus.RESOLVED,
				resolver.parentOfScope(org, SettingsScope.COMPONENT, cd.getUuid(), "default").status());
		assertEquals(p.getUuid(),
				resolver.parentOfScope(org, SettingsScope.COMPONENT, cd.getUuid(), "default").profile().getUuid());
		assertEquals(o.getUuid(),
				resolver.parentOfScope(org, SettingsScope.PERSPECTIVE, alpha.uuid(), "default").profile().getUuid());
	}

	@Test
	void aScopeLocalNameAtAPerspectiveResolvesForAMemberAndIsNotFoundAbove() {
		lenient().when(perspectives.teaPerspectivesOfComponent(any())).thenReturn(List.of(alpha));
		SbomProfile p = perspectiveRow(alpha, "premarket", TOP);
		SbomProfileResolution member = resolver.resolveForComponent(cd, "premarket");
		assertEquals(ScopedResolutionStatus.RESOLVED, member.status());
		assertEquals(p.getUuid(), member.profile().getUuid());
		assertEquals(p.getUuid(),
				resolver.effectiveOfScope(org, SettingsScope.PERSPECTIVE, alpha.uuid(), "premarket").profile().getUuid());

		SbomProfileResolution above = resolver.effectiveOfScope(org, SettingsScope.ORGANIZATION, null, "premarket");
		assertEquals(ScopedResolutionStatus.NOT_FOUND, above.status());
		assertNull(above.profile());
		assertNull(above.source());
		assertEquals(ScopedResolutionStatus.NOT_FOUND,
				resolver.effectiveOfScope(org, SettingsScope.PERSPECTIVE, beta.uuid(), "premarket").status(),
				"a sibling perspective does not see it");
	}

	@Test
	void aScopeLocalNameAtAComponent() {
		SbomProfile c = componentRow("internal", TOP);
		assertEquals(c.getUuid(), resolver.resolveForComponent(cd, "internal").profile().getUuid());
		assertEquals(ScopedResolutionStatus.NOT_FOUND,
				resolver.parentOfScope(org, SettingsScope.COMPONENT, cd.getUuid(), "internal").status());
		assertEquals(ScopedResolutionStatus.NOT_FOUND,
				resolver.effectiveOfScope(org, SettingsScope.ORGANIZATION, null, "internal").status());
	}

	@Test
	void twoPerspectivesDefiningANameConflictForAMemberWhileAnotherNameResolves() {
		perspectiveRow(beta, "default", FULL);
		perspectiveRow(alpha, "default", TOP);
		SbomProfile other = orgRow("other", FULL);
		SbomProfileResolution r = resolver.resolveForComponent(cd, "default");
		assertEquals(ScopedResolutionStatus.CONFLICT, r.status());
		assertNull(r.profile());
		assertEquals(List.of(alpha, beta), r.conflictingPerspectives());
		assertEquals(ScopedResolutionStatus.CONFLICT,
				resolver.effectiveOfScope(org, SettingsScope.COMPONENT, cd.getUuid(), "default").status());
		assertEquals(other.getUuid(), resolver.resolveForComponent(cd, "other").profile().getUuid(),
				"a conflict is per name");
	}

	@Test
	void thePreferredPerspectiveSettlesTheConflictOnlyWhenItDefinesTheName() {
		// F-1: the four-argument form, the publish path.
		SbomProfile a = perspectiveRow(alpha, "default", TOP);
		perspectiveRow(beta, "default", FULL);
		SbomProfileResolution preferred = resolver.resolveForComponent(cd, "default", null, alpha.uuid());
		assertEquals(ScopedResolutionStatus.RESOLVED, preferred.status());
		assertEquals(ScopedSettingsSource.PERSPECTIVE, preferred.source());
		assertEquals(alpha.uuid(), preferred.sourceObject());
		assertEquals(a.getUuid(), preferred.profile().getUuid());

		UUID gamma = UUID.randomUUID();
		SbomProfileResolution notDefining = resolver.resolveForComponent(cd, "default", null, gamma);
		assertEquals(ScopedResolutionStatus.CONFLICT, notDefining.status());
		assertEquals(List.of(alpha, beta), notDefining.conflictingPerspectives());
	}

	@Test
	void aComponentFollowRowResolvesTheFollowedPerspectivesRowOfTheName() {
		perspectiveRow(alpha, "default", FULL);
		SbomProfile b = perspectiveRow(beta, "default", TOP);
		followRow("default", beta);
		SbomProfileResolution r = resolver.resolveForComponent(cd, "default");
		assertEquals(ScopedSettingsSource.FOLLOWED_PERSPECTIVE, r.source());
		assertEquals(beta.uuid(), r.sourceObject());
		assertEquals(b.getUuid(), r.profile().getUuid());
	}

	@Test
	void defaultWithNoRowIsTheBuiltInAndAnyOtherNameIsNotFound() {
		SbomProfileResolution d = resolver.resolveForComponent(cd, "default");
		assertEquals(ScopedResolutionStatus.RESOLVED, d.status());
		assertEquals(ScopedSettingsSource.DEFAULT, d.source());
		assertEquals(SbomProfileData.builtInDefault(org), d.profile());
		SbomProfileResolution none = resolver.resolveForComponent(cd, "fda");
		assertEquals(ScopedResolutionStatus.NOT_FOUND, none.status());
		assertNull(none.profile());
		assertTrue(none.conflictingPerspectives().isEmpty());
		assertEquals(ScopedSettingsSource.DEFAULT,
				resolver.parentOfScope(org, SettingsScope.ORGANIZATION, null, "default").source());
		assertEquals(ScopedResolutionStatus.NOT_FOUND,
				resolver.parentOfScope(org, SettingsScope.ORGANIZATION, null, "fda").status());
	}

	@Test
	void supportMetadataIncludeReadsExcludeWhileTheInjectionIsOff() {
		SbomProfile o = orgRow("default", FULL);
		SbomProfileData stored0 = SbomProfileData.dataFromRecord(o);
		stored0.setSupportMetadata(SbomSupportMetadata.INCLUDE);
		o.setRecordData(stored0.toRecordData());

		injection(SupportInjectionSetting.DISABLED);
		assertEquals(SbomSupportMetadata.EXCLUDE, resolver.resolveForComponent(cd, "default").profile().getSupportMetadata());
		assertEquals(SbomSupportMetadata.EXCLUDE,
				resolver.effectiveOfScope(org, SettingsScope.ORGANIZATION, null, "default").profile().getSupportMetadata());
		assertEquals(SbomSupportMetadata.INCLUDE, SbomProfileData.dataFromRecord(o).getSupportMetadata(),
				"the stored row is untouched");

		injection(SupportInjectionSetting.ENABLED);
		assertEquals(SbomSupportMetadata.INCLUDE, resolver.resolveForComponent(cd, "default").profile().getSupportMetadata());
		assertEquals(SupportInjectionSetting.ENABLED, resolver.supportInjectionOf(org));
	}

	@Test
	void theCatalogListsTheUnionInNameOrderWithAConflictEntry() {
		orgRow("zeta", FULL);
		perspectiveRow(alpha, "default", TOP);
		perspectiveRow(beta, "default", FULL);
		perspectiveRow(beta, "Beta-only", FULL);
		componentRow("mine", FULL);
		List<SbomProfileCatalogEntry> catalog = resolver.catalogOfScope(org, SettingsScope.COMPONENT, cd.getUuid());
		assertEquals(List.of("Beta-only", "default", "mine", "zeta"),
				catalog.stream().map(SbomProfileCatalogEntry::name).toList());
		Map<String, SbomProfileResolution> byName = catalog.stream()
				.collect(Collectors.toMap(SbomProfileCatalogEntry::name, SbomProfileCatalogEntry::resolution));
		assertEquals(ScopedResolutionStatus.CONFLICT, byName.get("default").status(), "no preference applied");
		assertEquals(List.of(alpha, beta), byName.get("default").conflictingPerspectives());
		assertEquals(ScopedSettingsSource.PERSPECTIVE, byName.get("Beta-only").source());
		assertEquals(ScopedSettingsSource.COMPONENT, byName.get("mine").source());
		assertEquals(ScopedSettingsSource.ORGANIZATION, byName.get("zeta").source());

		List<SbomProfileCatalogEntry> atOrg = resolver.catalogOfScope(org, SettingsScope.ORGANIZATION, null);
		assertEquals(List.of("default", "zeta"), atOrg.stream().map(SbomProfileCatalogEntry::name).toList());
		assertEquals(ScopedSettingsSource.DEFAULT, atOrg.get(0).resolution().source());

		List<SbomProfileCatalogEntry> atBeta = resolver.catalogOfScope(org, SettingsScope.PERSPECTIVE, beta.uuid());
		assertEquals(List.of("Beta-only", "default", "zeta"), atBeta.stream().map(SbomProfileCatalogEntry::name).toList());
		assertEquals(ScopedSettingsSource.PERSPECTIVE, atBeta.get(1).resolution().source());
	}
}
