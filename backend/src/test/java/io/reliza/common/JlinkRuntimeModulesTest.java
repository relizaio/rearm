/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The backend image runs a jlink runtime holding only the modules the Dockerfile lists. A class from
 * a module left out compiles and passes every test on a full JDK, then fails in production with
 * NoClassDefFoundError: com.sun.management (jdk.management) in HeapPressureGuard stopped every
 * metrics compute worker on 2026-10-30. This pins the com.sun.management case.
 */
class JlinkRuntimeModulesTest {

	/** A reference to a class of the package (an import or a qualified name), not prose naming it. */
	private static final Pattern CLASS_REF = Pattern.compile("com\\.sun\\.management\\.[A-Za-z]");

	/**
	 * The modules the Dockerfile's jlink step adds. The tests also run inside the image build, where the
	 * Dockerfile is not in the build context: there the runtime is taken to lack jdk.management, the
	 * strict reading, so the source check below still runs.
	 */
	private static Set<String> jlinkModules() throws IOException {
		Path dockerfilePath = Path.of("Dockerfile");
		if (!Files.exists(dockerfilePath)) return Set.of();
		String dockerfile = Files.readString(dockerfilePath);
		Matcher m = Pattern.compile("--add-modules\\s+(\\S+)").matcher(dockerfile);
		assertTrue(m.find(), "the Dockerfile names its jlink modules with --add-modules");
		return Set.of(m.group(1).split(","));
	}

	@Test
	void noMainSourceUsesComSunManagementWithoutJdkManagementInTheRuntime() throws IOException {
		if (jlinkModules().contains("jdk.management")) return;
		List<String> offenders;
		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
				try {
					return CLASS_REF.matcher(Files.readString(p)).find();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}).map(Path::toString).collect(Collectors.toList());
		}
		assertTrue(offenders.isEmpty(),
				"com.sun.management is in jdk.management, which the jlink runtime does not include: " + offenders);
	}
}
