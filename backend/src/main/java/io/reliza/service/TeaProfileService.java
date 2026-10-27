/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.SettingsScope;
import io.reliza.model.TeaProfile;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.repositories.TeaProfileRepository;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

/**
 * Stores TEA profiles (task TEA-2): the TEA publishing settings of a scope (a TEA profile): the
 * channel fields and the names of the SBOM profiles it publishes; a recipe lives in an SBOM profile
 * (task TEA-27, {@link SbomProfileService}). One wholesale row per (org, scope, object), validated on
 * every save. Which row a component resolves to is {@link TeaProfileResolver}'s business.
 *
 * <p>Task TEA-9: every save and removal is assessed for what it does to the effective visibility
 * of its scope's object ({@link #assessSave}, {@link #assessRemoval}); a path to PUBLIC needs an
 * organization admin, and an archived component or product accepts only the row that hides it.
 * Round 2: a change of a component's perspectives is assessed the same way ({@link #assessMembership}),
 * and a perspective-row removal or a perspective deletion over the perspective's direct members
 * ({@link #assessPerspectiveDeletion}); an archived member never gains ENABLED or PUBLIC that way.
 */
@Slf4j
@Service
public class TeaProfileService implements SbomProfileConsumer {

	/** The TEA versions the organization's discovery document announces. */
	public static final String TEA_API_VERSION = "1.0.0";

	/** The lifecycles a profile may require of a release before it is published. */
	public static final List<ReleaseLifecycle> ACCEPTED_MINIMUM_LIFECYCLES = List.of(ReleaseLifecycle.ASSEMBLED,
			ReleaseLifecycle.READY_TO_SHIP, ReleaseLifecycle.GENERAL_AVAILABILITY);

	/** The most SBOM profile names one TEA publishing settings row may carry on ReARM Pro (task TEA-27). */
	public static final int MAX_PROFILE_NAMES = 10;

	static final String WHAT = "TEA profile";

	@Autowired private TeaProfileRepository repository;
	@Autowired private AuditService auditService;
	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private OrganizationService organizationService;
	/** The catalog the names of a row must resolve in (task TEA-27); lazy like the TEA resolver. */
	@Autowired @Lazy private SbomProfileResolver sbomProfileResolver;
	/** Lazy: the publication service depends on the resolver, which depends on this service. */
	@Autowired @Lazy private TeaPublicationService teaPublicationService;
	// The resolver reads rows through this service; lazy to break the cycle (no circular references allowed).
	@Autowired @Lazy private TeaProfileResolver teaProfileResolver;

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

	/**
	 * The caller's standing in the organization, computed by the fetcher; a scope admin is not an org admin.
	 * SYSTEM: a membership write with no principal to ask, a board's document publish joining the board's own
	 * perspectives ({@code BoardPerspectiveService.joinDocumentComponent}, task TEA-14); the admin rule does not
	 * apply to it, the archived rule does; never derived from a principal.
	 */
	public enum TeaCaller { ORG_ADMIN, SCOPE_ADMIN, SYSTEM }

	/** The visibility a resolution exposes; a CONFLICT (concealed, nothing readable) is UNRESOLVED. */
	public enum TeaEffectiveVisibility { PUBLIC, PRIVATE, UNRESOLVED }

	/** How a save, a removal or a membership change reaches PUBLIC; anything but NONE needs an organization admin. */
	public enum TeaPublicPath { NONE, ROW_PUBLIC, FOLLOW_RESOLVES_PUBLIC, REMOVAL_RESOLVES_PUBLIC,
			MEMBERSHIP_RESOLVES_PUBLIC }

	/** A component, other than the assessed object, that a write makes PUBLIC, and what it then resolves to. */
	public record TeaWidenedComponent(UUID component, String name, TeaProfileSource afterSource,
			UUID afterSourceObject) {}

	/**
	 * What a save, a removal or a membership change does to the effective visibility of its object.
	 * The after source names the profile the object resolves to afterwards (null on a CONFLICT);
	 * {@code widened} lists the other components the write makes PUBLIC, in name order: the direct
	 * members of a perspective whose row is removed or which is deleted, empty for every other write.
	 */
	public record TeaExposureChange(TeaEffectiveVisibility before, TeaEffectiveVisibility after, TeaPublicPath path,
			TeaProfileSource afterSource, UUID afterSourceObject, int publishedReleases,
			List<TeaWidenedComponent> widened) {}

	/** A validated save: the organization, the prospective row and, at COMPONENT scope, the component. */
	private record PreparedSave(OrganizationData od, TeaProfileData row, ComponentData cd) {}

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

	/** The TEA scope as the shared scoped settings vocabulary names it (task TEA-27). */
	public static SettingsScope settingsScope(TeaProfileScope scope) {
		return null == scope ? null : SettingsScope.valueOf(scope.name());
	}

