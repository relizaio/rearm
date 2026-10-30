/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.ExternalApiKeyService.AccessChange;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-3, design 4.2: creating EXTERNAL keys, the restricted grant shape, the printed id lookup and the
 * exchange check. A baseline of one ORGANIZATION, one PERSPECTIVE, one COMPONENT and one PRODUCT grant; each
 * case changes one thing.
 */
class ExternalApiKeyServiceTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private final UUID org = UUID.randomUUID();
	private final UUID otherOrg = UUID.randomUUID();
	private final UUID teaOrg = UUID.randomUUID();
	private final UUID perspective = UUID.randomUUID();
	private final UUID component = UUID.randomUUID();
	private final UUID product = UUID.randomUUID();

	private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
	private final OrganizationService organizationService = mock(OrganizationService.class);
	private final OrganizationRepository organizationRepository = mock(OrganizationRepository.class);
	private final GetComponentService getComponentService = mock(GetComponentService.class);
	private final OssPerspectiveService ossPerspectiveService = mock(OssPerspectiveService.class);
	private final ExternalApiKeyService svc = new ExternalApiKeyService();

	ExternalApiKeyServiceTest() {
		ReflectionTestUtils.setField(svc, "apiKeyService", apiKeyService);
		ReflectionTestUtils.setField(svc, "organizationService", organizationService);
		ReflectionTestUtils.setField(svc, "organizationRepository", organizationRepository);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "ossPerspectiveService", ossPerspectiveService);
		when(getComponentService.getComponentData(any())).thenReturn(Optional.empty());
		when(getComponentService.getComponentData(component)).thenReturn(Optional.of(componentData(component, org, ComponentType.COMPONENT)));
		when(getComponentService.getComponentData(product)).thenReturn(Optional.of(componentData(product, org, ComponentType.PRODUCT)));
		when(ossPerspectiveService.teaPerspectivesSupported()).thenReturn(true);
		when(ossPerspectiveService.teaPerspectiveRef(perspective)).thenReturn(Optional.of(new TeaPerspectiveRef(perspective, "p", PerspectiveType.PERSPECTIVE)));
		RelizaObject owner = mock(RelizaObject.class);
		when(owner.getOrg()).thenReturn(org);
		when(ossPerspectiveService.teaPerspectiveObject(perspective)).thenReturn(Optional.of(owner));
	}

	private static ComponentData componentData(UUID uuid, UUID orgUuid, ComponentType type) {
		ComponentData cd = new ComponentData();
		cd.setUuid(uuid);
		cd.setOrg(orgUuid);
		cd.setType(type);
		return cd;
	}

	private PermissionDto grant(PermissionScope scope, UUID object, PermissionType type) {
		return new PermissionDto(null, scope, object, type, Set.of(), List.of());
	}

	private List<PermissionDto> baseline() {
		return new ArrayList<>(List.of(grant(PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY),
				grant(PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_ONLY),
				grant(PermissionScope.COMPONENT, component, PermissionType.READ_ONLY),
				grant(PermissionScope.COMPONENT, product, PermissionType.READ_ONLY)));
	}

	private ApiKey externalRow() {
		ApiKey ak = new ApiKey();
		ak.setObjectType(ApiTypeEnum.EXTERNAL);
		ak.setObjectUuid(org);
		ak.setOrg(org);
		ak.setKeyOrder(UUID.randomUUID().toString());
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(org);
		akd.setTeaOrg(teaOrg);
		akd.setSecrets(new LinkedList<>(List.of(new ApiKeySecret(1, "h1", true, ZonedDateTime.now(), null),
				new ApiKeySecret(2, "h2", true, ZonedDateTime.now(), null))));
		ak.setRecordData(Utils.dataToRecord(akd));
		return ak;
	}

	// ---- create (6) ----

	@Test
	void createMintsTheTeaIdFirstAndHandsItToTheRow() throws Exception {
		ApiKey created = externalRow();
		when(organizationService.ensureTeaUuid(org, WU)).thenReturn(teaOrg);
		when(apiKeyService.createExternalKeyRow(org, teaOrg, "Acme", "n", WU)).thenReturn(created);
		assertSame(created, svc.create(org, "  Acme ", "n", WU));
		InOrder order = inOrder(organizationService, apiKeyService);
		order.verify(organizationService).ensureTeaUuid(org, WU);
		order.verify(apiKeyService).createExternalKeyRow(org, teaOrg, "Acme", "n", WU);
	}

	@Test
	void aBlankOrTooLongHolderNameIsRefusedAndNothingIsMinted() throws Exception {
		assertThrows(RelizaException.class, () -> svc.create(org, "   ", null, WU));
		assertThrows(RelizaException.class, () -> svc.create(org, null, null, WU));
		assertThrows(RelizaException.class, () -> svc.create(org, "x".repeat(201), null, WU));
		verifyNoInteractions(organizationService);
		verify(apiKeyService, never()).createExternalKeyRow(any(), any(), any(), any(), any());
		svc.validHolderName("x".repeat(200)); // the bound itself is accepted
	}

	// ---- grants (7 to 11) ----

	@ParameterizedTest
	@EnumSource(value = PermissionScope.class, names = { "INSTANCE", "BOARD", "BRANCH", "RELEASE" })
	void scopesOutsideTheThreeAreRefusedNamingTheScope(PermissionScope scope) {
		List<PermissionDto> grants = baseline();
		grants.add(grant(scope, UUID.randomUUID(), PermissionType.READ_ONLY));
		RelizaException e = assertThrows(RelizaException.class, () -> svc.normalizeGrants(org, grants));
		assertTrue(e.getMessage().contains("scope " + scope), e.getMessage());
	}

	@ParameterizedTest
	@EnumSource(value = PermissionType.class, names = { "READ_WRITE", "ADMIN", "ESSENTIAL_READ" })
	void typesOtherThanReadOnlyAreRefused(PermissionType type) {
		List<PermissionDto> grants = baseline();
		grants.set(2, grant(PermissionScope.COMPONENT, component, type));
		RelizaException e = assertThrows(RelizaException.class, () -> svc.normalizeGrants(org, grants));
		assertTrue(e.getMessage().contains("READ_ONLY"), e.getMessage());
	}

	@Test
	void aNoneEntryIsDroppedAndAnotherOrgIsRefused() throws Exception {
		List<PermissionDto> grants = baseline();
		grants.set(2, grant(PermissionScope.COMPONENT, component, PermissionType.NONE));
		List<PermissionDto> out = svc.normalizeGrants(org, grants);
		assertEquals(3, out.size());
		assertTrue(out.stream().noneMatch(p -> component.equals(p.object())));
		List<PermissionDto> foreign = baseline();
		foreign.add(new PermissionDto(otherOrg, PermissionScope.ORGANIZATION, otherOrg, PermissionType.READ_ONLY, Set.of(), List.of()));
		RelizaException e = assertThrows(RelizaException.class, () -> svc.normalizeGrants(org, foreign));
		assertTrue(e.getMessage().contains("another organization"), e.getMessage());
	}

	@Test
	void functionsAreForcedToTeaReadApprovalsEmptiedAndTheOrgGrantLandsOnTheOrg() throws Exception {
		List<PermissionDto> grants = List.of(
				new PermissionDto(null, PermissionScope.ORGANIZATION, UUID.randomUUID(), PermissionType.READ_ONLY,
						Set.of(PermissionFunction.ARTIFACT_DOWNLOAD, PermissionFunction.AGENT), List.of("QA")),
				new PermissionDto(org, PermissionScope.COMPONENT, component, PermissionType.READ_ONLY,
						Set.of(PermissionFunction.ARTIFACT_DOWNLOAD), List.of("QA")));
		List<PermissionDto> out = svc.normalizeGrants(org, grants);
		assertEquals(2, out.size());
		for (PermissionDto p : out) {
			assertEquals(Set.of(PermissionFunction.TEA_READ), p.functions());
			assertTrue(p.approvals().isEmpty());
			assertEquals(PermissionType.READ_ONLY, p.type());
			assertEquals(org, p.org());
		}
		assertEquals(org, out.get(0).object(), "an ORGANIZATION grant is stored on the org uuid whatever was sent");
	}

	@Test
	void componentGrantsMustNameAComponentOfTheOrg() throws Exception {
		UUID unknown = UUID.randomUUID();
		assertTrue(assertThrows(RelizaException.class,
				() -> svc.normalizeGrants(org, List.of(grant(PermissionScope.COMPONENT, unknown, PermissionType.READ_ONLY))))
				.getMessage().contains("does not exist"));
		UUID foreign = UUID.randomUUID();
		when(getComponentService.getComponentData(foreign)).thenReturn(Optional.of(componentData(foreign, otherOrg, ComponentType.COMPONENT)));
		assertTrue(assertThrows(RelizaException.class,
				() -> svc.normalizeGrants(org, List.of(grant(PermissionScope.COMPONENT, foreign, PermissionType.READ_ONLY))))
				.getMessage().contains("another organization"));
		assertEquals(product, svc.normalizeGrants(org, List.of(grant(PermissionScope.COMPONENT, product, PermissionType.READ_ONLY)))
				.get(0).object(), "a PRODUCT is accepted as a component");
	}

	@Test
	void perspectiveGrantsNeedProAndALivePerspectiveOfTheOrg() throws Exception {
		List<PermissionDto> one = List.of(grant(PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_ONLY));
		assertEquals(1, svc.normalizeGrants(org, one).size(), "a PERSPECTIVE ref is accepted");

		UUID absent = UUID.randomUUID();
		when(ossPerspectiveService.teaPerspectiveRef(absent)).thenReturn(Optional.empty());
		assertTrue(assertThrows(RelizaException.class,
				() -> svc.normalizeGrants(org, List.of(grant(PermissionScope.PERSPECTIVE, absent, PermissionType.READ_ONLY))))
				.getMessage().contains("not found"));

		when(ossPerspectiveService.teaPerspectiveRef(product)).thenReturn(Optional.of(new TeaPerspectiveRef(product, "prod", PerspectiveType.PRODUCT)));
		assertTrue(assertThrows(RelizaException.class,
				() -> svc.normalizeGrants(org, List.of(grant(PermissionScope.PERSPECTIVE, product, PermissionType.READ_ONLY))))
				.getMessage().contains("grant the product as a component"));

		UUID foreign = UUID.randomUUID();
		when(ossPerspectiveService.teaPerspectiveRef(foreign)).thenReturn(Optional.of(new TeaPerspectiveRef(foreign, "f", PerspectiveType.PERSPECTIVE)));
		RelizaObject owner = mock(RelizaObject.class);
		when(owner.getOrg()).thenReturn(otherOrg);
		when(ossPerspectiveService.teaPerspectiveObject(foreign)).thenReturn(Optional.of(owner));
		assertTrue(assertThrows(RelizaException.class,
				() -> svc.normalizeGrants(org, List.of(grant(PermissionScope.PERSPECTIVE, foreign, PermissionType.READ_ONLY))))
				.getMessage().contains("another organization"));

		when(ossPerspectiveService.teaPerspectivesSupported()).thenReturn(false);
		assertTrue(assertThrows(RelizaException.class, () -> svc.normalizeGrants(org, one)).getMessage().contains("ReARM Pro"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void duplicatesCollapseAndTheSaveReplacesTheSetWholesale() throws Exception {
		ApiKey ak = externalRow();
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
		List<PermissionDto> grants = baseline();
		grants.add(grant(PermissionScope.COMPONENT, component, PermissionType.READ_ONLY));
		svc.setGrants(ak.getUuid(), grants, WU);
		ArgumentCaptor<List<PermissionDto>> saved = ArgumentCaptor.forClass(List.class);
		verify(apiKeyService).writeExternalGrants(eq(ak.getUuid()), saved.capture(), eq(WU));
		assertEquals(4, saved.getValue().size(), "two grants on one (scope, object) are stored once");
	}

	@Test
	void writingGrantsReplacesThePreviousSet() throws Exception {
		// through the real write: revokeAllOrgPermissions, then the new grants
		io.reliza.repositories.ApiKeyRepository repo = mock(io.reliza.repositories.ApiKeyRepository.class);
		ApiKeyService real = new ApiKeyService(repo);
		ReflectionTestUtils.setField(real, "auditService", mock(AuditService.class));
		ApiKey ak = externalRow();
		ApiKeyData before = ApiKeyData.dataFromRecord(ak);
		before.setPermission(org, PermissionScope.COMPONENT, product, PermissionType.READ_ONLY, Set.of(PermissionFunction.TEA_READ), List.of());
		ak.setRecordData(Utils.dataToRecord(before));
		when(repo.findByUUID(ak.getUuid())).thenReturn(Optional.of(ak));
		// the write reads the row locked for write (TEA-14 runtime fix 1, main's saveApiKey shape)
		when(repo.findByIdWriteLocked(ak.getUuid())).thenReturn(Optional.of(ak));
		ReflectionTestUtils.setField(real, "entityManager", mock(jakarta.persistence.EntityManager.class));
		when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
		real.writeExternalGrants(ak.getUuid(), svc.normalizeGrants(org, List.of(grant(PermissionScope.COMPONENT, component, PermissionType.READ_ONLY))), WU);
		ApiKeyData after = ApiKeyData.dataFromRecord(ak);
		var set = after.getPermissions(org).getOrgPermissionsAsSet(org);
		assertEquals(1, set.size());
		assertEquals(component, set.iterator().next().getObject());
		assertEquals(Set.of(PermissionFunction.TEA_READ), Set.copyOf(set.iterator().next().getFunctions()));
	}

	@Test
	void grantsAreForExternalKeysOnly() {
		ApiKey ak = externalRow();
		ak.setObjectType(ApiTypeEnum.FREEFORM);
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
		assertThrows(RelizaException.class, () -> svc.setGrants(ak.getUuid(), baseline(), WU));
	}

	// ---- the direction of a lifecycle change (60, design round 2, T-2) ----

	@ParameterizedTest
	@EnumSource(ApiKeyData.ApiKeyStatus.class)
	void onlyReactivationWidensAmongStatuses(ApiKeyData.ApiKeyStatus status) {
		assertEquals(status == ApiKeyData.ApiKeyStatus.ACTIVE ? AccessChange.WIDENS : AccessChange.NARROWS, ExternalApiKeyService.ofStatus(status));
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void reEnablingASecretWidensAndRetiringNarrows(boolean active) {
		assertEquals(active ? AccessChange.WIDENS : AccessChange.NARROWS, ExternalApiKeyService.ofSecretActive(active));
	}

	static Stream<Arguments> expiries() {
		ZonedDateTime d = ZonedDateTime.parse("2026-10-03T12:00:00Z");
		return Stream.of(
				Arguments.of(null, null, AccessChange.WIDENS),
				Arguments.of(null, d, AccessChange.NARROWS),
				Arguments.of(d, null, AccessChange.WIDENS),
				Arguments.of(d, d.plusHours(1), AccessChange.WIDENS),
				Arguments.of(d, d.minusHours(1), AccessChange.NARROWS),
				Arguments.of(d, d, AccessChange.NARROWS),
				// the same instant in another zone is equal, not later
				Arguments.of(d, d.withZoneSameInstant(ZoneId.of("Asia/Tokyo")), AccessChange.NARROWS));
	}

	@ParameterizedTest
	@MethodSource("expiries")
	void aClearedOrLaterExpiryWidensAndAFirstEqualOrEarlierOneNarrows(ZonedDateTime current, ZonedDateTime requested, AccessChange expected) {
		assertEquals(expected, ExternalApiKeyService.ofExpiry(current, requested));
	}

	// ---- findByPrintedId (12) ----

	@Test
	void thePrintedIdMapsTheTeaOrgBackToTheOrganization() {
		Organization o = new Organization();
		o.setUuid(org);
		when(organizationRepository.findByTeaUuid(teaOrg.toString())).thenReturn(Optional.of(o));
		when(organizationRepository.findByTeaUuid(org.toString())).thenReturn(Optional.empty());
		ApiKey ak = externalRow();
		when(apiKeyService.getApiKeyByObjUuidTypeOrder(org, ApiTypeEnum.EXTERNAL, ak.getKeyOrder(), org)).thenReturn(Optional.of(ak));
		assertEquals(Optional.of(ak), svc.findByPrintedId("EXTERNAL__" + teaOrg + "__ord__" + ak.getKeyOrder()));
		assertTrue(svc.findByPrintedId("EXTERNAL__" + org + "__ord__" + ak.getKeyOrder()).isEmpty(), "the org uuid is not a TEA id");
		assertTrue(svc.findByPrintedId("EXTERNAL__" + UUID.randomUUID() + "__ord__" + ak.getKeyOrder()).isEmpty(), "unknown TEA id");
		assertTrue(svc.findByPrintedId("FREEFORM__" + teaOrg + "__ord__" + ak.getKeyOrder()).isEmpty(), "another type");
		assertTrue(svc.findByPrintedId("EXTERNAL__" + teaOrg).isEmpty(), "no order");
		assertTrue(svc.findByPrintedId("EXTERNAL__" + teaOrg + "__ord__").isEmpty(), "empty order");
		assertTrue(svc.findByPrintedId("EXTERNAL__not-a-uuid__ord__x").isEmpty(), "malformed uuid");
		assertTrue(svc.findByPrintedId(null).isEmpty());
	}

	// ---- authenticate (13) ----

	private ApiKey wireLookup() {
		Organization o = new Organization();
		o.setUuid(org);
		when(organizationRepository.findByTeaUuid(teaOrg.toString())).thenReturn(Optional.of(o));
		ApiKey ak = externalRow();
		when(apiKeyService.getApiKeyByObjUuidTypeOrder(org, ApiTypeEnum.EXTERNAL, ak.getKeyOrder(), org)).thenReturn(Optional.of(ak));
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
		return ak;
	}

	@Test
	void anotherOrganizationsPathIsRefusedBeforeTheSecretIsChecked() {
		ApiKey ak = wireLookup();
		assertTrue(svc.authenticate(ApiKeyService.keyIdOf(ak), "s", otherOrg, "ip").isEmpty());
		verify(apiKeyService, never()).isMatchingApiKey(any());
	}

	@Test
	void aWrongSecretIsEmpty() {
		ApiKey ak = wireLookup();
		when(apiKeyService.isMatchingApiKey(any())).thenReturn(null);
		assertTrue(svc.authenticate(ApiKeyService.keyIdOf(ak), "s", org, "ip").isEmpty());
	}

	@Test
	void aMatchAnswersTheRowAndTheMatchedSlotsSecret() {
		ApiKey ak = wireLookup();
		doAnswer(i -> {
			AuthHeaderParse ahp = i.getArgument(0);
			assertEquals(ApiTypeEnum.EXTERNAL, ahp.getType());
			assertEquals(org, ahp.getOrgUuid());
			assertEquals(ak.getKeyOrder(), ahp.getKeyOrder());
			assertEquals("s", ahp.getApiKey());
			ahp.setMatchedSecretSlot(2);
			return ak.getUuid();
		}).when(apiKeyService).isMatchingApiKey(any());
		var m = svc.authenticate(ApiKeyService.keyIdOf(ak), "s", org, "ip").orElseThrow();
		assertEquals(ak.getUuid(), m.key().getUuid());
		assertEquals(2, m.secret().getSlot());
		assertEquals("h2", m.secret().getHash());
	}

	@Test
	void anIdThatIsNotExternalNeverReachesTheMatcher() {
		assertTrue(svc.authenticate("FREEFORM__" + org + "__ord__x", "s", org, "ip").isEmpty());
		verify(apiKeyService, never()).isMatchingApiKey(any());
		verify(organizationRepository, never()).findByTeaUuid(anyString());
	}
}
