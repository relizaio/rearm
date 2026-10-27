/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyGroup;
import io.reliza.model.ApiKeyGroupData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserGroupData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateApiKeyGroupDto;
import io.reliza.model.dto.CreateUserGroupDto;
import io.reliza.model.dto.UpdateApiKeyGroupDto;
import io.reliza.model.dto.UpdateUserGroupDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.repositories.ApiKeyGroupRepository;
import io.reliza.service.AgentBoardService;
import io.reliza.service.ApiKeyGroupService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuditService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserGroupService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The shared rig of the API key group tests (TPF-91, architecture-5 section 5): the
 * BoardPermissionIntegrationTest idiom (real principals through AuthHeaderParse.fromVerifiedKey, the
 * browser mutations through the executor with only who is signed in stubbed), plus group builders and
 * the direct-row plant that bypasses the service's member validation.
 */
@SpringBootTest(classes = {App.class})
abstract class ApiKeyGroupTestBase {

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	static final String CREATE = "mutation($g: CreateApiKeyGroupInput!) { createApiKeyGroup(apiKeyGroup: $g)"
			+ " { uuid name status apiKeys } }";
	static final String UPDATE = "mutation($g: UpdateApiKeyGroupInput!) { updateApiKeyGroup(apiKeyGroup: $g)"
			+ " { uuid name status apiKeys apiKeyDetails { uuid keyOrder type status notes declaredName } } }";
	static final String DELETE = "mutation($u: ID!) { deleteApiKeyGroup(groupUuid: $u) { uuid status apiKeys } }";
	static final String LIST = "query($o: ID!) { apiKeyGroupsOfOrg(org: $o) { uuid name status apiKeys } }";

	@MockitoSpyBean UserService userService;
	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired TestInitializer testInitializer;
	@Autowired AgentBoardService agentBoardService;
	@Autowired ComponentService componentService;
	@Autowired ApiKeyService apiKeyService;
	@Autowired ApiKeyGroupService apiKeyGroupService;
	@Autowired ApiKeyGroupRepository apiKeyGroupRepository;
	@Autowired UserGroupService userGroupService;
	@Autowired AuthorizationService authorizationService;
	@Autowired AuditService auditService;
	@Autowired LicenseStatus licenseStatus;

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

	// ---------- objects ----------

