/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the host-suffix accept set for MS Teams Workflows webhook URLs.
 * Microsoft hands out two URL shapes for the same Workflows feature
 * (Logic-Apps-style and Power-Platform-environment-style); both must
 * validate. Anything else must be rejected.
 */
class TeamsWebhookUrlValidatorTest {

    @Test
    void acceptsLogicAppsStyleWorkflowsUrl() {
        assertTrue(TeamsWebhookUrlValidator.isValid(
                "https://prod-12.eastus.logic.azure.com:443/workflows/abc123/triggers/manual/paths/invoke"
                        + "?api-version=2016-10-01&sp=%2Ftriggers%2Fmanual%2Frun&sv=1.0&sig=xxx"));
    }

    @Test
    void acceptsPowerPlatformEnvironmentStyleWorkflowsUrl() {
        // The shape make.powerautomate.com hands out as of 2026.
        assertTrue(TeamsWebhookUrlValidator.isValid(
                "https://default1dfed4cd10244fc78a5f98b9b1e35c.96.environment.api.powerplatform.com:443"
                        + "/powerautomate/automations/direct/workflows/76c1adb6aa1b429c8d93f97bb535383c"
                        + "/triggers/manual/paths/invoke?api-version=1&sp=%2Ftriggers%2Fmanual%2Frun"
                        + "&sv=1.0&sig=QqGc16_gPf0Z69w5wV1t7y8nFIevRjwhEbHbebsTkOg"));
    }

    @Test
    void rejectsPlaintextHttp() {
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "http://prod-12.eastus.logic.azure.com/workflows/abc"));
    }

    @Test
    void rejectsNonWorkflowsHost() {
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "https://hooks.slack.com/services/T00/B00/abc"));
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "https://attacker.example.com/workflows/abc"));
    }

    @Test
    void rejectsBareHostnameNoScheme() {
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "logic.azure.com/workflows/abc"));
    }

    @Test
    void rejectsBlankOrNull() {
        assertFalse(TeamsWebhookUrlValidator.isValid(null));
        assertFalse(TeamsWebhookUrlValidator.isValid(""));
        assertFalse(TeamsWebhookUrlValidator.isValid("   "));
    }

    @Test
    void caseInsensitiveOnHost() {
        assertTrue(TeamsWebhookUrlValidator.isValid(
                "https://PROD-12.EastUS.Logic.Azure.Com/workflows/abc"));
    }

    @Test
    void rejectsUserinfoSmuggling() {
        // SSRF bypass: substring parse reads userinfo as host; real host is evil.com.
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "https://logic.azure.com:x@evil.com/workflows/abc"));
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "https://logic.azure.com@evil.com/workflows/abc"));
    }

    @Test
    void rejectsLookalikeSuffixHost() {
        // A bare-suffix endsWith check would wrongly accept these; the dot
        // boundary requires the accepted host to be the suffix or a true
        // subdomain of it.
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "https://evilpowerplatform.com/workflows/abc"));
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "https://notlogic.azure.com.evil.com/workflows/abc"));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        // Pasted trailing newline / surrounding spaces must still validate --
        // otherwise new URI() throws on the control char and false-rejects a
        // real Workflows URL (mirrors the Slack validator).
        assertTrue(TeamsWebhookUrlValidator.isValid(
                "https://prod-12.eastus.logic.azure.com/workflows/abc\n"));
        assertTrue(TeamsWebhookUrlValidator.isValid(
                "  https://prod-12.eastus.logic.azure.com/workflows/abc  "));
    }

    @Test
    void stillRejectsInternalWhitespace() {
        // Stripping is edges-only: a space inside the URL stays invalid.
        assertFalse(TeamsWebhookUrlValidator.isValid(
                "https://prod-12.eastus.logic.azure.com/work flows/abc"));
    }
}
