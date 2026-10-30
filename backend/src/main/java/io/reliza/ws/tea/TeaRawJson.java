/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes stored TEA documents byte for byte (task TEA-6): a collection version as TEA-5 stored it,
 * or a page or object assembled around stored documents. The controller method then returns null,
 * which Spring MVC takes as a handled response for a ResponseEntity return type.
 */
public final class TeaRawJson {

	private TeaRawJson() {}

	public static void write(NativeWebRequest request, int status, HttpHeaders headers, String json) {
		HttpServletResponse response = responseOf(request);
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		response.setStatus(status);
		copyHeaders(headers, response);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setContentLength(bytes.length);
		try {
			response.getOutputStream().write(bytes);
			response.flushBuffer();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A 304 with the given headers and no body. */
	public static void notModified(NativeWebRequest request, HttpHeaders headers) {
		HttpServletResponse response = responseOf(request);
		response.setStatus(HttpStatus.NOT_MODIFIED.value());
		copyHeaders(headers, response);
		try {
			response.flushBuffer();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** {@code {"hasNext":false,"results":[<doc>,...]}} with each document verbatim. */
	public static String page(List<String> documents) {
		StringBuilder sb = new StringBuilder("{\"hasNext\":false,\"results\":[");
		for (int i = 0; i < documents.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append(documents.get(i));
		}
		return sb.append("]}").toString();
	}

	private static void copyHeaders(HttpHeaders headers, HttpServletResponse response) {
		if (null == headers) return;
		headers.forEach((name, values) -> {
			if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) return;
			for (String v : values) response.addHeader(name, v);
		});
	}

	private static HttpServletResponse responseOf(NativeWebRequest request) {
		HttpServletResponse r = null == request ? null : request.getNativeResponse(HttpServletResponse.class);
		if (null == r && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
			r = sra.getResponse();
		}
		if (null == r) throw new IllegalStateException("no servlet response to write the TEA document to");
		return r;
	}
}
