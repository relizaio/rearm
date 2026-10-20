/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.SbomExportSigningFixtures.ORG;
import static io.reliza.service.SbomExportSigningFixtures.RELEASE;
import static io.reliza.service.SbomExportSigningFixtures.metadataProperty;
import static io.reliza.service.SbomExportSigningFixtures.metadataPropertyNames;
import static io.reliza.service.SbomExportSigningFixtures.unsignedExpectation;
import static io.reliza.service.SbomExportSigningFixtures.withoutValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.Utils;
import io.reliza.exceptions.SbomSigningException;
import io.reliza.exceptions.SbomSigningException.SbomSigningErrorReason;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyAlgorithm;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import io.reliza.service.SbomExportSigningService.UnsignedReason;
import io.reliza.service.JsfVerifier.Outcome;
import io.reliza.service.SbomSigningTestKeys.LogCapture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stamp and the signature at egress (SCORE-33 T-10 to T-13): what a merged JSON export carries
 * with a current key, without one, and when signing fails; and what never changes it.
 */
class SbomExportSigningServiceTest {

	/** A merged-shaped document with rebom's and the injector's records already on metadata. */
	private static final String MERGED = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"serialNumber\":\"urn:uuid:00000000-0000-4000-8000-0000000000aa\",\"version\":1,"
			+ "\"metadata\":{\"timestamp\":\"2026-10-14T00:00:00Z\","
			+ "\"component\":{\"type\":\"application\",\"bom-ref\":\"pkg:generic/ex/app@1.0.0\",\"name\":\"app\"},"
			+ "\"properties\":[{\"name\":\"reliza:export:fileComponentsExcluded\",\"value\":\"2\"},"
			+ "{\"name\":\"reliza:support:disclosure\",\"value\":\"current-state\"}],"
			+ "\"lifecycles\":[{\"phase\":\"build\"}]},"
			+ "\"components\":[{\"type\":\"library\",\"bom-ref\":\"pkg:npm/a@1.0.0\",\"name\":\"a\",\"version\":\"1.0.0\","
			+ "\"description\":\"caf\\u00e9 & <x> \\u001f /\",\"purl\":\"pkg:npm/a@1.0.0\"}],"
			+ "\"dependencies\":[{\"ref\":\"pkg:generic/ex/app@1.0.0\",\"dependsOn\":[\"pkg:npm/a@1.0.0\"]}]}";

	private final SbomSigningKeyService keys = mock(SbomSigningKeyService.class);
	private LocalKeySigner signer;
	private SbomSigningKeyData key;
	private SbomExportSigningService service;

	@BeforeEach
	void wire() throws Exception {
		signer = spy(SbomExportSigningFixtures.signer());
		key = SbomExportSigningFixtures.test1Key();
		when(keys.currentKey(ORG)).thenReturn(Optional.of(key));
		service = new SbomExportSigningService(keys, signer);
	}

	private static ObjectNode merged() {
		return (ObjectNode) Utils.OM.readTree(MERGED);
	}

	private static List<String> rootNames(JsonNode doc) {
		List<String> names = new ArrayList<>();
		doc.propertyNames().forEach(names::add);
		return names;
	}

	// ---- T-10 ------------------------------------------------------------------------------------

