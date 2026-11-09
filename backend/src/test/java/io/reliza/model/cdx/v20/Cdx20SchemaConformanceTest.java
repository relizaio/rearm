/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.ObjectMapper;

/**
 * Every property the CycloneDX 2.0 subset binds exists in the pinned schema snapshot, with a
 * matching JSON type. The walk starts at {@link Cdx20Document} and follows the subset's own field
 * types, so the subset classes and their {@link Cdx20SchemaDef} pointers are the only list: after a
 * snapshot bump, a failure names each moved, renamed or retyped field.
 *
 * <p>Pointers are resolved by following {@code $ref} and {@code allOf} only. {@code oneOf} and
 * {@code anyOf} branches are not merged into a definition's properties (that would mix, for example,
 * a component with a component reference); a property whose own type is a union matches when any
 * branch has the expected type.
 */
class Cdx20SchemaConformanceTest {

	private static final Map<String, Object> SCHEMA = loadSchema();

	@SuppressWarnings("unchecked")
	private static Map<String, Object> loadSchema() {
		return new ObjectMapper().readValue(Cdx20TestResources.read(Cdx20TestResources.SCHEMA), Map.class);
	}

	@Test
	void everyBoundPropertyExistsWithItsType() {
		Map<String, String> checked = new LinkedHashMap<>();
		List<String> failures = check(Cdx20Document.class, checked);
		assertEquals(List.of(), failures, "Subset fields that do not match the pinned schema");
		// the walk reached the blueprint side and the component side
		assertTrue(checked.containsKey("Cdx20SafetyIntegrityLevel.level"), checked.keySet().toString());
		assertTrue(checked.containsKey("Cdx20Identity.value"), checked.keySet().toString());
	}

	@Test
	void aFieldMissingFromTheSchemaIsReported() {
		List<String> failures = check(Misbound.class, new LinkedHashMap<>());
		assertEquals(2, failures.size(), failures.toString());
		assertTrue(failures.stream().anyMatch(f -> f.contains("componentRefTypo") && f.contains("no such property")),
			failures.toString());
		assertTrue(failures.stream().anyMatch(f -> f.contains("refId") && f.contains("expected integer")),
			failures.toString());
	}

	@Test
	void unionSchemesAcceptBothShapes() {
		Set<String> types = types(properties(resolve("#/$defs/cyclonedx-component-2.0/$defs/identity")).get("scheme"));
		assertTrue(types.containsAll(Set.of("string", "object")), types.toString());
	}

	@Test
	void safetySchemesMatchTheSchemaEnum() {
		Object scheme = properties(resolve("#/$defs/cyclonedx-blueprint-2.0/$defs/safetyIntegrityLevel")).get("scheme");
		assertEquals(enumValues(scheme),
			Arrays.stream(Cdx20SafetyScheme.values()).map(Cdx20SafetyScheme::value).toList());
	}

	@Test
	void assumptionValiditiesMatchTheSchemaEnum() {
		Object validity = properties(resolve("#/$defs/cyclonedx-blueprint-2.0/$defs/assumption")).get("validity");
		assertEquals(enumValues(validity),
			Arrays.stream(Cdx20AssumptionValidity.values()).map(Cdx20AssumptionValidity::value).toList());
	}

	/** The string enum values of a property, from the property itself or its first enum branch. */
	private static List<Object> enumValues(Object node) {
		Map<String, Object> m = deref(node);
		if (m.get("enum") instanceof List<?> e) return new ArrayList<>(e);
		if (m.get("oneOf") instanceof List<?> branches) {
			for (Object b : branches) {
				if (deref(b).get("enum") instanceof List<?> e) return new ArrayList<>(e);
			}
		}
		throw new IllegalStateException("No enum in " + m.keySet());
	}

	/** Bound to the asset definition with one unknown field and one retyped field. */
	@Cdx20SchemaDef("#/$defs/cyclonedx-blueprint-2.0/$defs/asset")
	static class Misbound {
		String componentRefTypo;
		Integer refId;
	}

	private static List<String> check(Class<?> root, Map<String, String> checked) {
		List<String> failures = new ArrayList<>();
		walk(root, new HashSet<>(), checked, failures);
		return failures;
	}

