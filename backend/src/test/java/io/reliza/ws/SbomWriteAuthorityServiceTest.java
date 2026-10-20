/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.SbomWriteAuthority;
import io.reliza.service.UserGroupService;

/**
 * SCORE-52 T-10 (ADR-2): {@code SbomWriteAuthorityService}, one case per row of
 * the principal table. A user's rights follow its combined user and group permissions; a key's
 * follow the stored key row, never the presented header; a component of another organization and a
 * missing uuid are never writable; {@code NO_PRINCIPAL} writes nothing; and asking a Free Form key
 * records no API key access.
 */
class SbomWriteAuthorityServiceTest extends SbomUpdateGuardProgrammaticTestBase {

	@MockitoSpyBean private FederatedTrustRuleService federatedTrustRuleService;
	@MockitoSpyBean private ApiKeyAccessService apiKeyAccessService;
	@Autowired private UserGroupService userGroupService;
	@Autowired private AuthorizationService authorizationService;

	private SbomWriteAuthority user(World w, UserData u) {
		return authorities.forUser(u, w.org().getUuid());
	}

	private SbomWriteAuthority key(World w, ApiKey k) {
		return authorities.forKey(AuthHeaderParse.fromVerifiedKey(k, 1, "198.51.100.52"), w.org().getUuid());
	}

	private static void assertRights(SbomWriteAuthority a, boolean orgWide, boolean writesA, boolean writesB, World w) {
		assertEquals(orgWide, a.orgWideWrite(), a.describe() + " orgWide");
		assertEquals(writesA, a.canWrite(w.a().getUuid()), a.describe() + " A");
		assertEquals(writesB, a.canWrite(w.b().getUuid()), a.describe() + " B");
	}

