/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactScanState;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.ws.App;

/**
 * TPF-121 architecture-3 section 6, rows 13, 14 and 18: what a refused caller of artifactScanStatus receives
 * (through DgsQueryExecutor, the bytes the client sees), and the authorization level under it. An unknown uuid
 * and a foreign one are identical on the wire, the one error Not authorized with data null, and the explainer is
 * never reached for either.
 */
@SpringBootTest(classes = {App.class})
public class ArtifactScanStatusAuthorizationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AtomicInteger VERSION = new AtomicInteger();
	private static final String QUERY = "query($a: ID!) { artifactScanStatus(artifactUuid: $a) { state inCandidateSet } }";

	@MockitoSpyBean private UserService userService;
	@MockitoSpyBean private SyntheticSbomService syntheticSbomService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ArtifactService artifactService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
		SecurityContextHolder.clearContext();
	}

	// ---- fixture ------------------------------------------------------------------------------------------

	private Organization org(String prefix) {
		return testInitializer.obtainOrganization();
	}

	/** A member of the organization with RESOURCE READ at organization scope. */
	private UserData reader(Organization org) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Scan " + tag, "tpf121-" + tag + "@scan-status.io", true,
				List.of(org.getUuid()), "tpf121-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ArtifactData bom(Organization org) throws Exception {
		UUID uuid = artifactService.createArtifact(ArtifactDto.builder().org(org.getUuid()).type(ArtifactType.BOM)
				.displayIdentifier("tpf121-bom-" + UUID.randomUUID()).build(), WU).getUuid();
		return artifactService.getArtifactData(uuid).orElseThrow();
	}

	private Component component(Organization org) throws Exception {
		return componentService.createComponent("tpf121-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	private void releaseWith(Organization org, Component c, UUID artifact) throws Exception {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).org(org.getUuid())
				.branch(branch.getUuid()).version("1.0." + VERSION.incrementAndGet()).status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).artifacts(new ArrayList<>(List.of(artifact))).build(), WU);
	}

	private ExecutionResult asPerson(UserData user, UUID artifact) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf121-scan-status").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(QUERY, Map.of("a", artifact.toString()));
	}

	/** The one refusal the client receives: exactly one error, Not authorized, data null, no explainer call. */
	private String assertRefused(ExecutionResult result) {
		List<GraphQLError> errors = result.getErrors();
		assertEquals(1, errors.size(), errors.toString());
		String message = errors.get(0).getMessage();
		assertEquals("Not authorized", message);
		assertFalse(message.contains("Artifact not found"), message);
		Map<?, ?> data = result.getData();
		assertNull(null == data ? null : data.get("artifactScanStatus"));
		verify(syntheticSbomService, never()).explainArtifactScanStatus(any());
		return message;
	}

	// ---- rows ---------------------------------------------------------------------------------------------

	/** Row 13: an unknown uuid. */
	@Test
	void unknownUuidIsNotAuthorized() throws Exception {
		Organization orgA = org("tpf121-a");
		assertRefused(asPerson(reader(orgA), UUID.randomUUID()));
	}

	/** Row 14: a foreign uuid, with no release (organization check) and with one (any-component check). */
	@Test
	void foreignUuidReadsExactlyAsAnUnknownOne() throws Exception {
		Organization orgA = org("tpf121-a");
		Organization orgB = org("tpf121-b");
		UserData userA = reader(orgA);
		String unknown = assertRefused(asPerson(userA, UUID.randomUUID()));

		ArtifactData orphan = bom(orgB);
		String foreignOrphan = assertRefused(asPerson(userA, orphan.getUuid()));

		ArtifactData released = bom(orgB);
		releaseWith(orgB, component(orgB), released.getUuid());
		String foreignReleased = assertRefused(asPerson(userA, released.getUuid()));

		assertEquals(unknown, foreignOrphan);
		assertEquals(unknown, foreignReleased);

		// Positive control: a reader of org B gets an answer for both.
		UserData userB = reader(orgB);
		for (ArtifactData ad : List.of(orphan, released)) {
			ExecutionResult ok = asPerson(userB, ad.getUuid());
			assertTrue(ok.getErrors().isEmpty(), ok.getErrors().toString());
			Map<?, ?> data = ok.getData();
			Map<?, ?> status = (Map<?, ?>) data.get("artifactScanStatus");
			assertNotNull(status);
			Set<String> states = Set.copyOf(Arrays.stream(ArtifactScanState.values()).map(Enum::name).toList());
			assertTrue(states.contains((String) status.get("state")), String.valueOf(status));
		}
	}

	/** Row 18: the read checks refuse a foreign artifact with AccessDeniedException Forbidden. */
	@Test
	void readChecksRefuseAForeignArtifact() throws Exception {
		Organization orgA = org("tpf121-a");
		Organization orgB = org("tpf121-b");
		UserData userA = reader(orgA);
		ArtifactData artifactOfB = bom(orgB);
		Component componentOfB = component(orgB);

		AccessDeniedException byOrg = assertThrows(AccessDeniedException.class,
				() -> authorizationService.isUserAuthorizedForObjectGraphQL(userA, PermissionFunction.RESOURCE,
						PermissionScope.ORGANIZATION, orgB.getUuid(), List.of(artifactOfB), CallType.READ));
		assertEquals("Forbidden", byOrg.getMessage());
		AccessDeniedException byComponent = assertThrows(AccessDeniedException.class,
				() -> authorizationService.isUserAuthorizedForAnyObjectGraphQL(userA, PermissionFunction.RESOURCE,
						PermissionScope.COMPONENT, Set.of(componentOfB.getUuid()), List.of(artifactOfB),
						CallType.READ));
		assertEquals("Forbidden", byComponent.getMessage());

		// Positive control: the same calls for an artifact and a component of org A.
		ArtifactData artifactOfA = bom(orgA);
		Component componentOfA = component(orgA);
		assertTrue(AuthorizationResponse.isAllowed(authorizationService.isUserAuthorizedForObjectGraphQL(userA,
				PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgA.getUuid(), List.of(artifactOfA),
				CallType.READ)));
		assertTrue(AuthorizationResponse.isAllowed(authorizationService.isUserAuthorizedForAnyObjectGraphQL(userA,
				PermissionFunction.RESOURCE, PermissionScope.COMPONENT, Set.of(componentOfA.getUuid()),
				List.of(artifactOfA), CallType.READ)));
	}
}
