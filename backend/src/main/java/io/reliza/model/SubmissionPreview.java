/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.List;

/**
 * What a submission act on a release would do, resolved without generating (task TEA-35): whether
 * it can run, the refusal and its text when it cannot, the version it would write, the head when
 * one exists, the settings and SBOM profile it would apply and the plan of parts.
 */
public record SubmissionPreview(boolean canSubmit, SubmissionRefusal refusal, String message, int nextVersion,
		SubmissionData head, SubmissionSettingsRef settings, SubmissionProfileRef sbomProfile,
		List<SubmissionPlanEntry> plan) {}
