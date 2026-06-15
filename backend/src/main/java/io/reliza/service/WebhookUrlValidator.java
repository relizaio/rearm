/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import org.apache.commons.lang3.StringUtils;

/**
 * Single source of truth for "what is an acceptable generic-webhook
 * URL." Both the save-time validator
 * ({@code NotificationChannelService.upsertChannel}) and the dispatch-
 * time validator ({@code WebhookChannelDispatcher.dispatch}) call into
 * here, so a customer who passes a wrong URL gets the same answer at
 * create-time as at delivery-time.
 *
 * <p>Mirrors {@link SlackWebhookUrlValidator}'s placement: the
 * dispatcher class is a leaf, not the right owner of input-validation
 * helpers.
 */
public final class WebhookUrlValidator {

    private WebhookUrlValidator() {}

    /** HTTPS scheme prefix — case-insensitive per RFC 3986 §3.1. */
    public static final String HTTPS_PREFIX = "https://";

    /**
     * True iff the URL has an https scheme. Trims leading/trailing
     * whitespace from sloppy-paste input and matches scheme
     * case-insensitively (RFC 3986 §3.1 says schemes are
     * case-insensitive; a real-world client emitting {@code HTTPS://}
     * should not be rejected).
     */
    public static boolean isHttps(String url) {
        if (StringUtils.isBlank(url)) return false;
        String trimmed = url.trim();
        if (trimmed.length() < HTTPS_PREFIX.length()) return false;
        return trimmed.regionMatches(true /* ignoreCase */, 0,
                HTTPS_PREFIX, 0, HTTPS_PREFIX.length());
    }
}
