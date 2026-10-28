/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.SettingsScope;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.service.TeaProfileService.TeaCaller;

/**
 * Task TEA-27, design 4.6: the names a TEA publishing settings row publishes. The pattern, non-empty,
 * duplicate and CE/Pro count checks run on every non-FOLLOW row; the resolve check runs only on an
 * ENABLED row (F-4); a FOLLOW row is checked against the followed perspective's ENABLED row at the
 * component with that perspective preferred (F-1). The SBOM profile resolver is the mock of the base.
 */
class TeaProfileServiceProfileNamesTest extends TeaProfileServiceTestBase {

	/** ReARM CE (rearm-saas#955) stores every SBOM profile name as default: Pro's naming cases skip there (LicensingConstants.isOssEdition()), so one file serves both editions. */
	private static final String CE_NAMES = "ReARM CE stores every SBOM profile as default";

	private TeaProfileInputDto names(TeaProfileInputDto in, String... names) {
		return in.toBuilder().profiles(null == names ? null : List.of(names)).build();
	}

	private TeaProfileInputDto enabled(TeaProfileInputDto in) {
		return in.toBuilder().publishing(TeaPublishing.ENABLED).build();
	}

	@Test
	void profilesAreRequiredAndNonEmptyOnEveryNonFollowSaveEnabledOrDisabled() {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		for (TeaPublishing publishing : TeaPublishing.values()) {
			TeaProfileInputDto org = full().toBuilder().publishing(publishing).build();
			assertEquals("profiles is required",
					refused(TeaProfileScope.ORGANIZATION, null, org.toBuilder().profiles(null).build()).getMessage());
			assertEquals("profiles must name at least one SBOM profile",
					refused(TeaProfileScope.ORGANIZATION, null, org.toBuilder().profiles(List.of()).build()).getMessage());
			assertEquals("profiles must name at least one SBOM profile",
					refused(TeaProfileScope.PERSPECTIVE, alpha.uuid(), org.toBuilder().profiles(List.of()).build())
							.getMessage());
			assertEquals("profiles is required", refused(TeaProfileScope.COMPONENT, cd.getUuid(),
					override().toBuilder().publishing(publishing).profiles(null).build()).getMessage());
		}
		verify(repository, never()).save(any());
	}

