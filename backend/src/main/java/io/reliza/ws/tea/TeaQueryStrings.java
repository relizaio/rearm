/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Task TEA-21: names the parameter of a raw query string that Tomcat refused to decode, for the one DEBUG line the
 * TEA chain writes when a request's parameters could not be decoded ({@link TeaErrorAdvice} and
 * {@link TeaTokenController} answer {@code org.apache.tomcat.util.http.InvalidParameterException}).
 *
 * <p>It reproduces the decoding rules of Tomcat 11's {@code Parameters} and {@code UDecoder} over the raw text rather
 * than parsing Tomcat's message, which is localized and built from the request bytes: pairs split on {@code &} only,
 * an empty pair skipped; the name is the text before the first {@code =}, the value the rest; in both, {@code +} is a
 * space and {@code %XX} with two hex digits one byte, a {@code %} without two hex digits makes the pair undecodable,
 * and the bytes must be valid UTF-8. It does not reproduce the parameter-count limit nor the empty-name rule: those
 * refusals carry no cause, and the log line tells them apart by its cause word. A Tomcat upgrade that changes the
 * rules degrades only the logged name, never the answer. Pure: no request access, no logging.
 */
final class TeaQueryStrings {

	private TeaQueryStrings() {}

	/**
	 * The name of the first pair of {@code rawQuery} whose name or value would not decode, itself decoded with
	 * replacement (so a name that is the offender carries U+FFFD, which {@code LogSafe.text} renders as {@code ?});
	 * empty when every pair decodes or the query is null or empty. The caller bounds the rendering.
	 */
	static Optional<String> undecodableName(String rawQuery) {
		if (null == rawQuery || rawQuery.isEmpty()) return Optional.empty();
		int n = rawQuery.length();
		int start = 0;
		while (start < n) {
			int amp = rawQuery.indexOf('&', start);
			int end = amp < 0 ? n : amp;
			if (end > start) {
				int eq = rawQuery.indexOf('=', start);
				int nameEnd = eq < 0 || eq > end ? end : eq;
				byte[] name = bytesOf(rawQuery, start, nameEnd);
				byte[] value = nameEnd == end ? new byte[0] : bytesOf(rawQuery, nameEnd + 1, end);
				if (null == name || null == value || !isUtf8(name) || !isUtf8(value)) {
					return Optional.of(lenientName(rawQuery, start, nameEnd));
				}
			}
			if (amp < 0) break;
			start = amp + 1;
		}
		return Optional.empty();
	}

	/**
	 * The simple class name of the refusal's cause, or of the refusal itself when it has none: a class name, never
	 * the message, which Tomcat builds from the request bytes.
	 */
	static String causeWord(Throwable refusal) {
		Throwable cause = refusal.getCause();
		return (null == cause ? refusal : cause).getClass().getSimpleName();
	}

	/** The bytes of {@code s[from, to)} as Tomcat's query decoder reads them, or null on a bad {@code %} sequence. */
	private static byte[] bytesOf(String s, int from, int to) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
		for (int i = from; i < to; i++) {
			char c = s.charAt(i);
			if (c == '%') {
				if (i + 2 >= to || !isAsciiHex(s.charAt(i + 1)) || !isAsciiHex(s.charAt(i + 2))) return null;
				out.write((Character.digit(s.charAt(i + 1), 16) << 4) | Character.digit(s.charAt(i + 2), 16));
				i += 2;
			} else {
				writeChar(out, c);
			}
		}
		return out.toByteArray();
	}

	/** The name decoded with replacement; a bad {@code %} sequence is kept as typed. */
	private static String lenientName(String s, int from, int to) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(to - from);
		for (int i = from; i < to; i++) {
			char c = s.charAt(i);
			if (c == '%' && i + 2 < to && isAsciiHex(s.charAt(i + 1)) && isAsciiHex(s.charAt(i + 2))) {
				out.write((Character.digit(s.charAt(i + 1), 16) << 4) | Character.digit(s.charAt(i + 2), 16));
				i += 2;
			} else {
				writeChar(out, c);
			}
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/**
	 * One character of the raw query: {@code +} is a space; a character up to U+00FF is the byte Tomcat read (its
	 * query string is the request-line bytes as ISO-8859-1); anything above, which the request line cannot carry, as
	 * its UTF-8 bytes.
	 */
	private static void writeChar(ByteArrayOutputStream out, char c) {
		if (c == '+') {
			out.write(' ');
		} else if (c <= 0xFF) {
			out.write(c);
		} else {
			byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
			out.write(b, 0, b.length);
		}
	}

	private static boolean isAsciiHex(char c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
	}

	private static boolean isUtf8(byte[] bytes) {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			decoder.decode(ByteBuffer.wrap(bytes));
			return true;
		} catch (CharacterCodingException undecodable) {
			return false;
		}
	}
}
