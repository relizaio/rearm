/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto;

/**
 * Convergence state of an instance against its target release.
 *
 * Lives in the shared dto package rather than nested in the SAAS-only
 * UpdateProgressDto because the notification formatters that render it
 * (Slack, Teams, synthetic event templates) are shared code: a shared class
 * importing io.reliza.model.dto.saas.* does not compile in the CE tree, which
 * has no saas package. The enum itself carries no SAAS coupling -- it is a
 * plain state vocabulary -- so sharing it costs nothing.
 */
public enum UpdateStatus {
	REQUESTED, // target release changed, but no actual change yet
	IN_PROGRESS,
	CONVERGED, // actual matches target
	UNDEPLOYED,
	NEW,
	ERROR,
	RECOVERED, // from error
	NONE;
}
