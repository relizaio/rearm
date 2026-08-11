/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.reliza.model.AgentData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.service.AgentMonitoringService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.ModelOntologyService;
import io.reliza.service.UserService;

/**
 * Regression guard for the {@code List.of(ro)} NPE in
 * {@link AgentDataFetcher}'s read resolvers.
 *
 * <p>When the scoping uuid (agent / session / org) doesn't resolve, the
 * resolved {@code RelizaObject ro} is {@code null}. The pre-fix code fed
 * that null into {@code List.of(ro)}, which throws {@link NullPointerException}
 * <em>before</em> the authorization check ran — GraphQL then surfaced an
 * opaque {@code SERVICE_ERROR} for what is really an unauthorized /
 * not-found uuid. The fix swaps in the null-tolerant
 * {@code Collections.singletonList(ro)} so the authz call is actually
 * reached: {@code getMatchingOrg} maps a null-only collection to a null
 * org and the request is denied cleanly (an {@link AccessDeniedException},
 * mapped to PERMISSION_DENIED, not SERVICE_ERROR).
 *
 * <p>These tests pin both halves: (1) the authz call is reached with a
 * one-element, null-bearing collection rather than NPE-ing first, and
 * (2) a denial from the authz layer propagates as {@link AccessDeniedException}.
 */
class AgentDataFetcherAuthzNullSafetyTest {

	private AuthorizationService authorizationService;
	private UserService userService;
	private GetOrganizationService getOrganizationService;
	private AgentService agentService;
	private AgentSessionService agentSessionService;
	private ModelOntologyService modelOntologyService;
	private AgentMonitoringService agentMonitoringService;
	private AgentDataFetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		authorizationService = mock(AuthorizationService.class);
		userService = mock(UserService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		agentService = mock(AgentService.class);
		agentSessionService = mock(AgentSessionService.class);
		modelOntologyService = mock(ModelOntologyService.class);
		agentMonitoringService = mock(AgentMonitoringService.class);

		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class)))
				.thenReturn(Optional.of(mock(UserData.class)));

		fetcher = new AgentDataFetcher();
		inject("authorizationService", authorizationService);
		inject("userService", userService);
		inject("getOrganizationService", getOrganizationService);
		inject("agentService", agentService);
		inject("agentSessionService", agentSessionService);
		inject("modelOntologyService", modelOntologyService);
		inject("agentMonitoringService", agentMonitoringService);

		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = AgentDataFetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	/**
	 * Asserts the authz helper was invoked exactly once with a one-element
	 * collection whose single element is null — the null-safe shape the fix
	 * introduced. Under the pre-fix {@code List.of(ro)} this point was never
	 * reached because construction NPE'd first.
	 */
	private void assertReachedAuthzWithNullScope() throws Exception {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<RelizaObject>> rosCaptor =
				ArgumentCaptor.forClass(Collection.class);
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(
				any(), any(), any(), any(), rosCaptor.capture(), any());
		Collection<RelizaObject> ros = rosCaptor.getValue();
		assertEquals(1, ros.size(),
				"authz should receive a single-element scope collection");
		assertNull(ros.iterator().next(),
				"unresolved uuid must surface as a null scope element, not NPE on List.of(null)");
	}

	// ---------- entity-scoped resolvers (uuid → entity → entity.getOrg()) ----------

	@Test
	void getAgentUnresolvedUuidReachesAuthzWithoutNpe() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(agentService.getAgentData(uuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.getAgent(uuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void getAgentUnauthorizedUuidPropagatesAccessDenied() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(agentService.getAgentData(uuid)).thenReturn(Optional.empty());
		// Real AuthorizationService denies (null matching org) by throwing
		// AccessDeniedException — pin that the resolver lets it through as a
		// clean denial rather than swallowing it or NPE-ing first.
		doThrow(new AccessDeniedException("Forbidden"))
				.when(authorizationService).isUserAuthorizedForObjectGraphQL(
						any(), any(), any(), any(), any(), any());

		assertThrows(AccessDeniedException.class, () -> fetcher.getAgent(uuid));
	}

	@Test
	void getSessionUnresolvedUuidReachesAuthzWithoutNpe() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(agentSessionService.getSessionData(uuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.getSession(uuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void subAgentsOfUnresolvedRootReachesAuthzWithoutNpe() throws Exception {
		UUID rootUuid = UUID.randomUUID();
		when(agentService.getAgentData(rootUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.subAgentsOf(rootUuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void sessionsOfAgentUnresolvedRootReachesAuthzWithoutNpe() throws Exception {
		UUID rootUuid = UUID.randomUUID();
		when(agentService.getAgentData(rootUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.sessionsOfAgent(rootUuid, null));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void getModelOntologyUnresolvedUuidReachesAuthzWithoutNpe() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(modelOntologyService.getModelOntologyData(uuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.getModelOntology(uuid));
		assertReachedAuthzWithNullScope();
	}

	// ---------- org-scoped resolvers (orgUuid → org row) ----------

	@Test
	void agentsOfOrgUnresolvedOrgReachesAuthzWithoutNpe() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.agentsOfOrg(orgUuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void sessionsOfOrgUnresolvedOrgReachesAuthzWithoutNpe() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.sessionsOfOrg(orgUuid, null));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void modelOntologiesOfOrgUnresolvedOrgReachesAuthzWithoutNpe() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.modelOntologiesOfOrg(orgUuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void agentDashboardKpisUnresolvedOrgReachesAuthzWithoutNpe() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.agentDashboardKpis(orgUuid));
		assertReachedAuthzWithNullScope();
	}

	// ---------- happy-path sanity: a resolved entity still binds its real org ----------

	@Test
	void getAgentResolvedUuidBindsEntityOrg() throws Exception {
		UUID uuid = UUID.randomUUID();
		UUID orgUuid = UUID.randomUUID();
		AgentData ad = mock(AgentData.class);
		when(ad.getOrg()).thenReturn(orgUuid);
		when(agentService.getAgentData(uuid)).thenReturn(Optional.of(ad));

		AgentData result = fetcher.getAgent(uuid);
		assertEquals(ad, result);
		// Org bound from the resolved entity, and the scope collection now
		// carries the real (non-null) object.
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<RelizaObject>> rosCaptor =
				ArgumentCaptor.forClass(Collection.class);
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(
				any(), any(), any(), eq(orgUuid), rosCaptor.capture(), any());
		assertEquals(ad, rosCaptor.getValue().iterator().next());
	}
}
