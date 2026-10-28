/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.RelizaObject;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.repositories.TeaProfileRepository;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.ws.RelizaConfigProps;

/**
 * Task TEA-2, design 4.2: TEA profile writes, validation, upsert, deletes and discovery, all as an
 * organization admin (the TEA-9 gates are in TeaProfileExposureGateTest and
 * TeaProfileArchivedComponentTest).
 */
class TeaProfileServiceTest extends TeaProfileServiceTestBase {

	/** CE (rearm-saas#955): every SBOM profile name is stored as default, so Pro's naming cases do not apply. */
	private static final String CE_NAMES = "ReARM CE stores every SBOM profile as default";


	@Test
	void theOrganizationScopeTakesNoObjectAndNoMode() {
		assertTrue(refused(TeaProfileScope.ORGANIZATION, UUID.randomUUID(), full()).getMessage().contains("object"));
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, override()).getMessage().contains("mode"));
	}

	@Test
	void thePerspectiveScopeNeedsARealPerspectiveOfTheOrg() throws RelizaException {
		when(perspectives.teaPerspectivesSupported()).thenReturn(false);
		assertTrue(refused(TeaProfileScope.PERSPECTIVE, alpha.uuid(), full()).getMessage().contains("ReARM Pro"));
		when(perspectives.teaPerspectivesSupported()).thenReturn(true);

		UUID product = UUID.randomUUID();
		when(perspectives.teaPerspectiveRef(product))
				.thenReturn(Optional.of(new TeaPerspectiveRef(product, "Shop", PerspectiveType.PRODUCT)));
		assertTrue(refused(TeaProfileScope.PERSPECTIVE, product, full()).getMessage().contains("COMPONENT-scope profile"));

		UUID foreign = UUID.randomUUID();
		when(perspectives.teaPerspectiveRef(foreign))
				.thenReturn(Optional.of(new TeaPerspectiveRef(foreign, "Theirs", PerspectiveType.PERSPECTIVE)));
		when(perspectives.teaPerspectiveObject(foreign)).thenReturn(Optional.of(orgObject(UUID.randomUUID())));
		assertTrue(refused(TeaProfileScope.PERSPECTIVE, foreign, full()).getMessage().contains("not found"));

		TeaProfileData saved = service.saveProfile(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), full(), TeaCaller.ORG_ADMIN, WU);
		assertEquals(alpha.uuid(), saved.getObject());
		assertEquals(TeaProfileScope.PERSPECTIVE, saved.getScope());
		assertNull(saved.getMode());
	}

	@Test
	void aDeletedPerspectiveIsRefusedLikeAnUnknownOne() throws RelizaException {
		// deletePerspective archives: the hook answers no ref for it (OssPerspectiveServiceTeaTest), while the
		// perspective still reads as an object of this org for authorization. The save is refused all the same.
		UUID archived = UUID.randomUUID();
		when(perspectives.teaPerspectiveRef(archived)).thenReturn(Optional.empty());
		lenient().when(perspectives.teaPerspectiveObject(archived)).thenReturn(Optional.of(orgObject(org)));
		TeaProfileInputDto publicEnabled = full().toBuilder().publishing(TeaPublishing.ENABLED)
				.visibility(TeaVisibility.PUBLIC).build();
		assertEquals("Perspective not found: " + archived,
				refused(TeaProfileScope.PERSPECTIVE, archived, publicEnabled).getMessage());

		UUID unknown = UUID.randomUUID();
		assertEquals("Perspective not found: " + unknown, refused(TeaProfileScope.PERSPECTIVE, unknown, full()).getMessage());
		verify(repository, never()).save(any());
		verify(organizationService, never()).ensureTeaUuid(any(), any());
	}

	@Test
	void theComponentScopeNeedsAValidMode() throws RelizaException {
		UUID c = cd.getUuid();
		assertTrue(refused(TeaProfileScope.COMPONENT, c, full()).getMessage().contains("mode"));
		assertTrue(refused(TeaProfileScope.COMPONENT, c, override().toBuilder().followedPerspective(alpha.uuid()).build())
				.getMessage().contains("followedPerspective"));
		TeaProfileInputDto follow = TeaProfileInputDto.builder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE).build();
		assertTrue(refused(TeaProfileScope.COMPONENT, c, follow).getMessage().contains("followedPerspective"));

		UUID elsewhere = UUID.randomUUID();
		when(perspectives.teaPerspectiveRef(elsewhere))
				.thenReturn(Optional.of(new TeaPerspectiveRef(elsewhere, "Elsewhere", PerspectiveType.PERSPECTIVE)));
		String message = refused(TeaProfileScope.COMPONENT, c, follow.toBuilder().followedPerspective(elsewhere).build())
				.getMessage();
		// Named by its uuid only (task TEA-9 round 2, design 3.13): it may be another organization's perspective.
		assertTrue(message.contains(elsewhere.toString()), message);
		assertFalse(message.contains("Elsewhere"), message);
		assertTrue(message.contains("Alpha, Beta"), "lists the candidates by name: " + message);

		TeaProfileData saved = service.saveProfile(org, TeaProfileScope.COMPONENT, c,
				full().toBuilder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE).followedPerspective(beta.uuid())
						.publishing(TeaPublishing.ENABLED).profiles(null).build(), TeaCaller.ORG_ADMIN, WU);
		assertEquals(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, saved.getMode());
		assertEquals(beta.uuid(), saved.getFollowedPerspective());
		for (var f : TeaProfileService.REQUIRED_FIELDS.entrySet()) {
			assertNull(f.getValue().apply(saved), f.getKey() + " is stored null on a FOLLOW row");
		}
		assertNull(saved.getTeiDomain());
		verify(organizationService, never()).ensureTeaUuid(any(), any());
	}

	static Stream<String> requiredFields() {
		return TeaProfileService.REQUIRED_FIELDS.keySet().stream();
	}

	@ParameterizedTest
	@MethodSource("requiredFields")
	void everyProfileFieldIsRequiredOnAnOverride(String field) {
		assumeFalse(LicensingConstants.isOssEdition() && "profiles".equals(field), CE_NAMES);
		@SuppressWarnings("unchecked")
		Map<String, Object> m = Utils.OM.convertValue(override(), Map.class);
		m.remove(field);
		TeaProfileInputDto in = Utils.OM.convertValue(m, TeaProfileInputDto.class);
		assertEquals(field + " is required", refused(TeaProfileScope.COMPONENT, cd.getUuid(), in).getMessage());
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class, names = { "CANCELLED", "REJECTED", "PENDING", "DRAFT" })
	void aMinimumLifecycleBelowAssembledIsTooLow(ReleaseLifecycle rl) {
		String m = refused(TeaProfileScope.ORGANIZATION, null, full().toBuilder().minimumLifecycle(rl).build()).getMessage();
		assertTrue(m.contains("minimumLifecycle") && m.contains("too low"), m);
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class,
			names = { "END_OF_MARKETING", "END_OF_DISTRIBUTION", "END_OF_SUPPORT", "END_OF_LIFE" })
	void aPostShipmentMinimumLifecycleNamesGeneralAvailability(ReleaseLifecycle rl) {
		String m = refused(TeaProfileScope.ORGANIZATION, null, full().toBuilder().minimumLifecycle(rl).build()).getMessage();
		assertTrue(m.contains("use GENERAL_AVAILABILITY"), m);
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class, names = { "ASSEMBLED", "READY_TO_SHIP", "GENERAL_AVAILABILITY" })
	void theAcceptedMinimumLifecycles(ReleaseLifecycle rl) throws RelizaException {
		assertEquals(rl, service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				full().toBuilder().minimumLifecycle(rl).build(), TeaCaller.ORG_ADMIN, WU).getMinimumLifecycle());
	}

	@Test
	void theTeiDomainRules() throws RelizaException {
		TeaProfileInputDto uuidTei = full().toBuilder().tei(TeaTei.UUID).build();
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, uuidTei).getMessage().contains("teiDomain"));
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, uuidTei.toBuilder().teiDomain("Products.Example.com").build())
				.getMessage().contains("lowercase"));
		assertTrue(refused(TeaProfileScope.ORGANIZATION, null, uuidTei.toBuilder().teiDomain("localhost").build())
				.getMessage().contains("dot"));
		assertEquals("products.example.com", service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				uuidTei.toBuilder().teiDomain("products.example.com").build(), TeaCaller.ORG_ADMIN, WU).getTeiDomain());
		assertNull(service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				full().toBuilder().teiDomain("products.example.com").build(), TeaCaller.ORG_ADMIN, WU).getTeiDomain(),
				"tei DISABLED stores no domain");
	}

	@Test
	void theFirstEnabledSaveMintsTheOrgTeaId() throws RelizaException {
		service.saveProfile(org, TeaProfileScope.ORGANIZATION, null, full(), TeaCaller.ORG_ADMIN, WU);
		verify(organizationService, never()).ensureTeaUuid(any(), any());

		service.saveProfile(org, TeaProfileScope.ORGANIZATION, null,
				full().toBuilder().publishing(TeaPublishing.ENABLED).build(), TeaCaller.ORG_ADMIN, WU);
		verify(organizationService, times(1)).ensureTeaUuid(org, WU);

		od.setTeaUuid(UUID.randomUUID());
		service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(),
				override().toBuilder().publishing(TeaPublishing.ENABLED).build(), TeaCaller.ORG_ADMIN, WU);
		verify(organizationService, times(1)).ensureTeaUuid(any(), any());
	}

	@Test
	void aSecondSaveUpdatesTheSameRow() throws RelizaException {
		TeaProfileData first = service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), override(), TeaCaller.ORG_ADMIN, WU);
		assertEquals(0, first.getRevision());
		verify(auditService, never()).createAndSaveAuditRecord(any(), any());

		TeaProfileData second = service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(),
				override().toBuilder().visibility(TeaVisibility.PUBLIC).build(), TeaCaller.ORG_ADMIN, WU);
		assertEquals(first.getUuid(), second.getUuid());
		assertEquals(1, second.getRevision());
		assertEquals(TeaVisibility.PUBLIC, second.getVisibility());
		assertEquals(1, stored.size());
		verify(auditService, times(1)).createAndSaveAuditRecord(eq(TableName.TEA_PROFILE), any());
		assertEquals(cd.getUuid(), stored.get(0).getObjectUuid(), "the key columns follow the record");
		assertEquals(TeaProfileScope.COMPONENT, stored.get(0).getScope());
		assertEquals(org, stored.get(0).getOrg());
	}

	@Test
	void deletingRows() throws RelizaException {
		assertThrows(RelizaException.class, () -> service.deleteProfile(org, TeaProfileScope.ORGANIZATION, null, TeaCaller.ORG_ADMIN, WU));
		service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), override(), TeaCaller.ORG_ADMIN, WU);
		assertTrue(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.ORG_ADMIN, WU));
		assertTrue(stored.isEmpty());
		assertFalse(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.ORG_ADMIN, WU));
	}

	@Test
	void deleteProfilesOfObjectRemovesOnlyThatObjectsRows() throws RelizaException {
		service.saveProfile(org, TeaProfileScope.ORGANIZATION, null, full(), TeaCaller.ORG_ADMIN, WU);
		service.saveProfile(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), full(), TeaCaller.ORG_ADMIN, WU);
		service.saveProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), override(), TeaCaller.ORG_ADMIN, WU);
		service.deleteProfilesOfObject(org, alpha.uuid(), WU);
		assertEquals(2, stored.size());
		assertTrue(stored.stream().noneMatch(p -> alpha.uuid().equals(p.getObjectUuid())));
	}

	/** TEA-5 design 4.6 case 35: the count is the publication engine's, asked with the same arguments. */
	@Test
	void thePublishedCountDelegatesToThePublicationEngine() {
		TeaPublicationService publications = mock(TeaPublicationService.class);
		ReflectionTestUtils.setField(service, "teaPublicationService", publications);
		when(publications.countPublishedResolvingTo(org, TeaProfileScope.ORGANIZATION, null)).thenReturn(7);
		when(publications.countPublishedResolvingTo(org, TeaProfileScope.COMPONENT, cd.getUuid())).thenReturn(2);
		assertEquals(7, service.countPublishedReleasesResolvingTo(org, TeaProfileScope.ORGANIZATION, null));
		assertEquals(2, service.countPublishedReleasesResolvingTo(org, TeaProfileScope.COMPONENT, cd.getUuid()));
		verify(publications).countPublishedResolvingTo(org, TeaProfileScope.ORGANIZATION, null);
		verify(publications).countPublishedResolvingTo(org, TeaProfileScope.COMPONENT, cd.getUuid());
	}

	@Test
	void orgDiscovery() throws RelizaException {
		TeaProfileService.TeaOrgDiscovery before = service.orgDiscovery(org);
		assertNull(before.teaUuid());
		assertNull(before.apiBase());
		assertNull(before.wellKnownDocument());

		UUID tea = UUID.randomUUID();
		od.setTeaUuid(tea);
		TeaProfileService.TeaOrgDiscovery after = service.orgDiscovery(org);
		String base = "https://rearm.example.com/tea/" + tea;
		assertEquals(tea, after.teaUuid());
		assertEquals(base, after.apiBase());
		assertNotNull(after.wellKnownDocument());
		assertEquals(Utils.OM.readTree("{\"schemaVersion\":1,\"endpoints\":[{\"url\":\"" + base
				+ "\",\"versions\":[\"1.0.0\"],\"priority\":1}]}"), Utils.OM.readTree(after.wellKnownDocument()));
	}
}
