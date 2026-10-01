/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

public enum AnalysisState {
	EXPLOITABLE,
	IN_TRIAGE,
	FALSE_POSITIVE,
	NOT_AFFECTED,
	RESOLVED;

	/**
	 * Whether a finding in this state no longer affects the release: it is
	 * left out of the severity counts and the risk summary.
	 */
	public boolean isSuppressing() {
		return this == FALSE_POSITIVE || this == NOT_AFFECTED || this == RESOLVED;
	}
}
