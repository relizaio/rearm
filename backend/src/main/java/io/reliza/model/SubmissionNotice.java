/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.UUID;

/**
 * What a submission act tells the release change hook after its commit (task TEA-35): identifiers
 * and enums only, no free text (decision E14), so no notification channel can render text a
 * component writer typed.
 */
public record SubmissionNotice(UUID submission, PackageKind kind, SubmissionState state, int version,
		SubmissionActOutcome act) {}
