/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Answers a multipart request over the upload limit ({@code spring.servlet.multipart.max-file-size} /
 * {@code max-request-size}) with a fixed 413 (task PFA-3).
 *
 * <p>The DispatcherServlet resolves a multipart request before it looks up a handler; the container refuses a body
 * or a part over the limit there and Spring raises {@link MaxUploadSizeExceededException}. The stock resolver answers
 * it with {@code sendError(413)}, which makes the container dispatch the request to /error: there the browser chain
 * answered 401 with a Bearer challenge in the task's reproduction (and a key client sent the whole upload again), and
 * in the test rig the dispatch fails on the same parse and the container writes its own HTML page. This handler
 * returns the 413 itself, so no error dispatch starts: no security chain, matcher or permit list is involved, and
 * every request refused by a filter on its REQUEST dispatch is refused exactly as before.
 *
 * <p>The advice has no selector on purpose: the exception is raised while there is no handler yet, and an advice
 * limited by type or package does not apply then. It handles this one exception type and nothing else.
 *
 * <p>The answer follows {@link FirewallRejectionHandler}: application/json, no-store and a constant body. It reads
 * nothing from the request and puts nothing from the exception (the limit, the size sent) into the answer or the log.
 */
@ControllerAdvice
public class UploadLimitExceededHandler {

	private static final Logger log = LoggerFactory.getLogger(UploadLimitExceededHandler.class);

	/** The fixed answer. Nothing of the request or the configured limit is echoed. */
	static final String BODY = "{\"message\":\"Request exceeds the upload limit\"}";

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<String> uploadLimitExceeded(MaxUploadSizeExceededException e) {
		log.debug("Multipart request refused: over the upload limit");
		return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
				.contentType(MediaType.APPLICATION_JSON)
				.cacheControl(CacheControl.noStore())
				.body(BODY);
	}
}
