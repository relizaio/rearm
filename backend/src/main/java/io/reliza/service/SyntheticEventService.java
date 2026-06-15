/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SyntheticEventTemplates.Template;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Injects synthetic events into the outbox so the rest of the pipeline
 * (fan-out, channel workers, history) can be exercised without waiting
 * for a real vuln to arrive. The §7.11 primitive of the design doc.
 *
 * <p>Synthetic events flow through the entire pipeline — outbox →
 * subscription evaluation → channel delivery — just like real events.
 * The difference is the {@code origin} stamped on the outbox row, which
 * the fan-out worker propagates onto every delivery. Downstream code
 * (history filters, analytics, rate-limits) can treat synthetic deliveries
 * separately by checking {@code delivery.origin == SYNTHETIC}.
 *
 * <p>Who calls this (see §7.11 of the design doc):
 * <ul>
 *   <li>{@code injectSyntheticEvent} GraphQL mutation — operator-only;
 *       any template, any org.
 *   <li>Channel "Test" button — customer-admin; single curated template,
 *       direct-to-channel narrow scope (later phase).
 *   <li>Quick Start verify step — customer-admin; matching-preset template
 *       through the just-created subscription (later phase).
 *   <li>Integration test harness — CI; same primitive against test orgs.
 * </ul>
 *
 * <p>The primitive here is the lowest-level "write to outbox with
 * origin=SYNTHETIC" call. The four call sites above wrap it with
 * appropriate scope restrictions.
 */
@Service
@Slf4j
public class SyntheticEventService {

    /**
     * Template used by {@link #injectChannelTest}. Single curated event
     * with broadly understandable shape (one shipped release, a CRITICAL
     * severity, a fake CVE id) so a customer reading the Slack post knows
     * "this is what a real alert would look like." The actual content
     * doesn't matter for the test outcome — only that the channel's
     * webhook accepts and renders a payload.
     */
    private static final Template CHANNEL_TEST_TEMPLATE = Template.CRITICAL_VULN_SINGLE_SHIPPED_RELEASE;

    @Autowired
    private NotificationOutboxEventRepository outboxRepo;

    @Autowired
    private GetOrganizationService getOrganizationService;

    @Autowired
    private IntegrationRepository integrationRepo;

    /**
     * Inject a curated synthetic event into the outbox for the given org.
     * Returns the persisted outbox event so the caller can show "synthetic
     * event injected" feedback that links to the resulting history rows.
     *
     * <p><b>TODO — audit trail.</b> Synthetic injection writes to a hot-path
     * table without an audit record of "who triggered this." The current
     * codebase audit pattern (Phase 1a {@code TableName.NOTIFICATION_SUBSCRIPTION /
     * NOTIFICATION_CHANNEL} entries) is driven by GraphQL mutation flows
     * that carry a {@code WhoUpdated}. When the {@code injectSyntheticEvent}
     * GraphQL mutation lands in a later phase, audit emission lands with
     * it. Service-layer callers (channel test, Quick Start, integration
     * tests) attribute via their own audit trails.
     */
    @Transactional
    public NotificationOutboxEvent inject(UUID org, Template template) throws RelizaException {
        if (org == null) throw new RelizaException("Cannot inject synthetic event with null org");
        if (template == null) throw new RelizaException("Cannot inject synthetic event with null template");
        // Existence check: prevents fuzz / mis-wired UUIDs from silently
        // writing rows that fan-out to "no subscriptions" and disappear.
        if (getOrganizationService.getOrganizationData(org).isEmpty()) {
            throw new RelizaException("Cannot inject synthetic event for unknown org " + org);
        }

        NotificationEventType eventType = SyntheticEventTemplates.eventTypeOf(template);
        Object payload = SyntheticEventTemplates.payloadOf(template);
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(payload, Map.class);

        ZonedDateTime now = ZonedDateTime.now();
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setOrg(org);
        event.setEventType(eventType);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOrigin(NotificationDeliveryOrigin.SYNTHETIC);
        event.setOccurredAt(now);
        event.setCreatedDate(now);
        event.setLastUpdatedDate(now);
        // Synthetic events get a fresh dedup_key per injection so a
        // user firing the same template twice in 24h doesn't dedup-
        // suppress the second one (belt-and-suspenders alongside the
        // fan-out side's origin=SYNTHETIC dedup-bypass).
        event.setDedupKey("synthetic:" + template.name() + ":" + UUID.randomUUID());
        event.setRecordData(recordData);

        NotificationOutboxEvent saved = outboxRepo.save(event);
        log.info("Injected synthetic event {} ({}) for org {} as outbox event {}",
                template, eventType, org, saved.getUuid());
        return saved;
    }

