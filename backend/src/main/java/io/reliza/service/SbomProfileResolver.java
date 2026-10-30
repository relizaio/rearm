/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ComponentData;
import io.reliza.model.SbomProfileData;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.SettingsScope;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.service.ScopedSettingsResolver.ScopedResolution;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Which SBOM profile row a name resolves to (task TEA-27): the {@link ScopedSettingsResolver} walk per
 * name. A name resolves at a component as the engine walks: the component's own row of that name
 * (OVERRIDE, or the followed perspective's row of that name), exactly one candidate perspective row of
 * that name, then among two or more the recorded row and the preferred perspective's row (the publish
 * path only), else a CONFLICT for that name only; then the organization's row of that name, then the
 * built-in for {@code default}, else NOT_FOUND. A lower scope overrides a higher one by defining the
 * same name; a scope may define a name no higher scope knows.
 */
@Service
public class SbomProfileResolver {

	@Autowired private ScopedSettingsResolver scopedSettingsResolver;
	@Autowired private SbomProfileService sbomProfileService;
	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private SupportInjectionService supportInjectionService;

	/** A resolution of one name; {@code profile} is null on CONFLICT and NOT_FOUND, {@code source} null on NOT_FOUND. */
	public record SbomProfileResolution(ScopedResolutionStatus status, ScopedSettingsSource source, UUID sourceObject,
			SbomProfileData profile, List<TeaPerspectiveRef> conflictingPerspectives) {

		static SbomProfileResolution of(ScopedResolution<SbomProfileData> r) {
			return new SbomProfileResolution(r.status(), r.source(), r.sourceObject(), r.settings(),
					r.conflictingPerspectives());
		}
	}

	/** One name a scope offers, with its resolution there. */
	public record SbomProfileCatalogEntry(String name, SbomProfileResolution resolution) {}

	/** The name at the component, no fallback: the catalog, the editor and the save-time checks of an OVERRIDE row. */
	public SbomProfileResolution resolveForComponent(ComponentData cd, String name) {
		return resolveForComponent(cd, name, null, null);
	}

	/**
	 * The publish path: a CONFLICT of the name settles on the recorded row first (TEA-28), then on the
	 * row of {@code preferredPerspective}, the perspective the channel's own settings resolved from.
	 */
	public SbomProfileResolution resolveForComponent(ComponentData cd, String name, UUID recordedRow,
			UUID preferredPerspective) {
		return SbomProfileResolution.of(scopedSettingsResolver.resolveForComponent(sbomProfileService.storeFor(name), cd,
				recordedRow, preferredPerspective));
	}

	public SbomProfileResolution effectiveOfScope(UUID org, SettingsScope scope, UUID object, String name) {
		return SbomProfileResolution.of(scopedSettingsResolver.effectiveOfScope(sbomProfileService.storeFor(name), org,
				scope, object));
	}

	public SbomProfileResolution parentOfScope(UUID org, SettingsScope scope, UUID object, String name) {
		return SbomProfileResolution.of(scopedSettingsResolver.parentOfScope(sbomProfileService.storeFor(name), org,
				scope, object));
	}

	/**
	 * Every name that resolves or conflicts at the scope, in name order, each with its resolution
	 * there (no fallback): the names stored at the organization, at the scope's candidate perspectives
	 * (the component's, or the one perspective at PERSPECTIVE scope) and at the scope's own object,
	 * plus {@code default}.
	 */
	public List<SbomProfileCatalogEntry> catalogOfScope(UUID org, SettingsScope scope, UUID object) {
		Set<String> names = new LinkedHashSet<>();
		names.add(SbomProfileData.DEFAULT_NAME);
		sbomProfileService.listProfilesOfScope(org, SettingsScope.ORGANIZATION, null).forEach(p -> names.add(p.getName()));
		if (SettingsScope.PERSPECTIVE == scope && null != object) {
			sbomProfileService.listProfilesOfScope(org, SettingsScope.PERSPECTIVE, object)
					.forEach(p -> names.add(p.getName()));
		} else if (SettingsScope.COMPONENT == scope && null != object) {
			Optional<ComponentData> cd = getComponentService.getComponentData(object).filter(c -> org.equals(c.getOrg()));
			if (cd.isPresent()) {
				for (TeaPerspectiveRef p : ossPerspectiveService.teaPerspectivesOfComponent(cd.get())) {
					sbomProfileService.listProfilesOfScope(org, SettingsScope.PERSPECTIVE, p.uuid())
							.forEach(r -> names.add(r.getName()));
				}
				sbomProfileService.listProfilesOfScope(org, SettingsScope.COMPONENT, object)
						.forEach(p -> names.add(p.getName()));
			}
		}
		return names.stream().sorted(Comparator.comparing((String n) -> n, ScopedSettingsResolver.NAME_ORDER))
				.map(n -> new SbomProfileCatalogEntry(n, effectiveOfScope(org, scope, object, n))).toList();
	}

	/** The organization's support injection switch, as the editor needs it; unknown reads as DISABLED. */
	public SupportInjectionSetting supportInjectionOf(UUID org) {
		return supportInjectionService.isInjectionEnabled(org) ? SupportInjectionSetting.ENABLED
				: SupportInjectionSetting.DISABLED;
	}
}
