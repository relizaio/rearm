/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.FederatedContext;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.ComponentData;
import io.reliza.model.FederatedGrant;
import io.reliza.model.FederatedGrant.GrantType;
import io.reliza.model.FederatedGrant.StaticPermission;
import io.reliza.model.FederatedIdentity;
import io.reliza.model.FederatedMatcher;
import io.reliza.model.FederatedTrustRule;
import io.reliza.model.FederatedTrustRule.Provider;
import io.reliza.model.FederatedTrustRule.Status;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.FederatedTrustRuleRepository;
import io.reliza.service.FederatedAssertionService.AssertionException;
import io.reliza.service.FederatedAssertionService.VerifiedAssertion;
import io.reliza.common.OAuthErrors;
import io.reliza.service.FederatedMatching.IdentityClaims;
import lombok.extern.slf4j.Slf4j;

/**
 * Trust rules of an org, the exchange of a verified identity token into a principal, and the
 * permissions a federated identity holds at check time. Shared layer: no saas imports.
 */
@Service
@Slf4j
public class FederatedTrustRuleService {

	@Autowired private FederatedTrustRuleRepository repository;
	@Autowired private io.reliza.repositories.FederatedAssertionIdRepository assertionIds;
	@Autowired private FederatedAssertionService assertionService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private GetComponentService getComponentService;

	// ---- rules -----------------------------------------------------------------------------

	public Optional<FederatedTrustRule> get(UUID uuid) { return repository.findById(uuid); }
	public List<FederatedTrustRule> listByOrg(UUID org) { return repository.findByOrg(org); }
	public List<FederatedTrustRule> listBoundToKey(UUID org, UUID keyUuid) { return repository.findByOrgAndBoundKey(org, keyUuid.toString()); }

	@Transactional
	public FederatedTrustRule create(UUID org, String name, Provider provider, String issuer, FederatedMatcher matcher,
			FederatedGrant grant, ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		FederatedTrustRule r = new FederatedTrustRule();
		r.setOrg(org);
		r.setProvider(provider == null ? Provider.GITHUB_ACTIONS : provider);
		r.setIssuer(defaultIssuer(r.getProvider(), issuer));
		String problem = FederatedAssertionService.issuerProblem(r.getIssuer());
		if (problem != null) throw new RelizaException("Refusing this issuer: " + problem);
		r.setCreatedBy(wu.getLastUpdatedBy());
		apply(r, name, matcher, grant, expiresDate, wu);
		r.setVersion(1);
		return repository.save(r);
	}

	/** Every edit bumps the version, which invalidates the tokens minted through the rule. */
	@Transactional
	public FederatedTrustRule update(UUID uuid, String name, FederatedMatcher matcher, FederatedGrant grant,
			ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		FederatedTrustRule r = repository.findById(uuid).orElseThrow(() -> new RelizaException("Trust rule not found"));
		apply(r, name, matcher, grant, expiresDate, wu);
		r.setVersion(r.getVersion() + 1);
		return repository.save(r);
	}

	private void apply(FederatedTrustRule r, String name, FederatedMatcher matcher, FederatedGrant grant,
			ZonedDateTime expiresDate, WhoUpdated wu) throws RelizaException {
		if (StringUtils.isBlank(name)) throw new RelizaException("Trust rule needs a name");
		if (matcher == null || StringUtils.isBlank(matcher.getOwner())) throw new RelizaException("Trust rule needs the owner (organization or user) on the provider side");
		if (grant == null) grant = new FederatedGrant();
		if (grant.getType() == GrantType.KEY) {
			if (grant.getKeyUuid() == null) throw new RelizaException("A key-bound rule needs the key to act as");
			ApiKey bound = apiKeyService.getApiKey(grant.getKeyUuid()).orElseThrow(() -> new RelizaException("Bound key not found"));
			if (bound.getObjectType() != ApiTypeEnum.FREEFORM || !r.getOrg().equals(bound.getOrg()))
				throw new RelizaException("A rule can only bind a Free Form key of the same organization");
		} else {
			for (StaticPermission sp : grant.getPermissions()) {
				if (sp.getScope() == null || sp.getObject() == null || sp.getType() == null)
					throw new RelizaException("Each extra permission needs scope, object and type");
			}
		}
		r.setName(name.trim());
		r.setMatcherOf(matcher);
		r.setGrantOf(grant);
		r.setExpiresDate(expiresDate);
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setLastUpdatedBy(wu.getLastUpdatedBy());
	}

