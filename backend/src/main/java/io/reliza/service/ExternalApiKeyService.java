/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.LogSafe;
import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ComponentData;
import io.reliza.model.Organization;
import io.reliza.model.RelizaObject;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ApiKeyDto;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.oss.OssPerspectiveService;
import lombok.extern.slf4j.Slf4j;

/**
 * EXTERNAL keys (task TEA-3): keys an organization hands to a party outside it. They authenticate at the TEA
 * token endpoint only and read what the organization published on TEA. The printed id carries the
 * organization's TEA id ({@code EXTERNAL__<teaOrg>__ord__<keyOrder>}); the row keeps the real org uuid.
 * Grants are the restricted RBAC shape: READ_ONLY at ORGANIZATION, PERSPECTIVE or COMPONENT scope, stored with
 * the single function {@link PermissionFunction#TEA_READ}.
 */
@Slf4j
@Service
public class ExternalApiKeyService {

	public static final int MAX_HOLDER_NAME = 200;
	static final Set<PermissionScope> GRANT_SCOPES = Set.of(PermissionScope.ORGANIZATION, PermissionScope.PERSPECTIVE,
			PermissionScope.COMPONENT);

	@Autowired private ApiKeyService apiKeyService;
	@Autowired private OrganizationService organizationService;
	@Autowired private OrganizationRepository organizationRepository;
	@Autowired private GetComponentService getComponentService;
	@Autowired private OssPerspectiveService ossPerspectiveService;

	/** The outcome of a successful exchange check: the row, its record and the secret that matched. */
	public record ExternalKeyMatch(ApiKey key, ApiKeyData data, ApiKeySecret secret) {}

	/**
	 * The direction of a lifecycle change on an EXTERNAL key (TEA-3 design round 2, the one-way rule): a change that
	 * restores or extends an outside party's access WIDENS and needs org ADMIN, as minting does; a change that
	 * withdraws it NARROWS and keeps org WRITE.
	 */
	public enum AccessChange { WIDENS, NARROWS }

	/** Re-activating widens; any other status narrows. */
	public static AccessChange ofStatus(ApiKeyData.ApiKeyStatus requested) {
		return requested == ApiKeyData.ApiKeyStatus.ACTIVE ? AccessChange.WIDENS : AccessChange.NARROWS;
	}

	/** Re-enabling a secret widens; retiring it narrows. */
	public static AccessChange ofSecretActive(boolean requested) {
		return requested ? AccessChange.WIDENS : AccessChange.NARROWS;
	}

	/**
	 * A cleared expiry (never expires) widens; a first expiry on a secret that never expired narrows; a later expiry
	 * widens; an equal or earlier one narrows. Instants are compared, so the zone does not matter.
	 */
	public static AccessChange ofExpiry(ZonedDateTime current, ZonedDateTime requested) {
		if (requested == null) return AccessChange.WIDENS;
		if (current == null) return AccessChange.NARROWS;
		return requested.toInstant().isAfter(current.toInstant()) ? AccessChange.WIDENS : AccessChange.NARROWS;
	}

	/**
	 * Create the key id for the named outside party, with no secret and no grant. Mints the organization's TEA
	 * id first when it has none, so the printed id can carry it.
	 */
	@Transactional
	public ApiKey create(UUID org, String holderName, String notes, WhoUpdated wu) throws RelizaException {
		String name = validHolderName(holderName);
		UUID teaOrg = organizationService.ensureTeaUuid(org, wu);
		return apiKeyService.createExternalKeyRow(org, teaOrg, name, notes, wu);
	}

	public ApiKeyDto setHolderName(UUID keyUuid, String holderName, WhoUpdated wu) throws RelizaException {
		String name = validHolderName(holderName);
		return apiKeyService.writeExternalHolderName(keyUuid, name, wu);
	}

	static String validHolderName(String holderName) throws RelizaException {
		String name = StringUtils.trimToEmpty(holderName);
		if (name.isEmpty() || name.length() > MAX_HOLDER_NAME) {
			throw new RelizaException("The holder name of an external key is required, 1 to " + MAX_HOLDER_NAME + " characters");
		}
		return name;
	}

	/**
	 * Replace the key's grants wholesale. Each grant is checked and normalized before anything is written: scope
	 * ORGANIZATION, PERSPECTIVE or COMPONENT; type READ_ONLY (a NONE entry is dropped); functions forced to
	 * {TEA_READ}, approvals emptied; the object must be the org, a live perspective of the org (Pro) or a
	 * component of the org. Two grants on one (scope, object) collapse into one.
	 */
	public ApiKeyDto setGrants(UUID keyUuid, List<PermissionDto> grants, WhoUpdated wu) throws RelizaException {
		ApiKey ak = apiKeyService.getApiKey(keyUuid).orElseThrow(() -> new RelizaException("API key not found"));
		if (ak.getObjectType() != ApiTypeEnum.EXTERNAL) throw new RelizaException("Only external keys take TEA grants");
		List<PermissionDto> normalized = normalizeGrants(ak.getOrg(), grants);
		return apiKeyService.writeExternalGrants(keyUuid, normalized, wu);
	}

