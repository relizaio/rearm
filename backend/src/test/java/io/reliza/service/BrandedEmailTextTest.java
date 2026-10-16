/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.BrandingPreset;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.BrandedEmailText.EmailText;

/**
 * Account and organization emails (WL-2 design 4.3, test 12). With the default branding each is
 * the exact text ReARM sent before branding existed (copied from the send sites it replaced); with
 * a preset, its title and support address replace ReARM's.
 */
class BrandedEmailTextTest {

	private static final String BASE = "https://rearm.example.com";
	private static final String KEY = "k%2Fey";
	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000011");
	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000022");

	private static final BrandingPreset DEFAULT = BrandingTestFixtures.DEFAULT.values();
	private static final BrandingPreset EXAMPLE = BrandingTestFixtures.EXAMPLE.values();

	private static List<EmailText> all(BrandingPreset b) {
		return List.of(
				BrandedEmailText.emailChangeNotice(b),
				BrandedEmailText.verifyEmail(b, BASE, KEY),
				BrandedEmailText.invite(b, BASE, "Acme", KEY),
				BrandedEmailText.adminInviteNotice(b, "Acme", ADMIN, PermissionType.READ_WRITE),
				BrandedEmailText.userJoinedNotice(b, "Acme", BASE, USER, PermissionType.NONE),
				BrandedEmailText.testEmail(b));
	}

	@Test
	void emailChangeNoticeOnDefaultIsUnchanged() {
		EmailText t = BrandedEmailText.emailChangeNotice(DEFAULT);
		assertEquals("This email address was requested to be replaced with a new one on ReARM", t.subject());
		assertEquals("Our records indicate that you requested to replace this email with a new one in ReARM."
				+ "If you didn't do it, please contact us at info@reliza.io as soon as possible.", t.body());
	}

	@Test
	void verifyEmailOnDefaultIsUnchanged() {
		EmailText t = BrandedEmailText.verifyEmail(DEFAULT, BASE, KEY);
		assertEquals("Verify your email on ReARM", t.subject());
		assertEquals("Please click <a clicktracking=off href=\"https://rearm.example.com/verifyEmail/k%2Fey\">this link</a>"
				+ " to verify your email on ReARM. Note that the link will be valid only within the next 48 hours.", t.body());
	}

	@Test
	void inviteOnDefaultIsUnchanged() {
		EmailText t = BrandedEmailText.invite(DEFAULT, BASE, "Acme", KEY);
		assertEquals("You are invited to join organization Acme on ReARM", t.subject());
		assertEquals("Please click <a clicktracking=off href=\"https://rearm.example.com/joinOrganization/k%2Fey\">the link </a>"
				+ " to join organization. The link is valid for 48 hours. Note that by clicking the link you are also"
				+ " accepting ReARM cookie and privacy policies.", t.body());
	}

	@Test
	void adminInviteNoticeOnDefaultIsUnchanged() {
		EmailText t = BrandedEmailText.adminInviteNotice(DEFAULT, "Acme", ADMIN, PermissionType.READ_WRITE);
		assertEquals("New user invited to join the organizaiton Acme on ReARM", t.subject());
		assertEquals("Admin user with uuid = 00000000-0000-0000-0000-000000000022 invited a new user to join the"
				+ " organization Acme on ReARM with permission: READ_WRITE. Check pending invitations for details.", t.body());
	}

	@Test
	void userJoinedNoticeOnDefaultIsUnchanged() {
		EmailText withPermission = BrandedEmailText.userJoinedNotice(DEFAULT, "Acme", BASE, USER, PermissionType.READ_ONLY);
		assertEquals("New user joined the organizaiton Acme on ReARM at https://rearm.example.com", withPermission.subject());
		assertEquals("A new user with the uuid 00000000-0000-0000-0000-000000000011 joined the organization Acme"
				+ " on ReARM with permission: READ_ONLY.", withPermission.body());

		EmailText withoutPermission = BrandedEmailText.userJoinedNotice(DEFAULT, "Acme", BASE, USER, PermissionType.NONE);
		assertEquals("A new user with the uuid 00000000-0000-0000-0000-000000000011 joined the organization Acme"
				+ " on ReARM with permission: NONE.\n\nPlease open Organization Settings view in ReARM and assign"
				+ " permissions for this user.", withoutPermission.body());
	}

	@Test
	void testEmailOnDefaultIsUnchanged() {
		EmailText t = BrandedEmailText.testEmail(DEFAULT);
		assertEquals("Test Email from ReARM", t.subject());
		assertEquals("This is a test email sent from ReARM to verify your email configuration. If you received this"
				+ " email, your email settings are working correctly.", t.body());
	}

	@Test
	void withAPresetEveryEmailCarriesItsTitleAndNoReARM() {
		for (EmailText t : all(EXAMPLE)) {
			String both = t.subject() + "\n" + t.body();
			assertTrue(t.subject().contains("Example Release Hub"), t.subject());
			assertTrue(t.body().contains("Example Release Hub"), t.body());
			assertFalse(both.contains("ReARM"), both);
			assertFalse(both.contains("info@reliza.io"), both);
		}
	}

	@Test
	void withAPresetTheSupportAddressIsThePresets() {
		assertTrue(BrandedEmailText.emailChangeNotice(EXAMPLE).body().contains("contact us at support@example.com"));
	}
}
