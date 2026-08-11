/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.lang3.StringUtils;

/**
 * Save-time + dispatch-time validation of MS Teams webhook URLs.
 *
 * <p>Modern Teams uses Power Automate Workflows webhooks (the legacy
 * O365 connector path is being removed by Microsoft). Two URL shapes
 * are seen in the wild for the same Workflows feature:
 * <ul>
 *   <li>Logic-Apps-style: {@code prod-XX.<region>.logic.azure.com:443/workflows/<guid>/...} —
 *       the older Workflows export format.</li>
 *   <li>Power-Platform-environment style:
 *       {@code default<env>.<region>.environment.api.powerplatform.com:443/powerautomate/automations/...} —
 *       the newer environment-scoped Workflows URL Microsoft hands out
 *       from {@code make.powerautomate.com}.</li>
 * </ul>
 * Either is a legitimate Workflows webhook; the validator accepts an
 * HTTPS URL whose host ends with one of the suffixes in
 * {@link #ACCEPTED_HOST_SUFFIXES}. The full path shape is deliberately
 * unvalidated — Microsoft has changed it before and this validator
 * shouldn't be what breaks on a Workflows URL revision.
 *
 * <p>Defense-in-depth: refuses plaintext HTTP. Refuses bare hostnames
 * (no protocol) — Slack/Webhook validators all do the same so a
 * tampered or legacy row never leaks vuln data over the wire.
 */
public final class TeamsWebhookUrlValidator {

    private TeamsWebhookUrlValidator() {}

    /**
     * Host suffixes accepted as a Workflows webhook destination. Order
     * is irrelevant — {@code isValid} treats the list as a set.
     */
    public static final java.util.List<String> ACCEPTED_HOST_SUFFIXES = java.util.List.of(
            "logic.azure.com",
            "powerplatform.com");

    public static final String EXPECTED_PREFIX = "https://";

    public static boolean isValid(String url) {
        if (StringUtils.isBlank(url)) return false;
        // Tolerate a pasted trailing newline or surrounding spaces (see
        // SlackWebhookUrlValidator): a stray control char makes new URI(...)
        // throw and would false-reject an otherwise-valid Workflows URL.
        url = url.strip();
        if (!StringUtils.startsWithIgnoreCase(url, EXPECTED_PREFIX)) return false;
        // Parse via URI so the authority is resolved per RFC-3986 -- a
        // hand-rolled substring parse is fooled by a user:pass@ form (e.g.
        // https://logic.azure.com:x@evil.com/) into reading the userinfo as
        // the host. Reject userinfo outright (a real Workflows URL has none).
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getUserInfo() != null) return false;
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase();
        for (String suffix : ACCEPTED_HOST_SUFFIXES) {
            // Dot boundary so an accepted suffix can't be spoofed by a
            // lookalike host (e.g. evilpowerplatform.com ending in the bare
            // suffix). Accept the suffix itself or any true subdomain of it.
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        return false;
    }
}