	@Transactional
	public FederatedTrustRule setStatus(UUID uuid, Status status, WhoUpdated wu) throws RelizaException {
		FederatedTrustRule r = repository.findById(uuid).orElseThrow(() -> new RelizaException("Trust rule not found"));
		r.setStatus(status);
		r.setVersion(r.getVersion() + 1);
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setLastUpdatedBy(wu.getLastUpdatedBy());
		return repository.save(r);
	}

	@Transactional
	public void delete(UUID uuid) { repository.deleteById(uuid); }

	/** Accept whatever owner id the next exchange presents (after a rename or re-creation on the provider). */
	@Transactional
	public FederatedTrustRule resetOwnerPin(UUID uuid, WhoUpdated wu) throws RelizaException {
		FederatedTrustRule r = repository.findById(uuid).orElseThrow(() -> new RelizaException("Trust rule not found"));
		r.setPinnedOwnerId(null);
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setLastUpdatedBy(wu.getLastUpdatedBy());
		return repository.save(r);
	}

	public static String defaultIssuer(Provider provider, String issuer) {
		if (StringUtils.isNotBlank(issuer)) return issuer.trim().replaceAll("/+$", "");
		return switch (provider) {
			case GITHUB_ACTIONS -> FederatedMatching.GITHUB_ISSUER;
		};
	}

	// ---- exchange --------------------------------------------------------------------------

	public static class ExchangeException extends Exception {
		private static final long serialVersionUID = 1L;
		private final String code;
		public ExchangeException(String code, String message) { super(message); this.code = code; }
		public String code() { return code; }
	}

	/** What the token endpoint mints from: the key to act as, the rules that admitted the identity, and the claims kept. */
	public record Exchange(ApiKey principal, List<FederatedTrustRule> rules, FederatedContext context, Instant clip) {}

