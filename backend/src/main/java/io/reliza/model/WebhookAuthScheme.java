/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Authentication scheme for outbound {@code IntegrationType.WEBHOOK}
 * notification deliveries. The customer picks the scheme at channel-create
 * time; the dispatcher honours it on every POST.
 *
 * <p>Phase 4 ships three schemes — anything PagerDuty / Opsgenie /
 * Splunk / a custom in-house receiver might want:
 * <ul>
 *   <li>{@link #NONE} — no auth. Public-ish receivers, IP-allowlisted
 *       endpoints, or behind-a-VPN deployments.</li>
 *   <li>{@link #BEARER} — {@code Authorization: Bearer &lt;token&gt;}.
 *       Common for SaaS receivers; the token is encrypted at rest.</li>
 *   <li>{@link #HMAC_SHA256} — server signs the payload with a shared
 *       secret; the receiver verifies. Standard webhook-signing pattern
 *       (GitHub, Stripe, etc.).</li>
 * </ul>
 */
public enum WebhookAuthScheme {
    NONE,
    BEARER,
    HMAC_SHA256;
}
