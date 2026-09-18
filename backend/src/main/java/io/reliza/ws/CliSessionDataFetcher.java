/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.ws;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKeyData;
import io.reliza.model.CliSession;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.CliSessionService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;

/** Browser side of the CLI login (user JWT): see the pending request, approve or deny it, manage sessions. */
@DgsComponent
public class CliSessionDataFetcher {

	@Autowired private CliSessionService cliSessionService;
	@Autowired private UserService userService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private GetOrganizationService getOrganizationService;

	private UserData me() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new AccessDeniedException("Not authorized"));
	}

	/** The pending request behind the code the user typed or followed; null when unknown or expired. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "cliLoginRequest")
	public CliSession cliLoginRequest(@InputArgument("userCode") String userCode) throws RelizaException {
		me();
		authorizationService.validateSystemOperational(CallType.READ);
		return cliSessionService.pending(userCode).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "approveCliLogin")
	public CliSession approveCliLogin(@InputArgument("userCode") String userCode, @InputArgument("apiKeyUuid") String apiKeyUuidStr,
			@InputArgument("createKeyOrgUuid") String createOrgStr, @InputArgument("createKeyNotes") String createNotes,
			@InputArgument("permissionType") PermissionType permissionType,
			@InputArgument("permissions") List<java.util.LinkedHashMap<String, Object>> permissions) throws RelizaException {
		UserData ud = me();
		authorizationService.validateSystemOperational(CallType.WRITE);
		if (apiKeyUuidStr != null && !apiKeyUuidStr.isBlank()) {
			return cliSessionService.approveWithKey(userCode, ud, UUID.fromString(apiKeyUuidStr));
		}
		if (createOrgStr == null || createOrgStr.isBlank()) throw new RelizaException("Choose a key, or an organization to create a personal key in");
		UUID orgUuid = UUID.fromString(createOrgStr);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		if (od.isEmpty()) throw new RelizaException("Organization not found");
		// Membership is the gate here, not a level: a member whose grants are all object-scoped has no
		// organization-wide level to check against, and the key they make can only carry what they hold.
		if (!ud.isGlobalAdmin() && !ud.getOrganizations().contains(orgUuid))
			throw new AccessDeniedException("Not a member of this organization");
		// the key starts empty; what the approver asked for is stored reduced to what they hold themselves.
		// Everything that can reject the request runs before the session is approved, and the writes that
		// follow share one transaction, so a rejected set never leaves an approved session with a useless key.
		List<PermissionDto> requested = permissions == null ? List.of()
				: permissions.stream().map(m -> Utils.OM.convertValue(m, PermissionDto.class)).toList();
		for (PermissionDto pd : requested) {
			if (pd.approvals() != null && !pd.approvals().isEmpty() && !Utils.isSanitizedApprovalsSent(pd.approvals(), od.get()))
				throw new RelizaException("Invalid approvals sent");
		}
		AuthorizationService.ClampedPermissions clamped = authorizationService.clampToOwner(ud, orgUuid, permissionType, requested);
		return cliSessionService.approveWithNewKey(userCode, ud, orgUuid, createNotes, clamped.orgType(), clamped.permissions(),
				WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "denyCliLogin")
	public Boolean denyCliLogin(@InputArgument("userCode") String userCode) throws RelizaException {
		UserData ud = me();
		authorizationService.validateSystemOperational(CallType.WRITE);
		return cliSessionService.deny(userCode, ud);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "myCliSessions")
	public List<CliSession> myCliSessions() throws RelizaException {
		UserData ud = me();
		authorizationService.validateSystemOperational(CallType.READ);
		return cliSessionService.listMine(ud.getUuid());
	}

	/** Org admins: the active sessions riding a key of their org. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "cliSessionsOfKey")
	public List<CliSession> cliSessionsOfKey(@InputArgument("apiKeyUuid") String apiKeyUuidStr) throws RelizaException {
		UserData ud = me();
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		ApiKeyData akd = apiKeyService.getApiKeyData(apiKeyUuid).orElseThrow(() -> new AccessDeniedException("Not authorized"));
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION,
				akd.getOrg(), List.of(akd), CallType.ADMIN);
		return cliSessionService.listByKey(apiKeyUuid);
	}

	/** The session's own user, or an org admin of the session's org. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "revokeCliSession")
	public Boolean revokeCliSession(@InputArgument("uuid") String uuidStr) throws RelizaException {
		UserData ud = me();
		CliSession s = cliSessionService.get(UUID.fromString(uuidStr)).orElseThrow(() -> new RelizaException("CLI session not found"));
		if (!ud.getUuid().equals(s.getUser())) {
			OrganizationData od = (s.getOrg() == null ? Optional.<OrganizationData>empty() : getOrganizationService.getOrganizationData(s.getOrg()))
					.orElseThrow(() -> new AccessDeniedException("Not authorized"));
			authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, s.getOrg(), List.of(od), CallType.ADMIN);
		} else {
			authorizationService.validateSystemOperational(CallType.WRITE);
		}
		cliSessionService.revoke(s, WhoUpdated.getWhoUpdated(ud));
		return true;
	}

	/** What the CLI reported and what the server observed, split so the approver can weigh them differently. */
	public record CliDeviceInfo(String reportedHostname, String reportedOs, String reportedTimeZone, String reportedClient, String observedIp) {}

	@DgsData(parentType = "CliSession", field = "deviceInfo")
	public CliDeviceInfo deviceInfo(com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		CliSession s = dfe.getSource();
		if (s == null || s.getDeviceInfo() == null) return null;
		CliSessionService.DeviceInfo d = CliSessionService.DeviceInfo.fromMap(s.getDeviceInfo());
		return new CliDeviceInfo(d.reportedHostname(), d.reportedOs(), d.reportedTimeZone(), d.reportedClient(), d.observedIp());
	}
}
