/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.lang3.StringUtils;

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
 *
 * <p><b>Host lock, not path lock.</b> Slack serves several kinds of
 * webhook from the same host on different paths: classic incoming
 * webhooks ({@code /services/...}), Workflow Builder webhook triggers
 * ({@code /triggers/...}), and older/other shapes. They are all
 * legitimately Slack. This validator therefore locks to the
 * {@code hooks.slack.com} host and leaves the path unvalidated -- an
 * earlier {@code /services/}-only PREFIX check rejected valid Workflow
 * and legacy webhook URLs, which surfaced as every delivery to such a
 * channel failing non-retriably after the notifications-framework
 * upgrade. Mirrors {@link TeamsWebhookUrlValidator}'s host approach.
 *
 * <p>Defense-in-depth: only POST to the Slack host (an encrypted secret
 * in the DB outlives the input validation that stored it, and a
 * tampered or legacy row must not silently exfiltrate vulnerability-
 * detail payloads to an arbitrary host); refuses plaintext HTTP; refuses
 * a bare hostname with no scheme.
 */
public final class SlackWebhookUrlValidator {

    private SlackWebhookUrlValidator() {}

    /** The single host Slack serves incoming-webhook + Workflow URLs from. */
    public static final String SLACK_WEBHOOK_HOST = "hooks.slack.com";

    public static final String EXPECTED_PREFIX = "https://";

    /**
     * The host + path prefix legacy base Slack integrations were sent to: the
     * old sender held this as the WebClient baseUrl and stored only the path
     * fragment (e.g. {@code T000/B000/xxx}) in the secret. The notifications
     * dispatcher expects a full URL, so a bare fragment is expanded with this.
     */
    public static final String SERVICES_URL_PREFIX = "https://hooks.slack.com/services/";

    /**
     * Normalize a decrypted Slack webhook secret to a full URL. New channels
     * store a full {@code https://} URL (returned stripped, unchanged);
     * legacy base Slack integrations stored only the {@code /services} path
     * fragment (the old sender prepended the host) -- a value with no scheme
     * is treated as that fragment and expanded with {@link #SERVICES_URL_PREFIX}
     * so migrated base integrations keep delivering. A leading slash on the
     * fragment is dropped so the prefix's trailing slash isn't doubled.
     */
    public static String normalize(String secret) {
        if (secret == null) return null;
        String s = secret.strip();
        if (s.contains("://")) return s; // already a full URL (any scheme)
        // Expand a legacy /services token, which is always T.../B.../secret --
        // a multi-segment path. Only expand a plausible token: a blank,
        // slash-only, or single-segment value is malformed and is returned
        // UNEXPANDED so isValid() rejects it (-> channelMisconfigured
        // auto-disable) rather than fabricating a valid-looking
        // hooks.slack.com URL that would POST to a dead endpoint on every
        // event forever.
        String frag = s;
        while (frag.startsWith("/")) frag = frag.substring(1); // drop leading slashes (no doubled prefix slash)
        if (frag.isBlank() || !frag.contains("/")) return s;
        return SERVICES_URL_PREFIX + frag;
    }

    /**
     * True iff the URL is an HTTPS URL whose host is exactly
     * {@link #SLACK_WEBHOOK_HOST} (case-insensitive), regardless of path,
     * and carries NO userinfo. Parsed via {@link URI} so the authority is
     * resolved per RFC-3986 -- a hand-rolled substring parse is fooled by
     * a {@code user:pass@} form (e.g. {@code https://hooks.slack.com:x@evil.com/})
     * into reading the userinfo as the host, which would exfiltrate the
     * payload to an arbitrary destination. Userinfo is rejected outright:
     * a real Slack webhook never carries credentials in the authority.
     */
    public static boolean isValid(String url) {
        if (StringUtils.isBlank(url)) return false;
        // Tolerate a pasted trailing newline or surrounding spaces: a stray
        // control char makes new URI(...) throw and would false-reject an
        // otherwise-valid Slack webhook (a leading space also defeats the
        // https:// prefix check). Internal whitespace stays invalid.
        url = url.strip();
        if (!StringUtils.startsWithIgnoreCase(url, EXPECTED_PREFIX)) return false;
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getUserInfo() != null) return false; // no user:pass@ smuggling
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        return SLACK_WEBHOOK_HOST.equalsIgnoreCase(uri.getHost());
    }
}
