/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.RelizaObject;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaSupportMetadata;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVulnerabilityDocuments;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.repositories.TeaProfileRepository;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores TEA profiles (task TEA-2): one wholesale row per (org, scope, object), validated on
 * every save. Which row a component resolves to is {@link TeaProfileResolver}'s business.
 */
@Slf4j
@Service
public class TeaProfileService {

	/** The TEA versions the organization's discovery document announces. */
	public static final String TEA_API_VERSION = "1.0.0";

	/** The lifecycles a profile may require of a release before it is published. */
	public static final List<ReleaseLifecycle> ACCEPTED_MINIMUM_LIFECYCLES = List.of(ReleaseLifecycle.ASSEMBLED,
			ReleaseLifecycle.READY_TO_SHIP, ReleaseLifecycle.GENERAL_AVAILABILITY);

	@Autowired private TeaProfileRepository repository;
	@Autowired private AuditService auditService;
	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private OrganizationService organizationService;
	@Autowired private SupportInjectionService supportInjectionService;

	private RelizaConfigProps relizaConfigProps;

	@Autowired
	public void setProps(RelizaConfigProps relizaConfigProps) {
		this.relizaConfigProps = relizaConfigProps;
	}

	/**
	 * Where the organization's TEA API lives and the {@code .well-known/tea} document it hosts on
	 * its own domain. Both are null until the organization's TEA id is minted.
	 */
	public record TeaOrgDiscovery(UUID org, UUID teaUuid, String apiBase, String wellKnownDocument) {}

	/** The TEA 1.0.0 discovery shape: schemaVersion, endpoints with url, versions, priority. */
	public record WellKnownTea(int schemaVersion, List<WellKnownEndpoint> endpoints) {}

	public record WellKnownEndpoint(String url, List<String> versions, int priority) {}

	public Optional<TeaProfileData> getProfile(UUID org, TeaProfileScope scope, UUID object) {
		return findEntity(org, scope, object).map(TeaProfileData::dataFromRecord);
	}

	public List<TeaProfileData> listProfilesOfOrg(UUID org) {
		if (null == org) return List.of();
		return repository.listByOrg(org).stream().map(TeaProfileData::dataFromRecord).toList();
	}

	public List<TeaProfileData> listPerspectiveProfiles(UUID org, Collection<UUID> perspectives) {
		if (null == org || null == perspectives || perspectives.isEmpty()) return List.of();
		String[] ids = perspectives.stream().filter(u -> null != u).map(UUID::toString).distinct()
				.toArray(String[]::new);
		if (ids.length == 0) return List.of();
		return repository.listByScopeObjects(org, TeaProfileScope.PERSPECTIVE.name(), ids).stream()
				.map(TeaProfileData::dataFromRecord).toList();
	}

	private Optional<TeaProfile> findEntity(UUID org, TeaProfileScope scope, UUID object) {
		if (null == org || null == scope) return Optional.empty();
		if (TeaProfileScope.ORGANIZATION == scope) return repository.findOrgProfile(org);
		if (null == object) return Optional.empty();
		return repository.findByScopeObject(org, scope.name(), object);
	}

