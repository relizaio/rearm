/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The startup error that tells an operator they are running on the shipped password works by
 * comparing the resolved password against {@code relizaprops.encryption.shippedDefault}. That only
 * holds while the password actually defaults to it, through the nested placeholder in the yaml --
 * a one-character edit there would disable the warning and nothing else would notice.
 *
 * <p>Reads the real file rather than a fixture, because a fixture would drift from it.
 */
public class ShippedDefaultResolutionTest {

	/**
	 * The yaml, and nothing else.
	 *
	 * <p>A StandardEnvironment carries the real process environment and system properties ahead of
	 * anything added to it, so a developer who happens to export RELIZAPROP_PASS -- from a local
	 * .env, say -- would watch this fail and reasonably conclude the check was broken. What is
	 * under test is the file, so the file is all this reads; an override is supplied explicitly by
	 * the one test that wants one.
	 */
	private static StandardEnvironment environmentFromApplicationYaml(PropertySource<?>... overrides)
			throws IOException {
		FileSystemResource yaml = new FileSystemResource("application.yaml");
		assertTrue(yaml.exists(), "application.yaml not found; run from the backend module");
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		// First wins for placeholder resolution, so an explicit override goes ahead of the file.
		for (PropertySource<?> override : overrides) env.getPropertySources().addLast(override);
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", yaml);
		sources.forEach(s -> env.getPropertySources().addLast(s));
		return env;
	}

	@Test
	public void theEncryptionPasswordDefaultsToTheDeclaredShippedValue() throws IOException {
		StandardEnvironment env = environmentFromApplicationYaml();
		String shipped = env.getProperty("relizaprops.encryption.shippedDefault");
		assertNotNull(shipped, "relizaprops.encryption.shippedDefault must exist for the warning to work");

		// With RELIZAPROP_PASS unset -- which is the case under test, since the warning only fires
		// for an installation that set nothing -- the password must resolve to that same value.
		assertEquals(shipped, env.getProperty("relizaprops.encryption.password"),
				"the shipped password must be recognisable as the default, or the startup warning is dead code");
		assertEquals(shipped, env.getProperty("relizaprops.encryption.oldpassword"));
	}

	@Test
	public void theSaltDefaultsTheSameWay() throws IOException {
		StandardEnvironment env = environmentFromApplicationYaml();
		String shippedSalt = env.getProperty("relizaprops.encryption.shippedDefaultSalt");
		assertNotNull(shippedSalt);
		assertEquals(shippedSalt, env.getProperty("relizaprops.encryption.salt"));
		assertEquals(shippedSalt, env.getProperty("relizaprops.encryption.oldsalt"));
	}

	@Test
	public void anOperatorOverrideIsWhatWins() throws IOException {
		// Supplied as a property source rather than by setting a system property: the comparison is
		// against the RESOLVED value whatever route the override takes, and a test that mutates
		// global state has to remember to put it back.
		StandardEnvironment overridden = environmentFromApplicationYaml(new MapPropertySource(
				"operator", Map.of("RELIZAPROP_PASS", "an-operator-chose-this")));

		assertEquals("an-operator-chose-this", overridden.getProperty("relizaprops.encryption.password"));
		// The declared default is still visible, which is what the service compares against; it is
		// simply no longer what is in force, so the startup warning stays quiet.
		assertEquals(environmentFromApplicationYaml().getProperty("relizaprops.encryption.shippedDefault"),
				overridden.getProperty("relizaprops.encryption.shippedDefault"));
	}
}
