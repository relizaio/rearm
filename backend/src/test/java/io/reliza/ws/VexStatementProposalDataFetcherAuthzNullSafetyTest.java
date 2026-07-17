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

import io.reliza.model.AnalysisScope;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.VexStatementProposalData;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VexStatementProposalService;

/**
 * Regression guard for the {@code List.of(ro)} NPE in
 * {@link VexStatementProposalDataFetcher}'s authorization gate helpers
 * ({@code gateOrg}, {@code gateRelease}, {@code gateComponent}) — the same
 * latent bug fixed in {@code AgentDataFetcher} (see
 * {@link AgentDataFetcherAuthzNullSafetyTest}).
 *
 * <p>When the scoping entity (org / release / component-org) doesn't
 * resolve, the {@code RelizaObject} is {@code null}. The pre-fix code fed
 * that null into {@code List.of(ro)}, which throws {@link NullPointerException}
 * <em>before</em> the authorization check ran — surfacing an opaque
 * {@code SERVICE_ERROR} for an unauthorized / not-found uuid. The fix swaps
 * in {@code Collections.singletonList(ro)} so the authz call is reached and
 * the request is denied cleanly ({@link AccessDeniedException}).
 *
 * <p>The gates are private; they are exercised through the public resolvers
 * that route to each one. Driving them through an authz denial both proves
 * {@link AccessDeniedException} propagation and captures the null-bearing
 * scope (Mockito records the invocation before the stubbed throw), while
 * confirming construction did not NPE first.
 */
class VexStatementProposalDataFetcherAuthzNullSafetyTest {

	private AuthorizationService authorizationService;
	private VexStatementProposalService proposalService;
	private UserService userService;
	private GetOrganizationService getOrganizationService;
	private SharedReleaseService sharedReleaseService;
	private ArtifactService artifactService;
	private VexStatementProposalDataFetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		authorizationService = mock(AuthorizationService.class);
		proposalService = mock(VexStatementProposalService.class);
		userService = mock(UserService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		artifactService = mock(ArtifactService.class);

		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class)))
				.thenReturn(Optional.of(mock(UserData.class)));
		// Default the entity lookups to "not found" so each gate hits the
		// null-scope path; individual tests override where needed.
		when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.empty());
		when(sharedReleaseService.getReleaseData(any(), any())).thenReturn(Optional.empty());

		fetcher = new VexStatementProposalDataFetcher();
		inject("authorizationService", authorizationService);
		inject("proposalService", proposalService);
		inject("userService", userService);
		inject("getOrganizationService", getOrganizationService);
		inject("sharedReleaseService", sharedReleaseService);
		inject("artifactService", artifactService);

		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = VexStatementProposalDataFetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

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
				"unresolved entity must surface as a null scope element, not NPE on List.of(null)");
	}

	private void denyAuthz() throws Exception {
		doThrow(new AccessDeniedException("Forbidden"))
				.when(authorizationService).isUserAuthorizedForObjectGraphQL(
						any(), any(), any(), any(), any(), any());
	}

	// ---------- gateOrg (via getVexStatementProposals) ----------

	@Test
	void getVexStatementProposalsUnresolvedOrgDeniesWithNullScope() throws Exception {
		UUID org = UUID.randomUUID();
		denyAuthz();

		assertThrows(AccessDeniedException.class,
				() -> fetcher.getVexStatementProposals(org, null));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void getVexStatementProposalsUnresolvedOrgCompletesWithoutNpeWhenAuthorized() throws Exception {
		// authz mock is a no-op (authorized); the gate still constructs a
		// null-bearing scope collection — proves construction itself is
		// NPE-free, independent of the deny path.
		UUID org = UUID.randomUUID();
		when(proposalService.listForOrg(org)).thenReturn(List.of());

		assertDoesNotThrow(() -> fetcher.getVexStatementProposals(org, null));
		assertReachedAuthzWithNullScope();
	}

	// ---------- gateRelease (via getVexStatementProposalsByRelease) ----------

	@Test
	void getByReleaseUnresolvedReleaseDeniesWithNullScope() throws Exception {
		UUID org = UUID.randomUUID();
		UUID release = UUID.randomUUID();
		denyAuthz();

		assertThrows(AccessDeniedException.class,
				() -> fetcher.getByRelease(org, release));
		assertReachedAuthzWithNullScope();
	}

	// ---------- gateComponent (via getVexStatementProposal on a COMPONENT-scoped proposal) ----------

	@Test
	void getVexStatementProposalComponentScopedUnresolvedOrgDeniesWithNullScope() throws Exception {
		UUID uuid = UUID.randomUUID();
		UUID org = UUID.randomUUID();
		UUID componentUuid = UUID.randomUUID();
		VexStatementProposalData proposal = mock(VexStatementProposalData.class);
		when(proposal.getScope()).thenReturn(AnalysisScope.COMPONENT);
		when(proposal.getScopeUuid()).thenReturn(componentUuid);
		when(proposal.getOrg()).thenReturn(org);
		when(proposalService.getProposal(uuid)).thenReturn(Optional.of(proposal));
		denyAuthz();

		assertThrows(AccessDeniedException.class,
				() -> fetcher.getVexStatementProposal(uuid));
		assertReachedAuthzWithNullScope();
	}

	// ---------- happy-path sanity: a resolved org still binds its real org ----------

	@Test
	void getVexStatementProposalsResolvedOrgBindsOrg() throws Exception {
		UUID org = UUID.randomUUID();
		io.reliza.model.OrganizationData od = mock(io.reliza.model.OrganizationData.class);
		when(getOrganizationService.getOrganizationData(org)).thenReturn(Optional.of(od));
		when(proposalService.listForOrg(org)).thenReturn(List.of());

		assertDoesNotThrow(() -> fetcher.getVexStatementProposals(org, null));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<RelizaObject>> rosCaptor =
				ArgumentCaptor.forClass(Collection.class);
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(
				any(), any(), any(), eq(org), rosCaptor.capture(), any());
		assertEquals(od, rosCaptor.getValue().iterator().next());
	}
}
