/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import org.apache.commons.lang3.StringUtils;

/**
 * Save-time + dispatch-time validation of Azure Sentinel Data
 * Collection Endpoint URLs.
 *
 * <p>The canonical DCE host pattern is
 * {@code https://<name>.<region>.ingest.monitor.azure.com}, so this
 * validator accepts any HTTPS URL ending in {@code azure.com}. We
 * deliberately don't pin a stricter pattern — Microsoft has changed
 * Azure subdomain layouts before, and this validator shouldn't be
 * the thing that breaks on a region rename.
 *
 * <p>Defense-in-depth: refuses plaintext HTTP, refuses bare hostnames
 * (no protocol). Mirrors the Slack/Teams/Webhook validator pattern.
 */
public final class SentinelEndpointValidator {

    private SentinelEndpointValidator() {}

    public static final String AZURE_HOST_SUFFIX = "azure.com";
    public static final String EXPECTED_PREFIX = "https://";

    public static boolean isValid(String url) {
        if (StringUtils.isBlank(url)) return false;
        if (!url.startsWith(EXPECTED_PREFIX)) return false;
        String afterScheme = url.substring(EXPECTED_PREFIX.length());
        int firstSlash = afterScheme.indexOf('/');
        String hostPort = firstSlash > 0 ? afterScheme.substring(0, firstSlash) : afterScheme;
        int colon = hostPort.indexOf(':');
        String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        return host.toLowerCase().endsWith(AZURE_HOST_SUFFIX);
    }
}
