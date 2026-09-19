/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.common;

/** OAuth 2.0 error codes (RFC 6749 §5.2, RFC 8628 §3.5) and grant type identifiers the token endpoint answers with. */
public final class OAuthErrors {
	private OAuthErrors() {}

	public static final String INVALID_REQUEST = "invalid_request";
	public static final String INVALID_CLIENT = "invalid_client";
	public static final String INVALID_GRANT = "invalid_grant";
	public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
	public static final String AUTHORIZATION_PENDING = "authorization_pending";
	public static final String ACCESS_DENIED = "access_denied";
	public static final String EXPIRED_TOKEN = "expired_token";

	public static final String GRANT_CLIENT_CREDENTIALS = "client_credentials";
	public static final String GRANT_REFRESH_TOKEN = "refresh_token";
	public static final String GRANT_DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code";
	public static final String GRANT_JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";
}
