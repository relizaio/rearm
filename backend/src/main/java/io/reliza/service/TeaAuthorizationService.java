/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.ComponentData;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-object authorization of the TEA API (task TEA-3): the contract the TEA 1.0.0 controllers call for every
 * object and every listing. An object resolves to its component, the component to its effective TEA profile
 * (task TEA-2). PUBLIC serves whatever token was presented; PRIVATE needs a valid token of an EXTERNAL key whose
 * grants cover the component; anything concealed, not covered or unknown answers the same 404.
 */
@Slf4j
@Service
public class TeaAuthorizationService {

	/** The request attribute the TEA authentication filter leaves its classification in. */
	public static final String CALLER_ATTRIBUTE = "io.reliza.tea.caller";

	static final String INVALID_TOKEN_CHALLENGE = "Bearer realm=\"tea\", error=\"invalid_token\", "
			+ "error_description=\"The access token is invalid, expired or revoked\"";
	static final String CHALLENGE = "Bearer realm=\"tea\"";

	@Autowired private TeaProfileResolver teaProfileResolver;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApiKeyAccessService apiKeyAccessService;
	@Autowired private AuthorizationService authorizationService;

	public enum TeaAuthState { ANONYMOUS, INVALID_TOKEN, AUTHENTICATED }

	/** The EXTERNAL key behind a valid TEA access token. */
	public record TeaPrincipal(UUID keyUuid, UUID org, String keyId, Integer slot, String remoteIp) {}

	/** Who is calling, as the TEA authentication filter classified the request. */
	public record TeaCaller(TeaAuthState state, TeaPrincipal principal, String reason) {
		public static TeaCaller anonymous() { return new TeaCaller(TeaAuthState.ANONYMOUS, null, null); }
		public static TeaCaller invalidToken(String reason) { return new TeaCaller(TeaAuthState.INVALID_TOKEN, null, reason); }
		public static TeaCaller authenticated(TeaPrincipal p) { return new TeaCaller(TeaAuthState.AUTHENTICATED, p, null); }
	}

	public enum TeaAccess { SERVE, UNAUTHENTICATED, INVALID_TOKEN, CONCEAL }

	/** The filter's classification of the request; anonymous when the filter did not run. */
	public TeaCaller callerOf(HttpServletRequest request) {
		Object c = request == null ? null : request.getAttribute(CALLER_ATTRIBUTE);
		return c instanceof TeaCaller tc ? tc : TeaCaller.anonymous();
	}

	/**
	 * The caller as the served organization sees it (task TEA-18): a valid token of a key of another
	 * organization is no credential here and is handled as an absent Authorization header. Anonymous
	 * and invalid-token callers pass through unchanged; a null caller or organization is anonymous.
	 */
	public static TeaCaller callerOn(TeaCaller caller, UUID org) {
		if (caller == null || org == null) return TeaCaller.anonymous();
		if (caller.state() != TeaAuthState.AUTHENTICATED) return caller;
		TeaPrincipal p = caller.principal();
		return p != null && org.equals(p.org()) ? caller : TeaCaller.anonymous();
	}

	public TeaAccess accessToComponent(TeaCaller caller, UUID org, ComponentData cd) {
		if (cd == null || org == null || !org.equals(cd.getOrg())) return TeaAccess.CONCEAL;
		return accessToComponent(caller, org, cd, teaProfileResolver.resolveForComponent(cd));
	}

	/**
	 * The decision for one component under a resolution the caller already has (a publication's recorded
	 * profile). First match wins: unknown or another org's component, a CONFLICT and publishing DISABLED conceal
	 * from everyone; PUBLIC serves everyone; PRIVATE asks for a valid token whose grants cover the component.
	 */
	public TeaAccess accessToComponent(TeaCaller caller, UUID org, ComponentData cd, TeaResolvedProfile resolved) {
		if (cd == null || org == null || !org.equals(cd.getOrg())) return TeaAccess.CONCEAL;
		if (resolved == null || resolved.status() == TeaResolutionStatus.CONFLICT || resolved.profile() == null) return TeaAccess.CONCEAL;
		if (resolved.profile().getPublishing() != TeaPublishing.ENABLED) return TeaAccess.CONCEAL;
		TeaCaller c = callerOn(caller, org);
		if (resolved.profile().getVisibility() == TeaVisibility.PUBLIC) {
			recordServed(c, org);
			return TeaAccess.SERVE;
		}
		switch (c.state()) {
			case ANONYMOUS: return TeaAccess.UNAUTHENTICATED;
			case INVALID_TOKEN: return TeaAccess.INVALID_TOKEN;
			case AUTHENTICATED:
			default:
				if (covers(c.principal(), org, cd.getUuid())) {
					recordServed(c, org);
					return TeaAccess.SERVE;
				}
				return TeaAccess.CONCEAL;
		}
	}

	private void recordServed(TeaCaller c, UUID org) {
		if (c.state() != TeaAuthState.AUTHENTICATED || c.principal() == null) return;
		TeaPrincipal p = c.principal();
		try {
			apiKeyAccessService.recordApiKeyAccess(p.keyUuid(), p.remoteIp(), org, p.keyId());
		} catch (RuntimeException e) {
			log.error("could not record TEA access of key {}", p.keyUuid(), e);
		}
	}

	/** Whether the key's grants cover the component for TEA_READ; the key is re-read so a disable applies at once. */
	public boolean covers(TeaPrincipal principal, UUID org, UUID componentUuid) {
		Optional<Set<UserPermission>> grants = liveGrants(principal, org);
		if (grants.isEmpty() || componentUuid == null) return false;
		return grants.get().stream().anyMatch(g -> authorizationService.doesPermissionAuthorize(g, org, PermissionFunction.TEA_READ,
				PermissionScope.COMPONENT, componentUuid, CallType.READ));
	}

	/** The listing side: null for every component (an org-wide grant), else the covered set (empty: none). */
	public Set<UUID> coveredComponentUuids(TeaPrincipal principal, UUID org) {
		Optional<Set<UserPermission>> grants = liveGrants(principal, org);
		if (grants.isEmpty()) return Set.of();
		return authorizationService.coveredComponentUuids(grants.get(), org, PermissionFunction.TEA_READ);
	}

	private Optional<Set<UserPermission>> liveGrants(TeaPrincipal principal, UUID org) {
		if (principal == null || org == null || !org.equals(principal.org())) return Optional.empty();
		Optional<ApiKeyData> oakd = apiKeyService.getApiKeyData(principal.keyUuid());
		if (oakd.isEmpty() || oakd.get().getStatus() != ApiKeyStatus.ACTIVE || !org.equals(oakd.get().getOrg())) return Optional.empty();
		return Optional.of(oakd.get().getPermissions(org).getOrgPermissionsAsSet(org));
	}

	/** The response for a refusal; null for SERVE. Every refusal is no-store. */
	public ResponseEntity<TeaErrorResponse> deny(TeaAccess access) {
		return denial(access);
	}

	/** {@link #deny} without an instance, for the TEA controllers' thrown refusals (task TEA-6). */
	public static ResponseEntity<TeaErrorResponse> denial(TeaAccess access) {
		if (access == null || access == TeaAccess.SERVE) return null;
		return switch (access) {
			case UNAUTHENTICATED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.header(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
			case INVALID_TOKEN -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.header(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
			default -> ResponseEntity.status(HttpStatus.NOT_FOUND).header(HttpHeaders.CACHE_CONTROL, "no-store")
					.contentType(MediaType.APPLICATION_JSON).body(new TeaErrorResponse(TeaUnknownErrorType.OBJECT_UNKNOWN));
		};
	}
}
