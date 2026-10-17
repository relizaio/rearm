/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.service.AgentBoardService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserGroupService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Board grants through real principals and the permission mutation (board-permissions.md §2.3,
 * task ffb564ce): a team's grant reaches its member, a USER key sees no more than its owner holds,
 * a FREEFORM key its own grants, and the API key permission mutation stores a BOARD grant on a
 * board of the organization and refuses any other object. Only who is signed in is stubbed.
 */
@SpringBootTest(classes = {App.class})
public class BoardPermissionIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private UserGroupService userGroupService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private AgentBoardData board(Organization org) throws RelizaException {
		Component target = componentService.createComponent("perm_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), "perm-" + UUID.randomUUID(), "permissions",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
	}

	private UserData member(Organization org, PermissionType orgLevel) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Member " + tag, "member-" + tag + "@boards.io", true, List.of(org.getUuid()),
				"member-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				orgLevel, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A team (user group) whose one grant is BOARD_READ on the board, with the user in it. */
	private void teamGrantingRead(Organization org, UserData user, AgentBoardData bd) throws RelizaException {
		UserGroupData ugd = userGroupService.createUserGroup(CreateUserGroupDto.builder()
				.name("board-readers-" + UUID.randomUUID()).org(org.getUuid()).build(), WU);
		userGroupService.updateUserGroupComprehensive(UpdateUserGroupDto.builder()
				.groupId(ugd.getUuid()).manualUsers(Set.of(user.getUuid())).status(UserGroupStatus.ACTIVE)
				.permissions(List.of(UserGroupPermissionDto.builder().scope(PermissionScope.BOARD)
						.objectId(bd.getUuid()).type(PermissionType.READ_ONLY)
						.functions(List.of(PermissionFunction.BOARD_READ)).build()))
				.build(), WU);
	}

	private static PermissionDto boardGrant(Organization org, UUID board, PermissionType type, PermissionFunction... fns) {
		return new PermissionDto(org.getUuid(), PermissionScope.BOARD, board, type, Set.of(fns), null);
	}

	private static AuthHeaderParse as(ApiKey key) {
		return AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12");
	}

	// ---------- 5. principals ----------

	@Test
	public void aTeamsBoardGrantReachesItsMember() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		UserData user = member(org, PermissionType.READ_ONLY);
		assertFalse(authorizationService.boardPermission(user, org.getUuid(), a.getUuid(), PermissionFunction.BOARD_READ, CallType.READ),
				"the org-level tier alone reads no board");

