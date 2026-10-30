/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;

/**
 * SCORE-71 T-1 (ADR-2 (a)): the identifier arm of updateRelease invalidates the stored merged rows only when
 * the preferred purl changes, the one identifier those rows carry; any other identifier change leaves them.
 */
class OssReleaseServicePreferredPurlChangedTest {

	private static final String P = "pkg:generic/acme/p@1.0.0";
	private static final String Q = "pkg:generic/acme/q@1.0.0";
	private static final String SID = "pkg:sid/acme.example/p@1.0.0";

	private static RearmIdentifier purl(String value) {
		return new RearmIdentifier(RearmIdentifierType.PURL, value);
	}

	private static RearmIdentifier cpe(String value) {
		return new RearmIdentifier(RearmIdentifierType.CPE, value);
	}

	@Test
	void aPurlAddedWhereThereWasNoneIsAChange() {
		assertTrue(OssReleaseService.preferredPurlChanged(List.of(), List.of(purl(P))));
	}

	@Test
	void anotherPurlIsAChange() {
		assertTrue(OssReleaseService.preferredPurlChanged(List.of(purl(P)), List.of(purl(Q))));
	}

	@Test
	void aCpeAddedBesideTheSamePurlIsNoChange() {
		assertFalse(OssReleaseService.preferredPurlChanged(List.of(purl(P)),
				List.of(purl(P), cpe("cpe:2.3:a:acme:p:1.0.0:*:*:*:*:*:*:*"))));
	}

	@Test
	void twoPurlsReorderedAreNoChange() {
		assertFalse(OssReleaseService.preferredPurlChanged(List.of(purl(P), purl(Q)), List.of(purl(Q), purl(P))));
	}

	@Test
	void aNonSidPurlAddedBesideAKeptSidPurlIsNoChange() {
		assertFalse(OssReleaseService.preferredPurlChanged(List.of(purl(SID)), List.of(purl(SID), purl(P))));
	}

	@Test
	void theOnlyNonSidPurlRemovedBesideAKeptSidPurlIsNoChange() {
		assertFalse(OssReleaseService.preferredPurlChanged(List.of(purl(SID), purl(P)), List.of(purl(SID))));
	}

	@Test
	void aMalformedPurlReplacedByAValidOneIsAChange() {
		assertTrue(OssReleaseService.preferredPurlChanged(List.of(purl("not a purl")), List.of(purl(P))));
	}

	@Test
	void nullAndAnEmptyListAreNoChange() {
		assertFalse(OssReleaseService.preferredPurlChanged(null, List.of()));
		assertFalse(OssReleaseService.preferredPurlChanged(List.of(), null));
	}

	@Test
	void thePurlRemovedSoThatNoneIsLeftIsAChange() {
		assertTrue(OssReleaseService.preferredPurlChanged(List.of(purl(P)), List.of()));
	}
}
