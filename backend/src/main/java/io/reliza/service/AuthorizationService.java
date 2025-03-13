/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.AuthPrincipal;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.dto.AuthorizationResponse;
import io.reliza.model.dto.AuthorizationResponse.AllowType;
import io.reliza.model.dto.AuthorizationResponse.ForbidType;
import io.reliza.model.dto.AuthorizationResponse.InitType;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AuthorizationService {
	
	@Autowired
	OrganizationService organizationService;
	
	@Autowired 
	ResourceGroupService resourceGroupService;
	
	@Autowired
	GetComponentService getComponentService;
	
	@Autowired
	ApiKeyService apiKeyService;
	
	@Autowired
	UserService userService;
	
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
	
	public AuthorizationResponse isUserAuthorizedOrgWideGraphQL(UserData ud, UUID org, CallType ct) {
		AuthorizationResponse ar = AuthorizationResponse.initialize(InitType.FORBID);
		AuthorizationStatus as = organizationService.isUserAuthorizedOrgWide(ud, org, ct);
		if (as == AuthorizationStatus.AUTHORIZED) AuthorizationResponse.allow(ar);
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}

	public AuthorizationResponse isUserAuthorizedOrgWideGraphQLWithObject(UserData ud, RelizaObject ro, CallType ct) {
		return isUserAuthorizedOrgWideGraphQLWithObjects(ud, List.of(ro), ct);
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
			AuthorizationStatus as = organizationService.isUserAuthorizedOrgWide(ud, org, ct);
			if (as == AuthorizationStatus.AUTHORIZED) AuthorizationResponse.allow(ar);
		}
		
		gqlValidateAuthorizationResponse(ar);
		return ar;
	}
	
	public UUID getMatchingOrg (Collection<RelizaObject> ros) {
		UUID org = null;
		boolean orgMatch = (null != ros && !ros.isEmpty());
		if (orgMatch) {
			org = ros.iterator().next().getOrg();
			for (var ro: ros) {
				if (null == ro || !org.equals(ro.getOrg())) orgMatch = false; 
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
			Optional<OrganizationData> od = organizationService.getOrganizationData(ahp.getOrgUuid());
			ro = od.isPresent() ? od.get() : null;
		} else if (classType.equals(CommonVariables.ORGANIZATION_FIELD)) {
			Optional<OrganizationData> od = organizationService.getOrganizationData(ahp.getObjUuid());
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
			matchingKey = apiKeyService.isProgrammaticAccessAuthorized(ahp, ct);
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
	
	private void gqlValidateAuthorizationResponse(AuthorizationResponse ar) {
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

	
}
