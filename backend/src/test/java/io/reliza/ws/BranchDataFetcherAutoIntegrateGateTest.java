/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateResult;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.ws.BranchDataFetcher.AutoIntegrateResultView;

/**
 * Gate order of the auto-integrate fetchers (TPF-119 design row 12 (b)): the system check comes before
 * any lookup, the BRANCH check gets READ for the preview and WRITE for the mutations, and an unknown
 * uuid is refused through the same check (a singleton list holding null), never a NullPointerException.
 * Stub evaluations carry no rows, so the redaction helper reads no component.
 */
@ExtendWith(MockitoExtension.class)
class BranchDataFetcherAutoIntegrateGateTest {

	private static final String SEALED = "System is sealed. Please unseal the system first.";

	@Mock private UserService userService;
	@Mock private AuthorizationService authorizationService;
	@Mock private BranchService branchService;
	@Mock private ReleaseService releaseService;
	@Mock private SharedReleaseService sharedReleaseService;
	@Mock private GetComponentService getComponentService;
	@InjectMocks private BranchDataFetcher fetcher;

	private final UUID uuid = UUID.randomUUID();

	private static AutoIntegrateEvaluation evaluation() {
		return new AutoIntegrateEvaluation(AutoIntegrateOutcome.WOULD_CREATE, null, "1.0.0", List.of(), null, null);
	}

	private static ArgumentMatcher<Collection<RelizaObject>> singleton(Object element) {
		return l -> l.size() == 1 && l.iterator().next() == element;
	}

	private void sealed(CallType ct) throws RelizaException {
		doThrow(new RelizaException(SEALED)).when(authorizationService).validateSystemOperational(ct);
	}

	private void assertNothingLookedUp() {
		verifyNoInteractions(branchService, releaseService);
		verify(userService, never()).getUserDataByAuth(any());
	}

	/** (b1) The preview while sealed: refused before the user, the branch or the service is touched. */
	@Test
	void previewSealedRefusesBeforeAnyLookup() throws Exception {
		sealed(CallType.READ);
		assertThrows(RelizaException.class, () -> fetcher.autoIntegrateFeatureSetPreview(uuid));
		assertNothingLookedUp();
	}

	/** (b2) The explained mutation while sealed. */
	@Test
	void explainedSealedRefusesBeforeAnyLookup() throws Exception {
		sealed(CallType.WRITE);
		assertThrows(RelizaException.class, () -> fetcher.autoIntegrateFeatureSetExplained(uuid));
		assertNothingLookedUp();
	}

	/** (b6) The old mutation while sealed. */
	@Test
	void oldMutationSealedRefusesBeforeAnyLookup() throws Exception {
		sealed(CallType.WRITE);
		assertThrows(RelizaException.class, () -> fetcher.autoIntegrateFeatureSet(uuid));
		assertNothingLookedUp();
	}

	/** (b3) The preview's happy order with READ. */
	@Test
	void previewHappyOrder() throws Exception {
		BranchData bd = mock(BranchData.class);
		AutoIntegrateEvaluation ev = evaluation();
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(mock(UserData.class)));
		when(branchService.getBranchData(uuid)).thenReturn(Optional.of(bd));
		when(releaseService.evaluateAutoIntegrate(bd)).thenReturn(ev);
		AutoIntegrateEvaluation out = fetcher.autoIntegrateFeatureSetPreview(uuid);
		assertEquals(ev, out);
		InOrder io = inOrder(authorizationService, branchService, releaseService);
		io.verify(authorizationService).validateSystemOperational(CallType.READ);
		io.verify(branchService).getBranchData(uuid);
		io.verify(authorizationService).isUserAuthorizedForObjectGraphQL(any(), eq(PermissionFunction.RESOURCE),
				eq(PermissionScope.BRANCH), eq(uuid), argThat(singleton(bd)), eq(CallType.READ));
		io.verify(releaseService).evaluateAutoIntegrate(bd);
	}

	/** (b4) The explained mutation's happy order with WRITE; nothing created, so no release read. */
	@Test
	void explainedHappyOrder() throws Exception {
		BranchData bd = mock(BranchData.class);
		AutoIntegrateEvaluation ev = evaluation();
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(mock(UserData.class)));
		when(branchService.getBranchData(uuid)).thenReturn(Optional.of(bd));
		when(releaseService.autoIntegrateFeatureSetOnDemandExplained(bd))
				.thenReturn(new AutoIntegrateResult(Optional.empty(), ev));
		AutoIntegrateResultView out = fetcher.autoIntegrateFeatureSetExplained(uuid);
		assertNull(out.release());
		// the redaction helper always returns a copy (withDependencies), so equal, not the same instance
		assertEquals(ev, out.evaluation());
		InOrder io = inOrder(authorizationService, branchService, releaseService);
		io.verify(authorizationService).validateSystemOperational(CallType.WRITE);
		io.verify(branchService).getBranchData(uuid);
		io.verify(authorizationService).isUserAuthorizedForObjectGraphQL(any(), eq(PermissionFunction.RESOURCE),
				eq(PermissionScope.BRANCH), eq(uuid), argThat(singleton(bd)), eq(CallType.WRITE));
		io.verify(releaseService).autoIntegrateFeatureSetOnDemandExplained(bd);
		verifyNoInteractions(sharedReleaseService);
	}

	private AccessDeniedException unknownUuidRefused(CallType ct) throws Exception {
		AccessDeniedException forbidden = new AccessDeniedException("Forbidden");
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(mock(UserData.class)));
		when(branchService.getBranchData(uuid)).thenReturn(Optional.empty());
		doThrow(forbidden).when(authorizationService).isUserAuthorizedForObjectGraphQL(any(),
				eq(PermissionFunction.RESOURCE), eq(PermissionScope.BRANCH), eq(uuid), argThat(singleton(null)),
				eq(ct));
		return forbidden;
	}

	/** (b5) The preview on an unknown uuid: the same Forbidden as a foreign one, no NullPointerException. */
	@Test
	void previewUnknownUuidIsForbidden() throws Exception {
		AccessDeniedException forbidden = unknownUuidRefused(CallType.READ);
		AccessDeniedException thrown = assertThrows(AccessDeniedException.class,
				() -> fetcher.autoIntegrateFeatureSetPreview(uuid));
		assertSame(forbidden, thrown);
		verifyNoInteractions(releaseService);
	}

	/** (b5) The explained mutation on an unknown uuid. */
	@Test
	void explainedUnknownUuidIsForbidden() throws Exception {
		AccessDeniedException forbidden = unknownUuidRefused(CallType.WRITE);
		assertSame(forbidden, assertThrows(AccessDeniedException.class,
				() -> fetcher.autoIntegrateFeatureSetExplained(uuid)));
		verifyNoInteractions(releaseService);
	}

	/** (b6) The old mutation on an unknown uuid. */
	@Test
	void oldMutationUnknownUuidIsForbidden() throws Exception {
		AccessDeniedException forbidden = unknownUuidRefused(CallType.WRITE);
		assertSame(forbidden, assertThrows(AccessDeniedException.class, () -> fetcher.autoIntegrateFeatureSet(uuid)));
		verifyNoInteractions(releaseService);
	}
}
