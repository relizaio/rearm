/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;

import org.springframework.stereotype.Component;

import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import io.reliza.common.LogSafe;
import io.reliza.common.Utils;
import io.reliza.exceptions.InvalidInputException;
import tools.jackson.core.JacksonException;

/**
 * Client text holding U+0000 is refused at input, naming the field (task TPF-47). Postgres stores that
 * code point neither in {@code text} nor in {@code jsonb}, so such text used to pass every check and
 * fail at the write with the generic "Request violates data constraints".
 *
 * <p>One check over the executor, not one per field: every field that declares arguments, on both
 * endpoints ({@code /graphql} and {@code /api/programmatic/graphql} run this one engine), has its
 * coerced arguments walked before its data fetcher is invoked, variables and document literals alike.
 * The first value in argument order that holds U+0000 throws {@link InvalidInputException}, which
 * {@code GraphQLExceptionHandlers.handleInvalidInput} answers as BAD_REQUEST: the argument path
 * (through {@link LogSafe#text}, since a map key under an {@code Object} argument is client text) and
 * a fixed text, never the value, no log line. Nothing of the refused field runs: not its argument
 * binding, its authorization, a read or a write. Only U+0000 is refused; every other character can
 * be stored and keeps its answer.
 *
 * <p>A {@code String} argument whose text the server parses as a JSON document carries the character only as the
 * JSON escape, which this walk sees as six storable characters; the fetcher that parses such an argument calls
 * {@link #refuseDecodedJson} after its authorization and right before its parse, so the decoded NUL is refused with
 * the same text under the argument's path (architecture 3.8). The helper reads with the reader its site's parser uses,
 * over the form that parser reads: the CycloneDX import site calls {@link #refuseDecodedCycloneDxJson} instead.
 */
@Component
public class NulInputInstrumentation extends SimplePerformantInstrumentation {

	static final String NUL_NOT_ACCEPTED = "must not contain U+0000";

	private static final char NUL = '\u0000';

	@Override
	public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters,
			InstrumentationState state) {
		if (parameters.isTrivialDataFetcher()) return dataFetcher; // a property read takes no arguments
		if (parameters.getEnvironment().getFieldDefinition().getArguments().isEmpty()) return dataFetcher;
		return env -> {
			refuse(env.getArguments());
			return dataFetcher.get(env);
		};
	}

	/** Throws InvalidInputException for the first argument value, in argument order, that holds U+0000. */
	static void refuse(Map<String, Object> arguments) {
		if (null == arguments) return;
		Path path = new Path();
		for (Map.Entry<String, Object> e : arguments.entrySet()) {
			path.push(e.getKey());
			walk(e.getValue(), path);
			path.pop();
		}
	}

	/**
	 * A String argument whose text the server parses as a JSON document (architecture 3.8): the decoded document is
	 * walked under the argument's path, so a NUL that travelled as the six-character JSON escape is refused exactly
	 * like a raw one, by the first offending value in document order. Text that is not JSON is left alone: the
	 * fetcher's own parser answers it as it does today. Null or blank text is left alone for the same reason.
	 */
	static void refuseDecodedJson(String argumentPath, String json) {
		if (null == json || json.isBlank()) return;
		Object decoded;
		try {
			decoded = Utils.OM.readValue(json, Object.class); // maps, lists, strings, numbers, booleans, null
		} catch (JacksonException e) { // not JSON: the fetcher's own parser answers it, as today
			return;
		}
		Path path = new Path();
		path.push(argumentPath); // rendered as is, then .key and [i] below it
		walk(decoded, path);
	}

	/**
	 * Site 2 only (architecture decision 11): the CycloneDX import parses the argument's bytes with cyclonedx-core-java's
	 * own default Jackson 2 ObjectMapper (JsonParser 51-53, 69-75: readValue(byte[], Bom.class)), which ignores trailing
	 * tokens, skips a UTF-8 byte-order mark in byte input and admits 1000 levels, where Utils.OM (Jackson 3) fails on
	 * trailing tokens, refuses U+FEFF in character input and admits 500. So this walk reads the same bytes with the
	 * repository's Jackson 2 mapper, Utils.CDX_OM, whose two customizations are write-side: the two readers cannot
	 * disagree. Bytes the import cannot read are left alone: the import answers them with its own parse-failure row,
	 * as today.
	 */
	static void refuseDecodedCycloneDxJson(String argumentPath, String cdxJson) {
		if (null == cdxJson || cdxJson.isBlank()) return;
		Object decoded;
		try {
			decoded = Utils.CDX_OM.readValue(cdxJson.getBytes(), Object.class); // the import's own call, less the Bom binding
		} catch (IOException e) { // Jackson 2's JacksonException is an IOException; a byte array raises no other
			return;
		}
		Path path = new Path();
		path.push(argumentPath);
		walk(decoded, path);
	}

	/**
	 * The segments of the value being walked: the argument name or a prefix, then a String per map key and an Integer
	 * per list index. Pushed and popped as the walk descends and returns; rendered only by {@link #refusal}.
	 */
	private static final class Path {
		private final ArrayDeque<Object> segments = new ArrayDeque<>();

		void push(Object segment) {
			segments.addLast(segment);
		}

		void pop() {
			segments.removeLast();
		}

		/**
		 * The first segment as is, then ".key" per key and "[i]" per index, stopping once the text is longer than
		 * LogSafe.MAX_LENGTH units: LogSafe.text cuts there and appends "...", so the message is the same as for the
		 * whole path, and the rendering costs at most that many units plus one segment.
		 */
		String render() {
			StringBuilder b = new StringBuilder();
			boolean first = true;
			for (Object segment : segments) {
				if (b.length() > LogSafe.MAX_LENGTH) break;
				if (segment instanceof Integer i) b.append('[').append((int) i).append(']');
				else if (first) b.append(segment);
				else b.append('.').append(segment);
				first = false;
			}
			return b.toString();
		}
	}

	/**
	 * Depth first: a string is checked, a map key then value ({@code path.key}), a collection by index
	 * ({@code path[i]}); any other value (a uuid, a number, an enum, a date, an uploaded file) is
	 * skipped without a method call on it. The path is a stack of segments, rendered only on a refusal,
	 * so the walk is linear in the input.
	 */
	private static void walk(Object value, Path path) {
		if (value instanceof String s) {
			if (s.indexOf(NUL) >= 0) throw refusal(path);
		} else if (value instanceof Map<?, ?> m) {
			for (Map.Entry<?, ?> e : m.entrySet()) {
				path.push(e.getKey());
				if (e.getKey() instanceof String k && k.indexOf(NUL) >= 0) throw refusal(path);
				walk(e.getValue(), path);
				path.pop();
			}
		} else if (value instanceof Collection<?> c) {
			int i = 0;
			for (Object element : c) {
				path.push(i);
				walk(element, path);
				path.pop();
				i++;
			}
		}
	}

	private static InvalidInputException refusal(Path path) {
		return new InvalidInputException(LogSafe.text(path.render()) + ": " + NUL_NOT_ACCEPTED);
	}
}