    /**
     * Channel-test variant of {@link #inject}: stamps the outbox row with
     * {@code channelTestTarget = channelUuid}, which the fan-out worker
     * reads as a directive to bypass subscription / CEL / severity-gate
     * evaluation and write exactly one delivery row pointing at this
     * channel. End-to-end pipeline gets exercised (outbox → fan-out →
     * channel dispatcher → real Slack webhook) without depending on the
     * customer having a matching subscription wired up — useful as the
     * "Test channel" button after channel CRUD.
     *
     * <p>Verifies channel exists and belongs to the requested org; rejects
     * mismatches so an org admin can't drive deliveries to a channel
     * outside their tenant.
     *
     * <p>Returns the persisted outbox event so the caller (GraphQL
     * mutation) can hand back the event uuid; the customer's UI then
     * polls deliveries by event for status. The actual webhook POST
     * happens on the next fan-out tick (5s) plus the channel-worker tick.
     */
    @Transactional
    public NotificationOutboxEvent injectChannelTest(UUID org, UUID channelUuid) throws RelizaException {
        if (org == null) throw new RelizaException("Cannot inject channel test with null org");
        if (channelUuid == null) throw new RelizaException("Cannot inject channel test with null channelUuid");
        if (getOrganizationService.getOrganizationData(org).isEmpty()) {
            throw new RelizaException("Cannot inject channel test for unknown org " + org);
        }

        Optional<Integration> oChannel = integrationRepo.findById(channelUuid);
        if (oChannel.isEmpty()) {
            throw new RelizaException("Cannot inject channel test for unknown channel " + channelUuid);
        }
        Integration channel = oChannel.get();
        // Enforce channel/org membership. Without this an org admin in
        // org A could fire a test against a channel uuid they happen to
        // know in org B and exfiltrate the channel's payload shape.
        IntegrationData channelData = parseChannelData(channel);
        if (channelData == null || channelData.getOrg() == null || !channelData.getOrg().equals(org)) {
            throw new RelizaException("Channel " + channelUuid + " does not belong to org " + org);
        }
        // Reject channel test on a DISABLED channel. The NotificationDeliveryWorker
        // short-circuits disabled channels back to PENDING and bumps attempt_count,
        // so a test against a disabled channel would silently fail. Surface this
        // up front so the operator sees an actionable error. isEnabled=false is
        // the disabled state; null is treated as enabled.
        if (Boolean.FALSE.equals(channelData.getIsEnabled())) {
            throw new RelizaException("Channel " + channelUuid + " is DISABLED; re-enable before testing");
        }

        NotificationEventType eventType = SyntheticEventTemplates.eventTypeOf(CHANNEL_TEST_TEMPLATE);
        Object payload = SyntheticEventTemplates.payloadOf(CHANNEL_TEST_TEMPLATE);
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(payload, Map.class);

        ZonedDateTime now = ZonedDateTime.now();
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setOrg(org);
        event.setEventType(eventType);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOrigin(NotificationDeliveryOrigin.SYNTHETIC);
        event.setChannelTestTarget(channelUuid);
        event.setOccurredAt(now);
        event.setCreatedDate(now);
        event.setLastUpdatedDate(now);
        // Per-call fresh dedup_key so repeated test-button presses each
        // produce a delivery — operators rely on the test producing
        // visible feedback every time.
        event.setDedupKey("channel-test:" + channelUuid + ":" + UUID.randomUUID());
        event.setRecordData(recordData);

        NotificationOutboxEvent saved = outboxRepo.save(event);
        log.info("Injected channel-test event for org {} channel {} as outbox event {}",
                org, channelUuid, saved.getUuid());
        return saved;
    }

    private IntegrationData parseChannelData(Integration channel) {
        if (channel.getRecordData() == null) return null;
        try {
            return IntegrationData.dataFromRecord(channel);
        } catch (RuntimeException e) {
            log.warn("Failed to parse channel {} record_data while injecting channel test: {}",
                    channel.getUuid(), e.getMessage());
            return null;
        }
    }
}
