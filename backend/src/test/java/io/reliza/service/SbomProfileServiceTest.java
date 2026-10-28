/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.SbomProfile;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.SettingsScope;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.dto.SbomProfileInputDto;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/** Task TEA-27, design 4.5: storing SBOM profiles, the delete guard and the F-6 audit record. */
class SbomProfileServiceTest extends SbomProfileServiceTestBase {

	/** ReARM CE (rearm-saas#955) stores every SBOM profile name as default: Pro's naming cases skip there (LicensingConstants.isOssEdition()), so one file serves both editions. */
	private static final String CE_NAMES = "ReARM CE stores every SBOM profile as default";

	private RelizaException refused(SettingsScope scope, UUID object, String name, SbomProfileInputDto in) {
		return assertThrows(RelizaException.class, () -> service.saveProfile(org, scope, object, name, in, WU));
	}

	private SbomProfileData save(SettingsScope scope, UUID object, String name, SbomProfileInputDto in)
			throws RelizaException {
		return service.saveProfile(org, scope, object, name, in, WU);
	}

	private SbomProfileInputDto override() {
		return recipe().toBuilder().mode(ScopedSettingsMode.OVERRIDE).build();
	}

	@Test
	void theNameIsValidatedAndNeverEchoedWhenRefused() {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		for (String bad : new String[] { null, "", " default", ".x", "a".repeat(65), "two words", "d\u00e9faut" }) {
			String m = refused(SettingsScope.ORGANIZATION, null, bad, recipe()).getMessage();
			assertTrue(m.contains("SBOM profile name"), m);
			if (null != bad && !bad.isEmpty()) assertFalse(m.contains(bad), "the refused name is not echoed: " + m);
		}
		verify(repository, never()).save(any());
	}

	@Test
	void theOrganizationScopeTakesNoObjectAndNoMode() {
		assertTrue(refused(SettingsScope.ORGANIZATION, cd.getUuid(), "default", recipe()).getMessage()
				.contains("object must be empty for an ORGANIZATION SBOM profile"));
		assertTrue(refused(SettingsScope.ORGANIZATION, null, "default", override()).getMessage()
				.contains("mode must be empty for an ORGANIZATION SBOM profile"));
	}

	@Test
	void thePerspectiveScopeNeedsARealNonProductPerspectiveOfTheOrg() throws RelizaException {
		UUID unknown = UUID.randomUUID();
		assertEquals("Perspective not found: " + unknown,
				refused(SettingsScope.PERSPECTIVE, unknown, "default", recipe()).getMessage());

		TeaPerspectiveRef product = new TeaPerspectiveRef(UUID.randomUUID(), "Platform", PerspectiveType.PRODUCT);
		when(perspectives.teaPerspectiveRef(product.uuid())).thenReturn(Optional.of(product));
		assertTrue(refused(SettingsScope.PERSPECTIVE, product.uuid(), "default", recipe()).getMessage()
				.contains("is a product"));

		TeaPerspectiveRef foreign = new TeaPerspectiveRef(UUID.randomUUID(), "Foreign", PerspectiveType.PERSPECTIVE);
		when(perspectives.teaPerspectiveRef(foreign.uuid())).thenReturn(Optional.of(foreign));
		when(perspectives.teaPerspectiveObject(foreign.uuid())).thenReturn(Optional.of(orgObject(UUID.randomUUID())));
		assertEquals("Perspective not found: " + foreign.uuid(),
				refused(SettingsScope.PERSPECTIVE, foreign.uuid(), "default", recipe()).getMessage());

		assertTrue(refused(SettingsScope.PERSPECTIVE, alpha.uuid(), "default", override()).getMessage()
				.contains("mode must be empty for a PERSPECTIVE SBOM profile"));
		SbomProfileData saved = save(SettingsScope.PERSPECTIVE, alpha.uuid(), "default", recipe());
		assertEquals(SettingsScope.PERSPECTIVE, saved.getScope());
		assertEquals(alpha.uuid(), saved.getObject());
	}

	@Test
	void thePerspectiveScopeIsAProFeature() {
		when(perspectives.teaPerspectivesSupported()).thenReturn(false);
		assertEquals("PERSPECTIVE SBOM profiles need perspectives, which are a ReARM Pro feature",
				refused(SettingsScope.PERSPECTIVE, alpha.uuid(), "default", recipe()).getMessage());
	}

