/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Pins the decode contract of the TEA {@code /discovery} endpoint against the
 * double-decode regression.
 *
 * <p>The controller used to call {@code URLDecoder.decode} on the {@code tei}
 * request parameter, which Spring had ALREADY percent-decoded. That second pass
 * ate one level of escaping. It was invisible for purls made of unreserved
 * characters, but every sid PURL whose component name contains a space carries a
 * literal {@code %20} in its canonical form -- and that {@code %20} collapsed to
 * a space, so the reconstructed purl never matched the stored identifier
 * (observed live: only a triple-escaped URL resolved).
 *
 * <p>These tests model the wire -> lookup chain for the fixed single-decode
 * behaviour, and assert the purl reconstruction in
 * {@code TeaTransformerService.performTeiDiscovery} round-trips a
 * {@code %}-bearing sid purl exactly.
 */
class TeiPurlReconstructionTest {

	/** Canonical sid purl for a component whose name contains a space. */
	private static final String SID_PURL = "pkg:sid/example.com/My%20Product@1.0.0";
	private static final String TEI = "urn:tei:purl:example.com:" + SID_PURL;

	/**
	 * Mirror of the purl reconstruction in {@code performTeiDiscovery}: rebuild
	 * from the {@code pkg} token onward, re-joining the ':' the split consumed.
	 */
	private static String reconstructPurl(String decodedTei) {
		String[] teiEls = decodedTei.split(":");
		StringBuilder purlBuilder = new StringBuilder();
		boolean foundPurlStart = false;
		for (int i = 4; i < teiEls.length; i++) {
			if (!foundPurlStart && "pkg".equals(teiEls[i])) foundPurlStart = true;
			if (foundPurlStart) {
				purlBuilder.append(teiEls[i]);
				if (i < teiEls.length - 1) purlBuilder.append(":");
			}
		}
		return purlBuilder.toString();
	}

	/** What Spring hands the controller, i.e. exactly one percent-decode of the wire value. */
	private static String afterSpringDecode(String wireValue) {
		return URLDecoder.decode(wireValue, StandardCharsets.UTF_8);
	}

	@Test
	void singleEscapedTeiRoundTripsSidPurlWithPercentEscape() {
		// What `rearm tea discovery` sends: one url.QueryEscape over the raw TEI,
		// so the purl's own '%' becomes '%25' on the wire.
		String wire = "urn%3Atei%3Apurl%3Aexample.com%3Apkg%3Asid%2Fexample.com%2FMy%2520Product%401.0.0";
		String decodedTei = afterSpringDecode(wire);
		assertEquals(TEI, decodedTei, "one decode must yield the raw TEI with its literal %20 intact");
		assertEquals(SID_PURL, reconstructPurl(decodedTei),
				"reconstructed purl must match the stored identifier byte for byte");
	}

	@Test
	void secondDecodeWouldDestroyThePercentEscape() {
		// The regression: decoding again (as the controller used to) turns the
		// purl's %20 into a space, which no stored identifier will ever match.
		String decodedTei = afterSpringDecode(
				"urn%3Atei%3Apurl%3Aexample.com%3Apkg%3Asid%2Fexample.com%2FMy%2520Product%401.0.0");
		String doubleDecoded = URLDecoder.decode(decodedTei, StandardCharsets.UTF_8);
		assertEquals("pkg:sid/example.com/My Product@1.0.0", reconstructPurl(doubleDecoded),
				"guard: a second decode collapses %20 to a space -- this is the bug being fixed");
	}

	@Test
	void purlsWithoutPercentEscapesAreUnaffected() {
		// Why this hid for so long: plain purls survive any number of decodes.
		String plain = "pkg:github/relizaio/rearm@26.07.158";
		String wire = "urn%3Atei%3Apurl%3Ademo.example.com%3Apkg%3Agithub%2Frelizaio%2Frearm%4026.07.158";
		assertEquals(plain, reconstructPurl(afterSpringDecode(wire)));
	}

	@Test
	void versionBuildMetadataSurvivesSingleDecode() {
		// '+' is form-decoded to a space by URLDecoder; QueryEscape emits %2B, so
		// semver build metadata survives the single remaining decode.
		String purl = "pkg:sid/example.com/thing@1.0.0+build.5";
		String wire = "urn%3Atei%3Apurl%3Aexample.com%3Apkg%3Asid%2Fexample.com%2Fthing%401.0.0%2Bbuild.5";
		assertEquals(purl, reconstructPurl(afterSpringDecode(wire)));
	}
}
