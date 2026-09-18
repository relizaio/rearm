/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.exceptions.DgsEntityNotFoundException;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.AuthorizationStatus;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.common.CommonVariables.RequestType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentData.AgentType;
import io.reliza.model.AgentIdentityCredential;
import io.reliza.model.AgentIdentityData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ReleaseData;
import io.reliza.model.ApiKeyData;
import io.reliza.model.AuthPrincipal;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.common.CommonVariables.UserStatus;
import io.reliza.common.CommonVariables.ProgrammaticType;
import java.util.function.Predicate;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.ProgrammaticAuthContext;
import io.reliza.model.dto.AuthorizationResponse.AllowType;
import io.reliza.model.dto.AuthorizationResponse.ForbidType;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import io.reliza.service.oss.OssPerspectiveService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AuthorizationService {
	
	@Autowired
	private OrganizationService organizationService;
	
	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired 
	private ApiKeyAccessService apiKeyAccessService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private ApiKeyService apiKeyService;

	@Autowired
	private FederatedTrustRuleService federatedTrustRuleService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private OssPerspectiveService ossPerspectiveService;
	
	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private LicenseStatus licenseStatus;

	@Autowired
	private AgentService agentService;

	@Autowired
	private AgentSessionService agentSessionService;

	@Autowired
	private AgentIdentityService agentIdentityService;
	
	public AuthHeaderParse authenticateProgrammatic (HttpHeaders headers, ServletWebRequest servletWebRequest) {
		AuthHeaderParse ahp = null;
		Object verified = servletWebRequest.getRequest().getAttribute(io.reliza.ws.ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
		if (verified instanceof AuthHeaderParse tokenAhp) {
			return tokenAhp; // bearer access token, verified by the programmatic authentication filter
		}
		String remoteIp = servletWebRequest.getRequest().getRemoteAddr();
		if (null != headers && headers.getFirst(HttpHeaders.AUTHORIZATION) != null) {
			ahp = AuthHeaderParse.parseAuthHeader(headers, remoteIp);
		}
		return ahp;
	}

	/**
	 * Authenticate a programmatic request and resolve the effective org for the
	 * authenticated key. Use this in preference to {@link #authenticateProgrammatic}
	 * when the caller needs the org for a key type whose auth header does not
	 * embed it (notably FREEFORM). The returned context wraps the parsed
	 * {@link AuthHeaderParse} alongside the resolved org so callers do not need
	 * to mutate the AHP itself.
	 *
	 * For key types that already populate {@code ahp.getOrgUuid()}
	 * (ORGANIZATION/ORGANIZATION_RW), the context's {@code orgUuid()} matches
	 * {@code ahp.getOrgUuid()}. For FREEFORM, the org is looked up from the
	 * stored ApiKey row.
	 */
	public ProgrammaticAuthContext authenticateProgrammaticWithOrg (HttpHeaders headers, ServletWebRequest servletWebRequest) {
		AuthHeaderParse ahp = authenticateProgrammatic(headers, servletWebRequest);
		if (ahp == null) return new ProgrammaticAuthContext(null, null);
		UUID orgUuid = ahp.getOrgUuid();
		if (orgUuid == null && ahp.isRbacKey()) {
			orgUuid = apiKeyService.resolveOrgForKey(ahp);
		}
		return new ProgrammaticAuthContext(ahp, orgUuid);
	}
	
//	public AuthPrincipal authenticate (HttpHeaders headers, HttpServletRequest request, 
//			OAuth2User oauth2User, HttpServletResponse response, boolean rebuildCookie) {
//		AuthPrincipal ap = null;
//		// determine whether we are dealing with user or programmatic access
//		if (null != headers && headers.getFirst(HttpHeaders.AUTHORIZATION) != null) {
//			ap = AuthHeaderParse.parseAuthHeader(headers, request.getRemoteAddr());
//		} else {
//			// we are in manual user world
//			try {
//				ap = userService.resolveUser(null, request, oauth2User, response, rebuildCookie);
//			} catch (Exception e) {
//				log.warn("Exception on user auth", e);
//			}
//		}
//		return ap;
//	}

	public AuthorizationResponse authorize(AuthPrincipal ap, CallType ct) throws RelizaException {
		validateSystemOperational(ct);
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		UserData ud = (UserData) ap;
		if (ud.isGlobalAdmin() && ct == CallType.GLOBAL_ADMIN) {
			AuthorizationResponse.allow(ar, AllowType.OK);
		}
		if (ud.isPrimaryEmailVerified() && ct == CallType.INIT) {
			AuthorizationResponse.allow(ar, AllowType.OK);
		}
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}
	
	public AuthorizationResponse doUsersBelongToOrg (Collection<UUID> users, final UUID org) {
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.ALLOW);
		if (null != users && !users.isEmpty()) {
			Set<UUID> cleanedUsers = new HashSet<>(users);
			Iterator<UUID> cuIter = cleanedUsers.iterator();
			while (AuthorizationResponse.isAllowed(ar) && cuIter.hasNext()) {
				UUID uid = cuIter.next();
				var ud = userService.getUserDataWithOrg(uid, org);
				if (ud.isEmpty()) AuthorizationResponse.forbid(ar, "wrong users");
			}
		}
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}
	
	public AuthorizationResponse isUserAuthorizedOrgWideGraphQLWithObjects(UserData ud, Collection<RelizaObject> ros, CallType ct) throws RelizaException {
		validateSystemOperational(ct);
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		
		UUID org = getMatchingOrg(ros);

		if (null != org) {
			AuthorizationStatus as = isUserAuthorizedOrgWide(ud, org, ct);
			if (as == AuthorizationStatus.AUTHORIZED) AuthorizationResponse.allow(ar);
		}
		
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}

	/**
	 * New authorization call with RBAC
	 * @param ud
	 * @param function
	 * @param releaseData
	 * @param ros - Must contain original object 
	 * @param ct
	 * @return
	 * @throws RelizaException 
	 */
	public AuthorizationResponse isUserAuthorizedForObjectGraphQL(final UserData ud, @NonNull final PermissionFunction function,
			final PermissionScope objectType, final UUID objectUuid, Collection<RelizaObject> ros, CallType ct) throws RelizaException {
		validateSystemOperational(ct);
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (authorizesObject(ud, function, objectType, objectUuid, ros, ct)) {
			AuthorizationResponse.allow(ar);
		}
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}

	/**
	 * The same question without the refusal: does this user clear {@code ct} on this object.
	 *
	 * <p>For callers that vary what an operation does by tier rather than whether it happens at
	 * all -- a marketing-release lifecycle move is allowed to a writer and allowed to go
	 * backwards only to an administrator, so the second question has to be askable without
	 * throwing at the first person who is merely a writer.
	 */
	public boolean isUserAuthorizedForObject(final UserData ud, @NonNull final PermissionFunction function,
			final PermissionScope objectType, final UUID objectUuid, Collection<RelizaObject> ros, CallType ct) {
		return authorizesObject(ud, function, objectType, objectUuid, ros, ct);
	}

	private boolean authorizesObject(final UserData ud, @NonNull final PermissionFunction function,
			final PermissionScope objectType, final UUID objectUuid, Collection<RelizaObject> ros, CallType ct) {
		if (ud.isGlobalAdmin()) return true;
		final UUID org = getMatchingOrg(ros);
		if (null == org) return false;
		var permissions = organizationService.obtainCombinedUserOrgPermissions(ud, org)
				.getOrgPermissionsAsSet(org);
		return permissions.stream().anyMatch(x ->
			doesPermissionAuthorize(x, org, function, objectType, objectUuid, ct));
	}
	
	public AuthorizationResponse isUserAuthorizedForAnyObjectGraphQL(final UserData ud, @NonNull final PermissionFunction function,
			final PermissionScope objectType, final Set<UUID> objectUuids, Collection<RelizaObject> ros, CallType ct) throws RelizaException {
		validateSystemOperational(ct);
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (ud.isGlobalAdmin()) {
			AuthorizationResponse.allow(ar);
		}
		
		if (!AuthorizationResponse.isAllowed(ar) && null != objectUuids && !objectUuids.isEmpty()) {
			final UUID org = getMatchingOrg(ros);
			if (null != org) {
				var permissions = organizationService.obtainCombinedUserOrgPermissions(ud, org)
						.getOrgPermissionsAsSet(org);
				boolean authorized = false;
				Iterator<UUID> objectIter = objectUuids.iterator();
				while (!authorized && objectIter.hasNext()) {
					UUID objectUuid = objectIter.next();
					authorized = permissions.stream().anyMatch(x -> 
						doesPermissionAuthorize(x, org, function, objectType, objectUuid, ct));
				}
				if (authorized) {
					AuthorizationResponse.allow(ar);
				}
			}
		}
		
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}
	
	private boolean hasUserAcceptedAndVerified (UserData ud) {
		return (null != ud && ud.isPoliciesAccepted() && ud.isPrimaryEmailVerified() &&
				(StringUtils.isNotEmpty(ud.getGithubId()) || StringUtils.isNotEmpty(ud.getOauthId())));
	}
	
	/**
	 * The part of {@link #doesPermissionAuthorize} that does not depend on which object is asked
	 * for: the permission belongs to this org, carries the function the call needs and sits at or
	 * above the level the call needs. Org-wide ADMIN clears everything.
	 *
	 * Split out so that the list filters ({@link #readableComponentUuids},
	 * {@link #readableInstanceUuids}) gate a permission exactly the way the per-object check does,
	 * instead of re-deciding it. They enumerate where the check tests membership; that inversion is
	 * the only part they do on their own.
	 */
	boolean permissionClearsCall(UserPermission permission, UUID org, @NonNull PermissionFunction function, CallType ct) {
		if (!permission.getOrg().equals(org)) {
			return false;
		}
		if (ct == CallType.GLOBAL_ADMIN) {
			return false;
		}

		if (permission.getScope() == PermissionScope.ORGANIZATION && permission.getType() == PermissionType.ADMIN) {
			return true;
		}

		if (function != PermissionFunction.RESOURCE && (null == permission.getFunctions() || !permission.getFunctions().contains(function))) {
			return false;
		}

		PermissionType resolvedPt = PermissionType.mapFromCallType(ct);
		if (null == resolvedPt) {
			return false;
		}

		return permission.getType().ordinal() >= resolvedPt.ordinal();
	}

	private boolean doesPermissionAuthorize(UserPermission permission, UUID org, @NonNull PermissionFunction function, PermissionScope objectType, UUID objectUuid, CallType ct) {
		if (null == objectUuid) {
			return false;
		}

		if (!permissionClearsCall(permission, org, function, ct)) {
			return false;
		}
		
		if (permission.getScope() == PermissionScope.ORGANIZATION) {
			return true;
		}
		
		if (objectType.ordinal() > permission.getScope().ordinal()) {
			return false;
		}
		
		if (objectType == permission.getScope() && permission.getObject().equals(objectUuid)) {
			return true;
		}
		
		return doesPermissionScopeContainObject(permission, org, objectType, objectUuid, PermissionType.mapFromCallType(ct));

	}

	/**
	 * Checks if a permission's scope covers a given release, ignoring functions and permission type.
	 * Org-wide permissions always cover any release in that org.
	 * Perspective/component-scoped permissions cover the release if the release's component
	 * is contained within the permission's scope hierarchy.
	 * Release-scoped permissions cover the release if the object UUID matches.
	 * @param permission the user permission to check
	 * @param org the organization UUID
	 * @param releaseUuid the release UUID to check coverage for
	 * @return true if the permission covers the release
	 */
	public boolean doesPermissionCoverRelease(UserPermission permission, UUID org, UUID releaseUuid) {
		if (!permission.getOrg().equals(org)) {
			return false;
		}
		if (permission.getScope() == PermissionScope.ORGANIZATION) {
			return true;
		}
		if (permission.getScope() == PermissionScope.RELEASE && permission.getObject().equals(releaseUuid)) {
			return true;
		}
		return doesPermissionScopeContainObject(permission, org, PermissionScope.RELEASE, releaseUuid, null);
	}

	/**
	 * Resolve the components covered by a permission for a given call.
	 *
	 * scope=PERSPECTIVE always cascades through the perspective's members
	 * (and, when the perspective UUID is itself a PRODUCT, through the
	 * product's dependency tree) regardless of call type — the perspective
	 * scope's whole purpose is to grant joint management.
	 *
	 * scope=COMPONENT pointing at a PRODUCT-typed component is more
	 * restrictive: read calls still see the dependency cascade (so
	 * granting product-READ lets you read every component the product
	 * pulls in), but write/admin calls only authorize the product itself.
	 * Cascading WRITE through dependencies would let a product-write
	 * grant edit access to shared components owned by other teams; users
	 * who want that should grant via PERSPECTIVE scope instead.
	 *
	 * resolvedPt may be null (e.g. {@link #doesPermissionCoverRelease}
	 * which ignores permission type by design) — null is treated as a
	 * read-equivalent and gets the full cascade.
	 */
	private boolean doesPermissionScopeContainObject (UserPermission permission, UUID org, PermissionScope objectType, UUID objectUuid, PermissionType resolvedPt) {
		return doComponentsContainObject(componentsInScope(permission, org, resolvedPt), org, objectType, objectUuid);
	}

	/**
	 * The components a single permission covers, following the cascade described above. Shared by
	 * the per-object check and by {@link #readableComponentUuids} so both expand a grant the same way.
	 */
	private List<ComponentData> componentsInScope (UserPermission permission, UUID org, PermissionType resolvedPt) {
		List<ComponentData> authorizedComponents = new LinkedList<>();
		if (permission.getScope() == PermissionScope.PERSPECTIVE) {
			var opd = ossPerspectiveService.getPerspectiveData(permission.getObject());
			if (opd.isEmpty() || !org.equals(opd.get().getOrg())) {
				log.error("Empty or wrong match for user permission with org = {}, requested under org = {} with permission object = {}",
						permission.getOrg(), org, permission.getObject());
				return List.of();
			}
			authorizedComponents = getComponentService.listComponentsByPerspective(permission.getObject());
		} else if (permission.getScope() == PermissionScope.COMPONENT) {
			var ocd = getComponentService.getComponentData(permission.getObject());
			if (ocd.isEmpty() || !org.equals(ocd.get().getOrg())) {
				log.error("Empty or wrong match for user permission with org = {}, requested under org = {} with permission object = {}",
						permission.getOrg(), org, permission.getObject());
				return List.of();
			}
			authorizedComponents.add(ocd.get());
			boolean isProduct = ocd.get().getType() == ComponentData.ComponentType.PRODUCT;
			boolean writeOrAdminCall = resolvedPt != null
					&& resolvedPt.ordinal() >= PermissionType.READ_WRITE.ordinal();
			if (!(isProduct && writeOrAdminCall)) {
				var childCompList = getComponentService.listComponentsByProduct(permission.getObject());
				authorizedComponents.addAll(childCompList);
			}
		}
		return authorizedComponents;
	}
	
	private boolean doComponentsContainObject (List<ComponentData> authorizedComponents, UUID org, PermissionScope objectType, UUID objectUuid) {
		if (null == authorizedComponents || authorizedComponents.isEmpty()) {
			return false;
		}
		switch (objectType) {
		case RELEASE:
			return doComponentsContainRelease(authorizedComponents, org, objectUuid);
		case BRANCH:
			return doComponentsContainBranch(authorizedComponents, objectUuid);
		case COMPONENT:
			return doComponentsContainComponent(authorizedComponents, objectUuid);
		default:
			return false;
		}
	}
	
	private boolean doComponentsContainRelease (List<ComponentData> authorizedComponents, @NonNull UUID org, @NonNull UUID releaseUuid) {
		var ord = sharedReleaseService.getReleaseData(releaseUuid, org);
		if (ord.isEmpty()) {
			return false;
		}
		UUID releaseComponent = ord.get().getComponent();
		return authorizedComponents.stream().anyMatch(x -> x.getUuid().equals(releaseComponent));
	}
	
	private boolean doComponentsContainBranch (List<ComponentData> authorizedComponents, @NonNull UUID branchUuid) {
		var obd = branchService.getBranchData(branchUuid);
		if (obd.isEmpty()) {
			return false;
		}
		UUID branchComponent = obd.get().getComponent();
		return authorizedComponents.stream().anyMatch(x -> x.getUuid().equals(branchComponent));
	}
	
	private boolean doComponentsContainComponent (List<ComponentData> authorizedComponents, @NonNull UUID componentUuid) {
		var ocd = getComponentService.getComponentData(componentUuid);
		if (ocd.isEmpty()) {
			return false;
		}
		return authorizedComponents.stream().anyMatch(x -> x.getUuid().equals(componentUuid));
	}
	
	public AuthorizationStatus isUserAuthorizedOrgWide(UserData ud, UUID org, CallType ct) {
		AuthorizationStatus as = AuthorizationStatus.AUTHORIZED;
		boolean authorized = false;
		try {
			Optional<OrganizationData> od = Optional.empty();
			if (null != org) od = getOrganizationService.getOrganizationData(org);
			
			authorized = (od.isPresent() && null != ud && ud.isGlobalAdmin());
			boolean acceptedAndVerified = hasUserAcceptedAndVerified(ud);
			// special case for init call
			if (!authorized && od.isPresent() && ct == CallType.INIT && acceptedAndVerified) {
					authorized = true;
			}
			if (!authorized && od.isPresent() && acceptedAndVerified) {
				// for now, all permissions are only resolved on org level - TODO - allow by resource group
				Optional<UserPermission> oup = organizationService.obtainUserOrgPermission(ud, org);
				switch (ct) {
				case ADMIN:
					if (oup.isPresent() && oup.get().getType() == PermissionType.ADMIN) {
						authorized = true;
					}
					break;
				case WRITE:
					if (oup.isPresent() && oup.get().getType().ordinal() >= PermissionType.READ_WRITE.ordinal()) {
						authorized = true;
					}
					break;
				case READ:
					if ((oup.isPresent() && oup.get().getType().ordinal() >= PermissionType.READ_ONLY.ordinal())) {
						authorized = true;
					}
					break;
				case ESSENTIAL_READ:
					if ((oup.isPresent() && oup.get().getType().ordinal() >= PermissionType.ESSENTIAL_READ.ordinal())) {
						authorized = true;
					}
					break;
				case GLOBAL_ADMIN:
				case INIT:
					authorized = true;
					break;
				}
			}
		} catch (Exception e) {
			log.warn("Exception when trying to authorize user, deem as not authorized", e);
			authorized = false;
		}
		if (!authorized) {
			as = AuthorizationStatus.FORBIDDEN;
		}
		return as;
	}
	
	private boolean isUserAuthorizedOrgWide(UserData ud, UUID org, HttpServletResponse response, CallType ct) {
		AuthorizationStatus as = isUserAuthorizedOrgWide(ud, org, ct);
		boolean authorized = (as == AuthorizationStatus.AUTHORIZED);
		if (!authorized) {
			try {
				if (!response.isCommitted()) response.sendError(HttpStatus.FORBIDDEN.value(), "You do not have permissions to this resource");
			} catch (IOException e) {
				log.error("IO error when sending response", e);
				// re-throw
				throw new IllegalStateException("IO error when sending error response");
			}
		}
		return authorized;
	}


	
	public UUID getMatchingOrg (Collection<RelizaObject> ros) {
		UUID org = null;
		boolean orgMatch = (null != ros && !ros.isEmpty());
		if (orgMatch) {
			var rosIter = ros.iterator();
			while (orgMatch && rosIter.hasNext()) {
				var ro = rosIter.next();
				if (null == org && null != ro) org = ro.getOrg();
				if (null == ro || null == org || !org.equals(ro.getOrg())) orgMatch = false; 
			}
		}
		if (!orgMatch) org = null;
		return org;
	}
	
	public RelizaObject resolveRelizaObjectFromApiId (@NonNull AuthHeaderParse ahp, String classType) {
		RelizaObject ro = null;
		if (classType.equals(CommonVariables.COMPONENT_FIELD)
				&& ApiTypeEnum.COMPONENT == ahp.getType()) {
			Optional<ComponentData> ocd = getComponentService.getComponentData(ahp.getObjUuid());
			ro = ocd.isPresent() ? ocd.get() : null;
		} else if (classType.equals(CommonVariables.ORGANIZATION_FIELD)) {
			Optional<OrganizationData> od = getOrganizationService.getOrganizationData(ahp.getObjUuid());
			ro = od.isPresent() ? od.get() : null;
		}
		return ro;
	}
	
	public AuthorizationResponse isApiKeyAuthorized(AuthHeaderParse ahp, List<ApiTypeEnum> supportedApiTypes, UUID org, 
			CallType ct, RelizaObject ro) throws RelizaException {
		validateSystemOperational(ct);
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.ALLOW);
		UUID matchingKey = null;
		
		if (ahp == null) {
			AuthorizationResponse.forbid(ar, "Invalid authorization type");
		}
		
		if (AuthorizationResponse.isAllowed(ar) && !supportedApiTypes.contains(ahp.getType())) {
			AuthorizationResponse.forbid(ar, "Unsupported object type");
		}
		
		if (AuthorizationResponse.isAllowed(ar)) {
			boolean orgMatch = (null != ro && null != org && org.equals(ro.getOrg()));
			if (!orgMatch) {
				AuthorizationResponse.forbid(ar, "Org mismatch");
				if (null != ro) log.error("SECURITY: org mismatch, requested org = " + org + " , ro org = " + ro.getOrg());
			}
		}
		
		if (AuthorizationResponse.isAllowed(ar)) {
			matchingKey = isProgrammaticAccessAuthorized(ahp, ct);
			if (null == matchingKey) {
				AuthorizationResponse.forbid(ar, "Key unauthorized");
			}
		}
		
		if (AuthorizationResponse.isAllowed(ar)) confirmApiKeyAccess(ar, ahp, matchingKey, ro, ct);
		
		if (AuthorizationResponse.isAllowed(ar)) {
			WhoUpdated wu = WhoUpdated.getApiWhoUpdated(matchingKey, ahp.getRemoteIp());
			ar.setWhoUpdated(wu);
		}
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}
	
	/**
	 * Checks in-memory license and sealed status before any authorization.
	 * Global admin calls are always allowed (needed for unsealing and license upload).
	 * @throws RelizaException 
	 * @throws AccessDeniedException if system is sealed or license is invalid
	 */
	public void validateSystemOperational(CallType ct) throws RelizaException {
		if (ct == CallType.GLOBAL_ADMIN || ct == CallType.INIT) {
			return;
		}
		if (licenseStatus.isSystemSealed()) {
			throw new RelizaException("System is sealed. Please unseal the system first.");
		}
		if (!licenseStatus.isLicenseValid()) {
			throw new RelizaException("License is invalid or expired. Please upload a valid license.");
		}
	}

	public void gqlValidateAuthorizationResponse(AuthorizationResponse ar) {
		if (!AuthorizationResponse.isAllowed(ar)) {
			if (ar.getHttpStatus() == HttpStatus.FORBIDDEN || ar.getHttpStatus() == HttpStatus.EXPECTATION_FAILED) {
				throw new AccessDeniedException(ar.getMessage());
			} else if (ar.getHttpStatus() == HttpStatus.NOT_FOUND) {
				throw new DgsEntityNotFoundException(ar.getMessage());
			} else {
				throw new RuntimeException(ar.getMessage());
			}
		}
	}
	
	private void confirmApiKeyAccess(AuthorizationResponse ar, AuthHeaderParse ahp, UUID apiKeyId, RelizaObject ro, CallType ct) {
		Optional<ApiKeyDto> oakd = apiKeyService.getApiKeyDto(apiKeyId);
		if (oakd.isEmpty()) {
			AuthorizationResponse.forbid(ar, ForbidType.EXPECTATION_FAILED, "Invalid Api Key");
		}
		if (AuthorizationResponse.isAllowed(ar) && oakd.isPresent()) {
			// confirm if ro and api key have matching organizations
			ApiKeyDto akd = oakd.get();
			if (!ro.getOrg().equals(akd.getOrg())) {
				AuthorizationResponse.forbid(ar, "Org mismatch");
				log.error("SECURITY: org mismatch, api key org={} ro org={} ro uuid={}",
						akd.getOrg(), ro.getOrg(), ro.getUuid());
			}
			
			if (akd.getType() == ApiTypeEnum.ORGANIZATION && ct == CallType.READ && akd.getObject().equals(ro.getOrg())) {
				// authorized
			} else if (akd.getType() == ApiTypeEnum.ORGANIZATION_RW && akd.getObject().equals(ro.getOrg())) {
				// authorized
			} else if (akd.getType() == ahp.getType() && akd.getObject().equals(ro.getUuid())) {
				// authorized
			} else {
				AuthorizationResponse.forbid(ar, "Api Key missing permissions");
			}
		}
	}

	public UUID isProgrammaticAccessAuthorized(AuthHeaderParse ahp, CallType ct) {
		return isProgrammaticAccessAuthorized(ahp, null, RequestType.GRAPHQL, ct);
	}
	
	public UUID isProgrammaticAccessAuthorized(AuthHeaderParse ahp, HttpServletResponse response, CallType ct) {
		return isProgrammaticAccessAuthorized(ahp, response, RequestType.REST, ct);
	}
	
	/**
	 * 
	 * @param ahp
	 * @param response
	 * @param rt
	 * @return if authorized, returns matching key UUID, otherwise returns null
	 */
	public UUID isProgrammaticAccessAuthorized(AuthHeaderParse ahp, HttpServletResponse response, RequestType rt, CallType ct) {
		UUID matchingKeyId = null;;
		String apiKey = ahp.getApiKey();
		if (StringUtils.isNotEmpty(apiKey) || ahp.getVerifiedKeyUuid() != null) matchingKeyId = apiKeyService.isMatchingApiKey(ahp);
		if (null == matchingKeyId) {
			log.warn("SECURITY: programmatic auth failed for type={} obj={} keyId={} ip={}",
					ahp.getType(), ahp.getObjUuid(), ahp.getApiKeyId(), ahp.getRemoteIp());
			try {
				if (rt == RequestType.REST) {
					response.sendError(HttpStatus.FORBIDDEN.value(), "You do not have permissions to this resource");
				}
			} catch (IOException e) {
				log.error("IO error when sending response", e);
				// re-throw
				throw new RuntimeException("IO error when sending error response");
			}
		}

		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(matchingKeyId);
		if(oakd.isPresent()){
			ApiKeyData akd = oakd.get();
			apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), akd.getOrg(), ahp.getApiKeyId());

		}
		return matchingKeyId;
	}
	
	public record FreeformKeyVerification(WhoUpdated whoUpdated, UUID orgUuid, UUID apiKeyUuid) {}

	/**
	 * Permissions a personal key may store: the requested set reduced to what its owner holds right
	 * now. The organization-wide level drops to the owner's, functions the owner lacks are removed,
	 * object permissions fall to the highest level the owner has on that object (through the same
	 * cascade the runtime check uses) and are dropped when the owner cannot read the object at all.
	 * Approvals pass through; they are validated by the caller. Call-time intersection stays the
	 * second line, so nothing here is security-critical, it keeps the stored ceiling honest.
	 */
	public record ClampedPermissions(PermissionType orgType, List<PermissionDto> permissions, List<String> reductions) {}

	private static final PermissionType[] LEVELS = { PermissionType.ADMIN, PermissionType.READ_WRITE, PermissionType.READ_ONLY, PermissionType.ESSENTIAL_READ };

	private static CallType callTypeOf(PermissionType t) {
		return switch (t) {
			case ADMIN -> CallType.ADMIN;
			case READ_WRITE -> CallType.WRITE;
			case READ_ONLY -> CallType.READ;
			case ESSENTIAL_READ -> CallType.ESSENTIAL_READ;
			default -> null;
		};
	}

	public ClampedPermissions clampToOwner(UserData owner, UUID org, PermissionType requestedOrgType, List<PermissionDto> requested) {
		Permissions ownerPerms = organizationService.obtainCombinedUserOrgPermissions(owner, org);
		return clamp(ownerPerms.getOrgPermissionsAsSet(org), owner.isGlobalAdmin(), owner.getUuid(), org, requestedOrgType, requested);
	}

	/** The clamp over an explicit owner permission set; the entry point above resolves the set. Package-private for tests. */
	ClampedPermissions clamp(Set<UserPermission> ownerSet, boolean globalAdmin, UUID ownerUuid, UUID org, PermissionType requestedOrgType, List<PermissionDto> requested) {
		List<String> reductions = new java.util.ArrayList<>();
		Optional<UserPermission> ownerOrg = ownerSet.stream().filter(p -> p.getScope() == PermissionScope.ORGANIZATION && org.equals(p.getObject())).findFirst();
		boolean ownerAdmin = globalAdmin || ownerOrg.map(p -> p.getType() == PermissionType.ADMIN).orElse(false);
		PermissionType orgType = requestedOrgType == null ? PermissionType.NONE : requestedOrgType;
		if (!ownerAdmin) {
			PermissionType ownerLevel = ownerOrg.map(UserPermission::getType).orElse(PermissionType.NONE);
			if (orgType.ordinal() > ownerLevel.ordinal()) {
				reductions.add("organization-wide " + orgType + " reduced to " + ownerLevel);
				orgType = ownerLevel;
			}
		}
		List<PermissionDto> out = new java.util.ArrayList<>();
		for (PermissionDto p : requested == null ? List.<PermissionDto>of() : requested) {
			if (p.scope() == null || p.type() == null || p.type() == PermissionType.NONE) continue;
			Set<PermissionFunction> wanted = p.functions() == null ? Set.of() : p.functions();
			if (p.scope() == PermissionScope.ORGANIZATION) {
				if (orgType == PermissionType.NONE) { reductions.add("organization-wide permission dropped"); continue; }
				Set<PermissionFunction> allowed = ownerAdmin ? wanted : intersect(wanted, ownerOrg.map(UserPermission::getFunctions).orElse(Set.of()));
				if (!allowed.equals(wanted)) reductions.add("organization-wide functions reduced to " + allowed);
				out.add(new PermissionDto(org, PermissionScope.ORGANIZATION, org, orgType, allowed, p.approvals()));
				continue;
			}
			if (p.object() == null) continue;
			PermissionType granted = null;
			Set<PermissionFunction> ownerFunctions = new java.util.LinkedHashSet<>();
			for (PermissionType level : LEVELS) {
				if (level.ordinal() > p.type().ordinal()) continue;
				CallType ct = callTypeOf(level);
				List<UserPermission> covering = ownerSet.stream()
						.filter(o -> doesPermissionAuthorize(o, org, PermissionFunction.RESOURCE, p.scope(), p.object(), ct)).toList();
				if (!covering.isEmpty()) {
					granted = level;
					for (UserPermission o : covering) {
						if (o.getScope() == PermissionScope.ORGANIZATION && o.getType() == PermissionType.ADMIN) ownerFunctions.addAll(wanted);
						else if (o.getFunctions() != null) ownerFunctions.addAll(o.getFunctions());
					}
					break;
				}
			}
			if (ownerAdmin) { granted = p.type(); ownerFunctions.addAll(wanted); }
			if (granted == null) { reductions.add(p.scope() + " " + p.object() + " dropped: owner cannot read it"); continue; }
			if (granted != p.type()) reductions.add(p.scope() + " " + p.object() + " " + p.type() + " reduced to " + granted);
			Set<PermissionFunction> allowed = intersect(wanted, ownerFunctions);
			if (!allowed.equals(wanted)) reductions.add(p.scope() + " " + p.object() + " functions reduced to " + allowed);
			out.add(new PermissionDto(org, p.scope(), p.object(), granted, allowed, p.approvals()));
		}
		if (!reductions.isEmpty()) log.info("personal key permissions clamped to owner {} in org {}: {}", ownerUuid, org, reductions);
		return new ClampedPermissions(orgType, out, reductions);
	}

	private static Set<PermissionFunction> intersect(Set<PermissionFunction> a, Set<PermissionFunction> b) {
		Set<PermissionFunction> r = new java.util.LinkedHashSet<>(a);
		r.retainAll(b == null ? Set.of() : b);
		return r;
	}

	/**
	 * The components a user may read, the way the runtime check sees them: everything with
	 * organization-wide read, otherwise component grants plus the components of granted
	 * perspectives and, for read-level product grants, the components the product pulls in.
	 * Empty means "filter to nothing"; null means "no filter, sees all".
	 */
	public Set<UUID> readableComponentUuids(UserData ud, UUID org) {
		if (ud.isGlobalAdmin()) return null;
		Permissions perms = organizationService.obtainCombinedUserOrgPermissions(ud, org);
		return readableComponentUuids(perms.getOrgPermissionsAsSet(org), org);
	}

	/** The set-only half, so it can be driven from a test without a database. */
	Set<UUID> readableComponentUuids(Set<UserPermission> set, UUID org) {
		Set<UUID> out = new java.util.HashSet<>();
		for (UserPermission p : set) {
			if (!permissionClearsCall(p, org, PermissionFunction.RESOURCE, CallType.READ)) continue;
			if (p.getScope() == PermissionScope.ORGANIZATION) return null;
			componentsInScope(p, org, PermissionType.READ_ONLY).forEach(c -> out.add(c.getUuid()));
		}
		return out;
	}

	/**
	 * Instance uuids this user may read, gated the way the per-instance check gates them: a grant
	 * counts only if it clears a DEVOPS_READ read call, so an instance-scoped grant without that
	 * function lists nothing, exactly as it authorizes nothing. Null means "no filter, sees all".
	 */
	public Set<UUID> readableInstanceUuids(UserData ud, UUID org) {
		if (ud.isGlobalAdmin()) return null;
		Permissions perms = organizationService.obtainCombinedUserOrgPermissions(ud, org);
		return readableInstanceUuids(perms.getOrgPermissionsAsSet(org), org);
	}

	/** The set-only half, so it can be driven from a test without a database. */
	Set<UUID> readableInstanceUuids(Set<UserPermission> set, UUID org) {
		Set<UUID> out = new java.util.HashSet<>();
		for (UserPermission p : set) {
			if (!permissionClearsCall(p, org, PermissionFunction.DEVOPS_READ, CallType.READ)) continue;
			if (p.getScope() == PermissionScope.ORGANIZATION) return null;
			if (p.getScope() == PermissionScope.INSTANCE) out.add(p.getObject());
		}
		return out;
	}

	/** A key carrying its own permission set: FREEFORM, or USER whose set is a ceiling over the owner's. */
	private record RbacKey(UUID keyUuid, ApiKeyData akd, UUID orgUuid, UserData owner, ApiTypeEnum type, FederatedContext federation) {}

	private RbacKey resolveRbacKey(AuthHeaderParse ahp) {
		if (ahp == null || !ahp.isRbacKey())
			throw new AccessDeniedException("FREEFORM or USER API key required");
		UUID matchingKeyId = apiKeyService.isMatchingApiKey(ahp);
		if (matchingKeyId == null)
			throw new AccessDeniedException("Invalid API key");
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(matchingKeyId);
		if (oakd.isEmpty())
			throw new AccessDeniedException("API key data not found");
		ApiKeyData akd = oakd.get();
		UserData owner = null;
		if (ahp.getType() == ApiTypeEnum.USER) {
			UUID ownerUuid = apiKeyService.getApiKey(matchingKeyId).map(io.reliza.model.ApiKey::getObjectUuid).orElse(null);
			owner = ownerUuid == null ? null : userService.getUserData(ownerUuid).orElse(null);
			boolean member = owner != null && owner.getStatus() == UserStatus.ACTIVE
					&& (owner.isGlobalAdmin() || owner.getOrganizations().contains(akd.getOrg()));
			if (!member) {
				log.warn("SECURITY: user key {} refused, owner {} is not an active member of org {}", matchingKeyId, ownerUuid, akd.getOrg());
				throw new AccessDeniedException("Owner of this user key is not an active member of the organization");
			}
		}
		if (ahp.getType() == ApiTypeEnum.FEDERATED && !ahp.isFederated()) {
			// an identity row never authenticates on its own: only an access token from a federated exchange carries it
			log.warn("SECURITY: federated identity {} presented without a federated exchange", matchingKeyId);
			throw new AccessDeniedException("Federated identities authenticate through the token endpoint only");
		}
		return new RbacKey(matchingKeyId, akd, akd.getOrg(), owner, ahp.getType(), ahp.getFederation());
	}

	/** The key's own permissions in its org: the whole story for FREEFORM, the ceiling for USER. */
	private Set<UserPermission> keyPermissions(RbacKey key) {
		if (key.type() == ApiTypeEnum.FEDERATED) {
			// nothing is stored on the identity row: the trust rules and the calling repository decide on every call
			return federatedTrustRuleService.permissionsFor(key.orgUuid(), key.federation());
		}
		return key.akd().getPermissions(key.orgUuid()).getOrgPermissionsAsSet(key.orgUuid());
	}

	/**
	 * USER keys: the owner's current permissions (groups included) form the other side of the
	 * intersection, so either side can be lowered at any time and the lowest wins. Empty means
	 * unconstrained: FREEFORM keys, or a global-admin owner (the ceiling still applies).
	 */
	private Optional<Permissions> ownerSide(RbacKey key) {
		if (key.owner() == null || key.owner().isGlobalAdmin()) return Optional.empty();
		return Optional.of(organizationService.obtainCombinedUserOrgPermissions(key.owner(), key.orgUuid()));
	}

	private boolean ownerAlsoAuthorizes(Optional<Permissions> ownerSide, Predicate<Permissions> test) {
		return ownerSide.isEmpty() || test.test(ownerSide.get());
	}

	/** Same object, same call: the key's set and (for USER keys) the owner's set must both authorize it. */
	private boolean bothAuthorize(RbacKey key, Optional<Permissions> ownerSide, UUID org, PermissionFunction function,
			PermissionScope objectType, UUID objectUuid, CallType ct) {
		boolean keyOk = keyPermissions(key).stream().anyMatch(x -> doesPermissionAuthorize(x, org, function, objectType, objectUuid, ct));
		return keyOk && ownerAlsoAuthorizes(ownerSide, p -> p.getOrgPermissionsAsSet(org).stream()
				.anyMatch(x -> doesPermissionAuthorize(x, org, function, objectType, objectUuid, ct)));
	}

	/** Actions through a USER key are attributed to the owner; FREEFORM keys stay attributed to the key. */
	private WhoUpdated whoUpdatedFor(RbacKey key, String ip) {
		return whoUpdatedFor(key, ip, null);
	}

	/** As above; a CLI-session actor (the user who logged in) is recorded on the write when present. */
	private WhoUpdated whoUpdatedFor(RbacKey key, String ip, UUID actorUser) {
		WhoUpdated wu = key.owner() != null
				? WhoUpdated.getWhoUpdated(ProgrammaticType.API, key.owner().getUuid(), ip)
				: WhoUpdated.getApiWhoUpdated(key.keyUuid(), ip);
		return actorUser != null ? wu.withActor(actorUser) : wu;
	}

	public FreeformKeyVerification verifyFreeformKeyForPermissionFunctions(AuthHeaderParse ahp,
			Set<PermissionFunction> requiredFunctions) throws RelizaException {
		return verifyFreeformKeyForPermissionFunctions(ahp, requiredFunctions, true);
	}

	public FreeformKeyVerification verifyFreeformKeyForPermissionFunctions(AuthHeaderParse ahp,
			Set<PermissionFunction> requiredFunctions, boolean recordAccess) throws RelizaException {
		validateSystemOperational(CallType.WRITE);
		RbacKey key = resolveRbacKey(ahp);
		UUID matchingKeyId = key.keyUuid();
		UUID orgUuid = key.orgUuid();
		Optional<UserPermission> oup = keyPermissions(key).stream()
				.filter(p -> p.getScope() == PermissionScope.ORGANIZATION && orgUuid.equals(p.getObject())).findFirst();
		if (oup.isEmpty())
			throw new AccessDeniedException("No org-wide permission on this key");
		UserPermission up = oup.get();
		if (up.getType().ordinal() < PermissionType.READ_WRITE.ordinal())
			throw new AccessDeniedException("Key requires org-wide READ_WRITE or ADMIN permission");
		if (!up.getFunctions().containsAll(requiredFunctions))
			throw new AccessDeniedException("Key missing required permission functions: " + requiredFunctions);
		// USER key: the owner must hold the same org-wide level and functions right now (org ADMIN covers every function)
		boolean ownerOk = ownerAlsoAuthorizes(ownerSide(key), p -> p.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid)
				.filter(o -> o.getType().ordinal() >= PermissionType.READ_WRITE.ordinal())
				.filter(o -> o.getType() == PermissionType.ADMIN || (o.getFunctions() != null && o.getFunctions().containsAll(requiredFunctions)))
				.isPresent());
		if (!ownerOk)
			throw new AccessDeniedException("Key owner lacks the org-wide permission or functions this key would need: " + requiredFunctions);
		if (recordAccess)
			apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), orgUuid, ahp.getApiKeyId());
		return new FreeformKeyVerification(whoUpdatedFor(key, ahp.getRemoteIp(), ahp.getActorUser()), orgUuid, matchingKeyId);
	}

	/**
	 * A federated identity creating a component: allowed only for its own repository (the VCS URI
	 * of the new component must be the calling repository) and only when a template rule that
	 * admitted it grants creation. Static permissions play no part; this is the one place where a
	 * TEMPLATE identity acts org-wide, and it is bound to the repository the token names.
	 */
	public FreeformKeyVerification isFederatedKeyAuthorizedToCreateUnderVcs(AuthHeaderParse ahp, UUID orgUuid, String vcsUri,
			Collection<RelizaObject> ros) throws RelizaException {
		validateSystemOperational(CallType.WRITE);
		RbacKey key = resolveRbacKey(ahp);
		if (!ahp.isFederatedIdentity()) throw new AccessDeniedException("Not a federated identity");
		final UUID org = getMatchingOrg(ros);
		boolean authorized = org != null && org.equals(key.orgUuid()) && org.equals(orgUuid)
				&& federatedTrustRuleService.mayCreateComponentUnder(org, key.federation(), vcsUri);
		if (!authorized) {
			log.warn("SECURITY: federated identity {} refused to create a component under {} in org {}", ahp.getFederation().repository(), vcsUri, orgUuid);
			throw new AccessDeniedException("This identity may only create components bound to its own repository, and only when a trust rule allows it");
		}
		apiKeyAccessService.recordApiKeyAccess(key.keyUuid(), ahp.getRemoteIp(), key.orgUuid(), ahp.getApiKeyId());
		return new FreeformKeyVerification(whoUpdatedFor(key, ahp.getRemoteIp(), ahp.getActorUser()), key.orgUuid(), key.keyUuid());
	}

	public FreeformKeyVerification isFreeformKeyAuthorizedForAnyObjectGraphQL(AuthHeaderParse ahp,
			PermissionFunction function, PermissionScope objectType, Set<UUID> objectUuids,
			Collection<RelizaObject> ros) throws RelizaException {
		validateSystemOperational(CallType.READ);
		RbacKey key = resolveRbacKey(ahp);
		UUID matchingKeyId = key.keyUuid();
		UUID orgUuid = key.orgUuid();

		boolean authorized = false;
		if (objectUuids != null && !objectUuids.isEmpty()) {
			final UUID org = getMatchingOrg(ros);
			if (org != null) {
				Optional<Permissions> ownerSide = ownerSide(key);
				Iterator<UUID> objectIter = objectUuids.iterator();
				while (!authorized && objectIter.hasNext()) {
					UUID objectUuid = objectIter.next();
					authorized = bothAuthorize(key, ownerSide, org, function, objectType, objectUuid, CallType.READ);
				}
			}
		}
		if (!authorized)
			throw new AccessDeniedException("FreeForm key not authorized for this resource");

		apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), orgUuid, ahp.getApiKeyId());
		return new FreeformKeyVerification(whoUpdatedFor(key, ahp.getRemoteIp(), ahp.getActorUser()), orgUuid, matchingKeyId);
	}

	/**
	 * Single-object FREEFORM authorization with arbitrary CallType. Mirrors
	 * {@link #isUserAuthorizedForObjectGraphQL(UserData, PermissionFunction, PermissionScope, UUID, Collection, CallType)}
	 * but for FREEFORM API keys. Org is derived from {@code ros} via {@link #getMatchingOrg(Collection)},
	 * which also enforces that all supplied RelizaObjects belong to the same org as the key.
	 */
	public FreeformKeyVerification isFreeformKeyAuthorizedForObjectGraphQL(AuthHeaderParse ahp,
			@NonNull PermissionFunction function, PermissionScope objectType, UUID objectUuid,
			Collection<RelizaObject> ros, CallType ct) throws RelizaException {
		validateSystemOperational(ct);
		RbacKey key = resolveRbacKey(ahp);
		UUID matchingKeyId = key.keyUuid();
		UUID orgUuid = key.orgUuid();

		boolean authorized = false;
		if (objectUuid != null) {
			final UUID org = getMatchingOrg(ros);
			if (org != null && org.equals(orgUuid)) {
				authorized = bothAuthorize(key, ownerSide(key), org, function, objectType, objectUuid, ct);
			}
		}
		if (!authorized)
			throw new AccessDeniedException("FreeForm key not authorized for this resource");

		apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), orgUuid, ahp.getApiKeyId());
		return new FreeformKeyVerification(whoUpdatedFor(key, ahp.getRemoteIp(), ahp.getActorUser()), orgUuid, matchingKeyId);
	}

	/**
	 * Authorization check for an AI agent reading state attributed to
	 * its own session — release lookups, session show, anything where
	 * the agent should only see what it produced.
	 *
	 * <p>Chain of trust (all four checks must pass; any failure throws
	 * {@link AccessDeniedException} and is logged SECURITY-level):
	 * <ol>
	 *   <li>The calling FREEFORM key carries
	 *       {@code PermissionFunction.AGENT} at {@code ORGANIZATION}
	 *       scope (the persistent grant on the key).</li>
	 *   <li>The session identified by {@code sessionUuid} or
	 *       {@code clientSessionId} exists.</li>
	 *   <li>The session's owning root agent has an
	 *       {@code agentIdentity} that matches the calling key's
	 *       identity (resolved via the
	 *       {@code agent_identity_credentials} table — the key uuid is
	 *       the credential value).</li>
	 *   <li>The {@code releaseUuid} is attributed to that session —
	 *       at least one of the session's SCEs produced this release.</li>
	 * </ol>
	 *
	 * <p>Conceptually this is "AGENT permission at session scope" but
	 * it isn't expressed via the {@link PermissionScope} enum — the
	 * scope is enforced inline through the chain check above rather
	 * than carried on a {@link UserPermission} row, since it isn't a
	 * shape an operator could meaningfully grant ahead of time.
	 */
	/**
	 * The calling key owns this session: its resolved agent identity is the identity of the
	 * session's root agent.
	 *
	 * <p>For operations that act <em>as</em> the session rather than reading it -- filing an
	 * attestation, releasing a lock. Without this check any agent key in the organization could
	 * pass another agent's session uuid and have the record name that agent, which would make the
	 * whole attestation worthless: the point of it is who stood behind the claim.
	 */
	public FreeformKeyVerification assertFreeformKeyOwnsSession(AuthHeaderParse ahp, UUID sessionUuid)
			throws RelizaException {
		validateSystemOperational(CallType.WRITE);
		RbacKey key = resolveRbacKey(ahp);
		UUID matchingKeyId = key.keyUuid();
		UUID orgUuid = key.orgUuid();
		AgentSessionData session = agentSessionService.getSessionData(sessionUuid).orElse(null);
		if (session == null || !orgUuid.equals(session.getOrg())) {
			log.error("SECURITY: key {} referenced session {} which is not in its org {}",
					matchingKeyId, sessionUuid, orgUuid);
			throw new AccessDeniedException("Session not found");
		}
		AgentData rootAgent = agentService.getAgentData(session.getAgent()).orElse(null);
		if (rootAgent == null) {
			throw new AccessDeniedException("Session's root agent not found");
		}
		AgentIdentityData callerIdentity = agentIdentityService.findOrRegisterByCredential(
				orgUuid, AgentIdentityCredential.IdentityType.REARM_API_KEY, matchingKeyId.toString(),
				WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()));
		if (!callerIdentity.getUuid().equals(rootAgent.getAgentIdentity())) {
			log.error("SECURITY: key {} (identity {}) tried to act as session {}, owned by agent {} "
					+ "with identity {}", matchingKeyId, callerIdentity.getUuid(), sessionUuid,
					rootAgent.getUuid(), rootAgent.getAgentIdentity());
			throw new AccessDeniedException("Session not owned by calling key");
		}
		return new FreeformKeyVerification(
				WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()), orgUuid, matchingKeyId);
	}

	public FreeformKeyVerification isFreeformKeyAuthorizedForAgenticSessionRead(
			AuthHeaderParse ahp, UUID sessionUuid, String clientSessionId,
			UUID releaseUuid) throws RelizaException {
		validateSystemOperational(CallType.READ);
		RbacKey key = resolveRbacKey(ahp);
		UUID matchingKeyId = key.keyUuid();
		ApiKeyData akd = key.akd();
		UUID orgUuid = key.orgUuid();

		// 1. Org-wide AGENT permission. The threshold is ESSENTIAL_READ —
		// the same floor the rest of the agent-flow programmatic surface
		// uses (see authorizeProgrammaticOrgWrite in AgentDataFetcher).
		// READ_ONLY here would silently lock out ESSENTIAL_READ keys that
		// pass every other agent gate.
		//
		// A missed AGENT-perm check is NOT logged at SECURITY/ERROR — the
		// caller (agenticReleaseProgrammatic) treats a denial here as
		// "wrong auth method" and may fall back to a RESOURCE-on-release
		// permission check. Logging at INFO keeps the diagnostic trail
		// without triggering security alerts on every fallback caller.
		Optional<UserPermission> orgPerm = akd.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid);
		boolean hasAgentOrgPerm = orgPerm.isPresent()
				&& orgPerm.get().getFunctions() != null
				&& orgPerm.get().getFunctions().contains(PermissionFunction.AGENT)
				&& orgPerm.get().getType().ordinal() >= PermissionType.ESSENTIAL_READ.ordinal();
		if (!hasAgentOrgPerm) {
			log.info("agentic-session-read: FREEFORM key {} lacks ORGANIZATION-scope AGENT permission (org={}, sessionUuid={}, clientSessionId={}, releaseUuid={}) — caller may fall back to RESOURCE-on-release path",
					matchingKeyId, orgUuid, sessionUuid, clientSessionId, releaseUuid);
			throw new AccessDeniedException("Calling key lacks org-wide AGENT permission");
		}
		// USER key: the owner must currently hold org-wide AGENT (or be org ADMIN) as well
		boolean ownerAgentOk = ownerAlsoAuthorizes(ownerSide(key), p -> p.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid)
				.filter(o -> o.getType() == PermissionType.ADMIN || (o.getFunctions() != null && o.getFunctions().contains(PermissionFunction.AGENT)
						&& o.getType().ordinal() >= PermissionType.ESSENTIAL_READ.ordinal()))
				.isPresent());
		if (!ownerAgentOk) {
			log.info("agentic-session-read: owner of user key {} lacks ORGANIZATION-scope AGENT permission (org={})", matchingKeyId, orgUuid);
			throw new AccessDeniedException("Key owner lacks org-wide AGENT permission");
		}

		// 2. Resolve session — uuid path or clientSessionId path.
		AgentSessionData session = null;
		if (sessionUuid != null) {
			session = agentSessionService.getSessionData(sessionUuid).orElse(null);
			if (session != null && !orgUuid.equals(session.getOrg())) {
				log.info("agentic-session-read: session {} belongs to org {}, calling key {} is in org {} (releaseUuid={}) — caller may fall back to RESOURCE-on-release path",
						sessionUuid, session.getOrg(), matchingKeyId, orgUuid, releaseUuid);
				throw new AccessDeniedException("Session belongs to a different org");
			}
		} else if (StringUtils.isNotBlank(clientSessionId)) {
			// (org, agent, clientSessionId) is unique; look up by walking
			// the agents owned by the calling key's identity.
			AgentIdentityData identity = agentIdentityService.findOrRegisterByCredential(
					orgUuid, AgentIdentityCredential.IdentityType.REARM_API_KEY,
					matchingKeyId.toString(),
					WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()));
			for (AgentData a : agentService.listByOrg(orgUuid)) {
				if (a.getAgentType() != AgentType.ROOT) continue;
				if (!identity.getUuid().equals(a.getAgentIdentity())) continue;
				Optional<AgentSessionData> osd = agentSessionService.getByClientSessionId(
						orgUuid, a.getUuid(), clientSessionId);
				if (osd.isPresent()) { session = osd.get(); break; }
			}
		} else {
			throw new AccessDeniedException("sessionUuid or clientSessionId is required");
		}
		if (session == null) {
			log.info("agentic-session-read: session not found (sessionUuid={}, clientSessionId={}, releaseUuid={}, callingKey={}, org={}) — caller may fall back to RESOURCE-on-release path",
					sessionUuid, clientSessionId, releaseUuid, matchingKeyId, orgUuid);
			throw new AccessDeniedException("Session not found");
		}

		// 3. Session's owning agent must belong to calling key's identity.
		AgentData rootAgent = agentService.getAgentData(session.getAgent()).orElse(null);
		if (rootAgent == null) {
			log.info("agentic-session-read: session {} references missing root agent {} (releaseUuid={}, callingKey={}) — caller may fall back to RESOURCE-on-release path",
					session.getUuid(), session.getAgent(), releaseUuid, matchingKeyId);
			throw new AccessDeniedException("Session's root agent not found");
		}
		AgentIdentityData callerIdentity = agentIdentityService.findOrRegisterByCredential(
				orgUuid, AgentIdentityCredential.IdentityType.REARM_API_KEY,
				matchingKeyId.toString(),
				WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()));
		if (!callerIdentity.getUuid().equals(rootAgent.getAgentIdentity())) {
			log.info("agentic-session-read: session {} owned by agent {} with identity {}, but calling key {} resolves to identity {} (releaseUuid={}) — caller may fall back to RESOURCE-on-release path",
					session.getUuid(), rootAgent.getUuid(), rootAgent.getAgentIdentity(),
					matchingKeyId, callerIdentity.getUuid(), releaseUuid);
			throw new AccessDeniedException("Session not owned by calling key");
		}

		// 4. Release must be attributed to this session.
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty() || !orgUuid.equals(ord.get().getOrg())) {
			log.info("agentic-session-read: release {} not found in org {} (session={}, callingKey={}) — caller may fall back to RESOURCE-on-release path",
					releaseUuid, orgUuid, session.getUuid(), matchingKeyId);
			throw new AccessDeniedException("Release not found in this org");
		}
		boolean releaseInSession = session.getCommits() != null
				&& sharedReleaseService.findReleaseDatasBySces(session.getCommits(), orgUuid)
						.stream().anyMatch(rd -> releaseUuid.equals(rd.getUuid()));
		if (!releaseInSession) {
			log.info("agentic-session-read: release {} is not attributed to session {} (callingKey={}, org={}) — caller may fall back to RESOURCE-on-release path",
					releaseUuid, session.getUuid(), matchingKeyId, orgUuid);
			throw new AccessDeniedException("Release not attributed to this session");
		}

		apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), orgUuid, ahp.getApiKeyId());
		return new FreeformKeyVerification(
				WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()), orgUuid, matchingKeyId);
	}

	public AuthHeaderParse isProgrammaticAccessAuthorized(HttpHeaders headers,
			HttpServletResponse response, String remoteIp, CallType ct) {
		AuthHeaderParse ahp = null;
		try {
			ahp = AuthHeaderParse.parseAuthHeader(headers, remoteIp);
			log.debug("PSDEBUG: ahp org = " + ahp.getOrgUuid() + ", type = " + ahp.getType() + 
					", obj = " + ahp.getObjUuid());
			isProgrammaticAccessAuthorized(ahp, response, ct);
		} catch (Exception e) {
			try {
				log.warn("Exception when authorizing programmatic access", e);
				if (!response.isCommitted()) {
					response.sendError(HttpStatus.FORBIDDEN.value(), "You do not have permissions to this resource");
				}
			} catch (IOException ioe) {
				throw new IllegalStateException("No permissions");
			}
		}
		return ahp;
	}
	
}