	private static void walk(Class<?> type, Set<Class<?>> visited, Map<String, String> checked, List<String> failures) {
		if (!visited.add(type)) return;
		Cdx20SchemaDef typeDef = type.getAnnotation(Cdx20SchemaDef.class);
		assertFalse(typeDef == null, type.getSimpleName() + " has no @Cdx20SchemaDef");
		for (Field f : type.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers())) continue;
			Cdx20SchemaDef fieldDef = f.getAnnotation(Cdx20SchemaDef.class);
			String pointer = fieldDef != null ? fieldDef.value() : typeDef.value();
			JsonProperty jp = f.getAnnotation(JsonProperty.class);
			String name = jp != null && !jp.value().isEmpty() ? jp.value() : f.getName();
			String where = type.getSimpleName() + "." + name + " (" + pointer + ")";
			Object prop = properties(resolve(pointer)).get(name);
			if (prop == null) {
				failures.add(where + ": no such property");
				continue;
			}
			Class<?> javaType = f.getType();
			Class<?> elementType = null;
			if (List.class.isAssignableFrom(javaType)) {
				elementType = (Class<?>) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
			}
			String expected = jsonType(javaType);
			Set<String> actual = types(prop);
			if (!actual.contains(expected)) {
				failures.add(where + ": expected " + expected + ", schema has " + actual);
				continue;
			}
			if (elementType != null) {
				Set<String> itemTypes = types(deref(prop).get("items"));
				String expectedItem = jsonType(elementType);
				if (!itemTypes.contains(expectedItem)) {
					failures.add(where + ": expected items " + expectedItem + ", schema has " + itemTypes);
					continue;
				}
			}
			checked.put(type.getSimpleName() + "." + name, pointer);
			Class<?> nested = elementType != null ? elementType : javaType;
			if (nested.isAnnotationPresent(Cdx20SchemaDef.class)) walk(nested, visited, checked, failures);
		}
	}

	private static String jsonType(Class<?> c) {
		if (c == String.class || c.isEnum()) return "string";
		if (c == Integer.class || c == int.class) return "integer";
		if (c == Boolean.class || c == boolean.class) return "boolean";
		if (List.class.isAssignableFrom(c)) return "array";
		// Cdx20Scheme is a string-or-object union; the object side is the stricter check
		return "object";
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> resolve(String pointer) {
		Object node = SCHEMA;
		if (!"#".equals(pointer)) {
			for (String part : pointer.substring(2).split("/")) {
				node = ((Map<String, Object>) node).get(part.replace("~1", "/").replace("~0", "~"));
				if (node == null) throw new IllegalStateException("Unresolvable pointer " + pointer);
			}
		}
		return (Map<String, Object>) node;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> deref(Object node) {
		if (!(node instanceof Map<?, ?>)) return Map.of();
		Map<String, Object> m = (Map<String, Object>) node;
		int hops = 0;
		while (m.get("$ref") instanceof String ref && ref.startsWith("#") && hops++ < 20) {
			m = resolve(ref);
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> properties(Object node) {
		Map<String, Object> m = deref(node);
		Map<String, Object> out = new LinkedHashMap<>();
		if (m.get("properties") instanceof Map<?, ?> p) out.putAll((Map<String, Object>) p);
		if (m.get("allOf") instanceof List<?> all) all.forEach(sub -> out.putAll(properties(sub)));
		return out;
	}

	private static Set<String> types(Object node) {
		Map<String, Object> m = deref(node);
		Set<String> out = new HashSet<>();
		Object t = m.get("type");
		if (t instanceof String s) out.add(s);
		if (t instanceof List<?> l) l.forEach(x -> out.add(String.valueOf(x)));
		if (m.get("enum") instanceof List<?> e && !e.isEmpty() && e.get(0) instanceof String) out.add("string");
		if (m.get("const") instanceof String) out.add("string");
		if (m.containsKey("properties") || m.containsKey("required")) out.add("object");
		for (String key : List.of("allOf", "oneOf", "anyOf")) {
			if (m.get(key) instanceof List<?> branches) branches.forEach(b -> out.addAll(types(b)));
		}
		return out;
	}
}
