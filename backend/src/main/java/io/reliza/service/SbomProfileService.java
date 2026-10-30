/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.SbomProfile;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.SbomProfileData.SbomVulnerabilityDocuments;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.SettingsScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SbomProfileInputDto;
import io.reliza.repositories.SbomProfileRepository;
import io.reliza.service.ScopedSettingsResolver.ScopedResolution;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Stores SBOM profiles (task TEA-27): one wholesale row per (org, scope, object, name), validated on
 * every save. Which row a name resolves to is {@link SbomProfileResolver}'s business. A profile is a
 * catalog entry: the organization row of a name can be deleted, and {@code default} then falls back
 * to the built-in. A delete is refused while a channel ({@link SbomProfileConsumer}) names the
 * profile and the name would stop resolving there.
 */
@Service
public class SbomProfileService {

	static final String WHAT = "SBOM profile";

	@Autowired private SbomProfileRepository repository;
	@Autowired private AuditService auditService;
	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private SupportInjectionService supportInjectionService;
	@Autowired private ScopedSettingsResolver scopedSettingsResolver;
	/** Resolved at call time: the channels depend on the catalog, never the other way round at construction. */
	@Autowired private ObjectProvider<SbomProfileConsumer> consumers;

	public Optional<SbomProfileData> getProfile(UUID org, SettingsScope scope, UUID object, String name) {
		return findEntity(org, scope, object, name).map(SbomProfileData::dataFromRecord);
	}

	/** The rows stored at a scope, by name. */
	public List<SbomProfileData> listProfilesOfScope(UUID org, SettingsScope scope, UUID object) {
		if (null == org || null == scope) return List.of();
		if (SettingsScope.ORGANIZATION == scope) {
			return repository.listOfOrganizationScope(org).stream().map(SbomProfileData::dataFromRecord).toList();
		}
		if (null == object) return List.of();
		return repository.listByScopeObject(org, scope.name(), object).stream().map(SbomProfileData::dataFromRecord)
				.toList();
	}

	public List<SbomProfileData> listProfilesOfOrg(UUID org) {
		if (null == org) return List.of();
		return repository.listByOrg(org).stream().map(SbomProfileData::dataFromRecord).toList();
	}

	/** The rows of {@code name} at the given perspectives. */
	public List<SbomProfileData> listPerspectiveProfiles(UUID org, Collection<UUID> perspectives, String name) {
		if (null == org || null == name || null == perspectives || perspectives.isEmpty()) return List.of();
		String[] ids = perspectives.stream().filter(u -> null != u).map(UUID::toString).distinct()
				.toArray(String[]::new);
		if (ids.length == 0) return List.of();
		return repository.listByScopeObjectsAndName(org, SettingsScope.PERSPECTIVE.name(), ids, name).stream()
				.map(SbomProfileData::dataFromRecord).toList();
	}

	private Optional<SbomProfile> findEntity(UUID org, SettingsScope scope, UUID object, String name) {
		if (null == org || null == scope || null == name) return Optional.empty();
		if (SettingsScope.ORGANIZATION == scope) return repository.findOrgProfile(org, name);
		if (null == object) return Optional.empty();
		return repository.findByScopeObjectName(org, scope.name(), object, name);
	}

	/**
	 * The store of one name, for the engine: the rows of {@code name} at each scope, the built-in for
	 * {@code default} only, and TEA-2's supportMetadata rule as the normalization: INCLUDE reads EXCLUDE
	 * while the organization's support injection is off, on a detached copy, never on the stored row.
	 */
	public ScopedSettingsStore<SbomProfileData> storeFor(String name) {
		return new NameStore(name);
	}

	private final class NameStore implements ScopedSettingsStore<SbomProfileData> {
		private final String name;

		NameStore(String name) {
			this.name = name;
		}

