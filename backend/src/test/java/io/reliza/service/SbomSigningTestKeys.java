/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import io.reliza.common.Utils;
import io.reliza.model.SystemInfoData.EncProps;
import io.reliza.ws.RelizaConfigProps;
import tools.jackson.databind.JsonNode;

/**
 * Shared material of the SBOM signing key tests (SCORE-32): a real {@link EncryptionService} over a
 * test password, Ed25519 keys from fixed seeds, PEM armour, the RFC 8032 vectors and a log capture.
 */
final class SbomSigningTestKeys {

	static final String PASSWORD = "score-32-test-password";
	static final String OTHER_PASSWORD = "score-32-another-password";
	static final String SALT = "5c0e32a1b2c3d4e5";

	private SbomSigningTestKeys() {}

	/** A real EncryptionService, as setProps builds it from the configuration. */
	static EncryptionService encryptionService(String password) {
		RelizaConfigProps props = new RelizaConfigProps();
		props.setEncryption(new EncProps(password, SALT, password + "-old", SALT));
		EncryptionService es = new EncryptionService();
		es.setProps(props);
		return es;
	}

	static byte[] hex(String s) {
		return HexFormat.of().parseHex(s);
	}

	/** The PKCS#8 DER of the Ed25519 key with this 32-byte seed, as the JDK encodes it. */
	static byte[] pkcs8FromSeed(byte[] seed) throws GeneralSecurityException {
		return KeyFactory.getInstance("Ed25519")
				.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)).getEncoded();
	}

	static String pem(String type, byte[] der) {
		return "-----BEGIN " + type + "-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
				+ "\n-----END " + type + "-----\n";
	}

	static String base64Url(byte[] b) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	record Rfc8032Vector(String name, byte[] secretKey, byte[] publicKey, byte[] message, byte[] signature) {}

	/** RFC 8032 section 7.1 TEST 1 to TEST 3, from {@code jsf/rfc8032-vectors.json}. */
	static List<Rfc8032Vector> rfc8032Vectors() throws IOException {
		try (InputStream in = SbomSigningTestKeys.class.getResourceAsStream("/jsf/rfc8032-vectors.json")) {
			JsonNode root = Utils.OM.readTree(in);
			List<Rfc8032Vector> out = new ArrayList<>();
			for (JsonNode v : root.get("vectors")) {
				out.add(new Rfc8032Vector(v.get("name").asString(), hex(v.get("secretKey").asString()),
						hex(v.get("publicKey").asString()), hex(v.get("message").asString()),
						hex(v.get("signature").asString())));
			}
			return out;
		}
	}

	/**
	 * Every event of the named classes' loggers, message and stack trace as text, at any level; and
	 * each message by its level, for a test that counts the lines of one level.
	 */
	static final class LogCapture implements AutoCloseable {
		final List<String> lines = new CopyOnWriteArrayList<>();
		private final List<Map.Entry<Level, String>> messages = new CopyOnWriteArrayList<>();
		private final List<Logger> loggers = new ArrayList<>();
		private final List<Level> levels = new ArrayList<>();
		private final Appender appender;

		LogCapture(Class<?>... classes) {
			appender = new AbstractAppender("sbomSigningKeyTestCapture", null, null, true, Property.EMPTY_ARRAY) {
				@Override
				public void append(LogEvent event) {
					StringWriter sw = new StringWriter();
					sw.append(event.getMessage().getFormattedMessage());
					if (null != event.getThrown()) event.getThrown().printStackTrace(new PrintWriter(sw));
					lines.add(sw.toString());
					messages.add(Map.entry(event.getLevel(), event.getMessage().getFormattedMessage()));
				}
			};
			appender.start();
			for (Class<?> c : classes) {
				Logger logger = (Logger) LogManager.getLogger(c);
				loggers.add(logger);
				levels.add(logger.getLevel());
				logger.addAppender(appender);
				// every level, so an INFO line is seen whatever the test configuration filters; after
				// addAppender, which rebuilds the logger's configuration and would drop the level
				logger.setLevel(Level.ALL);
			}
		}

		String text() {
			return String.join("\n", lines);
		}

		/** The messages logged at {@code level}, without stack traces, in order. */
		List<String> at(Level level) {
			return messages.stream().filter(m -> level.equals(m.getKey())).map(Map.Entry::getValue).toList();
		}

		@Override
		public void close() {
			for (int i = 0; i < loggers.size(); i++) {
				loggers.get(i).removeAppender(appender);
				loggers.get(i).setLevel(levels.get(i));
			}
			appender.stop();
		}
	}
}
