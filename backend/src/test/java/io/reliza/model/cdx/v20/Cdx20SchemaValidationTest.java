/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * The fixtures the subset is tested with are valid against the pinned CycloneDX 2.0 snapshot, and
 * the validator rejects an invalid one. Validation is test-only: ReARM does not validate uploads
 * against the 2.0-dev schema at runtime.
 *
 * <p>The schema is passed as a stream and documents as strings (the recipe of
 * {@code OpenVexServiceSchemaTest}). Never call {@code initializeValidators()}: the bundle keeps six
 * external {@code $ref}s (behavior taxonomy, cryptography definitions, SPDX) that cannot be
 * resolved offline; they sit on paths the fixtures do not use.
 */
class Cdx20SchemaValidationTest {

	private static final JsonSchema SCHEMA = loadSchema();

	private static JsonSchema loadSchema() {
		JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
		try (InputStream in = Cdx20SchemaValidationTest.class.getResourceAsStream(Cdx20TestResources.SCHEMA)) {
			if (in == null) throw new IllegalStateException(Cdx20TestResources.SCHEMA + " not found");
			return factory.getSchema(in);
		} catch (Exception e) {
			throw new IllegalStateException("Cannot load " + Cdx20TestResources.SCHEMA, e);
		}
	}

	private static Set<ValidationMessage> validate(String path) {
		return SCHEMA.validate(Cdx20TestResources.read(path), InputFormat.JSON);
	}

	@Test
	void rearmFixtureIsValid() {
		assertEquals(Set.of(), validate(Cdx20TestResources.REARM_FIXTURE));
	}

	@Test
	void upstreamValidFixtureIsValid() {
		assertEquals(Set.of(), validate(Cdx20TestResources.UPSTREAM_VALID_FIXTURE));
	}

	@Test
	void levelIsRequired() {
		Set<ValidationMessage> errors = validate(Cdx20TestResources.UPSTREAM_INVALID_FIXTURE);
		assertFalse(errors.isEmpty());
		assertTrue(errors.stream().anyMatch(m -> m.getMessage().contains("level")), errors.toString());
	}
}
