/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.InputArgument;

import io.reliza.common.CommonVariables.BranchSuffixMode;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.InstallationType;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaObject;
import io.reliza.model.ResourceGroupData;
import io.reliza.model.UserData;
import io.reliza.model.UserData.OrgUserData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKeyData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.model.dto.ApiKeyForUserDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.CdxImportService;
import io.reliza.service.CdxImportService.ImportComponentResult;
import io.reliza.service.FindingChangeEventBackfillService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.ResourceGroupService;
import io.reliza.service.SystemInfoService;
import io.reliza.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@DgsComponent
public class OrganizationDataFetcher {
	
	@Autowired
	private ApiKeyService apiKeyService;
	
	@Autowired
	private AuthorizationService authorizationService;
	
	@Autowired
	private GetOrganizationService getOrganizationService;
	
	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private FindingChangeEventBackfillService findingChangeEventBackfillService;
	
	@Autowired
	private UserService userService;
	
	@Autowired
	private ResourceGroupService resourceGroupService;

	@Autowired
	private SystemInfoService systemInfoService;

	@Autowired
	private CdxImportService cdxImportService;

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "importCyclonedxComponents")
	public List<ImportComponentResult> importCyclonedxComponents(DgsDataFetchingEnvironment dfe,
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("cdxJson") String cdxJson) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ros = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ros), CallType.WRITE);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		List<ImportComponentResult> results = cdxImportService.importFromCycloneDx(orgUuid, cdxJson, wu);
		return results.stream().filter(r -> r != null).toList();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "organizations")
	public Iterable<OrganizationData> getOrganizations() {
		Collection<OrganizationData> orgs = new LinkedList<>();
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		if (oud.isPresent()) {
			orgs = organizationService.listMyOrganizationData(oud.get());
		}
		return orgs; 
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "users")
	public List<OrgUserData> getUsers(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("includeInactive") Boolean includeInactive) throws RelizaException{
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roUsers = od.isPresent() ? od.get() : null;
		InstallationType systemInstallationType = userService.getInstallationType();
		CallType ct = CallType.READ;
		if (InstallationType.DEMO == systemInstallationType) {
			ct = CallType.ADMIN;
		}
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roUsers), ct);
		boolean includeInactiveUsers = includeInactive != null && includeInactive;
		return userService.listOrgUserDataByOrg(od.get().getUuid(), includeInactiveUsers);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "combinedUserOrgPermissions")
	public Permissions getCombinedUserOrgPermissions(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("userUuid") UUID userUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roUsers = od.isPresent() ? od.get() : null;
		// A member may always read their own effective permissions: the profile page and the CLI login
		// form show what a personal key may be given, and that answer must not itself need a level in
		// the org -- a user holding only component-scoped grants has to see them too. Anyone else's
		// permissions need org admin.
		boolean self = userUuid.equals(oud.get().getUuid());
		if (self) {
			authorizationService.validateSystemOperational(CallType.ESSENTIAL_READ);
		} else {
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roUsers), CallType.ADMIN);
		}
		var targetUser = userService.getUserDataWithOrg(userUuid, orgUuid)
				.orElseThrow(() -> new AccessDeniedException("User is not in organization"));
		return organizationService.obtainCombinedUserOrgPermissions(targetUser, orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "resourceGroups")
	public List<ResourceGroupData> getApplications(@InputArgument("orgUuid") String orgUuidStr) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		var odRg = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roRg = odRg.isPresent() ? odRg.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roRg), CallType.READ);
		return resourceGroupService.listResourceGroupDataOfOrg(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "totalsAnalytics")
	public Map<String,BigInteger> getTotalsAnalytics(@InputArgument("orgUuid") String orgUuidStr) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		var odTotals = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roTotals = odTotals.isPresent() ? odTotals.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roTotals), CallType.READ);
		return organizationService.getNumericAnalytics(orgUuid);
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "apiKeys")
	public List<ApiKeyDto> getApiKeys(@InputArgument("orgUuid") String orgUuidStr) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roApiKeys = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roApiKeys), CallType.ADMIN);
		return apiKeyService.listApiKeyDtoByOrgWithLastAccessDate(od.get().getUuid());
	}
	
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setOrgApiKey")
	public ApiKeyForUserDto setOrgApiKey(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("apiType") ApiTypeEnum keyType,
			@InputArgument("notes") String notes
		) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roSetKey = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roSetKey), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		String apiKey = null;
		String keyId = null;
		if (keyType == ApiTypeEnum.FREEFORM || keyType == ApiTypeEnum.ORGANIZATION || keyType == ApiTypeEnum.ORGANIZATION_RW) {
			String keyOrder = UUID.randomUUID().toString();
			apiKey = apiKeyService.setObjectApiKey(od.get().getUuid(), keyType, od.get().getUuid(), keyOrder, notes, wu);
			keyId = keyType.toString() + "__" + od.get().getUuid().toString() + "__ord__" + keyOrder;
		} else {
			throw new RelizaException("Unsupported Key type for this location");
		}
		
		ApiKeyForUserDto retKey = ApiKeyForUserDto.builder()
				.apiKey(apiKey)
				.id(keyId)
				.authorizationHeader("Basic " + HttpHeaders.encodeBasicAuth(keyId, apiKey, StandardCharsets.UTF_8))
				.build();

		return retKey;
	}
	
	/** Create an org-level key id with no secret; the first secret is minted separately via addApiKeySecret. */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createOrgApiKey")
	public ApiKeyDto createOrgApiKey(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("apiType") ApiTypeEnum keyType,
			@InputArgument("notes") String notes
		) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roSetKey = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roSetKey), CallType.ADMIN);
		if (keyType != ApiTypeEnum.FREEFORM && keyType != ApiTypeEnum.ORGANIZATION && keyType != ApiTypeEnum.ORGANIZATION_RW) {
			throw new RelizaException("Unsupported Key type for this location");
		}
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		String keyOrder = UUID.randomUUID().toString();
		return ApiKeyDto.fromApiKey(apiKeyService.createObjectApiKey(od.get().getUuid(), keyType, od.get().getUuid(), keyOrder, notes, wu));
	}

	/**
	 * Gate for key lifecycle mutations: the owner of a USER key may manage their own key;
	 * anyone else needs the given permission level on the key's org.
	 */
	private WhoUpdated authorizeKeyOwnerOr(UUID apiKeyUuid, CallType ct) throws RelizaException {
		return authorizeKeyOwnerOr(apiKeyUuid, ct, true);
	}

	/** True when the caller is the owner of a USER key or the holder of a FREEFORM key. */
	private static boolean isOwnerOrHolder(UserData ud, ApiKey ak) {
		if (ak.getObjectType() == ApiTypeEnum.USER) return ud.getUuid().equals(ak.getObjectUuid());
		if (ak.getObjectType() == ApiTypeEnum.FREEFORM) {
			UUID holder = ApiKeyData.dataFromRecord(ak).getHolder();
			return holder != null && holder.equals(ud.getUuid());
		}
		return false;
	}

	private WhoUpdated authorizeKeyOwnerOr(UUID apiKeyUuid, CallType ct, boolean holderMayAct) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ApiKey> oak = apiKeyService.getApiKey(apiKeyUuid);
		if (oak.isPresent() && isOwnerOrHolder(oud.get(), oak.get())
				&& (holderMayAct || oak.get().getObjectType() == ApiTypeEnum.USER)) {
			authorizationService.validateSystemOperational(CallType.WRITE);
			return WhoUpdated.getWhoUpdated(oud.get());
		}
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(apiKeyUuid);
		RelizaObject ro = oakd.isPresent() ? oakd.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION,
				ro != null ? ro.getOrg() : null, List.of(ro), ct);
		return WhoUpdated.getWhoUpdated(oud.get());
	}

	/** Whether the caller passes the org-level gate for this key on their own account (as opposed to the owner / holder shortcut). */
	private boolean hasOrgWriteOnKey(UUID apiKeyUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(apiKeyUuid);
		RelizaObject ro = oakd.isPresent() ? oakd.get() : null;
		try {
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION,
					ro != null ? ro.getOrg() : null, List.of(ro), CallType.WRITE);
			return true;
		} catch (org.springframework.security.access.AccessDeniedException e) {
			return false;
		}
	}

	/** Secret lifecycle mutations: owner of a USER key, holder of a FREEFORM key, or write access on the key's org. */
	private WhoUpdated authorizeKeyAdmin(UUID apiKeyUuid) throws RelizaException {
		return authorizeKeyOwnerOr(apiKeyUuid, CallType.WRITE);
	}

	/**
	 * Minting returns cleartext, so on a held FREEFORM key only the holder may do it; admins keep
	 * retire / delete / deactivate. Requests and denied keys cannot be minted at all.
	 */
	private WhoUpdated authorizeKeyMinter(UUID apiKeyUuid) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		ApiKey ak = apiKeyService.getApiKey(apiKeyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		ApiKeyData akd = ApiKeyData.dataFromRecord(ak);
		if (akd.getStatus() != ApiKeyData.ApiKeyStatus.ACTIVE && akd.getStatus() != ApiKeyData.ApiKeyStatus.INACTIVE) {
			throw new RelizaException("This key is a request; it can be minted once an admin approves it");
		}
		if (ak.getObjectType() == ApiTypeEnum.FREEFORM && akd.getHolder() != null && !akd.getHolder().equals(oud.get().getUuid())) {
			throw new RelizaException("Only the holder of this key may mint its secrets");
		}
		// a personal key acts as its owner and is attributed to them: nobody else, admins included, may hold a secret for it
		if (ak.getObjectType() == ApiTypeEnum.USER && !oud.get().getUuid().equals(ak.getObjectUuid())) {
			throw new RelizaException("Only the owner of a personal key may mint its secrets");
		}
		return authorizeKeyAdmin(apiKeyUuid);
	}

	private static ZonedDateTime futureOrNull(ZonedDateTime expiresDate) throws RelizaException {
		if (expiresDate != null && !expiresDate.isAfter(ZonedDateTime.now())) throw new RelizaException("Expiry must be in the future");
		return expiresDate;
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setApiKeySecretExpiry")
	public ApiKeyDto setApiKeySecretExpiry(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("slot") Integer slot,
			@InputArgument("expiresDate") ZonedDateTime expiresDate) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyAdmin(apiKeyUuid);
		return apiKeyService.setApiKeySecretExpiry(apiKeyUuid, slot, futureOrNull(expiresDate), wu);
	}

	/** Write users ask for a free-form key; admins see it as a pending request. */
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "requestFreeformApiKey")
	public ApiKeyDto requestFreeformApiKey(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("notes") String notes,
			@InputArgument("permissionType") PermissionType permissionType,
			@InputArgument("permissions") List<LinkedHashMap<String, Object>> permissions
		) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.WRITE);
		if (!oud.get().isGlobalAdmin() && !oud.get().getOrganizations().contains(orgUuid)) {
			throw new RelizaException("Not a member of this organization");
		}
		List<PermissionDto> convertedPermissions = permissions == null ? List.of() : permissions.stream()
				.map(p -> Utils.OM.convertValue(p, PermissionDto.class)).collect(Collectors.toList());
		for (PermissionDto p : convertedPermissions) {
			if (null != p.approvals() && !p.approvals().isEmpty() && !Utils.isSanitizedApprovalsSent(p.approvals(), od.get())) {
				throw new RuntimeException("Invalid approvals sent");
			}
		}
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return ApiKeyDto.fromApiKey(apiKeyService.requestFreeformApiKey(oud.get().getUuid(), orgUuid, notes,
				permissionType == null ? PermissionType.NONE : permissionType, convertedPermissions, wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "resolveApiKeyRequest")
	public ApiKeyDto resolveApiKeyRequest(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("approve") Boolean approve,
			@InputArgument("reason") String reason) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyOwnerOr(apiKeyUuid, CallType.ADMIN, false);
		return apiKeyService.resolveApiKeyRequest(apiKeyUuid, Boolean.TRUE.equals(approve), reason, wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setApiKeyHolder")
	public ApiKeyDto setApiKeyHolder(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("holder") String holderStr) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyOwnerOr(apiKeyUuid, CallType.ADMIN, false);
		UUID holder = StringUtils.isBlank(holderStr) ? null : UUID.fromString(holderStr);
		if (holder != null) {
			ApiKey ak = apiKeyService.getApiKey(apiKeyUuid).orElseThrow(() -> new RelizaException("API key not found"));
			Optional<UserData> oh = userService.getUserData(holder);
			if (oh.isEmpty() || !oh.get().getOrganizations().contains(ak.getOrg())) throw new RelizaException("Holder must be a member of the key's organization");
		}
		return apiKeyService.setApiKeyHolder(apiKeyUuid, holder, wu);
	}

	/** Personal key: an active member creates one for themselves, with no secret and no permissions yet (the ceiling starts empty). */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "createUserApiKey")
	public ApiKeyDto createUserApiKey(@InputArgument("orgUuid") String orgUuidStr, @InputArgument("notes") String notes) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject ro = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(ro), CallType.ESSENTIAL_READ);
		if (!oud.get().isGlobalAdmin() && !oud.get().getOrganizations().contains(orgUuid)) {
			throw new RelizaException("Not a member of this organization");
		}
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		return ApiKeyDto.fromApiKey(apiKeyService.createObjectApiKey(oud.get().getUuid(), ApiTypeEnum.USER, orgUuid, UUID.randomUUID().toString(), notes, wu));
	}

	/** The calling user's own USER keys across organizations (metadata only). */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "myApiKeys")
	public List<ApiKeyDto> myApiKeys() throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		authorizationService.validateSystemOperational(CallType.READ);
		return apiKeyService.listUserKeyDtos(oud.get().getUuid());
	}

	private ApiKeyForUserDto keyForUser(UUID apiKeyUuid, String cleartext) {
		ApiKey ak = apiKeyService.getApiKey(apiKeyUuid).orElseThrow();
		String keyId = ApiKeyService.keyIdOf(ak);
		return ApiKeyForUserDto.builder().apiKey(cleartext).id(keyId)
				.authorizationHeader("Basic " + HttpHeaders.encodeBasicAuth(keyId, cleartext, StandardCharsets.UTF_8)).build();
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "addApiKeySecret")
	public ApiKeyForUserDto addApiKeySecret(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("expiresDate") ZonedDateTime expiresDate) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyMinter(apiKeyUuid);
		return keyForUser(apiKeyUuid, apiKeyService.addApiKeySecret(apiKeyUuid, futureOrNull(expiresDate), wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "regenerateApiKeySecret")
	public ApiKeyForUserDto regenerateApiKeySecret(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("slot") Integer slot,
			@InputArgument("expiresDate") ZonedDateTime expiresDate) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyMinter(apiKeyUuid);
		return keyForUser(apiKeyUuid, apiKeyService.regenerateApiKeySecret(apiKeyUuid, slot, futureOrNull(expiresDate), wu));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setApiKeySecretActive")
	public ApiKeyDto setApiKeySecretActive(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("slot") Integer slot,
			@InputArgument("active") Boolean active) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyAdmin(apiKeyUuid);
		return apiKeyService.setApiKeySecretActive(apiKeyUuid, slot, Boolean.TRUE.equals(active), wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteApiKeySecret")
	public ApiKeyDto deleteApiKeySecret(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("slot") Integer slot) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyAdmin(apiKeyUuid);
		return apiKeyService.deleteApiKeySecret(apiKeyUuid, slot, wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setApiKeyStatus")
	public ApiKeyDto setApiKeyStatus(@InputArgument("apiKeyUuid") String apiKeyUuidStr, @InputArgument("status") ApiKeyData.ApiKeyStatus status) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyAdmin(apiKeyUuid);
		return apiKeyService.setApiKeyStatus(apiKeyUuid, status, hasOrgWriteOnKey(apiKeyUuid), wu);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "deleteApiKey")
	public Boolean setOrgApiKey(@InputArgument("apiKeyUuid") String apiKeyUuidStr) throws RelizaException {
		UUID apiKeyUuid = UUID.fromString(apiKeyUuidStr);
		WhoUpdated wu = authorizeKeyOwnerOr(apiKeyUuid, CallType.ADMIN);
		apiKeyService.deleteApiKey(apiKeyUuid, wu);
		return true;
	}

	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "removeUser")
	public Boolean removeUser (@InputArgument("org") UUID org, @InputArgument("user") UUID user) {
		Optional<UserData> oud = Optional.empty();
		try {
			JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
			oud = userService.getUserDataByAuth(auth);
			var odRemove = getOrganizationService.getOrganizationData(org);
			RelizaObject roRemove = odRemove.isPresent() ? odRemove.get() : null;
			authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, org, List.of(roRemove), CallType.ADMIN);
			WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
			userService.removeUserFromOrg(org, user, wu);
		} catch (Exception e) {
			throw new AccessDeniedException("Error removing user from organization, please contact support at info@reliza.io");
		}
		return true;
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateOrganizationTerminology")
	public OrganizationData updateOrganizationTerminology(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("terminology") Map<String, Object> terminology) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var odTerm = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roTerm = odTerm.isPresent() ? odTerm.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roTerm), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		String featureSetLabel = terminology != null ? (String) terminology.get("featureSetLabel") : null;
		return organizationService.updateTerminology(orgUuid, featureSetLabel, wu);
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateOrganizationIgnoreViolation")
	public OrganizationData updateOrganizationIgnoreViolation(
			@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("ignoreViolation") Map<String, Object> ignoreViolation) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var odIgnore = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roIgnore = odIgnore.isPresent() ? odIgnore.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roIgnore), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());
		
		@SuppressWarnings("unchecked")
		List<String> licenseViolationRegexIgnore = ignoreViolation != null ? 
				(List<String>) ignoreViolation.get("licenseViolationRegexIgnore") : null;
		@SuppressWarnings("unchecked")
		List<String> securityViolationRegexIgnore = ignoreViolation != null ? 
				(List<String>) ignoreViolation.get("securityViolationRegexIgnore") : null;
		@SuppressWarnings("unchecked")
		List<String> operationalViolationRegexIgnore = ignoreViolation != null ? 
				(List<String>) ignoreViolation.get("operationalViolationRegexIgnore") : null;
		
		// Validate regex patterns
		validateRegexPatterns(licenseViolationRegexIgnore, "licenseViolationRegexIgnore");
		validateRegexPatterns(securityViolationRegexIgnore, "securityViolationRegexIgnore");
		validateRegexPatterns(operationalViolationRegexIgnore, "operationalViolationRegexIgnore");
		
		return organizationService.updateIgnoreViolation(orgUuid, licenseViolationRegexIgnore, 
				securityViolationRegexIgnore, operationalViolationRegexIgnore, wu);
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "updateOrganizationSettings")
	public OrganizationData updateOrganizationSettings(
			@InputArgument("orgUuid") String orgUuidStr,
			@InputArgument("settings") Map<String, Object> settings) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		UUID orgUuid = UUID.fromString(orgUuidStr);
		var odSettings = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roSettings = odSettings.isPresent() ? odSettings.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roSettings), CallType.ADMIN);
		WhoUpdated wu = WhoUpdated.getWhoUpdated(oud.get());

		OrganizationData.Settings settingsPatch = settings == null
				? new OrganizationData.Settings()
				: Utils.OM.convertValue(settings, OrganizationData.Settings.class);
		if (settingsPatch.getBranchSuffixMode() == BranchSuffixMode.INHERIT) {
			throw new RelizaException("INHERIT is not a valid branchSuffixMode for organization settings");
		}
		return organizationService.updateSettings(orgUuid, settingsPatch, wu);
	}

	/**
	 * Admin-gated, org-scoped one-time backfill of the branch-grain {@code finding_change_events_v3}
	 * (events-lite) store from the existing {@code metrics_audit} history (board task #38 + the v3
	 * follow-on; v1/v2 were dropped in V64, so v3 is the sole store). The org-ADMIN authorization below
	 * also carries tenant scoping: the backfill only ever touches the audited releases of {@code org}, so
	 * a caller can never seed another tenant's data. Idempotent + restartable -- re-running is a near
	 * no-op. Returns the number of releases processed for the org.
	 *
	 * <p>The v3 backfill is branch-chained + full-history, so the legacy {@code sinceRevisionDate} /
	 * {@code reseed} arguments (which drove the per-release v1/v2 coverage skip) no longer apply and are
	 * ignored; the mutation shape is kept for backward compatibility.
	 *
	 * <p>Intentionally NOT {@code @Transactional}: the backfill isolates each release in its own
	 * {@code TransactionTemplate} transaction so one release's failure cannot roll back the batch. An
	 * ambient resolver transaction would be joined by those templates (PROPAGATION_REQUIRED) and defeat
	 * that isolation. The auth check above is read-only and needs no surrounding transaction.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "backfillFindingChangeEvents")
	public Integer backfillFindingChangeEvents(
			@InputArgument("org") UUID org,
			@InputArgument("sinceRevisionDate") ZonedDateTime sinceRevisionDate,
			@InputArgument("reseed") Boolean reseed) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		var odBackfill = getOrganizationService.getOrganizationData(org);
		RelizaObject roBackfill = odBackfill.isPresent() ? odBackfill.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, org, List.of(roBackfill), CallType.ADMIN);
		return findingChangeEventBackfillService.backfillOrgV3(org).releasesProcessed();
	}

	@DgsData(parentType = "Organization", field = "type")
	public String organizationType(DgsDataFetchingEnvironment dfe) {
		OrganizationData od = dfe.getSource();
		UUID defaultOrg = systemInfoService.getDefaultOrg();
		if (defaultOrg != null && defaultOrg.equals(od.getUuid())) {
			return "DEFAULT";
		}
		return "REGULAR";
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "OrgUserData", field = "combinedUserOrgPermissions")
	public Permissions getCombinedUserOrgPermissionsSubfield(DgsDataFetchingEnvironment dfe) throws RelizaException {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		var oud = userService.getUserDataByAuth(auth);
		
		OrgUserData orgUserData = dfe.getSource();
		UUID userUuid = orgUserData.getUuid();
		UUID orgUuid = orgUserData.getOrgUuid();
		
		if (orgUuid == null) {
			return new Permissions();
		}
		
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		RelizaObject roUsers = od.isPresent() ? od.get() : null;
		authorizationService.isUserAuthorizedForObjectGraphQL(oud.get(), PermissionFunction.RESOURCE, PermissionScope.ORGANIZATION, orgUuid, List.of(roUsers), CallType.ADMIN);
		
		var targetUser = userService.getUserDataWithOrg(userUuid, orgUuid)
				.orElseThrow(() -> new AccessDeniedException("User is not in organization"));
		return organizationService.obtainCombinedUserOrgPermissions(targetUser, orgUuid);
	}
	
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setPermissionsOnFreeformApiKey")
	public ApiKeyDto setPermissionsOnFreeformApiKey(
			@InputArgument("apiKeyUuid") UUID apiKeyUuid,
			@InputArgument("permissionType") PermissionType permissionType,
			@InputArgument("permissions") List<LinkedHashMap<String, Object>> permissions
		) throws RelizaException {
		var oakd = apiKeyService.getApiKeyData(apiKeyUuid);
		boolean holderMayEdit = oakd.isPresent() && oakd.get().getStatus() == ApiKeyData.ApiKeyStatus.REQUESTED; // the requester shapes the proposal; admins own it once approved
		WhoUpdated wu = authorizeKeyOwnerOr(apiKeyUuid, CallType.ADMIN, holderMayEdit);
		OrganizationData od = getOrganizationService.getOrganizationData(oakd.get().getOrg()).get();
		List<PermissionDto> convertedPermissions = permissions.stream()
				.map(p -> Utils.OM.convertValue(p, PermissionDto.class)).collect(Collectors.toList());
		for (PermissionDto p : convertedPermissions) {
			if (null != p.approvals() && !p.approvals().isEmpty()) {
				if (!Utils.isSanitizedApprovalsSent(p.approvals(), od)) {
					throw new RuntimeException("Invalid approvals sent");
				}
			}
		}
		Optional<ApiKey> oak = apiKeyService.getApiKey(apiKeyUuid);
		if (oak.isPresent() && oak.get().getObjectType() == ApiTypeEnum.USER) {
			// a personal key stores no more than its owner holds right now, whoever edits it; call time intersects again
			UserData owner = userService.getUserData(oak.get().getObjectUuid()).orElseThrow(() -> new RelizaException("Key owner not found"));
			AuthorizationService.ClampedPermissions clamped = authorizationService.clampToOwner(owner, oak.get().getOrg(), permissionType, convertedPermissions);
			return apiKeyService.setPermissionsOnApiKey(apiKeyUuid, clamped.orgType(), clamped.permissions(), wu);
		}
		return apiKeyService.setPermissionsOnApiKey(apiKeyUuid, permissionType, convertedPermissions, wu);
	}

	/**
	 * Edit just the {@code notes} field on an API key. Admin auth on
	 * the key's owning org -- same gate as setPermissionsOnFreeformApiKey
	 * -- so a notes-only edit doesn't require the caller to round-trip
	 * the full permissions list. Works for any key type, not only
	 * FREEFORM (notes is a generic field on api_keys recordData).
	 */
	@Transactional
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "setNotesOnApiKey")
	public ApiKeyDto setNotesOnApiKey(
			@InputArgument("apiKeyUuid") UUID apiKeyUuid,
			@InputArgument("notes") String notes
		) throws RelizaException {
		WhoUpdated wu = authorizeKeyOwnerOr(apiKeyUuid, CallType.ADMIN);
		return apiKeyService.setNotesOnApiKey(apiKeyUuid, notes, wu);
	}

	private void validateRegexPatterns(List<String> patterns, String fieldName) throws RelizaException {
		if (patterns == null) {
			return;
		}
		for (String pattern : patterns) {
			try {
				java.util.regex.Pattern.compile(pattern);
			} catch (java.util.regex.PatternSyntaxException e) {
				throw new RelizaException("Invalid regex pattern in " + fieldName + ": " + pattern + " - " + e.getMessage());
			}
		}
	}
	
}