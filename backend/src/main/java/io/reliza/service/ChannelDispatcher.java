/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import io.reliza.model.Integration;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationOutboxEvent;

/**
 * Shared seam for notification channel dispatch. Each implementation
 * handles exactly one {@link IntegrationType} destination and POSTs (or
 * otherwise delivers) one notification event to one channel.
 *
 * <p>The CE-safe dispatchers (Slack, Teams, Webhook) live in shared
 * {@code service/} and ship to CE via the mirror; the Pro-only
 * dispatchers (Email, Sentinel) stay in {@code service/saas/} and are
 * simply absent on CE. {@link NotificationDeliveryWorker} injects the
 * full {@code List<ChannelDispatcher>} Spring discovers on the
 * classpath, indexes them by {@link #supportedType()}, and dispatches
 * via map lookup — so on CE a delivery targeting an absent type is
 * marked failed with a clear "no dispatcher for &lt;type&gt; on this
 * edition" message rather than failing to start the context.
 */
public interface ChannelDispatcher {

    /** The single channel destination type this dispatcher handles. */
    IntegrationType supportedType();

    /** Deliver one notification event to one channel. */
    ChannelDispatchResult dispatch(NotificationOutboxEvent event, Integration channel);
}
