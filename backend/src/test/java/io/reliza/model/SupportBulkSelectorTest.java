/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.github.packageurl.PackageURL;

/** FDAW-14 3.4 rows 7 and 9: the selector's charset, bounds and canonical namespace, with no database. */
class SupportBulkSelectorTest {

	@Test
	void theTypeCharsetRefusesPatternsCaseAndEmpty() {
		for (String t : new String[] {"np%", "Deb", "", "a_b", "a b", "x".repeat(65)}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> SupportBulkSelector.validated(SupportBulkSelectorForm.TYPE, t, null), t);
			assertEquals("bulk selector: type must match [a-z0-9.+-]{1,64}", e.getMessage());
		}
		assertNull(SupportBulkSelector.validated(SupportBulkSelectorForm.TYPE, "deb", "ignored").namespace());
	}

	@Test
	void theNamespaceIsRequiredBoundedAndCanonical() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", " "));
		assertEquals("bulk selector: namespace is required for the NAMESPACE form", e.getMessage());
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "n".repeat(257)));
		assertEquals("bulk selector: namespace is longer than 256 characters or holds a control character", e.getMessage());
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "a\u0001"));
		assertEquals("bulk selector: namespace is longer than 256 characters or holds a control character", e.getMessage());
		// FDAW-49 row 5: the namespace goes through the shared rule (DEL, C1, FORMAT), still one line
		for (String ns : new String[] {"a\u202Eb", "a\u007Fb", "a\u0085b", "a\tb"}) {
			e = assertThrows(IllegalArgumentException.class,
					() -> SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", ns), ns);
			assertEquals("bulk selector: namespace is longer than 256 characters or holds a control character", e.getMessage());
		}
		assertEquals(SupportBulkSelector.canonicalNamespace("maven", "org.na\u00EFve"),
				SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "maven", "org.na\u00EFve").namespace());
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportBulkSelector.validated(null, "npm", null));
		assertEquals("bulk selector: form is required", e.getMessage());

		assertEquals("%40acme", SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "@acme").namespace());
		assertEquals("github.com/foo",
				SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "golang", "github.com/foo").namespace());
		assertEquals("@acme", SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "@acme")
				.decodedNamespace());
	}

	/** A pattern character in a namespace is a literal: it is encoded, so it can only match itself. */
	@Test
	void aPatternCharacterInANamespaceMatchesOnlyItself() throws Exception {
		SupportBulkSelector pct;
		try {
			pct = SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "%");
		} catch (IllegalArgumentException refused) {
			assertTrue(refused.getMessage().startsWith("bulk selector:"), refused.getMessage());
			return;
		}
		assertFalse(pct.matches(new PackageURL("pkg:npm/%40acme/a@1")));
		assertFalse(pct.namespace().equals("%"), "the stored spelling is encoded, never a raw pattern character");
		SupportBulkSelector under = SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "@acme_");
		assertFalse(under.matches(new PackageURL("pkg:npm/%40acme/a@1")));
		assertFalse(under.matches(new PackageURL("pkg:npm/%40acmeb/a@1")));
	}

	@Test
	void matchesByDecodedTypeAndNamespaceAndTheReceiptCarriesTheCanonicalSpelling() throws Exception {
		SupportBulkSelector deb = SupportBulkSelector.validated(SupportBulkSelectorForm.TYPE, "deb", null);
		assertTrue(deb.matches(new PackageURL("pkg:deb/debian/curl@7.88")));
		assertFalse(deb.matches(new PackageURL("pkg:npm/left-pad@1")));
		SupportBulkSelector acme = SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "npm", "@acme");
		assertTrue(acme.matches(new PackageURL("pkg:npm/%40acme/a@1")));
		assertFalse(acme.matches(new PackageURL("pkg:npm/%40acme_b/x@1")));
		UUID release = UUID.randomUUID();
		assertEquals("{form=NAMESPACE, type=npm, namespace=%40acme, releaseUuid=" + release + "}",
				acme.receipt(release).toString());
		assertEquals("NAMESPACE npm/%40acme", acme.describe());
	}
}
