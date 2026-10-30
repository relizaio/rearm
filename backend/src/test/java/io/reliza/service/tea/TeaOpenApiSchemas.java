/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.yaml.snakeyaml.Yaml;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Test support (task TEA-6): the schemas of the upstream TEA 1.0.0 OpenAPI file, one JSON Schema
 * per name. OpenAPI 3.1 schema objects are JSON Schema 2020-12, so every component schema goes
 * under {@code $defs}, its references are rewritten from {@code #/components/schemas/x} to
 * {@code #/$defs/x}, and the schema for a name is {@code {"$defs": all, "$ref": "#/$defs/name"}},
 * with format assertions on.
 */
public final class TeaOpenApiSchemas {

	static final String OPENAPI = "/schema/tea-1.0.0-openapi.yaml";
	static final String WELL_KNOWN = "/schema/tea-well-known.schema.json";

	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
	private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
	private static final Map<String, Object> DEFS = loadDefs();
	private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();

	private TeaOpenApiSchemas() {}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> loadDefs() {
		try (InputStream in = TeaOpenApiSchemas.class.getResourceAsStream(OPENAPI)) {
			if (null == in) throw new IllegalStateException("TEA 1.0.0 OpenAPI fixture not found at " + OPENAPI);
			Map<String, Object> doc = new Yaml().load(in);
			Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
			return (Map<String, Object>) rewrite(schemas);
		} catch (Exception e) {
			throw new IllegalStateException("could not load the TEA 1.0.0 OpenAPI fixture", e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Object rewrite(Object node) {
		if (node instanceof Map<?, ?> m) {
			Map<String, Object> out = new LinkedHashMap<>();
			for (Map.Entry<?, ?> e : m.entrySet()) {
				Object v = e.getValue();
				if ("$ref".equals(e.getKey()) && v instanceof String s && s.startsWith("#/components/schemas/")) {
					out.put("$ref", "#/$defs/" + s.substring("#/components/schemas/".length()));
				} else {
					out.put(String.valueOf(e.getKey()), rewrite(v));
				}
			}
			return out;
		}
		if (node instanceof List<?> l) return l.stream().map(TeaOpenApiSchemas::rewrite).toList();
		return node;
	}

	/** Every component schema name of the 1.0.0 file. */
	public static Set<String> names() {
		return DEFS.keySet();
	}

	public static JsonSchema schema(String name) {
		return CACHE.computeIfAbsent(name, n -> {
			assertTrue(DEFS.containsKey(n), "no schema named " + n + " in the TEA 1.0.0 OpenAPI file");
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
			root.put("$defs", DEFS);
			root.put("$ref", "#/$defs/" + n);
			try {
				return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.writeValueAsString(root), CONFIG);
			} catch (Exception e) {
				throw new IllegalStateException("could not build the schema " + n, e);
			}
		});
	}

	public static Set<ValidationMessage> errors(String json, String schemaName) {
		return schema(schemaName).validate(json, InputFormat.JSON);
	}

	/** Fails with the messages and the document unless {@code json} conforms to the named schema. */
	public static void assertConforms(String json, String schemaName) {
		assertNotNull(json, "no document to validate against " + schemaName);
		Set<ValidationMessage> errors = errors(json, schemaName);
		assertTrue(errors.isEmpty(), () -> "not a valid TEA 1.0.0 " + schemaName + ":\n" + errors + "\n---\n" + json);
	}

	/** Fails unless {@code json} conforms to the upstream discovery document schema. */
	public static void assertWellKnownConforms(String json) {
		JsonSchema s;
		try (InputStream in = TeaOpenApiSchemas.class.getResourceAsStream(WELL_KNOWN)) {
			if (null == in) throw new IllegalStateException("discovery schema fixture not found at " + WELL_KNOWN);
			s = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in, CONFIG);
		} catch (Exception e) {
			throw new IllegalStateException("could not load the discovery schema fixture", e);
		}
		Set<ValidationMessage> errors = s.validate(json, InputFormat.JSON);
		assertTrue(errors.isEmpty(), () -> "not a valid TEA discovery document:\n" + errors + "\n---\n" + json);
	}
}
