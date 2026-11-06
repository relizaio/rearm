/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/** The state a submission head held before V107 migrated it (FDAW-1 D6); null on every other head. */
public enum SubmissionLegacyState {
	ACTIVE, WITHDRAWN
}
