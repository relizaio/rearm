/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.SbomExportSigningFixtures.resource;
import static io.reliza.service.SbomExportSigningFixtures.resourceText;
import static io.reliza.service.SbomExportSigningFixtures.withoutValue;
import static io.reliza.service.SbomExportSigningFixtures.ORG;
import static io.reliza.service.SbomExportSigningFixtures.RELEASE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.service.JsfVerifier.Outcome;

/**
 * The cross-language proof (SCORE-33 ADR-16, section 3.9): what ReARM emits for the document the
 * shared vectors were signed from is byte for byte what rearm-cli's generator wrote, so the Go engine
 * verifies ReARM's bytes by construction.
 */
class SbomExportSigningVectorsTest {

	private static SbomExportSigningService serviceWith(SbomSigningKeyData key) {
		SbomSigningKeyService keys = mock(SbomSigningKeyService.class);
		when(keys.currentKey(ORG)).thenReturn(Optional.ofNullable(key));
		return new SbomExportSigningService(keys, SbomExportSigningFixtures.signer());
	}

	@Test
	void theBareFixtureSignedWithTheRfc8032Test1KeyIsByteIdenticalToSignedEd25519Fixture() throws Exception {
		String emitted = serviceWith(SbomExportSigningFixtures.test1Key())
				.stampAndSign(SbomExportSigningFixtures.bareFixture(), ORG, RELEASE);
		assertEquals(resourceText("signed-ed25519.cdx.json"), emitted);
		assertArrayEquals(resource("canonical/signed-ed25519.jcs"), JsfCanonicalizer.canonicalize(withoutValue(emitted)));
	}

	@Test
	void theSignerReproducesTheFixtureValueOverTheCommittedCanonicalBytes() throws Exception {
		byte[] sig = SbomExportSigningFixtures.signer().sign(resource("canonical/signed-ed25519.jcs"),
				SbomExportSigningFixtures.test1Key());
		String fixtureValue = Utils.OM.readTree(resourceText("signed-ed25519.cdx.json")).get("signature").get("value")
				.asString();
		assertEquals(fixtureValue, SbomSigningTestKeys.base64Url(sig));
	}

	@Test
	void theEmittedFixtureVerifiesUnderKeysJwksByKid() throws Exception {
		String emitted = serviceWith(SbomExportSigningFixtures.test1Key())
				.stampAndSign(SbomExportSigningFixtures.bareFixture(), ORG, RELEASE);
		var entry = SbomExportSigningFixtures.jwksEntry("keys.jwks.json", SbomExportSigningFixtures.KID_TEST1);
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(emitted, JsfVerifier.keyFromJwk(entry)));
	}

	@Test
	void theBareFixtureWithoutAKeyIsByteIdenticalToUnsignedFixture() throws Exception {
		String emitted = serviceWith(null).stampAndSign(SbomExportSigningFixtures.bareFixture(), ORG, RELEASE);
		assertEquals(resourceText("unsigned.cdx.json"), emitted);
		assertEquals(resource("unsigned.cdx.json").length, emitted.getBytes(StandardCharsets.UTF_8).length);
	}
}
