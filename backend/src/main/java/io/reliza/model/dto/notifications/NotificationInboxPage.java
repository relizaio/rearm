/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Paginated wrapper for the Phase 14 Inbox MVP listing — see
 * notifications-framework.md §8.1.
 *
 * <p>{@code unreadCount} rides alongside the page so the inbox-tab
 * badge can update from the same response that paints the list, without
 * a second {@code notificationUnreadCount} round-trip. {@code totalCount}
 * is everything visible to the caller; {@code unreadCount} is the subset
 * the caller has not yet marked read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationInboxPage(
        List<NotificationInboxItem> items,
        long totalCount,
        long unreadCount,
        int limit,
        int offset) {
}