	/**
	 * Upsert the row of (org, scope, object) with {@code incoming}, wholesale. Every field of the
	 * row comes from {@code incoming}; an omitted required field is refused, never defaulted,
	 * because the editor pre-fills from the parent and a server default would silently turn a
	 * copied value into something else.
	 */
	@Transactional
	public TeaProfileData saveProfile(UUID org, TeaProfileScope scope, UUID object, TeaProfileInputDto incoming,
			WhoUpdated wu) throws RelizaException {
		if (null == org) throw new RelizaException("org is required");
		if (null == scope) throw new RelizaException("scope is required");
		if (null == incoming) throw new RelizaException("profile is required");
		OrganizationData od = getOrganizationService.getOrganizationData(org)
				.orElseThrow(() -> new RelizaException("Organization not found: " + org));

		TeaProfileData d = new TeaProfileData();
		d.setOrg(org);
		d.setScope(scope);
		validateScopeAndObject(org, scope, object, incoming, d);
		boolean follows = TeaProfileScope.COMPONENT == scope
				&& TeaComponentProfileMode.FOLLOW_PERSPECTIVE == incoming.getMode();
		if (!follows) {
			copyProfileFields(incoming, d);
			validateProfileFields(d, supportInjectionService.isInjectionEnabled(org));
		}

		Optional<TeaProfile> existing = findEntity(org, scope, object);
		TeaProfile e;
		if (existing.isPresent()) {
			e = existing.get();
			auditService.createAndSaveAuditRecord(TableName.TEA_PROFILE, e);
			e.setRevision(e.getRevision() + 1);
			e.setLastUpdatedDate(ZonedDateTime.now());
		} else {
			e = new TeaProfile();
			e.setUuid(UUID.randomUUID());
		}
		d.setUuid(e.getUuid());
		e.setOrg(org);
		e.setScope(scope.name());
		e.setObjectUuid(d.getObject());
		e.setRecordData(d.toRecordData());
		e = (TeaProfile) WhoUpdated.injectWhoUpdatedData(e, wu);
		TeaProfileData saved = TeaProfileData.dataFromRecord(repository.save(e));

		// "TEA first enabled" for an org is its first ENABLED save at any scope; a FOLLOW row
		// carries no publishing value and never qualifies.
		if (TeaPublishing.ENABLED == saved.getPublishing() && null == od.getTeaUuid()) {
			organizationService.ensureTeaUuid(org, wu);
		}
		return saved;
	}

	private void validateScopeAndObject(UUID org, TeaProfileScope scope, UUID object, TeaProfileInputDto incoming,
			TeaProfileData d) throws RelizaException {
		switch (scope) {
			case ORGANIZATION -> {
				if (null != object) throw new RelizaException("object must be empty for an ORGANIZATION TEA profile");
				if (null != incoming.getMode()) throw new RelizaException("mode must be empty for an ORGANIZATION TEA profile");
				if (null != incoming.getFollowedPerspective()) {
					throw new RelizaException("followedPerspective must be empty for an ORGANIZATION TEA profile");
				}
			}
			case PERSPECTIVE -> {
				if (null == object) throw new RelizaException("object is required for a PERSPECTIVE TEA profile");
				if (!ossPerspectiveService.teaPerspectivesSupported()) {
					throw new RelizaException("PERSPECTIVE TEA profiles need perspectives, which are a ReARM Pro feature");
				}
				TeaPerspectiveRef ref = ossPerspectiveService.teaPerspectiveRef(object)
						.orElseThrow(() -> new RelizaException("Perspective not found: " + object));
				if (PerspectiveType.PRODUCT == ref.type()) {
					throw new RelizaException("Perspective " + ref.name() + " is a product; its TEA profile is the "
							+ "product's COMPONENT-scope profile, edit it there");
				}
				Optional<RelizaObject> ro = ossPerspectiveService.teaPerspectiveObject(object);
				if (ro.isEmpty() || !org.equals(ro.get().getOrg())) {
					throw new RelizaException("Perspective not found: " + object);
				}
				if (null != incoming.getMode()) throw new RelizaException("mode must be empty for a PERSPECTIVE TEA profile");
				if (null != incoming.getFollowedPerspective()) {
					throw new RelizaException("followedPerspective must be empty for a PERSPECTIVE TEA profile");
				}
			}
			case COMPONENT -> {
				if (null == object) throw new RelizaException("object is required for a COMPONENT TEA profile");
				ComponentData cd = getComponentService.getComponentData(object)
						.filter(c -> org.equals(c.getOrg()))
						.orElseThrow(() -> new RelizaException("Component not found: " + object));
				if (null == incoming.getMode()) {
					throw new RelizaException("mode is required for a COMPONENT TEA profile: OVERRIDE or FOLLOW_PERSPECTIVE");
				}
				d.setMode(incoming.getMode());
				if (TeaComponentProfileMode.OVERRIDE == incoming.getMode()) {
					if (null != incoming.getFollowedPerspective()) {
						throw new RelizaException("followedPerspective must be empty when mode is OVERRIDE");
					}
				} else {
					UUID followed = incoming.getFollowedPerspective();
					if (null == followed) {
						throw new RelizaException("followedPerspective is required when mode is FOLLOW_PERSPECTIVE");
					}
					List<TeaPerspectiveRef> candidates = ossPerspectiveService.teaPerspectivesOfComponent(cd);
					if (candidates.stream().noneMatch(c -> followed.equals(c.uuid()))) {
						String followedName = ossPerspectiveService.teaPerspectiveRef(followed)
								.map(TeaPerspectiveRef::name).orElse(followed.toString());
						String names = candidates.isEmpty() ? "none"
								: candidates.stream().map(TeaPerspectiveRef::name).sorted(TeaProfileResolver.NAME_ORDER)
										.collect(Collectors.joining(", "));
						throw new RelizaException("followedPerspective " + followedName + " is not a perspective of component "
								+ cd.getName() + "; its perspectives: " + names);
					}
					d.setFollowedPerspective(followed);
				}
			}
		}
		d.setObject(object);
	}