		teamGrantingRead(org, user, a);
		assertTrue(authorizationService.boardPermission(user, org.getUuid(), a.getUuid(), PermissionFunction.BOARD_READ, CallType.READ));
		assertFalse(authorizationService.boardPermission(user, org.getUuid(), b.getUuid(), PermissionFunction.BOARD_READ, CallType.READ));
		assertEquals(Set.of(PermissionFunction.BOARD_READ), authorizationService.boardFunctions(user, org.getUuid(), a.getUuid()));
	}

	@Test
	public void aUserKeySeesItsOwnersBoardGrantsAndNothingMore() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		UserData owner = member(org, PermissionType.READ_ONLY);
		teamGrantingRead(org, owner, a);
		ApiKey key = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "personal", WU);
		// Stored as asked, unclamped: the call-time intersection is what is under test.
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), PermissionType.READ_ONLY, List.of(boardGrant(org, a.getUuid(),
				PermissionType.READ_WRITE, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_WRITE)), WU);

		assertTrue(authorizationService.boardPermission(as(key), org.getUuid(), a.getUuid(), PermissionFunction.BOARD_READ, CallType.READ),
				"the owner reads the board, and so does the key");
		assertFalse(authorizationService.boardPermission(as(key), org.getUuid(), a.getUuid(), PermissionFunction.BOARD_WRITE, CallType.WRITE),
				"the key carries BOARD_WRITE, its owner does not");
	}

	@Test
	public void aFreeformKeyHoldsItsOwnBoardGrants() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData b = board(org);
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "board agent", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), PermissionType.READ_ONLY, List.of(boardGrant(org, a.getUuid(),
				PermissionType.READ_ONLY, PermissionFunction.BOARD_AGENT)), WU);

		assertTrue(authorizationService.boardPermission(as(key), org.getUuid(), a.getUuid(), PermissionFunction.BOARD_AGENT, CallType.READ));
		assertTrue(authorizationService.boardPermission(as(key), org.getUuid(), a.getUuid(), PermissionFunction.BOARD_READ, CallType.READ),
				"BOARD_AGENT implies BOARD_READ");
		assertFalse(authorizationService.boardPermission(as(key), org.getUuid(), a.getUuid(), PermissionFunction.BOARD_WRITE, CallType.WRITE));
		assertFalse(authorizationService.boardPermission(as(key), org.getUuid(), b.getUuid(), PermissionFunction.BOARD_AGENT, CallType.READ));
	}

	// ---------- 6. the permission mutation ----------

	private void signInAs(UserData admin) {
		doReturn(Optional.of(admin)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("board-permissions-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	private ExecutionResult setKeyPermissions(ApiKey key, UUID object) {
		return dgsQueryExecutor.execute("""
				mutation perms($key: ID!, $perms: [PermissionInput]) {
					setPermissionsOnFreeformApiKey(apiKeyUuid: $key, permissionType: READ_ONLY, permissions: $perms) { uuid }
				}""", Map.of("key", key.getUuid().toString(), "perms", List.of(Map.of(
						"scope", "BOARD", "object", object.toString(), "type", "READ_ONLY",
						"functions", List.of("BOARD_READ")))));
	}

	private Set<UserPermission> keyGrants(ApiKey key, Organization org) {
		return ApiKeyData.dataFromRecord(apiKeyService.getApiKey(key.getUuid()).orElseThrow())
				.getPermissions(org.getUuid()).getOrgPermissionsAsSet(org.getUuid());
	}

	@Test
	public void thePermissionMutationStoresABoardGrantAndRefusesAnyOtherObject() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData a = board(org);
		AgentBoardData elsewhere = board(testInitializer.obtainOrganization());
		signInAs(member(org, PermissionType.ADMIN));
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "granted by hand", WU);

		ExecutionResult ok = setKeyPermissions(key, a.getUuid());
		assertTrue(ok.getErrors().isEmpty(), ok.getErrors().toString());
		assertTrue(keyGrants(key, org).stream().anyMatch(p -> PermissionScope.BOARD == p.getScope()
				&& a.getUuid().equals(p.getObject()) && p.getFunctions().contains(PermissionFunction.BOARD_READ)));

		for (UUID bad : List.of(elsewhere.getUuid(), UUID.randomUUID())) {
			ExecutionResult refused = setKeyPermissions(key, bad);
			assertFalse(refused.getErrors().isEmpty(), "a BOARD grant on " + bad + " must be refused");
			assertTrue(refused.getErrors().toString().contains("not a board of this organization"), refused.getErrors().toString());
			assertTrue(keyGrants(key, org).stream().noneMatch(p -> bad.equals(p.getObject())), "and nothing stored");
		}
	}

	// ---------- RD2-9: a document component's board link only where it opens ----------

	@Autowired private io.reliza.service.AgentDocumentService agentDocumentService;

	@SuppressWarnings("unchecked")
	private Object boardRefReadable(UUID component) {
		ExecutionResult r = dgsQueryExecutor.execute("query c($c: ID!) { component(componentUuid: $c) { agentBoard { uuid readable } } }",
				Map.of("c", component.toString()));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> c = (Map<String, Object>) ((Map<String, Object>) r.getData()).get("component");
		return ((Map<String, Object>) c.get("agentBoard")).get("readable");
	}

	@Test
	public void aDocumentComponentsBoardIsReadableOnlyToWhoMayReadTheBoard() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		AgentBoardData bd = board(org);
		UUID doc = agentDocumentService.createDocumentComponentIsolated(bd, io.reliza.model.RearmSpecificationType.ARCHITECTURE, WU);
		agentBoardService.recordDocumentComponent(bd.getUuid(), io.reliza.model.RearmSpecificationType.ARCHITECTURE, doc, WU);

		signInAs(member(org, PermissionType.ADMIN));
		assertEquals(Boolean.TRUE, boardRefReadable(doc), "an org admin reads every board");

		UserData reader = member(org, PermissionType.READ_ONLY);
		teamGrantingRead(org, reader, bd);
		signInAs(reader);
		assertEquals(Boolean.TRUE, boardRefReadable(doc), "BOARD_READ on the board");

		signInAs(member(org, PermissionType.READ_ONLY));
		assertEquals(Boolean.FALSE, boardRefReadable(doc), "the component reads, the board does not (D18 stands)");
	}
}
