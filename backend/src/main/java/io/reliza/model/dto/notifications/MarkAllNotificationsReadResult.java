/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Result shape for {@code markAllNotificationsRead}.
 *
 * <p>The original v1 mutation returned a bare {@code Int} count, which
 * left the UI unable to distinguish "marked everything you had" from
 * "hit the per-call cap; there's more." The cap matters because the
 * backend deliberately bounds a single sweep at
 * {@code MARK_ALL_CAP = 500} to keep one click from issuing 10000+
 * inserts. Without {@code hasMore}, a user with 800 unread who clicks
 * Mark All would silently leave 300 unread.
 *
 * <p>{@code hasMore = true} means: after marking {@code count} rows
 * read, there were still unread+visible deliveries remaining for the
 * caller. The UI surface the layer-2 review suggested is "re-run to
 * clear the remaining N" in the toast.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkAllNotificationsReadResult(
        int count,
        boolean hasMore) {
}
