/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ComponentData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaSupportMetadata;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import lombok.extern.slf4j.Slf4j;

/**
 * Which TEA profile a component resolves to (task TEA-2). The first rule that yields a profile
 * wins: the component's own row (OVERRIDE, or FOLLOW_PERSPECTIVE of one of its perspectives), then
 * exactly one perspective row among the component's perspectives, then the organization row,
 * then the built-in defaults. Two or more candidate perspective rows and no component row is a
 * CONFLICT: part 4 refuses to publish it and part 5 conceals it. The resolver only answers.
 *
 * <p>Candidate perspectives are what the oss perspective hook answers: the component's real,
 * active perspectives in ReARM Pro, none in CE. Product-derived perspectives are never
 * candidates; a product's profile reaches component releases through the product cascade only,
 * which resolves the product once and never its children.
 */
@Slf4j
@Service
public class TeaProfileResolver {

	/** Perspective names in a stable, human order; a missing name sorts last. */
	static final Comparator<String> NAME_ORDER = Comparator.nullsLast(
			String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

	@Autowired private TeaProfileService teaProfileService;
	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private SupportInjectionService supportInjectionService;
	@Autowired private GetComponentService getComponentService;

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
		UUID org = cd.getOrg();
		List<TeaPerspectiveRef> candidates = ossPerspectiveService.teaPerspectivesOfComponent(cd);
		Optional<TeaProfileData> own = teaProfileService.getProfile(org, TeaProfileScope.COMPONENT, cd.getUuid());
		if (own.isPresent()) {
			TeaProfileData row = own.get();
			if (TeaComponentProfileMode.OVERRIDE == row.getMode()) {
				return normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.COMPONENT, cd.getUuid(), row));
			}
			if (TeaComponentProfileMode.FOLLOW_PERSPECTIVE == row.getMode()) {
				UUID followed = row.getFollowedPerspective();
				boolean candidate = null != followed && candidates.stream().anyMatch(c -> followed.equals(c.uuid()));
				Optional<TeaProfileData> target = candidate
						? teaProfileService.getProfile(org, TeaProfileScope.PERSPECTIVE, followed)
						: Optional.empty();
				if (target.isPresent()) {
					return normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.FOLLOWED_PERSPECTIVE, followed,
							target.get()));
				}
				log.warn("TEA profile of component " + cd.getUuid() + " follows perspective " + followed
						+ ", which is " + (candidate ? "without a TEA profile" : "no longer one of its perspectives")
						+ "; resolving past it");
			} else {
				log.warn("TEA profile of component " + cd.getUuid() + " has no mode; resolving past it");
			}
		}
		return perspectiveStepAndBelow(org, candidates, recordedProfile);
	}

	/** The profile in force at a scope: what the editor shows as effective. */
	public TeaResolvedProfile effectiveOfScope(UUID org, TeaProfileScope scope, UUID object) {
		switch (scope) {
			case COMPONENT: {
				Optional<ComponentData> cd = component(org, object);
				return cd.isPresent() ? resolveForComponent(cd.get()) : organizationStep(org);
			}
			case PERSPECTIVE: {
				Optional<TeaProfileData> row = teaProfileService.getProfile(org, TeaProfileScope.PERSPECTIVE, object);
				return row.isPresent()
						? normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.PERSPECTIVE, object, row.get()))
						: organizationStep(org);
			}
			default:
				return organizationStep(org);
		}
	}

	/**
	 * The same walk with the scope's own row left out: what the editor pre-fills from. A
	 * COMPONENT's parent is its perspective step and below, so it can be a CONFLICT.
	 */
	public TeaResolvedProfile parentOfScope(UUID org, TeaProfileScope scope, UUID object) {
		switch (scope) {
			case COMPONENT: {
				Optional<ComponentData> cd = component(org, object);
				return cd.isPresent()
						? perspectiveStepAndBelow(org, ossPerspectiveService.teaPerspectivesOfComponent(cd.get()), null)
						: organizationStep(org);
			}
			case PERSPECTIVE:
				return organizationStep(org);
			default:
				return normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.DEFAULT, null,
						TeaProfileData.builtInDefault(org)));
		}
	}

	private Optional<ComponentData> component(UUID org, UUID object) {
		if (null == object) return Optional.empty();
		return getComponentService.getComponentData(object).filter(c -> org.equals(c.getOrg()));
	}

	private TeaResolvedProfile perspectiveStepAndBelow(UUID org, List<TeaPerspectiveRef> candidates,
			UUID recordedProfile) {
		if (!candidates.isEmpty()) {
			Map<UUID, TeaPerspectiveRef> byUuid = candidates.stream()
					.collect(Collectors.toMap(TeaPerspectiveRef::uuid, Function.identity(), (a, b) -> a));
			List<TeaProfileData> rows = teaProfileService.listPerspectiveProfiles(org, byUuid.keySet()).stream()
					.filter(r -> byUuid.containsKey(r.getObject())).toList();
			if (rows.size() == 1) {
				TeaProfileData row = rows.get(0);
				return normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.PERSPECTIVE, row.getObject(), row));
			}
			if (rows.size() > 1) {
				if (null != recordedProfile) {
					Optional<TeaProfileData> recorded = rows.stream()
							.filter(r -> recordedProfile.equals(r.getUuid())).findFirst();
					if (recorded.isPresent()) {
						return normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.PERSPECTIVE,
								recorded.get().getObject(), recorded.get()));
					}
				}
				List<TeaPerspectiveRef> conflicting = rows.stream().map(r -> byUuid.get(r.getObject()))
						.sorted(Comparator.comparing(TeaPerspectiveRef::name, NAME_ORDER))
						.toList();
				return new TeaResolvedProfile(TeaResolutionStatus.CONFLICT, TeaProfileSource.PERSPECTIVE, null, null,
						conflicting);
			}
		}
		return organizationStep(org);
	}

	private TeaResolvedProfile organizationStep(UUID org) {
		Optional<TeaProfileData> row = teaProfileService.getProfile(org, TeaProfileScope.ORGANIZATION, null);
		if (row.isPresent()) {
			return normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.ORGANIZATION, null, row.get()));
		}
		return normalized(org, TeaResolvedProfile.resolved(TeaProfileSource.DEFAULT, null,
				TeaProfileData.builtInDefault(org)));
	}

	/**
	 * The only effective normalization: supportMetadata reads EXCLUDE while the organization's
	 * support injection is off, whatever is stored, because the org switch can be turned off after
	 * a profile stored INCLUDE and a profile must never inject what the org no longer allows. The
	 * stored row is never touched. Whether injection is on is SupportInjectionService's one
	 * answer, the same every export egress uses.
	 */
	private TeaResolvedProfile normalized(UUID org, TeaResolvedProfile r) {
		TeaProfileData p = r.profile();
		if (null == p || TeaSupportMetadata.INCLUDE != p.getSupportMetadata()) return r;
		if (supportInjectionService.isInjectionEnabled(org)) return r;
		TeaProfileData effective = p.copy();
		effective.setSupportMetadata(TeaSupportMetadata.EXCLUDE);
		return new TeaResolvedProfile(r.status(), r.source(), r.sourceObject(), effective, r.conflictingPerspectives());
	}

	/** The organization's support injection switch, as the editor needs it; unknown reads as DISABLED. */
	public SupportInjectionSetting supportInjectionOf(UUID org) {
		return supportInjectionService.isInjectionEnabled(org) ? SupportInjectionSetting.ENABLED
				: SupportInjectionSetting.DISABLED;
	}
}
