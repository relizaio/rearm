/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.reliza.ws.RelizaConfigProps;

/**
 * Task TEA-6, design 4.7 case 23 (round 2: no sendError under ws/tea and service/tea): what the
 * source tree must no longer carry, and that the generated package holds generated files only.
 * The suite runs in backend/.
 */
class TeaSurfaceSourceTest {

	private static final Path MAIN = Path.of("src/main/java/io/reliza");

	private static List<Path> javaFiles(Path dir) throws IOException {
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(p -> p.toString().endsWith(".java")).toList();
		}
	}

	@Test
	void theTeaCodeCarriesNoneOfTheZeroFourSurface() throws IOException {
		for (Path dir : List.of(MAIN.resolve("ws/tea"), MAIN.resolve("service/tea"))) {
			for (Path f : javaFiles(dir)) {
				String s = Files.readString(f, StandardCharsets.UTF_8);
				// sendError: a status sent there reaches the error dispatch, which answers a non-TEA body (round 2, T-1)
				for (String banned : List.of("USER_ORG", "v0.4.0", "enableBetaTea", "transparencyExchange.base-path", "sendError(")) {
					assertFalse(s.contains(banned), f + " contains " + banned);
				}
			}
		}
		assertTrue(Arrays.stream(RelizaConfigProps.class.getDeclaredFields()).noneMatch(f -> f.getName().equals("enableBetaTea")));
		String app = Files.readString(MAIN.resolve("ws/App.java"), StandardCharsets.UTF_8);
		assertEquals(1, app.split(Pattern.quote("/.well-known/tea\""), -1).length - 1, "the discovery document is named once, for the TEA chain");
		assertFalse(app.contains("getEnableBetaTea"));
	}

	@Test
	void theGeneratedPackageHoldsOnlyFilesOfOneGeneratorVersion() throws IOException {
		Set<String> versions = new HashSet<>();
		Pattern marker = Pattern.compile("@Generated\\(value = \"org\\.openapitools\\.codegen\\.languages\\.SpringCodegen\".*comments = \"Generator version: ([0-9.]+)\"\\)");
		List<Path> generated = new java.util.ArrayList<>(javaFiles(MAIN.resolve("model/tea")).stream()
				.filter(p -> !p.toString().contains("/publication/")).toList());
		try (Stream<Path> s = Files.list(MAIN.resolve("ws/tea"))) {
			s.filter(p -> p.getFileName().toString().endsWith("Api.java")).forEach(generated::add);
		}
		assertFalse(generated.isEmpty());
		for (Path f : generated) {
			Matcher m = marker.matcher(Files.readString(f, StandardCharsets.UTF_8));
			assertTrue(m.find(), f + " carries no generator marker: a hand-written class in the generated set");
			versions.add(m.group(1));
		}
		assertEquals(Set.of("7.21.0"), versions);
		for (Path f : javaFiles(MAIN.getParent().getParent())) {
			String s = Files.readString(f, StandardCharsets.UTF_8);
			assertFalse(s.contains("io.reliza.model.tea.Rebom") || s.contains("io.reliza.model.tea.Link;"), f + " imports a moved class");
		}
	}
}
