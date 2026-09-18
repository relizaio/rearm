/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import io.reliza.exceptions.ActionRefusedException;
import io.reliza.model.ComponentData;
import io.reliza.model.GuardedAction;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;

/**
 * Optional Pro-side gate on an action.
 *
 * <p>The counterpart of the trigger engine: triggers decide when something happens, this decides
 * whether something somebody asked for directly may happen at all. Both speak CEL over the same
 * activation, so a rule reads the same wherever it is written.
 *
 * <p>Keyed by {@link GuardedAction} rather than one method per action, so adding an action means
 * contributing its variables to the context -- the storage, resolution, modes and reporting are
 * already shared. Absent in CE builds, where actions stay ungoverned.
 */
public interface ActionGuardHook {

	/**
	 * What the guards had to say about one attempted action.
	 *
	 * @param refusals one line per blocking guard that was not satisfied; empty means proceed
	 * @param notes one line per reporting-mode guard that was not satisfied
	 */
	public record GuardAssessment(List<String> refusals, List<String> notes) {
		public static final GuardAssessment CLEAR = new GuardAssessment(List.of(), List.of());

		public boolean refused() {
			return !refusals.isEmpty();
		}
	}

	/**
	 * Evaluate every guard that governs this action, once.
	 *
	 * <p>One call rather than separate refuse / report / cascade questions: resolving the guards
	 * means reading the component, its perspectives and the organization, and evaluating one
	 * means building the release activation. A promotion used to pay for all of that up to four
	 * times over to answer three questions about the same set of guards.
	 *
	 * @param action what is being attempted
	 * @param release the release the action concerns
	 * @param actionVars action-specific variables, exposed to the expression under {@code action}
	 */
	default GuardAssessment assess(GuardedAction action, ReleaseData release,
			Map<String, Object> actionVars) {
		return assess(action, release, actionVars, new GuardResolutionCache());
	}

	/**
	 * Rows read while resolving guards, shared across an assessment of several releases.
	 *
	 * <p>A promotion assesses its own release and then every dependency the cascade would carry,
	 * and those are overwhelmingly the same organization and often the same components. Without
	 * somewhere to put them, a ten-dependency product reads the organization eleven times to
	 * answer one question. Deliberately a plain holder passed in by the caller rather than a
	 * cache with a lifetime of its own: it lives exactly as long as the one operation that made
	 * it, so no guard decision is ever taken on a row read for an earlier one.
	 */
	public static final class GuardResolutionCache {
		private final Map<UUID, Optional<ComponentData>> components = new HashMap<>();
		private final Map<UUID, Optional<OrganizationData>> orgs = new HashMap<>();

		public Optional<ComponentData> component(UUID uuid, Function<UUID, Optional<ComponentData>> read) {
			return components.computeIfAbsent(uuid, read);
		}

		public Optional<OrganizationData> org(UUID uuid, Function<UUID, Optional<OrganizationData>> read) {
			return orgs.computeIfAbsent(uuid, read);
		}
	}

	/** @param cache reused reads; see {@link GuardResolutionCache}. */
	GuardAssessment assess(GuardedAction action, ReleaseData release, Map<String, Object> actionVars,
			GuardResolutionCache cache);

	/**
	 * Refuse the action when a blocking guard is unsatisfied. Call before anything is written, so
	 * that a refusal leaves the subject untouched.
	 *
	 * @throws ActionRefusedException naming the guards that refused
	 */
	default void enforce(GuardedAction action, ReleaseData release, Map<String, Object> actionVars) {
		GuardAssessment assessment = assess(action, release, actionVars);
		if (assessment.refused()) throw refusal(release, assessment.refusals());
	}

	/** The wording of a refusal, in one place so every path that raises one reads the same. */
	static ActionRefusedException refusal(ReleaseData release, List<String> refusals) {
		return new ActionRefusedException("Release " + release.getVersion() + " cannot be promoted: "
				+ String.join(", ", refusals) + " not satisfied");
	}
}
