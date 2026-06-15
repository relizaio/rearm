/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;

/**
 * Pins the channel-agnostic strings the four notification formatters
 * (Slack, Email, Teams, Inbox) all rely on. A change to a label here
 * is intentionally a one-test-update operation — if you have to touch
 * this file, also walk the per-formatter snapshot tests to make sure
 * the rendered subject/title/card surfaces still read sensibly.
 */
class NotificationLabelProviderTest {

    private final NotificationLabelProvider labels = new NotificationLabelProvider();

    @Test
    void humanizeChangeTypeCoversEveryEnumValue() {
        assertEquals("severity bumped",
                labels.humanizeChangeType(VulnerabilityRecordUpdatedPayload.ChangeType.SEVERITY_BUMPED));
        assertEquals("added to CISA KEV",
                labels.humanizeChangeType(VulnerabilityRecordUpdatedPayload.ChangeType.KEV_ADDED));
        assertEquals("EPSS spiked",
                labels.humanizeChangeType(VulnerabilityRecordUpdatedPayload.ChangeType.EPSS_SPIKED));
    }

    @Test
    void humanizeChangeTypeNullFallsBackToUpdated() {
        // The fallback exists for legacy payloads that pre-date the
        // changeType field; an inbox title or Slack subject would
        // otherwise render with an empty verb-phrase.
        assertEquals("updated", labels.humanizeChangeType(null));
    }

    @Test
    void componentNounDistinguishesProductFromComponent() {
        assertEquals("product", labels.componentNoun(ComponentType.PRODUCT));
        assertEquals("component", labels.componentNoun(ComponentType.COMPONENT));
        // Null (legacy / missing type) reads as the more common "component".
        assertEquals("component", labels.componentNoun(null));
    }

    @Test
    void branchNounDistinguishesFeatureSetFromBranch() {
        assertEquals("feature set", labels.branchNoun(ComponentType.PRODUCT));
        assertEquals("branch", labels.branchNoun(ComponentType.COMPONENT));
        assertEquals("branch", labels.branchNoun(null));
    }

    @Test
    void humanizeLifecycleVerbCoversEveryLifecycleValue() {
        // Every enum constant must map to a non-blank verb phrase — a new
        // ReleaseLifecycle value should force a deliberate label choice
        // here rather than silently rendering "updated".
        for (ReleaseLifecycle lc : ReleaseLifecycle.values()) {
            String verb = labels.humanizeLifecycleVerb(lc);
            assertNotNull(verb, "No verb for lifecycle " + lc);
        }
        assertEquals("assembled", labels.humanizeLifecycleVerb(ReleaseLifecycle.ASSEMBLED));
        assertEquals("cancelled", labels.humanizeLifecycleVerb(ReleaseLifecycle.CANCELLED));
        assertEquals("rejected", labels.humanizeLifecycleVerb(ReleaseLifecycle.REJECTED));
        assertEquals("moved to draft", labels.humanizeLifecycleVerb(ReleaseLifecycle.DRAFT));
    }

    @Test
    void humanizeLifecycleVerbNullFallsBackToUpdated() {
        assertEquals("updated", labels.humanizeLifecycleVerb(null));
    }

    @Test
    void humanizeResolutionCoversEveryResolutionValue() {
        assertEquals("approved",
                labels.humanizeResolution(ApprovalResolvedPayload.Resolution.APPROVED));
        assertEquals("disapproved",
                labels.humanizeResolution(ApprovalResolvedPayload.Resolution.DISAPPROVED));
    }

    @Test
    void humanizeResolutionNullFallsBackToResolved() {
        assertEquals("resolved", labels.humanizeResolution(null));
    }
}