	private static void copyProfileFields(TeaProfileInputDto in, TeaProfileData d) {
		d.setPublishing(in.getPublishing());
		d.setVisibility(in.getVisibility());
		d.setDependencyDepth(in.getDependencyDepth());
		d.setOptionalDependencies(in.getOptionalDependencies());
		d.setStructure(in.getStructure());
		d.setSources(in.getSources());
		d.setExcludedCoverage(in.getExcludedCoverage());
		d.setSupportMetadata(in.getSupportMetadata());
		d.setInternalMetadata(in.getInternalMetadata());
		d.setRawArtifacts(in.getRawArtifacts());
		d.setProductComponents(in.getProductComponents());
		d.setMinimumLifecycle(in.getMinimumLifecycle());
		d.setTei(in.getTei());
		d.setTeiDomain(in.getTeiDomain());
		d.setVulnerabilityDocuments(in.getVulnerabilityDocuments());
	}

	/** The profile fields every OVERRIDE, PERSPECTIVE or ORGANIZATION save must carry; teiDomain is checked with tei. */
	static final Map<String, Function<TeaProfileData, Object>> REQUIRED_FIELDS = requiredFields();

	private static Map<String, Function<TeaProfileData, Object>> requiredFields() {
		Map<String, Function<TeaProfileData, Object>> m = new LinkedHashMap<>();
		m.put("publishing", TeaProfileData::getPublishing);
		m.put("visibility", TeaProfileData::getVisibility);
		m.put("dependencyDepth", TeaProfileData::getDependencyDepth);
		m.put("optionalDependencies", TeaProfileData::getOptionalDependencies);
		m.put("structure", TeaProfileData::getStructure);
		m.put("sources", TeaProfileData::getSources);
		m.put("excludedCoverage", TeaProfileData::getExcludedCoverage);
		m.put("supportMetadata", TeaProfileData::getSupportMetadata);
		m.put("internalMetadata", TeaProfileData::getInternalMetadata);
		m.put("rawArtifacts", TeaProfileData::getRawArtifacts);
		m.put("productComponents", TeaProfileData::getProductComponents);
		m.put("minimumLifecycle", TeaProfileData::getMinimumLifecycle);
		m.put("tei", TeaProfileData::getTei);
		m.put("vulnerabilityDocuments", TeaProfileData::getVulnerabilityDocuments);
		return m;
	}

	private static void validateProfileFields(TeaProfileData d, boolean injectionEnabled) throws RelizaException {
		for (Map.Entry<String, Function<TeaProfileData, Object>> f : REQUIRED_FIELDS.entrySet()) {
			if (null == f.getValue().apply(d)) throw new RelizaException(f.getKey() + " is required");
		}
		if (d.getSources().isEmpty()) {
			throw new RelizaException("sources must name at least one of DELIVERABLE, RELEASE, SOURCE_CODE");
		}
		ReleaseLifecycle ml = d.getMinimumLifecycle();
		if (!ACCEPTED_MINIMUM_LIFECYCLES.contains(ml)) {
			if (ReleaseLifecycle.maturity(ml) < ReleaseLifecycle.maturity(ReleaseLifecycle.ASSEMBLED)) {
				throw new RelizaException("minimumLifecycle " + ml + " is too low: use ASSEMBLED, READY_TO_SHIP or "
						+ "GENERAL_AVAILABILITY");
			}
			throw new RelizaException("minimumLifecycle " + ml + " ranks with GENERAL_AVAILABILITY as a threshold: "
					+ "use GENERAL_AVAILABILITY");
		}
		if (TeaTei.UUID == d.getTei()) {
			validateTeiDomain(d.getTeiDomain());
		} else {
			d.setTeiDomain(null);
		}
		if (TeaSupportMetadata.INCLUDE == d.getSupportMetadata() && !injectionEnabled) {
			throw new RelizaException("supportMetadata INCLUDE needs the organization setting supportInjection ENABLED");
		}
		if (TeaVulnerabilityDocuments.NONE != d.getVulnerabilityDocuments()) {
			throw new RelizaException("vulnerabilityDocuments must be NONE");
		}
	}