	@Test
	void withACurrentKeyTheDocumentIsStampedAndSigned() {
		ObjectNode input = merged();
		String emitted = service.stampAndSign(input, ORG, RELEASE);
		JsonNode doc = Utils.OM.readTree(emitted);

		assertEquals(List.of("reliza:export:fileComponentsExcluded", "reliza:support:disclosure",
				"reliza:export:signed", "reliza:export:signatureKeyId"), metadataPropertyNames(doc));
		assertEquals("true", metadataProperty(doc, "reliza:export:signed"));
		assertEquals(key.getKeyId(), metadataProperty(doc, "reliza:export:signatureKeyId"));
		assertNull(metadataProperty(doc, "reliza:export:unsignedReason"));

		List<String> root = rootNames(doc);
		assertEquals("signature", root.get(root.size() - 1), "signature is the last root member");
		assertEquals(List.of("bomFormat", "specVersion", "serialNumber", "version", "metadata", "components",
				"dependencies", "signature"), root);
		JsonNode sig = doc.get("signature");
		assertEquals(List.of("algorithm", "keyId", "publicKey", "value"), rootNames(sig));
		assertEquals("Ed25519", sig.get("algorithm").asString());
		assertEquals(key.getKeyId(), sig.get("keyId").asString());
		assertEquals(List.of("kty", "crv", "x"), rootNames(sig.get("publicKey")), "publicJwk order, not the record's");
		assertEquals(SbomExportSigningFixtures.X_TEST1, sig.get("publicKey").get("x").asString());
		assertTrue(sig.get("value").asString().matches("[A-Za-z0-9_-]{86}"), "base64url, no padding, 64 bytes");

		// nothing else changed
		ObjectNode expected = merged();
		ObjectNode actual = (ObjectNode) doc.deepCopy();
		actual.remove("signature");
		var props = (ArrayNode) actual.get("metadata").get("properties");
		props.remove(3);
		props.remove(2);
		assertEquals(expected.toString(), actual.toString());

		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(emitted, JsfVerifier.keyFromJwk(key.getPublicKeyJwk())));
		assertEquals(Outcome.VERIFIED, JsfVerifier.verifyEmbedded(emitted));
	}

	@Test
	void thePropertiesAreInsideTheSignedPayload() {
		String emitted = service.stampAndSign(merged(), ORG, RELEASE);
		var pub = JsfVerifier.keyFromJwk(key.getPublicKeyJwk());
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(emitted, pub));

		ObjectNode dropped = (ObjectNode) Utils.OM.readTree(emitted);
		var props = (ArrayNode) dropped.get("metadata").get("properties");
		props.remove(2);
		assertEquals(Outcome.FAILED, JsfVerifier.verify(dropped, pub), "reliza:export:signed removed");

		ObjectNode changed = (ObjectNode) Utils.OM.readTree(emitted);
		((ObjectNode) changed.get("metadata").get("properties").get(3)).put("value", "another-key");
		assertEquals(Outcome.FAILED, JsfVerifier.verify(changed, pub), "signatureKeyId changed");

		ObjectNode keyIdChanged = (ObjectNode) Utils.OM.readTree(emitted);
		((ObjectNode) keyIdChanged.get("signature")).put("keyId", "another-key");
		assertEquals(Outcome.FAILED, JsfVerifier.verify(keyIdChanged, pub), "signature.keyId is covered");
	}

	@Test
	void aForgedSignedPropertyAnywhereIsReplaced() {
		ObjectNode input = merged();
		ObjectNode component = (ObjectNode) input.get("components").get(0);
		var cprops = component.putArray("properties");
		cprops.addObject().put("name", "reliza:export:signed").put("value", "true");
		cprops.addObject().put("name", "reliza:export:signatureKeyId").put("value", "forged");
		cprops.addObject().put("name", "uploader:kept").put("value", "kept");
		var mprops = (ArrayNode) input.get("metadata").get("properties");
		mprops.addObject().put("name", "reliza:export:signed").put("value", "true");
		mprops.addObject().put("name", "reliza:export:signatureKeyId").put("value", "forged");
		mprops.addObject().put("name", "reliza:export:unsignedReason").put("value", "forged");
		ObjectNode forgedSig = input.putObject("signature");
		forgedSig.put("algorithm", "Ed25519").put("keyId", "forged").put("value", "AAAA");

		String emitted = service.stampAndSign(input, ORG, RELEASE);
		JsonNode doc = Utils.OM.readTree(emitted);
		int signed = 0;
		int keyIds = 0;
		int reasons = 0;
		for (String n : allPropertyNames(doc)) {
			if ("reliza:export:signed".equals(n)) signed++;
			if ("reliza:export:signatureKeyId".equals(n)) keyIds++;
			if ("reliza:export:unsignedReason".equals(n)) reasons++;
		}
		assertEquals(1, signed);
		assertEquals(1, keyIds);
		assertEquals(0, reasons);
		assertEquals(key.getKeyId(), metadataProperty(doc, "reliza:export:signatureKeyId"));
		assertEquals(List.of("uploader:kept"), names(doc.get("components").get(0).get("properties")));
		assertEquals(key.getKeyId(), doc.get("signature").get("keyId").asString());
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(emitted, JsfVerifier.keyFromJwk(key.getPublicKeyJwk())));
	}

	private static List<String> names(JsonNode props) {
		List<String> out = new ArrayList<>();
		for (JsonNode p : props) out.add(p.get("name").asString());
		return out;
	}

	private static List<String> allPropertyNames(JsonNode node) {
		List<String> out = new ArrayList<>();
		if (node.isObject() && node.has("properties") && node.get("properties").isArray()) {
			out.addAll(names(node.get("properties")));
		}
		for (JsonNode child : node) out.addAll(allPropertyNames(child));
		return out;
	}

	@Test
	void theEmittedBytesAreTheDocumentPlusValueNotTheCanonicalForm() {
		String emitted = service.stampAndSign(merged(), ORG, RELEASE);
		ArgumentCaptor<byte[]> signed = ArgumentCaptor.forClass(byte[].class);
		verify(signer).sign(signed.capture(), any());

		JsonNode doc = Utils.OM.readTree(emitted);
		assertEquals(doc.toString(), emitted, "compact Jackson serialisation of the tree");
		assertTrue(emitted.startsWith("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","), "input member order kept");
		assertNotEquals(new String(JsfCanonicalizer.canonicalize(withoutValue(emitted)), StandardCharsets.UTF_8),
				withoutValue(emitted).toString(),
				"the served form is not the canonical form");
		assertArrayEquals(signed.getValue(), JsfCanonicalizer.canonicalize(withoutValue(emitted)),
				"the verifier's input is exactly the bytes the signer was given");
	}

	@Test
	void aNonObjectNodeIsServedUnchanged() {
		JsonNode array = Utils.OM.readTree("[{\"a\":1}]");
		assertEquals("[{\"a\":1}]", service.stampAndSign(array, ORG, RELEASE));
		verifyNoInteractions(keys);
		verifyNoInteractions(signer);
	}

	// ---- T-11 ------------------------------------------------------------------------------------

	@Test
	void withoutAKeyTheDocumentIsStampedUnsignedAndOtherwiseUntouched() {
		UUID keyless = UUID.randomUUID();
		when(keys.currentKey(keyless)).thenReturn(Optional.empty());
		try (LogCapture logs = new LogCapture(SbomExportSigningService.class)) {
			String emitted = service.stampAndSign(merged(), keyless, RELEASE);

			assertEquals(unsignedExpectation(merged(), UnsignedReason.NO_KEY), emitted);
			assertFalse(Utils.OM.readTree(emitted).has("signature"));
			verifyNoInteractions(signer);
			assertEquals(1, logs.at(Level.INFO).size(), logs.lines.toString());
			assertTrue(logs.at(Level.INFO).get(0).contains("SBOM_EXPORT_UNSIGNED release " + RELEASE + " org " + keyless
					+ " reason NO_KEY"), logs.lines.toString());
		}
	}

	@Test
	void aDocumentWithoutMetadataGetsOne() {
		when(keys.currentKey(ORG)).thenReturn(Optional.empty());
		String emitted = service.stampAndSign(Utils.OM.readTree("{\"bomFormat\":\"CycloneDX\"}"), ORG, RELEASE);
		assertEquals("{\"bomFormat\":\"CycloneDX\",\"metadata\":{\"properties\":[{\"name\":\"reliza:export:signed\","
				+ "\"value\":\"false\"},{\"name\":\"reliza:export:unsignedReason\",\"value\":\"NO_KEY\"}]}}", emitted);
	}

	// ---- T-12 ------------------------------------------------------------------------------------

	private SbomExportSigningService withSigner(SbomSigner s) {
		return new SbomExportSigningService(keys, s);
	}

	private static SbomSigner failingSigner(RuntimeException e) {
		SbomSigner s = mock(SbomSigner.class);
		when(s.algorithm(any())).thenReturn(SbomSigningKeyAlgorithm.Ed25519.name());
		when(s.sign(any(), any())).thenThrow(e);
		return s;
	}

	@Test
	void aSignerFailureServesTheDocumentUnsignedWithSigningError() {
		SbomSigner failing = failingSigner(new SbomSigningException(SbomSigningErrorReason.KEY_UNREADABLE,
				"SBOM signing key " + key.getKeyId() + " could not be read as an Ed25519 private key"));
		try (LogCapture logs = new LogCapture(SbomExportSigningService.class)) {
			String emitted = withSigner(failing).stampAndSign(merged(), ORG, RELEASE);
			assertEquals(unsignedExpectation(merged(), UnsignedReason.SIGNING_ERROR), emitted);
			JsonNode doc = Utils.OM.readTree(emitted);
			assertFalse(doc.has("signature"));
			assertNull(metadataProperty(doc, "reliza:export:signatureKeyId"));

			assertEquals(1, logs.at(Level.ERROR).size(), logs.lines.toString());
			String error = logs.at(Level.ERROR).get(0);
			for (String part : List.of(RELEASE.toString(), ORG.toString(), key.getKeyId(), "SIGNING_ERROR",
					"KEY_UNREADABLE")) {
				assertTrue(error.contains(part), part + " missing from " + error);
			}
			assertNoKeyMaterial(logs);
		}
	}

	/** No captured line or stack trace carries the ciphertext, the PKCS#8 or the seed. */
	private void assertNoKeyMaterial(LogCapture logs) {
		String pkcs8 = SbomExportSigningFixtures.ENCRYPTION.decrypt(key.getEncryptedPrivateKey());
		String text = logs.text();
		assertFalse(text.contains(key.getEncryptedPrivateKey()), "ciphertext logged");
		assertFalse(text.contains(pkcs8), "PKCS#8 logged");
		assertFalse(text.contains("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"), "seed logged");
		assertTrue(text.contains("at io.reliza.service."), "the DEBUG stack trace was captured and scanned");
	}

	@Test
	void aCanonicalisationFailureIsSigningErrorToo() {
		SbomSigner s = mock(SbomSigner.class);
		when(s.algorithm(any())).thenReturn(SbomSigningKeyAlgorithm.Ed25519.name());
		ObjectNode input = merged();
		((ObjectNode) input.get("components").get(0)).put("description", "lone " + '\uD800');
		ObjectNode expectedInput = merged();
		((ObjectNode) expectedInput.get("components").get(0)).put("description", "lone " + '\uD800');
		try (LogCapture logs = new LogCapture(SbomExportSigningService.class)) {
			String emitted = withSigner(s).stampAndSign(input, ORG, RELEASE);
			assertEquals(unsignedExpectation(expectedInput, UnsignedReason.SIGNING_ERROR), emitted);
			verify(s, never()).sign(any(), any());
			assertEquals(1, logs.at(Level.ERROR).size(), logs.lines.toString());
			assertTrue(logs.at(Level.ERROR).get(0).contains("NOT_CANONICALIZABLE"), logs.lines.toString());
		}
	}

	@Test
	void anUnexpectedRuntimeExceptionIsSigningErrorToo() {
		String emitted = withSigner(failingSigner(new IllegalStateException("engine broke")))
				.stampAndSign(merged(), ORG, RELEASE);
		assertEquals(unsignedExpectation(merged(), UnsignedReason.SIGNING_ERROR), emitted);
	}

	@Test
	void anErrorIsNotSwallowed() {
		SbomSigner s = mock(SbomSigner.class);
		when(s.algorithm(any())).thenReturn(SbomSigningKeyAlgorithm.Ed25519.name());
		when(s.sign(any(), any())).thenThrow(new OutOfMemoryError("test"));
		assertThrows(OutOfMemoryError.class, () -> withSigner(s).stampAndSign(merged(), ORG, RELEASE));
	}

	@Test
	void aKeyThatIsNotTheCurrentKeyOfTheExportingOrgNeverSigns() throws Exception {
		SbomSigningKeyData foreign = SbomExportSigningFixtures.key(0, SbomExportSigningFixtures.KID_TEST1,
				UUID.randomUUID());
		SbomSigningKeyData retired = SbomExportSigningFixtures.test1Key();
		retired.setStatus(SbomSigningKeyStatus.RETIRED);
		SbomSigningKeyData revoked = SbomExportSigningFixtures.test1Key();
		revoked.setStatus(SbomSigningKeyStatus.REVOKED);
		for (SbomSigningKeyData wrong : List.of(foreign, retired, revoked)) {
			when(keys.currentKey(ORG)).thenReturn(Optional.of(wrong));
			String emitted = service.stampAndSign(merged(), ORG, RELEASE);
			assertEquals(unsignedExpectation(merged(), UnsignedReason.SIGNING_ERROR), emitted, wrong.getStatus() + " " + wrong.getOrg());
		}
		verify(signer, never()).sign(any(), any());
	}

	// ---- T-13 ------------------------------------------------------------------------------------

	@Test
	void twoStampsOfEqualDocumentsAreByteIdentical() {
		assertEquals(service.stampAndSign(merged(), ORG, RELEASE), service.stampAndSign(merged(), ORG, RELEASE));
	}

	@Test
	void aRotationChangesKeyIdPublicKeyAndValue() throws Exception {
		String first = service.stampAndSign(merged(), ORG, RELEASE);
		SbomSigningKeyData second = SbomExportSigningFixtures.test2Key();
		when(keys.currentKey(ORG)).thenReturn(Optional.of(second));
		String rotated = service.stampAndSign(merged(), ORG, RELEASE);

		JsonNode a = Utils.OM.readTree(first);
		JsonNode b = Utils.OM.readTree(rotated);
		for (String member : List.of("keyId", "publicKey", "value")) {
			assertNotEquals(a.get("signature").get(member), b.get("signature").get(member), member);
		}
		assertEquals(SbomExportSigningFixtures.KID_TEST2, metadataProperty(b, "reliza:export:signatureKeyId"));
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(first, JsfVerifier.keyFromJwk(key.getPublicKeyJwk())));
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(rotated, JsfVerifier.keyFromJwk(second.getPublicKeyJwk())));
		assertEquals(Outcome.KEY_MISMATCH, JsfVerifier.verify(rotated, JsfVerifier.keyFromJwk(key.getPublicKeyJwk())));

		ObjectNode restA = (ObjectNode) a.deepCopy();
		ObjectNode restB = (ObjectNode) b.deepCopy();
		restA.remove("signature");
		restB.remove("signature");
		((ObjectNode) restA.get("metadata").get("properties").get(3)).remove("value");
		((ObjectNode) restB.get("metadata").get("properties").get(3)).remove("value");
		assertEquals(restA.toString(), restB.toString(), "the rest of the document is byte-equal");
		assertEquals(64, Base64.getUrlDecoder().decode(b.get("signature").get("value").asString()).length);
	}
}
