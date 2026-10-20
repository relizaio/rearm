/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.ApiKey;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;

/**
 * Builds the {@link SbomWriteAuthority} of a request's principal (SCORE-52 ADR-2). Each entry
 * point builds it after its own authorization passed, from the same principal: the user, or the
 * principal {@link AuthorizationService#verifyProgrammaticKey} returned, whose key type, object and
 * organization are read from the stored key row, never from the presented header.
 *
 * <p>Nothing on the upload chain depends on this bean, so it adds no constructor cycle.
 */
@Service
public class SbomWriteAuthorityService {

	private final AuthorizationService authorizationService;
	private final GetComponentService getComponentService;
	private final GetOrganizationService getOrganizationService;
	private final ApiKeyService apiKeyService;

	public SbomWriteAuthorityService(AuthorizationService authorizationService, GetComponentService getComponentService,
			GetOrganizationService getOrganizationService, ApiKeyService apiKeyService) {
		this.authorizationService = authorizationService;
		this.getComponentService = getComponentService;
		this.getOrganizationService = getOrganizationService;
		this.apiKeyService = apiKeyService;
	}

	/**
	 * A user's rights in {@code org}: the decision path of the entry points' own checks
	 * ({@code isUserAuthorizedForObject}: combined user and group permissions, global admin).
	 */
	public SbomWriteAuthority forUser(UserData ud, UUID org) {
		String describe = "USER:" + (null == ud ? "none" : ud.getUuid());
		Optional<OrganizationData> od = null == ud || null == org ? Optional.empty()
				: getOrganizationService.getOrganizationData(org);
		if (od.isEmpty()) {
			return new BoundAuthority(org, () -> false, c -> false, describe);
		}
		return new BoundAuthority(org,
				() -> authorizationService.isUserAuthorizedForObject(ud, PermissionFunction.RESOURCE,
						PermissionScope.ORGANIZATION, org, List.of(od.get()), CallType.WRITE),
				cd -> authorizationService.isUserAuthorizedForObject(ud, PermissionFunction.RESOURCE,
						PermissionScope.COMPONENT, cd.getUuid(), List.of(cd), CallType.WRITE),
				describe);
	}

	/**
	 * A programmatic key's rights in {@code org}, from the principal {@code verifyProgrammaticKey}
	 * returned (it carries the verified key uuid, so no secret is checked again). A missing key row,
	 * or a row of another organization, answers false to both questions.
	 */
	public SbomWriteAuthority forKey(AuthHeaderParse verifiedPrincipal, UUID org) {
		UUID keyUuid = null == verifiedPrincipal ? null : verifiedPrincipal.getVerifiedKeyUuid();
		Optional<ApiKey> oak = null == keyUuid ? Optional.empty() : apiKeyService.getApiKey(keyUuid);
		if (oak.isEmpty() || null == org || !org.equals(oak.get().getOrg())) {
			return new BoundAuthority(org, () -> false, c -> false, "KEY:none:" + keyUuid);
		}
		ApiKey key = oak.get();
		String describe = "KEY:" + key.getObjectType() + ":" + key.getUuid();
		return switch (key.getObjectType()) {
			case ORGANIZATION_RW -> {
				boolean orgWide = org.equals(key.getObjectUuid());
				yield new BoundAuthority(org, () -> orgWide, c -> orgWide, describe);
			}
			case COMPONENT -> new BoundAuthority(org, () -> false, cd -> cd.getUuid().equals(key.getObjectUuid()),
					describe);
			case FREEFORM, USER, FEDERATED -> new BoundAuthority(org,
					() -> authorizationService.rbacKeyWrites(verifiedPrincipal, org, PermissionScope.ORGANIZATION, org),
					cd -> authorizationService.rbacKeyWrites(verifiedPrincipal, org, PermissionScope.COMPONENT,
							cd.getUuid()),
					describe);
			case ORGANIZATION, INSTANCE, CLUSTER, APPROVAL -> new BoundAuthority(org, () -> false, c -> false, describe);
		};
	}

	/**
	 * An authority bound to one organization: a component is asked about only when it exists and
	 * is of that organization. Both answers are memoised for the life of the object.
	 */
	private final class BoundAuthority implements SbomWriteAuthority {

		private final UUID org;
		private final BooleanSupplier orgWide;
		private final Predicate<ComponentData> componentWrite;
		private final String describe;
		private final Map<UUID, Boolean> canWriteMemo = new HashMap<>();
		private Boolean orgWideMemo;

		private BoundAuthority(UUID org, BooleanSupplier orgWide, Predicate<ComponentData> componentWrite,
				String describe) {
			this.org = org;
			this.orgWide = orgWide;
			this.componentWrite = componentWrite;
			this.describe = describe;
		}

		@Override
		public boolean orgWideWrite() {
			if (null == orgWideMemo) {
				orgWideMemo = orgWide.getAsBoolean();
			}
			return orgWideMemo;
		}

		@Override
		public boolean canWrite(UUID component) {
			if (null == component || null == org) {
				return false;
			}
			return canWriteMemo.computeIfAbsent(component, c -> getComponentService.getComponentData(c)
					.filter(cd -> org.equals(cd.getOrg()))
					.map(componentWrite::test)
					.orElse(false));
		}

		@Override
		public String describe() {
			return describe;
		}
	}
}
