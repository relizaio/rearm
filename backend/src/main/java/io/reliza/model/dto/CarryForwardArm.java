/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

/**
 * Which ownership arm of a release a findings carry-forward is operating on.
 *
 * <p>An enum, not the free-form String this started as. The value reaches operator-facing log lines
 * that get grepped and aggregated, so a typo or a rename at one of the call sites would compile,
 * pass every test (the tests pass their own literals) and silently split the log population someone
 * is counting. {@code ai-agents/coding_principles.md} calls for enums over magic strings.
 */
public enum CarryForwardArm {
	RELEASE_DIRECT("release-direct"),
	SCE("sce"),
	DELIVERABLE("deliverable");

	private final String label;

	CarryForwardArm (String label) {
		this.label = label;
	}

	/** Stable operator-facing name, used in log lines. */
	public String label () {
		return label;
	}
}