		@Override public String label() { return WHAT + " " + name; }
		@Override public Optional<SbomProfileData> organizationRow(UUID org) {
			return getProfile(org, SettingsScope.ORGANIZATION, null, name);
		}
		@Override public Optional<SbomProfileData> perspectiveRow(UUID org, UUID perspective) {
			return getProfile(org, SettingsScope.PERSPECTIVE, perspective, name);
		}
		@Override public List<SbomProfileData> perspectiveRows(UUID org, Collection<UUID> perspectives) {
			return listPerspectiveProfiles(org, perspectives, name);
		}
		@Override public Optional<SbomProfileData> componentRow(UUID org, UUID component) {
			return getProfile(org, SettingsScope.COMPONENT, component, name);
		}
		@Override public Optional<SbomProfileData> builtInDefault(UUID org) {
			return SbomProfileData.DEFAULT_NAME.equals(name) ? Optional.of(SbomProfileData.builtInDefault(org))
					: Optional.empty();
		}
		@Override public UUID uuidOf(SbomProfileData row) { return row.getUuid(); }
		@Override public UUID objectOf(SbomProfileData row) { return row.getObject(); }
		@Override public ScopedSettingsMode modeOf(SbomProfileData row) { return row.getMode(); }
		@Override public UUID followedPerspectiveOf(SbomProfileData row) { return row.getFollowedPerspective(); }
		@Override public SbomProfileData normalized(UUID org, SbomProfileData row) {
			if (SbomSupportMetadata.INCLUDE != row.getSupportMetadata()) return row;
			if (supportInjectionService.isInjectionEnabled(org)) return row;
			SbomProfileData effective = row.copy();
			effective.setSupportMetadata(SbomSupportMetadata.EXCLUDE);
			return effective;
		}
	}

	/**
	 * ReARM CE has one SBOM profile, {@code default}: a save under any other name stores {@code default}
	 * (overridable per component like any profile). ReARM Pro keeps the name as sent.
	 */
	static String editionName(String name, boolean ossEdition) {
		return ossEdition ? SbomProfileData.DEFAULT_NAME : name;
	}

	/** ASCII, no whitespace, at most 64 characters; the refusal never echoes the refused name. */
	public static void validateName(String name) throws RelizaException {
		if (null == name || name.isEmpty()) throw new RelizaException("An SBOM profile name is required");
		if (!SbomProfileData.isValidName(name)) {
			throw new RelizaException("An SBOM profile name is 1 to 64 ASCII letters, digits, dots, underscores or "
					+ "hyphens, starting with a letter or a digit (" + SbomProfileData.NAME_PATTERN.pattern() + ")");
		}
	}

	/** The recipe fields every non-FOLLOW save must carry. */
	static final Map<String, Function<SbomProfileData, Object>> REQUIRED_FIELDS = requiredFields();

	private static Map<String, Function<SbomProfileData, Object>> requiredFields() {
		Map<String, Function<SbomProfileData, Object>> m = new LinkedHashMap<>();
		m.put("dependencyDepth", SbomProfileData::getDependencyDepth);
		m.put("optionalDependencies", SbomProfileData::getOptionalDependencies);
		m.put("structure", SbomProfileData::getStructure);
		m.put("sources", SbomProfileData::getSources);
		m.put("excludedCoverage", SbomProfileData::getExcludedCoverage);
		m.put("supportMetadata", SbomProfileData::getSupportMetadata);
		m.put("internalMetadata", SbomProfileData::getInternalMetadata);
		m.put("vulnerabilityDocuments", SbomProfileData::getVulnerabilityDocuments);
		return m;
	}

	/** The fields a FOLLOW_PERSPECTIVE save must leave empty: the recipe and the description. */
	private static final Map<String, Function<SbomProfileInputDto, Object>> FOLLOW_EMPTY_FIELDS = followEmptyFields();

