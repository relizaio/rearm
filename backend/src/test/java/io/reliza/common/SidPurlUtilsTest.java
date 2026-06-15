/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;

/** Pure-function tests for {@link SidPurlUtils}. No Spring context. */
public class SidPurlUtilsTest {

	// ---- validateAuthoritySegments ----

	@Nested
	class ValidateAuthoritySegments {

		@Test
		void emptyOrNullList_rejected() {
			assertFalse(SidPurlUtils.validateAuthoritySegments(null).valid());
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of()).valid());
		}

		@Test
		void singleSegmentDomainForm_accepted() {
			assertTrue(SidPurlUtils.validateAuthoritySegments(List.of("reliza.io")).valid());
		}

		@Test
		void multiSegmentDomainForm_accepted() {
			assertTrue(SidPurlUtils.validateAuthoritySegments(
					List.of("acme.com", "Acme Robotics", "Industrial")).valid());
		}

		@Test
		void registryForm_acceptedWithoutDot() {
			// No dot in first segment → registry-based namespace. Backend can't verify
			// the upstream PURL registry, so we accept with a (later) UI-side warning.
			assertTrue(SidPurlUtils.validateAuthoritySegments(List.of("foo")).valid());
		}

		@Test
		void allNumericFirstSegment_rejected() {
			// Looks like an IPv4; spec disallows.
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("192168")).valid());
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("192.168.1.1")).valid());
		}

		@Test
		void nonAsciiFirstSegment_rejected() {
			// IDN domains must be in punycode form ("xn--mnchen-3ya.de"), not raw "münchen.de".
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("münchen.de")).valid());
		}

		@Test
		void controlCharInSegment_rejected() {
			// Literal U+0001 (SOH) — FORBIDDEN_RAW covers U+0000..U+001F and U+007F.
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("acme.com", "bad\u0001seg")).valid());
			// Also cover DEL (U+007F) to pin both ends of the control-char range.
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("acme.com", "bad\u007Fseg")).valid());
		}

		@Test
		void slashInSegment_rejected() {
			// Decoded segments cannot contain '/' — that's the segment separator.
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("acme.com", "a/b")).valid());
		}

		@Test
		void percentInSegment_rejected() {
			// Raw '%' would double-encode through packageurl-java. Reject.
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("acme.com", "Already%20Encoded")).valid());
		}

		@Test
		void leadingTrailingWhitespace_rejected() {
			// Reject rather than silently trim — list-equality on segments must stay well-defined.
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of(" reliza.io")).valid());
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("reliza.io ")).valid());
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("acme.com", " Robotics")).valid());
		}

		@Test
		void internalSpaces_accepted() {
			// Spec example uses "Acme Robotics" (encoded as "Acme%20Robotics" at emission).
			assertTrue(SidPurlUtils.validateAuthoritySegments(List.of("acme.com", "Acme Robotics")).valid());
		}

		@Test
		void leadingTrailingDot_rejected() {
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of(".acme.com")).valid());
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("acme.com.")).valid());
		}

		@Test
		void rfc1035LabelTooLong_rejected() {
			// Label > 63 chars violates RFC 1035.
			String longLabel = "a".repeat(64);
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of(longLabel + ".com")).valid());
		}

		@Test
		void rfc1035LeadingHyphen_rejected() {
			assertFalse(SidPurlUtils.validateAuthoritySegments(List.of("-bad.com")).valid());
		}

		@Test
		void blankSegment_rejected() {
			assertFalse(SidPurlUtils.validateAuthoritySegments(java.util.Arrays.asList("acme.com", "")).valid());
		}

		@Test
		void nullSegment_rejected() {
			assertFalse(SidPurlUtils.validateAuthoritySegments(java.util.Arrays.asList("acme.com", null)).valid());
		}
	}

	// ---- buildSidPurl ----

	@Nested
	class BuildSidPurl {

		@Test
		void specExample_multiSegment_internalSpaces() throws RelizaException {
			// Verbatim from the upstream spec.
			String purl = SidPurlUtils.buildSidPurl(
					List.of("acme.com", "Acme Robotics", "Industrial"),
					"motion-controller", "4.1.0", null, null);
			assertEquals("pkg:sid/acme.com/Acme%20Robotics/Industrial/motion-controller@4.1.0", purl);
		}

		@Test
		void componentNameWithSpace_percentEncoded() throws RelizaException {
			String purl = SidPurlUtils.buildSidPurl(
					List.of("reliza.io"), "ReARM Backend", "25.04.5", null, null);
			assertEquals("pkg:sid/reliza.io/ReARM%20Backend@25.04.5", purl);
		}

		@Test
		void typeIsAlwaysLowercase() throws RelizaException {
			String purl = SidPurlUtils.buildSidPurl(
					List.of("acme.com"), "AcmeApp", "1.0.0", null, null);
			assertTrue(purl.startsWith("pkg:sid/"), "type must canonicalize to lowercase 'sid'");
		}

		@Test
		void caseInNameAndAuthority_preserved() throws RelizaException {
			String purl = SidPurlUtils.buildSidPurl(
					List.of("acme.com"), "AcmeApplication", "1.0.0", null, null);
			assertEquals("pkg:sid/acme.com/AcmeApplication@1.0.0", purl);
		}

		@Test
		void qualifiers_appendedAndSorted() throws RelizaException {
			String purl = SidPurlUtils.buildSidPurl(
					List.of("acme.com"), "AcmeApp", "1.0.0",
					Map.of("arch", "x86_64", "locale", "en-GB"), null);
			// packageurl-java sorts qualifier keys alphabetically; values preserve case.
			assertEquals("pkg:sid/acme.com/AcmeApp@1.0.0?arch=x86_64&locale=en-GB", purl);
		}

		@Test
		void nullVersion_omitted() throws RelizaException {
			String purl = SidPurlUtils.buildSidPurl(
					List.of("acme.com"), "AcmeApp", null, null, null);
			assertEquals("pkg:sid/acme.com/AcmeApp", purl);
		}

		@Test
		void emptySegments_throw() {
			assertThrows(RelizaException.class, () ->
					SidPurlUtils.buildSidPurl(List.of(), "x", "1", null, null));
		}

		@Test
		void blankComponentName_throws() {
			assertThrows(RelizaException.class, () ->
					SidPurlUtils.buildSidPurl(List.of("acme.com"), " ", "1", null, null));
		}
	}

	// ---- isSidPurl ----

	@Nested
	class IsSidPurl {

		@Test
		void sidLowercase_true() {
			assertTrue(SidPurlUtils.isSidPurl("pkg:sid/acme.com/X@1.0"));
		}

		@Test
		void sidMixedCase_true() {
			// PURL spec normalizes type to lowercase; library handles it.
			assertTrue(SidPurlUtils.isSidPurl("pkg:SID/acme.com/X@1.0"));
		}

		@Test
		void mavenPurl_false() {
			assertFalse(SidPurlUtils.isSidPurl("pkg:maven/com.example/foo@1.0"));
		}

		@Test
		void malformedPurl_false() {
			assertFalse(SidPurlUtils.isSidPurl("not-a-purl"));
			assertFalse(SidPurlUtils.isSidPurl(""));
		}

		@Test
		void nullPurl_false() {
			assertFalse(SidPurlUtils.isSidPurl(null));
		}
	}

	// ---- pickPreferredPurl ----

	@Nested
	class PickPreferredPurl {

		@Test
		void nullList_emptyOptional() {
			assertTrue(SidPurlUtils.pickPreferredPurl(null).isEmpty());
		}

		@Test
		void emptyList_emptyOptional() {
			assertTrue(SidPurlUtils.pickPreferredPurl(List.of()).isEmpty());
		}

		@Test
		void noPurlIdentifiers_emptyOptional() {
			RearmIdentifier cpe = idOf(RearmIdentifierType.CPE, "cpe:2.3:a:acme:foo:1.0");
			assertTrue(SidPurlUtils.pickPreferredPurl(List.of(cpe)).isEmpty());
		}

		@Test
		void singlePurl_returnsThat() {
			RearmIdentifier maven = idOf(RearmIdentifierType.PURL, "pkg:maven/com.example/foo@1.0");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(List.of(maven));
			assertTrue(picked.isPresent());
			assertSame(maven, picked.get());
		}

		@Test
		void sidWinsOverMaven() {
			RearmIdentifier maven = idOf(RearmIdentifierType.PURL, "pkg:maven/com.example/foo@1.0");
			RearmIdentifier sid = idOf(RearmIdentifierType.PURL, "pkg:sid/acme.com/Foo@1.0");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(List.of(maven, sid));
			assertTrue(picked.isPresent());
			assertEquals(sid.getIdValue(), picked.get().getIdValue());
		}

		@Test
		void sidWinsRegardlessOfInsertionOrder() {
			RearmIdentifier sid = idOf(RearmIdentifierType.PURL, "pkg:sid/acme.com/Foo@1.0");
			RearmIdentifier maven = idOf(RearmIdentifierType.PURL, "pkg:maven/com.example/foo@1.0");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(List.of(sid, maven));
			assertTrue(picked.isPresent());
			assertEquals(sid.getIdValue(), picked.get().getIdValue());
		}

		@Test
		void nonSidTier_lexicographicSmallestWins() {
			// Within the non-sid tier, deterministic by lexicographic idValue (D4).
			RearmIdentifier npm = idOf(RearmIdentifierType.PURL, "pkg:npm/foo@1.0");
			RearmIdentifier maven = idOf(RearmIdentifierType.PURL, "pkg:maven/com.example/foo@1.0");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(List.of(npm, maven));
			assertTrue(picked.isPresent());
			assertEquals("pkg:maven/com.example/foo@1.0", picked.get().getIdValue());
		}

		@Test
		void malformedPurl_doesNotThrow_tiersLast() {
			// Single bad tenant-supplied identifier must not break BOM emission.
			RearmIdentifier malformed = idOf(RearmIdentifierType.PURL, "not-a-purl");
			RearmIdentifier maven = idOf(RearmIdentifierType.PURL, "pkg:maven/x/y@1");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(List.of(malformed, maven));
			assertTrue(picked.isPresent());
			assertEquals("pkg:maven/x/y@1", picked.get().getIdValue());
		}

		@Test
		void onlyMalformedPurl_returnsThat() {
			// All-malformed list still returns one (lowest tier rather than empty), so
			// the picker doesn't silently drop user-supplied identifiers.
			RearmIdentifier malformed = idOf(RearmIdentifierType.PURL, "not-a-purl");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(List.of(malformed));
			assertTrue(picked.isPresent());
		}

		@Test
		void mixedTypes_purlOnlyConsidered() {
			RearmIdentifier cpe = idOf(RearmIdentifierType.CPE, "cpe:2.3:a:acme:foo:1.0");
			RearmIdentifier sid = idOf(RearmIdentifierType.PURL, "pkg:sid/acme.com/Foo@1.0");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(List.of(cpe, sid));
			assertTrue(picked.isPresent());
			assertEquals(sid.getIdValue(), picked.get().getIdValue());
		}

		@Test
		void nullElementInList_skipped() {
			RearmIdentifier maven = idOf(RearmIdentifierType.PURL, "pkg:maven/x/y@1");
			Optional<RearmIdentifier> picked = SidPurlUtils.pickPreferredPurl(java.util.Arrays.asList(null, maven));
			assertTrue(picked.isPresent());
			assertSame(maven, picked.get());
		}
	}

	// --- helpers ---

	private static RearmIdentifier idOf(RearmIdentifierType type, String value) {
		RearmIdentifier ti = new RearmIdentifier();
		ti.setIdType(type);
		ti.setIdValue(value);
		return ti;
	}

	@SuppressWarnings("unused")
	private static void unused() {
		// Suppress unused-import lint for assertNotNull/assertNull/assertNotEquals
		// imported for future test additions.
		assertNotNull(new Object());
		assertNull(null);
	}
}
