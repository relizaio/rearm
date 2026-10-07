/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;

/**
 * The release (and variant) a programmatic write targets, resolved and authorized in one place for
 * addArtifactProgrammatic and addOutboundDeliverablesProgrammatic.
 *
 * <p>Rules: the key is verified first; every lookup is scoped to the key's organization and only
 * a release of that organization is a target (the shared external organization is readable, never
 * written through these); the release must belong to the component the call is authorized for.
 * A missing, malformed, foreign or inaccessible release (or variant) gets one answer --
 * {@link CommonVariables#PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE} or
 * {@link CommonVariables#PROGRAMMATIC_VARIANT_NOT_FOUND_MESSAGE} -- and nothing is written. No
 * caller is exempt: these mutations are served only on the programmatic endpoint, and a key,
 * whoever owns it, is bound to its own organization.
 */
@Service
public class ProgrammaticReleaseTargetService {

	/** Key types other than RBAC keys that may write through these mutations. */
	private static final List<ApiTypeEnum> SUPPORTED_API_TYPES = List.of(ApiTypeEnum.COMPONENT, ApiTypeEnum.ORGANIZATION_RW);

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private VariantService variantService;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private ReleaseService releaseService;

	/** Whether a mutation takes RBAC keys (FREEFORM / USER / FEDERATED) besides component and org keys. */
	public enum RbacKeyPolicy { ACCEPTED, REFUSED }

	/** The resolved target: variant is empty unless the caller named one. */
	public record ProgrammaticReleaseTarget(ReleaseData release, Optional<VariantData> variant,
			ComponentData component, WhoUpdated whoUpdated) {}

