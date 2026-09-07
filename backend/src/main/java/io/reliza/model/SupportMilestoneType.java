/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Which per-component support milestone a {@link SupportMilestone} row
 * asserts. Ordered by increasing severity along the support lifecycle:
 * {@code END_OF_GUARANTEED_SUPPORT} (bug fixes stop, security fixes
 * continue -- feeds {@link SupportStatus#SECURITY_ONLY}), {@code
 * END_OF_SUPPORT} (all support ceases), {@code END_OF_LIFE}. Scoped to
 * per-component support only (CLE 1.0.0 "third-party claims", future-scope
 * there); device-level CLE events are a separate axis.
 */
public enum SupportMilestoneType {
	END_OF_GUARANTEED_SUPPORT,
	END_OF_SUPPORT,
	END_OF_LIFE
}
