/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Page-wrapper for the Phase 2e {@code notificationDeliveries} listing
 * query. Carries the items plus a server-computed total so the UI can
 * render "showing 1-50 of 2,847" without a second roundtrip.
 *
 * <p>Offset+limit pagination (not cursor) matches the rest of the
 * rearm read-side. Total count is from {@code SELECT COUNT(*)} with
 * the same WHERE filters as the items query, so the two stay in sync
 * within a single repository call.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationDeliveriesPage(
        List<NotificationDeliveryResult> items,
        long totalCount,
        int limit,
        int offset) {
}
