/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.Team;
import io.reliza.model.TeamData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateTeamDto;
import io.reliza.model.dto.TeamWebDto;
import io.reliza.model.dto.UpdateTeamDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.TeamService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

/**
 * GraphQL surface for {@link Team}.
 *
 * <p>Everything here is ORG-ADMIN gated, exactly like the UserGroup surface it
 * sits beside. Delegated administration by a team lead is Phase 3 and needs a
 * permission model of its own: {@code TeamData.leads} is stored and validated
 * today but nothing reads it for authorization, and nothing here should start
 * doing so informally.
 *
 * <p>Referential validation deliberately lives in {@link TeamService}, not in
 * this fetcher, so a programmatic caller is held to the same rules.
 */
@Slf4j
@DgsComponent
public class TeamDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private TeamService teamService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	/** Authorize the caller as an admin of {@code orgUuid} and return their WhoUpdated. */
	private WhoUpdated authorizeOrgAdmin(UUID orgUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> ood = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = ood.isPresent() ? ood.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ADMIN);
		return WhoUpdated.getWhoUpdated(oud.get());
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "getTeams")
	public List<TeamWebDto> getTeams(@InputArgument("org") UUID org) throws RelizaException {
		authorizeOrgAdmin(org);
		return teamService.getAllTeamsByOrganization(org).stream()
				.map(TeamData::toWebDto)
				.toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createTeam")
	public TeamWebDto createTeam(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> teamInputMap = dfe.getArgument("team");
		CreateTeamDto createDto = Utils.OM.convertValue(teamInputMap, CreateTeamDto.class);
		if (null == createDto.getOrg()) throw new RelizaException("Team organization is required");
		WhoUpdated wu = authorizeOrgAdmin(createDto.getOrg());
		return TeamData.toWebDto(teamService.createTeam(createDto, wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateTeam")
	public TeamWebDto updateTeam(DgsDataFetchingEnvironment dfe) throws RelizaException {
		Map<String, Object> teamInputMap = dfe.getArgument("team");
		UpdateTeamDto updateDto = Utils.OM.convertValue(teamInputMap, UpdateTeamDto.class);
		if (null == updateDto.getTeamId()) throw new RelizaException("teamId is required");

		// Authorize against the org of the row being edited, resolved from the
		// STORED record rather than anything the caller sent. Trusting an org from
		// the input would let a caller who administers org A edit a team in org B
		// by naming A.
		Optional<TeamData> otd = teamService.getTeamData(updateDto.getTeamId());
		if (otd.isEmpty()) throw new RelizaException("Team not found: " + updateDto.getTeamId());
		WhoUpdated wu = authorizeOrgAdmin(otd.get().getOrg());

		return TeamData.toWebDto(teamService.updateTeam(updateDto, wu));
	}
}
