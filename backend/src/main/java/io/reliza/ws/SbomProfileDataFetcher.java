/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.InputConversion;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SettingsScope;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.model.dto.SbomProfileInputDto;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SbomProfileResolver;
import io.reliza.service.SbomProfileResolver.SbomProfileCatalogEntry;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.service.SbomProfileService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * GraphQL CRUD of SBOM profiles (task TEA-27), the shape of {@link TeaProfileDataFetcher}: reads need
 * READ on the scope's object, writes ADMIN on it; there is no organization-admin gate, because a
 * profile carries no visibility. An object of another organization answers "not found", so its uuid
 * reveals nothing. The two programmatic reads take the organization from the verified key and
 * authorize the key per scope, as the user reads authorize the user (the per-scope key authorization
 * is written here, not extracted: the TEA publication fetcher's helper is bound to releases).
 */
@DgsComponent
public class SbomProfileDataFetcher {

	@Autowired private UserService userService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private SbomProfileService sbomProfileService;
	@Autowired private SbomProfileResolver sbomProfileResolver;

	/** Everything a profile editor needs in one round trip. */
	public record SbomProfileEditorView(SbomProfileData stored, SbomProfileResolution effective,
			SbomProfileResolution parent, SupportInjectionSetting supportInjection) {}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomProfiles")
	public List<SbomProfileData> sbomProfiles(@InputArgument("org") UUID org, @InputArgument("scope") SettingsScope scope,
			@InputArgument("object") UUID object) throws RelizaException {
		authorize(currentUser(), org, scope, object, CallType.READ);
		return sbomProfileService.listProfilesOfScope(org, scope, object);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomProfilesOfOrg")
	public List<SbomProfileData> sbomProfilesOfOrg(@InputArgument("org") UUID org) throws RelizaException {
		authorize(currentUser(), org, SettingsScope.ORGANIZATION, null, CallType.READ);
		return sbomProfileService.listProfilesOfOrg(org);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomProfile")
	public SbomProfileData sbomProfile(@InputArgument("org") UUID org, @InputArgument("scope") SettingsScope scope,
			@InputArgument("object") UUID object, @InputArgument("name") String name) throws RelizaException {
		authorize(currentUser(), org, scope, object, CallType.READ);
		return sbomProfileService.getProfile(org, scope, object, name).orElse(null);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomProfileEffective")
	public SbomProfileResolution sbomProfileEffective(@InputArgument("org") UUID org,
			@InputArgument("scope") SettingsScope scope, @InputArgument("object") UUID object,
			@InputArgument("name") String name) throws RelizaException {
		authorize(currentUser(), org, scope, object, CallType.READ);
		return sbomProfileResolver.effectiveOfScope(org, scope, object, name);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomProfileCatalog")
	public List<SbomProfileCatalogEntry> sbomProfileCatalog(@InputArgument("org") UUID org,
			@InputArgument("scope") SettingsScope scope, @InputArgument("object") UUID object) throws RelizaException {
		authorize(currentUser(), org, scope, object, CallType.READ);
		return sbomProfileResolver.catalogOfScope(org, scope, object);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomProfileEditorView")
	public SbomProfileEditorView sbomProfileEditorView(@InputArgument("org") UUID org,
			@InputArgument("scope") SettingsScope scope, @InputArgument("object") UUID object,
			@InputArgument("name") String name) throws RelizaException {
		authorize(currentUser(), org, scope, object, CallType.READ);
		return new SbomProfileEditorView(sbomProfileService.getProfile(org, scope, object, name).orElse(null),
				sbomProfileResolver.effectiveOfScope(org, scope, object, name),
				sbomProfileResolver.parentOfScope(org, scope, object, name),
				sbomProfileResolver.supportInjectionOf(org));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "saveSbomProfile")
	public SbomProfileData saveSbomProfile(@InputArgument("org") UUID org, @InputArgument("scope") SettingsScope scope,
			@InputArgument("object") UUID object, @InputArgument("name") String name,
			@InputArgument("profile") Map<String, Object> profile) throws RelizaException {
		UserData ud = currentUser();
		authorize(ud, org, scope, object, CallType.ADMIN);
		return sbomProfileService.saveProfile(org, scope, object, name, input(profile), WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteSbomProfile")
	public Boolean deleteSbomProfile(@InputArgument("org") UUID org, @InputArgument("scope") SettingsScope scope,
			@InputArgument("object") UUID object, @InputArgument("name") String name) throws RelizaException {
		UserData ud = currentUser();
		authorize(ud, org, scope, object, CallType.ADMIN);
		return sbomProfileService.deleteProfile(org, scope, object, name, WhoUpdated.getWhoUpdated(ud));
	}

	@DgsData(parentType = "Query", field = "sbomProfilesProgrammatic")
	public List<SbomProfileData> sbomProfilesProgrammatic(@InputArgument("scope") SettingsScope scope,
			@InputArgument("object") UUID object, DgsDataFetchingEnvironment dfe) throws RelizaException {
		VerifiedProgrammaticKey vk = verifiedKey(dfe, CallType.READ);
		authorizeKey(vk, scope, object);
		return sbomProfileService.listProfilesOfScope(vk.org(), scope, object);
	}

	@DgsData(parentType = "Query", field = "sbomProfileEffectiveProgrammatic")
	public SbomProfileResolution sbomProfileEffectiveProgrammatic(@InputArgument("scope") SettingsScope scope,
			@InputArgument("object") UUID object, @InputArgument("name") String name, DgsDataFetchingEnvironment dfe)
			throws RelizaException {
		VerifiedProgrammaticKey vk = verifiedKey(dfe, CallType.READ);
		authorizeKey(vk, scope, object);
		return sbomProfileResolver.effectiveOfScope(vk.org(), scope, object, name);
	}

	private static SbomProfileInputDto input(Map<String, Object> profile) {
		return null == profile ? null : InputConversion.convert(profile, SbomProfileInputDto.class, "profile");
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

	/** The scope's authorization object, after checking it belongs to {@code org}; "not found" otherwise. */
	private RelizaObject scopeObject(UUID org, SettingsScope scope, UUID object) throws RelizaException {
		if (null == scope) throw new RelizaException("scope is required");
		switch (scope) {
			case PERSPECTIVE -> {
				if (null == object) throw new RelizaException("object is required for a PERSPECTIVE SBOM profile");
				if (!ossPerspectiveService.teaPerspectivesSupported()) {
					throw new RelizaException("PERSPECTIVE SBOM profiles need perspectives, which are a ReARM Pro feature");
				}
				Optional<RelizaObject> ro = ossPerspectiveService.teaPerspectiveObject(object);
				if (ro.isEmpty() || !org.equals(ro.get().getOrg())) {
					throw new RelizaException("Perspective not found: " + object);
				}
				return ro.get();
			}
			case COMPONENT -> {
				if (null == object) throw new RelizaException("object is required for a COMPONENT SBOM profile");
				ComponentData cd = getComponentService.getComponentData(object)
						.filter(c -> org.equals(c.getOrg()))
						.orElseThrow(() -> new RelizaException("Component not found: " + object));
				return cd;
			}
			default -> {
				if (null != object) throw new RelizaException("object must be empty for an ORGANIZATION SBOM profile");
				return organization(org);
			}
		}
	}

	private static PermissionScope permissionScope(SettingsScope scope) {
		return switch (scope) {
			case ORGANIZATION -> PermissionScope.ORGANIZATION;
			case PERSPECTIVE -> PermissionScope.PERSPECTIVE;
			case COMPONENT -> PermissionScope.COMPONENT;
		};
	}

	/** READ or ADMIN on the scope's object, after checking the object belongs to {@code org}. */
	private void authorize(UserData ud, UUID org, SettingsScope scope, UUID object, CallType ct) throws RelizaException {
		organization(org);
		RelizaObject ro = scopeObject(org, scope, object);
		UUID objectUuid = SettingsScope.ORGANIZATION == scope ? org : object;
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, permissionScope(scope),
				objectUuid, List.of(ro), ct);
	}

	/**
	 * The calling key verified and the instance operational, before anything the caller named is read
	 * (the TEA-25 order of {@code TeaPublicationDataFetcher.verifiedKey}).
	 */
	private VerifiedProgrammaticKey verifiedKey(DgsDataFetchingEnvironment dfe, CallType ct) throws RelizaException {
		DgsWebMvcRequestData requestData = (DgsWebMvcRequestData) DgsContext.getRequestData(dfe);
		ServletWebRequest servletWebRequest = (ServletWebRequest) requestData.getWebRequest();
		ProgrammaticAuthContext authCtx = authorizationService.authenticateProgrammaticWithOrg(requestData.getHeaders(),
				servletWebRequest);
		AuthHeaderParse ahp = authCtx.ahp();
		if (null == ahp) throw new AccessDeniedException("Invalid authorization");
		VerifiedProgrammaticKey vk = authorizationService.verifyProgrammaticKey(ahp);
		authorizationService.validateSystemOperational(ct);
		return vk;
	}

	/**
	 * READ for the key on the scope's object of the key's own organization: the organization at
	 * ORGANIZATION scope, the perspective or the component otherwise; an object of another
	 * organization is "not found", as for a user.
	 */
	private void authorizeKey(VerifiedProgrammaticKey vk, SettingsScope scope, UUID object) throws RelizaException {
		UUID org = vk.org();
		RelizaObject ro = scopeObject(org, scope, object);
		UUID objectUuid = SettingsScope.ORGANIZATION == scope ? org : object;
		authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(vk.principal(), PermissionFunction.RESOURCE,
				permissionScope(scope), objectUuid, List.of(ro), CallType.READ);
	}
}