	AgentBoardData board(Organization org) throws RelizaException {
		Component target = componentService.createComponent("akg_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return agentBoardService.createBoard(org.getUuid(), "akg-" + UUID.randomUUID(), "api key groups",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
	}

	UserData member(Organization org, PermissionType orgLevel) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Member " + tag, "akg-" + tag + "@groups.io", true, List.of(org.getUuid()),
				"akg-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				orgLevel, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A user group whose one grant is BOARD_READ on the board, with the user in it. */
	void teamGrantingRead(Organization org, UserData user, AgentBoardData bd) throws RelizaException {
		userTeam(org, user, UserGroupPermissionDto.builder().scope(PermissionScope.BOARD).objectId(bd.getUuid())
				.type(PermissionType.READ_ONLY).functions(List.of(PermissionFunction.BOARD_READ)).build());
	}

	/** A user group with one permission and the user in it. */
	void userTeam(Organization org, UserData user, UserGroupPermissionDto permission) throws RelizaException {
		UserGroupData ugd = userGroupService.createUserGroup(CreateUserGroupDto.builder()
				.name("akg-users-" + UUID.randomUUID()).org(org.getUuid()).build(), WU);
		userGroupService.updateUserGroupComprehensive(UpdateUserGroupDto.builder()
				.groupId(ugd.getUuid()).manualUsers(Set.of(user.getUuid())).status(UserGroupStatus.ACTIVE)
				.permissions(List.of(permission)).build(), WU);
	}

	static PermissionDto boardGrant(Organization org, UUID board, PermissionType type, PermissionFunction... fns) {
		return new PermissionDto(org.getUuid(), PermissionScope.BOARD, board, type, Set.of(fns), null);
	}

	static UserGroupPermissionDto boardRead(UUID board) {
		return UserGroupPermissionDto.builder().scope(PermissionScope.BOARD).objectId(board)
				.type(PermissionType.READ_ONLY).functions(List.of(PermissionFunction.BOARD_READ)).build();
	}

	static UserGroupPermissionDto orgWide(Organization org, PermissionType type, PermissionFunction... fns) {
		return UserGroupPermissionDto.builder().scope(PermissionScope.ORGANIZATION).objectId(org.getUuid())
				.type(type).functions(List.of(fns)).build();
	}

	static UserGroupPermissionDto orgApprovals(Organization org, String... roles) {
		return UserGroupPermissionDto.builder().scope(PermissionScope.ORGANIZATION).objectId(org.getUuid())
				.type(PermissionType.NONE).approvals(List.of(roles)).build();
	}

	ApiKey freeform(Organization org, String notes) {
		return apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), notes, WU);
	}

	ApiKey userKey(Organization org, UserData owner) {
		return apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "personal", WU);
	}

	static AuthHeaderParse as(ApiKey key) {
		return AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12");
	}

	// ---------- groups ----------

	ApiKeyGroupData newGroup(Organization org) throws RelizaException {
		return apiKeyGroupService.createApiKeyGroup(CreateApiKeyGroupDto.builder()
				.name("akg-" + UUID.randomUUID()).org(org.getUuid()).build(), WU);
	}

	/** Group G with these permissions and members, through the service. */
	ApiKeyGroupData group(Organization org, Collection<UUID> members, UserGroupPermissionDto... permissions)
			throws RelizaException {
		ApiKeyGroupData g = newGroup(org);
		return apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.apiKeys(Set.copyOf(members)).permissions(Arrays.asList(permissions)).build(), WU);
	}

	ApiKeyGroupData setMembers(ApiKeyGroupData g, UUID... members) throws RelizaException {
		return apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.apiKeys(Set.of(members)).build(), WU);
	}

	ApiKeyGroupData setPermissions(ApiKeyGroupData g, UserGroupPermissionDto... permissions) throws RelizaException {
		return apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.permissions(Arrays.asList(permissions)).build(), WU);
	}

	ApiKeyGroupData restoreGroup(ApiKeyGroupData g) throws RelizaException {
		return apiKeyGroupService.updateApiKeyGroup(UpdateApiKeyGroupDto.builder().groupId(g.getUuid())
				.status(io.reliza.common.CommonVariables.ApiKeyGroupStatus.ACTIVE).build(), WU);
	}

	ApiKeyGroupData stored(ApiKeyGroupData g) {
		return apiKeyGroupService.getApiKeyGroupData(g.getUuid()).orElseThrow();
	}

	/**
	 * A key uuid written into the group's row directly, bypassing the service's member validation (the
	 * OrganizationArchiveIntegrationTest direct-row idiom): what a direct database write could plant.
	 */
	void plant(ApiKeyGroupData g, UUID keyUuid) {
		ApiKeyGroup row = apiKeyGroupRepository.findById(g.getUuid()).orElseThrow();
		ApiKeyGroupData d = ApiKeyGroupData.dataFromRecord(row);
		d.addApiKey(keyUuid);
		row.setRecordData(Utils.dataToRecord(d));
		apiKeyGroupRepository.save(row);
	}

	// ---------- the browser lane ----------

	void signInAs(UserData user) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("api-key-groups-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	ExecutionResult gqlUpdate(Map<String, Object> input) {
		return dgsQueryExecutor.execute(UPDATE, Map.of("g", input));
	}

	ExecutionResult gqlUpdateMembers(UUID group, UUID... members) {
		Map<String, Object> input = new HashMap<>();
		input.put("groupId", group.toString());
		input.put("apiKeys", Arrays.stream(members).map(UUID::toString).toList());
		return gqlUpdate(input);
	}

	ExecutionResult gqlDelete(UUID group) {
		return dgsQueryExecutor.execute(DELETE, Map.of("u", group.toString()));
	}

	ExecutionResult gqlCreate(Organization org, String name) {
		return dgsQueryExecutor.execute(CREATE, Map.of("g", Map.of("name", name, "org", org.getUuid().toString())));
	}

	ExecutionResult gqlList(Organization org) {
		return dgsQueryExecutor.execute(LIST, Map.of("o", org.getUuid().toString()));
	}

	/** The programmatic executor with the principal the filter leaves after it verified the key's bearer token. */
	ExecutionResult asKey(ApiKey key, String op, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE, as(key));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(op, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	/** The one error of a refused call. */
	static String onlyError(ExecutionResult r) {
		List<GraphQLError> errors = r.getErrors();
		assertEquals(1, errors.size(), errors.toString());
		return errors.get(0).getMessage();
	}
}
