/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.multipart.MultipartFile;

import io.reliza.exceptions.InvalidInputException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.PropertyBindingException;
import tools.jackson.databind.exc.ValueInstantiationException;

/**
 * The conversion of a resolver's own GraphQL argument (a map or list it read from
 * {@code dfe.getArgument(..)} or an {@code @InputArgument}, or a part of one) into a DTO (task TPF-63).
 *
 * <p>Use it instead of {@code Utils.OM.convertValue} wherever the value converted came from the
 * caller; keep {@code Utils.OM.convertValue} for stored or internal data, whose failure is a server
 * fault. A value that does not fit the type throws {@link InvalidInputException} with a message
 * {@code <path>: <what it must be>}, which {@code GraphQLExceptionHandlers.handleInvalidInput}
 * answers as BAD_REQUEST: the path through {@link LogSafe#text}, the rest fixed vocabulary (for an
 * enum, its own constants as the server writes them). The value sent is never echoed, and the
 * exception has no cause. A type that cannot be read at all, or a property the DTO refuses, is the
 * server's fault (the schema already validated the input) and is rethrown unchanged.
 *
 * <p>Call it as {@code InputConversion.convert(..)} (no static import), so a grep finds every site.
 */
public final class InputConversion {

	static final String FILE_NOT_ACCEPTED = "a file is not accepted here";
	static final String NOT_A_UUID = "not a uuid";
	static final String INVALID_VALUE = "has an invalid value";

	private InputConversion() {}

	/** {@code value} read as {@code type}; null for null. */
	public static <T> T convert(Object value, Class<T> type, String path) {
		if (null == value) return null;
		refuseUploads(value, path);
		try {
			return Utils.OM.convertValue(value, type);
		} catch (JacksonException e) {
			throw refusal(e, path);
		}
	}

	/** {@code value} read as {@code type}; null for null. */
	public static <T> T convert(Object value, TypeReference<T> type, String path) {
		if (null == value) return null;
		refuseUploads(value, path);
		try {
			return Utils.OM.convertValue(value, type);
		} catch (JacksonException e) {
			throw refusal(e, path);
		}
	}

	/**
	 * Each element read as {@code type}, element i under {@code path[i]}, into a new mutable list; null
	 * for null.
	 */
	public static <T> List<T> convertEach(List<?> values, Class<T> type, String path) {
		if (null == values) return null;
		List<T> converted = new ArrayList<>(values.size());
		for (int i = 0; i < values.size(); i++) {
			converted.add(convert(values.get(i), type, path + "[" + i + "]"));
		}
		return converted;
	}

	/**
	 * A file anywhere in the tree is refused before Jackson would write it out bean by bean. No method
	 * of the file is called.
	 */
	private static void refuseUploads(Object value, String path) {
		if (value instanceof MultipartFile) {
			throw new InvalidInputException(LogSafe.text(path) + ": " + FILE_NOT_ACCEPTED);
		}
		if (value instanceof Map<?, ?> m) {
			for (Map.Entry<?, ?> e : m.entrySet()) {
				if (null != e.getValue()) refuseUploads(e.getValue(), path + "." + e.getKey());
			}
		} else if (value instanceof Collection<?> c) {
			int i = 0;
			for (Object element : c) {
				if (null != element) refuseUploads(element, path + "[" + i + "]");
				i++;
			}
		}
	}

	private static RuntimeException refusal(JacksonException e, String path) {
		if (e instanceof InvalidDefinitionException || e instanceof PropertyBindingException) {
			return e;
		}
		return new InvalidInputException(LogSafe.text(path + referencePath(e)) + ": " + what(e));
	}

	/** Jackson's reference chain, outermost first: {@code .name} for a property, {@code [i]} for an index. */
	private static String referencePath(JacksonException e) {
		StringBuilder b = new StringBuilder();
		for (JacksonException.Reference r : e.getPath()) {
			if (null != r.getPropertyName()) {
				b.append('.').append(r.getPropertyName());
			} else if (r.getIndex() >= 0) {
				b.append('[').append(r.getIndex()).append(']');
			}
		}
		return b.toString();
	}

	private static String what(JacksonException e) {
		if (e instanceof MismatchedInputException mie) {
			Class<?> target = mie.getTargetType();
			return null == target ? INVALID_VALUE : kindOf(target);
		}
		if (e instanceof ValueInstantiationException vie) {
			JavaType type = vie.getType();
			Class<?> target = null == type ? null : type.getRawClass();
			return null != target && isEnum(target) ? oneOf(target) : INVALID_VALUE;
		}
		return INVALID_VALUE;
	}

	private static String kindOf(Class<?> t) {
		if (isEnum(t)) return oneOf(t);
		if (UUID.class.equals(t)) return NOT_A_UUID;
		if (Boolean.class.equals(t) || boolean.class.equals(t)) return "must be true or false";
		if (Number.class.isAssignableFrom(t)
				|| (t.isPrimitive() && !boolean.class.equals(t) && !char.class.equals(t) && !void.class.equals(t))) {
			return "must be a number";
		}
		if (CharSequence.class.isAssignableFrom(t) || Character.class.equals(t) || char.class.equals(t)) {
			return "must be a string";
		}
		if (Temporal.class.isAssignableFrom(t)) return "must be a date-time";
		if (Collection.class.isAssignableFrom(t) || t.isArray()) return "must be a list";
		return "must be an object";
	}

	private static boolean isEnum(Class<?> t) {
		return Enum.class.isAssignableFrom(t) && !Enum.class.equals(t);
	}

	/** Every constant in declaration order, each as the server writes (and reads) it. */
	private static String oneOf(Class<?> t) {
		Class<?> enumClass = t.isEnum() ? t : t.getSuperclass();
		Object[] constants = enumClass.getEnumConstants();
		if (null == constants || constants.length == 0) return INVALID_VALUE;
		return "must be one of " + Arrays.stream(constants)
				.map(c -> Utils.OM.convertValue(c, String.class))
				.collect(Collectors.joining(", "));
	}
}
