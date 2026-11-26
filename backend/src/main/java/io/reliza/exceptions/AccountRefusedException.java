/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import java.io.Serializable;
import java.util.Objects;

/**
 * A person's account is refused: at sign-in, or on a request it makes. Unchecked, so it passes every
 * {@code catch (RelizaException)} on its way to {@code GraphQLExceptionHandlers}, which answers
 * BAD_REQUEST with the message as is and {@code extensions = { code, reason }}. The message is the
 * end-user text.
 */
public class AccountRefusedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** The code of a refused account; the UI switches on it with the reason. */
	public static final String LICENSE_SEAT_REFUSED = "LICENSE_SEAT_REFUSED";

	public static final String NO_ROLE_TEXT = "You signed in, but no ReARM role is assigned to your account."
			+ " Ask your ReARM administrator to add you to a ReARM user group or assign you the application role"
			+ " in your identity provider. Reference: NO-ROLE.";

	/**
	 * Why an account was refused: an enum constant, whose name is the {@code reason} on the wire. The
	 * shared reasons are {@link AccountRefusalKind}; an edition's seat gate adds its own enum.
	 */
	public interface RefusalReason extends Serializable {
		String name();
	}

	/** The reasons shared code refuses an account for. */
	public enum AccountRefusalKind implements RefusalReason {
		/** A first SSO sign-in that matched no role, in an organization that refuses accounts without a role. */
		NO_ROLE
	}

	private final RefusalReason reason;

	protected AccountRefusedException(RefusalReason reason, String message) {
		super(message);
		this.reason = Objects.requireNonNull(reason, "reason");
	}

	/** A first SSO sign-in that matched no role, in an organization that refuses accounts without a role. */
	public static AccountRefusedException noRole() {
		return new AccountRefusedException(AccountRefusalKind.NO_ROLE, NO_ROLE_TEXT);
	}

	/** What clients switch on with the reason, never the text: one code for every refused account. */
	public String getErrorCode() {
		return LICENSE_SEAT_REFUSED;
	}

	public RefusalReason getReason() {
		return reason;
	}
}