	/**
	 * RFC 7523 exchange. The issuer is read first so only rules naming it decide whether the
	 * token is verified at all; then the matching rules are grouped by org, the org resolved
	 * (unique, or the one named by {@code clientId}), key-bound rules take precedence over
	 * templates, ids are checked against the pins, and only then is anything written: the
	 * assertion id reserved, pins recorded, the identity row materialised, last use touched.
	 * A refused exchange rolls every write back, so it leaves no trace beyond the log.
	 */
	@Transactional(rollbackFor = ExchangeException.class)
	public Exchange exchange(String assertion, String clientId) throws ExchangeException {
		String issuer;
		try {
			issuer = assertionService.peekIssuer(assertion);
		} catch (AssertionException e) {
			throw new ExchangeException(OAuthErrors.INVALID_GRANT, e.getMessage());
		}
		if (StringUtils.isBlank(issuer)) throw new ExchangeException(OAuthErrors.INVALID_GRANT, "assertion has no issuer");
		List<FederatedTrustRule> candidates = repository.findActiveByIssuer(issuer, Status.ACTIVE.name()).stream().filter(FederatedTrustRule::isUsable).toList();
		if (candidates.isEmpty()) {
			log.warn("SECURITY: federated assertion from untrusted issuer {} refused", issuer);
			throw new ExchangeException(OAuthErrors.INVALID_GRANT, NO_MATCH);
		}
		Provider provider = candidates.get(0).getProvider();
		VerifiedAssertion va;
		try {
			va = assertionService.verify(assertion, issuer, provider);
		} catch (AssertionException e) {
			throw new ExchangeException(OAuthErrors.INVALID_GRANT, e.getMessage());
		}
		IdentityClaims id = va.identity();
		List<FederatedTrustRule> matching = candidates.stream().filter(r -> FederatedMatching.matches(r.matcherOf(), id)).toList();
		if (matching.isEmpty()) {
			log.warn("SECURITY: federated identity {} ({}) matched no trust rule", id.repository(), issuer);
			throw new ExchangeException(OAuthErrors.INVALID_GRANT, NO_MATCH);
		}
		Map<UUID, List<FederatedTrustRule>> byOrg = matching.stream().collect(Collectors.groupingBy(FederatedTrustRule::getOrg, LinkedHashMap::new, Collectors.toList()));
		UUID org;
		if (StringUtils.isNotBlank(clientId)) {
			try {
				org = UUID.fromString(clientId.trim());
			} catch (IllegalArgumentException e) {
				throw new ExchangeException(OAuthErrors.INVALID_CLIENT, "client_id must be the organization uuid");
			}
			if (!byOrg.containsKey(org)) {
				log.warn("SECURITY: federated identity {} matched rules, but none of org {} named as client_id", id.repository(), org);
				throw new ExchangeException(OAuthErrors.INVALID_GRANT, NO_MATCH);
			}
		} else if (byOrg.size() == 1) {
			org = byOrg.keySet().iterator().next();
		} else {
			throw new ExchangeException(OAuthErrors.INVALID_REQUEST, "this identity is trusted by several organizations; pass client_id=<organization uuid>");
		}
		List<FederatedTrustRule> rules = byOrg.get(org);
		// ---- checks: owner pins on the rules
		for (FederatedTrustRule r : rules) {
			if (r.getPinnedOwnerId() != null && !r.getPinnedOwnerId().equals(id.ownerId())) {
				log.warn("SECURITY: federated owner id changed for rule {} ({} vs pinned {}); an admin must reset the pin", r.getUuid(), id.ownerId(), r.getPinnedOwnerId());
				throw new ExchangeException(OAuthErrors.INVALID_GRANT, "the owner behind this identity changed since it was pinned; an administrator must reset the pin on rule " + r.getName());
			}
		}
		// ---- checks: the principal
		List<FederatedTrustRule> keyBound = rules.stream().filter(r -> r.grantOf().getType() == GrantType.KEY).toList();
		ApiKey principal = null;
		List<FederatedTrustRule> admitted;
		if (!keyBound.isEmpty()) {
			Set<UUID> keys = keyBound.stream().map(r -> r.grantOf().getKeyUuid()).collect(Collectors.toCollection(LinkedHashSet::new));
			if (keys.size() > 1) throw new ExchangeException(OAuthErrors.INVALID_REQUEST, "several key-bound rules match this identity and bind different keys");
			UUID keyUuid = keys.iterator().next();
			principal = apiKeyService.getApiKey(keyUuid).orElseThrow(() -> new ExchangeException(OAuthErrors.INVALID_GRANT, "the key bound by the rule is gone"));
			ApiKeyData akd = ApiKeyData.dataFromRecord(principal);
			if (principal.getObjectType() != ApiTypeEnum.FREEFORM || akd.getStatus() != ApiKeyStatus.ACTIVE || !org.equals(principal.getOrg()))
				throw new ExchangeException(OAuthErrors.INVALID_GRANT, "the key bound by the rule is not an active Free Form key of the organization");
			admitted = keyBound;
		} else {
			Optional<ApiKey> existing = apiKeyService.getFederatedIdentity(org, identityOf(id));
			if (existing.isPresent()) {
				ApiKeyData akd = ApiKeyData.dataFromRecord(existing.get());
				// INACTIVE is the admin's kill switch and durable; REVOKED is the tombstone of a deleted row, which the
				// rules re-materialise (trust comes from the rule, deleting a row only forgets its history)
				if (akd.getStatus() == ApiKeyStatus.INACTIVE) throw new ExchangeException(OAuthErrors.INVALID_GRANT, "this repository's identity is disabled in the organization");
				FederatedIdentity fi = akd.getFederation();
				if (fi != null && fi.getRepositoryId() != null && !fi.getRepositoryId().equals(id.repositoryId())) {
					log.warn("SECURITY: federated repository id changed for identity {} ({} vs pinned {})", existing.get().getUuid(), id.repositoryId(), fi.getRepositoryId());
					throw new ExchangeException(OAuthErrors.INVALID_GRANT, "the repository behind this identity changed since it was pinned; an administrator must reset the pin");
				}
			}
			admitted = rules;
		}
		// ---- writes: everything checked out
		if (assertionIds.reserve(issuer, va.jti(), ZonedDateTime.ofInstant(va.expiresAt(), java.time.ZoneOffset.UTC)) == 0) {
			log.warn("SECURITY: federated assertion replayed (issuer {}, jti {})", issuer, va.jti());
			throw new ExchangeException(OAuthErrors.INVALID_GRANT, "assertion already used");
		}
		try { assertionIds.purgeExpired(); } catch (RuntimeException e) { log.error("could not purge expired assertion ids", e); }
		for (FederatedTrustRule r : rules) {
			if (r.getPinnedOwnerId() == null) {
				r.setPinnedOwnerId(id.ownerId());
				repository.save(r);
			}
		}
		if (principal == null) {
			principal = apiKeyService.ensureFederatedIdentity(org, identityOf(id), WhoUpdated.getAutoWhoUpdated());
		}
		Instant clip = admitted.stream().map(FederatedTrustRule::getExpiresDate).filter(d -> d != null).map(ZonedDateTime::toInstant).min(Comparator.naturalOrder()).orElse(null);
		for (FederatedTrustRule r : admitted) {
			try { repository.touchLastUsed(r.getUuid()); } catch (RuntimeException e) { log.error("could not record last use of trust rule {}", r.getUuid(), e); }
		}
		FederatedContext ctx = new FederatedContext(admitted.stream().map(FederatedTrustRule::getUuid).toList(), id.provider().toString(), issuer,
				id.owner(), id.repository(), id.repositoryUri(), id.ref(), id.sha(), id.workflowRef(), id.environment(), id.event(), id.actor(), id.runId());
		return new Exchange(principal, admitted, ctx, clip);
	}

