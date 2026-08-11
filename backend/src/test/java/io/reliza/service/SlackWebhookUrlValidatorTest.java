/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the Slack webhook URL accept rule to a HOST lock (hooks.slack.com),
 * not a path prefix. Slack serves classic incoming webhooks (/services/),
 * Workflow Builder triggers (/triggers/), and other shapes from the same
 * host; all must validate. The earlier /services/-only prefix check rejected
 * legitimate Workflow / legacy URLs, which broke delivery to those channels
 * after the notifications-framework upgrade -- these tests guard against a
 * regression to path-based validation.
 */
class SlackWebhookUrlValidatorTest {

    @Test
    void acceptsClassicIncomingWebhook() {
        assertTrue(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXXXXXX"));
    }

    @Test
    void acceptsWorkflowBuilderTrigger() {
        // The /triggers/... shape Slack Workflow Builder hands out -- rejected
        // by the old /services/-only check, the core of this fix.
        assertTrue(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com/triggers/T00000000/1234567890/abcdef0123456789abcdef0123456789"));
    }

    @Test
    void acceptsWorkflowsPathAndTrailingPort() {
        assertTrue(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com/workflows/T0/A0/1/xyz"));
        assertTrue(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com:443/services/T0/B0/xyz"));
    }

    @Test
    void acceptsHostCaseInsensitively() {
        assertTrue(SlackWebhookUrlValidator.isValid(
                "https://Hooks.Slack.Com/services/T0/B0/xyz"));
    }

    @Test
    void rejectsPlaintextHttp() {
        assertFalse(SlackWebhookUrlValidator.isValid(
                "http://hooks.slack.com/services/T0/B0/xyz"));
    }

    @Test
    void rejectsNonSlackHost() {
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com.attacker.example.com/services/T0/B0/xyz"));
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://attacker.example.com/services/T0/B0/xyz"));
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://prod-12.eastus.logic.azure.com/workflows/abc"));
    }

    @Test
    void rejectsSubdomainOfSlack() {
        // Host must be exactly hooks.slack.com -- not any *.slack.com.
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://evil.hooks.slack.com/services/T0/B0/xyz"));
    }

    @Test
    void rejectsBareHostnameNoScheme() {
        assertFalse(SlackWebhookUrlValidator.isValid(
                "hooks.slack.com/services/T0/B0/xyz"));
    }

    @Test
    void rejectsUserinfoSmuggling() {
        // SSRF bypass: a substring parser reads the userinfo as the host and
        // would POST to evil.com. The real authority host is evil.com.
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com:x@evil.com/services/T0/B0/xyz"));
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com@evil.com/services/T0/B0/xyz"));
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://user:pass@evil.com/services/T0/B0/xyz"));
    }

    @Test
    void rejectsBlankOrNull() {
        assertFalse(SlackWebhookUrlValidator.isValid(null));
        assertFalse(SlackWebhookUrlValidator.isValid(""));
        assertFalse(SlackWebhookUrlValidator.isValid("   "));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        // A URL pasted with a trailing newline (or leading/trailing spaces/tabs)
        // must still validate: the stray control char otherwise makes new URI()
        // throw and false-rejects a real Slack channel. Regression guard for a
        // channel that delivered fine until a stricter parse shipped.
        assertTrue(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com/services/T0/B0/xyz\n"));
        assertTrue(SlackWebhookUrlValidator.isValid(
                "  https://hooks.slack.com/services/T0/B0/xyz  "));
        assertTrue(SlackWebhookUrlValidator.isValid(
                "\thttps://hooks.slack.com/services/T0/B0/xyz\r\n"));
    }

    @Test
    void stillRejectsInternalWhitespace() {
        // Stripping is edges-only: a space inside the URL stays invalid.
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com/serv ices/T0/B0/xyz"));
    }

    @Test
    void normalizeExpandsLegacyFragment() {
        // Legacy base Slack stored only the /services path fragment; normalize
        // expands it to a full URL that then validates + delivers.
        String full = SlackWebhookUrlValidator.normalize("T00000000/B00000000/abcXYZ");
        assertEquals("https://hooks.slack.com/services/T00000000/B00000000/abcXYZ", full);
        assertTrue(SlackWebhookUrlValidator.isValid(full));
    }

    @Test
    void normalizeDropsLeadingSlashOnFragment() {
        // A leading slash on the fragment must not double the prefix's slash.
        assertEquals("https://hooks.slack.com/services/T0/B0/xyz",
                SlackWebhookUrlValidator.normalize("/T0/B0/xyz"));
    }

    @Test
    void normalizeStripsWhitespaceOnFragment() {
        assertEquals("https://hooks.slack.com/services/T0/B0/xyz",
                SlackWebhookUrlValidator.normalize("  T0/B0/xyz\n"));
    }

    @Test
    void normalizeLeavesFullUrlUntouchedExceptWhitespace() {
        // A full URL (new-style channel) passes through unchanged, only stripped.
        assertEquals("https://hooks.slack.com/services/T0/B0/xyz",
                SlackWebhookUrlValidator.normalize("https://hooks.slack.com/services/T0/B0/xyz\n"));
        assertEquals("https://hooks.slack.com/triggers/T0/1/abc",
                SlackWebhookUrlValidator.normalize("https://hooks.slack.com/triggers/T0/1/abc"));
    }

    @Test
    void normalizeNullIsNull() {
        assertNull(SlackWebhookUrlValidator.normalize(null));
    }

    @Test
    void normalizeDoesNotFabricateUrlFromJunkFragment() {
        // A blank / slash-only / single-token secret is malformed: normalize
        // must NOT turn it into a valid-looking hooks.slack.com URL, or the
        // channel would POST to a dead endpoint on every event instead of
        // auto-disabling once. Returned unexpanded so isValid() rejects it.
        assertEquals("/", SlackWebhookUrlValidator.normalize("/"));
        assertEquals("//", SlackWebhookUrlValidator.normalize("//"));
        assertEquals("abc", SlackWebhookUrlValidator.normalize("abc"));
        assertFalse(SlackWebhookUrlValidator.isValid(SlackWebhookUrlValidator.normalize("/")));
        assertFalse(SlackWebhookUrlValidator.isValid(SlackWebhookUrlValidator.normalize("//")));
        assertFalse(SlackWebhookUrlValidator.isValid(SlackWebhookUrlValidator.normalize("abc")));
    }

    @Test
    void stripDoesNotDefeatHostGuards() {
        // Stripping the edges must not open a host-spoof/userinfo bypass: a
        // newline mid-authority still makes new URI() throw, and userinfo
        // revealed once the surrounding spaces are stripped is still rejected.
        assertFalse(SlackWebhookUrlValidator.isValid(
                "https://hooks.slack.com\n@evil.example.com/services/T0/B0/xyz"));
        assertFalse(SlackWebhookUrlValidator.isValid(
                "  https://user@hooks.slack.com/services/T0/B0/xyz  "));
    }
}
