/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

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
        if (!url.startsWith(EXPECTED_PREFIX)) return false;
        // Parse defensively — strip any port + path before checking host suffix.
        String afterScheme = url.substring(EXPECTED_PREFIX.length());
        int firstSlash = afterScheme.indexOf('/');
        String hostPort = firstSlash > 0 ? afterScheme.substring(0, firstSlash) : afterScheme;
        int colon = hostPort.indexOf(':');
        String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        String lc = host.toLowerCase();
        for (String suffix : ACCEPTED_HOST_SUFFIXES) {
            if (lc.endsWith(suffix)) return true;
        }
        return false;
    }
}
