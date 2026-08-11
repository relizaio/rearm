/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Inbox visibility scope for the bell inbox queries ({@code notificationInbox} /
 * {@code notificationUnreadCount}) and the {@code markAllNotificationsRead} sweep.
 *
 * <ul>
 *   <li>{@code PERSONAL} (default) -- the caller's own view: component-team +
 *       perspective + targeted rows only. An org admin is NOT auto-shown every
 *       org delivery, so the bell, its badge, and a mark-all sweep all reflect
 *       what is actually relevant to the caller.</li>
 *   <li>{@code ORG_ALL} -- the org-wide firehose: every delivery in the org.
 *       Honored ONLY for org admins; a non-admin request safely collapses to
 *       PERSONAL because the admin arm is AND-gated on the real isOrgAdmin flag.
 *       The org-wide audit view also remains in Delivery History.</li>
 * </ul>
 */
public enum NotificationInboxScope {
	PERSONAL,
	ORG_ALL;
}
