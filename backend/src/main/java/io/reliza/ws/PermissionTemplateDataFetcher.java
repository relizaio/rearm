/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.InputConversion;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.PermissionTemplateData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreatePermissionTemplateDto;
import io.reliza.model.dto.PermissionTemplateWebDto;
import io.reliza.model.dto.UpdatePermissionTemplateDto;
import io.reliza.model.dto.UserGroupPermissionDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.PermissionTemplateService;
import io.reliza.service.UserService;

/**
 * Permission templates on the browser lane (TPF-87, TPF-112): owner only. Each root field resolves the
 * caller and answers the system state before any argument or row is read. Create gates on membership
 * of the input's organization; update and delete answer the literal "Not authorized" for an unknown
 * template and another user's alike, and update also gates on membership of the template's
 * organization before validating grant objects or approvals against it. A template grants nothing:
 * what is approved from it goes through approveCliLogin and its clamp.
 */
@DgsComponent
public class PermissionTemplateDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private PermissionTemplateService permissionTemplateService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	private UserData caller() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new AccessDeniedException("Not authorized"));
	}

	private static boolean isMember(UserData ud, UUID org) {
		return ud.isGlobalAdmin() || ud.getOrganizations().contains(org);
	}

	/** The caller's own template; an unknown uuid and another user's answer the same "Not authorized". */
	private PermissionTemplateData ownTemplate(UserData ud, UUID uuid) {
		Optional<PermissionTemplateData> optd = permissionTemplateService.getPermissionTemplateData(uuid);
		if (optd.isEmpty() || !ud.getUuid().equals(optd.get().getUser())) throw new AccessDeniedException("Not authorized");
		return optd.get();
	}

	/** The rows as approveCliLogin reads its permissions, for assertGrantObjectsExist. */
	private static List<PermissionDto> toPermissionDtos(UUID org, Collection<UserGroupPermissionDto> rows) {
		List<PermissionDto> out = new ArrayList<>();
		if (null == rows) return out;
		for (UserGroupPermissionDto r : rows) {
			if (null == r) continue;
			out.add(new PermissionDto(org, r.getScope(), r.getObjectId(), r.getType(),
					null == r.getFunctions() ? null : new LinkedHashSet<>(r.getFunctions()), r.getApprovals()));
		}
		return out;
	}

	/** Grant objects and approvals checked exactly as approveCliLogin checks a request. */
	private void validateRows(UUID org, OrganizationData od, Collection<UserGroupPermissionDto> rows) throws RelizaException {
		List<PermissionDto> requested = toPermissionDtos(org, rows);
		authorizationService.assertGrantObjectsExist(org, requested);
		for (PermissionDto pd : requested) {
			if (pd.approvals() != null && !pd.approvals().isEmpty() && !Utils.isSanitizedApprovalsSent(pd.approvals(), od))
				throw new RelizaException("Invalid approvals sent");
		}
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "myPermissionTemplates")
	public List<PermissionTemplateWebDto> myPermissionTemplates(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2 order: the system state is answered before any argument or row is read
		authorizationService.validateSystemOperational(CallType.READ);
		UUID org = InputConversion.convert(dfe.getArgument("org"), UUID.class, "org");
		if (null != org && !isMember(ud, org)) throw new AccessDeniedException("Not authorized");
		return permissionTemplateService.listForUser(ud.getUuid(), org).stream()
				.map(PermissionTemplateData::toWebDto)
				.toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createPermissionTemplate")
	public PermissionTemplateWebDto createPermissionTemplate(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2 order: the system state is answered before any argument or row is read
		authorizationService.validateSystemOperational(CallType.WRITE);
		Map<String, Object> input = dfe.getArgument("template");
		CreatePermissionTemplateDto dto = InputConversion.convert(input, CreatePermissionTemplateDto.class, "template");
		Optional<OrganizationData> od = null == dto.getOrg() ? Optional.empty() : getOrganizationService.getOrganizationData(dto.getOrg());
		if (od.isEmpty()) throw new RelizaException("Organization not found");
		// membership is the gate, as on approveCliLogin: a template is a proposal the clamp reduces at approval
		if (!isMember(ud, dto.getOrg())) throw new AccessDeniedException("Not a member of this organization");
		validateRows(dto.getOrg(), od.get(), dto.getPermissions());
		return PermissionTemplateData.toWebDto(
				permissionTemplateService.createPermissionTemplate(dto, ud.getUuid(), WhoUpdated.getWhoUpdated(ud)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updatePermissionTemplate")
	public PermissionTemplateWebDto updatePermissionTemplate(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2 order: the system state is answered before the template is looked up, so a
		// sealed or unlicensed instance cannot tell an existing template uuid from an unknown one
		authorizationService.validateSystemOperational(CallType.WRITE);
		Map<String, Object> input = dfe.getArgument("template");
		UpdatePermissionTemplateDto dto = InputConversion.convert(input, UpdatePermissionTemplateDto.class, "template");
		PermissionTemplateData ptd = ownTemplate(ud, dto.getUuid());
		// TPF-112 F-7: a former member cannot validate grants or approvals against an organization they left
		if (!isMember(ud, ptd.getOrg())) throw new AccessDeniedException("Not authorized");
		if (null != dto.getPermissions()) {
			OrganizationData od = getOrganizationService.getOrganizationData(ptd.getOrg())
					.orElseThrow(() -> new AccessDeniedException("Not authorized"));
			validateRows(ptd.getOrg(), od, dto.getPermissions());
		}
		return PermissionTemplateData.toWebDto(
				permissionTemplateService.updatePermissionTemplate(dto, WhoUpdated.getWhoUpdated(ud)));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deletePermissionTemplate")
	public Boolean deletePermissionTemplate(DgsDataFetchingEnvironment dfe) throws RelizaException {
		UserData ud = caller();
		// TPF-91 security F-2 order: the system state is answered before the template is looked up
		authorizationService.validateSystemOperational(CallType.WRITE);
		UUID uuid = InputConversion.convert(dfe.getArgument("uuid"), UUID.class, "uuid");
		PermissionTemplateData ptd = ownTemplate(ud, uuid);
		// owner only, no membership gate: a former member can still clean up their own rows
		permissionTemplateService.deletePermissionTemplate(ptd.getUuid(), WhoUpdated.getWhoUpdated(ud));
		return true;
	}
}
