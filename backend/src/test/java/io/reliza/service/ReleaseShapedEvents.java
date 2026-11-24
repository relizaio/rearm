/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.PackageKind;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SubmissionActOutcome;
import io.reliza.model.SubmissionState;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.BomComponentChange;
import io.reliza.model.dto.notifications.ReleaseBomDiffPayload;
import io.reliza.model.dto.notifications.ReleaseCreatedPayload;
import io.reliza.model.dto.notifications.ReleaseLifecycleChangedPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.dto.notifications.ReleaseSubmissionPayload;
import io.reliza.model.dto.notifications.ReleaseTeaPublicationPayload;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;

/**
 * TPF-158 test support: the nine release-shaped event types (every payload that embeds a
 * {@link ReleaseRef}) and one outbox event per type around a ref with a chosen component kind.
 * Shared by the CEL-surface rows and the fan-out document-gate rows.
 */
public final class ReleaseShapedEvents {

    /** Every event type whose payload embeds a ReleaseRef, i.e. every type the document gate reads. */
    public static final List<NotificationEventType> TYPES = List.of(
            NotificationEventType.RELEASE_CREATED,
            NotificationEventType.RELEASE_LIFECYCLE_CHANGED,
            NotificationEventType.RELEASE_BOM_DIFF,
            NotificationEventType.RELEASE_TEA_PUBLISHED,
            NotificationEventType.RELEASE_TEA_HIDDEN,
            NotificationEventType.RELEASE_SUBMISSION_CREATED,
            NotificationEventType.RELEASE_SUBMISSION_WITHDRAWN,
            NotificationEventType.APPROVAL_REQUESTED,
            NotificationEventType.APPROVAL_RESOLVED);

    private ReleaseShapedEvents() {
    }

    /**
     * A ref with the given component kind; null builds it through the thirteen-argument
     * back-compat constructor, i.e. the shape of a payload written before the kind existed.
     */
    public static ReleaseRef ref(String componentKind) {
        if (null == componentKind) {
            return new ReleaseRef(UUID.randomUUID(), "v1.0", UUID.randomUUID(), "myapp",
                    ComponentType.COMPONENT, UUID.randomUUID(), "main", ReleaseLifecycle.ASSEMBLED,
                    null, null, null, null, null);
        }
        return new ReleaseRef(UUID.randomUUID(), "v1.0", UUID.randomUUID(), "myapp",
                ComponentType.COMPONENT, UUID.randomUUID(), "main", ReleaseLifecycle.ASSEMBLED,
                null, null, null, null, null, componentKind);
    }

    /** The type's payload around {@code ref}; APPROVAL_REQUESTED carries no target users. */
    public static Object payload(NotificationEventType type, ReleaseRef ref) {
        return switch (type) {
            case RELEASE_CREATED -> new ReleaseCreatedPayload(ref, false);
            case RELEASE_LIFECYCLE_CHANGED -> new ReleaseLifecycleChangedPayload(
                    ref, ReleaseLifecycle.DRAFT, ReleaseLifecycle.ASSEMBLED);
            case RELEASE_BOM_DIFF -> new ReleaseBomDiffPayload(ref,
                    List.of(new BomComponentChange("pkg:npm/added@1.0.0", "1.0.0")),
                    List.of(new BomComponentChange("pkg:npm/removed@0.9.0", "0.9.0")));
            case RELEASE_TEA_PUBLISHED -> new ReleaseTeaPublicationPayload(ref, UUID.randomUUID(),
                    TeaPublicationState.PUBLISHED, 1, TeaExposureState.PRIVATE,
                    "https://tea.example.com/v1.0.0/componentRelease/" + UUID.randomUUID(), 0);
            case RELEASE_TEA_HIDDEN -> new ReleaseTeaPublicationPayload(ref, UUID.randomUUID(),
                    TeaPublicationState.HIDDEN, null, null, null, 0);
            case RELEASE_SUBMISSION_CREATED -> new ReleaseSubmissionPayload(ref, UUID.randomUUID(),
                    PackageKind.FDA_PREMARKET, SubmissionState.ACTIVE, 1, SubmissionActOutcome.CREATED);
            case RELEASE_SUBMISSION_WITHDRAWN -> new ReleaseSubmissionPayload(ref, UUID.randomUUID(),
                    PackageKind.FDA_PREMARKET, SubmissionState.WITHDRAWN, 1, SubmissionActOutcome.WITHDRAWN);
            case APPROVAL_REQUESTED -> new ApprovalRequestedPayload(ref, UUID.randomUUID(), UUID.randomUUID(),
                    "Alice", "alice@example.com",
                    List.of(new ApprovalRequestEntryRef(UUID.randomUUID(), "QA sign-off")), List.of());
            case APPROVAL_RESOLVED -> new ApprovalResolvedPayload(ref, UUID.randomUUID(), "QA sign-off",
                    ApprovalResolvedPayload.Resolution.APPROVED, UUID.randomUUID(), "Bob", "bob@example.com",
                    List.of());
            default -> throw new IllegalArgumentException("not a release-shaped type: " + type);
        };
    }

    /** A PENDING outbox event of {@code type} on a fresh org, its payload around a ref of {@code componentKind}. */
    public static NotificationOutboxEvent event(NotificationEventType type, String componentKind) {
        return event(type, ref(componentKind));
    }

    @SuppressWarnings("unchecked")
    public static NotificationOutboxEvent event(NotificationEventType type, ReleaseRef ref) {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(UUID.randomUUID());
        event.setEventType(type);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setDedupKey("tpf158:" + type + ":" + ref.releaseUuid());
        event.setRecordData(Utils.OM.convertValue(payload(type, ref), Map.class));
        return event;
    }
}
