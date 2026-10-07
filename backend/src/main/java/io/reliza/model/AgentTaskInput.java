/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * What a task must be able to read before an agent may start it.
 *
 * <p>The board's Ready rule, in ReARM's terms: a stage does not begin until the things it works
 * from exist at a stated maturity. A requirement is declared on a role (every coder task needs
 * it) or on a single task (this one also needs the test plan), and the two are unioned at
 * eligibility time -- a task may add requirements, never weaken them, so where both name the same
 * input the higher threshold wins.
 *
 * <p>Requirements are inputs the work reads, not stages it follows. "Role B waits for role A" is
 * already said by {@code dependsOn} and sign-offs; saying it twice in two vocabularies would let
 * them disagree.
 */
public class AgentTaskInput {

	private AgentTaskInput() {}

	/** What kind of thing the task reads. */
	public enum InputKind {
		/** A specification document hanging off the board's target node. */
		DOCUMENT,
		/** A built release -- of the node itself, a named component, or this task's own output. */
		RELEASE
	}

	/** Which release a RELEASE requirement is about. */
	public enum InputScope {
		/** Any release of the component: "the thing is built". */
		COMPONENT,
		/** A release linked to this very task: "the work under review exists". */
		TASK
	}

	/** Which release of a component satisfies the threshold. */
	public enum InputResolution {
		/**
		 * Some release at or above the threshold exists; the binding resolves to the newest
		 * such release. A draft revision appearing does not un-satisfy a baselined document.
		 */
		LATEST_PASSING,
		/**
		 * The component's own latest release must clear the threshold. Stricter: work never
		 * proceeds against a superseded version, at the cost of stalling on every new draft.
		 */
		STRICT_LATEST
	}

	/**
	 * One declared requirement. {@code specification} applies to DOCUMENT, {@code scope} and
	 * {@code component} to RELEASE; a null component means the board's target node. A null
	 * {@code resolution} falls back to the board default.
	 *
	 * @param release one release pinned by uuid (task RD4-12): the inputs an investigation is commissioned
	 *        with, and the report pinned on the task that commissioned it. It resolves to that release and no
	 *        other, whatever the component's newest is; null on every other requirement
	 */
	public static record RequiredInput(InputKind kind, RearmSpecificationType specification,
			InputScope scope, UUID component, ReleaseLifecycle minLifecycle,
			InputResolution resolution, UUID release) implements Serializable {

		/** A requirement resolved through the board, not pinned to one release: every one from before pins. */
		public RequiredInput(InputKind kind, RearmSpecificationType specification, InputScope scope, UUID component,
				ReleaseLifecycle minLifecycle, InputResolution resolution) {
			this(kind, specification, scope, component, minLifecycle, resolution, null);
		}

		/** One release, pinned by uuid (task RD4-12). */
		public static RequiredInput pinned(InputKind kind, RearmSpecificationType specification, UUID release) {
			return new RequiredInput(kind, specification, null, null, null, null, release);
		}

		/**
		 * Identity for union/merge: two requirements about the same thing, whatever their thresholds. A pinned
		 * release is its own thing, so two pins never merge, and a pin never merges with the board's resolution.
		 */
		public String mergeKey() {
			return kind + "/" + specification + "/" + scope + "/" + component + (null == release ? "" : "/" + release);
		}
	}

	/**
	 * One child in a split, as the coordinator described it.
	 *
	 * <p>A record rather than a {@code Map<String, Object>} parsed inside the service. The service
	 * was doing the fetcher's job -- casting {@code Object} to String, testing whether a value
	 * happened to be a Number -- which put wire-shape knowledge in the place least able to report
	 * a good error about it, and made the accepted shape invisible to anyone reading the method.
	 *
	 * @param title required; a child with no title is not a task
	 * @param externalRef tracker reference, when the coordinator has already filed one
	 * @param sourceUrl human link to the same
	 * @param workLevel work level; inherits the parent's when absent
	 * @param producesComponent the component this child builds, if it differs from the parent's
	 * @param dependsOnSiblingIndexes positions in THIS split's list that must complete first,
	 *        which is how a coordinator encodes an ordering before the children have uuids
	 */
	public static record SplitChild(String title, String externalRef, String sourceUrl,
			Integer workLevel, UUID producesComponent, List<Integer> dependsOnSiblingIndexes, String description,
			String group, List<io.reliza.common.CommonVariables.TagRecord> tags)
			implements Serializable {

		public SplitChild {
			if (null == dependsOnSiblingIndexes) dependsOnSiblingIndexes = List.of();
		}

		/**
		 * Without a group or tags: the child inherits the parent's (task-groups-and-tags.md D9, task
		 * RD2-29), as every caller from before them does.
		 */
		public SplitChild(String title, String externalRef, String sourceUrl, Integer level,
				UUID producesComponent, List<Integer> dependsOnSiblingIndexes, String description) {
			this(title, externalRef, sourceUrl, level, producesComponent, dependsOnSiblingIndexes, description, null, null);
		}

		/** Without a description: every caller from before it (task fceb1e57). */
		public SplitChild(String title, String externalRef, String sourceUrl, Integer level,
				UUID producesComponent, List<Integer> dependsOnSiblingIndexes) {
			this(title, externalRef, sourceUrl, level, producesComponent, dependsOnSiblingIndexes, null);
		}
	}

	/**
	 * What a requirement resolved to when the task was assigned, pinned on the assignment so the
	 * agent knows which version it is working from and so a later rejection of that version can
	 * be traced to the work that consumed it.
	 */
	public static record ResolvedInput(InputKind kind, RearmSpecificationType specification,
			UUID component, UUID release, String version, ReleaseLifecycle lifecycle)
			implements Serializable {}
}