	/** One message for every "this token is not trusted here" outcome, so an unknown caller cannot probe which organizations trust an owner. */
	static final String NO_MATCH = "no trust rule matches this identity";

	private static FederatedIdentity identityOf(IdentityClaims id) {
		FederatedIdentity fi = new FederatedIdentity();
		fi.setProvider(id.provider());
		fi.setIssuer(id.issuer());
		fi.setOwner(id.owner());
		fi.setRepository(id.repository());
		fi.setRepositoryUri(id.repositoryUri());
		fi.setRepositoryId(id.repositoryId());
		fi.setOwnerId(id.ownerId());
		fi.setLastRef(id.ref());
		fi.setLastRunId(id.runId());
		fi.setLastActor(id.actor());
		return fi;
	}

	/** Names of the claims kept in the access token's {@code fed} member; the same list reads them back. */
	static final class FedClaim {
		static final String PROVIDER = "provider", ISSUER = "issuer", OWNER = "owner", REPOSITORY = "repository", REPOSITORY_URI = "repositoryUri",
				REF = "ref", SHA = "sha", WORKFLOW_REF = "workflowRef", ENVIRONMENT = "environment", EVENT = "event", ACTOR = "actor", RUN_ID = "runId";
		private FedClaim() {}
	}

	/** The claims copied into the access token; strings only. */
	public static Map<String, String> tokenClaimsOf(FederatedContext c) {
		Map<String, String> m = new LinkedHashMap<>();
		put(m, FedClaim.PROVIDER, c.provider()); put(m, FedClaim.ISSUER, c.issuer()); put(m, FedClaim.OWNER, c.owner()); put(m, FedClaim.REPOSITORY, c.repository());
		put(m, FedClaim.REPOSITORY_URI, c.repositoryUri()); put(m, FedClaim.REF, c.ref()); put(m, FedClaim.SHA, c.sha()); put(m, FedClaim.WORKFLOW_REF, c.workflowRef());
		put(m, FedClaim.ENVIRONMENT, c.environment()); put(m, FedClaim.EVENT, c.event()); put(m, FedClaim.ACTOR, c.actor()); put(m, FedClaim.RUN_ID, c.runId());
		return m;
	}

	public static FederatedContext contextOf(List<UUID> rules, Map<String, String> m) {
		if (m == null) m = Map.of();
		return new FederatedContext(rules, m.get(FedClaim.PROVIDER), m.get(FedClaim.ISSUER), m.get(FedClaim.OWNER), m.get(FedClaim.REPOSITORY), m.get(FedClaim.REPOSITORY_URI),
				m.get(FedClaim.REF), m.get(FedClaim.SHA), m.get(FedClaim.WORKFLOW_REF), m.get(FedClaim.ENVIRONMENT), m.get(FedClaim.EVENT), m.get(FedClaim.ACTOR), m.get(FedClaim.RUN_ID));
	}

	private static void put(Map<String, String> m, String k, String v) { if (v != null) m.put(k, v); }

	// ---- bearer verification -----------------------------------------------------------------