	private static Map<String, Function<SbomProfileInputDto, Object>> followEmptyFields() {
		Map<String, Function<SbomProfileInputDto, Object>> m = new LinkedHashMap<>();
		m.put("description", SbomProfileInputDto::getDescription);
		m.put("dependencyDepth", SbomProfileInputDto::getDependencyDepth);
		m.put("optionalDependencies", SbomProfileInputDto::getOptionalDependencies);
		m.put("structure", SbomProfileInputDto::getStructure);
		m.put("sources", SbomProfileInputDto::getSources);
		m.put("excludedCoverage", SbomProfileInputDto::getExcludedCoverage);
		m.put("supportMetadata", SbomProfileInputDto::getSupportMetadata);
		m.put("internalMetadata", SbomProfileInputDto::getInternalMetadata);
		m.put("vulnerabilityDocuments", SbomProfileInputDto::getVulnerabilityDocuments);
		return m;
	}

	/**
	 * Upsert the row of (org, scope, object, name) with {@code incoming}, wholesale. A non-FOLLOW row
	 * needs every recipe field, never defaulted; a FOLLOW_PERSPECTIVE row carries none. An update bumps
	 * the revision and writes an audit record.
	 */
	@Transactional
	public SbomProfileData saveProfile(UUID org, SettingsScope scope, UUID object, String name,
			SbomProfileInputDto incoming, WhoUpdated wu) throws RelizaException {
		name = editionName(name, LicensingConstants.isOssEdition());
		validateName(name);
		if (null == incoming) throw new RelizaException("profile is required");
		if (null != org) {
			getOrganizationService.getOrganizationData(org)
					.orElseThrow(() -> new RelizaException("Organization not found: " + org));
		}
		ScopedSettingsValidation.validateScope(org, scope, object, incoming.getMode(),
				incoming.getFollowedPerspective(), WHAT, ossPerspectiveService, getComponentService);

		SbomProfileData d = new SbomProfileData();
		d.setOrg(org);
		d.setScope(scope);
		d.setObject(object);
		d.setName(name);
		boolean follows = SettingsScope.COMPONENT == scope && ScopedSettingsMode.FOLLOW_PERSPECTIVE == incoming.getMode();
		if (SettingsScope.COMPONENT == scope) d.setMode(incoming.getMode());
		if (follows) {
			d.setFollowedPerspective(incoming.getFollowedPerspective());
			for (Map.Entry<String, Function<SbomProfileInputDto, Object>> f : FOLLOW_EMPTY_FIELDS.entrySet()) {
				if (null != f.getValue().apply(incoming)) {
					throw new RelizaException(f.getKey() + " must be empty when mode is FOLLOW_PERSPECTIVE");
				}
			}
		} else {
			copyRecipe(incoming, d);
			validateRecipe(d, supportInjectionService.isInjectionEnabled(org));
		}

		Optional<SbomProfile> existing = findEntity(org, scope, object, name);
		SbomProfile e;
		if (existing.isPresent()) {
			e = existing.get();
			auditService.createAndSaveAuditRecord(TableName.SBOM_PROFILE, e);
			e.setRevision(e.getRevision() + 1);
			e.setLastUpdatedDate(ZonedDateTime.now());
		} else {
			e = new SbomProfile();
			e.setUuid(UUID.randomUUID());
		}
		d.setUuid(e.getUuid());
		e.setOrg(org);
		e.setScope(scope);
		e.setObjectUuid(object);
		e.setName(name);
		e.setRecordData(d.toRecordData());
		e = (SbomProfile) WhoUpdated.injectWhoUpdatedData(e, wu);
		return SbomProfileData.dataFromRecord(repository.save(e));
	}

	private static void copyRecipe(SbomProfileInputDto in, SbomProfileData d) {
		d.setDescription(in.getDescription());
		d.setDependencyDepth(in.getDependencyDepth());
		d.setOptionalDependencies(in.getOptionalDependencies());
		d.setStructure(in.getStructure());
		d.setSources(in.getSources());
		d.setExcludedCoverage(in.getExcludedCoverage());
		d.setSupportMetadata(in.getSupportMetadata());
		d.setInternalMetadata(in.getInternalMetadata());
		d.setVulnerabilityDocuments(in.getVulnerabilityDocuments());
	}

