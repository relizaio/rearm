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
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.TeaProfileResolver;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaProfileService;
import io.reliza.service.TeaProfileService.TeaOrgDiscovery;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * GraphQL CRUD of TEA profiles (task TEA-2). Reads need READ on the scope's object, writes ADMIN
 * on it, and a PUBLIC profile also needs organization ADMIN at every scope: the check is on the
 * saved value, so a non-admin cannot edit a PUBLIC row at all until an admin makes it PRIVATE.
 * An object of another organization answers "not found", so its uuid reveals nothing.
 */
@DgsComponent
public class TeaProfileDataFetcher {

	@Autowired private UserService userService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private TeaProfileService teaProfileService;
	@Autowired private TeaProfileResolver teaProfileResolver;

	/** Everything the profile editor needs in one round trip. */
	public record TeaProfileEditorView(TeaProfileData stored, TeaResolvedProfile effective, TeaResolvedProfile parent,
			int publishedReleases, SupportInjectionSetting supportInjection) {}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "teaProfile")
	public TeaProfileData teaProfile(@InputArgument("org") UUID org, @InputArgument("scope") TeaProfileScope scope,
			@InputArgument("object") UUID object) throws RelizaException {
		authorize(currentUser(), org, scope, object, CallType.READ);
		return teaProfileService.getProfile(org, scope, object).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "teaProfileEditorView")
	public TeaProfileEditorView teaProfileEditorView(@InputArgument("org") UUID org,
			@InputArgument("scope") TeaProfileScope scope, @InputArgument("object") UUID object) throws RelizaException {
		authorize(currentUser(), org, scope, object, CallType.READ);
		return new TeaProfileEditorView(teaProfileService.getProfile(org, scope, object).orElse(null),
				teaProfileResolver.effectiveOfScope(org, scope, object),
				teaProfileResolver.parentOfScope(org, scope, object),
				teaProfileService.countPublishedReleasesResolvingTo(org, scope, object),
				teaProfileResolver.supportInjectionOf(org));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "teaProfilesOfOrg")
	public List<TeaProfileData> teaProfilesOfOrg(@InputArgument("org") UUID org) throws RelizaException {
		authorize(currentUser(), org, TeaProfileScope.ORGANIZATION, null, CallType.READ);
		return teaProfileService.listProfilesOfOrg(org);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "teaOrgDiscovery")
	public TeaOrgDiscovery teaOrgDiscovery(@InputArgument("org") UUID org) throws RelizaException {
		authorize(currentUser(), org, TeaProfileScope.ORGANIZATION, null, CallType.READ);
		return teaProfileService.orgDiscovery(org);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "saveTeaProfile")
	public TeaProfileData saveTeaProfile(@InputArgument("org") UUID org, @InputArgument("scope") TeaProfileScope scope,
			@InputArgument("object") UUID object, @InputArgument("profile") Map<String, Object> profile)
			throws RelizaException {
		UserData ud = currentUser();
		authorize(ud, org, scope, object, CallType.ADMIN);
		TeaProfileInputDto incoming = null == profile ? null : Utils.OM.convertValue(profile, TeaProfileInputDto.class);
		if (null != incoming && TeaVisibility.PUBLIC == incoming.getVisibility()
				&& !authorizationService.isUserAuthorizedForObject(ud, PermissionFunction.RESOURCE,
						PermissionScope.ORGANIZATION, org, List.of(organization(org)), CallType.ADMIN)) {
			throw new RelizaException("Only an organization admin can save a PUBLIC TEA profile; "
					+ "an admin can make it PRIVATE first");
		}
		return teaProfileService.saveProfile(org, scope, object, incoming, WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteTeaProfile")
	public Boolean deleteTeaProfile(@InputArgument("org") UUID org, @InputArgument("scope") TeaProfileScope scope,
			@InputArgument("object") UUID object) throws RelizaException {
		UserData ud = currentUser();
		authorize(ud, org, scope, object, CallType.ADMIN);
		return teaProfileService.deleteProfile(org, scope, object, WhoUpdated.getWhoUpdated(ud));
	}

	private UserData currentUser() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new RelizaException("User not found"));
	}

	private OrganizationData organization(UUID org) throws RelizaException {
		if (null == org) throw new RelizaException("org is required");
		return getOrganizationService.getOrganizationData(org)
				.orElseThrow(() -> new RelizaException("Organization not found: " + org));
	}

	/** READ or ADMIN on the scope's object, after checking the object belongs to {@code org}. */
	private void authorize(UserData ud, UUID org, TeaProfileScope scope, UUID object, CallType ct)
			throws RelizaException {
		if (null == scope) throw new RelizaException("scope is required");
		OrganizationData od = organization(org);
		switch (scope) {
			case ORGANIZATION -> authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
					PermissionScope.ORGANIZATION, org, List.of(od), ct);
			case PERSPECTIVE -> {
				if (null == object) throw new RelizaException("object is required for a PERSPECTIVE TEA profile");
				if (!ossPerspectiveService.teaPerspectivesSupported()) {
					throw new RelizaException("PERSPECTIVE TEA profiles need perspectives, which are a ReARM Pro feature");
				}
				Optional<RelizaObject> ro = ossPerspectiveService.teaPerspectiveObject(object);
				if (ro.isEmpty() || !org.equals(ro.get().getOrg())) {
					throw new RelizaException("Perspective not found: " + object);
				}
				authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
						PermissionScope.PERSPECTIVE, object, List.of(ro.get()), ct);
			}
			case COMPONENT -> {
				if (null == object) throw new RelizaException("object is required for a COMPONENT TEA profile");
				ComponentData cd = getComponentService.getComponentData(object)
						.filter(c -> org.equals(c.getOrg()))
						.orElseThrow(() -> new RelizaException("Component not found: " + object));
				authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
						PermissionScope.COMPONENT, object, List.of(cd), ct);
			}
		}
	}
}
