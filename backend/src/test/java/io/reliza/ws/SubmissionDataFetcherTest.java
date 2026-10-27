/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SubmissionRefusedException;
import io.reliza.model.ComponentData;
import io.reliza.model.PackageKind;
import io.reliza.model.ReleaseData;
import io.reliza.model.SubmissionActOutcome;
import io.reliza.model.SubmissionActResult;
import io.reliza.model.SubmissionData;
import io.reliza.model.SubmissionPreview;
import io.reliza.model.SubmissionRefusal;
import io.reliza.model.SubmissionState;
import io.reliza.model.SubmissionVersionData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SubmissionHook;
import io.reliza.service.UserService;

/**
 * Task TEA-35, design 4.4 case 10 (M-1, M-2 unit half): the shared fetcher, with the hook present and
 * absent (the CE stub). Root fields read their object by uuid, answer "not found" outside the caller's
 * organization, check, then call the hook with the object's organization; the four child resolvers make
 * no check of their own.
 */
class SubmissionDataFetcherTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final UUID FOREIGN_ORG = UUID.randomUUID();

	private SubmissionDataFetcher fetcher;
	private UserService userService;
	private AuthorizationService auth;
	private SharedReleaseService shared;
	private GetComponentService components;
	private SubmissionHook hook;
	private UserData ud;
	private ReleaseData rd;
	private ComponentData cd;
	private SubmissionData head;

	@BeforeEach
	void rig() {
		fetcher = new SubmissionDataFetcher();
		userService = mock(UserService.class);
		auth = mock(AuthorizationService.class);
		shared = mock(SharedReleaseService.class);
		components = mock(GetComponentService.class);
		hook = mock(SubmissionHook.class);
		ReflectionTestUtils.setField(fetcher, "userService", userService);
		ReflectionTestUtils.setField(fetcher, "authorizationService", auth);
		ReflectionTestUtils.setField(fetcher, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(fetcher, "getComponentService", components);
		ReflectionTestUtils.setField(fetcher, "hook", hook);
		ud = mock(UserData.class);
		when(ud.isInOrganization(ORG)).thenReturn(true);
		cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(ORG);
		cd.setName("Widget Pro");
		rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", cd.getUuid());
		rd.setVersion("1.2.0");
		when(shared.getReleaseData(rd.getUuid())).thenReturn(Optional.of(rd));
		when(shared.getReleaseData(rd.getUuid(), ORG)).thenReturn(Optional.of(rd));
		when(shared.getReleaseDataLight(rd.getUuid())).thenReturn(Optional.of(rd));
		when(components.getComponentData(cd.getUuid())).thenReturn(Optional.of(cd));
		when(components.getComponentData(cd.getUuid(), ORG)).thenReturn(Optional.of(cd));
		head = new SubmissionData();
		head.setUuid(UUID.randomUUID());
		head.setOrg(ORG);
		head.setRelease(rd.getUuid());
		head.setComponent(cd.getUuid());
		head.setKind(PackageKind.FDA_PREMARKET);
		head.setLatestVersion(2);
		when(hook.getSubmission(head.getUuid())).thenReturn(Optional.of(head));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private void denyEverything() throws RelizaException {
		doThrow(new AccessDeniedException("Not authorized")).when(auth).isUserAuthorizedForObjectGraphQL(any(), any(),
				any(), any(), anyList(), any());
		doThrow(new AccessDeniedException("Not authorized")).when(auth).isFreeformKeyAuthorizedForObjectGraphQL(any(),
				any(), any(), any(), anyList(), any());
	}

	private ReleaseData foreignRelease() {
		ReleaseData f = new ReleaseData();
		ReflectionTestUtils.setField(f, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(f, "org", FOREIGN_ORG);
		ReflectionTestUtils.setField(f, "component", UUID.randomUUID());
		when(shared.getReleaseData(f.getUuid())).thenReturn(Optional.of(f));
		return f;
	}

	private static void notFound(String what, org.junit.jupiter.api.function.Executable e) {
		RelizaException ex = assertThrows(RelizaException.class, e);
		assertTrue(ex.getMessage().startsWith(what + " not found: "), ex.getMessage());
	}

	// ------------------------------------------------------------------ hook present

	@Test
	void releaseKeyedReadsCheckTheReleaseThenCallTheHookWithItsOrganization() throws Exception {
		when(hook.listOfRelease(ORG, rd.getUuid())).thenReturn(List.of(head));
		assertEquals(List.of(head), fetcher.submissionsOfRelease(ud, rd.getUuid()));
		verify(auth).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				rd.getUuid(), List.of(rd), CallType.READ);
		verify(hook).listOfRelease(ORG, rd.getUuid());
	}

	@Test
	void aMissingOrForeignObjectIsNotFoundBeforeAnyCheckOrHookAct() throws Exception {
		notFound("Release", () -> fetcher.submissionsOfRelease(ud, UUID.randomUUID()));
		notFound("Release", () -> fetcher.submissionsOfRelease(ud, foreignRelease().getUuid()));
		notFound("Release", () -> fetcher.createSubmission(ud, foreignRelease().getUuid(), PackageKind.FDA_PREMARKET, null));
		notFound("Release", () -> fetcher.submissionPreview(ud, UUID.randomUUID(), PackageKind.FDA_PREMARKET));
		notFound("Component", () -> fetcher.submissionsOfComponent(ud, UUID.randomUUID(), null, null));
		ComponentData fc = new ComponentData();
		fc.setUuid(UUID.randomUUID());
		fc.setOrg(FOREIGN_ORG);
		when(components.getComponentData(fc.getUuid())).thenReturn(Optional.of(fc));
		notFound("Component", () -> fetcher.submissionsOfComponent(ud, fc.getUuid(), null, null));
		notFound("Submission", () -> fetcher.submission(ud, UUID.randomUUID()));
		SubmissionData foreign = new SubmissionData();
		foreign.setUuid(UUID.randomUUID());
		foreign.setOrg(FOREIGN_ORG);
		when(hook.getSubmission(foreign.getUuid())).thenReturn(Optional.of(foreign));
		notFound("Submission", () -> fetcher.submission(ud, foreign.getUuid()));
		notFound("Submission", () -> fetcher.submissionVersion(ud, foreign.getUuid(), 1));
		notFound("Submission", () -> fetcher.resubmitSubmission(ud, foreign.getUuid(), "r", null));
		notFound("Submission", () -> fetcher.withdrawSubmission(ud, foreign.getUuid(), "r"));
		verifyNoInteractions(auth);
		verify(hook, never()).create(any(), any(), any(), any());
		verify(hook, never()).resubmit(any(), any(), any(), any(), any());
		verify(hook, never()).withdraw(any(), any(), any(), any());
		verify(hook, never()).listOfRelease(any(), any());
		verify(hook, never()).listOfComponent(any(), any(), any(), any());
	}

	@Test
	void anUnauthorizedCallerIsDeniedWithNoHookAct() throws Exception {
		denyEverything();
		assertThrows(AccessDeniedException.class, () -> fetcher.submissionsOfRelease(ud, rd.getUuid()));
		assertThrows(AccessDeniedException.class, () -> fetcher.submissionsOfComponent(ud, cd.getUuid(), null, null));
		assertThrows(AccessDeniedException.class, () -> fetcher.submission(ud, head.getUuid()));
		assertThrows(AccessDeniedException.class, () -> fetcher.submissionVersion(ud, head.getUuid(), 1));
		assertThrows(AccessDeniedException.class,
				() -> fetcher.submissionPreview(ud, rd.getUuid(), PackageKind.FDA_PREMARKET));
		assertThrows(AccessDeniedException.class,
				() -> fetcher.createSubmission(ud, rd.getUuid(), PackageKind.FDA_PREMARKET, null));
		assertThrows(AccessDeniedException.class, () -> fetcher.resubmitSubmission(ud, head.getUuid(), "r", 2));
		assertThrows(AccessDeniedException.class, () -> fetcher.withdrawSubmission(ud, head.getUuid(), "r"));
		verify(hook, never()).create(any(), any(), any(), any());
		verify(hook, never()).resubmit(any(), any(), any(), any(), any());
		verify(hook, never()).withdraw(any(), any(), any(), any());
		verify(hook, never()).preview(any(), any());
		verify(hook, never()).getVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt());
		verify(hook, never()).listOfRelease(any(), any());
	}

	@Test
	void theActsNeedWriteOnTheComponentAndGetTheObjectsOrganization() throws Exception {
		SubmissionActResult res = new SubmissionActResult(SubmissionActOutcome.CREATED, head, null);
		when(hook.create(eq(rd.getUuid()), eq(PackageKind.FDA_PREMARKET), eq("r"), any())).thenReturn(res);
		assertSame(res, fetcher.createSubmission(ud, rd.getUuid(), PackageKind.FDA_PREMARKET, "r"));
		fetcher.resubmitSubmission(ud, head.getUuid(), "again", 2);
		fetcher.withdrawSubmission(ud, head.getUuid(), "done");
		verify(auth, org.mockito.Mockito.times(3)).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.WRITE);
		verify(hook).resubmit(eq(ORG), eq(head.getUuid()), eq("again"), eq(2), any());
		verify(hook).withdraw(eq(ORG), eq(head.getUuid()), eq("done"), any());
	}

	@Test
	void aRefusalPropagates() throws Exception {
		SubmissionRefusedException refused = new SubmissionRefusedException(SubmissionRefusal.ALREADY_SUBMITTED,
				rd.getUuid(), "Release x: use resubmit");
		when(hook.create(any(), any(), any(), any())).thenThrow(refused);
		assertSame(refused, assertThrows(SubmissionRefusedException.class,
				() -> fetcher.createSubmission(ud, rd.getUuid(), PackageKind.FDA_PREMARKET, null)));
	}

	@Test
	void theComponentListThePreviewAndTheSinglesUseTheirScopes() throws Exception {
		fetcher.submissionsOfComponent(ud, cd.getUuid(), PackageKind.FDA_PREMARKET, SubmissionState.ACTIVE);
		verify(auth).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.COMPONENT,
				cd.getUuid(), List.of(cd), CallType.READ);
		verify(hook).listOfComponent(ORG, cd.getUuid(), PackageKind.FDA_PREMARKET, SubmissionState.ACTIVE);
		fetcher.submissionPreview(ud, rd.getUuid(), PackageKind.FDA_PREMARKET);
		verify(hook).preview(rd.getUuid(), PackageKind.FDA_PREMARKET);
		SubmissionVersionData v = new SubmissionVersionData();
		when(hook.getVersion(ORG, head.getUuid(), 1)).thenReturn(Optional.of(v));
		assertSame(head, fetcher.submission(ud, head.getUuid()));
		assertSame(v, fetcher.submissionVersion(ud, head.getUuid(), 1));
		verify(auth, org.mockito.Mockito.times(2)).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);
	}

	@Test
	void theChildResolversMakeNoCheckAndCallTheHookWithTheParent() {
		fetcher.submissionsOf(rd);
		verify(hook).listOfRelease(ORG, rd.getUuid());
		fetcher.versionsOf(head);
		verify(hook).listVersions(ORG, head.getUuid());
		fetcher.latestOf(head);
		verify(hook).getVersion(ORG, head.getUuid(), 2);
		assertEquals("1.2.0", fetcher.releaseVersionOf(head));
		SubmissionData empty = new SubmissionData();
		empty.setUuid(UUID.randomUUID());
		empty.setOrg(ORG);
		assertNull(fetcher.latestOf(empty), "no version yet");
		verify(hook, never()).getVersion(ORG, empty.getUuid(), 0);
		verifyNoInteractions(auth);
	}

	@Test
	void theProgrammaticFieldsVerifyTheKeyBeforeReadingAnything() throws Exception {
		SubmissionDataFetcher spied = spy(fetcher);
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		doThrow(new AccessDeniedException("Invalid authorization")).when(spied).verifiedKey(any(), any());
		assertThrows(AccessDeniedException.class, () -> spied.submissionsOfReleaseProgrammatic(rd.getUuid(), dfe));
		assertThrows(AccessDeniedException.class,
				() -> spied.submissionsOfComponentProgrammatic(cd.getUuid(), null, null, dfe));
		assertThrows(AccessDeniedException.class, () -> spied.submissionProgrammatic(head.getUuid(), dfe));
		assertThrows(AccessDeniedException.class, () -> spied.submissionVersionProgrammatic(head.getUuid(), 1, dfe));
		verifyNoInteractions(shared, components, hook);
	}

	@Test
	void aKeyReadsInItsOwnOrganizationOnly() throws Exception {
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		VerifiedProgrammaticKey key = new VerifiedProgrammaticKey(ahp, ORG);
		VerifiedProgrammaticKey foreignKey = new VerifiedProgrammaticKey(ahp, FOREIGN_ORG);
		fetcher.submissionsOfReleaseForKey(key, rd.getUuid());
		verify(auth).isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.RESOURCE, PermissionScope.RELEASE,
				rd.getUuid(), List.of(rd), CallType.READ);
		verify(hook).listOfRelease(ORG, rd.getUuid());
		fetcher.submissionsOfComponentForKey(key, cd.getUuid(), null, null);
		verify(auth).isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.READ);
		assertSame(head, fetcher.submissionForKey(key, head.getUuid()));
		notFound("Release", () -> fetcher.submissionsOfReleaseForKey(foreignKey, rd.getUuid()));
		notFound("Component", () -> fetcher.submissionsOfComponentForKey(foreignKey, cd.getUuid(), null, null));
		notFound("Submission", () -> fetcher.submissionForKey(foreignKey, head.getUuid()));
		notFound("Submission", () -> fetcher.submissionVersionForKey(foreignKey, head.getUuid(), 1));
		verify(hook, never()).getVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}

	/** Design 3.7 for the two programmatic singles: the key's READ on the head's release, then the hook. */
	@Test
	void aKeyWithoutReadOnTheHeadsReleaseIsDeniedOnBothSinglesWithNoVersionRead() throws Exception {
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		VerifiedProgrammaticKey key = new VerifiedProgrammaticKey(ahp, ORG);
		SubmissionVersionData v = new SubmissionVersionData();
		when(hook.getVersion(ORG, head.getUuid(), 1)).thenReturn(Optional.of(v));
		assertSame(head, fetcher.submissionForKey(key, head.getUuid()));
		assertSame(v, fetcher.submissionVersionForKey(key, head.getUuid(), 1));
		verify(auth, times(2)).isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, rd.getUuid(), List.of(rd), CallType.READ);

		clearInvocations(hook);
		denyEverything();
		assertThrows(AccessDeniedException.class, () -> fetcher.submissionForKey(key, head.getUuid()));
		assertThrows(AccessDeniedException.class, () -> fetcher.submissionVersionForKey(key, head.getUuid(), 1));
		verify(hook, never()).getVersion(any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}

	/** The request context the programmatic filter leaves behind, as the TEA fetcher tests build it. */
	private static DgsDataFetchingEnvironment programmatic() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/programmatic/graphql");
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of()).build(), mock(ApplicationContext.class));
	}

	private VerifiedProgrammaticKey keyOfTheRequest() throws Exception {
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		when(auth.authenticateProgrammaticWithOrg(any(), any()))
				.thenReturn(new ProgrammaticAuthContext(ahp, ORG));
		VerifiedProgrammaticKey key = new VerifiedProgrammaticKey(ahp, ORG);
		when(auth.verifyProgrammaticKey(ahp)).thenReturn(key);
		return key;
	}

	/** Design 3.7 (TPF-17 order): credential, key, then the system state for a read; nothing is read when it refuses. */
	@Test
	void aKeyFieldChecksTheSystemStateForAReadAndReadsNothingWhenItRefuses() throws Exception {
		VerifiedProgrammaticKey key = keyOfTheRequest();
		DgsDataFetchingEnvironment dfe = programmatic();
		assertSame(key, fetcher.verifiedKey(dfe, CallType.READ));
		InOrder inOrder = inOrder(auth);
		inOrder.verify(auth).authenticateProgrammaticWithOrg(any(), any());
		inOrder.verify(auth).verifyProgrammaticKey(key.principal());
		inOrder.verify(auth).validateSystemOperational(CallType.READ);

		doThrow(new RelizaException("the system is locked")).when(auth).validateSystemOperational(any());
		for (Executable field : List.<Executable>of(
				() -> fetcher.submissionsOfReleaseProgrammatic(rd.getUuid(), dfe),
				() -> fetcher.submissionsOfComponentProgrammatic(cd.getUuid(), null, null, dfe),
				() -> fetcher.submissionProgrammatic(head.getUuid(), dfe),
				() -> fetcher.submissionVersionProgrammatic(head.getUuid(), 1, dfe))) {
			assertEquals("the system is locked", assertThrows(RelizaException.class, field).getMessage());
		}
		verify(auth, times(5)).validateSystemOperational(CallType.READ);
		verifyNoInteractions(shared, components, hook);
	}

	@Test
	void theKeysComponentListPassesTheKindAndStateFilters() throws Exception {
		keyOfTheRequest();
		DgsDataFetchingEnvironment dfe = programmatic();
		when(hook.listOfComponent(ORG, cd.getUuid(), PackageKind.FDA_PREMARKET, SubmissionState.WITHDRAWN))
				.thenReturn(List.of(head));
		assertEquals(List.of(head), fetcher.submissionsOfComponentProgrammatic(cd.getUuid(), PackageKind.FDA_PREMARKET,
				SubmissionState.WITHDRAWN, dfe));
		fetcher.submissionsOfComponentProgrammatic(cd.getUuid(), null, null, dfe);
		verify(hook).listOfComponent(ORG, cd.getUuid(), PackageKind.FDA_PREMARKET, SubmissionState.WITHDRAWN);
		verify(hook).listOfComponent(ORG, cd.getUuid(), null, null);
	}

	// ------------------------------------------------------------------ hook absent (CE)

	@Test
	void withoutAHookReleaseAndComponentKeyedFieldsStillCheckThenAnswerTheStub() throws Exception {
		ReflectionTestUtils.setField(fetcher, "hook", null);
		assertEquals(List.of(), fetcher.submissionsOfRelease(ud, rd.getUuid()));
		assertEquals(List.of(), fetcher.submissionsOfComponent(ud, cd.getUuid(), null, null));
		SubmissionPreview p = fetcher.submissionPreview(ud, rd.getUuid(), PackageKind.FDA_PREMARKET);
		assertFalse(p.canSubmit());
		assertEquals(SubmissionRefusal.EDITION_UNSUPPORTED, p.refusal());
		assertEquals(0, p.nextVersion());
		assertTrue(p.plan().isEmpty());
		SubmissionRefusedException e = assertThrows(SubmissionRefusedException.class,
				() -> fetcher.createSubmission(ud, rd.getUuid(), PackageKind.FDA_PREMARKET, null));
		assertEquals(SubmissionRefusal.EDITION_UNSUPPORTED, e.reason());
		assertEquals(rd.getUuid(), e.release());
		assertTrue(e.getMessage().contains("regulatory submissions are a ReARM Pro feature"), e.getMessage());
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		assertEquals(List.of(), fetcher.submissionsOfReleaseForKey(new VerifiedProgrammaticKey(ahp, ORG), rd.getUuid()));
		assertEquals(List.of(), fetcher.submissionsOfComponentForKey(new VerifiedProgrammaticKey(ahp, ORG), cd.getUuid(),
				null, null));

		notFound("Release", () -> fetcher.submissionsOfRelease(ud, foreignRelease().getUuid()));
		denyEverything();
		assertThrows(AccessDeniedException.class, () -> fetcher.submissionsOfRelease(ud, rd.getUuid()));
		assertThrows(AccessDeniedException.class, () -> fetcher.submissionsOfComponent(ud, cd.getUuid(), null, null));
		assertThrows(AccessDeniedException.class,
				() -> fetcher.submissionPreview(ud, rd.getUuid(), PackageKind.FDA_PREMARKET));
		assertThrows(AccessDeniedException.class,
				() -> fetcher.createSubmission(ud, rd.getUuid(), PackageKind.FDA_PREMARKET, null));
		assertThrows(AccessDeniedException.class,
				() -> fetcher.submissionsOfReleaseForKey(new VerifiedProgrammaticKey(ahp, ORG), rd.getUuid()));
	}

	@Test
	void withoutAHookSubmissionKeyedFieldsAnswerAfterAuthenticationAlone() throws Exception {
		ReflectionTestUtils.setField(fetcher, "hook", null);
		doReturn(Optional.of(ud)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-35").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		assertNull(fetcher.submission(head.getUuid()));
		assertNull(fetcher.submissionVersion(head.getUuid(), 1));
		for (SubmissionRefusedException e : List.of(
				assertThrows(SubmissionRefusedException.class, () -> fetcher.resubmitSubmission(head.getUuid(), "r", null)),
				assertThrows(SubmissionRefusedException.class, () -> fetcher.withdrawSubmission(head.getUuid(), "r")))) {
			assertEquals(SubmissionRefusal.EDITION_UNSUPPORTED, e.reason());
			assertNull(e.release());
			assertEquals("regulatory submissions are a ReARM Pro feature", e.getMessage());
		}
		VerifiedProgrammaticKey key = new VerifiedProgrammaticKey(mock(AuthHeaderParse.class), ORG);
		assertNull(fetcher.submissionForKey(key, head.getUuid()));
		assertNull(fetcher.submissionVersionForKey(key, head.getUuid(), 1));
		verify(userService, org.mockito.Mockito.times(4)).getUserDataByAuth(any());
		verifyNoInteractions(auth, shared, components);
	}

	@Test
	void withoutAHookTheChildResolversAnswerEmptyWithNoCheckAndNoRead() {
		ReflectionTestUtils.setField(fetcher, "hook", null);
		assertEquals(List.of(), fetcher.submissionsOf(rd));
		assertEquals(List.of(), fetcher.versionsOf(head));
		assertNull(fetcher.latestOf(head));
		assertNull(fetcher.releaseVersionOf(head));
		verifyNoInteractions(auth, shared, components);
	}

	@Test
	void theUserFieldsNeedAUser() {
		when(userService.getUserDataByAuth(isNull())).thenReturn(Optional.empty());
		SecurityContextHolder.clearContext();
		assertThrows(RelizaException.class, () -> fetcher.submissionsOfRelease(rd.getUuid()));
	}
}
