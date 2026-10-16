/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

import io.reliza.model.BrandingPreset;
import io.reliza.model.UserPermission.PermissionType;

/**
 * Subjects and bodies of the account and organization emails, with the deployment's branding in
 * place of the product name and the support address.
 *
 * <p>Built here rather than inline at each send site so they can be checked without sending mail.
 * With {@link BrandingPreset#DEFAULT} every string is exactly what ReARM sent before branding
 * existed, including the long-standing "organizaiton" typo in two subjects: changing a default
 * subject is a separate decision from branding it.
 */
public final class BrandedEmailText {

	private BrandedEmailText() {}

	public record EmailText(String subject, String body) {}

	/** Sent to the old address when a user asks to replace it. */
	public static EmailText emailChangeNotice(BrandingPreset b) {
		return new EmailText(
				"This email address was requested to be replaced with a new one on " + b.titleText(),
				"Our records indicate that you requested to replace this email with a new one in " + b.titleText() + "."
						+ "If you didn't do it, please contact us at " + b.supportEmail() + " as soon as possible.");
	}

	public static EmailText verifyEmail(BrandingPreset b, String baseUri, String urlSafeKey) {
		return new EmailText(
				"Verify your email on " + b.titleText(),
				"Please click <a clicktracking=off href=\"" + baseUri + "/verifyEmail/" + urlSafeKey
						+ "\">this link</a> to verify your email on " + b.titleText() + ". "
						+ "Note that the link will be valid only within the next 48 hours.");
	}

	public static EmailText invite(BrandingPreset b, String baseUri, String orgName, String urlSafeKey) {
		return new EmailText(
				"You are invited to join organization " + orgName + " on " + b.titleText(),
				"Please click <a clicktracking=off href=\"" + baseUri + "/joinOrganization/" + urlSafeKey
						+ "\">the link </a> to join organization. The link is valid for 48 hours. Note that by clicking"
						+ " the link you are also accepting " + b.titleText() + " cookie and privacy policies.");
	}

	/** Sent to the organization's admins when one of them invites a user. */
	public static EmailText adminInviteNotice(BrandingPreset b, String orgName, UUID invitedBy, PermissionType type) {
		return new EmailText(
				"New user invited to join the organizaiton " + orgName + " on " + b.titleText(),
				"Admin user with uuid = " + invitedBy + " invited a new user to join the organization " + orgName
						+ " on " + b.titleText() + " with permission: " + type.toString()
						+ ". Check pending invitations for details.");
	}

	/** Sent to the organization's admins when a user joins it. */
	public static EmailText userJoinedNotice(BrandingPreset b, String orgName, String baseUri, UUID userUuid,
			PermissionType type) {
		String body = "A new user with the uuid " + userUuid + " joined the organization " + orgName
				+ " on " + b.titleText() + " with permission: " + type.toString() + ".";
		if (PermissionType.NONE == type) {
			body += "\n\nPlease open Organization Settings view in " + b.titleText()
					+ " and assign permissions for this user.";
		}
		return new EmailText(
				"New user joined the organizaiton " + orgName + " on " + b.titleText() + " at " + baseUri,
				body);
	}

	/** The System Settings "send test email" message. */
	public static EmailText testEmail(BrandingPreset b) {
		return new EmailText(
				"Test Email from " + b.titleText(),
				"This is a test email sent from " + b.titleText() + " to verify your email configuration. "
						+ "If you received this email, your email settings are working correctly.");
	}
}
