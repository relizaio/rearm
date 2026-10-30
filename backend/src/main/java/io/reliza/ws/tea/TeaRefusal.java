/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.service.TeaAuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaAccess;

/**
 * A TEA refusal (task TEA-6): thrown by a TEA controller, rendered by {@link TeaErrorAdvice} as the
 * response it carries, so the implementing methods keep their generated return types. Every refusal
 * is no-store.
 */
public class TeaRefusal extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient ResponseEntity<TeaErrorResponse> response;

	private TeaRefusal(ResponseEntity<TeaErrorResponse> response) {
		super(String.valueOf(response.getStatusCode()), null, false, false);
		this.response = response;
	}

	public ResponseEntity<TeaErrorResponse> response() {
		return response;
	}

	/** The refusal of an access decision: 401 with a challenge, or 404 OBJECT_UNKNOWN. */
	public static TeaRefusal of(TeaAccess access) {
		ResponseEntity<TeaErrorResponse> r = TeaAuthorizationService.denial(access);
		if (null == r) throw new IllegalArgumentException("SERVE is not a refusal");
		return new TeaRefusal(r);
	}

	public static TeaRefusal objectUnknown() {
		return of(TeaAccess.CONCEAL);
	}

	/** 404 OBJECT_UNKNOWN with a message, for a document that does not exist rather than an object concealed. */
	public static TeaRefusal objectUnknown(String message) {
		return body(HttpStatus.NOT_FOUND, TeaUnknownErrorType.OBJECT_UNKNOWN, message);
	}

	/** 404 NOT_IMPLEMENTED: the server does not provide this optional capability or endpoint. */
	public static TeaRefusal notImplemented(String message) {
		return body(HttpStatus.NOT_FOUND, TeaUnknownErrorType.NOT_IMPLEMENTED, message);
	}

	public static TeaRefusal invalidRequest(String message) {
		return body(HttpStatus.BAD_REQUEST, TeaUnknownErrorType.INVALID_REQUEST, message);
	}

	/** 406 NO_ACCEPTABLE_FORMAT: the artifact revision has no format the request selects (task TEA-10). */
	public static TeaRefusal noAcceptableFormat(String message) {
		return body(HttpStatus.NOT_ACCEPTABLE, TeaUnknownErrorType.NO_ACCEPTABLE_FORMAT, message);
	}

	/** 404 SIGNATURE_NOT_FOUND: the revision and format exist, no signature was published for them (task TEA-10). */
	public static TeaRefusal signatureNotFound() {
		return body(HttpStatus.NOT_FOUND, TeaUnknownErrorType.SIGNATURE_NOT_FOUND, null);
	}

	/** No page token was ever issued, so none is valid. */
	public static TeaRefusal invalidPageToken() {
		return body(HttpStatus.BAD_REQUEST, TeaUnknownErrorType.INVALID_PAGE_TOKEN, null);
	}

	private static TeaRefusal body(HttpStatus status, TeaUnknownErrorType error, String message) {
		TeaErrorResponse b = new TeaErrorResponse(error);
		b.setMessage(message);
		// the preset content type skips negotiation, so no Accept of a download turns the TEA body into the dispatch's (TEA-10)
		return new TeaRefusal(ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
				.contentType(MediaType.APPLICATION_JSON).body(b));
	}
}
