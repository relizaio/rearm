/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code policy-checks} section: the CEL surface a policy can check.
 *
 * <p>One file per orientation section (see {@link OrientationAskingContractTest}).
 */
class OrientationPolicyChecksContractTest {

	/** TPF-129: the BOM tally inputs, the refusing rule and the scannable-BOM precondition. */
	@Test
	void theNotScannableBomInputsAreDocumented() throws IOException {
		String doc = section("policy-checks").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("release.bomScanState == \"ALL_NOT_SCANNABLE\""), "the refusing rule");
		assertTrue(doc.contains("release.scannableBoms >= 1"), "the precondition");
		assertTrue(doc.contains("!(\"NO_COMPONENTS\" in release.notScannableReasons)"), "the late-parse exclusion");
	}
}
