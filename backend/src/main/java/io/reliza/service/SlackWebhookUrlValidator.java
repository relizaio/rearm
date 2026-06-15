/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

/**
 * Single source of truth for "what is an acceptable Slack webhook URL."
 * Both the save-time validator
 * ({@code NotificationChannelService.upsertChannel}) and the dispatch-
 * time validator ({@code SlackChannelDispatcher.dispatch}) call into
 * here, so a customer who passes a wrong URL gets the same answer at
 * create-time as at delivery-time.
 *
 * <p>Pulled out of the dispatcher class so the dispatcher stays a leaf
 * — channel CRUD shouldn't depend on the dispatch-side machinery just
 * to ask "is this URL well-formed."
 */
public final class SlackWebhookUrlValidator {

    private SlackWebhookUrlValidator() {}

    /**
     * Defense-in-depth: only POST to URLs that match the Slack incoming-
     * webhook host. Channel-create-time validation enforces this at
     * save, but encrypted secrets in the DB outlive the input
     * validation that put them there — a future tamper or legacy row
     * with a stale URL should fail loudly rather than silently
     * exfiltrate vulnerability-detail payloads to an arbitrary host.
     */
    public static final String SLACK_WEBHOOK_HOST_PREFIX = "https://hooks.slack.com/services/";

    /**
     * True iff the URL matches the Slack incoming-webhook host prefix.
     */
    public static boolean isValid(String url) {
        return url != null && url.startsWith(SLACK_WEBHOOK_HOST_PREFIX);
    }
}
