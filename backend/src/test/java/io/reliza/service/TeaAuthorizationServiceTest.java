/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.saas.PerspectiveData;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.TeaAuthorizationService.TeaAccess;
import io.reliza.service.TeaAuthorizationService.TeaAuthState;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * Task TEA-3, design 4.6: per-object TEA authorization over a real AuthorizationService walk, with the profile
 * resolver, the key store and the component and perspective lookups mocked.
 */
class TeaAuthorizationServiceTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID OTHER_ORG = UUID.randomUUID();
	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID SIBLING = UUID.randomUUID();
	private static final UUID PRODUCT = UUID.randomUUID();
	private static final UUID IN_PRODUCT = UUID.randomUUID();
	private static final UUID PERSPECTIVE = UUID.randomUUID();
	private static final UUID IN_PERSPECTIVE = UUID.randomUUID();

	private final TeaProfileResolver resolver = mock(TeaProfileResolver.class);
	private final ApiKeyService apiKeyService = mock(ApiKeyService.class);
	private final ApiKeyAccessService access = mock(ApiKeyAccessService.class);
	private final GetComponentService components = mock(GetComponentService.class);
	private final OssPerspectiveService perspectives = mock(OssPerspectiveService.class);
	private final AuthorizationService authorization = new AuthorizationService();
	private final TeaAuthorizationService svc = new TeaAuthorizationService();
	private final UUID keyUuid = UUID.randomUUID();

	TeaAuthorizationServiceTest() {
		ReflectionTestUtils.setField(authorization, "getComponentService", components);
		ReflectionTestUtils.setField(authorization, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(svc, "teaProfileResolver", resolver);
		ReflectionTestUtils.setField(svc, "apiKeyService", apiKeyService);
		ReflectionTestUtils.setField(svc, "apiKeyAccessService", access);
		ReflectionTestUtils.setField(svc, "authorizationService", authorization);
		for (UUID c : List.of(COMPONENT, SIBLING, IN_PRODUCT, IN_PERSPECTIVE)) {
			when(components.getComponentData(c)).thenReturn(Optional.of(cd(c, ORG, ComponentType.COMPONENT)));
		}
		when(components.getComponentData(PRODUCT)).thenReturn(Optional.of(cd(PRODUCT, ORG, ComponentType.PRODUCT)));
		when(components.listComponentsByProduct(PRODUCT)).thenReturn(List.of(cd(IN_PRODUCT, ORG, ComponentType.COMPONENT)));
		when(components.listComponentsByProduct(COMPONENT)).thenReturn(List.of());
		PerspectiveData pd = new PerspectiveData();
		pd.setUuid(PERSPECTIVE);
		pd.setOrg(ORG);
		when(perspectives.getPerspectiveData(PERSPECTIVE)).thenReturn(Optional.of(pd));
		when(components.listComponentsByPerspective(PERSPECTIVE)).thenReturn(List.of(cd(IN_PERSPECTIVE, ORG, ComponentType.COMPONENT)));
	}

	private static ComponentData cd(UUID uuid, UUID org, ComponentType type) {
		ComponentData cd = new ComponentData();
		cd.setUuid(uuid);
		cd.setOrg(org);
		cd.setType(type);
		return cd;
	}

	private static UserPermission grant(PermissionScope scope, UUID object, PermissionFunction... fns) {
		return UserPermission.permissionFactory(ORG, scope, object, PermissionType.READ_ONLY, List.of(fns), null);
	}

	private void key(ApiKeyStatus status, UserPermission... grants) {
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(ORG);
		akd.setUuid(keyUuid);
		akd.setStatus(status);
		for (UserPermission g : grants) {
			akd.setPermission(ORG, g.getScope(), g.getObject(), g.getType(), g.getFunctions(), List.of());
		}
		// round trip through the record, as the store does
		ApiKeyData stored = Utils.OM.convertValue(Utils.dataToRecord(akd), ApiKeyData.class);
		stored.setUuid(keyUuid);
		stored.setOrg(ORG);
		when(apiKeyService.getApiKeyData(keyUuid)).thenReturn(Optional.of(stored));
	}

	private TeaPrincipal principal() {
		return new TeaPrincipal(keyUuid, ORG, "EXTERNAL__t__ord__o", 1, "192.0.2.9");
	}

	private static TeaResolvedProfile resolved(TeaPublishing publishing, TeaVisibility visibility) {
		TeaProfileData p = TeaProfileData.builtInDefault(ORG);
		p.setPublishing(publishing);
		p.setVisibility(visibility);
		return new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.ORGANIZATION, ORG, p, List.of());
	}

	// ---- 27: the access matrix ----

	enum Caller { ANONYMOUS, INVALID_TOKEN, COVERED, NOT_COVERED }
	enum Obj { UNKNOWN, OTHER_ORG, CONFLICT, DISABLED, PUBLIC, PRIVATE }

	static Stream<Arguments> matrix() {
		List<Arguments> out = new ArrayList<>();
		for (Obj o : Obj.values()) {
			for (Caller c : Caller.values()) {
				TeaAccess expected = switch (o) {
					case UNKNOWN, OTHER_ORG, CONFLICT, DISABLED -> TeaAccess.CONCEAL;
					case PUBLIC -> TeaAccess.SERVE;
					case PRIVATE -> switch (c) {
						case ANONYMOUS -> TeaAccess.UNAUTHENTICATED;
						case INVALID_TOKEN -> TeaAccess.INVALID_TOKEN;
						case COVERED -> TeaAccess.SERVE;
						case NOT_COVERED -> TeaAccess.CONCEAL;
					};
				};
				out.add(Arguments.of(c, o, expected));
			}
		}
		return out.stream();
	}

	@ParameterizedTest(name = "{0} on {1} -> {2}")
	@MethodSource("matrix")
	void theAccessMatrix(Caller caller, Obj obj, TeaAccess expected) {
		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.COMPONENT, COMPONENT, PermissionFunction.TEA_READ));
		TeaCaller c = switch (caller) {
			case ANONYMOUS -> TeaCaller.anonymous();
			case INVALID_TOKEN -> TeaCaller.invalidToken("invalid or expired");
			case COVERED, NOT_COVERED -> TeaCaller.authenticated(principal());
		};
		UUID target = caller == Caller.NOT_COVERED ? SIBLING : COMPONENT;
		ComponentData data = switch (obj) {
			case UNKNOWN -> null;
			case OTHER_ORG -> cd(target, OTHER_ORG, ComponentType.COMPONENT);
			default -> cd(target, ORG, ComponentType.COMPONENT);
		};
		TeaResolvedProfile r = switch (obj) {
			case CONFLICT -> new TeaResolvedProfile(TeaResolutionStatus.CONFLICT, null, null, null, List.of());
			case DISABLED -> resolved(TeaPublishing.DISABLED, TeaVisibility.PUBLIC);
			case PUBLIC -> resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC);
			default -> resolved(TeaPublishing.ENABLED, TeaVisibility.PRIVATE);
		};
		if (data != null) when(resolver.resolveForComponent(data)).thenReturn(r);
		assertEquals(expected, svc.accessToComponent(c, ORG, data));
	}

	@Test
	void theFourArgumentFormUsesTheGivenResolution() {
		ComponentData data = cd(COMPONENT, ORG, ComponentType.COMPONENT);
		assertEquals(TeaAccess.SERVE, svc.accessToComponent(TeaCaller.anonymous(), ORG, data, resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC)));
		verify(resolver, never()).resolveForComponent(any());
		assertEquals(TeaAccess.CONCEAL, svc.accessToComponent(TeaCaller.anonymous(), ORG, cd(COMPONENT, OTHER_ORG, ComponentType.COMPONENT),
				resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC)), "another organization's component is concealed under a given resolution too");
		assertEquals(TeaAccess.CONCEAL, svc.accessToComponent(TeaCaller.anonymous(), ORG, null, resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC)));
	}

	// ---- 28: covers ----

	@Test
	void coversFollowsTheExistingWalk() {
		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.ORGANIZATION, ORG, PermissionFunction.TEA_READ));
		assertTrue(svc.covers(principal(), ORG, SIBLING), "an ORGANIZATION grant covers any component");

		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.COMPONENT, COMPONENT, PermissionFunction.TEA_READ));
		assertTrue(svc.covers(principal(), ORG, COMPONENT));
		assertFalse(svc.covers(principal(), ORG, SIBLING), "a COMPONENT grant does not cover a sibling");

		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.COMPONENT, PRODUCT, PermissionFunction.TEA_READ));
		assertTrue(svc.covers(principal(), ORG, PRODUCT));
		assertTrue(svc.covers(principal(), ORG, IN_PRODUCT), "a PRODUCT grant covers its tree");
		assertFalse(svc.covers(principal(), ORG, COMPONENT));

		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.PERSPECTIVE, PERSPECTIVE, PermissionFunction.TEA_READ));
		assertTrue(svc.covers(principal(), ORG, IN_PERSPECTIVE), "a PERSPECTIVE grant covers its components");
		assertFalse(svc.covers(principal(), ORG, COMPONENT));
		when(perspectives.getPerspectiveData(PERSPECTIVE)).thenReturn(Optional.empty()); // the CE hook: no perspectives
		assertFalse(svc.covers(principal(), ORG, IN_PERSPECTIVE), "with the hook empty a PERSPECTIVE grant covers nothing");

		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.ORGANIZATION, ORG, PermissionFunction.ARTIFACT_DOWNLOAD));
		assertFalse(svc.covers(principal(), ORG, COMPONENT), "a grant without TEA_READ covers nothing");

		key(ApiKeyStatus.INACTIVE, grant(PermissionScope.ORGANIZATION, ORG, PermissionFunction.TEA_READ));
		assertFalse(svc.covers(principal(), ORG, COMPONENT), "an inactive key covers nothing");

		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.ORGANIZATION, ORG, PermissionFunction.TEA_READ));
		assertFalse(svc.covers(principal(), OTHER_ORG, COMPONENT), "a key never covers another organization");
		TeaPrincipal mismatched = new TeaPrincipal(keyUuid, OTHER_ORG, "EXTERNAL__t__ord__o", 1, "192.0.2.9");
		assertFalse(svc.covers(mismatched, ORG, COMPONENT), "a principal of another organization covers nothing here");
	}

	// ---- 29: coveredComponentUuids ----

	@Test
	void theListingSide() {
		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.ORGANIZATION, ORG, PermissionFunction.TEA_READ));
		assertNull(svc.coveredComponentUuids(principal(), ORG), "an ORGANIZATION grant: no filter");
		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.COMPONENT, COMPONENT, PermissionFunction.TEA_READ),
				grant(PermissionScope.PERSPECTIVE, PERSPECTIVE, PermissionFunction.TEA_READ));
		assertEquals(Set.of(COMPONENT, IN_PERSPECTIVE), svc.coveredComponentUuids(principal(), ORG));
		key(ApiKeyStatus.ACTIVE);
		assertEquals(Set.of(), svc.coveredComponentUuids(principal(), ORG));
		key(ApiKeyStatus.INACTIVE, grant(PermissionScope.ORGANIZATION, ORG, PermissionFunction.TEA_READ));
		assertEquals(Set.of(), svc.coveredComponentUuids(principal(), ORG), "an inactive key lists nothing");
		// the RESOURCE-based reader of the same walk is unchanged
		assertNull(authorization.readableComponentUuids(Set.of(grant(PermissionScope.ORGANIZATION, ORG)), ORG));
		assertEquals(Set.of(COMPONENT), authorization.readableComponentUuids(Set.of(grant(PermissionScope.COMPONENT, COMPONENT)), ORG));
		assertEquals(Set.of(), authorization.coveredComponentUuids(Set.of(grant(PermissionScope.COMPONENT, COMPONENT)), ORG,
				PermissionFunction.TEA_READ), "a grant without TEA_READ lists nothing for TEA");
	}

	// ---- TEA-8 merged in: a PERSPECTIVE grant counts only on a live perspective of the organization ----

	@Test
	void aGrantOnADeletedOrForeignPerspectiveCoversNothing() {
		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.COMPONENT, COMPONENT, PermissionFunction.TEA_READ),
				grant(PermissionScope.PERSPECTIVE, PERSPECTIVE, PermissionFunction.TEA_READ));
		assertTrue(svc.covers(principal(), ORG, IN_PERSPECTIVE), "control: the live perspective covers its member");
		for (boolean deleted : List.of(true, false)) {
			PerspectiveData dead = new PerspectiveData();
			dead.setUuid(PERSPECTIVE);
			dead.setOrg(deleted ? ORG : OTHER_ORG);
			dead.setStatus(deleted ? StatusEnum.ARCHIVED : StatusEnum.ACTIVE);
			when(perspectives.getPerspectiveData(PERSPECTIVE)).thenReturn(Optional.of(dead));
			String what = deleted ? "a deleted perspective" : "a perspective of another organization";
			assertFalse(svc.covers(principal(), ORG, IN_PERSPECTIVE), what + " covers no member, though it still lists one");
			assertEquals(Set.of(COMPONENT), svc.coveredComponentUuids(principal(), ORG), what + " adds nothing to the listing");
		}
	}

	// ---- 30: deny ----

	@Test
	void theDenyResponses() throws Exception {
		assertNull(svc.deny(TeaAccess.SERVE));
		ResponseEntity<TeaErrorResponse> un = svc.deny(TeaAccess.UNAUTHENTICATED);
		assertEquals(401, un.getStatusCode().value());
		assertEquals("Bearer realm=\"tea\"", un.getHeaders().getFirst("WWW-Authenticate"));
		assertEquals("no-store", un.getHeaders().getFirst("Cache-Control"));
		assertNull(un.getBody());
		ResponseEntity<TeaErrorResponse> inv = svc.deny(TeaAccess.INVALID_TOKEN);
		assertEquals(401, inv.getStatusCode().value());
		assertEquals("Bearer realm=\"tea\", error=\"invalid_token\", error_description=\"The access token is invalid, expired or revoked\"",
				inv.getHeaders().getFirst("WWW-Authenticate"));
		assertEquals("no-store", inv.getHeaders().getFirst("Cache-Control"));
		assertNull(inv.getBody());
		ResponseEntity<TeaErrorResponse> hide = svc.deny(TeaAccess.CONCEAL);
		assertEquals(404, hide.getStatusCode().value());
		assertEquals("no-store", hide.getHeaders().getFirst("Cache-Control"));
		assertNull(hide.getHeaders().getFirst("WWW-Authenticate"));
		assertEquals(TeaUnknownErrorType.OBJECT_UNKNOWN, hide.getBody().getError());
		// TEA-10 decision 7: the body presets its content type, so no Accept of a download can renegotiate it
		assertEquals(org.springframework.http.MediaType.APPLICATION_JSON, hide.getHeaders().getContentType());
		assertNull(un.getHeaders().getContentType());
		assertNull(inv.getHeaders().getContentType());
		// the TEA surface writes its bodies with the TEA mapper (task TEA-6): the 1.0.0 message field is left out when null
		assertEquals(Map.of("error", "OBJECT_UNKNOWN"),
				Utils.OM.readValue(io.reliza.ws.tea.TeaJson.MAPPER.writeValueAsString(hide.getBody()), Map.class));
	}

	// ---- 31: access recording ----

	@Test
	void anAuthenticatedServeIsRecordedOnceAndNothingElseIs() {
		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.COMPONENT, COMPONENT, PermissionFunction.TEA_READ));
		ComponentData data = cd(COMPONENT, ORG, ComponentType.COMPONENT);
		ComponentData sibling = cd(SIBLING, ORG, ComponentType.COMPONENT);
		TeaCaller auth = TeaCaller.authenticated(principal());
		svc.accessToComponent(auth, ORG, data, resolved(TeaPublishing.ENABLED, TeaVisibility.PRIVATE));
		verify(access, times(1)).recordApiKeyAccess(keyUuid, "192.0.2.9", ORG, "EXTERNAL__t__ord__o");
		reset(access);
		svc.accessToComponent(auth, ORG, sibling, resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC));
		verify(access, times(1)).recordApiKeyAccess(keyUuid, "192.0.2.9", ORG, "EXTERNAL__t__ord__o");
		reset(access);
		svc.accessToComponent(TeaCaller.anonymous(), ORG, data, resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC));
		svc.accessToComponent(auth, ORG, sibling, resolved(TeaPublishing.ENABLED, TeaVisibility.PRIVATE)); // CONCEAL
		svc.accessToComponent(auth, ORG, data, resolved(TeaPublishing.DISABLED, TeaVisibility.PUBLIC)); // CONCEAL
		verify(access, never()).recordApiKeyAccess(any(), any(), any(), any());
	}

	// ---- 31a-31c (task TEA-18): a valid token of another organization is no credential here ----

	private TeaCaller foreign() {
		return TeaCaller.authenticated(new TeaPrincipal(keyUuid, OTHER_ORG, "EXTERNAL__t__ord__o", 1, "192.0.2.9"));
	}

	@Test
	void aForeignKeyIsAnonymousOnThisOrganizationsSurface() {
		key(ApiKeyStatus.ACTIVE, grant(PermissionScope.ORGANIZATION, ORG, PermissionFunction.TEA_READ));
		ComponentData data = cd(COMPONENT, ORG, ComponentType.COMPONENT);
		assertEquals(TeaAccess.SERVE, svc.accessToComponent(foreign(), ORG, data, resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC)));
		verify(access, never()).recordApiKeyAccess(any(), any(), any(), any());
		assertEquals(TeaAccess.UNAUTHENTICATED, svc.accessToComponent(foreign(), ORG, data, resolved(TeaPublishing.ENABLED, TeaVisibility.PRIVATE)),
				"a foreign key on PRIVATE is refused as anonymous, not concealed");
		verify(access, never()).recordApiKeyAccess(any(), any(), any(), any());
		verify(apiKeyService, never()).getApiKeyData(any());
		// control: a key of this organization is recorded with this organization
		assertEquals(TeaAccess.SERVE, svc.accessToComponent(TeaCaller.authenticated(principal()), ORG, data,
				resolved(TeaPublishing.ENABLED, TeaVisibility.PUBLIC)));
		verify(access, times(1)).recordApiKeyAccess(keyUuid, "192.0.2.9", ORG, "EXTERNAL__t__ord__o");
	}

	@Test
	void callerOnDemotesAForeignPrincipalOnly() {
		assertEquals(TeaCaller.anonymous(), TeaAuthorizationService.callerOn(null, ORG));
		assertEquals(TeaCaller.anonymous(), TeaAuthorizationService.callerOn(TeaCaller.anonymous(), ORG));
		TeaCaller invalid = TeaCaller.invalidToken("x");
		TeaCaller passed = TeaAuthorizationService.callerOn(invalid, ORG);
		assertSame(invalid, passed, "an invalid token passes through unchanged");
		assertEquals(TeaAuthState.INVALID_TOKEN, passed.state());
		assertEquals("x", passed.reason());
		TeaCaller own = TeaCaller.authenticated(principal());
		assertSame(own, TeaAuthorizationService.callerOn(own, ORG), "a key of this organization is kept");
		TeaPrincipal mismatched = new TeaPrincipal(keyUuid, OTHER_ORG, "EXTERNAL__t__ord__o", 1, "192.0.2.9");
		assertEquals(TeaCaller.anonymous(), TeaAuthorizationService.callerOn(TeaCaller.authenticated(mismatched), ORG));
		assertEquals(TeaCaller.anonymous(), TeaAuthorizationService.callerOn(TeaCaller.authenticated(principal()), null));
		assertEquals(TeaCaller.anonymous(), TeaAuthorizationService.callerOn(TeaCaller.authenticated(null), ORG));
	}

	@Test
	void denialOfTheDemotedCallerIsTheAnonymousChallenge() {
		ComponentData data = cd(COMPONENT, ORG, ComponentType.COMPONENT);
		ResponseEntity<TeaErrorResponse> demoted = TeaAuthorizationService.denial(
				svc.accessToComponent(foreign(), ORG, data, resolved(TeaPublishing.ENABLED, TeaVisibility.PRIVATE)));
		ResponseEntity<TeaErrorResponse> anonymous = TeaAuthorizationService.denial(
				svc.accessToComponent(TeaCaller.anonymous(), ORG, data, resolved(TeaPublishing.ENABLED, TeaVisibility.PRIVATE)));
		assertEquals(401, demoted.getStatusCode().value());
		assertEquals("Bearer realm=\"tea\"", demoted.getHeaders().getFirst("WWW-Authenticate"));
		assertEquals("no-store", demoted.getHeaders().getFirst("Cache-Control"));
		assertNull(demoted.getBody());
		assertEquals(anonymous.getStatusCode(), demoted.getStatusCode());
		assertEquals(anonymous.getHeaders(), demoted.getHeaders());
		assertEquals(anonymous.getBody(), demoted.getBody());
	}

	// ---- 32: callerOf ----

	@Test
	void callerOfReadsTheFiltersAttribute() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		assertEquals(TeaCaller.anonymous(), svc.callerOf(req));
		TeaCaller c = TeaCaller.authenticated(principal());
		req.setAttribute(TeaAuthorizationService.CALLER_ATTRIBUTE, c);
		assertEquals(c, svc.callerOf(req));
		assertEquals(TeaCaller.anonymous(), svc.callerOf(null));
	}
}
