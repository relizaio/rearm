/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * The auto-integrate dry run (ReleaseService.evaluateAutoIntegrate): what an on-demand trigger on a
 * feature set would do right now and why. Read-side DTOs only, nothing is persisted. The enum simple
 * names are the schema enum names (GraphQlSchemaEnumSyncTest resolves them by exact simple name).
 */
public class AutoIntegrateDtos {
	private AutoIntegrateDtos() {}

	public enum AutoIntegrateOutcome {
		WOULD_CREATE,
		AUTO_INTEGRATE_DISABLED,
		REQUIRED_DEPENDENCY_WITHOUT_RELEASE,
		MATCHING_PRODUCT_RELEASE_EXISTS,
		CREATE_FAILED
	}

	public enum AutoIntegrateReleaseSource {
		PINNED,
		LATEST_ASSEMBLED,
		NONE
	}

	/** A component or a branch: uuid and name (name null when the object is absent). */
	public static record AutoIntegrateRef(UUID uuid, String name) {}

	public static record AutoIntegrateReleaseRef(UUID uuid, String version, ReleaseLifecycle lifecycle,
			ZonedDateTime createdDate) {}

	public static record AutoIntegrateDependencyRow(AutoIntegrateRef component, AutoIntegrateRef branch,
			StatusEnum status, AutoIntegrateReleaseSource source, AutoIntegrateReleaseRef release, String note) {

		/** The one note of a row whose component the caller cannot READ. */
		public static final String NOT_READABLE_NOTE = "release not shown: you have no read permission on this component";

		/** Release gone, note the fixed sentence; component, branch, status and source kept. */
		public AutoIntegrateDependencyRow redacted() {
			return new AutoIntegrateDependencyRow(component, branch, status, source, null, NOT_READABLE_NOTE);
		}
	}

	/**
	 * lockRefusal is service-side only and not in the schema: the raw ActionRefusedException text when
	 * CREATE_FAILED is a lock (the old mutation rethrows it), else null. It always equals note when set.
	 */
	public static record AutoIntegrateEvaluation(AutoIntegrateOutcome outcome, AutoIntegrateReleaseRef existingRelease,
			String projectedVersion, List<AutoIntegrateDependencyRow> dependencies, String note,
			String lockRefusal) {

		/** projectedVersion is WOULD_CREATE-only, so it is dropped here. */
		public AutoIntegrateEvaluation withOutcome(AutoIntegrateOutcome newOutcome, String newNote) {
			return new AutoIntegrateEvaluation(newOutcome, existingRelease, null, dependencies, newNote, null);
		}

		public AutoIntegrateEvaluation withLockRefusal(String refusal) {
			return new AutoIntegrateEvaluation(AutoIntegrateOutcome.CREATE_FAILED, existingRelease, null,
					dependencies, refusal, refusal);
		}

		/** The fetcher's redacted copy; everything else as is. */
		public AutoIntegrateEvaluation withDependencies(List<AutoIntegrateDependencyRow> rows) {
			return new AutoIntegrateEvaluation(outcome, existingRelease, projectedVersion, rows, note, lockRefusal);
		}
	}

	/** Service-side result of the explained mutation: the created product release, if any, and why. */
	public static record AutoIntegrateResult(Optional<UUID> release, AutoIntegrateEvaluation evaluation) {}
}