	/**
	 * @param componentStr the component the caller named, may be null (a component key writes its
	 *        own component; naming another is refused as before)
	 * @param releaseStr release uuid, may be null
	 * @param version release version, located on the named component, may be null
	 * @param variantStr variant uuid, may be null; when a release is also requested the variant
	 *        must be one of its variants (another release's reads as not found)
	 * @param rbacKeys whether FREEFORM / USER / FEDERATED keys may write through the caller
	 */
	public ProgrammaticReleaseTarget resolveProgrammaticReleaseTarget(AuthHeaderParse ahp, String componentStr,
			String releaseStr, String version, String variantStr, RbacKeyPolicy rbacKeys) throws RelizaException {
		if (null == ahp) throw new AccessDeniedException("Invalid authorization type");
		boolean typeAccepted = ahp.isRbacKey() ? RbacKeyPolicy.ACCEPTED == rbacKeys
				: null != ahp.getType() && SUPPORTED_API_TYPES.contains(ahp.getType());
		if (!typeAccepted) throw new AccessDeniedException("Unsupported object type");

		// Key-level checks, before any lookup.
		VerifiedProgrammaticKey vk = authorizationService.verifyProgrammaticKey(ahp);
		UUID org = vk.org();

		// The component: a component key's own, else the one named. It is looked up in the key's
		// org and authorized before any release lookup; missing, malformed, foreign and not
		// writable all read the same.
		UUID suppliedComponentId = null;
		if (StringUtils.isNotEmpty(componentStr)) {
			suppliedComponentId = Utils.parseUuid(componentStr)
					.orElseThrow(() -> new RelizaException(CommonVariables.PROGRAMMATIC_COMPONENT_NOT_FOUND_MESSAGE));
		}
		UUID componentId = Utils.bindProgrammaticComponentId(suppliedComponentId, ahp);
		ComponentData cd = null;
		WhoUpdated wu = null;
		if (null != componentId) {
			cd = getComponentService.getComponentData(componentId, org)
					.orElseThrow(() -> new RelizaException(CommonVariables.PROGRAMMATIC_COMPONENT_NOT_FOUND_MESSAGE));
			wu = authorizeComponentWrite(vk.principal(), cd, null,
					CommonVariables.PROGRAMMATIC_COMPONENT_NOT_FOUND_MESSAGE);
		}

		// The release: by uuid (scoped to the key's org) when one is given -- no falling back to the
		// version then -- else by version on the authorized component. A requested release that
		// does not resolve is refused, a variant or not.
		boolean releaseRequested = StringUtils.isNotEmpty(releaseStr) || (StringUtils.isNotEmpty(version) && null != cd);
		Optional<ReleaseData> ord = Optional.empty();
		if (StringUtils.isNotEmpty(releaseStr)) {
			ord = Utils.parseUuid(releaseStr).flatMap(u -> sharedReleaseService.getReleaseData(u, org));
		} else if (StringUtils.isNotEmpty(version) && null != cd) {
			ord = releaseService.getReleaseDataByComponentAndVersion(cd.getUuid(), version);
		}
		if (releaseRequested && ord.isEmpty()) {
			throw new RelizaException(CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE);
		}

		Optional<VariantData> ovd = Optional.empty();
		if (StringUtils.isNotEmpty(variantStr)) {
			ovd = Utils.parseUuid(variantStr).flatMap(variantService::getVariantData);
			Optional<ReleaseData> ovrd = ovd.map(VariantData::getRelease)
					.flatMap(r -> sharedReleaseService.getReleaseData(r, org));
			if (ovrd.isEmpty()) throw new RelizaException(CommonVariables.PROGRAMMATIC_VARIANT_NOT_FOUND_MESSAGE);
			// a variant of another release reads like a missing variant
			if (ord.isPresent() && !ord.get().getUuid().equals(ovrd.get().getUuid())) {
				throw new RelizaException(CommonVariables.PROGRAMMATIC_VARIANT_NOT_FOUND_MESSAGE);
			}
			ord = ovrd;
		}
		if (ord.isEmpty()) throw new RelizaException(CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE);
		ReleaseData rd = ord.get();
		String notFound = ovd.isPresent() ? CommonVariables.PROGRAMMATIC_VARIANT_NOT_FOUND_MESSAGE
				: CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE;

		if (null == cd) {
			// No component named: the release's own component, looked up in the key's org (so a
			// release of the shared external org never qualifies) and authorized; a key that may
			// not write it must not learn that the release exists.
			cd = getComponentService.getComponentData(rd.getComponent(), org)
					.orElseThrow(() -> new RelizaException(notFound));
			wu = authorizeComponentWrite(vk.principal(), cd, rd, notFound);
		}

		// Every write the caller makes targets this release: it must be the authorized
		// component's, whatever path resolved it; one that is not reads "not found".
		if (!cd.getUuid().equals(rd.getComponent())) throw new RelizaException(notFound);
		return new ProgrammaticReleaseTarget(rd, ovd, cd, wu);
	}

	/**
	 * WRITE on the component; {@code rd}, when already known, rides in the RBAC org check with it.
	 * The key was verified already, so a refusal here is about this object and reads
	 * {@code notFound}.
	 */
	private WhoUpdated authorizeComponentWrite(AuthHeaderParse principal, ComponentData cd, ReleaseData rd,
			String notFound) throws RelizaException {
		try {
			WhoUpdated wu;
			if (principal.isRbacKey()) {
				// FREEFORM keys carry scope/function permission tuples: COMPONENT-scoped READ_WRITE on
				// this component, a PERSPECTIVE permission containing it, or org-wide READ_WRITE all match.
				List<RelizaObject> ros = (null == rd) ? List.of(cd) : List.of(cd, rd);
				wu = authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(principal, PermissionFunction.RESOURCE,
						PermissionScope.COMPONENT, cd.getUuid(), ros, CallType.WRITE).whoUpdated();
			} else {
				wu = authorizationService.isApiKeyAuthorized(principal, SUPPORTED_API_TYPES, cd.getOrg(), CallType.WRITE, cd)
						.getWhoUpdated();
			}
			return wu;
		} catch (AccessDeniedException e) {
			throw new RelizaException(notFound);
		}
	}
}
