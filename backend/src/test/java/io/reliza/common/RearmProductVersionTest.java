/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.cyclonedx.Version;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FDAW-2 (B15): every generated document names the version of the binary that produced it, and never
 * a constant. Two tool entries: ReARM with the deployment's REARM_PRODUCT_VERSION, rearm-backend with
 * build.version of META-INF/build-info.properties; each without a version when it is not known.
 * Design T8.
 */
class RearmProductVersionTest {

	@TempDir
	Path tmp;

	@Test
	void theDeploymentsVersionWins() {
		assertEquals("26.10.28", Utils.rearmProductVersion("26.10.28"));
		assertEquals("26.10.28", Utils.rearmProductVersion("  26.10.28\n"));
	}

	@Test
	void anUnsetOrBlankVersionIsNoVersion() {
		assertNull(Utils.rearmProductVersion(null));
		assertNull(Utils.rearmProductVersion(""));
		assertNull(Utils.rearmProductVersion("   "));
	}

	@Test
	void utilsCarriesNoFallbackVersion() throws IOException {
		assertThrows(NoSuchFieldException.class, () -> Utils.class.getDeclaredField("REARM_PRODUCT_VERSION_FALLBACK"));
		// The test runs from backend/ (surefire's working directory).
		String source = Files.readString(Path.of("src/main/java/io/reliza/common/Utils.java"), StandardCharsets.UTF_8);
		assertFalse(source.contains("26.08.95"), "Utils.java names a literal ReARM version");
	}

	@Test
	void withTheDeploymentsVersion_theReArmEntryNamesIt() throws Exception {
		Bom bom = bomWith("26.11.2", null);
		assertEquals("26.11.2", tool(bom, Utils.REARM_TOOL_NAME).getVersion());
		assertSchemaValid(bom);
	}

	@Test
	void withoutTheDeploymentsVersion_theReArmEntryHasNoVersion() throws Exception {
		Bom bom = bomWith(null, null);
		assertNull(tool(bom, Utils.REARM_TOOL_NAME).getVersion());
		assertSchemaValid(bom);
	}

	@Test
	void theBackendEntryIsAlwaysThere_withTheBuildVersionFromTheClasspath() throws Exception {
		String buildVersion = Utils.buildVersionOn(loaderWith("build.version=26.11.2-backend.7\n"));
		assertEquals("26.11.2-backend.7", buildVersion);
		Bom bom = bomWith("26.11.2", buildVersion);
		Component backend = tool(bom, Utils.REARM_BACKEND_TOOL_NAME);
		assertEquals(Utils.REARM_TOOL_GROUP, backend.getGroup());
		assertEquals("26.11.2-backend.7", backend.getVersion());
		assertEquals(2, bom.getMetadata().getToolChoice().getComponents().size());
		assertSchemaValid(bom);
	}

	@Test
	void theBackendEntryHasNoVersion_whenTheBuildKnewNone() throws Exception {
		assertNull(Utils.buildVersionOn(new URLClassLoader(new URL[] { tmp.toUri().toURL() }, null)),
				"no properties file");
		for (String content : List.of("build.version=Version_Managed_By_CI_AND_Reliza\n", "build.version=not_versioned\n",
				"build.version=   \n", "build.name=rearm-core\n")) {
			String buildVersion = Utils.buildVersionOn(loaderWith(content));
			assertNull(buildVersion, content);
			Bom bom = bomWith("26.11.2", buildVersion);
			Component backend = tool(bom, Utils.REARM_BACKEND_TOOL_NAME);
			assertEquals(Utils.REARM_TOOL_GROUP, backend.getGroup());
			assertNull(backend.getVersion(), content);
			assertSchemaValid(bom);
		}
	}

	@Test
	void anUnreadableBuildInfoResourceIsNoVersion_notAFailure() {
		ClassLoader broken = new ClassLoader(null) {
			@Override
			public java.io.InputStream getResourceAsStream(String name) {
				if (!Utils.BUILD_INFO_RESOURCE.equals(name)) return null;
				return new java.io.InputStream() {
					@Override
					public int read() throws IOException {
						throw new IOException("boom");
					}
				};
			}
		};
		assertNull(Utils.buildVersionOn(broken), "an IOException reading build-info names no version");
	}

	private URLClassLoader loaderWith(String content) throws IOException {
		Path dir = Files.createTempDirectory(tmp, "cp");
		Path file = dir.resolve(Utils.BUILD_INFO_RESOURCE);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.ISO_8859_1);
		// No parent: only this directory's META-INF/build-info.properties is visible, not the build's own.
		return new URLClassLoader(new URL[] { dir.toUri().toURL() }, null);
	}

	private static Bom bomWith(String productVersion, String buildVersion) {
		Bom bom = new Bom();
		Component root = new Component();
		root.setName("demo-app");
		root.setType(Component.Type.APPLICATION);
		root.setVersion("1.0.0");
		Utils.setRearmBomMetadata(bom, root, productVersion, buildVersion);
		return bom;
	}

	private static Component tool(Bom bom, String name) {
		return bom.getMetadata().getToolChoice().getComponents().stream()
				.filter(c -> name.equals(c.getName()))
				.findFirst().orElseThrow(() -> new AssertionError("no tool entry " + name));
	}

	private static void assertSchemaValid(Bom bom) throws Exception {
		String json = BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString();
		List<ParseException> errors = new JsonParser().validate(json.getBytes(StandardCharsets.UTF_8), Version.VERSION_16);
		assertTrue(errors.isEmpty(), () -> errors + "\n" + json);
	}
}