	/** The rules a token names, when every one of them is still usable and their versions still match the token. */
	public Optional<List<FederatedTrustRule>> usable(List<UUID> ruleUuids, String fingerprint) {
		if (ruleUuids == null || ruleUuids.isEmpty()) return Optional.empty();
		List<FederatedTrustRule> rules = new ArrayList<>();
		for (UUID u : ruleUuids) {
			Optional<FederatedTrustRule> r = repository.findById(u);
			if (r.isEmpty() || !r.get().isUsable()) return Optional.empty();
			rules.add(r.get());
		}
		if (!ApiTokenService.fingerprint(rules).equals(fingerprint)) return Optional.empty();
		return Optional.of(rules);
	}

	// ---- permissions at check time -------------------------------------------------------------

	/**
	 * What a TEMPLATE identity may do right now: the union over the admitting rules of the org-wide
	 * level, the level on every component whose VCS repository is the calling repository, and the
	 * static extras. Nothing is stored on the identity row, so both sides can change freely.
	 */
	public Set<UserPermission> permissionsFor(UUID org, FederatedContext ctx) {
		Set<UserPermission> out = new LinkedHashSet<>();
		if (ctx == null || ctx.ruleUuids() == null) return out;
		List<UUID> repoComponents = null;
		for (UUID ruleUuid : ctx.ruleUuids()) {
			Optional<FederatedTrustRule> or = repository.findById(ruleUuid);
			if (or.isEmpty() || !or.get().isUsable() || !org.equals(or.get().getOrg())) continue;
			FederatedGrant g = or.get().grantOf();
			if (g.getType() != GrantType.TEMPLATE) continue;
			Set<PermissionFunction> functions = new LinkedHashSet<>(g.getFunctions());
			functions.add(PermissionFunction.RESOURCE);
			if (g.getOrgPermission() != PermissionType.NONE) {
				out.add(UserPermission.permissionFactory(org, PermissionScope.ORGANIZATION, org, g.getOrgPermission(), functions, null));
			}
			if (g.getVcsPermission() != PermissionType.NONE) {
				if (repoComponents == null) repoComponents = componentsOfRepository(org, ctx.repositoryUri());
				for (UUID c : repoComponents) {
					out.add(UserPermission.permissionFactory(org, PermissionScope.COMPONENT, c, g.getVcsPermission(), functions, null));
				}
			}
			for (StaticPermission sp : g.getPermissions()) {
				Set<PermissionFunction> f = new LinkedHashSet<>(sp.getFunctions());
				f.add(PermissionFunction.RESOURCE);
				out.add(UserPermission.permissionFactory(org, sp.getScope(), sp.getObject(), sp.getType(), f, null));
			}
		}
		return out;
	}

	/** May this identity create components bound to {@code vcsUri} in {@code org}? Only its own repository, and only when a template says so. */
	public boolean mayCreateComponentUnder(UUID org, FederatedContext ctx, String vcsUri) {
		if (ctx == null || ctx.ruleUuids() == null || StringUtils.isBlank(vcsUri) || ctx.repositoryUri() == null) return false;
		String requested = Utils.cleanVcsUri(Utils.normalizeVcsUri(vcsUri.trim()));
		if (!requested.equalsIgnoreCase(ctx.repositoryUri())) return false;
		for (UUID ruleUuid : ctx.ruleUuids()) {
			Optional<FederatedTrustRule> or = repository.findById(ruleUuid);
			if (or.isEmpty() || !or.get().isUsable() || !org.equals(or.get().getOrg())) continue;
			FederatedGrant g = or.get().grantOf();
			if (g.getType() == GrantType.TEMPLATE && g.isCreateComponents() && g.getVcsPermission().ordinal() >= PermissionType.READ_WRITE.ordinal()) return true;
		}
		return false;
	}

	/** Components of the org whose VCS repository URI is the calling repository (case-insensitive on the cleaned form). */
	private List<UUID> componentsOfRepository(UUID org, String repositoryUri) {
		List<UUID> out = new ArrayList<>();
		if (repositoryUri == null) return out;
		for (VcsRepositoryData vrd : vcsRepositoryService.listVcsRepoDataByOrg(org)) {
			String uri = vrd.getUri() == null ? null : Utils.cleanVcsUri(Utils.normalizeVcsUri(vrd.getUri()));
			if (uri != null && uri.equalsIgnoreCase(repositoryUri)) {
				for (ComponentData cd : getComponentService.listComponentDataByVcs(vrd.getUuid())) out.add(cd.getUuid());
			}
		}
		return out;
	}
}
