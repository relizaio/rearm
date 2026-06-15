/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import io.reliza.common.Utils;
import io.reliza.model.dto.notifications.AffectedComponent;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.BomComponentChange;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.dto.notifications.ReleaseBomDiffPayload;
import io.reliza.model.dto.notifications.ReleaseCreatedPayload;
import io.reliza.model.dto.notifications.ReleaseLifecycleChangedPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the CEL activation map for notification-event-shaped policy
 * evaluation. Sibling to {@link ReleaseActivationMapBuilder} — neither
 * shares dependencies nor a parent interface, because release and event
 * activations have disjoint shapes and would only meet under forced
 * polymorphism (§6.2 of the design doc).
 *
 * <p>The output exposes a single top-level CEL variable, {@code event},
 * whose subobject shape depends on {@link NotificationEventType}.
 * Customer filters read it as e.g.
 *   {@code event.severity in ['CRITICAL','HIGH']}
 *   {@code event.affectedReleases.exists(r, r.lifecycle == 'SHIPPED')}
 *   {@code event.kevListed == true}
 *   {@code event.epssScore > 0.7}
 *
 * <p>The full CEL surface — every field name a customer can reference —
 * is the public API of this builder. Field additions are
 * forward-compatible; renames/removals require the dual-emit period
 * called out in §13.2 "Event-schema versioning discipline."
 */
@Service
@Slf4j
public class EventActivationMapBuilder {

    public Map<String, Object> buildForEvent(NotificationOutboxEvent event) {
        Map<String, Object> eventMap = new HashMap<>();

        // Common fields available on every event type.
        eventMap.put("type", event.getEventType() != null ? event.getEventType().name() : "");
        eventMap.put("org", event.getOrg() != null ? event.getOrg().toString() : "");
        eventMap.put("dedupKey", StringUtils.defaultString(event.getDedupKey()));
        eventMap.put("occurredAt", event.getOccurredAt() != null ? event.getOccurredAt().toString() : "");

        // Type-specific fields. Each branch deserializes the JSONB into the
        // typed payload record and then maps onto the CEL surface. We
        // surface every payload field — keeping the activation map a near-
        // mirror of the typed record makes the customer-facing CEL surface
        // easy to predict from looking at the payload class.
        if (event.getEventType() != null && event.getRecordData() != null) {
            switch (event.getEventType()) {
                case NEW_VULN_AFFECTS_RELEASES -> populateNewVulnAffectsReleases(eventMap, event);
                case VULNERABILITY_RECORD_UPDATED -> populateVulnerabilityRecordUpdated(eventMap, event);
                case VEX_STATE_CHANGED -> populateVexStateChanged(eventMap, event);
                case RELEASE_CREATED -> populateReleaseCreated(eventMap, event);
                case RELEASE_LIFECYCLE_CHANGED -> populateReleaseLifecycleChanged(eventMap, event);
                case RELEASE_BOM_DIFF -> populateReleaseBomDiff(eventMap, event);
                case APPROVAL_REQUESTED -> populateApprovalRequested(eventMap, event);
                case APPROVAL_RESOLVED -> populateApprovalResolved(eventMap, event);
            }
        }

        Map<String, Object> activation = new HashMap<>();
        activation.put("event", eventMap);
        return activation;
    }

    private void populateNewVulnAffectsReleases(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        NewVulnAffectsReleasesPayload p = deserialize(event, NewVulnAffectsReleasesPayload.class);
        if (p == null) return;

        eventMap.put("vulnPrimaryId", StringUtils.defaultString(p.vulnPrimaryId()));
        eventMap.put("aliases", p.aliases() != null ? p.aliases() : List.of());
        eventMap.put("cvssScore", p.cvssScore() != null ? p.cvssScore() : 0.0);
        eventMap.put("cvssVector", StringUtils.defaultString(p.cvssVector()));
        eventMap.put("epssScore", p.epssScore() != null ? p.epssScore() : 0.0);
        eventMap.put("kevListed", Boolean.TRUE.equals(p.kevListed()));
        eventMap.put("fixVersion", StringUtils.defaultString(p.fixVersion()));
        eventMap.put("severity", p.severity() != null ? p.severity().name() : "");

        AffectedComponent c = p.affectedComponent();
        if (c != null) {
            Map<String, Object> componentMap = new HashMap<>();
            componentMap.put("purl", StringUtils.defaultString(c.purl()));
            componentMap.put("name", StringUtils.defaultString(c.name()));
            componentMap.put("version", StringUtils.defaultString(c.version()));
            eventMap.put("affectedComponent", componentMap);
        }

        List<Map<String, Object>> releaseList = new ArrayList<>();
        if (p.affectedReleases() != null) {
            for (AffectedRelease r : p.affectedReleases()) {
                if (r == null) continue;
                Map<String, Object> rm = new HashMap<>();
                rm.put("uuid", r.uuid() != null ? r.uuid().toString() : "");
                rm.put("component", StringUtils.defaultString(r.component()));
                rm.put("version", StringUtils.defaultString(r.version()));
                rm.put("branch", StringUtils.defaultString(r.branch()));
                rm.put("lifecycle", r.lifecycle() != null ? r.lifecycle().name() : "");
                rm.put("deployedEnvs", r.deployedEnvs() != null ? r.deployedEnvs() : List.of());
                releaseList.add(rm);
            }
        }
        eventMap.put("affectedReleases", releaseList);
    }

