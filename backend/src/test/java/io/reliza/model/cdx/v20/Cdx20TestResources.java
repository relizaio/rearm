/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Test resources for the CycloneDX 2.0 subset. The schema snapshot is the bundled 2.0-dev schema
 * at {@link Cdx20DocumentReader#CDX_2_0_DEV_PINNED_COMMIT}, copied byte for byte (never reformat it)
 * from https://github.com/CycloneDX/specification/blob/ea8c3605829d1da13339a0790b6da73548e42a6b/schema/2.0/cyclonedx-2.0-bundled.schema.json.
 * The {@code valid-}/{@code invalid-} fixtures are copied from tools/src/test/resources/2.0/ at the
 * same commit; {@code rearm-safety-context-2.0.json} is ReARM's own.
 */
final class Cdx20TestResources {

	static final String SCHEMA = "/schema/cyclonedx-2.0-dev-ea8c3605.bundled.schema.json";
	static final String REARM_FIXTURE = "/cdx20/rearm-safety-context-2.0.json";
	static final String UPSTREAM_VALID_FIXTURE = "/cdx20/valid-safety-impact-assessment-2.0.json";
	static final String UPSTREAM_INVALID_FIXTURE = "/cdx20/invalid-safety-integrity-level-missing-level-2.0.json";

	private Cdx20TestResources() {}

	static String read(String path) {
		try (InputStream in = Cdx20TestResources.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("Missing test resource " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