	/** The shared scope as the TEA API names it (task TEA-27). */
	public static TeaProfileScope teaScope(SettingsScope scope) {
		return null == scope ? null : TeaProfileScope.valueOf(scope.name());
	}

	/** The TEA mode as the shared scoped settings vocabulary names it (task TEA-27). */
	static ScopedSettingsMode settingsMode(TeaComponentProfileMode mode) {
		return null == mode ? null : ScopedSettingsMode.valueOf(mode.name());
	}

	/** The rows of this service as {@link ScopedSettingsResolver} reads them (task TEA-27). */
	public ScopedSettingsStore<TeaProfileData> store() {
		return new TeaProfileStore(this);
	}

	/**
	 * The TEA publishing settings store: one row per scope and object, the built-in defaults, and no
	 * normalization (the supportMetadata rule left with the recipe for the SBOM profile store).
	 */
	static final class TeaProfileStore implements ScopedSettingsStore<TeaProfileData> {
		private final TeaProfileService rows;

		TeaProfileStore(TeaProfileService rows) {
			this.rows = rows;
		}

		@Override public String label() { return WHAT; }
		@Override public Optional<TeaProfileData> organizationRow(UUID org) {
			return rows.getProfile(org, TeaProfileScope.ORGANIZATION, null);
		}
		@Override public Optional<TeaProfileData> perspectiveRow(UUID org, UUID perspective) {
			return rows.getProfile(org, TeaProfileScope.PERSPECTIVE, perspective);
		}
		@Override public List<TeaProfileData> perspectiveRows(UUID org, Collection<UUID> perspectives) {
			return rows.listPerspectiveProfiles(org, perspectives);
		}
		@Override public Optional<TeaProfileData> componentRow(UUID org, UUID component) {
			return rows.getProfile(org, TeaProfileScope.COMPONENT, component);
		}
		@Override public Optional<TeaProfileData> builtInDefault(UUID org) {
			return Optional.of(TeaProfileData.builtInDefault(org));
		}
		@Override public UUID uuidOf(TeaProfileData row) { return row.getUuid(); }
		@Override public UUID objectOf(TeaProfileData row) { return row.getObject(); }
		@Override public ScopedSettingsMode modeOf(TeaProfileData row) { return settingsMode(row.getMode()); }
		@Override public UUID followedPerspectiveOf(TeaProfileData row) { return row.getFollowedPerspective(); }
		@Override public TeaProfileData normalized(UUID org, TeaProfileData row) { return row; }
	}

