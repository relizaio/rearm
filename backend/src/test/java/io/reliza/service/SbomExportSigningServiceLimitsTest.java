/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.SbomExportSigningFixtures.ORG;
import static io.reliza.service.SbomExportSigningFixtures.RELEASE;
import static io.reliza.service.SbomExportSigningFixtures.metadataProperty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.service.JsfVerifier.Outcome;
import io.reliza.service.SbomExportSigningService.UnsignedReason;
import io.reliza.service.SbomSigningTestKeys.LogCapture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What bounds the signing and what it refuses (SCORE-33 security review, M-2, L-2, L-3, L-4): a
 * signing that cannot get a permit or a document over the size limit, a key lookup that fails, a
 * number the canonical form would round, and a metadata that is not an object are all served
 * unsigned with {@code SIGNING_ERROR}, never failed and never left without the record.
 */
class SbomExportSigningServiceLimitsTest {

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
			+ "\"metadata\":{\"properties\":[]},\"components\":[{\"type\":\"library\",\"name\":\"a\"}]}";

	private final SbomSigningKeyService keys = mock(SbomSigningKeyService.class);
	private SbomSigningKeyData key;

	@BeforeEach
	void wire() throws Exception {
		key = SbomExportSigningFixtures.test1Key();
		when(keys.currentKey(ORG)).thenReturn(Optional.of(key));
	}

	private static ObjectNode doc() {
		return (ObjectNode) Utils.OM.readTree(DOC);
	}

	private static void assertSigningError(String emitted, String what) {
		JsonNode doc = Utils.OM.readTree(emitted);
		assertEquals("false", metadataProperty(doc, "reliza:export:signed"), what + ": " + emitted);
		assertEquals(UnsignedReason.SIGNING_ERROR.name(), metadataProperty(doc, "reliza:export:unsignedReason"), what);
		assertNull(metadataProperty(doc, "reliza:export:signatureKeyId"), what);
		assertFalse(doc.has("signature"), what);
	}