	/**
	 * The TEI authority: the sid PURL domain form, plus TEA discovery's own rules (lowercase, at
	 * least one dot), since the domain is resolved through DNS.
	 */
	static void validateTeiDomain(String teiDomain) throws RelizaException {
		if (StringUtils.isEmpty(teiDomain)) throw new RelizaException("teiDomain is required when tei is UUID");
		if (!teiDomain.chars().allMatch(c -> c < 0x80)) {
			throw new RelizaException("teiDomain must be ASCII (use punycode for IDN domains)");
		}
		if (!teiDomain.equals(teiDomain.toLowerCase(Locale.ROOT))) {
			throw new RelizaException("teiDomain must be lowercase");
		}
		if (!teiDomain.contains(".")) throw new RelizaException("teiDomain must be a domain name with at least one dot");
		SidPurlUtils.ValidationResult vr = SidPurlUtils.validateAuthoritySegments(List.of(teiDomain));
		if (!vr.valid()) {
			throw new RelizaException("teiDomain is not a valid domain: " + vr.error());
		}
	}

	/** Remove a PERSPECTIVE or COMPONENT row; the organization row is reset, never deleted. */
	@Transactional
	public boolean deleteProfile(UUID org, TeaProfileScope scope, UUID object, WhoUpdated wu) throws RelizaException {
		if (TeaProfileScope.ORGANIZATION == scope) {
			throw new RelizaException("The organization TEA profile cannot be deleted; reset it to the defaults instead");
		}
		Optional<TeaProfile> existing = findEntity(org, scope, object);
		if (existing.isEmpty()) return false;
		auditService.createAndSaveAuditRecord(TableName.TEA_PROFILE, existing.get());
		repository.delete(existing.get());
		return true;
	}

	/**
	 * Remove every row whose object is {@code object}, whatever its scope: called in the same
	 * transaction as the deletion of the object (a perspective), since there is no FOREIGN KEY.
	 */
	@Transactional
	public void deleteProfilesOfObject(UUID org, UUID object, WhoUpdated wu) {
		if (null == org || null == object) return;
		for (TeaProfile p : repository.listByObject(org, object)) {
			auditService.createAndSaveAuditRecord(TableName.TEA_PROFILE, p);
			repository.delete(p);
		}
	}

	/**
	 * How many published releases resolve to the profile of this scope; the confirmation before a
	 * profile goes PUBLIC states it. Answers 0 until task TEA-5 (part 4, the publication engine)
	 * implements it against the publications it creates.
	 */
	public int countPublishedReleasesResolvingTo(UUID org, TeaProfileScope scope, UUID object) {
		return 0;
	}

	public TeaOrgDiscovery orgDiscovery(UUID org) throws RelizaException {
		OrganizationData od = getOrganizationService.getOrganizationData(org)
				.orElseThrow(() -> new RelizaException("Organization not found: " + org));
		UUID teaUuid = od.getTeaUuid();
		if (null == teaUuid) return new TeaOrgDiscovery(org, null, null, null);
		String base = StringUtils.stripEnd(null == relizaConfigProps ? "" : relizaConfigProps.getBaseuri(), "/");
		String apiBase = base + "/tea/" + teaUuid;
		String document = Utils.OM.writeValueAsString(new WellKnownTea(1,
				List.of(new WellKnownEndpoint(apiBase, List.of(TEA_API_VERSION), 1))));
		return new TeaOrgDiscovery(org, teaUuid, apiBase, document);
	}
}
