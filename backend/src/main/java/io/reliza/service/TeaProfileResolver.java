/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ComponentData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.service.ScopedSettingsResolver.ScopedResolution;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Which TEA profile (the TEA publishing settings of a scope) a component resolves to (task TEA-2;
 * a facade over {@link ScopedSettingsResolver} since task TEA-27). The first rule that yields a
 * profile wins: the component's own row (OVERRIDE, or FOLLOW_PERSPECTIVE of one of its perspectives),
 * then exactly one perspective row among the component's perspectives, then the organization row,
 * then the built-in defaults. Two or more candidate perspective rows and no component row is a
 * CONFLICT: part 4 refuses to publish it and part 5 conceals it. The resolver only answers.
 *
 * <p>Candidate perspectives are what the oss perspective hook answers: the component's real,
 * active perspectives in ReARM Pro, none in CE. Product-derived perspectives are never
 * candidates; a product's profile reaches component releases through the product cascade only,
 * which resolves the product once and never its children.
 *
 * <p>This facade never passes a preferred perspective to the engine: a TEA row's follow is its own
 * row's mode, so no TEA visibility or publishing answer can be picked by a preference (TEA-27 T1).
 */
@Service
public class TeaProfileResolver {

	/** Perspective names in a stable, human order; a missing name sorts last. */
	static final Comparator<String> NAME_ORDER = ScopedSettingsResolver.NAME_ORDER;

	@Autowired private TeaProfileService teaProfileService;
	@Autowired private ScopedSettingsResolver scopedSettingsResolver;
	@Autowired private SupportInjectionService supportInjectionService;

	public enum TeaResolutionStatus { RESOLVED, CONFLICT }

	public enum TeaProfileSource { COMPONENT, FOLLOWED_PERSPECTIVE, PERSPECTIVE, ORGANIZATION, DEFAULT }

	/**
	 * A resolution. {@code profile} is null on a CONFLICT, and {@code conflictingPerspectives}
	 * then lists every candidate perspective that has a row, in name order; it is empty otherwise.
	 */
	public record TeaResolvedProfile(TeaResolutionStatus status, TeaProfileSource source, UUID sourceObject,
			TeaProfileData profile, List<TeaPerspectiveRef> conflictingPerspectives) {

		static TeaResolvedProfile resolved(TeaProfileSource source, UUID sourceObject, TeaProfileData profile) {
			return new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, source, sourceObject, profile, List.of());
		}
	}

	public TeaResolvedProfile resolveForComponent(ComponentData cd) {
		return resolveForComponent(cd, null);
	}

	/**
	 * As {@link #resolveForComponent(ComponentData)}, except that a CONFLICT whose candidates
	 * include the row with uuid {@code recordedProfile} resolves to that row: the request-time
	 * fallback to the profile recorded on a publication while it is still a candidate. Any other
	 * CONFLICT stays one, which to the caller means concealed.
	 */
	public TeaResolvedProfile resolveForComponent(ComponentData cd, UUID recordedProfile) {
		return of(scopedSettingsResolver.resolveForComponent(store(), cd, recordedProfile, null));
	}

	/**
	 * The resolution the component would have if its own row were {@code ownRow} (null: no row),
	 * with the same candidates and FOLLOW tolerance as {@link #resolveForComponent(ComponentData)};
	 * the stored row is not read. The what-if a save or a removal is assessed on (task TEA-9).
	 */
	public TeaResolvedProfile resolveForComponentWithRow(ComponentData cd, TeaProfileData ownRow) {
		return of(scopedSettingsResolver.resolveForComponentWithRow(store(), cd, ownRow));
	}

	/**
	 * The resolution the component would have if its perspectives were {@code perspectives}, with its
	 * stored row: the candidates are those of the uuids that are real, active perspectives of its
	 * organization. The what-if a membership change is assessed on (task TEA-9, round 2).
	 */
	public TeaResolvedProfile resolveForComponentWithPerspectives(ComponentData cd, Collection<UUID> perspectives) {
		return of(scopedSettingsResolver.resolveForComponentWithPerspectives(store(), cd, perspectives));
	}

	/**
	 * The resolution the component would have if {@code perspective} had no TEA profile row, with its
	 * stored row and candidates: a FOLLOW of it falls through and the perspective step counts the
	 * rows without it. The what-if a perspective-row removal is assessed on per member (task TEA-9).
	 */
	public TeaResolvedProfile resolveForComponentWithoutPerspectiveRow(ComponentData cd, UUID perspective) {
		Set<UUID> absent = null == perspective ? Set.of()
				: teaProfileService.getProfile(cd.getOrg(), TeaProfileScope.PERSPECTIVE, perspective)
						.map(r -> Set.of(r.getUuid())).orElse(Set.of());
		return of(scopedSettingsResolver.resolveForComponentWithoutRows(store(), cd, absent));
	}

	/** The profile in force at a scope: what the editor shows as effective. */
	public TeaResolvedProfile effectiveOfScope(UUID org, TeaProfileScope scope, UUID object) {
		return of(scopedSettingsResolver.effectiveOfScope(store(), org, TeaProfileService.settingsScope(scope), object));
	}

	/**
	 * The same walk with the scope's own row left out: what the editor pre-fills from. A
	 * COMPONENT's parent is its perspective step and below, so it can be a CONFLICT.
	 */
	public TeaResolvedProfile parentOfScope(UUID org, TeaProfileScope scope, UUID object) {
		return of(scopedSettingsResolver.parentOfScope(store(), org, TeaProfileService.settingsScope(scope), object));
	}

	private ScopedSettingsStore<TeaProfileData> store() {
		return teaProfileService.store();
	}

	/** The engine's answer as the TEA API knows it: status and source mapped by name. */
	static TeaResolvedProfile of(ScopedResolution<TeaProfileData> r) {
		return new TeaResolvedProfile(TeaResolutionStatus.valueOf(r.status().name()),
				null == r.source() ? null : TeaProfileSource.valueOf(r.source().name()), r.sourceObject(), r.settings(),
				r.conflictingPerspectives());
	}

	/** The organization's support injection switch, as the editor needs it; unknown reads as DISABLED. */
	public SupportInjectionSetting supportInjectionOf(UUID org) {
		return supportInjectionService.isInjectionEnabled(org) ? SupportInjectionSetting.ENABLED
				: SupportInjectionSetting.DISABLED;
	}
}
