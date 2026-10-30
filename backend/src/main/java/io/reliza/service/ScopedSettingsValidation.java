/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.RelizaObject;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.SettingsScope;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * The scope and object rules every scoped settings save shares (task TEA-27, extracted from TEA-2's
 * {@code TeaProfileService.validateScopeAndObject}). {@code what} names the type in the messages
 * ("TEA profile", "SBOM profile").
 */
public final class ScopedSettingsValidation {

	private ScopedSettingsValidation() {}

	/**
	 * ORGANIZATION takes no object and no mode; PERSPECTIVE needs perspectives (ReARM Pro), a real
	 * non-PRODUCT perspective of the organization, and no mode; COMPONENT needs a component of the
	 * organization and a mode, and {@code followedPerspective} only with FOLLOW_PERSPECTIVE and among the
	 * component's candidate perspectives (a foreign uuid is named by uuid only, TEA-9 3.13). Answers the
	 * component at COMPONENT scope, else null.
	 */
	public static ComponentData validateScope(UUID org, SettingsScope scope, UUID object, ScopedSettingsMode mode,
			UUID followedPerspective, String what, OssPerspectiveService perspectives, GetComponentService components)
			throws RelizaException {
		if (null == org) throw new RelizaException("org is required");
		if (null == scope) throw new RelizaException("scope is required");
		switch (scope) {
			case ORGANIZATION -> {
				if (null != object) throw new RelizaException("object must be empty for an ORGANIZATION " + what);
				if (null != mode) throw new RelizaException("mode must be empty for an ORGANIZATION " + what);
				if (null != followedPerspective) {
					throw new RelizaException("followedPerspective must be empty for an ORGANIZATION " + what);
				}
				return null;
			}
			case PERSPECTIVE -> {
				if (null == object) throw new RelizaException("object is required for a PERSPECTIVE " + what);
				if (!perspectives.teaPerspectivesSupported()) {
					throw new RelizaException("PERSPECTIVE " + what + "s need perspectives, which are a ReARM Pro feature");
				}
				TeaPerspectiveRef ref = perspectives.teaPerspectiveRef(object)
						.orElseThrow(() -> new RelizaException("Perspective not found: " + object));
				if (PerspectiveType.PRODUCT == ref.type()) {
					throw new RelizaException("Perspective " + ref.name() + " is a product; its " + what + " is the "
							+ "product's COMPONENT-scope profile, edit it there");
				}
				Optional<RelizaObject> ro = perspectives.teaPerspectiveObject(object);
				if (ro.isEmpty() || !org.equals(ro.get().getOrg())) {
					throw new RelizaException("Perspective not found: " + object);
				}
				if (null != mode) throw new RelizaException("mode must be empty for a PERSPECTIVE " + what);
				if (null != followedPerspective) {
					throw new RelizaException("followedPerspective must be empty for a PERSPECTIVE " + what);
				}
				return null;
			}
			default -> {
				ComponentData cd = componentOf(org, object, what, components);
				if (null == mode) {
					throw new RelizaException("mode is required for a COMPONENT " + what + ": OVERRIDE or FOLLOW_PERSPECTIVE");
				}
				if (ScopedSettingsMode.OVERRIDE == mode) {
					if (null != followedPerspective) {
						throw new RelizaException("followedPerspective must be empty when mode is OVERRIDE");
					}
				} else {
					if (null == followedPerspective) {
						throw new RelizaException("followedPerspective is required when mode is FOLLOW_PERSPECTIVE");
					}
					List<TeaPerspectiveRef> candidates = perspectives.teaPerspectivesOfComponent(cd);
					if (candidates.stream().noneMatch(c -> followedPerspective.equals(c.uuid()))) {
						// Named by uuid only: the uuid may be another organization's perspective (TEA-9 3.13).
						String names = candidates.isEmpty() ? "none"
								: candidates.stream().map(TeaPerspectiveRef::name)
										.sorted(ScopedSettingsResolver.NAME_ORDER).collect(Collectors.joining(", "));
						throw new RelizaException("followedPerspective " + followedPerspective
								+ " is not a perspective of component " + cd.getName() + "; its perspectives: " + names);
					}
				}
				return cd;
			}
		}
	}

	/** The component of the organization; a missing or foreign one is "not found". */
	public static ComponentData componentOf(UUID org, UUID object, String what, GetComponentService components)
			throws RelizaException {
		if (null == object) throw new RelizaException("object is required for a COMPONENT " + what);
		return components.getComponentData(object)
				.filter(c -> org.equals(c.getOrg()))
				.orElseThrow(() -> new RelizaException("Component not found: " + object));
	}
}