	@Test
	void theComponentScopeNeedsAModeAndAFollowNeedsACandidate() throws RelizaException {
		assertTrue(refused(SettingsScope.COMPONENT, cd.getUuid(), "default", recipe()).getMessage()
				.contains("mode is required for a COMPONENT SBOM profile"));
		UUID foreignComponent = UUID.randomUUID();
		assertEquals("Component not found: " + foreignComponent,
				refused(SettingsScope.COMPONENT, foreignComponent, "default", override()).getMessage());
		UUID elsewhere = UUID.randomUUID();
		String m = refused(SettingsScope.COMPONENT, cd.getUuid(), "default", SbomProfileInputDto.builder()
				.mode(ScopedSettingsMode.FOLLOW_PERSPECTIVE).followedPerspective(elsewhere).build()).getMessage();
		assertTrue(m.contains(elsewhere.toString()) && m.contains("Alpha, Beta"), m);
		assertTrue(refused(SettingsScope.COMPONENT, cd.getUuid(), "default", SbomProfileInputDto.builder()
				.mode(ScopedSettingsMode.FOLLOW_PERSPECTIVE).build()).getMessage()
				.contains("followedPerspective is required"));
		assertTrue(refused(SettingsScope.COMPONENT, cd.getUuid(), "default", override().toBuilder()
				.followedPerspective(alpha.uuid()).build()).getMessage().contains("followedPerspective must be empty"));

		SbomProfileData follow = save(SettingsScope.COMPONENT, cd.getUuid(), "default", SbomProfileInputDto.builder()
				.mode(ScopedSettingsMode.FOLLOW_PERSPECTIVE).followedPerspective(beta.uuid()).build());
		assertEquals(ScopedSettingsMode.FOLLOW_PERSPECTIVE, follow.getMode());
		assertEquals(beta.uuid(), follow.getFollowedPerspective());
		for (var f : SbomProfileService.REQUIRED_FIELDS.entrySet()) {
			assertNull(f.getValue().apply(follow), f.getKey() + " is stored null on a FOLLOW row");
		}
	}

	static Stream<String> requiredFields() {
		return SbomProfileService.REQUIRED_FIELDS.keySet().stream();
	}

	@ParameterizedTest
	@MethodSource("requiredFields")
	void everyRecipeFieldIsRequiredOnANonFollowRow(String field) {
		@SuppressWarnings("unchecked")
		Map<String, Object> m = Utils.OM.convertValue(override(), Map.class);
		m.remove(field);
		SbomProfileInputDto in = Utils.OM.convertValue(m, SbomProfileInputDto.class);
		assertEquals(field + " is required", refused(SettingsScope.COMPONENT, cd.getUuid(), "default", in).getMessage());
	}