	private ApiKey freeform(World w, PermissionType orgType, PermissionDto... perms) throws RelizaException {
		ApiKey k = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.FREEFORM, w.org().getUuid(),
				UUID.randomUUID().toString(), "score52 free form", WU);
		apiKeyService.setPermissionsOnApiKey(k.getUuid(), orgType, List.of(perms), WU);
		return apiKeyService.getApiKey(k.getUuid()).orElseThrow();
	}

	private static PermissionDto componentWrite(UUID component) {
		return new PermissionDto(null, PermissionScope.COMPONENT, component, PermissionType.READ_WRITE,
				Set.of(PermissionFunction.RESOURCE), null);
	}

	@Test
	void usersFollowTheirCombinedPermissions() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		UserData admin = member(w.org(), PermissionScope.ORGANIZATION, org, PermissionType.ADMIN);
		assertRights(user(w, admin), true, true, true, w);
		assertRights(user(w, writer(w.org(), PermissionScope.COMPONENT, w.a().getUuid())), false, true, false, w);
		assertRights(user(w, writer(w.org(), PermissionScope.RELEASE, w.releaseA())), false, false, false, w);
		UUID branchA = branchService.getBaseBranchOfComponent(w.a().getUuid()).orElseThrow().getUuid();
		assertRights(user(w, writer(w.org(), PermissionScope.BRANCH, branchA)), false, false, false, w);
		// CE: no PERSPECTIVE case. CE has no perspectives, so a perspective grant cannot be set up
		// here (SCORE-55 3.6); Pro's copy of this test keeps it.
	}

	@Test
	void aUserWithOrganizationWriteThroughAGroupOnlyIsOrganizationWide() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		UserData plain = member(w.org(), PermissionScope.ORGANIZATION, org, PermissionType.READ_ONLY);
		UserGroupData group = userGroupService.createUserGroup(CreateUserGroupDto.builder()
				.name("score52-group-" + UUID.randomUUID()).org(org).build(), WU);
		userGroupService.updateUserGroupComprehensive(UpdateUserGroupDto.builder().groupId(group.getUuid())
				.manualUsers(Set.of(plain.getUuid())).status(UserGroupStatus.ACTIVE)
				.permissions(List.of(UserGroupPermissionDto.builder().scope(PermissionScope.ORGANIZATION).objectId(org)
						.type(PermissionType.READ_WRITE).functions(List.of(PermissionFunction.RESOURCE)).build()))
				.build(), WU);

		assertRights(user(w, userService.getUserData(plain.getUuid()).orElseThrow()), true, true, true, w);
	}

	@Test
	void aProductWriterWritesTheProductAndNotItsDependency() throws Exception {
		World w = world();
		Component product = componentService.createComponent("score52-product-" + UUID.randomUUID(),
				w.org().getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		Branch base = branchService.getBaseBranchOfComponent(product.getUuid()).orElseThrow();
		BranchData bd = BranchData.branchDataFromDbRecord(base);
		List<ChildComponent> deps = new LinkedList<>();
		deps.add(ChildComponent.builder().uuid(w.a().getUuid())
				.branch(branchService.getBaseBranchOfComponent(w.a().getUuid()).map(Branch::getUuid).orElse(null))
				.status(StatusEnum.REQUIRED).build());
		branchService.updateBranch(BranchDto.builder().uuid(bd.getUuid()).dependencies(deps).build(), WU);

		SbomWriteAuthority p = user(w, writer(w.org(), PermissionScope.COMPONENT, product.getUuid()));
		assertTrue(p.canWrite(product.getUuid()));
		assertFalse(p.canWrite(w.a().getUuid()), "a dependency of the product");
		assertFalse(p.orgWideWrite());
	}

	@Test
	void componentAndOrganizationKeysFollowTheStoredRow() throws Exception {
		World w = world();
		ApiKey keyA = componentKey(w, w.a());
		assertRights(key(w, keyA), false, true, false, w);

		// a principal whose header names B, built from A's row: the row wins
		AuthHeaderParse forged = AuthHeaderParse.builder().type(ApiTypeEnum.COMPONENT).objUuid(w.b().getUuid())
				.verifiedKeyUuid(keyA.getUuid()).build();
		assertRights(authorities.forKey(forged, w.org().getUuid()), false, true, false, w);

		ApiKey orgRw = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.ORGANIZATION_RW,
				w.org().getUuid(), null, "score52 org rw", WU);
		assertRights(key(w, orgRw), true, true, true, w);
		ApiKey orgRead = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.ORGANIZATION,
				w.org().getUuid(), null, "score52 org read", WU);
		assertRights(key(w, orgRead), false, false, false, w);
	}

	@Test
	void freeFormKeysFollowTheirOwnPermissionsAndRecordNoAccess() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		ApiKey onA = freeform(w, null, componentWrite(w.a().getUuid()));
		assertRights(key(w, onA), false, true, false, w);
		ApiKey orgWide = freeform(w, PermissionType.READ_WRITE);
		assertRights(key(w, orgWide), true, true, true, w);
		ApiKey agentOnly = freeform(w, null, new PermissionDto(null, PermissionScope.ORGANIZATION, org,
				PermissionType.ESSENTIAL_READ, Set.of(PermissionFunction.AGENT), null));
		assertRights(key(w, agentOnly), false, false, false, w);

		assertTrue(authorizationService.rbacKeyWrites(AuthHeaderParse.fromVerifiedKey(onA, 1, "198.51.100.52"), org,
				PermissionScope.COMPONENT, w.a().getUuid()));
		verify(apiKeyAccessService, never()).recordApiKeyAccess(any(), any(), any(), any());
	}

	@Test
	void userKeysAreClampedByTheirOwner() throws Exception {
		World w = world();
		UserData owner = writer(w.org(), PermissionScope.COMPONENT, w.a().getUuid());
		ApiKey personal = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, w.org().getUuid(),
				UUID.randomUUID().toString(), "score52 personal", WU);
		apiKeyService.setPermissionsOnApiKey(personal.getUuid(), null, List.of(componentWrite(w.a().getUuid())), WU);
		personal = apiKeyService.getApiKey(personal.getUuid()).orElseThrow();
		assertRights(key(w, personal), false, true, false, w);

		userService.setUserPermission(owner.getUuid(), w.org().getUuid(), PermissionScope.COMPONENT, w.a().getUuid(),
				PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE), null, WU);
		assertRights(key(w, personal), false, false, false, w);

		userService.setUserPermission(owner.getUuid(), w.org().getUuid(), PermissionScope.COMPONENT, w.a().getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.RESOURCE), null, WU);
		assertRights(key(w, personal), false, true, false, w);
		userService.softDeleteUser(owner.getUuid(), WU);
		assertRights(key(w, personal), false, false, false, w);
	}

	@Test
	void aFederatedPrincipalFollowsItsTrustRules() throws Exception {
		World w = world();
		UUID org = w.org().getUuid();
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(Provider.GITHUB_ACTIONS);
		fi.setIssuer("https://token.actions.githubusercontent.com");
		fi.setOwner("score52");
		fi.setRepository("score52/repo-" + UUID.randomUUID());
		ApiKey identity = apiKeyService.ensureFederatedIdentity(org, fi, WU);
		FederatedContext ctx = new FederatedContext(List.of(UUID.randomUUID()), "GITHUB_ACTIONS", fi.getIssuer(),
				"score52", fi.getRepository(), null, null, null, null, null, null, null, null);
		doReturn(Set.of(UserPermission.permissionFactory(org, PermissionScope.COMPONENT, w.a().getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.RESOURCE), null)))
				.when(federatedTrustRuleService).permissionsFor(eq(org), any());

		SbomWriteAuthority federated = authorities.forKey(AuthHeaderParse.fromVerifiedFederation(identity, ctx,
				"198.51.100.52"), org);
		assertRights(federated, false, true, false, w);
		// the identity row without its exchange never authenticates
		assertRights(key(w, identity), false, false, false, w);
	}

	@Test
	void nothingIsWritableAcrossOrganizationsOrWithoutAPrincipal() throws Exception {
		World w = world();
		Organization other = testInitializer.obtainOrganization();
		Component foreign = component(other);
		ApiKey foreignOrgRw = apiKeyService.createObjectApiKey(other.getUuid(), ApiTypeEnum.ORGANIZATION_RW,
				other.getUuid(), null, "score52 foreign org rw", WU);
		assertRights(key(w, foreignOrgRw), false, false, false, w);

		UserData admin = member(w.org(), PermissionScope.ORGANIZATION, w.org().getUuid(), PermissionType.ADMIN);
		ApiKey orgRw = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.ORGANIZATION_RW,
				w.org().getUuid(), null, "score52 org rw", WU);
		for (SbomWriteAuthority a : List.of(user(w, admin), key(w, orgRw), key(w, componentKey(w, w.a())),
				key(w, freeform(w, PermissionType.READ_WRITE)))) {
			assertFalse(a.canWrite(foreign.getUuid()), a.describe() + " foreign component");
			assertFalse(a.canWrite(UUID.randomUUID()), a.describe() + " missing component");
		}
		assertRights(SbomWriteAuthority.NO_PRINCIPAL, false, false, false, w);
		assertEquals("NONE", SbomWriteAuthority.NO_PRINCIPAL.describe());
	}
}
