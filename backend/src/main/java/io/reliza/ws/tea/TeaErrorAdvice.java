/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.reliza.common.LogSafe;
import io.reliza.model.tea.TeaErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders the refusals of the TEA 1.0.0 controllers (task TEA-6): a thrown {@link TeaRefusal} as
 * the response it carries, and a request Spring could not bind or validate as 400 INVALID_REQUEST
 * naming the parameter. Everything else is left to Spring, whose error dispatch on the TEA chain
 * {@link TeaErrorDispatchFilter} answers with a non-TEA body. The token endpoint is not covered: its
 * RFC 6749 bodies are its own.
 *
 * <p>Task TEA-21: a query whose parameters Tomcat could not decode (a value that is not valid UTF-8, a bad
 * {@code %} sequence, an empty name, the parameter-count limit) raises Tomcat's
 * {@link InvalidParameterException} at argument binding, the first parameter read on the TEA chain; it is answered
 * here as 400 INVALID_REQUEST with a fixed message and one DEBUG line, so no {@code ServletException} reaches the
 * container, whose {@code StandardWrapperValve} would log it at ERROR with the stack trace. This depends on the
 * embedded Tomcat the pom pins: a container change must revisit it. With
 * {@code org.springframework.web.servlet.DispatcherServlet} logging at DEBUG, Spring reads the parameters before any
 * handler runs and the container's ERROR line returns (TEA-21 design section 6).
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = { ProductApiController.class, ProductsApiController.class,
		ProductReleaseApiController.class, ProductReleasesApiController.class, ComponentApiController.class,
		ComponentsApiController.class, ComponentReleaseApiController.class, ComponentReleasesApiController.class,
		DiscoveryApiController.class, ArtifactApiController.class, WellKnownWs.class })
public class TeaErrorAdvice {

	/** The fixed message of {@link #invalidParameter} (task TEA-21). */
	static final String UNDECODABLE_QUERY = "the query string could not be decoded";

	@ExceptionHandler(TeaRefusal.class)
	public ResponseEntity<TeaErrorResponse> refusal(TeaRefusal r) {
		return r.response();
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<TeaErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e) {
		String type = null == e.getRequiredType() ? "value" : e.getRequiredType().getSimpleName();
		return TeaRefusal.invalidRequest(e.getName() + " is not a valid " + type).response();
	}

	@ExceptionHandler({ ConversionFailedException.class, TypeMismatchException.class })
	public ResponseEntity<TeaErrorResponse> conversion(Exception e) {
		String name = e instanceof TypeMismatchException tme && null != tme.getPropertyName() ? tme.getPropertyName() : "a parameter";
		return TeaRefusal.invalidRequest(name + " could not be read").response();
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<TeaErrorResponse> missing(MissingServletRequestParameterException e) {
		return TeaRefusal.invalidRequest(e.getParameterName() + " is required").response();
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<TeaErrorResponse> methodValidation(HandlerMethodValidationException e) {
		String names = String.join(", ", e.getParameterValidationResults().stream()
				.map(r -> String.valueOf(r.getMethodParameter().getParameterName())).distinct().toList());
		return TeaRefusal.invalidRequest((names.isEmpty() ? "a parameter" : names) + " is out of range").response();
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<TeaErrorResponse> constraint(ConstraintViolationException e) {
		String names = String.join(", ", e.getConstraintViolations().stream()
				.map(v -> lastNode(String.valueOf(v.getPropertyPath())) + " " + v.getMessage()).sorted().toList());
		return TeaRefusal.invalidRequest(names.isEmpty() ? "a parameter is invalid" : names).response();
	}

	/**
	 * Task TEA-21: parameters Tomcat could not decode. The body echoes nothing of the request (a parameter name may
	 * itself be the undecodable text); the log line names the cause's class, the path and the parameter through
	 * {@link LogSafe#text}, never the value nor Tomcat's message, and carries no throwable. It reads only the raw
	 * query string: a parameter read here would answer the partial map Tomcat kept.
	 */
	@ExceptionHandler(InvalidParameterException.class)
	public ResponseEntity<TeaErrorResponse> invalidParameter(InvalidParameterException e, HttpServletRequest request) {
		logUndecodable(log, e, request);
		return TeaRefusal.invalidRequest(UNDECODABLE_QUERY).response();
	}

	/** The one DEBUG line of TEA-21, written by the class that answers. */
	static void logUndecodable(org.slf4j.Logger logger, InvalidParameterException e, HttpServletRequest request) {
		if (!logger.isDebugEnabled()) return;
		logger.debug("TEA request refused, parameters could not be decoded ({}): path {}, parameter {}",
				TeaQueryStrings.causeWord(e), LogSafe.text(request.getRequestURI()),
				LogSafe.text(TeaQueryStrings.undecodableName(request.getQueryString()).orElse(null)));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<TeaErrorResponse> unreadable(HttpMessageNotReadableException e) {
		return TeaRefusal.invalidRequest("the request could not be read").response();
	}

	private static String lastNode(String path) {
		int i = path.lastIndexOf('.');
		return i < 0 ? path : path.substring(i + 1);
	}
}
