/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import org.springframework.http.HttpStatus;

import io.reliza.common.CommonVariables.AuthorizationStatus;
import io.reliza.model.WhoUpdated;
import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;

@Getter @Setter(AccessLevel.PRIVATE)
public class AuthorizationResponse {
	
	private AuthorizationResponse () {}
	
	private AuthorizationStatus authorizationStatus;
	private String message;
	private HttpStatus httpStatus;
	@Setter(AccessLevel.PUBLIC) private WhoUpdated whoUpdated;

	public static void forbid (AuthorizationResponse ar, String message) {
		forbid(ar, ForbidType.FORBIDDEN, message);
	}
	
	public static void forbid (AuthorizationResponse ar, ForbidType ft, String message) {
		ar.setAuthorizationStatus(AuthorizationStatus.FORBIDDEN);
		HttpStatus ht = HttpStatus.FORBIDDEN;
		switch (ft) {
			case FORBIDDEN:
				ht = HttpStatus.FORBIDDEN;
				break;
			case NOT_FOUND:
				ht = HttpStatus.NOT_FOUND;
				break;
			case EXPECTATION_FAILED:
				ht = HttpStatus.EXPECTATION_FAILED;
				break;
		}

		ar.setHttpStatus(ht);
		ar.setMessage(message);
	}

	public static void allow (AuthorizationResponse ar) {
		allow(ar, AllowType.OK);
	}
	
	public static void allow (AuthorizationResponse ar, AllowType allowType) {
		ar.setAuthorizationStatus(AuthorizationStatus.AUTHORIZED);
		HttpStatus ht = (allowType == AllowType.OK) ? HttpStatus.OK : HttpStatus.CREATED;
		ar.setHttpStatus(ht);
	}
	
	public static AuthorizationResponse initialize (InitType initType) {
		AuthorizationResponse ar = new AuthorizationResponse();
		if (initType == InitType.ALLOW) {
			allow(ar);
		} else {
			forbid(ar, "Forbidden");
		}
		return ar;
	}
	
	public static boolean isAllowed (AuthorizationResponse ar) {
		return ar.authorizationStatus == AuthorizationStatus.AUTHORIZED;
	}
	
	public enum AllowType {
		OK,
		CREATED
	}
	
	public enum ForbidType {
		FORBIDDEN,
		NOT_FOUND,
		EXPECTATION_FAILED
	}
	
	public enum InitType {
		ALLOW,
		FORBID
	}
}
