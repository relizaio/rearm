/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes stored artifact bytes as they are (task TEA-10), the {@link TeaRawJson} shape for bytes:
 * no message converter negotiates the body or its Content-Type, and the length is exact. The
 * controller method then returns null. A HEAD keeps the headers; the container drops the body.
 */
public final class TeaRawBytes {

	private TeaRawBytes() {}

	public static void write(NativeWebRequest request, HttpHeaders headers, byte[] bytes) {
		HttpServletResponse response = responseOf(request);
		response.setStatus(HttpStatus.OK.value());
		headers.forEach((name, values) -> {
			if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name) || HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) return;
			for (String v : values) response.addHeader(name, v);
		});
		String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
		if (null != contentType) response.setContentType(contentType);
		response.setContentLengthLong(bytes.length);
		try {
			response.getOutputStream().write(bytes);
			response.flushBuffer();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A 302 with these headers and no body (task TEA-10 decision 11): the {@link TeaRawJson#notModified} shape. */
	public static void redirect(NativeWebRequest request, HttpHeaders headers) {
		HttpServletResponse response = responseOf(request);
		response.setStatus(HttpStatus.FOUND.value());
		headers.forEach((name, values) -> {
			for (String v : values) response.addHeader(name, v);
		});
		try {
			response.flushBuffer();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static HttpServletResponse responseOf(NativeWebRequest request) {
		HttpServletResponse r = null == request ? null : request.getNativeResponse(HttpServletResponse.class);
		if (null == r && RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
			r = sra.getResponse();
		}
		if (null == r) throw new IllegalStateException("no servlet response to write the TEA artifact to");
		return r;
	}
}