	private void assertSigned(String emitted, String what) {
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(emitted, JsfVerifier.keyFromJwk(key.getPublicKeyJwk())),
				what + ": " + emitted);
	}

	@Test
	void aSigningThatCannotGetAPermitIsServedUnsignedBusy() {
		LocalKeySigner real = SbomExportSigningFixtures.signer();
		AtomicReference<SbomExportSigningService> service = new AtomicReference<>();
		AtomicReference<String> whileHeld = new AtomicReference<>();
		SbomSigner holding = new SbomSigner() {
			@Override
			public byte[] sign(byte[] canonical, SbomSigningKeyData k) {
				// the one permit is held by this signing: a second export waits, then gives up
				if (null == whileHeld.get()) whileHeld.set(service.get().stampAndSign(doc(), ORG, RELEASE));
				return real.sign(canonical, k);
			}

			@Override
			public String algorithm(SbomSigningKeyData k) {
				return real.algorithm(k);
			}
		};
		service.set(new SbomExportSigningService(keys, holding, 1, 50L, SbomExportSigningService.MAX_SIGNED_CHARS));
		try (LogCapture logs = new LogCapture(SbomExportSigningService.class)) {
			String outer = service.get().stampAndSign(doc(), ORG, RELEASE);
			assertSigned(outer, "the export holding the permit");
			assertSigningError(whileHeld.get(), "the export that found none");
			assertEquals(1, logs.at(Level.ERROR).size(), logs.text());
			assertTrue(logs.at(Level.ERROR).get(0).contains("cause BUSY"), logs.text());
		}
		// and the permit came back
		assertSigned(service.get().stampAndSign(doc(), ORG, RELEASE), "after the permit was released");
	}

	@Test
	void aPermitIsReleasedWhenSigningFails() {
		LocalKeySigner real = SbomExportSigningFixtures.signer();
		AtomicInteger calls = new AtomicInteger();
		SbomSigner failingOnce = new SbomSigner() {
			@Override
			public byte[] sign(byte[] canonical, SbomSigningKeyData k) {
				if (0 == calls.getAndIncrement()) throw new IllegalStateException("first call fails");
				return real.sign(canonical, k);
			}

			@Override
			public String algorithm(SbomSigningKeyData k) {
				return real.algorithm(k);
			}
		};
		SbomExportSigningService service = new SbomExportSigningService(keys, failingOnce, 1, 50L,
				SbomExportSigningService.MAX_SIGNED_CHARS);
		assertSigningError(service.stampAndSign(doc(), ORG, RELEASE), "the failed signing");
		assertSigned(service.stampAndSign(doc(), ORG, RELEASE), "the next one gets the permit back");
	}

	@Test
	void aDocumentOverTheSizeLimitIsServedUnsignedAndNotCanonicalised() {
		SbomSigner signer = spy(SbomExportSigningFixtures.signer());
		SbomExportSigningService service = new SbomExportSigningService(keys, signer, 1, 50L, 200L);
		try (LogCapture logs = new LogCapture(SbomExportSigningService.class)) {
			assertSigningError(service.stampAndSign(doc(), ORG, RELEASE), "over 200 characters");
			assertTrue(logs.at(Level.ERROR).get(0).contains("cause TOO_LARGE"), logs.text());
		}
		verify(signer, never()).sign(any(), any());
		assertTrue(SbomExportSigningService.MAX_SIGNED_CHARS > 52_428_800L,
				"a document at rebom's 50 MB request limit is still signed");
	}

	@Test
	void aNumberTheCanonicalFormWouldRoundIsNotSigned() {
		SbomSigner signer = spy(SbomExportSigningFixtures.signer());
		SbomExportSigningService service = new SbomExportSigningService(keys, signer);
		BigInteger twoTo53 = BigInteger.TWO.pow(53);
		List<Consumer<ObjectNode>> inexact = List.of(
				d -> d.put("big", twoTo53.longValue() + 1),
				d -> d.put("big", twoTo53.negate().subtract(BigInteger.ONE)),
				d -> d.put("big", new BigInteger("295147905179352825856")),
				d -> d.put("dec", new BigDecimal("0.1000000000000000000001")));
		for (var edit : inexact) {
			ObjectNode d = doc();
			edit.accept((ObjectNode) d.get("components").get(0));
			try (LogCapture logs = new LogCapture(SbomExportSigningService.class)) {
				assertSigningError(service.stampAndSign(d, ORG, RELEASE), d.get("components").toString());
				assertTrue(logs.at(Level.ERROR).get(0).contains("cause NOT_CANONICALIZABLE"), logs.text());
			}
		}
		verify(signer, never()).sign(any(), any());

		ObjectNode exact = doc();
		ObjectNode c = (ObjectNode) exact.get("components").get(0);
		c.put("max", twoTo53.longValue()).put("min", twoTo53.negate()).put("double", 0.1)
				.put("dec", new BigDecimal("0.5")).put("small", 7);
		assertSigned(service.stampAndSign(exact, ORG, RELEASE), "numbers every double reader holds exactly");
	}

	@Test
	void aKeyLookupFailureIsServedUnsignedSigningError() {
		when(keys.currentKey(ORG)).thenThrow(new IllegalStateException("database unavailable"));
		SbomSigner signer = mock(SbomSigner.class);
		String emitted = new SbomExportSigningService(keys, signer).stampAndSign(doc(), ORG, RELEASE);
		assertSigningError(emitted, "key lookup failed");
		verify(signer, never()).sign(any(), any());
	}

	@Test
	void aMetadataThatIsNotAnObjectIsReplacedByTheUnsignedRecord() {
		SbomSigner signer = mock(SbomSigner.class);
		ObjectNode d = (ObjectNode) Utils.OM.readTree("{\"bomFormat\":\"CycloneDX\",\"metadata\":\"x\",\"components\":[]}");
		String emitted = new SbomExportSigningService(keys, signer).stampAndSign(d, ORG, RELEASE);
		assertEquals("{\"bomFormat\":\"CycloneDX\",\"metadata\":{\"properties\":[{\"name\":\"reliza:export:signed\","
				+ "\"value\":\"false\"},{\"name\":\"reliza:export:unsignedReason\",\"value\":\"SIGNING_ERROR\"}]},"
				+ "\"components\":[]}", emitted);
		verify(signer, never()).sign(any(), any());
	}
}
