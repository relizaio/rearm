/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactScanState;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ArtifactScanStatusDto;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SyntheticSbomService;
import io.reliza.service.UserService;

/**
 * The requeueArtifactScanFanOut fetcher: the system state first; write on a component holding the artifact in one
 * of its releases, or organization write when no release holds it (the artifactScanStatus rule at WRITE); a
 * refusal reads as an unknown uuid and requeues nothing; an authorized caller gets the scan status read after the
 * requeue.
 */
@ExtendWith(MockitoExtension.class)
class ArtifactDataFetcherScanFanOutRequeueTest {

	@Mock private UserService userService;
	@Mock private AuthorizationService authorizationService;
	@Mock private ArtifactService artifactService;
	@Mock private SharedReleaseService sharedReleaseService;
	@Mock private SyntheticSbomService syntheticSbomService;
	@InjectMocks private ArtifactDataFetcher fetcher;

	private final UUID org = UUID.randomUUID();
	private final UUID artifactUuid = UUID.randomUUID();
	private final UUID c1 = UUID.randomUUID();
	private final UserData ud = mock(UserData.class);
	private final DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);

	@BeforeEach
	void ownSecurityContext() {
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private ArtifactData bom(List<ReleaseData> holders) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", artifactUuid);
		ad.setOrg(org);
		ad.setType(ArtifactType.BOM);
		when(dfe.getArgument("artifactUuid")).thenReturn(artifactUuid.toString());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(ud));
		when(artifactService.getArtifactData(artifactUuid)).thenReturn(Optional.of(ad));
		when(sharedReleaseService.gatherReleasesForArtifact(artifactUuid, org)).thenReturn(holders);
		return ad;
	}

	private ReleaseData releaseOf(UUID component) {
		ReleaseData rd = mock(ReleaseData.class);
		when(rd.getComponent()).thenReturn(component);
		return rd;
	}

	private static ArtifactScanStatusDto requeued(UUID artifact) {
		return new ArtifactScanStatusDto(artifact, "BOM", ArtifactScanState.SCANNED, true, UUID.randomUUID(), null,
				null, null, null, null, null, 1, null, true, "requeued at ...", 2, 0, 0, 0, null, null, 0, false,
				List.of());
	}

	@Test
	void aComponentWriterRequeuesAndGetsTheStatusReadAfter() throws Exception {
		ArtifactData ad = bom(List.of(releaseOf(c1)));
		ArtifactScanStatusDto after = requeued(artifactUuid);
		when(syntheticSbomService.explainArtifactScanStatus(ad)).thenReturn(after);

		assertSame(after, fetcher.requeueArtifactScanFanOut(dfe));

		verify(authorizationService).isUserAuthorizedForAnyObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, Set.of(c1), List.of(ad), CallType.WRITE);
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(),
				any());
		InOrder order = inOrder(authorizationService, syntheticSbomService);
		order.verify(authorizationService).validateSystemOperational(CallType.WRITE);
		order.verify(authorizationService).isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), any(), any(),
				eq(CallType.WRITE));
		order.verify(syntheticSbomService).requeueFanOutForArtifact(ad);
		order.verify(syntheticSbomService).explainArtifactScanStatus(ad);
	}

	@Test
	void anArtifactNoReleaseHoldsAsksOrganizationWrite() throws Exception {
		ArtifactData ad = bom(List.of());
		when(syntheticSbomService.explainArtifactScanStatus(ad)).thenReturn(requeued(artifactUuid));

		fetcher.requeueArtifactScanFanOut(dfe);

		verify(authorizationService).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, org, List.of(ad), CallType.WRITE);
		verify(syntheticSbomService).requeueFanOutForArtifact(ad);
	}

	@Test
	void aRefusedCallerReadsAsAnUnknownUuidAndNothingIsRequeued() throws Exception {
		ArtifactData ad = bom(List.of(releaseOf(c1)));
		doThrow(new AccessDeniedException("Not authorized")).when(authorizationService)
				.isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), any(), any(), eq(CallType.WRITE));

		AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> fetcher.requeueArtifactScanFanOut(dfe));

		assertEquals("Artifact not found: " + artifactUuid, e.getMessage());
		verify(syntheticSbomService, never()).requeueFanOutForArtifact(ad);
		verify(syntheticSbomService, never()).explainArtifactScanStatus(any());
	}

	@Test
	void anUnknownUuidIsRefusedTheSameWay() throws Exception {
		when(dfe.getArgument("artifactUuid")).thenReturn(artifactUuid.toString());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(ud));
		when(artifactService.getArtifactData(artifactUuid)).thenReturn(Optional.empty());

		AccessDeniedException e = assertThrows(AccessDeniedException.class, () -> fetcher.requeueArtifactScanFanOut(dfe));

		assertEquals("Artifact not found: " + artifactUuid, e.getMessage());
		verify(syntheticSbomService, never()).requeueFanOutForArtifact(any());
	}

	@Test
	void theSystemStateIsAnsweredFirst() throws Exception {
		RelizaException sealed = new RelizaException("System is sealed, please contact your administrator");
		doThrow(sealed).when(authorizationService).validateSystemOperational(CallType.WRITE);

		assertSame(sealed, assertThrows(RelizaException.class, () -> fetcher.requeueArtifactScanFanOut(dfe)));

		verify(dfe, never()).getArgument(any());
		verify(userService, never()).getUserDataByAuth(any());
		verify(syntheticSbomService, never()).requeueFanOutForArtifact(any());
	}
}
