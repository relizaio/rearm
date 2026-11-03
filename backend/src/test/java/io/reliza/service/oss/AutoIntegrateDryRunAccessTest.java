/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateDependencyRow;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateReleaseSource;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.App;
import io.reliza.ws.BranchDataFetcher;

/**
 * Who sees what in the auto-integrate dry run (TPF-119 design rows 12 (a) and 17): unknown and foreign
 * feature sets are refused alike, and the preview fetcher redacts the rows of components the caller
 * cannot READ while still deciding on the full data.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class, TestAsyncConfig.class})
public class AutoIntegrateDryRunAccessTest extends AutoIntegrateDryRunTestBase {

	private static final String NOT_READABLE = "release not shown: you have no read permission on this component";

	@Autowired private AuthorizationService authorizationService;
	@Autowired private UserService userService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchDataFetcher branchDataFetcher;
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

	private record Person(String oauthId, UserData data) {}

	/** A member of the organization whose only grant is the given one. */
	private Person person(Organization org, PermissionScope scope, UUID object, PermissionType type) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String oauthId = "a-" + UUID.randomUUID();
		User u = userService.createUser("A " + tag, "a-" + tag + "@tpf119.io", true, List.of(org.getUuid()), oauthId,
				OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, type, null, null, WU);
		return new Person(oauthId, userService.getUserData(u.getUuid()).orElseThrow());
	}

	private void actAs(Person p) {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(p.oauthId()).build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	/** Row 12 (a): a feature set of another organization and an unknown uuid get the same refusal. */
	@Test
	public void row12a_crossOrgAndUnknownFeatureSetRefusedAlike() throws Exception {
		Organization orgA = testInitializer.obtainOrganization();
		Organization orgB = testInitializer.obtainOrganization();
		UserData ud = person(orgA, PermissionScope.ORGANIZATION, orgA.getUuid(), PermissionType.READ_ONLY).data();
		assertFalse(ud.isGlobalAdmin());
		BranchData bdB = read(featureSet(product(orgB), "fs"));
		AccessDeniedException foreign = assertThrows(AccessDeniedException.class,
				() -> authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
						PermissionScope.BRANCH, bdB.getUuid(), Collections.singletonList(bdB), CallType.READ));
		AccessDeniedException unknown = assertThrows(AccessDeniedException.class,
				() -> authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
						PermissionScope.BRANCH, UUID.randomUUID(), Collections.singletonList((RelizaObject) null),
						CallType.READ));
		assertEquals("Forbidden", foreign.getMessage());
		assertEquals("Forbidden", unknown.getMessage());
		assertFalse(foreign.getMessage().contains(orgB.getUuid().toString()));
		assertFalse(unknown.getMessage().contains(orgB.getUuid().toString()));
	}

	/** Row 17: rows of components the caller cannot READ are redacted; the decision is not. */
	@Test
	public void row17_unreadableComponentRowsAreRedacted() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "dryRunC1");
		Comp c2 = component(org, "dryRunC2");
		ReleaseData r1 = assembled(org, c1, "4.4.4");
		ReleaseData r2 = assembled(org, c2, "5.5.5");
		Component product = product(org);
		UUID fsUuid = featureSet(product, "fs");
		BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED),
				dep(c2, StatusEnum.REQUIRED));
		assertEquals(0, count(fsUuid));
		Person a = person(org, PermissionScope.BRANCH, fsUuid, PermissionType.READ_ONLY);
		Person b = person(org, PermissionScope.COMPONENT, product.getUuid(), PermissionType.READ_ONLY);
		ComponentData c1Data = getComponentService.getComponentData(c1.uuid()).orElseThrow();

		assertFalse(a.data().isGlobalAdmin());
		assertTrue(authorizationService.isUserAuthorizedForObject(a.data(), PermissionFunction.RESOURCE,
				PermissionScope.BRANCH, fsUuid, List.<RelizaObject>of(fs), CallType.READ));
		assertFalse(authorizationService.isUserAuthorizedForObject(a.data(), PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, c1.uuid(), List.<RelizaObject>of(c1Data), CallType.READ),
				"BRANCH READ on the feature set, no READ on the dependency: the gap");
		assertTrue(authorizationService.isUserAuthorizedForObject(b.data(), PermissionFunction.RESOURCE,
				PermissionScope.BRANCH, fsUuid, List.<RelizaObject>of(fs), CallType.READ));
		assertTrue(authorizationService.isUserAuthorizedForObject(b.data(), PermissionFunction.RESOURCE,
				PermissionScope.COMPONENT, c1.uuid(), List.<RelizaObject>of(c1Data), CallType.READ));

		actAs(a);
		AutoIntegrateEvaluation evA = branchDataFetcher.autoIntegrateFeatureSetPreview(fsUuid);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, evA.outcome());
		assertNotNull(evA.projectedVersion());
		assertEquals(List.of(c1.uuid(), c2.uuid()), rowComponents(evA));
		for (Comp c : List.of(c1, c2)) {
			AutoIntegrateDependencyRow r = row(evA, c);
			assertEquals(c.branchUuid(), r.branch().uuid());
			assertEquals(StatusEnum.REQUIRED, r.status());
			assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, r.source());
			assertNull(r.release());
			assertEquals(NOT_READABLE, r.note());
		}
		String s = evA.toString();
		assertFalse(s.contains(r1.getUuid().toString()), s);
		assertFalse(s.contains(r2.getUuid().toString()), s);
		assertFalse(s.contains("4.4.4"), s);
		assertFalse(s.contains("5.5.5"), s);
		assertThrows(AccessDeniedException.class, () -> branchDataFetcher.autoIntegrateFeatureSetExplained(fsUuid));

		actAs(b);
		AutoIntegrateEvaluation evB = branchDataFetcher.autoIntegrateFeatureSetPreview(fsUuid);
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, evB.outcome());
		assertEquals(evA.projectedVersion(), evB.projectedVersion());
		AutoIntegrateDependencyRow c1Row = row(evB, c1);
		assertEquals(r1.getUuid(), c1Row.release().uuid());
		assertEquals("4.4.4", c1Row.release().version());
		assertEquals("latest ASSEMBLED release of branch " + c1.branchName(), c1Row.note());
		AutoIntegrateDependencyRow c2Row = row(evB, c2);
		assertEquals(r2.getUuid(), c2Row.release().uuid());
		assertEquals("5.5.5", c2Row.release().version());
		assertEquals("latest ASSEMBLED release of branch " + c2.branchName(), c2Row.note());

		assertEquals(0, count(fsUuid));
		assertTrue(latestAssignment(fsUuid).isEmpty(), "nothing written, no mint");
	}
}
