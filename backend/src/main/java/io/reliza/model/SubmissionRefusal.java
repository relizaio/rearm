/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/** Why a submission act was refused (tasks TEA-29, TEA-35). */
public enum SubmissionRefusal {
	RELEASE_NOT_FOUND, RELEASE_ARCHIVED, COMPONENT_ARCHIVED, LIFECYCLE_TOO_LOW, ALREADY_SUBMITTED,
	NOT_SUBMITTED, STALE_HEAD, REASON_REQUIRED, SUBMISSION_IN_PROGRESS, SETTINGS_CONFLICT, SETTINGS_INVALID,
	PROFILE_CONFLICT, PROFILE_NOT_FOUND, PART_GENERATOR_MISSING, PART_MISSING, PART_FAILED, GENERATION_FAILED,
	/** The text is longer than 2048 characters or holds a control character other than TAB, LF and CR. */
	REASON_INVALID,
	/** The acting user is not a current member of the release's organization (FDAW-8, FDAW-1 F-26). */
	ACTOR_NOT_MEMBER,
	/** The edition has no submissions (CE): the shared fetcher's answer when no hook is present. */
	EDITION_UNSUPPORTED,
	/** The kind is not switched on in the component's effective package settings. */
	KIND_DISABLED,
	/**
	 * A REFERENCED artifact left the release's inventory or was archived between composition and
	 * commit; repeat the act (task FDAW-5).
	 */
	INVENTORY_CHANGED,
	/** The release already holds a draft of this kind (FDAW-1 A3). */
	DRAFT_EXISTS,
	/** No such draft, or one of another organization: the caller cannot tell which. */
	DRAFT_NOT_FOUND,
	/** The act needs an EDITING draft and this one awaits confirmation. */
	DRAFT_AWAITING_CONFIRMATION,
	/** A rebuild of the draft is requested, or running within its lease. */
	BUILD_IN_PROGRESS,
	/** The draft has no build yet. */
	NO_BUILD,
	/** The inputs moved since the draft's build. */
	REBUILD_FIRST,
	/** The act needs a confirmation it does not have, or the organization does not use one. */
	CONFIRMATION_REQUIRED,
	/** The second confirmer is the person who confirmed. */
	SAME_CONFIRMER,
	/** Only a FINAL version can be cancelled. */
	NOT_FINAL,
	/** The replaces target is missing, foreign, not permitted or of another component: one answer for all four. */
	REPLACES_NOT_FOUND,
	/** The replaces target is neither FINAL nor SENT. */
	REPLACES_NOT_REPLACEABLE,
	/** Support metadata export is off for the organization or the resolved profile. */
	SUPPORT_DISCLOSURE_OFF,
	/** The merged SBOM carries no support disclosure marker. */
	SUPPORT_INJECTION_FAILED,
	/** A stored part does not match its record. */
	PART_MISMATCH,
	/** The pinned release SBOM does not match its record. */
	PIN_MISMATCH,
	/** The organization's rebuild cap is reached. */
	REBUILD_LIMIT
}