	/**
	 * Every TEA publishing settings row of the organization that names the SBOM profile {@code name},
	 * ENABLED or DISABLED alike (a DISABLED row may be re-enabled); a FOLLOW row carries no names and is
	 * covered by the row it follows (task TEA-27, the delete guard of the catalog).
	 */
	@Override
	public List<ScopedSettingsRef> referencesTo(UUID org, String name) {
		if (null == org || null == name) return List.of();
		List<ScopedSettingsRef> out = new ArrayList<>();
		for (TeaProfileData d : listProfilesOfOrg(org)) {
			if (null == d.getProfiles() || !d.getProfiles().contains(name)) continue;
			out.add(new ScopedSettingsRef(settingsScope(d.getScope()), d.getObject(),
					"the TEA publishing settings of " + scopeLabel(d.getScope(), d.getObject())));
		}
		return out;
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
	 * copied value into something else. An archived component accepts only the hide row, and a
	 * save whose path to PUBLIC is not NONE needs {@link TeaCaller#ORG_ADMIN}; both are refused
	 * before anything is written.
	 */
	@Transactional
	public TeaProfileData saveProfile(UUID org, TeaProfileScope scope, UUID object, TeaProfileInputDto incoming,
			TeaCaller caller, WhoUpdated wu) throws RelizaException {
		PreparedSave ps = prepareSave(org, scope, object, incoming);
		TeaProfileData d = ps.row();
		TeaExposureChange x = assess(org, scope, object, ps);
		if (TeaPublicPath.NONE != x.path() && TeaCaller.ORG_ADMIN != caller) {
			throw new RelizaException(refusalOf(x, scope, object, ps.cd(), d));
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
		e.setScope(scope);
		e.setObjectUuid(d.getObject());
		e.setRecordData(d.toRecordData());
		e = (TeaProfile) WhoUpdated.injectWhoUpdatedData(e, wu);
		TeaProfileData saved = TeaProfileData.dataFromRecord(repository.save(e));

		// "TEA first enabled" for an org is its first ENABLED save at any scope; a FOLLOW row
		// carries no publishing value and never qualifies, and an archived component cannot
		// get this far with ENABLED.
		if (TeaPublishing.ENABLED == saved.getPublishing() && null == ps.od().getTeaUuid()) {
			organizationService.ensureTeaUuid(org, wu);
		}
		return saved;
	}

	/**
	 * What saving {@code incoming} would do to the effective visibility of the scope's object,
	 * after exactly the validation of {@link #saveProfile}, the archived rules included; writes nothing.
	 */
	public TeaExposureChange assessSave(UUID org, TeaProfileScope scope, UUID object, TeaProfileInputDto incoming)
			throws RelizaException {
		return assess(org, scope, object, prepareSave(org, scope, object, incoming));
	}

	/**
	 * What removing the scope's row would do to the effective visibility of its object, after the
	 * archived rule when a row exists; writes nothing. The organization row cannot be removed.
	 */
	public TeaExposureChange assessRemoval(UUID org, TeaProfileScope scope, UUID object) throws RelizaException {
		if (null == org) throw new RelizaException("org is required");
		if (null == scope) throw new RelizaException("scope is required");
		if (TeaProfileScope.ORGANIZATION == scope) throw new RelizaException(ORG_ROW_NOT_DELETABLE);
		ComponentData cd = TeaProfileScope.COMPONENT == scope ? componentOf(org, object) : null;
		if (null != cd && findEntity(org, scope, object).isPresent()) refuseArchivedRemoval(cd);
		return assessRemoval(org, scope, object, cd);
	}

	private TeaExposureChange assessRemoval(UUID org, TeaProfileScope scope, UUID object, ComponentData cd) {
		TeaResolvedProfile before;
		TeaResolvedProfile after;
		List<TeaWidenedComponent> widened = List.of();
		if (TeaProfileScope.COMPONENT == scope) {
			before = teaProfileResolver.resolveForComponent(cd);
			after = teaProfileResolver.resolveForComponentWithRow(cd, null);
		} else {
			before = teaProfileResolver.effectiveOfScope(org, scope, object);
			after = teaProfileResolver.parentOfScope(org, scope, object);
			if (TeaProfileScope.PERSPECTIVE == scope) widened = membersWidenedByRowRemoval(org, object);
		}
		TeaEffectiveVisibility b = visibilityOf(before);
		TeaEffectiveVisibility a = visibilityOf(after);
		TeaPublicPath path = widens(b, a) || !widened.isEmpty() ? TeaPublicPath.REMOVAL_RESOLVES_PUBLIC
				: TeaPublicPath.NONE;
		return change(org, scope, object, b, a, path, after, widened);
	}

	private static boolean widens(TeaEffectiveVisibility before, TeaEffectiveVisibility after) {
		return TeaEffectiveVisibility.PUBLIC == after && TeaEffectiveVisibility.PUBLIC != before;
	}

	/** The direct members of the perspective that resolve PUBLIC without its row and did not before (T-2). */
	private List<TeaWidenedComponent> membersWidenedByRowRemoval(UUID org, UUID perspective) {
		List<TeaWidenedComponent> out = new ArrayList<>();
		for (ComponentData m : directMembers(org, perspective)) {
			TeaResolvedProfile mb = teaProfileResolver.resolveForComponent(m);
			TeaResolvedProfile ma = teaProfileResolver.resolveForComponentWithoutPerspectiveRow(m, perspective);
			if (widens(visibilityOf(mb), visibilityOf(ma))) out.add(widenedOf(m, ma));
		}
		return sortedByName(out);
	}

	private List<ComponentData> directMembers(UUID org, UUID perspective) {
		return getComponentService.listDirectComponentsOfPerspective(perspective).stream()
				.filter(m -> org.equals(m.getOrg())).toList();
	}

	private static TeaWidenedComponent widenedOf(ComponentData m, TeaResolvedProfile after) {
		return new TeaWidenedComponent(m.getUuid(), m.getName(), after.source(), after.sourceObject());
	}

	private static List<TeaWidenedComponent> sortedByName(List<TeaWidenedComponent> widened) {
		return widened.stream().sorted(Comparator.comparing(TeaWidenedComponent::name, TeaProfileResolver.NAME_ORDER))
				.toList();
	}

	/**
	 * What setting the component's perspectives to exactly {@code next} would do to its effective
	 * visibility (task TEA-9, round 2): the stored row, with the candidates of {@code next}. On an
	 * archived component or product a change that gains publishing ENABLED or visibility PUBLIC is
	 * refused here, for everyone, so the dry-run reports it too. Writes nothing.
	 */
	public TeaExposureChange assessMembership(ComponentData cd, Collection<UUID> next) throws RelizaException {
		if (null == cd) throw new RelizaException("component is required");
		Collection<UUID> wanted = null == next ? List.of() : next;
		TeaResolvedProfile before = teaProfileResolver.resolveForComponent(cd);
		TeaResolvedProfile after = teaProfileResolver.resolveForComponentWithPerspectives(cd, wanted);
		refuseArchivedMembership(cd, wanted, before, after);
		TeaEffectiveVisibility b = visibilityOf(before);
		TeaEffectiveVisibility a = visibilityOf(after);
		TeaPublicPath path = widens(b, a) ? TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC : TeaPublicPath.NONE;
		return change(cd.getOrg(), TeaProfileScope.COMPONENT, cd.getUuid(), b, a, path, after, List.of());
	}

	/**
	 * What deleting the perspective would do (task TEA-9, round 2): its own step as a removal of its
	 * row, and every direct member resolved without it as a candidate, which also drops its row from
	 * the walk. The archived rule runs per member, for everyone. Writes nothing.
	 */
	public TeaExposureChange assessPerspectiveDeletion(UUID org, UUID perspective) throws RelizaException {
		if (null == org) throw new RelizaException("org is required");
		if (null == perspective) throw new RelizaException("perspective is required");
		TeaResolvedProfile before = teaProfileResolver.effectiveOfScope(org, TeaProfileScope.PERSPECTIVE, perspective);
		TeaResolvedProfile after = teaProfileResolver.parentOfScope(org, TeaProfileScope.PERSPECTIVE, perspective);
		List<TeaWidenedComponent> widened = new ArrayList<>();
		for (ComponentData m : directMembers(org, perspective)) {
			List<UUID> next = new ArrayList<>(null == m.getPerspectives() ? List.of() : m.getPerspectives());
			next.remove(perspective);
			TeaResolvedProfile mb = teaProfileResolver.resolveForComponent(m);
			TeaResolvedProfile ma = teaProfileResolver.resolveForComponentWithPerspectives(m, next);
			refuseArchivedMembership(m, next, mb, ma);
			if (widens(visibilityOf(mb), visibilityOf(ma))) widened.add(widenedOf(m, ma));
		}
		TeaEffectiveVisibility b = visibilityOf(before);
		TeaEffectiveVisibility a = visibilityOf(after);
		TeaPublicPath path = widens(b, a) || !widened.isEmpty() ? TeaPublicPath.REMOVAL_RESOLVES_PUBLIC
				: TeaPublicPath.NONE;
		return change(org, TeaProfileScope.PERSPECTIVE, perspective, b, a, path, after, sortedByName(widened));
	}

	/**
	 * The gate of {@code setPerspectivesOnComponent}: the assessment, then a widening by a caller who
	 * is not an organization admin is refused. Answers the assessment.
	 */
	public TeaExposureChange requireMembershipAllowed(ComponentData cd, Collection<UUID> next, TeaCaller caller)
			throws RelizaException {
		TeaExposureChange x = assessMembership(cd, next);
		if (TeaPublicPath.NONE != x.path() && TeaCaller.ORG_ADMIN != caller && TeaCaller.SYSTEM != caller) {
			throw new RelizaException("Only an organization admin can change the perspectives of component "
					+ cd.getName() + ": with them, it resolves to the PUBLIC TEA profile of " + sourceLabelOf(x));
		}
		return x;
	}

	/** The gate of {@code deletePerspective}, before anything is dropped. Answers the assessment. */
	public TeaExposureChange requirePerspectiveDeletionAllowed(UUID org, UUID perspective, TeaCaller caller)
			throws RelizaException {
		TeaExposureChange x = assessPerspectiveDeletion(org, perspective);
		if (TeaPublicPath.NONE != x.path() && TeaCaller.ORG_ADMIN != caller) {
			String name = perspectiveName(perspective);
			if (!x.widened().isEmpty()) {
				TeaWidenedComponent first = x.widened().get(0);
				throw new RelizaException("Only an organization admin can delete perspective " + name + ": without it, "
						+ "component " + first.name() + " resolves to the PUBLIC TEA profile of "
						+ sourceLabel(first.afterSource(), first.afterSourceObject()));
			}
			throw new RelizaException("Only an organization admin can delete perspective " + name + ": it deletes its "
					+ "TEA profile, without which it resolves to the PUBLIC TEA profile of " + sourceLabelOf(x));
		}
		return x;
	}

	/** The source a change resolves its object to afterwards, as the refusals word it. */
	public String sourceLabelOf(TeaExposureChange x) {
		return sourceLabel(x.afterSource(), x.afterSourceObject());
	}

	/**
	 * Whether a resolution exposes: {@link TeaPublishing#ENABLED}, then {@link TeaVisibility#PUBLIC}, in
	 * that order; a CONFLICT or no profile is neither. The refusals print the values by name.
	 */
	private static List<Enum<?>> exposureOf(TeaResolvedProfile r) {
		List<Enum<?>> out = new ArrayList<>();
		if (null == r || TeaResolutionStatus.CONFLICT == r.status() || null == r.profile()) return out;
		if (TeaPublishing.ENABLED == r.profile().getPublishing()) out.add(TeaPublishing.ENABLED);
		if (TeaVisibility.PUBLIC == r.profile().getVisibility()) out.add(TeaVisibility.PUBLIC);
		return out;
	}

	/** The exposure values by name, separated by a space, as the refusals word them. */
	private static String exposureNames(List<Enum<?>> exposure) {
		return exposure.stream().map(Enum::name).collect(Collectors.joining(" "));
	}

	/**
	 * An archived component or product takes no membership change that gains publishing ENABLED or
	 * visibility PUBLIC, whoever makes it (T-3); one that leaves it as exposed as before passes.
	 */
	private void refuseArchivedMembership(ComponentData cd, Collection<UUID> next, TeaResolvedProfile before,
			TeaResolvedProfile after) throws RelizaException {
		if (!archived(cd)) return;
		List<Enum<?>> b = exposureOf(before);
		List<Enum<?>> a = exposureOf(after);
		if (b.containsAll(a)) return;
		List<TeaPerspectiveRef> refs = ossPerspectiveService.teaPerspectiveRefs(cd.getOrg(), next);
		String names = refs.isEmpty() ? "none"
				: refs.stream().map(TeaPerspectiveRef::name).collect(Collectors.joining(", "));
		throw new RelizaException("Component " + cd.getName() + " is archived: with perspectives " + names
				+ " it would resolve to the " + exposureNames(a) + " TEA profile of " + sourceLabel(after)
				+ "; an archived component cannot widen its TEA exposure");
	}

	private TeaExposureChange assess(UUID org, TeaProfileScope scope, UUID object, PreparedSave ps) {
		TeaProfileData d = ps.row();
		if (TeaProfileScope.COMPONENT == scope) {
			TeaEffectiveVisibility b = visibilityOf(teaProfileResolver.resolveForComponent(ps.cd()));
			TeaResolvedProfile after = teaProfileResolver.resolveForComponentWithRow(ps.cd(), d);
			TeaEffectiveVisibility a = visibilityOf(after);
			TeaPublicPath path = TeaPublicPath.NONE;
			if (TeaComponentProfileMode.OVERRIDE == d.getMode()) {
				if (TeaVisibility.PUBLIC == d.getVisibility()) path = TeaPublicPath.ROW_PUBLIC;
			} else if (TeaEffectiveVisibility.PUBLIC == a && TeaEffectiveVisibility.PUBLIC != b) {
				path = TeaPublicPath.FOLLOW_RESOLVES_PUBLIC;
			}
			return change(org, scope, object, b, a, path, after, List.of());
		}
		TeaEffectiveVisibility b = visibilityOf(teaProfileResolver.effectiveOfScope(org, scope, object));
		TeaEffectiveVisibility a = TeaVisibility.PUBLIC == d.getVisibility() ? TeaEffectiveVisibility.PUBLIC
				: TeaEffectiveVisibility.PRIVATE;
		TeaProfileSource source = TeaProfileScope.PERSPECTIVE == scope ? TeaProfileSource.PERSPECTIVE
				: TeaProfileSource.ORGANIZATION;
		TeaPublicPath path = TeaEffectiveVisibility.PUBLIC == a ? TeaPublicPath.ROW_PUBLIC : TeaPublicPath.NONE;
		return new TeaExposureChange(b, a, path, source, TeaProfileScope.PERSPECTIVE == scope ? object : null,
				countPublishedReleasesResolvingTo(org, scope, object), List.of());
	}

	private TeaExposureChange change(UUID org, TeaProfileScope scope, UUID object, TeaEffectiveVisibility before,
			TeaEffectiveVisibility after, TeaPublicPath path, TeaResolvedProfile afterResolution,
			List<TeaWidenedComponent> widened) {
		boolean conflict = TeaResolutionStatus.CONFLICT == afterResolution.status();
		return new TeaExposureChange(before, after, path, conflict ? null : afterResolution.source(),
				conflict ? null : afterResolution.sourceObject(), countPublishedReleasesResolvingTo(org, scope, object),
				widened);
	}

	static TeaEffectiveVisibility visibilityOf(TeaResolvedProfile r) {
		if (null == r || TeaResolutionStatus.CONFLICT == r.status() || null == r.profile()) {
			return TeaEffectiveVisibility.UNRESOLVED;
		}
		return TeaVisibility.PUBLIC == r.profile().getVisibility() ? TeaEffectiveVisibility.PUBLIC
				: TeaEffectiveVisibility.PRIVATE;
	}

	/** The validation of a save, the archived rules included, and the prospective row. */
	private PreparedSave prepareSave(UUID org, TeaProfileScope scope, UUID object, TeaProfileInputDto incoming)
			throws RelizaException {
		if (null == org) throw new RelizaException("org is required");
		if (null == scope) throw new RelizaException("scope is required");
		if (null == incoming) throw new RelizaException("profile is required");
		OrganizationData od = getOrganizationService.getOrganizationData(org)
				.orElseThrow(() -> new RelizaException("Organization not found: " + org));

		TeaProfileData d = new TeaProfileData();
		d.setOrg(org);
		d.setScope(scope);
		ComponentData cd = ScopedSettingsValidation.validateScope(org, settingsScope(scope), object,
				settingsMode(incoming.getMode()), incoming.getFollowedPerspective(), WHAT, ossPerspectiveService,
				getComponentService);
		d.setObject(object);
		boolean follows = TeaProfileScope.COMPONENT == scope
				&& TeaComponentProfileMode.FOLLOW_PERSPECTIVE == incoming.getMode();
		if (TeaProfileScope.COMPONENT == scope) d.setMode(incoming.getMode());
		if (follows) {
			d.setFollowedPerspective(incoming.getFollowedPerspective());
			if (null != incoming.getProfiles() && !incoming.getProfiles().isEmpty()) {
				throw new RelizaException("profiles must be empty when mode is FOLLOW_PERSPECTIVE");
			}
		} else {
			copyProfileFields(incoming, d);
			validateProfileFields(d);
		}
		if (null != cd) refuseArchivedSave(cd, d);
		if (follows) {
			refuseFollowedNamesNotResolving(cd, d.getFollowedPerspective());
		} else if (TeaPublishing.ENABLED == d.getPublishing()) {
			refuseNamesNotResolving(org, scope, object, cd, d.getProfiles());
		}
		return new PreparedSave(od, d, cd);
	}

	/**
	 * The resolve check of an ENABLED row (task TEA-27, F-4): every name must resolve at the row's
	 * scope, at COMPONENT scope (an OVERRIDE row) at the component itself with no fallback, because the
	 * settings are the component's own. A DISABLED row is never refused for a recipe it does not
	 * generate; its names are checked the moment it is saved ENABLED.
	 */
	private void refuseNamesNotResolving(UUID org, TeaProfileScope scope, UUID object, ComponentData cd,
			List<String> names) throws RelizaException {
		String where = scopeLabel(scope, object);
		for (String name : names) {
			SbomProfileResolution r = TeaProfileScope.COMPONENT == scope
					? sbomProfileResolver.resolveForComponent(cd, name)
					: sbomProfileResolver.effectiveOfScope(org, settingsScope(scope), object, name);
			if (ScopedResolutionStatus.NOT_FOUND == r.status()) {
				throw new RelizaException("SBOM profile " + name + " does not resolve at " + where
						+ ": define it there or at a higher scope");
			}
			if (ScopedResolutionStatus.CONFLICT == r.status()) {
				throw new RelizaException("SBOM profile " + name + " resolves to a conflict between perspectives "
						+ perspectiveNames(r.conflictingPerspectives()) + " at " + where
						+ ": define it on the component or follow one perspective for it");
			}
		}
	}

	/**
	 * The save rule of a FOLLOW row (task TEA-27, F-1): when the followed perspective's row exists and
	 * is ENABLED, every name of it must resolve at the component with the followed perspective
	 * preferred, which is how the publish resolves it. An absent or DISABLED followed row leaves
	 * nothing to check; the publish-time refusal and the preview hold whatever changes later.
	 */
	private void refuseFollowedNamesNotResolving(ComponentData cd, UUID followed) throws RelizaException {
		Optional<TeaProfileData> row = getProfile(cd.getOrg(), TeaProfileScope.PERSPECTIVE, followed);
		if (row.isEmpty() || TeaPublishing.ENABLED != row.get().getPublishing() || null == row.get().getProfiles()) {
			return;
		}
		for (String name : row.get().getProfiles()) {
			SbomProfileResolution r = sbomProfileResolver.resolveForComponent(cd, name, null, followed);
			String named = "SBOM profile " + name + ", named by the TEA publishing settings of perspective "
					+ perspectiveName(followed) + " that component " + cd.getName() + " would follow, ";
			if (ScopedResolutionStatus.NOT_FOUND == r.status()) {
				throw new RelizaException(named + "does not resolve for the component");
			}
			if (ScopedResolutionStatus.CONFLICT == r.status()) {
				throw new RelizaException(named + "resolves to a conflict between perspectives "
						+ perspectiveNames(r.conflictingPerspectives())
						+ " for the component; define it on the component or follow one perspective for it");
			}
		}
	}

	private static String perspectiveNames(List<TeaPerspectiveRef> refs) {
		return refs.stream().map(TeaPerspectiveRef::name).collect(Collectors.joining(", "));
	}

	/** How a refusal names a scope: "the organization", "perspective Alpha", "component widget". */
	private String scopeLabel(TeaProfileScope scope, UUID object) {
		return switch (scope) {
			case ORGANIZATION -> "the organization";
			case PERSPECTIVE -> "perspective " + perspectiveName(object);
			case COMPONENT -> "component " + (null == object ? "null"
					: getComponentService.getComponentData(object).map(ComponentData::getName)
							.orElse(object.toString()));
		};
	}

	private static boolean archived(ComponentData cd) {
		return StatusEnum.ARCHIVED == cd.getStatus();
	}

	/** An archived component or product takes only the hide row: OVERRIDE, publishing DISABLED, visibility PRIVATE. */
	private static void refuseArchivedSave(ComponentData cd, TeaProfileData d) throws RelizaException {
		if (!archived(cd)) return;
		if (TeaComponentProfileMode.FOLLOW_PERSPECTIVE == d.getMode()) {
			throw new RelizaException("Component " + cd.getName() + " is archived: it cannot follow a perspective; save an "
					+ "OVERRIDE with publishing DISABLED and visibility PRIVATE to hide what it published, or remove its "
					+ "TEA profile");
		}
		List<String> widening = new ArrayList<>();
		if (TeaPublishing.ENABLED == d.getPublishing()) widening.add("publishing ENABLED");
		if (TeaVisibility.PUBLIC == d.getVisibility()) widening.add("visibility PUBLIC");
		if (!widening.isEmpty()) {
			throw new RelizaException("Component " + cd.getName() + " is archived: its TEA profile can only hide it "
					+ "(publishing DISABLED, visibility PRIVATE); " + String.join(" and ", widening)
					+ (widening.size() > 1 ? " are" : " is") + " refused");
		}
	}

	/** Removing an archived component's row must not let it resolve to an ENABLED or PUBLIC profile, for anyone. */
	private void refuseArchivedRemoval(ComponentData cd) throws RelizaException {
		if (!archived(cd)) return;
		TeaResolvedProfile parent = teaProfileResolver.resolveForComponentWithRow(cd, null);
		List<Enum<?>> exposing = exposureOf(parent);
		if (!exposing.isEmpty()) {
			throw new RelizaException("Component " + cd.getName() + " is archived: without its TEA profile it would "
					+ "resolve to the " + exposureNames(exposing) + " profile of " + sourceLabel(parent)
					+ "; set publishing DISABLED and visibility PRIVATE on it instead");
		}
	}

	private String refusalOf(TeaExposureChange x, TeaProfileScope scope, UUID object, ComponentData cd,
			TeaProfileData d) {
		String source = sourceLabel(x.afterSource(), x.afterSourceObject());
		return switch (x.path()) {
			case FOLLOW_RESOLVES_PUBLIC -> "Only an organization admin can make component " + cd.getName()
					+ " follow perspective " + perspectiveName(d.getFollowedPerspective())
					+ ": it resolves to the PUBLIC TEA profile of " + source;
			case REMOVAL_RESOLVES_PUBLIC -> x.widened().isEmpty()
					? "Only an organization admin can remove the TEA profile of "
							+ (TeaProfileScope.COMPONENT == scope ? "component " + cd.getName()
									: "perspective " + perspectiveName(object))
							+ ": without it, it resolves to the PUBLIC TEA profile of " + source
					: "Only an organization admin can remove the TEA profile of perspective " + perspectiveName(object)
							+ ": without it, " + x.widened().size() + " component(s) resolve to a PUBLIC TEA profile, "
							+ "for instance component " + x.widened().get(0).name() + " to the PUBLIC TEA profile of "
							+ sourceLabel(x.widened().get(0).afterSource(), x.widened().get(0).afterSourceObject());
			default -> "Only an organization admin can save a PUBLIC TEA profile; an admin can make it PRIVATE first";
		};
	}

	private String sourceLabel(TeaResolvedProfile r) {
		return sourceLabel(r.source(), r.sourceObject());
	}

	private String sourceLabel(TeaProfileSource source, UUID sourceObject) {
		if (null == source) return "conflicting perspectives";
		return switch (source) {
			case COMPONENT -> "component " + getComponentService.getComponentData(sourceObject)
					.map(ComponentData::getName).orElse(String.valueOf(sourceObject));
			case FOLLOWED_PERSPECTIVE, PERSPECTIVE -> "perspective " + perspectiveName(sourceObject);
			case ORGANIZATION -> "the organization";
			case DEFAULT -> "the built-in defaults";
		};
	}

	private String perspectiveName(UUID perspective) {
		if (null == perspective) return "null";
		return ossPerspectiveService.teaPerspectiveRef(perspective).map(TeaPerspectiveRef::name)
				.orElse(perspective.toString());
	}

	private ComponentData componentOf(UUID org, UUID object) throws RelizaException {
		return ScopedSettingsValidation.componentOf(org, object, WHAT, getComponentService);
	}

	private static void copyProfileFields(TeaProfileInputDto in, TeaProfileData d) {
		d.setPublishing(in.getPublishing());
		d.setVisibility(in.getVisibility());
		d.setRawArtifacts(in.getRawArtifacts());
		d.setProductComponents(in.getProductComponents());
		d.setMinimumLifecycle(in.getMinimumLifecycle());
		d.setTei(in.getTei());
		d.setTeiDomain(in.getTeiDomain());
		d.setProfiles(in.getProfiles());
	}

	/** The profile fields every OVERRIDE, PERSPECTIVE or ORGANIZATION save must carry; teiDomain is checked with tei. */
	static final Map<String, Function<TeaProfileData, Object>> REQUIRED_FIELDS = requiredFields();

	private static Map<String, Function<TeaProfileData, Object>> requiredFields() {
		Map<String, Function<TeaProfileData, Object>> m = new LinkedHashMap<>();
		m.put("publishing", TeaProfileData::getPublishing);
		m.put("visibility", TeaProfileData::getVisibility);
		m.put("rawArtifacts", TeaProfileData::getRawArtifacts);
		m.put("productComponents", TeaProfileData::getProductComponents);
		m.put("minimumLifecycle", TeaProfileData::getMinimumLifecycle);
		m.put("tei", TeaProfileData::getTei);
		m.put("profiles", TeaProfileData::getProfiles);
		return m;
	}

	private static void validateProfileFields(TeaProfileData d) throws RelizaException {
		for (Map.Entry<String, Function<TeaProfileData, Object>> f : REQUIRED_FIELDS.entrySet()) {
			if (null == f.getValue().apply(d)) throw new RelizaException(f.getKey() + " is required");
		}
		validateProfileNames(d.getProfiles(), LicensingConstants.isOssEdition());
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
	}

	/**
	 * The names a non-FOLLOW row publishes (task TEA-27): non-empty, every name a valid SBOM profile
	 * name, no duplicate; exactly one on ReARM CE, at most {@link #MAX_PROFILE_NAMES} on ReARM Pro. The
	 * edition comes from the oss seam ({@code LicensingConstants.isOssEdition()}) at the one call site,
	 * so this rule never branches on an edition flag of its own and both branches are unit-testable.
	 */
	static void validateProfileNames(List<String> names, boolean ossEdition) throws RelizaException {
		if (null == names || names.isEmpty()) {
			throw new RelizaException("profiles must name at least one SBOM profile");
		}
		Set<String> seen = new HashSet<>();
		for (String name : names) {
			SbomProfileService.validateName(name);
			if (!seen.add(name)) throw new RelizaException("profiles names SBOM profile " + name + " twice");
		}
		if (ossEdition && names.size() != 1) {
			throw new RelizaException("ReARM CE publishes one SBOM profile per TEA publishing settings; several profiles "
					+ "are a ReARM Pro feature");
		}
		if (names.size() > MAX_PROFILE_NAMES) {
			throw new RelizaException("TEA publishing settings name at most " + MAX_PROFILE_NAMES + " SBOM profiles");
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

	private static final String ORG_ROW_NOT_DELETABLE =
			"The organization TEA profile cannot be deleted; reset it to the defaults instead";

	/**
	 * Remove a PERSPECTIVE or COMPONENT row; the organization row is reset, never deleted. An
	 * archived component's row stays while its parent would expose it, and a removal that makes
	 * the object resolve PUBLIC needs {@link TeaCaller#ORG_ADMIN}.
	 */
	@Transactional
	public boolean deleteProfile(UUID org, TeaProfileScope scope, UUID object, TeaCaller caller, WhoUpdated wu)
			throws RelizaException {
		if (TeaProfileScope.ORGANIZATION == scope) throw new RelizaException(ORG_ROW_NOT_DELETABLE);
		Optional<TeaProfile> existing = findEntity(org, scope, object);
		if (existing.isEmpty()) return false;
		ComponentData cd = TeaProfileScope.COMPONENT == scope ? componentOf(org, object) : null;
		if (null != cd) refuseArchivedRemoval(cd);
		TeaExposureChange x = assessRemoval(org, scope, object, cd);
		if (TeaPublicPath.NONE != x.path() && TeaCaller.ORG_ADMIN != caller) {
			throw new RelizaException(refusalOf(x, scope, object, cd, null));
		}
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
	 * profile goes PUBLIC states it. The publication engine (task TEA-5) answers it.
	 */
	public int countPublishedReleasesResolvingTo(UUID org, TeaProfileScope scope, UUID object) {
		return null == teaPublicationService ? 0 : teaPublicationService.countPublishedResolvingTo(org, scope, object);
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
