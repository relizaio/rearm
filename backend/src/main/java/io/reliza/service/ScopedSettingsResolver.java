/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.ComponentData;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.SettingsScope;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import lombok.extern.slf4j.Slf4j;

/**
 * The one resolver of every scoped settings type (task TEA-27): TEA-2's TEA profile walk with the row
 * type and the row store abstracted out. The first rule that yields a row wins: the component's own
 * row (OVERRIDE, or FOLLOW_PERSPECTIVE of one of its perspectives), then exactly one candidate
 * perspective row, then, among two or more candidate rows and no own row, the recorded row, then the
 * preferred perspective's row, else a CONFLICT; then the organization row, then the store's built-in
 * default, else NOT_FOUND. A FOLLOW whose target is no candidate or has no row is resolved past with
 * one WARN.
 *
 * <p>Candidate perspectives are what the oss perspective hook answers: the component's real, active
 * perspectives in ReARM Pro, none in CE; product-derived and archived perspectives are never
 * candidates. The resolver only answers; what a CONFLICT or NOT_FOUND means is the caller's.
 *
 * <p>The two request-time fallbacks, the recorded row and the preferred perspective, are parameters
 * only {@link #resolveForComponent(ScopedSettingsStore, ComponentData, UUID, UUID)} takes; every other
 * method passes none, so the catalog, the editors and the gates see a CONFLICT as a CONFLICT.
 */
@Slf4j
@Service
public class ScopedSettingsResolver {

