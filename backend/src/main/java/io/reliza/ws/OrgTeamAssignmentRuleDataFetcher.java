/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.GlobalTeamAssignmentRule;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrgTeamAssignmentRuleService;
import io.reliza.service.UserService;

/**
 * Write surface for org-wide team-assignment rules (T2). Mirrors the
 * approval-policy rule fetcher: org-admin only, whole-list replace, validated
 * before persistence.
 *
 * <p>The list is replaced wholesale rather than patched per rule because ORDER
 * is the priority contract -- a per-rule edit API would make reordering an
 * awkward multi-call dance and invite races between two admins.
 */
@DgsComponent
public class OrgTeamAssignmentRuleDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private OrgTeamAssignmentRuleService teamAssignmentRuleService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setGlobalTeamAssignmentRules")
	public OrganizationData setGlobalTeamAssignmentRules(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("rules") List<Object> rules) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		List<GlobalTeamAssignmentRule> typedRules = new LinkedList<>();
		if (null != rules) {
			for (Object raw : rules) {
				typedRules.add(Utils.OM.convertValue(raw, GlobalTeamAssignmentRule.class));
			}
		}
		// Single entry point: validate-and-persist is encapsulated in the service
		// so no caller can reach the write without validation.
		return teamAssignmentRuleService.setRules(orgUuid, typedRules, wu);
	}
}
