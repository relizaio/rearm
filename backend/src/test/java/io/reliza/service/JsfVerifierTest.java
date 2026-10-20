/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.SbomExportSigningFixtures.KID_TEST1;
import static io.reliza.service.SbomExportSigningFixtures.jwksEntry;
import static io.reliza.service.SbomExportSigningFixtures.resourceText;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.PublicKey;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.service.JsfVerifier.Outcome;
import tools.jackson.databind.JsonNode;

/**
 * The test verifier against rearm-cli's shared JSF fixtures (SCORE-33 T-4): it says what SCORE-34's
 * engine says about each, so the other tests can rely on it.
 */
class JsfVerifierTest {

	private static PublicKey pubPem() throws Exception {
		return JsfVerifier.keyFromPem(resourceText("pub.pem"));
	}

	@Test
	void signedEd25519VerifiesWithPubPemAndWithTheEmbeddedKey() throws Exception {
		String doc = resourceText("signed-ed25519.cdx.json");
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(doc, pubPem()));
		assertEquals(Outcome.VERIFIED, JsfVerifier.verifyEmbedded(doc));
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify("\uFEFF" + doc, pubPem()), "a leading BOM is stripped");
	}

	@Test
	void prettyFixtureVerifies() throws Exception {
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(resourceText("signed-ed25519.pretty.cdx.json"), pubPem()));
	}

	@Test
	void tamperedFails() throws Exception {
		assertEquals(Outcome.FAILED, JsfVerifier.verify(resourceText("tampered.cdx.json"), pubPem()));
	}

	@Test
	void malformedIsReportedMalformed() throws Exception {
		assertEquals(Outcome.MALFORMED, JsfVerifier.verify(resourceText("malformed.cdx.json"), pubPem()));
		assertEquals(Outcome.MALFORMED, JsfVerifier.verify(resourceText("malformed-base64.cdx.json"), pubPem()));
		String padded = resourceText("signed-ed25519.cdx.json").replace("6xiiAA\"", "6xiiAA==\"");
		assertEquals(Outcome.MALFORMED, JsfVerifier.verify(padded, pubPem()), "padding is refused");
		String trailingBits = resourceText("signed-ed25519.cdx.json").replace("6xiiAA\"", "6xiiAB\"");
		assertEquals(Outcome.MALFORMED, JsfVerifier.verify(trailingBits, pubPem()), "non-zero trailing bits are refused");
		String duplicate = resourceText("signed-ed25519.cdx.json").replaceFirst("\\{\"bomFormat\":\"CycloneDX\",",
				"{\"bomFormat\":\"CycloneDX\",\"bomFormat\":\"CycloneDX\",");
		assertEquals(Outcome.MALFORMED, JsfVerifier.verify(duplicate, pubPem()), "a duplicate member name");
	}

	@Test
	void signedEs256VerifiesWithPubEs256Pem() throws Exception {
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(resourceText("signed-es256.cdx.json"),
				JsfVerifier.keyFromPem(resourceText("pub-es256.pem"))));
		assertEquals(Outcome.VERIFIED, JsfVerifier.verifyEmbedded(resourceText("signed-es256.cdx.json")));
	}

	@Test
	void embeddedWrongKeyIsKeyMismatch() throws Exception {
		assertEquals(Outcome.KEY_MISMATCH, JsfVerifier.verify(resourceText("embedded-wrong-key.cdx.json"), pubPem()));
	}

	@Test
	void keyIdOnlyVerifiesWithTheKeyFromKeysJwksByKid() throws Exception {
		PublicKey k = JsfVerifier.keyFromJwk(jwksEntry("keys.jwks.json", KID_TEST1));
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(resourceText("keyid-only.cdx.json"), k));
	}

	@Test
	void wrongKeyJwksIsKeyMismatch() throws Exception {
		PublicKey wrong = JsfVerifier.keyFromJwk(jwksEntry("wrong-key.jwks.json", KID_TEST1));
		assertEquals(Outcome.KEY_MISMATCH, JsfVerifier.verify(resourceText("signed-ed25519.cdx.json"), wrong));
		assertEquals(Outcome.FAILED, JsfVerifier.verify(resourceText("keyid-only.cdx.json"), wrong),
				"no embedded key to compare: the signature itself fails");
	}

	@Test
	void unsignedIsMalformed() throws Exception {
		assertEquals(Outcome.MALFORMED, JsfVerifier.verify(resourceText("unsigned.cdx.json"), pubPem()));
	}

	@Test
	void keyFromPemAndKeyFromJwkAgree() throws Exception {
		JsonNode jwk = Utils.OM.readTree(resourceText("pub.jwk.json"));
		assertArrayEquals(pubPem().getEncoded(), JsfVerifier.keyFromJwk(jwk).getEncoded());
	}
}