	/** Perspective names in a stable, human order; a missing name sorts last. */
	static final Comparator<String> NAME_ORDER = Comparator.nullsLast(
			String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));

	@Autowired private OssPerspectiveService ossPerspectiveService;
	@Autowired private GetComponentService getComponentService;

	/**
	 * A resolution. {@code settings} is null on a CONFLICT and on NOT_FOUND; on a CONFLICT
	 * {@code conflictingPerspectives} lists every candidate perspective that has a row, in name order,
	 * and it is empty otherwise. {@code source} is null on NOT_FOUND.
	 */
	public record ScopedResolution<T>(ScopedResolutionStatus status, ScopedSettingsSource source, UUID sourceObject,
			T settings, List<TeaPerspectiveRef> conflictingPerspectives) {

		static <T> ScopedResolution<T> resolved(ScopedSettingsSource source, UUID sourceObject, T settings) {
			return new ScopedResolution<>(ScopedResolutionStatus.RESOLVED, source, sourceObject, settings, List.of());
		}

		static <T> ScopedResolution<T> notFound() {
			return new ScopedResolution<>(ScopedResolutionStatus.NOT_FOUND, null, null, null, List.of());
		}
	}

	/**
	 * The component's resolution with its stored row and candidates, and the two request-time
	 * fallbacks for a CONFLICT: the row whose uuid is {@code recordedRow} first, then the row of
	 * {@code preferredPerspective}; either one that is not among the conflicting rows changes nothing.
	 */
	public <T> ScopedResolution<T> resolveForComponent(ScopedSettingsStore<T> store, ComponentData cd, UUID recordedRow,
			UUID preferredPerspective) {
		return resolve(store, cd, storedRow(store, cd, Set.of()), ossPerspectiveService.teaPerspectivesOfComponent(cd),
				recordedRow, preferredPerspective, Set.of());
	}

	/** As the four-argument form with no preferred perspective. */
	public <T> ScopedResolution<T> resolveForComponent(ScopedSettingsStore<T> store, ComponentData cd, UUID recordedRow) {
		return resolveForComponent(store, cd, recordedRow, null);
	}

	/**
	 * The resolution the component would have if its own row were {@code ownRow} (null: no row), with
	 * the same candidates; the stored row is not read; no fallback.
	 */
	public <T> ScopedResolution<T> resolveForComponentWithRow(ScopedSettingsStore<T> store, ComponentData cd, T ownRow) {
		return resolve(store, cd, ownRow, ossPerspectiveService.teaPerspectivesOfComponent(cd), null, null, Set.of());
	}

	/**
	 * The resolution the component would have if its perspectives were {@code perspectives}, with its
	 * stored row: the candidates are those of the uuids that are real, active perspectives of its
	 * organization; no fallback.
	 */
	public <T> ScopedResolution<T> resolveForComponentWithPerspectives(ScopedSettingsStore<T> store, ComponentData cd,
			Collection<UUID> perspectives) {
		return resolve(store, cd, storedRow(store, cd, Set.of()),
				ossPerspectiveService.teaPerspectiveRefs(cd.getOrg(), perspectives), null, null, Set.of());
	}

	/**
	 * The resolution the component would have if the rows whose uuids are in {@code absentRowUuids}
	 * did not exist, at any scope: a FOLLOW of such a row falls through and the perspective step counts
	 * the rows without it; no fallback.
	 */
	public <T> ScopedResolution<T> resolveForComponentWithoutRows(ScopedSettingsStore<T> store, ComponentData cd,
			Set<UUID> absentRowUuids) {
		Set<UUID> absent = null == absentRowUuids ? Set.of() : absentRowUuids;
		return resolve(store, cd, storedRow(store, cd, absent), ossPerspectiveService.teaPerspectivesOfComponent(cd),
				null, null, absent);
	}

	/** The settings in force at a scope: what an editor shows as effective; no fallback. */
	public <T> ScopedResolution<T> effectiveOfScope(ScopedSettingsStore<T> store, UUID org, SettingsScope scope,
			UUID object) {
		return effectiveOfScopeWithoutRows(store, org, scope, object, Set.of());
	}

	/** As {@link #effectiveOfScope} with the rows of {@code absentRowUuids} read as missing, at any scope. */
	public <T> ScopedResolution<T> effectiveOfScopeWithoutRows(ScopedSettingsStore<T> store, UUID org,
			SettingsScope scope, UUID object, Set<UUID> absentRowUuids) {
		Set<UUID> absent = null == absentRowUuids ? Set.of() : absentRowUuids;
		switch (null == scope ? SettingsScope.ORGANIZATION : scope) {
			case COMPONENT: {
				Optional<ComponentData> cd = component(org, object);
				return cd.isPresent() ? resolveForComponentWithoutRows(store, cd.get(), absent)
						: organizationStep(store, org, absent);
			}
			case PERSPECTIVE: {
				Optional<T> row = null == object ? Optional.empty()
						: store.perspectiveRow(org, object).filter(r -> present(store, r, absent));
				return row.isPresent()
						? normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.PERSPECTIVE, object,
								row.get()))
						: organizationStep(store, org, absent);
			}
			default:
				return organizationStep(store, org, absent);
		}
	}

	/**
	 * The same walk with the scope's own row left out: what an editor pre-fills from. A COMPONENT's
	 * parent is its perspective step and below, so it can be a CONFLICT; no fallback.
	 */
	public <T> ScopedResolution<T> parentOfScope(ScopedSettingsStore<T> store, UUID org, SettingsScope scope,
			UUID object) {
		switch (null == scope ? SettingsScope.ORGANIZATION : scope) {
			case COMPONENT: {
				Optional<ComponentData> cd = component(org, object);
				return cd.isPresent()
						? perspectiveStepAndBelow(store, org, ossPerspectiveService.teaPerspectivesOfComponent(cd.get()),
								null, null, Set.of())
						: organizationStep(store, org, Set.of());
			}
			case PERSPECTIVE:
				return organizationStep(store, org, Set.of());
			default:
				return defaultStep(store, org);
		}
	}

	private <T> T storedRow(ScopedSettingsStore<T> store, ComponentData cd, Set<UUID> absent) {
		return store.componentRow(cd.getOrg(), cd.getUuid()).filter(r -> present(store, r, absent)).orElse(null);
	}

	private static <T> boolean present(ScopedSettingsStore<T> store, T row, Set<UUID> absent) {
		return absent.isEmpty() || !absent.contains(store.uuidOf(row));
	}

	private Optional<ComponentData> component(UUID org, UUID object) {
		if (null == object || null == org) return Optional.empty();
		return getComponentService.getComponentData(object).filter(c -> org.equals(c.getOrg()));
	}

	/** The walk: the own row, then the perspective step over {@code candidates}, then the organization. */
	private <T> ScopedResolution<T> resolve(ScopedSettingsStore<T> store, ComponentData cd, T ownRow,
			List<TeaPerspectiveRef> candidates, UUID recordedRow, UUID preferredPerspective, Set<UUID> absent) {
		UUID org = cd.getOrg();
		if (null != ownRow) {
			ScopedSettingsMode mode = store.modeOf(ownRow);
			if (ScopedSettingsMode.OVERRIDE == mode) {
				return normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.COMPONENT, cd.getUuid(),
						ownRow));
			}
			if (ScopedSettingsMode.FOLLOW_PERSPECTIVE == mode) {
				UUID followed = store.followedPerspectiveOf(ownRow);
				boolean candidate = null != followed && candidates.stream().anyMatch(c -> followed.equals(c.uuid()));
				Optional<T> target = candidate
						? store.perspectiveRow(org, followed).filter(r -> present(store, r, absent))
						: Optional.empty();
				if (target.isPresent()) {
					return normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.FOLLOWED_PERSPECTIVE,
							followed, target.get()));
				}
				log.warn(store.label() + " of component " + cd.getUuid() + " follows perspective " + followed
						+ ", which " + (candidate ? "has no " + store.label() : "is no longer one of its perspectives")
						+ "; resolving past it");
			} else {
				log.warn(store.label() + " of component " + cd.getUuid() + " has no mode; resolving past it");
			}
		}
		return perspectiveStepAndBelow(store, org, candidates, recordedRow, preferredPerspective, absent);
	}

	private <T> ScopedResolution<T> perspectiveStepAndBelow(ScopedSettingsStore<T> store, UUID org,
			List<TeaPerspectiveRef> candidates, UUID recordedRow, UUID preferredPerspective, Set<UUID> absent) {
		if (!candidates.isEmpty()) {
			Map<UUID, TeaPerspectiveRef> byUuid = candidates.stream()
					.collect(Collectors.toMap(TeaPerspectiveRef::uuid, Function.identity(), (a, b) -> a));
			List<T> rows = store.perspectiveRows(org, byUuid.keySet()).stream()
					.filter(r -> byUuid.containsKey(store.objectOf(r)) && present(store, r, absent)).toList();
			if (rows.size() == 1) {
				T row = rows.get(0);
				return normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.PERSPECTIVE,
						store.objectOf(row), row));
			}
			if (rows.size() > 1) {
				if (null != recordedRow) {
					Optional<T> recorded = rows.stream().filter(r -> recordedRow.equals(store.uuidOf(r))).findFirst();
					if (recorded.isPresent()) {
						return normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.PERSPECTIVE,
								store.objectOf(recorded.get()), recorded.get()));
					}
				}
				if (null != preferredPerspective) {
					Optional<T> preferred = rows.stream().filter(r -> preferredPerspective.equals(store.objectOf(r)))
							.findFirst();
					if (preferred.isPresent()) {
						return normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.PERSPECTIVE,
								preferredPerspective, preferred.get()));
					}
				}
				List<TeaPerspectiveRef> conflicting = rows.stream().map(r -> byUuid.get(store.objectOf(r)))
						.sorted(Comparator.comparing(TeaPerspectiveRef::name, NAME_ORDER))
						.toList();
				return new ScopedResolution<>(ScopedResolutionStatus.CONFLICT, ScopedSettingsSource.PERSPECTIVE, null,
						null, conflicting);
			}
		}
		return organizationStep(store, org, absent);
	}

	private <T> ScopedResolution<T> organizationStep(ScopedSettingsStore<T> store, UUID org, Set<UUID> absent) {
		Optional<T> row = store.organizationRow(org).filter(r -> present(store, r, absent));
		if (row.isPresent()) {
			return normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.ORGANIZATION, null, row.get()));
		}
		return defaultStep(store, org);
	}

	private <T> ScopedResolution<T> defaultStep(ScopedSettingsStore<T> store, UUID org) {
		Optional<T> d = store.builtInDefault(org);
		if (d.isEmpty()) return ScopedResolution.notFound();
		return normalized(store, org, ScopedResolution.resolved(ScopedSettingsSource.DEFAULT, null, d.get()));
	}

	/** The store's normalization of every RESOLVED answer; the stored row is never touched. */
	private static <T> ScopedResolution<T> normalized(ScopedSettingsStore<T> store, UUID org, ScopedResolution<T> r) {
		if (ScopedResolutionStatus.RESOLVED != r.status() || null == r.settings()) return r;
		T effective = store.normalized(org, r.settings());
		if (effective == r.settings()) return r;
		return new ScopedResolution<>(r.status(), r.source(), r.sourceObject(), effective, r.conflictingPerspectives());
	}
}
