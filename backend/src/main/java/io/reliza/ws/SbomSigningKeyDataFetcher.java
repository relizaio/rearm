/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.Collections;
import java.util.List;
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
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SbomSigningKeyService;
import io.reliza.service.UserService;

/**
 * GraphQL surface of SBOM signing keys (SCORE-32): org ADMIN generates, imports and revokes, org READ
 * lists. On {@code user.graphqls} only, so the programmatic endpoint refuses every field here. The
 * {@code SbomSigningKey} type carries public material only; {@code publicKeyJwk} is resolved here as
 * a JSON string.
 */
@DgsComponent
public class SbomSigningKeyDataFetcher {

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private UserService userService;

	@Autowired
	private GetOrganizationService getOrganizationService;

	@Autowired
	private SbomSigningKeyService sbomSigningKeyService;

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Query", field = "sbomSigningKeys")
	public List<SbomSigningKeyData> sbomSigningKeys(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		authorizeOnOrg(currentUser(), orgUuid, CallType.READ);
		return sbomSigningKeyService.listByOrg(orgUuid);
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "generateSbomSigningKey")
	public SbomSigningKeyData generateSbomSigningKey(@InputArgument("orgUuid") UUID orgUuid) throws RelizaException {
		UserData ud = currentUser();
		authorizeOnOrg(ud, orgUuid, CallType.ADMIN);
		return sbomSigningKeyService.generate(orgUuid, WhoUpdated.getWhoUpdated(ud));
	}

	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "importSbomSigningKey")
	public SbomSigningKeyData importSbomSigningKey(@InputArgument("orgUuid") UUID orgUuid,
			@InputArgument("privateKeyPem") String privateKeyPem) throws RelizaException {
		UserData ud = currentUser();
		authorizeOnOrg(ud, orgUuid, CallType.ADMIN);
		return sbomSigningKeyService.importKey(orgUuid, privateKeyPem, WhoUpdated.getWhoUpdated(ud));
	}

	/**
	 * Authorized against the key's own org, read from the key; an unknown key authorizes against
	 * nothing and is refused as such (the {@code revokeSigningKey} precedent), so a caller learns
	 * nothing about keys outside the orgs it administers.
	 */
	@PreAuthorize("isAuthenticated()")
	@DgsData(parentType = "Mutation", field = "revokeSbomSigningKey")
	public SbomSigningKeyData revokeSbomSigningKey(@InputArgument("uuid") UUID uuid) throws RelizaException {
		UserData ud = currentUser();
		Optional<SbomSigningKeyData> okd = sbomSigningKeyService.getKey(uuid);
		RelizaObject ro = okd.orElse(null);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, null != ro ? ro.getOrg() : null, Collections.singletonList(ro),
				CallType.ADMIN);
		if (okd.isEmpty()) throw new RelizaException(SbomSigningKeyService.NOT_FOUND_MESSAGE);
		return sbomSigningKeyService.revoke(uuid, WhoUpdated.getWhoUpdated(ud));
	}

	@DgsData(parentType = "SbomSigningKey", field = "publicKeyJwk")
	public String publicKeyJwkOfSbomSigningKey(DgsDataFetchingEnvironment dfe) {
		SbomSigningKeyData kd = dfe.getSource();
		return null == kd.getPublicKeyJwk() ? null : Utils.OM.writeValueAsString(SbomSigningKeyService.publicJwk(kd));
	}

	private UserData currentUser() {
		JwtAuthenticationToken auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
		return userService.getUserDataByAuth(auth).orElseThrow();
	}

	private void authorizeOnOrg(UserData ud, UUID orgUuid, CallType ct) throws RelizaException {
		Optional<OrganizationData> od = getOrganizationService.getOrganizationData(orgUuid);
		authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.RESOURCE,
				PermissionScope.ORGANIZATION, orgUuid, Collections.singletonList(od.orElse(null)), ct);
	}
}