    private void populateVulnerabilityRecordUpdated(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        VulnerabilityRecordUpdatedPayload p = deserialize(event, VulnerabilityRecordUpdatedPayload.class);
        if (p == null) return;

        eventMap.put("vulnPrimaryId", StringUtils.defaultString(p.vulnPrimaryId()));
        eventMap.put("changeType", p.changeType() != null ? p.changeType().name() : "");
        eventMap.put("oldSeverity", p.oldSeverity() != null ? p.oldSeverity().name() : "");
        eventMap.put("newSeverity", p.newSeverity() != null ? p.newSeverity().name() : "");
        // Convenience: report "severity" as the new severity so route-table
        // filters that always read event.severity work uniformly.
        eventMap.put("severity", p.newSeverity() != null ? p.newSeverity().name() : "");
        eventMap.put("oldEpssScore", p.oldEpssScore() != null ? p.oldEpssScore() : 0.0);
        eventMap.put("newEpssScore", p.newEpssScore() != null ? p.newEpssScore() : 0.0);
        eventMap.put("kevListedNow", Boolean.TRUE.equals(p.kevListedNow()));

        List<Map<String, Object>> releaseList = new ArrayList<>();
        if (p.affectedReleases() != null) {
            for (AffectedRelease r : p.affectedReleases()) {
                if (r == null) continue;
                Map<String, Object> rm = new HashMap<>();
                rm.put("uuid", r.uuid() != null ? r.uuid().toString() : "");
                rm.put("component", StringUtils.defaultString(r.component()));
                rm.put("version", StringUtils.defaultString(r.version()));
                rm.put("branch", StringUtils.defaultString(r.branch()));
                rm.put("lifecycle", r.lifecycle() != null ? r.lifecycle().name() : "");
                rm.put("deployedEnvs", r.deployedEnvs() != null ? r.deployedEnvs() : List.of());
                releaseList.add(rm);
            }
        }
        eventMap.put("affectedReleases", releaseList);
    }

    private void populateVexStateChanged(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        VexStateChangedPayload p = deserialize(event, VexStateChangedPayload.class);
        if (p == null) return;

        eventMap.put("vulnPrimaryId", StringUtils.defaultString(p.vulnPrimaryId()));
        eventMap.put("releaseUuid", p.releaseUuid() != null ? p.releaseUuid().toString() : "");
        eventMap.put("componentPurl", StringUtils.defaultString(p.componentPurl()));
        eventMap.put("oldState", StringUtils.defaultString(p.oldState()));
        eventMap.put("newState", StringUtils.defaultString(p.newState()));
    }

    private void populateReleaseCreated(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        ReleaseCreatedPayload p = deserialize(event, ReleaseCreatedPayload.class);
        if (p == null) return;
        eventMap.put("scheduled", p.scheduled());
        populateReleaseRef(eventMap, p.release());
    }

    private void populateReleaseLifecycleChanged(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        ReleaseLifecycleChangedPayload p = deserialize(event, ReleaseLifecycleChangedPayload.class);
        if (p == null) return;
        eventMap.put("oldLifecycle", p.oldLifecycle() != null ? p.oldLifecycle().name() : "");
        eventMap.put("newLifecycle", p.newLifecycle() != null ? p.newLifecycle().name() : "");
        populateReleaseRef(eventMap, p.release());
    }