	@Test
	void aFollowRowWithProfilesIsRefused() throws RelizaException {
		TeaProfileInputDto follow = full().toBuilder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE)
				.followedPerspective(alpha.uuid()).build();
		assertEquals("profiles must be empty when mode is FOLLOW_PERSPECTIVE",
				refused(TeaProfileScope.COMPONENT, cd.getUuid(), follow).getMessage());
		TeaProfileData saved = save(TeaProfileScope.COMPONENT, cd.getUuid(), follow.toBuilder().profiles(List.of()).build(),
				TeaCaller.ORG_ADMIN);
		assertNull(saved.getProfiles(), "a FOLLOW row stores no names");
	}

	@Test
	void duplicateAndInvalidNamesAreRefused() {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		assertEquals("profiles names SBOM profile default twice",
				refused(TeaProfileScope.ORGANIZATION, null, names(full(), "default", "fda", "default")).getMessage());
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, names(full(), "default", "bad name")).getMessage()
				.contains("SBOM profile name"));
		List<String> withNull = new ArrayList<>(List.of("default"));
		withNull.add(null);
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, full().toBuilder().profiles(withNull).build())
				.getMessage().contains("SBOM profile name is required"));
	}

	// ReARM CE has the one SBOM profile `default`: whatever names a save sends, CE stores `default`
	@Test
	void ceReplacesTheNamesWithDefaultAndProKeepsThem() {
		assertEquals(List.of("default"), TeaProfileService.editionProfileNames(List.of("fda", "top"), true));
		assertEquals(List.of("default"), TeaProfileService.editionProfileNames(null, true));
		assertEquals(List.of("fda", "top"), TeaProfileService.editionProfileNames(List.of("fda", "top"), false));
		assertEquals("default", SbomProfileService.editionName("fda", true));
		assertEquals("fda", SbomProfileService.editionName("fda", false));
	}

	@Test
	void ceAllowsExactlyOneNameAndProAllowsTen() {
		assertDoesNotThrow(() -> TeaProfileService.validateProfileNames(List.of("default"), true));
		RelizaException ce = assertThrows(RelizaException.class,
				() -> TeaProfileService.validateProfileNames(List.of("default", "fda"), true));
		assertEquals("ReARM CE publishes one SBOM profile per TEA publishing settings; several profiles are a ReARM Pro "
				+ "feature", ce.getMessage());

		List<String> ten = IntStream.range(0, 10).mapToObj(i -> "p" + i).toList();
		assertDoesNotThrow(() -> TeaProfileService.validateProfileNames(ten, false));
		assertDoesNotThrow(() -> TeaProfileService.validateProfileNames(List.of("a", "b"), false));
		List<String> eleven = IntStream.range(0, 11).mapToObj(i -> "p" + i).toList();
		assertEquals("TEA publishing settings name at most 10 SBOM profiles",
				assertThrows(RelizaException.class, () -> TeaProfileService.validateProfileNames(eleven, false))
						.getMessage());
	}

	@Test
	void theSaveGoesThroughTheEditionSeam() throws RelizaException {
		// The edition seam (LicensingConstants.isOssEdition(), editionProfileNames): ReARM Pro keeps the names
		// in the order given; ReARM CE (rearm-saas#955) replaces whatever names a save sends with default --
		// two names, another name and none at all all store [default]. One file for both editions, so the
		// Pro-to-CE sync copies it unchanged.
		List<String> two = save(TeaProfileScope.ORGANIZATION, null, names(full(), "default", "fda"), TeaCaller.ORG_ADMIN)
				.getProfiles();
		if (!LicensingConstants.isOssEdition()) {
			assertEquals(List.of("default", "fda"), two, "the order given is kept");
			return;
		}
		assertEquals(List.of("default"), two);
		assertEquals(List.of("default"), save(TeaProfileScope.ORGANIZATION, null, names(full(), "fda"),
				TeaCaller.ORG_ADMIN).getProfiles());
		assertEquals(List.of("default"), save(TeaProfileScope.ORGANIZATION, null, full().toBuilder().profiles(null).build(),
				TeaCaller.ORG_ADMIN).getProfiles());
	}

	@Test
	void anEnabledRowNamingANotFoundNameIsRefusedAndTheSameRowDisabledIsAccepted() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		names.put("nope", notFound());
		assertEquals("SBOM profile nope does not resolve at the organization: define it there or at a higher scope",
				refused(TeaProfileScope.ORGANIZATION, null, enabled(names(full(), "nope"))).getMessage());
		assertEquals("SBOM profile nope does not resolve at perspective Alpha: define it there or at a higher scope",
				refused(TeaProfileScope.PERSPECTIVE, alpha.uuid(), enabled(names(full(), "nope"))).getMessage());
		assertEquals("SBOM profile nope does not resolve at component widget: define it there or at a higher scope",
				refused(TeaProfileScope.COMPONENT, cd.getUuid(), enabled(names(override(), "nope"))).getMessage());
		verify(repository, never()).save(any());

		assertEquals(List.of("nope"), save(TeaProfileScope.ORGANIZATION, null, names(full(), "nope"), TeaCaller.ORG_ADMIN)
				.getProfiles());
		assertEquals(List.of("nope"), save(TeaProfileScope.COMPONENT, cd.getUuid(), names(override(), "nope"),
				TeaCaller.ORG_ADMIN).getProfiles());
		// The organization and perspective rows resolve at their scope; the component row at the component, no fallback.
		verify(sbomResolver).effectiveOfScope(eq(org), eq(SettingsScope.ORGANIZATION), eq(null), eq("nope"));
		verify(sbomResolver).effectiveOfScope(eq(org), eq(SettingsScope.PERSPECTIVE), eq(alpha.uuid()), eq("nope"));
		verify(sbomResolver).resolveForComponent(any(), eq("nope"));
		verify(sbomResolver, never()).resolveForComponent(any(), anyString(), any(), any());
	}

	@Test
	void anEnabledComponentRowNamingAConflictIsRefusedNamingThePerspectives() throws RelizaException {
		names.put("default", nameConflict());
		assertEquals("SBOM profile default resolves to a conflict between perspectives Alpha, Beta at component widget: "
				+ "define it on the component or follow one perspective for it",
				refused(TeaProfileScope.COMPONENT, cd.getUuid(), enabled(override())).getMessage());
		TeaProfileData disabled = save(TeaProfileScope.COMPONENT, cd.getUuid(), override(), TeaCaller.ORG_ADMIN);
		assertEquals(TeaPublishing.DISABLED, disabled.getPublishing());
	}

	@Test
	void aScopeLocalNameDefinedAtTheComponentIsAcceptedOnItsRow() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		// The mocked catalog answers "premarket" RESOLVED at the component, as a component-scope row would.
		TeaProfileData saved = save(TeaProfileScope.COMPONENT, cd.getUuid(), enabled(names(override(), "premarket")),
				TeaCaller.ORG_ADMIN);
		assertEquals(List.of("premarket"), saved.getProfiles());
		verify(sbomResolver).resolveForComponent(any(), eq("premarket"));
	}

	/** The followed perspective's stored TEA row, with its names. */
	private void followedRow(TeaPublishing publishing, String... profileNames) {
		TeaProfile e = storedRow(TeaProfileScope.PERSPECTIVE, alpha.uuid(), null, TeaVisibility.PRIVATE, publishing);
		TeaProfileData d = TeaProfileData.dataFromRecord(e);
		d.setProfiles(List.of(profileNames));
		e.setRecordData(d.toRecordData());
	}

	private TeaProfileInputDto followAlpha() {
		return TeaProfileInputDto.builder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE)
				.followedPerspective(alpha.uuid()).build();
	}

	@Test
	void aFollowOfAPerspectiveWhoseEnabledRowNamesAResolvingNameIsAccepted() throws RelizaException {
		// F-1: the member of Alpha and Beta, both defining default; the preference settles it at the component.
		followedRow(TeaPublishing.ENABLED, "default");
		TeaProfileData saved = save(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha(), TeaCaller.ORG_ADMIN);
		assertEquals(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, saved.getMode());
		verify(sbomResolver).resolveForComponent(any(), eq("default"), eq(null), eq(alpha.uuid()));
	}

	@Test
	void aFollowWhoseFollowedNameDoesNotResolveAtTheComponentIsRefused() {
		followedRow(TeaPublishing.ENABLED, "default", "fda");
		names.put("fda", nameConflict());
		assertEquals("SBOM profile fda, named by the TEA publishing settings of perspective Alpha that component widget "
				+ "would follow, resolves to a conflict between perspectives Alpha, Beta for the component; define it on "
				+ "the component or follow one perspective for it",
				refused(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha()).getMessage());
		names.put("fda", notFound());
		assertEquals("SBOM profile fda, named by the TEA publishing settings of perspective Alpha that component widget "
				+ "would follow, does not resolve for the component",
				refused(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha()).getMessage());
	}

	@Test
	void aFollowOfADisabledOrAbsentRowChecksNoName() throws RelizaException {
		names.put("default", nameConflict());
		save(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha(), TeaCaller.ORG_ADMIN);
		stored.clear();
		followedRow(TeaPublishing.DISABLED, "default");
		save(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha(), TeaCaller.ORG_ADMIN);
		verify(sbomResolver, never()).resolveForComponent(any(), anyString(), any(), any());
	}

	@Test
	void assessSaveAppliesTheSameRules() {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		names.put("nope", notFound());
		assertEquals("SBOM profile nope does not resolve at the organization: define it there or at a higher scope",
				assertThrows(RelizaException.class, () -> service.assessSave(org, TeaProfileScope.ORGANIZATION, null,
						enabled(names(full(), "nope")))).getMessage());
		assertDoesNotThrow(() -> service.assessSave(org, TeaProfileScope.ORGANIZATION, null, names(full(), "nope")));
		followedRow(TeaPublishing.ENABLED, "nope");
		assertTrue(assertThrows(RelizaException.class, () -> service.assessSave(org, TeaProfileScope.COMPONENT,
				cd.getUuid(), followAlpha())).getMessage().contains("does not resolve for the component"));
	}

	@Test
	void referencesToListsTheRowsNamingTheProfileDisabledIncludedAndNoFollowRow() {
		TeaProfile o = storedRow(TeaProfileScope.ORGANIZATION, null, null, TeaVisibility.PRIVATE, TeaPublishing.ENABLED);
		TeaProfile p = storedRow(TeaProfileScope.PERSPECTIVE, alpha.uuid(), null, TeaVisibility.PRIVATE,
				TeaPublishing.DISABLED);
		TeaProfile c = storedRow(TeaProfileScope.COMPONENT, cd.getUuid(), TeaComponentProfileMode.OVERRIDE,
				TeaVisibility.PRIVATE, TeaPublishing.ENABLED);
		TeaProfileData cData = TeaProfileData.dataFromRecord(c);
		cData.setProfiles(List.of("fda"));
		c.setRecordData(cData.toRecordData());
		UUID other = UUID.randomUUID();
		TeaProfile follow = storedRow(TeaProfileScope.COMPONENT, other, TeaComponentProfileMode.FOLLOW_PERSPECTIVE,
				TeaVisibility.PRIVATE, TeaPublishing.ENABLED);
		TeaProfileData f = TeaProfileData.dataFromRecord(follow);
		f.setProfiles(null);
		follow.setRecordData(f.toRecordData());
		when(repository.listByOrg(org)).thenReturn(List.of(o, p, c, follow));

		List<ScopedSettingsRef> refs = service.referencesTo(org, "default");
		assertEquals(List.of(new ScopedSettingsRef(SettingsScope.ORGANIZATION, null,
				"the TEA publishing settings of the organization"),
				new ScopedSettingsRef(SettingsScope.PERSPECTIVE, alpha.uuid(),
						"the TEA publishing settings of perspective Alpha")), refs);
		assertEquals(List.of(new ScopedSettingsRef(SettingsScope.COMPONENT, cd.getUuid(),
				"the TEA publishing settings of component widget")), service.referencesTo(org, "fda"));
		assertTrue(service.referencesTo(org, "nope").isEmpty());
	}
}
