/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.SbomExportSigningFixtures.ORG;
import static io.reliza.service.SbomExportSigningFixtures.RELEASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.SbomSigningKeyData;
import io.reliza.service.JsfVerifier.Outcome;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The cost of signing a 50 MB export (SCORE-33 T-13): the largest document rebom's request limit
 * lets through, signed with the real signer. Prints each step's time for the implementation note.
 * Skipped on a JVM with less than 3 GB of heap; run it with {@code -DargLine=-Xmx4g}.
 */
class SbomExportSigningServiceTimingTest {

	private static final long FIFTY_MEGABYTES = 52_428_800L;
	private static final long BUDGET_MILLIS = 20_000L;

	/** A CycloneDX document whose compact serialisation is at least {@code minBytes}. */
	static ObjectNode largeDocument(long minBytes) {
		ObjectNode doc = Utils.OM.createObjectNode();
		doc.put("bomFormat", "CycloneDX").put("specVersion", "1.6").put("version", 1);
		ObjectNode metadata = doc.putObject("metadata");
		metadata.putObject("component").put("type", "application").put("name", "timing").put("version", "1.0.0");
		metadata.putArray("properties").addObject().put("name", "reliza:export:fileComponentsExcluded").put("value", "0");
		ArrayNode components = doc.putArray("components");
		ArrayNode dependencies = doc.putArray("dependencies");
		long size = doc.toString().length();
		int i = 0;
		while (size < minBytes) {
			String purl = "pkg:npm/%40scope-" + (i % 997) + "/component-" + i + "@" + (i % 13) + "." + (i % 7) + ".0";
			ObjectNode c = components.addObject();
			c.put("type", "library").put("bom-ref", purl).put("name", "component-" + i)
					.put("version", (i % 13) + "." + (i % 7) + ".0").put("purl", purl)
					.put("description", "Component number " + i + " of the timing document, caf\u00e9 & <tag> / x");
			c.putArray("hashes").addObject().put("alg", "SHA-256")
					.put("content", String.format("%064x", (long) i * 2654435761L));
			c.putArray("licenses").addObject().putObject("license").put("id", "Apache-2.0");
			dependencies.addObject().put("ref", purl).putArray("dependsOn");
			size += purl.length() * 3L + 330;
			i++;
			if (0 == i % 20_000) size = doc.toString().length();
		}
		return doc;
	}

	@Test
	void aFiftyMegabyteDocumentIsSignedWithinBudget() throws Exception {
		Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= 3L << 30,
				"needs at least 3 GB of heap; run with -DargLine=-Xmx4g");
		ObjectNode doc = largeDocument(FIFTY_MEGABYTES);
		SbomSigningKeyData key = SbomExportSigningFixtures.test1Key();
		LocalKeySigner signer = SbomExportSigningFixtures.signer();

		// the steps one by one, on a copy, for the note
		ObjectNode copy = doc.deepCopy();
		long t0 = System.nanoTime();
		String serialised = copy.toString();
		long t1 = System.nanoTime();
		byte[] canonical = JsfCanonicalizer.canonicalize(serialised);
		long t2 = System.nanoTime();
		signer.sign(canonical, key);
		long t3 = System.nanoTime();
		String again = copy.toString();
		long t4 = System.nanoTime();
		assertEquals(serialised.length(), again.length());
		assertTrue(serialised.length() >= FIFTY_MEGABYTES, "document is " + serialised.length() + " chars");

		SbomSigningKeyService keys = mock(SbomSigningKeyService.class);
		when(keys.currentKey(ORG)).thenReturn(Optional.of(key));
		SbomExportSigningService service = new SbomExportSigningService(keys, signer);
		long s0 = System.nanoTime();
		String emitted = service.stampAndSign(doc, ORG, RELEASE);
		long s1 = System.nanoTime();
		long totalMillis = (s1 - s0) / 1_000_000;

		System.out.printf("SCORE-33 T-13 timing: document %,d chars, %,d components, max heap %,d MB%n",
				emitted.length(), doc.get("components").size(), Runtime.getRuntime().maxMemory() >> 20);
		System.out.printf("SCORE-33 T-13 timing: toString %d ms, canonicalise %d ms, sign %d ms, emit %d ms;"
				+ " stampAndSign %d ms%n", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000,
				(t4 - t3) / 1_000_000, totalMillis);

		assertTrue(totalMillis < BUDGET_MILLIS, "stampAndSign took " + totalMillis + " ms");
		assertEquals(Outcome.VERIFIED, JsfVerifier.verify(Utils.OM.readTree(emitted),
				JsfVerifier.keyFromJwk(key.getPublicKeyJwk())));
	}
}