    private void populateReleaseBomDiff(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        ReleaseBomDiffPayload p = deserialize(event, ReleaseBomDiffPayload.class);
        if (p == null) return;
        eventMap.put("addedCount", p.added() != null ? p.added().size() : 0);
        eventMap.put("removedCount", p.removed() != null ? p.removed().size() : 0);
        eventMap.put("added", bomPurls(p.added()));
        eventMap.put("removed", bomPurls(p.removed()));
        populateReleaseRef(eventMap, p.release());
    }

    private void populateApprovalRequested(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        ApprovalRequestedPayload p = deserialize(event, ApprovalRequestedPayload.class);
        if (p == null) return;
        eventMap.put("requestUuid", p.requestUuid() != null ? p.requestUuid().toString() : "");
        eventMap.put("requestedBy", p.requestedBy() != null ? p.requestedBy().toString() : "");
        eventMap.put("requestedByEmail", StringUtils.defaultString(p.requestedByEmail()));
        List<Map<String, Object>> entryList = new ArrayList<>();
        if (p.entries() != null) {
            for (ApprovalRequestEntryRef e : p.entries()) {
                if (e == null) continue;
                Map<String, Object> em = new HashMap<>();
                em.put("uuid", e.approvalEntryUuid() != null ? e.approvalEntryUuid().toString() : "");
                em.put("name", StringUtils.defaultString(e.approvalEntryName()));
                entryList.add(em);
            }
        }
        eventMap.put("approvalEntries", entryList);
        populateReleaseRef(eventMap, p.release());
    }

    private void populateApprovalResolved(Map<String, Object> eventMap, NotificationOutboxEvent event) {
        ApprovalResolvedPayload p = deserialize(event, ApprovalResolvedPayload.class);
        if (p == null) return;
        eventMap.put("approvalEntryUuid", p.approvalEntryUuid() != null ? p.approvalEntryUuid().toString() : "");
        eventMap.put("approvalEntryName", StringUtils.defaultString(p.approvalEntryName()));
        eventMap.put("resolution", p.resolution() != null ? p.resolution().name() : "");
        eventMap.put("resolvedBy", p.resolvedBy() != null ? p.resolvedBy().toString() : "");
        eventMap.put("resolvedByEmail", StringUtils.defaultString(p.resolvedByEmail()));
        populateReleaseRef(eventMap, p.release());
    }

    /**
     * Flattens the shared {@link ReleaseRef} onto the CEL surface. The
     * {@code lifecycle} key here is the release's current lifecycle; for
     * {@code RELEASE_LIFECYCLE_CHANGED} the transition is additionally
     * exposed as {@code oldLifecycle} / {@code newLifecycle}.
     */
    private void populateReleaseRef(Map<String, Object> eventMap, ReleaseRef r) {
        if (r == null) return;
        eventMap.put("releaseUuid", r.releaseUuid() != null ? r.releaseUuid().toString() : "");
        eventMap.put("version", StringUtils.defaultString(r.version()));
        eventMap.put("componentUuid", r.componentUuid() != null ? r.componentUuid().toString() : "");
        eventMap.put("componentName", StringUtils.defaultString(r.componentName()));
        eventMap.put("componentType", r.componentType() != null ? r.componentType().name() : "");
        eventMap.put("branchUuid", r.branchUuid() != null ? r.branchUuid().toString() : "");
        eventMap.put("branch", StringUtils.defaultString(r.branchName()));
        eventMap.put("lifecycle", r.lifecycle() != null ? r.lifecycle().name() : "");
        eventMap.put("commitHash", StringUtils.defaultString(r.commitHash()));
        eventMap.put("updatedByEmail", StringUtils.defaultString(r.updatedByEmail()));
    }

    private static List<String> bomPurls(List<BomComponentChange> changes) {
        if (changes == null) return List.of();
        return changes.stream()
                .filter(c -> c != null && c.purl() != null)
                .map(BomComponentChange::purl)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Tolerant payload deserialization. A mismatched payload shape — e.g.
     * an event written by an older producer with a schema_version we don't
     * recognise — is logged and yields null; the type-specific branch
     * then leaves the event-map empty for those fields rather than
     * throwing. CEL filters referencing the missing fields evaluate to
     * defaults (null/empty), which is the right semantics for "we don't
     * know enough about this event to match."
     */
    private <T> T deserialize(NotificationOutboxEvent event, Class<T> payloadClass) {
        try {
            return Utils.OM.convertValue(event.getRecordData(), payloadClass);
        } catch (Exception e) {
            log.warn("Failed to deserialize {} payload for event {}: {}",
                    payloadClass.getSimpleName(),
                    event.getUuid(),
                    e.getMessage());
            return null;
        }
    }
}
