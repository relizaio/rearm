/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Set;
import io.reliza.model.OrganizationData;
import io.reliza.model.SupportExportState;
import io.reliza.model.RelizaObject;
import io.reliza.model.SupportAttestationFilter;
import io.reliza.model.UserData;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * Regression guard for the {@code List.of(ro)} NPE in {@link SbomComponentDataFetcher}'s
 * release-scoped resolvers -- the same latent bug already fixed in {@code AgentDataFetcher},
 * {@code SignatureDataFetcher} and {@code VexStatementProposalDataFetcher}.
 *
 * <p>When the release uuid does not resolve, {@code ro} is null. {@code List.of(null)} throws
 * {@link NullPointerException} BEFORE the authorization check runs, so GraphQL returns an
 * opaque SERVICE_ERROR for what is really an unauthorized-or-absent uuid. That difference is
 * also an existence oracle: an existing-but-forbidden release answers PERMISSION_DENIED while
 * a nonexistent one answers SERVICE_ERROR, which tells an unauthorized caller which release
 * uuids are real.
 *
 * <p>Worth spelling out why the existing service-level test did not catch this. There IS a
 * test asserting an unknown release yields an empty page, and it passes -- but it calls the
 * SERVICE. The resolver in front of it NPE'd on the very input the test names, so the suite
 * read as covering a path that was broken one layer up. This class tests the resolver.
 */
class SbomComponentDataFetcherAuthzNullSafetyTest {

	private AuthorizationService authorizationService;
	private UserService userService;
	private SharedReleaseService sharedReleaseService;
	private SbomComponentService sbomComponentService;
	private GetOrganizationService getOrganizationService;
	private SbomComponentDataFetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		authorizationService = mock(AuthorizationService.class);
		userService = mock(UserService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		sbomComponentService = mock(SbomComponentService.class);

		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class)))
				.thenReturn(Optional.of(mock(UserData.class)));

		fetcher = new SbomComponentDataFetcher();
		inject("authorizationService", authorizationService);
		inject("userService", userService);
		inject("sharedReleaseService", sharedReleaseService);
		inject("sbomComponentService", sbomComponentService);
		getOrganizationService = mock(GetOrganizationService.class);
		inject("getOrganizationService", getOrganizationService);
		inject("getComponentService", mock(GetComponentService.class));
		inject("ossPerspectiveService", mock(OssPerspectiveService.class));

		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = SbomComponentDataFetcher.class.getDeclaredField(field);
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
		assertEquals(1, ros.size(), "authz should receive a single-element scope collection");
		assertNull(ros.iterator().next(),
				"unresolved release must surface as a null scope element, not NPE on List.of(null)");
	}

	private void denyAuthz() throws Exception {
		doThrow(new AccessDeniedException("Forbidden"))
				.when(authorizationService).isUserAuthorizedForObjectGraphQL(
						any(), any(), any(), any(), any(), any());
	}

	/**
	 * The coverage resolver builds an untyped Map consumed by the default property fetcher
	 * for NON-NULL schema fields. A renamed or dropped key makes the whole
	 * sbomComponentSupportCoverage query return null -- taking total and attested down with
	 * supportExportState -- and nothing else in the suite would notice, because the thing
	 * that was tested was a constant that cannot break while the wiring that can was not.
	 */
	@Test
	void coverageResolverEmitsEveryKeyTheNonNullSchemaFieldsRequire() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		OrganizationData od = mock(OrganizationData.class);
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.of(od));
		when(sbomComponentService.getSupportCoverage(orgUuid, null))
				.thenReturn(new SbomComponentService.SupportCoverage(12, 5));
		when(sbomComponentService.supportExportState(orgUuid))
				.thenReturn(SupportExportState.PARTIAL);

		Map<String, Object> dto = fetcher.sbomComponentSupportCoverage(orgUuid, null);
		assertEquals(Set.of("total", "attested", "supportExportState"), dto.keySet(),
				"every non-null field on SbomComponentSupportCoverage needs a key here");
		assertEquals(12, dto.get("total"));
		assertEquals(5, dto.get("attested"));
		// Emitted as the enum NAME: graphql-java coerces a String to an enum value, and a
		// bare enum instance would serialise by toString and drift if that were overridden.
		assertEquals("PARTIAL", dto.get("supportExportState"));
	}

	@Test
	void pagedQueryUnresolvedReleaseReachesAuthzWithoutNpe() throws Exception {
		UUID releaseUuid = UUID.randomUUID();
		when(sharedReleaseService.getReleaseData(releaseUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.getReleaseSbomComponentsPage(
				releaseUuid, SupportAttestationFilter.ALL, null, 50, null));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void pagedQueryDenialPropagatesAsAccessDeniedNotServiceError() throws Exception {
		UUID releaseUuid = UUID.randomUUID();
		when(sharedReleaseService.getReleaseData(releaseUuid)).thenReturn(Optional.empty());
		denyAuthz();

		assertThrows(AccessDeniedException.class, () -> fetcher.getReleaseSbomComponentsPage(
				releaseUuid, SupportAttestationFilter.ALL, null, 50, null),
				"a nonexistent release must deny like a forbidden one, or the difference"
						+ " between the two answers is a release-existence oracle");
	}

	@Test
	void unpagedQueryUnresolvedReleaseReachesAuthzWithoutNpe() throws Exception {
		UUID releaseUuid = UUID.randomUUID();
		when(sharedReleaseService.getReleaseData(releaseUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.getReleaseSbomComponents(releaseUuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void graphQueryUnresolvedReleaseReachesAuthzWithoutNpe() throws Exception {
		UUID releaseUuid = UUID.randomUUID();
		when(sharedReleaseService.getReleaseData(releaseUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.getReleaseSbomComponentGraph(releaseUuid, UUID.randomUUID()));
		assertReachedAuthzWithNullScope();
	}
}
