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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactScanState;
import io.reliza.model.ReleaseData;
import io.reliza.model.ScanBlockerEnrichmentState;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.dto.ArtifactScanStatusDto;
import io.reliza.model.dto.ArtifactScanStatusDto.ScanBlockerDto;
import io.reliza.service.ArtifactDownloadAccessService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SyntheticSbomService;
import io.reliza.service.UserService;

/**
 * TPF-121 architecture-3 section 6, rows 15, 16 and 19: the artifactScanStatus fetcher asks the artifact's read
 * permission only (one reader tier), answers the system state first, and passes the explainer's answer through
 * unredacted.
 */
@ExtendWith(MockitoExtension.class)
class ArtifactDataFetcherScanStatusTest {

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
	void wireHolderResolution() {
		ArtifactDownloadAccessService access = new ArtifactDownloadAccessService();
		ReflectionTestUtils.setField(access, "authorizationService", authorizationService);
		ReflectionTestUtils.setField(access, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(fetcher, "artifactDownloadAccessService", access);
	}

	@BeforeEach
	void ownSecurityContext() {
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	private ArtifactData bom() {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", artifactUuid);
		ad.setOrg(org);
		ad.setType(ArtifactType.BOM);
		return ad;
	}

	private static ArtifactScanStatusDto dto(UUID artifact, List<ScanBlockerDto> blockers) {
		return new ArtifactScanStatusDto(artifact, "BOM", ArtifactScanState.BLOCKED_ON_COVERAGE, true,
				UUID.randomUUID(), null, null, null, null, null, null, 1, Boolean.TRUE, false, "not a candidate",
				2, 0, 0, 0, null, null, blockers.size(), false, blockers);
	}

	/** A reader of component c1: the artifact sits in a release of c1. */
	private ArtifactData componentReader() throws RelizaException {
		ArtifactData ad = bom();
		when(dfe.getArgument("artifactUuid")).thenReturn(artifactUuid.toString());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(ud));
		when(artifactService.getArtifactData(artifactUuid)).thenReturn(Optional.of(ad));
		ReleaseData rd = mock(ReleaseData.class);
		when(rd.getComponent()).thenReturn(c1);
		when(sharedReleaseService.gatherReleasesForArtifact(artifactUuid, org)).thenReturn(List.of(rd));
		return ad;
	}

	/** The two throwing checks were asked READ only, and the non-throwing tier question never. */
	private void readOnlyQuestions() throws RelizaException {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Set<UUID>> objects = ArgumentCaptor.forClass(Set.class);
		ArgumentCaptor<CallType> anyObjectCt = ArgumentCaptor.forClass(CallType.class);
		verify(authorizationService, times(1)).isUserAuthorizedForAnyObjectGraphQL(eq(ud),
				eq(PermissionFunction.RESOURCE), eq(PermissionScope.COMPONENT), objects.capture(), any(),
				anyObjectCt.capture());
		assertEquals(Set.of(c1), objects.getValue());
		assertEquals(List.of(CallType.READ), anyObjectCt.getAllValues());
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(), any());
		verify(authorizationService, never()).isUserAuthorizedForObject(any(), any(), any(), any(), any(), any());
		verify(authorizationService, times(1)).validateSystemOperational(CallType.READ);
		verify(authorizationService, never()).validateSystemOperational(CallType.ADMIN);
	}

	/** Row 15: a reader gets the explainer's answer as is; READ only, no tier question. */
	@Test
	void readerGetsTheExplainersAnswer() throws Exception {
		ArtifactData ad = componentReader();
		ArtifactScanStatusDto expected = dto(artifactUuid, List.of());
		when(syntheticSbomService.explainArtifactScanStatus(ad)).thenReturn(expected);

		assertSame(expected, fetcher.artifactScanStatus(dfe));

		verify(syntheticSbomService, times(1)).explainArtifactScanStatus(ad);
		readOnlyQuestions();
	}

	/** Row 16: the system state is answered first, before the argument, the user or any row. */
	@Test
	void systemStateFirst() throws Exception {
		RelizaException sealed = new RelizaException("System is sealed, please contact your administrator");
		doThrow(sealed).when(authorizationService).validateSystemOperational(CallType.READ);

		RelizaException thrown = assertThrows(RelizaException.class, () -> fetcher.artifactScanStatus(dfe));

		assertSame(sealed, thrown);
		verify(dfe, never()).getArgument(any());
		verify(userService, never()).getUserDataByAuth(any());
		verify(artifactService, never()).getArtifactData(any());
	}

	/** Row 19: a component reader sees the blocker rows intact; nothing is redacted on the fetcher path. */
	@Test
	void readerSeesBlockers() throws Exception {
		ArtifactData ad = componentReader();
		ZonedDateTime t7 = ZonedDateTime.of(2026, 10, 29, 9, 0, 0, 0, ZoneOffset.UTC);
		ScanBlockerDto row = new ScanBlockerDto("pkg:npm/b@1", ScanBlockerEnrichmentState.ENRICHED, null, 7,
				"SUBMITTED", t7, "bucket 7 is SUBMITTED since 2026-10-29T09:00:00Z; its components are uncovered "
						+ "until it is INGESTED");
		when(syntheticSbomService.explainArtifactScanStatus(ad)).thenReturn(dto(artifactUuid, List.of(row)));

		ArtifactScanStatusDto s = fetcher.artifactScanStatus(dfe);

		assertEquals(1, s.blockerTotal());
		assertEquals(1, s.blockers().size());
		ScanBlockerDto got = s.blockers().get(0);
		assertEquals("pkg:npm/b@1", got.purl());
		assertEquals(Integer.valueOf(7), got.bucketIndex());
		assertEquals("SUBMITTED", got.bucketIngestState());
		assertEquals(t7, got.bucketLastUpdatedDate());
		readOnlyQuestions();
	}
}
