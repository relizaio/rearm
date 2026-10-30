/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.SbomExportSigningFixtures.KID_TEST1;
import static io.reliza.service.SbomExportSigningFixtures.ORG;
import static io.reliza.service.SbomExportSigningFixtures.RELEASE;
import static io.reliza.service.SbomExportSigningFixtures.unsignedExpectation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyAlgorithm;
import io.reliza.model.SbomSigningKeyData.SbomSigningKeyStatus;
import io.reliza.service.JsfVerifier.Outcome;
import io.reliza.service.SbomExportSigningService.UnsignedReason;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An inbound root {@code signature} is never served on an unsigned export (SCORE-33 T-1, design 3.4
 * step 2): {@code stampAndSign} removes it itself, not relying on the egress sweep having run. The
 * inbound one here is what an uploader could forge: a valid signature under the uploader's own
 * {@code publicKey} that names the org's {@code keyId}, which a reader verifying with the embedded
 * key would take for the org's signature.
 */
class SbomExportSigningServiceInboundSignatureTest {

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
			+ "\"metadata\":{\"properties\":[{\"name\":\"uploader:kept\",\"value\":\"kept\"}]},"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"a\"}]}";

	private final SbomSigningKeyService keys = mock(SbomSigningKeyService.class);
	private final SbomSigner signer = mock(SbomSigner.class);
	private SbomSigningKeyData orgKey;
	private SbomSigningKeyData forgerKey;

	@BeforeEach
	void wire() throws Exception {
		orgKey = SbomExportSigningFixtures.test1Key();
		// RFC 8032 TEST 2's private key under the org's kid: the uploader's key, naming the org's
		forgerKey = SbomExportSigningFixtures.key(1, KID_TEST1, ORG);
		when(signer.algorithm(any())).thenReturn(SbomSigningKeyAlgorithm.Ed25519.name());
	}

	private static ObjectNode doc() {
		return (ObjectNode) Utils.OM.readTree(DOC);
	}

	/** {@link #DOC} carrying a forged root signature that verifies under its embedded publicKey. */
	private ObjectNode forged() {
		SbomSigningKeyService forgerKeys = mock(SbomSigningKeyService.class);
		when(forgerKeys.currentKey(ORG)).thenReturn(Optional.of(forgerKey));
		String signed = new SbomExportSigningService(forgerKeys, SbomExportSigningFixtures.signer())
				.stampAndSign(doc(), ORG, RELEASE);
		ObjectNode tree = (ObjectNode) Utils.OM.readTree(signed);
		JsonNode sig = tree.get("signature");
		assertEquals(orgKey.getKeyId(), sig.get("keyId").asString(), "the forgery names the org's kid");
		assertNotEquals(Utils.OM.valueToTree(SbomSigningKeyService.publicJwk(orgKey)), sig.get("publicKey"),
				"next to the uploader's own publicKey");
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(signed, JsfVerifier.keyFromJwk(forgerKey.getPublicKeyJwk())),
				"and verifies under that embedded key");
		return tree;
	}

	private static void assertServedUnsigned(String emitted, UnsignedReason reason, String what) {
		assertFalse(Utils.OM.readTree(emitted).has("signature"), what + ": " + emitted);
		assertEquals(unsignedExpectation(doc(), reason), emitted, what);
	}

	@Test
	void withoutAKeyAnInboundRootSignatureIsRemoved() {
		when(keys.currentKey(ORG)).thenReturn(Optional.empty());
		String emitted = new SbomExportSigningService(keys, signer).stampAndSign(forged(), ORG, RELEASE);
		assertServedUnsigned(emitted, UnsignedReason.NO_KEY, "NO_KEY");
		verify(signer, never()).sign(any(), any());
	}

	@Test
	void aMetadataThatIsNotAnObjectDropsAnInboundRootSignatureToo() {
		when(keys.currentKey(ORG)).thenReturn(Optional.of(orgKey));
		ObjectNode input = forged();
		input.put("metadata", "x");
		String emitted = new SbomExportSigningService(keys, signer).stampAndSign(input, ORG, RELEASE);
		assertEquals("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,\"metadata\":{\"properties\":"
				+ "[{\"name\":\"reliza:export:signed\",\"value\":\"false\"},{\"name\":\"reliza:export:unsignedReason\","
				+ "\"value\":\"SIGNING_ERROR\"}]},\"components\":[{\"type\":\"library\",\"name\":\"a\"}]}", emitted);
		verify(signer, never()).sign(any(), any());
	}

	@Test
	void everySigningErrorDropsAnInboundRootSignature() throws Exception {
		SbomSigningKeyData revoked = SbomExportSigningFixtures.test1Key();
		revoked.setStatus(SbomSigningKeyStatus.REVOKED);
		SbomSigner failing = mock(SbomSigner.class);
		when(failing.algorithm(any())).thenReturn(SbomSigningKeyAlgorithm.Ed25519.name());
		when(failing.sign(any(), any())).thenThrow(new IllegalStateException("engine broke"));

		Map<String, Supplier<SbomExportSigningService>> paths = new LinkedHashMap<>();
		paths.put("key lookup failure", () -> {
			SbomSigningKeyService throwing = mock(SbomSigningKeyService.class);
			when(throwing.currentKey(ORG)).thenThrow(new IllegalStateException("database unavailable"));
			return new SbomExportSigningService(throwing, signer);
		});
		paths.put("not the current key", () -> new SbomExportSigningService(keysReturning(revoked), signer));
		paths.put("busy", () -> new SbomExportSigningService(keysReturning(orgKey), signer, 0, 1L,
				SbomExportSigningService.MAX_SIGNED_CHARS));
		paths.put("over the size limit", () -> new SbomExportSigningService(keysReturning(orgKey), signer, 1, 1L, 10L));
		paths.put("signer failure", () -> new SbomExportSigningService(keysReturning(orgKey), failing));

		for (var path : paths.entrySet()) {
			String emitted = path.getValue().get().stampAndSign(forged(), ORG, RELEASE);
			assertServedUnsigned(emitted, UnsignedReason.SIGNING_ERROR, path.getKey());
		}
		verify(signer, never()).sign(any(), any());
	}

	private static SbomSigningKeyService keysReturning(SbomSigningKeyData key) {
		SbomSigningKeyService k = mock(SbomSigningKeyService.class);
		when(k.currentKey(ORG)).thenReturn(Optional.of(key));
		return k;
	}
}
