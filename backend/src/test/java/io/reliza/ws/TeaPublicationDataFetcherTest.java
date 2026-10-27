/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.LicenseStatus;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.TeaPublicationService;
import io.reliza.service.TeaPublicationService.TeaConcealReason;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishPreview;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.UserService;

/**
 * Task TEA-5, design 4.5 cases 32 to 34: the TEA publication fetchers authorize before the service
 * runs (PUBLISH_EXTERNALLY at WRITE on the release for the acts, RESOURCE at READ for the reads),
 * the service's refusal reaches the client as its message, the programmatic twins take RBAC keys
 * only, and the field resolvers compute exposure and the public URL per row. TEA-25: the
 * programmatic twins verify the key and the instance state before the release is read, and read
 * it in the key's organisation.
 */
class TeaPublicationDataFetcherTest {

	private UserService userService;
	private AuthorizationService authorizationService;
	private SharedReleaseService sharedReleaseService;
	private TeaPublicationService teaPublicationService;
	private TeaPublicationDataFetcher fetcher;

	private final UUID org = UUID.randomUUID();
	private UserData ud;
	private ReleaseData rd;

	@BeforeEach
	void wire() {
		userService = mock(UserService.class);
		authorizationService = mock(AuthorizationService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		teaPublicationService = mock(TeaPublicationService.class);
		fetcher = new TeaPublicationDataFetcher();
		ReflectionTestUtils.setField(fetcher, "userService", userService);
		ReflectionTestUtils.setField(fetcher, "authorizationService", authorizationService);
		ReflectionTestUtils.setField(fetcher, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(fetcher, "teaPublicationService", teaPublicationService);

		ud = mock(UserData.class);
		UUID userUuid = UUID.randomUUID();
		when(ud.getUuid()).thenReturn(userUuid);
		JwtAuthenticationToken auth = mock(JwtAuthenticationToken.class);
		SecurityContextHolder.getContext().setAuthentication(auth);
		when(userService.getUserDataByAuth(auth)).thenReturn(Optional.of(ud));

		rd = release(org);
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private ReleaseData release(UUID releaseOrg) {
		ReleaseData r = new ReleaseData();
		ReflectionTestUtils.setField(r, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(r, "org", releaseOrg);
		ReflectionTestUtils.setField(r, "component", UUID.randomUUID());
		r.setVersion("1.0.0");
		when(sharedReleaseService.getReleaseData(r.getUuid())).thenReturn(Optional.of(r));
		when(sharedReleaseService.getReleaseData(r.getUuid(), releaseOrg)).thenReturn(Optional.of(r));
		return r;
	}

	private static TeaPublicationData publication(ReleaseData of, ComponentType type) {
		TeaPublicationData p = new TeaPublicationData();
		p.setUuid(UUID.randomUUID());
		p.setOrg(of.getOrg());
		p.setRelease(of.getUuid());
		p.setComponent(of.getComponent());
		p.setComponentType(type);
		p.setState(TeaPublicationState.PUBLISHED);
		p.setLatestVersion(1);
		return p;
	}

	private TeaPublishResult result() {
		return new TeaPublishResult(publication(rd, ComponentType.COMPONENT), null, TeaPublishOutcome.PUBLISHED, List.of(),
				List.of());
	}

	private void verifyUserPublishCheck() throws RelizaException {
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.PUBLISH_EXTERNALLY,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.WRITE);
	}

	// ------------------------------------------------------------------ case 32: the user surface

	@Test
	void publishChecksPublishExternallyOnTheReleaseBeforeTheService() throws Exception {
		TeaPublishResult r = result();
		when(teaPublicationService.publish(any())).thenReturn(r);

		assertSame(r, fetcher.publishReleaseOnTea(rd.getUuid(), "first"));

		InOrder order = inOrder(authorizationService, teaPublicationService);
		order.verify(authorizationService).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.PUBLISH_EXTERNALLY,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.WRITE);
		ArgumentCaptor<TeaPublishRequest> req = ArgumentCaptor.forClass(TeaPublishRequest.class);
		order.verify(teaPublicationService).publish(req.capture());
		assertEquals(rd.getUuid(), req.getValue().release());
		assertEquals(TeaPublishMode.PUBLISH, req.getValue().mode());
		assertEquals("first", req.getValue().comment());
		assertEquals(ud.getUuid(), req.getValue().wu().getLastUpdatedBy());
	}

	@Test
	void republishChecksTheSameAndAsksForARepublish() throws Exception {
		when(teaPublicationService.publish(any())).thenReturn(result());

		fetcher.republishReleaseOnTea(rd.getUuid(), null);

		verifyUserPublishCheck();
		ArgumentCaptor<TeaPublishRequest> req = ArgumentCaptor.forClass(TeaPublishRequest.class);
		verify(teaPublicationService).publish(req.capture());
		assertEquals(TeaPublishMode.REPUBLISH, req.getValue().mode());
	}

	@Test
	void hideChecksTheSameBeforeTheService() throws Exception {
		TeaPublicationData hidden = publication(rd, ComponentType.COMPONENT);
		hidden.setState(TeaPublicationState.HIDDEN);
		when(teaPublicationService.hide(eq(rd.getUuid()), eq("bye"), any())).thenReturn(hidden);

		assertSame(hidden, fetcher.hideReleaseOnTea(rd.getUuid(), "bye"));

		InOrder order = inOrder(authorizationService, teaPublicationService);
		order.verify(authorizationService).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.PUBLISH_EXTERNALLY,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.WRITE);
		order.verify(teaPublicationService).hide(eq(rd.getUuid()), eq("bye"), any(WhoUpdated.class));
	}

	@Test
	void aUserWithoutTheFunctionReachesNoService() throws Exception {
		when(authorizationService.isUserAuthorizedForObjectGraphQL(any(), eq(PermissionFunction.PUBLISH_EXTERNALLY), any(),
				any(), anyCollection(), any())).thenThrow(new AccessDeniedException("not authorized"));

		for (Executable act : List.<Executable>of(
				() -> fetcher.publishReleaseOnTea(rd.getUuid(), null),
				() -> fetcher.republishReleaseOnTea(rd.getUuid(), null),
				() -> fetcher.hideReleaseOnTea(rd.getUuid(), null))) {
			assertThrows(AccessDeniedException.class, act);
		}
		verifyNoInteractions(teaPublicationService);
	}

	@Test
	void theServicesRefusalSurfacesAsTheGraphqlErrorWithItsMessage() throws Exception {
		String message = "Release widget 1.0.0 (" + rd.getUuid() + ") is already published on TEA (collection version 2); "
				+ "re-publish it instead";
		when(teaPublicationService.publish(any())).thenThrow(
				new TeaPublishRefusedException(TeaPublishRefusal.ALREADY_PUBLISHED, rd.getUuid(), message));
		when(teaPublicationService.hide(any(), any(), any())).thenThrow(
				new TeaPublishRefusedException(TeaPublishRefusal.NOT_PUBLISHED, rd.getUuid(), "not published"));

		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> fetcher.publishReleaseOnTea(rd.getUuid(), null));
		assertEquals(message, e.getMessage());
		assertEquals(TeaPublishRefusal.ALREADY_PUBLISHED, e.reason());
		assertThrows(TeaPublishRefusedException.class, () -> fetcher.republishReleaseOnTea(rd.getUuid(), null));
		assertEquals("not published",
				assertThrows(TeaPublishRefusedException.class, () -> fetcher.hideReleaseOnTea(rd.getUuid(), null)).getMessage());

		// the exception is a RelizaException, so the GraphQL handler surfaces it verbatim as BAD_REQUEST
		GraphQLError err = new GraphQLExceptionHandlers().handleReliza(e);
		assertEquals(message, err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
	}

	@Test
	void anUnknownReleaseIsRefusedBeforeAnyCheck() {
		UUID unknown = UUID.randomUUID();
		when(sharedReleaseService.getReleaseData(unknown)).thenReturn(Optional.empty());
		assertThrows(RelizaException.class, () -> fetcher.publishReleaseOnTea(unknown, null));
		verifyNoInteractions(authorizationService, teaPublicationService);
	}

	@Test
	void theViewChecksResourceAtRead() throws Exception {
		TeaPublishPreview preview = mock(TeaPublishPreview.class);
		when(teaPublicationService.preview(rd.getUuid())).thenReturn(preview);

		assertSame(preview, fetcher.releaseTeaPublicationView(rd.getUuid()));

		InOrder order = inOrder(authorizationService, teaPublicationService);
		order.verify(authorizationService).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
		order.verify(teaPublicationService).preview(rd.getUuid());
	}

	@Test
	void aPublicationOfAnotherOrgAnswersNull() throws Exception {
		ReleaseData foreign = release(UUID.randomUUID());
		TeaPublicationData theirs = publication(foreign, ComponentType.COMPONENT);
		when(teaPublicationService.getPublication(theirs.getUuid())).thenReturn(Optional.of(theirs));
		when(authorizationService.isUserAuthorizedForObject(ud, PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				foreign.getUuid(), List.of(foreign), CallType.READ)).thenReturn(false);

		assertNull(fetcher.teaPublication(theirs.getUuid()));
		assertEquals(List.of(), fetcher.teaPublicationCollections(theirs.getUuid()));
		verify(teaPublicationService, never()).listCollections(any());
	}

	@Test
	void aReadablePublicationAnswersItselfAndItsCollections() throws Exception {
		TeaPublicationData mine = publication(rd, ComponentType.COMPONENT);
		when(teaPublicationService.getPublication(mine.getUuid())).thenReturn(Optional.of(mine));
		when(authorizationService.isUserAuthorizedForObject(ud, PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				rd.getUuid(), List.of(rd), CallType.READ)).thenReturn(true);
		List<TeaPublishedCollectionData> collections = List.of(new TeaPublishedCollectionData());
		when(teaPublicationService.listCollections(mine.getUuid())).thenReturn(collections);

		assertSame(mine, fetcher.teaPublication(mine.getUuid()));
		assertSame(collections, fetcher.teaPublicationCollections(mine.getUuid()));
		assertNull(fetcher.teaPublication(UUID.randomUUID()), "an unknown publication answers null");
	}

	// ------------------------------------------------------------------ case 33: the programmatic three

	/** The request context the programmatic filter leaves behind, as the Agent* programmatic tests build it. */
	private static DgsDataFetchingEnvironment programmatic() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/programmatic/graphql");
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of()).build(), mock(ApplicationContext.class));
	}

	private static AuthHeaderParse key(ApiTypeEnum type) {
		return AuthHeaderParse.builder().type(type).objUuid(UUID.randomUUID()).apiKeyId("key-id").build();
	}

	/** The three acts and the view: every programmatic field of the fetcher. */
	private List<Executable> programmaticFields(DgsDataFetchingEnvironment dfe) {
		return List.of(
				() -> fetcher.releaseTeaPublicationViewProgrammatic(rd.getUuid(), dfe),
				() -> fetcher.publishReleaseOnTeaProgrammatic(rd.getUuid(), null, dfe),
				() -> fetcher.republishReleaseOnTeaProgrammatic(rd.getUuid(), null, dfe),
				() -> fetcher.hideReleaseOnTeaProgrammatic(rd.getUuid(), null, dfe));
	}

	private List<Executable> programmaticActs(DgsDataFetchingEnvironment dfe) {
		return List.of(
				() -> fetcher.publishReleaseOnTeaProgrammatic(rd.getUuid(), null, dfe),
				() -> fetcher.republishReleaseOnTeaProgrammatic(rd.getUuid(), null, dfe),
				() -> fetcher.hideReleaseOnTeaProgrammatic(rd.getUuid(), null, dfe));
	}

	@Test
	void aFreeformKeyWithTheFunctionPublishesAsItself() throws Exception {
		AuthHeaderParse ahp = key(ApiTypeEnum.FREEFORM);
		when(authorizationService.authenticateProgrammaticWithOrg(any(), any()))
				.thenReturn(new ProgrammaticAuthContext(ahp, org));
		when(authorizationService.verifyProgrammaticKey(ahp)).thenReturn(new VerifiedProgrammaticKey(ahp, org));
		WhoUpdated keyWho = WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "198.51.100.7");
		when(authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.PUBLISH_EXTERNALLY,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.WRITE))
				.thenReturn(new FreeformKeyVerification(keyWho, org, UUID.randomUUID()));
		when(teaPublicationService.publish(any())).thenReturn(result());
		DgsDataFetchingEnvironment dfe = programmatic();

		fetcher.publishReleaseOnTeaProgrammatic(rd.getUuid(), "ci", dfe);
		fetcher.republishReleaseOnTeaProgrammatic(rd.getUuid(), "ci", dfe);
		fetcher.hideReleaseOnTeaProgrammatic(rd.getUuid(), "ci", dfe);

		ArgumentCaptor<TeaPublishRequest> req = ArgumentCaptor.forClass(TeaPublishRequest.class);
		verify(teaPublicationService, times(2)).publish(req.capture());
		assertEquals(List.of(TeaPublishMode.PUBLISH, TeaPublishMode.REPUBLISH),
				req.getAllValues().stream().map(TeaPublishRequest::mode).toList());
		assertTrue(req.getAllValues().stream().allMatch(r -> r.wu() == keyWho && rd.getUuid().equals(r.release())),
				"the act is attributed to the key the verification answered");
		verify(teaPublicationService).hide(rd.getUuid(), "ci", keyWho);
		verify(authorizationService, times(3)).isFreeformKeyAuthorizedForObjectGraphQL(ahp,
				PermissionFunction.PUBLISH_EXTERNALLY, PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.WRITE);
	}

	@Test
	void aKeyWithoutTheFunctionIsRefusedAndReachesNoService() throws Exception {
		AuthHeaderParse ahp = key(ApiTypeEnum.FREEFORM);
		when(authorizationService.authenticateProgrammaticWithOrg(any(), any()))
				.thenReturn(new ProgrammaticAuthContext(ahp, org));
		when(authorizationService.verifyProgrammaticKey(ahp)).thenReturn(new VerifiedProgrammaticKey(ahp, org));
		when(authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(), any()))
				.thenThrow(new AccessDeniedException("FreeForm key not authorized for this resource"));
		DgsDataFetchingEnvironment dfe = programmatic();

		for (Executable act : programmaticActs(dfe)) assertThrows(AccessDeniedException.class, act);
		verifyNoInteractions(teaPublicationService);
	}

	@Test
	void anUnauthenticatedCallIsRefused() throws Exception {
		when(authorizationService.authenticateProgrammaticWithOrg(any(), any()))
				.thenReturn(new ProgrammaticAuthContext(null, null));
		DgsDataFetchingEnvironment dfe = programmatic();

		for (Executable act : programmaticActs(dfe)) assertThrows(AccessDeniedException.class, act);
		verify(authorizationService, never()).verifyProgrammaticKey(any());
		verify(sharedReleaseService, never()).getReleaseData(any(), any());
		verify(authorizationService, never()).isFreeformKeyAuthorizedForObjectGraphQL(any(), any(), any(), any(),
				anyCollection(), any());
		verifyNoInteractions(teaPublicationService);
	}

	/**
	 * A COMPONENT-bound key goes to the same RBAC check, which refuses it in resolveRbacKey ("FREEFORM or
	 * USER API key required"). The check here is the real AuthorizationService (only the request
	 * authentication is stubbed), so the refusal is the production one; no existing unit test pins
	 * resolveRbacKey's refusal of object-bound keys on its own.
	 */
	@Test
	void aComponentBoundKeyIsRefusedByTheRbacKeyResolution() throws Exception {
		AuthorizationService real = spy(new AuthorizationService());
		LicenseStatus operational = new LicenseStatus();
		operational.setLicenseValid(true);
		operational.setSystemSealed(false);
		ReflectionTestUtils.setField(real, "licenseStatus", operational);
		ReflectionTestUtils.setField(fetcher, "authorizationService", real);
		for (ApiTypeEnum objectBound : List.of(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW)) {
			AuthHeaderParse ahp = key(objectBound);
			doReturn(new ProgrammaticAuthContext(ahp, org)).when(real).authenticateProgrammaticWithOrg(any(), any());
			// the real verification needs ApiKeyService; the key's secret is taken as verified here
			doReturn(new VerifiedProgrammaticKey(ahp, org)).when(real).verifyProgrammaticKey(ahp);
			DgsDataFetchingEnvironment dfe = programmatic();

			for (Executable act : programmaticActs(dfe)) {
				AccessDeniedException e = assertThrows(AccessDeniedException.class, act);
				assertEquals("FREEFORM or USER API key required", e.getMessage(), objectBound.name());
			}
			verify(real, atLeastOnce()).isFreeformKeyAuthorizedForObjectGraphQL(ahp,
					PermissionFunction.PUBLISH_EXTERNALLY, PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.WRITE);
		}
		verifyNoInteractions(teaPublicationService);
	}

	@Test
	void theProgrammaticViewNeedsResourceAtRead() throws Exception {
		AuthHeaderParse ahp = key(ApiTypeEnum.FREEFORM);
		when(authorizationService.authenticateProgrammaticWithOrg(any(), any()))
				.thenReturn(new ProgrammaticAuthContext(ahp, org));
		when(authorizationService.verifyProgrammaticKey(ahp)).thenReturn(new VerifiedProgrammaticKey(ahp, org));
		TeaPublishPreview preview = mock(TeaPublishPreview.class);
		when(teaPublicationService.preview(rd.getUuid())).thenReturn(preview);

		assertSame(preview, fetcher.releaseTeaPublicationViewProgrammatic(rd.getUuid(), programmatic()));

		verify(authorizationService).isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
	}

	// ------------------------------------------------------------------ TEA-25: the TPF-17 order

	/** A sealed instance answers its sentence before the release is read or the object check runs. */
	@Test
	void aSealedInstanceAnswersBeforeTheReleaseIsRead() throws Exception {
		AuthorizationService real = spy(new AuthorizationService());
		LicenseStatus sealed = new LicenseStatus();
		sealed.setLicenseValid(true);
		sealed.setSystemSealed(true);
		ReflectionTestUtils.setField(real, "licenseStatus", sealed);
		ReflectionTestUtils.setField(fetcher, "authorizationService", real);
		AuthHeaderParse ahp = key(ApiTypeEnum.FREEFORM);
		doReturn(new ProgrammaticAuthContext(ahp, org)).when(real).authenticateProgrammaticWithOrg(any(), any());
		doReturn(new VerifiedProgrammaticKey(ahp, org)).when(real).verifyProgrammaticKey(ahp);
		DgsDataFetchingEnvironment dfe = programmatic();

		for (Executable field : programmaticFields(dfe)) {
			RelizaException e = assertThrows(RelizaException.class, field);
			assertEquals("System is sealed. Please unseal the system first.", e.getMessage());
		}
		verifyNoInteractions(sharedReleaseService, teaPublicationService);
		verify(real, never()).isFreeformKeyAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(),
				any());
	}

	/** A credential that does not verify is refused before the state check and the release read. */
	@Test
	void anUnverifiedKeyIsRefusedBeforeTheStateAndTheRead() throws Exception {
		AuthHeaderParse ahp = key(ApiTypeEnum.FREEFORM);
		when(authorizationService.authenticateProgrammaticWithOrg(any(), any()))
				.thenReturn(new ProgrammaticAuthContext(ahp, org));
		when(authorizationService.verifyProgrammaticKey(ahp)).thenThrow(new AccessDeniedException("Key unauthorized"));
		DgsDataFetchingEnvironment dfe = programmatic();

		for (Executable field : programmaticFields(dfe)) assertThrows(AccessDeniedException.class, field);
		verify(authorizationService, never()).validateSystemOperational(any());
		verifyNoInteractions(sharedReleaseService, teaPublicationService);
	}

	/** The release is read in the verified key's organisation: another organisation's reads as missing. */
	@Test
	void aReleaseOfAnotherOrganisationReadsAsMissing() throws Exception {
		AuthHeaderParse ahp = key(ApiTypeEnum.FREEFORM);
		UUID otherOrg = UUID.randomUUID();
		// the key row's organisation is authoritative, not the one the request authentication resolved
		when(authorizationService.authenticateProgrammaticWithOrg(any(), any()))
				.thenReturn(new ProgrammaticAuthContext(ahp, org));
		when(authorizationService.verifyProgrammaticKey(ahp)).thenReturn(new VerifiedProgrammaticKey(ahp, otherOrg));
		DgsDataFetchingEnvironment dfe = programmatic();

		for (Executable field : programmaticFields(dfe)) {
			RelizaException e = assertThrows(RelizaException.class, field);
			assertEquals("Release not found: " + rd.getUuid(), e.getMessage());
		}
		verify(sharedReleaseService, times(4)).getReleaseData(rd.getUuid(), otherOrg);
		verify(sharedReleaseService, never()).getReleaseData(rd.getUuid());
		verify(authorizationService, never()).isFreeformKeyAuthorizedForObjectGraphQL(any(), any(), any(), any(),
				anyCollection(), any());
		verifyNoInteractions(teaPublicationService);
	}

	// ------------------------------------------------------------------ case 34: field resolvers

	private static DgsDataFetchingEnvironment sourced(Object source) {
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getSource()).thenReturn(source);
		return dfe;
	}

	@Test
	void releaseTeaPublicationAnswersTheHeadOrNull() {
		TeaPublicationData head = publication(rd, ComponentType.COMPONENT);
		when(teaPublicationService.getPublicationOfRelease(rd.getUuid())).thenReturn(Optional.of(head));
		assertSame(head, fetcher.teaPublicationOfRelease(sourced(rd)));

		ReleaseData unpublished = release(org);
		when(teaPublicationService.getPublicationOfRelease(unpublished.getUuid())).thenReturn(Optional.empty());
		assertNull(fetcher.teaPublicationOfRelease(sourced(unpublished)));
	}

	@Test
	void exposureIsComputedPerRowThroughExposureOf() {
		TeaPublicationData pub = publication(rd, ComponentType.COMPONENT);
		TeaPublicationData hidden = publication(release(org), ComponentType.COMPONENT);
		when(teaPublicationService.exposureOf(pub))
				.thenReturn(new TeaPublicationExposure(TeaExposureState.PRIVATE, null, null, null, pub, null));
		when(teaPublicationService.exposureOf(hidden)).thenReturn(new TeaPublicationExposure(TeaExposureState.CONCEALED,
				TeaConcealReason.HIDDEN, null, null, hidden, null));

		assertEquals(TeaExposureState.PRIVATE, fetcher.exposure(sourced(pub)));
		assertEquals(TeaExposureState.CONCEALED, fetcher.exposure(sourced(hidden)));
		verify(teaPublicationService).exposureOf(pub);
		verify(teaPublicationService).exposureOf(hidden);
	}

	// ------------------------------------------------------------------ round 2, case 68

	@Test
	void concealedBecauseAndCascadeOfLabelResolvePerRow() {
		TeaPublicationData exposed = publication(rd, ComponentType.COMPONENT);
		TeaPublicationData cancelled = publication(release(org), ComponentType.COMPONENT);
		TeaPublicationData child = publication(release(org), ComponentType.COMPONENT);
		UUID owner = UUID.randomUUID();
		child.setCascadeOf(owner);
		when(teaPublicationService.exposureOf(exposed))
				.thenReturn(new TeaPublicationExposure(TeaExposureState.PRIVATE, null, null, null, exposed, null));
		when(teaPublicationService.exposureOf(cancelled)).thenReturn(new TeaPublicationExposure(TeaExposureState.CONCEALED,
				TeaConcealReason.LIFECYCLE_BELOW_ASSEMBLED, null, null, cancelled, null));
		when(teaPublicationService.ownerLabel(owner)).thenReturn("platform 2.0.1");

		assertNull(fetcher.concealedBecause(sourced(exposed)));
		assertNull(fetcher.cascadeOfLabel(sourced(exposed)));
		assertEquals(TeaConcealReason.LIFECYCLE_BELOW_ASSEMBLED, fetcher.concealedBecause(sourced(cancelled)));
		assertNull(fetcher.cascadeOfLabel(sourced(cancelled)));
		assertEquals("platform 2.0.1", fetcher.cascadeOfLabel(sourced(child)));
		verify(teaPublicationService, times(1)).ownerLabel(any());
	}

	@Test
	void publicUrlIsComputedPerRowFromTheOrgsApiBase() {
		String apiBase = "https://rearm.example.com/tea/" + UUID.randomUUID();
		when(teaPublicationService.apiBaseOf(org)).thenReturn(apiBase);
		TeaPublicationData component = publication(rd, ComponentType.COMPONENT);
		TeaPublicationData product = publication(release(org), ComponentType.PRODUCT);
		TeaPublicationData noTeaId = publication(release(UUID.randomUUID()), ComponentType.COMPONENT);

		assertEquals(apiBase + "/v1.0.0/componentRelease/" + component.getUuid(), fetcher.publicUrl(sourced(component)));
		assertEquals(apiBase + "/v1.0.0/productRelease/" + product.getUuid(), fetcher.publicUrl(sourced(product)));
		assertNull(fetcher.publicUrl(sourced(noTeaId)), "an org without a TEA id has no public URL");
		verify(teaPublicationService, times(2)).apiBaseOf(org);
	}
}
