/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.reliza.model.SystemInfoData.SmtpProps;

/** The SMTP sender's display name (WL-2 design 4.3, test 10): the admin's setting wins over the branding's. */
class EmailServiceSenderNameTest {

	private static SmtpProps smtp(String fromName) {
		return new SmtpProps("user", "pass", "smtp.example.com", 587, true, false, fromName);
	}

	@Test
	void theAdminsFromNameWinsOverEveryBranding() {
		assertEquals("Ops Mailer", EmailService.senderName(smtp("Ops Mailer"), BrandingTestFixtures.DEFAULT));
		assertEquals("Ops Mailer", EmailService.senderName(smtp("Ops Mailer"), BrandingTestFixtures.EXAMPLE));
	}

	@Test
	void withoutOneTheBrandingsSenderNameApplies() {
		assertEquals("ReARM - Do Not Reply", EmailService.senderName(smtp(null), BrandingTestFixtures.DEFAULT));
		assertEquals("ReARM - Do Not Reply", EmailService.senderName(smtp(""), BrandingTestFixtures.DEFAULT));
		assertEquals("Example Release Hub - Do Not Reply", EmailService.senderName(smtp(null), BrandingTestFixtures.EXAMPLE));
	}
}
