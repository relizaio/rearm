/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.CanonicalMapFlowControl;
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
import io.reliza.model.dto.ReleaseDto;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.ws.App;

/**
 * requeueArtifactScanFanOut through DgsQueryExecutor and the real permission check: a reader of the holding
 * component is refused (Not authorized, nothing requeued), a writer of it requeues every map row of the canonical,
 * organization write covers an artifact no release holds, and an unmapped BOM answers its own error.
 */
@SpringBootTest(classes = {App.class})
public class ArtifactScanFanOutRequeueAuthorizationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AtomicInteger VERSION = new AtomicInteger();
	private static final String MUTATION =
			"mutation($a: ID!) { requeueArtifactScanFanOut(artifactUuid: $a) { state inCandidateSet candidateRule } }";
	private static final String STAMP = "2026-11-01T00:00:00Z";

	@MockitoSpyBean private UserService userService;
	@MockitoSpyBean private SyntheticSbomService syntheticSbomService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ArtifactService artifactService;
	@Autowired private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
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

	private Organization org() {
		return testInitializer.obtainOrganization();
	}

	private UserData user(Organization org, PermissionScope scope, UUID object, PermissionType type) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Requeue " + tag, "requeue-" + tag + "@scan-status.io", true,
				List.of(org.getUuid()), "requeue-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, type,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ArtifactData bom(Organization org) throws Exception {
		UUID uuid = artifactService.createArtifact(ArtifactDto.builder().org(org.getUuid()).type(ArtifactType.BOM)
				.displayIdentifier("requeue-bom-" + UUID.randomUUID()).build(), WU).getUuid();
		return artifactService.getArtifactData(uuid).orElseThrow();
	}

	private Component component(Organization org) throws Exception {
		return componentService.createComponent("requeue-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	private void releaseWith(Organization org, Component c, UUID artifact) throws Exception {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).org(org.getUuid())
				.branch(branch.getUuid()).version("1.0." + VERSION.incrementAndGet()).status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).artifacts(new ArrayList<>(List.of(artifact))).build(), WU);
	}

	/** A map row of {@code artifact} onto {@code canonical}, stamped as fanned out. */
	private void mapStamped(Organization org, UUID artifact, UUID canonical) {
		ArtifactCanonicalMap m = new ArtifactCanonicalMap();
		m.setOrg(org.getUuid());
		m.setArtifactUuid(artifact);
		m.setCanonicalArtifactUuid(canonical);
		m.setFlowControl(new CanonicalMapFlowControl(null, STAMP, null));
		artifactCanonicalMapRepository.save(m);
	}

	private CanonicalMapFlowControl flowControlOf(UUID artifact) {
		return artifactCanonicalMapRepository.findByArtifactUuid(artifact).orElseThrow().getFlowControl();
	}

	private ExecutionResult asPerson(UserData user, UUID artifact) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("requeue-scan-fan-out").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(MUTATION, Map.of("a", artifact.toString()));
	}

	private static String onlyError(ExecutionResult result) {
		List<GraphQLError> errors = result.getErrors();
		assertEquals(1, errors.size(), errors.toString());
		return errors.get(0).getMessage();
	}

	// ---- rows ---------------------------------------------------------------------------------------------

	@Test
	void aReaderOfTheHoldingComponentIsRefusedAndNothingIsRequeued() throws Exception {
		Organization org = org();
		Component c = component(org);
		ArtifactData ad = bom(org);
		releaseWith(org, c, ad.getUuid());
		mapStamped(org, ad.getUuid(), ad.getUuid());

		ExecutionResult r = asPerson(user(org, PermissionScope.COMPONENT, c.getUuid(), PermissionType.READ_ONLY),
				ad.getUuid());

		assertEquals("Not authorized", onlyError(r));
		verify(syntheticSbomService, never()).requeueFanOutForArtifact(any());
		assertEquals(STAMP, flowControlOf(ad.getUuid()).fanOutAt(), "the stamp is untouched");
	}

	@Test
	void aWriterOfTheHoldingComponentRequeuesEveryRowOfTheCanonical() throws Exception {
		Organization org = org();
		Component c = component(org);
		ArtifactData ad = bom(org);
		ArtifactData sibling = bom(org);
		releaseWith(org, c, ad.getUuid());
		mapStamped(org, ad.getUuid(), ad.getUuid());
		mapStamped(org, sibling.getUuid(), ad.getUuid());

		ExecutionResult r = asPerson(user(org, PermissionScope.COMPONENT, c.getUuid(), PermissionType.READ_WRITE),
				ad.getUuid());

		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<?, ?> status = (Map<?, ?>) ((Map<?, ?>) r.getData()).get("requeueArtifactScanFanOut");
		assertEquals(Boolean.TRUE, status.get("inCandidateSet"), String.valueOf(status));
		assertTrue(String.valueOf(status.get("candidateRule")).startsWith("requeued at "), String.valueOf(status));
		for (UUID a : List.of(ad.getUuid(), sibling.getUuid())) {
			CanonicalMapFlowControl fc = flowControlOf(a);
			assertNull(fc.fanOutAt(), "fanOutAt removed on " + a);
			assertNotNull(fc.fanOutRequeuedAt(), "fanOutRequeuedAt set on " + a);
		}
	}

	@Test
	void organizationWriteCoversAnArtifactNoReleaseHolds() throws Exception {
		Organization org = org();
		ArtifactData ad = bom(org);
		mapStamped(org, ad.getUuid(), ad.getUuid());

		ExecutionResult r = asPerson(user(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE),
				ad.getUuid());

		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		assertNull(flowControlOf(ad.getUuid()).fanOutAt());
	}

	@Test
	void anUnmappedBomAnswersThatItIsNotMappedYet() throws Exception {
		Organization org = org();
		ArtifactData ad = bom(org);

		ExecutionResult r = asPerson(user(org, PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE),
				ad.getUuid());

		assertTrue(onlyError(r).contains("not mapped"), r.getErrors().toString());
	}
}
