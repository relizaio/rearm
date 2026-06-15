/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.HbomComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.AuthorizationService;
import io.reliza.service.HbomComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

/**
 * SAAS/OSS GraphQL surface for per-release HBOM (hardware BOM) components.
 */
@DgsComponent
public class HbomComponentDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private UserService userService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private HbomComponentService hbomComponentService;

	private ReleaseData authorizeRelease(UUID releaseUuid, CallType callType) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("Release not found: " + releaseUuid));
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.RELEASE, releaseUuid, List.of((RelizaObject) rd), callType);
		return rd;
	}

	/**
	 * Composed like SBOM components: a product release aggregates the HBOM rows
	 * of its constituent hardware component releases (tree walk, deduped).
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "hbomComponentsOfRelease")
	public List<HbomComponentData> hbomComponentsOfRelease(@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		authorizeRelease(releaseUuid, CallType.READ);
		return hbomComponentService.hbomComponentsOfReleaseTree(releaseUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "reconcileReleaseHbomComponents")
	public Boolean reconcileReleaseHbomComponents(@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		authorizeRelease(releaseUuid, CallType.WRITE);
		hbomComponentService.reconcile(releaseUuid);
		return true;
	}

	/**
	 * CDX #929 choice slots of a release tree, with their option nodes — used by
	 * the ship form (a produced lot must resolve every slot) and the repair flow
	 * (present approved alternatives instead of a single part).
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "releaseComponentChoices")
	public List<HbomComponentService.ComponentChoiceView> releaseComponentChoices(
			@InputArgument("releaseUuid") UUID releaseUuid) throws RelizaException {
		authorizeRelease(releaseUuid, CallType.READ);
		return hbomComponentService.releaseComponentChoices(releaseUuid);
	}
}
