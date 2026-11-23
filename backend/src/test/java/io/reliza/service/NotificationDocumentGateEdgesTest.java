/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;

/**
 * TPF-158 mutation round: what the document-gate rows leave open. The gate compares the carried kind with the exact name
 * of BOARD_DOCUMENT, so a near miss in case or length is not a document, and the sample event of every release-shaped
 * type that a subscription's dry run is tried against carries the kind of an ordinary software component.
 */
class NotificationDocumentGateEdgesTest {

    @Test
    void aKindThatIsNotTheExactNameIsNotADocument() {
        assertTrue(NotificationFanOutService.isBoardDocumentRelease(
                ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, "BOARD_DOCUMENT")));
        for (String near : new String[] {"board_document", "BOARD_DOCUMENTS", "BOARD_DOCUMEN", "BOARD", " BOARD_DOCUMENT", ""}) {
            assertFalse(NotificationFanOutService.isBoardDocumentRelease(
                    ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, near)), "'" + near + "'");
        }
    }

    @Test
    void theSampleOfEveryReleaseShapedTypeIsAnOrdinaryGenericComponent() {
        for (NotificationEventType type : ReleaseShapedEvents.TYPES) {
            NotificationOutboxEvent sample = SyntheticEventTemplates.sampleEventForType(UUID.randomUUID(), type);
            Object release = sample.getRecordData().get("release");
            assertTrue(release instanceof Map<?, ?>, type + ": the sample names a release");
            assertEquals("GENERIC", ((Map<?, ?>) release).get("componentKind"), type.name());
            assertFalse(NotificationFanOutService.isBoardDocumentRelease(sample), type.name());
        }
    }
}