	List<PermissionDto> normalizeGrants(UUID org, List<PermissionDto> grants) throws RelizaException {
		Map<String, PermissionDto> out = new LinkedHashMap<>();
		for (PermissionDto p : grants == null ? List.<PermissionDto>of() : grants) {
			if (p == null) continue;
			String label = "grant " + p.scope() + (p.object() == null ? "" : " " + p.object());
			if (p.org() != null && !p.org().equals(org)) {
				throw new RelizaException("The " + label + " names another organization; an external key reads its own organization only");
			}
			if (p.scope() == null || !GRANT_SCOPES.contains(p.scope())) {
				throw new RelizaException("The " + label + " has scope " + p.scope()
						+ "; an external key is granted at ORGANIZATION, PERSPECTIVE or COMPONENT scope only");
			}
			if (p.type() == PermissionType.NONE) continue;
			if (p.type() != PermissionType.READ_ONLY) {
				throw new RelizaException("The " + label + " has type " + p.type() + "; an external key is READ_ONLY only");
			}
			UUID object = switch (p.scope()) {
				case ORGANIZATION -> org;
				case COMPONENT -> componentOfOrg(org, p.object(), label);
				case PERSPECTIVE -> perspectiveOfOrg(org, p.object(), label);
				default -> throw new RelizaException("The " + label + " has an unsupported scope");
			};
			out.put(p.scope() + "|" + object, new PermissionDto(org, p.scope(), object, PermissionType.READ_ONLY,
					Set.of(PermissionFunction.TEA_READ), List.of()));
		}
		return List.copyOf(out.values());
	}

	private UUID componentOfOrg(UUID org, UUID object, String label) throws RelizaException {
		Optional<ComponentData> ocd = object == null ? Optional.empty() : getComponentService.getComponentData(object);
		if (ocd.isEmpty()) throw new RelizaException("The " + label + " names a component that does not exist");
		if (!org.equals(ocd.get().getOrg())) throw new RelizaException("The " + label + " names a component of another organization");
		return object; // a PRODUCT is accepted and expands to its tree at check time
	}

	private UUID perspectiveOfOrg(UUID org, UUID object, String label) throws RelizaException {
		if (!ossPerspectiveService.teaPerspectivesSupported()) {
			throw new RelizaException("The " + label + " is refused: perspectives are a ReARM Pro feature");
		}
		Optional<OssPerspectiveService.TeaPerspectiveRef> ref = ossPerspectiveService.teaPerspectiveRef(object);
		if (ref.isEmpty()) throw new RelizaException("The " + label + " names a perspective that was not found");
		if (ref.get().type() == PerspectiveType.PRODUCT) {
			throw new RelizaException("The " + label + " names a product: grant the product as a component");
		}
		Optional<RelizaObject> owner = ossPerspectiveService.teaPerspectiveObject(object);
		if (owner.isEmpty() || !org.equals(owner.get().getOrg())) {
			throw new RelizaException("The " + label + " names a perspective of another organization");
		}
		return object;
	}

	/**
	 * The live EXTERNAL row a printed id names: {@code EXTERNAL__<teaOrg>__ord__<keyOrder>}, the TEA org id mapped
	 * back to the organization, of an organization that is not archived (TEA-22). Empty for anything else.
	 */
	public Optional<ApiKey> findByPrintedId(String keyId) {
		if (StringUtils.isBlank(keyId)) return Optional.empty();
		String[] orderSplit = keyId.trim().split("__ord__", 2);
		if (orderSplit.length != 2 || StringUtils.isEmpty(orderSplit[1])) return Optional.empty();
		String[] head = orderSplit[0].split("__");
		if (head.length != 2 || !ApiTypeEnum.EXTERNAL.toString().equals(head[0])) return Optional.empty();
		UUID teaOrg;
		try {
			teaOrg = UUID.fromString(head[1]);
		} catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
		Optional<Organization> oorg = organizationRepository.findByTeaUuid(teaOrg.toString());
		if (oorg.isEmpty()) return Optional.empty();
		UUID org = oorg.get().getUuid();
		return apiKeyService.getApiKeyByObjUuidTypeOrder(org, ApiTypeEnum.EXTERNAL, orderSplit[1], org);
	}

	/**
	 * The TEA token endpoint's credential check: the printed id must name a live EXTERNAL key of
	 * {@code expectedOrg} (the organization of the base path), and the secret must match one of its usable
	 * secrets. Empty for any failure; the reasons go to the SECURITY log, never to the caller.
	 */
	public Optional<ExternalKeyMatch> authenticate(String keyId, String secret, UUID expectedOrg, String remoteIp) {
		if (StringUtils.isEmpty(secret) || expectedOrg == null) return Optional.empty();
		Optional<ApiKey> oak = findByPrintedId(keyId);
		if (oak.isEmpty()) {
			log.warn("SECURITY: TEA token exchange refused - no external key {} (ip={})", LogSafe.text(keyId), remoteIp);
			return Optional.empty();
		}
		ApiKey ak = oak.get();
		if (!expectedOrg.equals(ak.getOrg())) {
			log.warn("SECURITY: TEA token exchange refused - key {} belongs to another organization than the path names (ip={})",
					ak.getUuid(), remoteIp);
			return Optional.empty();
		}
		AuthHeaderParse ahp = AuthHeaderParse.builder().type(ApiTypeEnum.EXTERNAL).objUuid(ak.getOrg()).orgUuid(ak.getOrg())
				.keyOrder(ak.getKeyOrder()).apiKey(secret).apiKeyId(keyId).remoteIp(remoteIp).build();
		UUID matched = apiKeyService.isMatchingApiKey(ahp);
		if (matched == null || !matched.equals(ak.getUuid())) return Optional.empty();
		ApiKey current = apiKeyService.getApiKey(matched).orElse(ak);
		ApiKeyData akd = ApiKeyData.dataFromRecord(current);
		int slot = ahp.getMatchedSecretSlot() == null ? 1 : ahp.getMatchedSecretSlot();
		Optional<ApiKeySecret> sec = akd.effectiveSecrets(current.getApiKey()).stream().filter(x -> x.getSlot() == slot).findFirst();
		if (sec.isEmpty()) return Optional.empty();
		return Optional.of(new ExternalKeyMatch(current, akd, sec.get()));
	}
}
