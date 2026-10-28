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
	/** The edition has no submissions (CE): the shared fetcher's answer when no hook is present. */
	EDITION_UNSUPPORTED,
	/** The kind is not switched on in the component's effective package settings. */
	KIND_DISABLED
}
