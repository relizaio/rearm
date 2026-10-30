/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.PackageKind;
import io.reliza.model.SubmissionActOutcome;
import io.reliza.model.SubmissionState;

/**
 * Typed accessor for the JSONB payload on {@link NotificationOutboxEvent} when {@code eventType}
 * is {@code RELEASE_SUBMISSION_CREATED} or {@code RELEASE_SUBMISSION_WITHDRAWN} (task TEA-35).
 * Decision E14: the shared release ref, then identifiers and enums, and no free text of its own,
 * because the chat formatters escape nothing; a consumer reads the version record through GraphQL
 * with the uuids named here.
 *
 * @param version the version written, or the one a withdrawal tombstones
 * @param act CREATED, RESUBMITTED or WITHDRAWN
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseSubmissionPayload(
        ReleaseRef release,
        UUID submission,
        PackageKind kind,
        SubmissionState state,
        int version,
        SubmissionActOutcome act) {

    /**
     * The one plain rendering every channel uses: "Release c 1.0: FDA_PREMARKET submission v2
     * resubmitted".
     */
    @JsonIgnore
    public String headline() {
        String name = null == release ? "release" : (release.componentName() + " " + release.version());
        String what = SubmissionActOutcome.WITHDRAWN == act ? "withdrawn"
                : SubmissionActOutcome.RESUBMITTED == act ? "resubmitted" : "created";
        return "Release " + name + ": " + kind + " submission v" + version + " " + what;
    }
}