	@ParameterizedTest
	@MethodSource("requiredFields")
	void aFollowRowCarriesNoRecipeField(String field) {
		SbomProfileInputDto follow = override().toBuilder().mode(ScopedSettingsMode.FOLLOW_PERSPECTIVE)
				.followedPerspective(alpha.uuid()).build();
		@SuppressWarnings("unchecked")
		Map<String, Object> all = Utils.OM.convertValue(follow, Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> only = new java.util.LinkedHashMap<>(Map.of("mode", all.get("mode"),
				"followedPerspective", all.get("followedPerspective"), field, all.get(field)));
		SbomProfileInputDto in = Utils.OM.convertValue(only, SbomProfileInputDto.class);
		assertEquals(field + " must be empty when mode is FOLLOW_PERSPECTIVE",
				refused(SettingsScope.COMPONENT, cd.getUuid(), "default", in).getMessage());
		assertEquals("description must be empty when mode is FOLLOW_PERSPECTIVE",
				refused(SettingsScope.COMPONENT, cd.getUuid(), "default", SbomProfileInputDto.builder()
						.mode(ScopedSettingsMode.FOLLOW_PERSPECTIVE).followedPerspective(alpha.uuid()).description("x")
						.build()).getMessage());
	}

	@Test
	void theRecipeRules() throws RelizaException {
		assertTrue(refused(SettingsScope.ORGANIZATION, null, "default", recipe().toBuilder().sources(Set.of()).build())
				.getMessage().contains("sources must name at least one"));
		assertTrue(save(SettingsScope.ORGANIZATION, null, "default", recipe().toBuilder().excludedCoverage(Set.of())
				.build()).getExcludedCoverage().isEmpty(), "excludedCoverage may be empty");
		assertEquals("description must be at most 1024 characters", refused(SettingsScope.ORGANIZATION, null, "default",
				recipe().toBuilder().description("d".repeat(1025)).build()).getMessage());
		assertEquals("d".repeat(1024), save(SettingsScope.ORGANIZATION, null, "default",
				recipe().toBuilder().description("d".repeat(1024)).build()).getDescription());
	}

	@Test
	void supportMetadataIncludeNeedsTheOrgInjection() throws RelizaException {
		SbomProfileInputDto include = recipe().toBuilder().supportMetadata(SbomSupportMetadata.INCLUDE).build();
		injection(SupportInjectionSetting.DISABLED);
		assertTrue(refused(SettingsScope.ORGANIZATION, null, "default", include).getMessage().contains("supportInjection"));
		injection(SupportInjectionSetting.ENABLED);
		assertEquals(SbomSupportMetadata.INCLUDE, save(SettingsScope.ORGANIZATION, null, "default", include)
				.getSupportMetadata());
	}

	@Test
	void anUpdateBumpsTheRevisionAndWritesAnAuditRecordACreateDoesNot() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		SbomProfileData created = save(SettingsScope.COMPONENT, cd.getUuid(), "premarket", override());
		assertEquals(0, created.getRevision());
		verify(auditService, never()).createAndSaveAuditRecord(any(), any());
		SbomProfile entity = stored.get(0);
		assertEquals("premarket", entity.getName(), "the entity carries the name with the other key columns");
		assertEquals(SettingsScope.COMPONENT, entity.getScope());
		assertEquals(cd.getUuid(), entity.getObjectUuid());
		assertEquals(org, entity.getOrg());
		assertEquals(created.getUuid().toString(), entity.getRecordData().get("uuid"));
		assertEquals("premarket", entity.getRecordData().get("name"));

		SbomProfileData updated = save(SettingsScope.COMPONENT, cd.getUuid(), "premarket",
				override().toBuilder().dependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY).build());
		assertEquals(created.getUuid(), updated.getUuid());
		assertEquals(1, updated.getRevision());
		assertEquals(SbomDependencyDepth.TOP_LEVEL_ONLY, updated.getDependencyDepth());
		verify(auditService, times(1)).createAndSaveAuditRecord(eq(TableName.SBOM_PROFILE), any());
		assertEquals(1, stored.size());

