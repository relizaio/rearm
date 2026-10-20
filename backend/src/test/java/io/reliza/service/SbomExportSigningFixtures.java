/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.reliza.common.Utils;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyAlgorithm;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import io.reliza.service.SbomExportSigningService.UnsignedReason;
import io.reliza.service.SbomSigningTestKeys.Rfc8032Vector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared material of the export signing tests (SCORE-33): the RFC 8032 TEST 1 and TEST 2 keys as
 * stored SBOM signing keys with the fixtures' kids, the real {@link LocalKeySigner} over them, the
 * bare document the shared vectors were signed from, and readers of the vectors.
 */
final class SbomExportSigningFixtures {

	static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
	static final UUID RELEASE = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
	static final String KID_TEST1 = "00000000-0000-4000-8000-000000000001";
	static final String KID_TEST2 = "00000000-0000-4000-8000-000000000002";
	static final String X_TEST1 = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo";

	static final EncryptionService ENCRYPTION = SbomSigningTestKeys.encryptionService(SbomSigningTestKeys.PASSWORD);

	private SbomExportSigningFixtures() {}

	// ---- the shared JSF vectors (src/test/resources/jsf, see vectors-origin.txt) -----------------

	static byte[] resource(String path) throws IOException {
		try (InputStream in = SbomExportSigningFixtures.class.getResourceAsStream("/jsf/" + path)) {
			if (null == in) throw new IOException("missing test resource jsf/" + path);
			return in.readAllBytes();
		}
	}

	static String resourceText(String path) throws IOException {
		return new String(resource(path), StandardCharsets.UTF_8);
	}

	/** The document with {@code signature.value} removed: the verifier's signing input before JCS. */
	static ObjectNode withoutValue(String json) {
		ObjectNode doc = (ObjectNode) Utils.OM.readTree(json);
		((ObjectNode) doc.get("signature")).remove("value");
		return doc;
	}

	/** The entry of a JWKS resource with this {@code kid}, as rearm-cli's engine selects it. */
	static JsonNode jwksEntry(String jwksResource, String kid) throws IOException {
		for (JsonNode k : Utils.OM.readTree(resourceText(jwksResource)).get("keys")) {
			if (kid.equals(k.get("kid").asString())) return k;
		}
		throw new AssertionError("no kid " + kid + " in " + jwksResource);
	}

	// ---- keys -------------------------------------------------------------------------------------

	/**
	 * The service as the unit tests of the export path wire it into a {@code ReleaseService} built by
	 * hand: an org without a key, so every JSON export is stamped unsigned with {@code NO_KEY}.
	 */
	static SbomExportSigningService keylessService() {
		return new SbomExportSigningService(mock(SbomSigningKeyService.class), mock(SbomSigner.class));
	}

	static LocalKeySigner signer() {
		return new LocalKeySigner(ENCRYPTION);
	}

	/** RFC 8032 TEST 1 as the CURRENT key {@code ...0001} of {@link #ORG}. */
	static SbomSigningKeyData test1Key() throws Exception {
		return key(0, KID_TEST1, ORG);
	}

	/** RFC 8032 TEST 2 as the CURRENT key {@code ...0002} of {@link #ORG}. */
	static SbomSigningKeyData test2Key() throws Exception {
		return key(1, KID_TEST2, ORG);
	}

	/**
	 * The RFC 8032 vector at {@code index} as a stored key: encrypted PKCS#8, the public JWK written
	 * in an order other than {@code kty, crv, x}, as a record may keep it.
	 */
	static SbomSigningKeyData key(int index, String kid, UUID org) throws Exception {
		Rfc8032Vector v = SbomSigningTestKeys.rfc8032Vectors().get(index);
		SbomSigningKeyData k = new SbomSigningKeyData();
		k.setKeyId(kid);
		k.setOrg(org);
		k.setAlgorithm(SbomSigningKeyAlgorithm.Ed25519);
		k.setStatus(SbomSigningKeyStatus.CURRENT);
		Map<String, String> jwk = new LinkedHashMap<>();
		jwk.put(SbomSigningKeyData.JWK_X, SbomSigningTestKeys.base64Url(v.publicKey()));
		jwk.put(SbomSigningKeyData.JWK_CRV, SbomSigningKeyAlgorithm.Ed25519.name());
		jwk.put(SbomSigningKeyData.JWK_KTY, SbomSigningKeyData.JWK_KTY_OKP);
		k.setPublicKeyJwk(jwk);
		k.setEncryptedPrivateKey(ENCRYPTION.encrypt(
				Base64.getEncoder().encodeToString(SbomSigningTestKeys.pkcs8FromSeed(v.secretKey()))));
		return k;
	}

	/**
	 * The document {@code signed-ed25519.cdx.json} was signed from: the fixture read as a tree with
	 * {@code signature} removed and its two {@code reliza:export:*} entries removed from
	 * {@code metadata.properties}, the empty array kept in place.
	 */
	static ObjectNode bareFixture() throws Exception {
		ObjectNode doc = (ObjectNode) Utils.OM.readTree(resourceText("signed-ed25519.cdx.json"));
		doc.remove("signature");
		((ArrayNode) doc.get("metadata").get("properties")).removeAll();
		return doc;
	}

	/** The names of {@code metadata.properties}, in order. */
	static List<String> metadataPropertyNames(JsonNode doc) {
		List<String> names = new ArrayList<>();
		JsonNode props = doc.path("metadata").path("properties");
		for (JsonNode p : props) names.add(p.get("name").asString());
		return names;
	}

	/** The value of the {@code metadata.properties} entry {@code name}, or null. */
	static String metadataProperty(JsonNode doc, String name) {
		for (JsonNode p : doc.path("metadata").path("properties")) {
			if (name.equals(p.get("name").asString())) return p.get("value").asString();
		}
		return null;
	}

	/**
	 * {@code input} as an unsigned export serves it: the two records appended to its
	 * {@code metadata.properties}, signed {@code false} and the {@code reason}. Edits {@code input}.
	 */
	static String unsignedExpectation(ObjectNode input, UnsignedReason reason) {
		var props = (ArrayNode) input.get("metadata").get("properties");
		props.addObject().put("name", "reliza:export:signed").put("value", "false");
		props.addObject().put("name", "reliza:export:unsignedReason").put("value", reason.name());
		return input.toString();
	}
}
