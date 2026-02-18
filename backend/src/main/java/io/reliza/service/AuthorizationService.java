/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.AuthPrincipal;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.dto.AuthorizationResponse;
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
	
	public AuthHeaderParse authenticateProgrammatic (HttpHeaders headers, ServletWebRequest servletWebRequest) {
		AuthHeaderParse ahp = null;
		String remoteIp = servletWebRequest.getRequest().getRemoteAddr();
		if (null != headers && headers.containsKey(HttpHeaders.AUTHORIZATION)) {
			ahp = AuthHeaderParse.parseAuthHeader(headers, remoteIp);
		} 
		return ahp;
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

	public AuthorizationResponse authorize(AuthPrincipal ap, CallType ct) {
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
	
	public AuthorizationResponse isUserAuthorizedOrgWideGraphQLWithObjects(UserData ud, Collection<RelizaObject> ros, CallType ct) {
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
	 */
	public AuthorizationResponse isUserAuthorizedForObjectGraphQL(final UserData ud, @NonNull final PermissionFunction function,
			final PermissionScope objectType, final UUID objectUuid, Collection<RelizaObject> ros, CallType ct) {
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (ud.isGlobalAdmin()) {
			AuthorizationResponse.allow(ar);
		}
		
		if (!AuthorizationResponse.isAllowed(ar)) {
			final UUID org = getMatchingOrg(ros);
	
			if (null != org) {
				var permissions = ud.getOrgPermissions(org);
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
			final PermissionScope objectType, final Set<UUID> objectUuids, Collection<RelizaObject> ros, CallType ct) {
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		if (ud.isGlobalAdmin()) {
			AuthorizationResponse.allow(ar);
		}
		
		if (!AuthorizationResponse.isAllowed(ar) && null != objectUuids && !objectUuids.isEmpty()) {
			final UUID org = getMatchingOrg(ros);
			if (null != org) {
				boolean authorized = false;
				Iterator<UUID> objectIter = objectUuids.iterator();
				while (!authorized && objectIter.hasNext()) {
					UUID objectUuid = objectIter.next();
					var permissions = ud.getOrgPermissions(org);
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
		
		if (null != permission.getFunctions() && !permission.getFunctions().isEmpty() && !permission.getFunctions().contains(function)) {
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
		
		return doesPermissionScopeContainObject(permission, org, objectType, objectUuid);
		
	}
	
	/**
	 * We currently consider only COMPONENT and PERSPECTIVE as permission scopes
	 * @param permission
	 * @param objectType
	 * @param objectUuid
	 * @return
	 */
	private boolean doesPermissionScopeContainObject (UserPermission permission, UUID org, PermissionScope objectType, UUID objectUuid) {
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
			var childCompList = getComponentService.listComponentsByProduct(permission.getObject());
			authorizedComponents.addAll(childCompList);
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
					if (CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(org) ||
							(oup.isPresent() && oup.get().getType().ordinal() >= PermissionType.READ_ONLY.ordinal())) {
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
				&& (ApiTypeEnum.COMPONENT == ahp.getType() || ApiTypeEnum.VERSION_GEN == ahp.getType())) {
			Optional<ComponentData> ocd = getComponentService.getComponentData(ahp.getObjUuid());
			ro = ocd.isPresent() ? ocd.get() : null;
		} else if (classType.equals(CommonVariables.ORGANIZATION_FIELD) && ApiTypeEnum.USER == ahp.getType()) {
			Optional<OrganizationData> od = getOrganizationService.getOrganizationData(ahp.getOrgUuid());
			ro = od.isPresent() ? od.get() : null;
		} else if (classType.equals(CommonVariables.ORGANIZATION_FIELD)) {
			Optional<OrganizationData> od = getOrganizationService.getOrganizationData(ahp.getObjUuid());
			ro = od.isPresent() ? od.get() : null;
		}
		return ro;
	}
	
	public AuthorizationResponse isApiKeyAuthorized(AuthHeaderParse ahp, List<ApiTypeEnum> supportedApiTypes, UUID org, 
			CallType ct, RelizaObject ro) {
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
				if (!(ro instanceof ReleaseData)) {
					log.error("SECURITY: org mismatch, api key org = " + akd.getOrg() + " , ro org = " + ro.getOrg());
				} else {
					log.error("SECURITY: org mismatch, api key org = " + akd.getOrg() + ", release = " + ((ReleaseData) ro).getUuid());
				}
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

		//check if user has access to the organization
		if(null != matchingKeyId && ahp.getType() == ApiTypeEnum.USER){
			UserData ud = userService.getUserData(ahp.getObjUuid()).get();
			log.debug("is User authorized in checking for programmatic access");
			boolean authorized = isUserAuthorizedOrgWide(ud, ahp.getOrgUuid(), response, ct);
			log.debug("completed is User authorized for programmatic access");
			if (!authorized) matchingKeyId = null;
		}

		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(matchingKeyId);
		if(oakd.isPresent()){
			ApiKeyData akd = oakd.get();
			apiKeyAccessService.recordApiKeyAccess(matchingKeyId, ahp.getRemoteIp(), akd.getOrg(), ahp.getApiKeyId());

		}
		return matchingKeyId;
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
