/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads the role templates for the template contract tests, one class per template (task RD4-15). Helpers only;
 * no tests go here.
 *
 * <p>The templates live in {@code src/test/resources/agentic/roles} and are read from the test classpath. They
 * used to be read from {@code ai-plans/agentic/roles} on disk, which {@code backend/.dockerignore} keeps out of
 * the image build, so every test that read one failed in the main build -- the only build that runs the suite.
 */
final class RoleTemplates {

	static final String ROLES = "agentic/roles/";

	private RoleTemplates() {}

	/** The template as written. */
	static String raw(String name) throws IOException {
		try (InputStream in = RoleTemplates.class.getClassLoader().getResourceAsStream(ROLES + name)) {
			if (null == in) throw new IOException("No role template " + ROLES + name + " on the test classpath");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** The template with line wraps folded to spaces, so a phrase reads the same wherever it breaks. */
	static String template(String name) throws IOException {
		return fold(raw(name));
	}

	static String fold(String s) {
		return s.replaceAll("[ \\t]*\\n[ \\t]*", " ");
	}
}