		save(SettingsScope.COMPONENT, cd.getUuid(), "other", override());
		assertEquals(2, stored.size(), "another name is another row");
	}

	@Test
	void deletingAMissingRowAnswersFalse() throws RelizaException {
		assertFalse(service.deleteProfile(org, SettingsScope.ORGANIZATION, null, "nope", WU));
		assertFalse(service.deleteProfile(org, SettingsScope.COMPONENT, cd.getUuid(), "default", WU));
		verify(auditService, never()).createAndSaveAuditRecord(any(), any());
	}

	@Test
	void aDeleteWritesAnAuditRecord() throws RelizaException {
		// Security review F-6: every delete is audited, as TeaProfileService.deleteProfile does.
		SbomProfile row = componentRow("premarket", SbomDependencyDepth.FULL);
		assertTrue(service.deleteProfile(org, SettingsScope.COMPONENT, cd.getUuid(), "premarket", WU));
		verify(auditService, times(1)).createAndSaveAuditRecord(eq(TableName.SBOM_PROFILE), same(row));
		assertTrue(stored.isEmpty());
	}

	/** A consumer whose settings at (scope, object) name {@code name}. */
	private void namedBy(String name, SettingsScope scope, UUID object, String label) {
		consumers.add((o, n) -> o.equals(org) && n.equals(name) ? List.of(new ScopedSettingsRef(scope, object, label))
				: List.of());
	}

	@Test
	void theDeleteGuardRefusesWhenANamedProfileWouldNoLongerResolve() {
		componentRow("premarket", SbomDependencyDepth.FULL);
		namedBy("premarket", SettingsScope.COMPONENT, cd.getUuid(), "the TEA publishing settings of component widget");
		String m = assertThrows(RelizaException.class,
				() -> service.deleteProfile(org, SettingsScope.COMPONENT, cd.getUuid(), "premarket", WU)).getMessage();
		assertEquals("SBOM profile premarket of component widget cannot be deleted: the TEA publishing settings of "
				+ "component widget name it and it would no longer resolve there; point them at another profile first", m);
		assertEquals(1, stored.size());
		verify(auditService, never()).createAndSaveAuditRecord(any(), any());
	}

	@Test
	void theDeleteGuardRefusesWhenTheNameWouldBecomeAConflict() {
		// The component's own row settles two perspective rows; without it the name is a CONFLICT there.
		perspectiveRow(alpha, "premarket", SbomDependencyDepth.FULL);
		perspectiveRow(beta, "premarket", SbomDependencyDepth.TOP_LEVEL_ONLY);
		componentRow("premarket", SbomDependencyDepth.FULL);
		namedBy("premarket", SettingsScope.COMPONENT, cd.getUuid(), "the TEA publishing settings of component widget");
		assertTrue(assertThrows(RelizaException.class,
				() -> service.deleteProfile(org, SettingsScope.COMPONENT, cd.getUuid(), "premarket", WU)).getMessage()
				.contains("cannot be deleted"));
	}

	@Test
	void theDeleteGuardAllowsWhenAnotherDefinitionRemainsAndForDefault() throws RelizaException {
		orgRow("premarket", SbomDependencyDepth.FULL);
		componentRow("premarket", SbomDependencyDepth.TOP_LEVEL_ONLY);
		namedBy("premarket", SettingsScope.COMPONENT, cd.getUuid(), "the TEA publishing settings of component widget");
		assertTrue(service.deleteProfile(org, SettingsScope.COMPONENT, cd.getUuid(), "premarket", WU),
				"the organization's premarket still resolves at the component");

		orgRow("default", SbomDependencyDepth.TOP_LEVEL_ONLY);
		namedBy("default", SettingsScope.ORGANIZATION, null, "the TEA publishing settings of the organization");
		assertTrue(service.deleteProfile(org, SettingsScope.ORGANIZATION, null, "default", WU),
				"default falls back to the built-in");
	}

	@Test
	void theGuardOnlyAsksAboutTheNameItDeletes() throws RelizaException {
		orgRow("unnamed", SbomDependencyDepth.FULL);
		namedBy("premarket", SettingsScope.ORGANIZATION, null, "the TEA publishing settings of the organization");
		assertTrue(service.deleteProfile(org, SettingsScope.ORGANIZATION, null, "unnamed", WU));
	}

	@Test
	void deleteProfilesOfObjectRemovesEveryScopeAndNameOfTheObject() {
		perspectiveRow(alpha, "default", SbomDependencyDepth.FULL);
		perspectiveRow(alpha, "premarket", SbomDependencyDepth.FULL);
		SbomProfile keptB = perspectiveRow(beta, "default", SbomDependencyDepth.FULL);
		SbomProfile keptO = orgRow("default", SbomDependencyDepth.FULL);
		service.deleteProfilesOfObject(org, alpha.uuid(), WU);
		assertEquals(List.of(keptB, keptO), stored);
		verify(auditService, times(2)).createAndSaveAuditRecord(eq(TableName.SBOM_PROFILE), any());
	}

	@Test
	void theListsReadByScopeAndByOrganization() throws RelizaException {
		assumeFalse(LicensingConstants.isOssEdition(), CE_NAMES);
		save(SettingsScope.ORGANIZATION, null, "zeta", recipe());
		save(SettingsScope.ORGANIZATION, null, "alpha", recipe());
		save(SettingsScope.PERSPECTIVE, alpha.uuid(), "alpha", recipe());
		assertEquals(List.of("alpha", "zeta"), service.listProfilesOfScope(org, SettingsScope.ORGANIZATION, null)
				.stream().map(SbomProfileData::getName).toList());
		assertEquals(List.of("alpha"), service.listProfilesOfScope(org, SettingsScope.PERSPECTIVE, alpha.uuid())
				.stream().map(SbomProfileData::getName).toList());
		assertTrue(service.listProfilesOfScope(org, SettingsScope.PERSPECTIVE, null).isEmpty());
		assertEquals(3, service.listProfilesOfOrg(org).size());
		assertEquals(Optional.empty(), service.getProfile(org, SettingsScope.ORGANIZATION, null, "nope"));
	}
}