	static void validateRecipe(SbomProfileData d, boolean injectionEnabled) throws RelizaException {
		for (Map.Entry<String, Function<SbomProfileData, Object>> f : REQUIRED_FIELDS.entrySet()) {
			if (null == f.getValue().apply(d)) throw new RelizaException(f.getKey() + " is required");
		}
		if (d.getSources().isEmpty()) {
			throw new RelizaException("sources must name at least one of DELIVERABLE, RELEASE, SOURCE_CODE");
		}
		if (SbomVulnerabilityDocuments.NONE != d.getVulnerabilityDocuments()) {
			throw new RelizaException("vulnerabilityDocuments must be NONE");
		}
		if (SbomSupportMetadata.INCLUDE == d.getSupportMetadata() && !injectionEnabled) {
			throw new RelizaException("supportMetadata INCLUDE needs the organization setting supportInjection ENABLED");
		}
		if (null != d.getDescription() && d.getDescription().length() > SbomProfileData.MAX_DESCRIPTION_LENGTH) {
			throw new RelizaException("description must be at most " + SbomProfileData.MAX_DESCRIPTION_LENGTH
					+ " characters");
		}
	}

	/**
	 * Remove the row of (org, scope, object, name); answers whether one existed. Refused while a channel
	 * names the profile and the name would no longer resolve at that channel's scope without this row
	 * (NOT_FOUND or CONFLICT). The guard is conservative and protects publishing continuity only; the
	 * publish-time refusal is the guard that holds per member. Writes an audit record (TEA-27 F-6).
	 */
	@Transactional
	public boolean deleteProfile(UUID org, SettingsScope scope, UUID object, String name, WhoUpdated wu)
			throws RelizaException {
		if (null == scope) throw new RelizaException("scope is required");
		Optional<SbomProfile> existing = findEntity(org, scope, object, name);
		if (existing.isEmpty()) return false;
		UUID rowUuid = existing.get().getUuid();
		ScopedSettingsStore<SbomProfileData> store = storeFor(name);
		for (SbomProfileConsumer consumer : consumers.orderedStream().toList()) {
			for (ScopedSettingsRef ref : consumer.referencesTo(org, name)) {
				ScopedResolution<SbomProfileData> without = scopedSettingsResolver.effectiveOfScopeWithoutRows(store, org,
						ref.scope(), ref.object(), Set.of(rowUuid));
				if (ScopedResolutionStatus.RESOLVED != without.status()) {
					throw new RelizaException(WHAT + " " + name + " of " + scopeLabel(scope, object)
							+ " cannot be deleted: " + ref.label() + " name it and it would no longer resolve there; "
							+ "point them at another profile first");
				}
			}
		}
		auditService.createAndSaveAuditRecord(TableName.SBOM_PROFILE, existing.get());
		repository.delete(existing.get());
		return true;
	}

	/**
	 * Remove every row whose object is {@code object}, whatever its scope and name: called in the same
	 * transaction as the deletion of the object (a perspective), since there is no FOREIGN KEY.
	 */
	@Transactional
	public void deleteProfilesOfObject(UUID org, UUID object, WhoUpdated wu) {
		if (null == org || null == object) return;
		for (SbomProfile p : repository.listByObject(org, object)) {
			auditService.createAndSaveAuditRecord(TableName.SBOM_PROFILE, p);
			repository.delete(p);
		}
	}

	/** How a refusal names a scope: "the organization", "perspective Alpha", "component widget". */
	String scopeLabel(SettingsScope scope, UUID object) {
		return switch (scope) {
			case ORGANIZATION -> "the organization";
			case PERSPECTIVE -> "perspective " + (null == object ? "null"
					: ossPerspectiveService.teaPerspectiveRef(object).map(TeaPerspectiveRef::name)
							.orElse(object.toString()));
			case COMPONENT -> "component " + (null == object ? "null"
					: getComponentService.getComponentData(object).map(ComponentData::getName)
							.orElse(object.toString()));
		};
	}
}
