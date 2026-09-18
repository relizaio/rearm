/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Dump the served programmatic SDL to a file, offline.
 *
 * <p>Not an assertion about anything -- a tool. The client generators (rearm-client-go, the
 * Terraform provider) consume the bundle that {@code GET /api/programmatic/schema} serves, and
 * copying it out of a running server means having one. {@code buildBundle} is static and reads
 * only the two schema resources, so the same document can be produced from a branch that has
 * never been deployed.
 *
 * <p>Run it when the programmatic surface changes:
 * {@code mvn -o test -Dtest=DumpProgrammaticSchemaTest -DdumpTo=/tmp/programmatic.graphqls}
 */
public class DumpProgrammaticSchemaTest {

	@Test
	public void dump() throws Exception {
		String target = System.getProperty("dumpTo");
		if (target == null) {
			System.out.println("DumpProgrammaticSchemaTest: pass -DdumpTo=<path> to write the bundle");
			return;
		}
		String shared = new String(new ClassPathResource("schema/schema.graphqls").getInputStream()
				.readAllBytes(), StandardCharsets.UTF_8);
		String programmatic = new String(new ClassPathResource("schema/programmatic.graphqls").getInputStream()
				.readAllBytes(), StandardCharsets.UTF_8);
		String bundle = ProgrammaticSchemaRegistry.buildBundle(shared, programmatic);
		Files.writeString(Path.of(target), bundle, StandardCharsets.UTF_8);
		System.out.println("wrote " + bundle.length() + " chars of SDL to " + target);
	}
}
