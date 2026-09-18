/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.ws;

import java.time.ZonedDateTime;
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
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKeyData;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.FederatedTrustRuleService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;

/** Org-admin management of federated identity trust rules and the identities they materialise. */
@DgsComponent
public class FederatedTrustRuleDataFetcher {

	@Autowired private FederatedTrustRuleService federatedTrustRuleService;
	@Autowired private UserService userService;
	@Autowired private AuthorizationService authorizationService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private GetOrganizationService getOrganizationService;

	private UserData me() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow(() -> new AccessDeniedException("Not authorized"));
	}

	private WhoUpdated orgAdmin(UserData ud, UUID orgUuid) throws RelizaException {
		Optional<OrganizationData> od = orgUuid == null ? Optional.empty() : getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ADMIN);
		return WhoUpdated.getWhoUpdated(ud);
	}

	private FederatedTrustRule rule(String uuidStr) throws RelizaException {
		return federatedTrustRuleService.get(UUID.fromString(uuidStr)).orElseThrow(() -> new RelizaException("Trust rule not found"));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "federatedTrustRules")
	public List<FederatedTrustRule> federatedTrustRules(@InputArgument("orgUuid") String orgUuidStr) throws RelizaException {
		UUID orgUuid = UUID.fromString(orgUuidStr);
		orgAdmin(me(), orgUuid);
		return federatedTrustRuleService.listByOrg(orgUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createFederatedTrustRule")
	public FederatedTrustRule createFederatedTrustRule(@InputArgument("orgUuid") String orgUuidStr, @InputArgument("name") String name,
			@InputArgument("provider") FederatedTrustRule.Provider provider, @InputArgument("issuer") String issuer,
			@InputArgument("matcher") Map<String, Object> matcher, @InputArgument("grant") Map<String, Object> grant,
			@InputArgument("expiresDate") ZonedDateTime expiresDate) throws RelizaException {
		UUID orgUuid = UUID.fromString(orgUuidStr);
		WhoUpdated wu = orgAdmin(me(), orgUuid);
		return federatedTrustRuleService.create(orgUuid, name, provider, issuer, Utils.OM.convertValue(matcher, FederatedMatcher.class),
				Utils.OM.convertValue(grant, FederatedGrant.class), expiresDate, wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateFederatedTrustRule")
	public FederatedTrustRule updateFederatedTrustRule(@InputArgument("uuid") String uuidStr, @InputArgument("name") String name,
			@InputArgument("matcher") Map<String, Object> matcher, @InputArgument("grant") Map<String, Object> grant,
			@InputArgument("expiresDate") ZonedDateTime expiresDate) throws RelizaException {
		FederatedTrustRule r = rule(uuidStr);
		WhoUpdated wu = orgAdmin(me(), r.getOrg());
		return federatedTrustRuleService.update(r.getUuid(), name, Utils.OM.convertValue(matcher, FederatedMatcher.class),
				Utils.OM.convertValue(grant, FederatedGrant.class), expiresDate, wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setFederatedTrustRuleStatus")
	public FederatedTrustRule setFederatedTrustRuleStatus(@InputArgument("uuid") String uuidStr,
			@InputArgument("status") FederatedTrustRule.Status status) throws RelizaException {
		FederatedTrustRule r = rule(uuidStr);
		WhoUpdated wu = orgAdmin(me(), r.getOrg());
		return federatedTrustRuleService.setStatus(r.getUuid(), status, wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteFederatedTrustRule")
	public Boolean deleteFederatedTrustRule(@InputArgument("uuid") String uuidStr) throws RelizaException {
		FederatedTrustRule r = rule(uuidStr);
		orgAdmin(me(), r.getOrg());
		federatedTrustRuleService.delete(r.getUuid());
		return true;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "resetFederatedTrustRulePin")
	public FederatedTrustRule resetFederatedTrustRulePin(@InputArgument("uuid") String uuidStr) throws RelizaException {
		FederatedTrustRule r = rule(uuidStr);
		WhoUpdated wu = orgAdmin(me(), r.getOrg());
		return federatedTrustRuleService.resetOwnerPin(r.getUuid(), wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "resetFederatedIdentityPin")
	public ApiKeyDto resetFederatedIdentityPin(@InputArgument("apiKeyUuid") String apiKeyUuidStr) throws RelizaException {
		UUID keyUuid = UUID.fromString(apiKeyUuidStr);
		ApiKeyData akd = apiKeyService.getApiKeyData(keyUuid).orElseThrow(() -> new RelizaException("Identity not found"));
		WhoUpdated wu = orgAdmin(me(), akd.getOrg());
		return apiKeyService.resetFederatedIdentityPin(keyUuid, wu);
	}

	/** The rule's grant, read from its JSON column for the schema field. */
	@DgsData(parentType = "FederatedTrustRule", field = "grant")
	public FederatedGrant grant(com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		FederatedTrustRule r = dfe.getSource();
		return r == null ? null : r.grantOf();
	}

	@DgsData(parentType = "FederatedTrustRule", field = "matcher")
	public FederatedMatcher matcher(com.netflix.graphql.dgs.DgsDataFetchingEnvironment dfe) {
		FederatedTrustRule r = dfe.getSource();
		return r == null ? null : r.matcherOf();
	}
}
