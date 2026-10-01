/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class CvssVectorScorerTest {

	@Test
	void cvss31CriticalVector() {
		assertEquals(Optional.of(9.8),
				CvssVectorScorer.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"));
	}

	@Test
	void cvss31HighIntegrityOnlyVector() {
		assertEquals(Optional.of(7.5),
				CvssVectorScorer.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:N"));
	}

	@Test
	void cvss30Vector() {
		assertEquals(Optional.of(9.8),
				CvssVectorScorer.baseScore("CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"));
	}

	@Test
	void cvss40Vector() {
		assertEquals(Optional.of(9.3),
				CvssVectorScorer.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N"));
	}

	@Test
	void cvss2Vector() {
		assertEquals(Optional.of(7.5), CvssVectorScorer.baseScore("AV:N/AC:L/Au:N/C:P/I:P/A:P"));
	}

	@Test
	void surroundingWhitespaceIsTolerated() {
		assertEquals(Optional.of(7.5),
				CvssVectorScorer.baseScore("  CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:N\n"));
	}

	@Test
	void nullBlankAndGarbageAreEmpty() {
		assertTrue(CvssVectorScorer.baseScore(null).isEmpty());
		assertTrue(CvssVectorScorer.baseScore("").isEmpty());
		assertTrue(CvssVectorScorer.baseScore("   ").isEmpty());
		assertTrue(CvssVectorScorer.baseScore("not a vector").isEmpty());
		assertTrue(CvssVectorScorer.baseScore("CVSS:9.9/AV:N/AC:L").isEmpty());
		assertTrue(CvssVectorScorer.baseScore("CVSS:3.1/AV:N/AC:L").isEmpty());
		assertTrue(CvssVectorScorer.baseScore("CVSS:3.1/AV:X/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H").isEmpty());
	}

	@Test
	void versionSpecificScorersRejectOtherVersions() {
		String v2 = "AV:N/AC:L/Au:N/C:P/I:P/A:P";
		String v31 = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:N";
		String v30 = "CVSS:3.0/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H";
		String v40 = "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N";
		assertEquals(Optional.of(7.5), CvssVectorScorer.v3BaseScore(v31));
		assertEquals(Optional.of(9.8), CvssVectorScorer.v3BaseScore(v30));
		assertTrue(CvssVectorScorer.v3BaseScore(v2).isEmpty());
		assertTrue(CvssVectorScorer.v3BaseScore(v40).isEmpty());
		assertEquals(Optional.of(9.3), CvssVectorScorer.v4BaseScore(v40));
		assertTrue(CvssVectorScorer.v4BaseScore(v31).isEmpty());
		assertTrue(CvssVectorScorer.v4BaseScore(v2).isEmpty());
		assertTrue(CvssVectorScorer.v4BaseScore(null).isEmpty());
		assertEquals(Optional.of(7.5), CvssVectorScorer.v2BaseScore(v2));
		assertTrue(CvssVectorScorer.v2BaseScore(v31).isEmpty());
		assertTrue(CvssVectorScorer.v2BaseScore(v40).isEmpty());
		assertTrue(CvssVectorScorer.v2BaseScore(null).isEmpty());
		// The CVSS:2.0/ prefixed form, which the library's v2 parser rejects on its own.
		assertEquals(Optional.of(7.5), CvssVectorScorer.v2BaseScore("CVSS:2.0/" + v2));
		assertEquals(Optional.of(7.5), CvssVectorScorer.v2BaseScore(" cvss:2.0/" + v2));
		assertEquals(Optional.of(7.5), CvssVectorScorer.baseScore("CVSS:2.0/" + v2));
		assertTrue(CvssVectorScorer.v3BaseScore("CVSS:2.0/" + v2).isEmpty());
	}
}
