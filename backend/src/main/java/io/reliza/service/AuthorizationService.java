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
import io.reliza.model.UserPermission;
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
		String remoteIp = servletWebRequest.getRequest().getRemoteAddr();
		if (null != headers && headers.containsKey(HttpHeaders.AUTHORIZATION)) {
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
		if (orgUuid == null && ahp.getType() == ApiTypeEnum.FREEFORM) {
			orgUuid = apiKeyService.resolveOrgForKey(ahp);
		}
		return new ProgrammaticAuthContext(ahp, orgUuid);
	}
	
//	public AuthPrincipal authenticate (HttpHeaders headers, HttpServletRequest request, 
//			OAuth2User oauth2User, HttpServletResponse response, boolean rebuildCookie) {
//		AuthPrincipal ap = null;
//		// determine whether we are dealing with user or programmatic access
//		if (null != headers && headers.containsKey(HttpHeaders.AUTHORIZATION)) {
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
		if (ud.isGlobalAdmin()) {
			AuthorizationResponse.allow(ar);
		}
		
		if (!AuthorizationResponse.isAllowed(ar)) {
			final UUID org = getMatchingOrg(ros);
	
			if (null != org) {
				var permissions = organizationService.obtainCombinedUserOrgPermissions(ud, org)
						.getOrgPermissionsAsSet(org);
				boolean authorized = permissions.stream().anyMatch(x -> 
					doesPermissionAuthorize(x, org, function, objectType, objectUuid, ct));
				if (authorized) {
					AuthorizationResponse.allow(ar);
				}
			}
		}
		
		gqlValidateAuthorizationResponse(ar);
		return ar;
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
	
	private boolean doesPermissionAuthorize(UserPermission permission, UUID org, @NonNull PermissionFunction function, PermissionScope objectType, UUID objectUuid, CallType ct) {
		if (!permission.getOrg().equals(org)) {
			return false;
		}
		if (ct == CallType.GLOBAL_ADMIN) {
			return false;
		}

		if (null == objectUuid) {
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
		
		if (permission.getType().ordinal() < resolvedPt.ordinal()) {
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
		
		return doesPermissionScopeContainObject(permission, org, objectType, objectUuid, resolvedPt);

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
		List<ComponentData> authorizedComponents = new LinkedList<>();
		if (permission.getScope() == PermissionScope.PERSPECTIVE) {
			var opd = ossPerspectiveService.getPerspectiveData(permission.getObject());
			if (opd.isEmpty() || !org.equals(opd.get().getOrg())) {
				log.error("Empty or wrong match for user permission with org = {}, requested under org = {} with permission object = {}",
						permission.getOrg(), org, permission.getObject());
				return false;
			}
			authorizedComponents = getComponentService.listComponentsByPerspective(permission.getObject());
		} else if (permission.getScope() == PermissionScope.COMPONENT) {
			var ocd = getComponentService.getComponentData(permission.getObject());
			if (ocd.isEmpty() || !org.equals(ocd.get().getOrg())) {
				log.error("Empty or wrong match for user permission with org = {}, requested under org = {} with permission object = {}",
						permission.getOrg(), org, permission.getObject());
				return false;
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
		return doComponentsContainObject(authorizedComponents, org, objectType, objectUuid);
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
		if (StringUtils.isNotEmpty(apiKey)) matchingKeyId = apiKeyService.isMatchingApiKey(ahp);
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

	public FreeformKeyVerification verifyFreeformKeyForPermissionFunctions(AuthHeaderParse ahp,
			Set<PermissionFunction> requiredFunctions) throws RelizaException {
		return verifyFreeformKeyForPermissionFunctions(ahp, requiredFunctions, true);
	}

	public FreeformKeyVerification verifyFreeformKeyForPermissionFunctions(AuthHeaderParse ahp,
			Set<PermissionFunction> requiredFunctions, boolean recordAccess) throws RelizaException {
		validateSystemOperational(CallType.WRITE);
		if (ahp == null || ahp.getType() != ApiTypeEnum.FREEFORM)
			throw new AccessDeniedException("FREEFORM API key required");
		UUID matchingKeyId = apiKeyService.isMatchingApiKey(ahp);
		if (matchingKeyId == null)
			throw new AccessDeniedException("Invalid API key");
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(matchingKeyId);
		if (oakd.isEmpty())
			throw new AccessDeniedException("API key data not found");
		ApiKeyData akd = oakd.get();
		UUID orgUuid = akd.getOrg();
		Optional<UserPermission> oup = akd.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid);
		if (oup.isEmpty())
			throw new AccessDeniedException("No org-wide permission on this key");
		UserPermission up = oup.get();
		if (up.getType().ordinal() < PermissionType.READ_WRITE.ordinal())
			throw new AccessDeniedException("Key requires org-wide READ_WRITE or ADMIN permission");
		if (!up.getFunctions().containsAll(requiredFunctions))
			throw new AccessDeniedException("Key missing required permission functions: " + requiredFunctions);
		if (recordAccess)
			apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), orgUuid, ahp.getApiKeyId());
		return new FreeformKeyVerification(WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()), orgUuid, matchingKeyId);
	}

	public FreeformKeyVerification isFreeformKeyAuthorizedForAnyObjectGraphQL(AuthHeaderParse ahp,
			PermissionFunction function, PermissionScope objectType, Set<UUID> objectUuids,
			Collection<RelizaObject> ros) throws RelizaException {
		validateSystemOperational(CallType.READ);
		if (ahp == null || ahp.getType() != ApiTypeEnum.FREEFORM)
			throw new AccessDeniedException("FREEFORM API key required");
		UUID matchingKeyId = apiKeyService.isMatchingApiKey(ahp);
		if (matchingKeyId == null)
			throw new AccessDeniedException("Invalid API key");
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(matchingKeyId);
		if (oakd.isEmpty())
			throw new AccessDeniedException("API key data not found");
		ApiKeyData akd = oakd.get();
		UUID orgUuid = akd.getOrg();

		boolean authorized = false;
		if (objectUuids != null && !objectUuids.isEmpty()) {
			final UUID org = getMatchingOrg(ros);
			if (org != null) {
				var permissions = akd.getPermissions(orgUuid).getOrgPermissionsAsSet(orgUuid);
				Iterator<UUID> objectIter = objectUuids.iterator();
				while (!authorized && objectIter.hasNext()) {
					UUID objectUuid = objectIter.next();
					authorized = permissions.stream().anyMatch(x ->
						doesPermissionAuthorize(x, org, function, objectType, objectUuid, CallType.READ));
				}
			}
		}
		if (!authorized)
			throw new AccessDeniedException("FreeForm key not authorized for this resource");

		apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), orgUuid, ahp.getApiKeyId());
		return new FreeformKeyVerification(
			WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()), orgUuid, matchingKeyId);
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
		if (ahp == null || ahp.getType() != ApiTypeEnum.FREEFORM)
			throw new AccessDeniedException("FREEFORM API key required");
		UUID matchingKeyId = apiKeyService.isMatchingApiKey(ahp);
		if (matchingKeyId == null)
			throw new AccessDeniedException("Invalid API key");
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(matchingKeyId);
		if (oakd.isEmpty())
			throw new AccessDeniedException("API key data not found");
		ApiKeyData akd = oakd.get();
		UUID orgUuid = akd.getOrg();

		boolean authorized = false;
		if (objectUuid != null) {
			final UUID org = getMatchingOrg(ros);
			if (org != null && org.equals(orgUuid)) {
				var permissions = akd.getPermissions(orgUuid).getOrgPermissionsAsSet(orgUuid);
				authorized = permissions.stream().anyMatch(x ->
					doesPermissionAuthorize(x, org, function, objectType, objectUuid, ct));
			}
		}
		if (!authorized)
			throw new AccessDeniedException("FreeForm key not authorized for this resource");

		apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), orgUuid, ahp.getApiKeyId());
		return new FreeformKeyVerification(
			WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()), orgUuid, matchingKeyId);
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
	public FreeformKeyVerification isFreeformKeyAuthorizedForAgenticSessionRead(
			AuthHeaderParse ahp, UUID sessionUuid, String clientSessionId,
			UUID releaseUuid) throws RelizaException {
		validateSystemOperational(CallType.READ);
		if (ahp == null || ahp.getType() != ApiTypeEnum.FREEFORM)
			throw new AccessDeniedException("FREEFORM API key required");
		UUID matchingKeyId = apiKeyService.isMatchingApiKey(ahp);
		if (matchingKeyId == null)
			throw new AccessDeniedException("Invalid API key");
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(matchingKeyId);
		if (oakd.isEmpty())
			throw new AccessDeniedException("API key data not found");
		ApiKeyData akd = oakd.get();
		UUID orgUuid = akd.getOrg();

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

		// 2. Resolve session — uuid path or clientSessionId path.
		AgentSessionData session = null;
		if (sessionUuid != null) {
			session = agentSessionService.getSessionData(sessionUuid).orElse(null);
			if (session != null && !orgUuid.equals(session.getOrg())) {
				log.error("SECURITY: agentic-session-read denied — session {} belongs to org {}, calling key {} is in org {} (releaseUuid={})",
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
			log.error("SECURITY: agentic-session-read denied — session not found (sessionUuid={}, clientSessionId={}, releaseUuid={}, callingKey={}, org={})",
					sessionUuid, clientSessionId, releaseUuid, matchingKeyId, orgUuid);
			throw new AccessDeniedException("Session not found");
		}

		// 3. Session's owning agent must belong to calling key's identity.
		AgentData rootAgent = agentService.getAgentData(session.getAgent()).orElse(null);
		if (rootAgent == null) {
			log.error("SECURITY: agentic-session-read denied — session {} references missing root agent {} (releaseUuid={}, callingKey={})",
					session.getUuid(), session.getAgent(), releaseUuid, matchingKeyId);
			throw new AccessDeniedException("Session's root agent not found");
		}
		AgentIdentityData callerIdentity = agentIdentityService.findOrRegisterByCredential(
				orgUuid, AgentIdentityCredential.IdentityType.REARM_API_KEY,
				matchingKeyId.toString(),
				WhoUpdated.getApiWhoUpdated(matchingKeyId, ahp.getRemoteIp()));
		if (!callerIdentity.getUuid().equals(rootAgent.getAgentIdentity())) {
			log.error("SECURITY: agentic-session-read denied — session {} owned by agent {} with identity {}, but calling key {} resolves to identity {} (releaseUuid={})",
					session.getUuid(), rootAgent.getUuid(), rootAgent.getAgentIdentity(),
					matchingKeyId, callerIdentity.getUuid(), releaseUuid);
			throw new AccessDeniedException("Session not owned by calling key");
		}

		// 4. Release must be attributed to this session.
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(releaseUuid);
		if (ord.isEmpty() || !orgUuid.equals(ord.get().getOrg())) {
			log.error("SECURITY: agentic-session-read denied — release {} not found in org {} (session={}, callingKey={})",
					releaseUuid, orgUuid, session.getUuid(), matchingKeyId);
			throw new AccessDeniedException("Release not found in this org");
		}
		boolean releaseInSession = false;
		if (session.getCommits() != null) {
			for (UUID sceUuid : session.getCommits()) {
				for (ReleaseData rd : sharedReleaseService.findReleaseDatasBySce(sceUuid, orgUuid)) {
					if (releaseUuid.equals(rd.getUuid())) { releaseInSession = true; break; }
				}
				if (releaseInSession) break;
			}
		}
		if (!releaseInSession) {
			log.error("SECURITY: agentic-session-read denied — release {} is not attributed to session {} (callingKey={}, org={})",
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
