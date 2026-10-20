/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.cyclonedx.model.LicenseChoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the SPDX-id sanitization that keeps Dependency-Track from rejecting the
 * synthetic BOM ("license.id: does not have a value in the enumeration"). A
 * recognized SPDX id is kept; anything else is emitted as a freeform name.
 */
class CdxLicenseUtilTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> licenseOf(List<Map<String, Object>> cdxArray) {
		// round-trip through the typed LicenseChoice + back to the wire array
		LicenseChoice lc = CdxLicenseUtil.toLicenseChoice(cdxArray);
		List<Map<String, Object>> out = CdxLicenseUtil.toCdxArray(lc);
		assertEquals(1, out.size());
		return (Map<String, Object>) out.get(0).get("license");
	}

	@Test
	void validSpdxIdIsKept() {
		Map<String, Object> lic = licenseOf(List.of(Map.of("license", Map.of("id", "Apache-2.0"))));
		assertEquals("Apache-2.0", lic.get("id"));
		assertNull(lic.get("name"));
	}

	@Test
	void nonSpdxIdBecomesName() {
		Map<String, Object> lic = licenseOf(List.of(Map.of("license", Map.of("id", "LGPL"))));
		assertNull(lic.get("id"), "non-SPDX id must be dropped");
		assertEquals("LGPL", lic.get("name"), "original value preserved as freeform name");
	}

	@Test
	void idWithSpaceBecomesName() {
		// "Apache 2.0" is not an exact SPDX id; preserve literally as name rather
		// than risk a fuzzy remap.
		Map<String, Object> lic = licenseOf(List.of(Map.of("license", Map.of("id", "Apache 2.0"))));
		assertNull(lic.get("id"));
		assertEquals("Apache 2.0", lic.get("name"));
	}

	@Test
	void deprecatedButValidSpdxIdIsKept() {
		// "GPL-3.0" is deprecated but present in the SPDX/DTrack enum — must NOT be
		// demoted to a name (the bug with the resolver-normalization approach).
		Map<String, Object> lic = licenseOf(List.of(Map.of("license", Map.of("id", "GPL-3.0"))));
		assertEquals("GPL-3.0", lic.get("id"));
		assertNull(lic.get("name"));
	}

	@Test
	void miscasedValidIdIsCanonicalized() {
		Map<String, Object> lic = licenseOf(List.of(Map.of("license", Map.of("id", "apache-2.0"))));
		assertEquals("Apache-2.0", lic.get("id"), "mis-cased valid id -> canonical casing");
		assertNull(lic.get("name"));
	}

	@Test
	void proprietaryNameIsUntouched() {
		Map<String, Object> lic = licenseOf(List.of(Map.of("license", Map.of("name", "My Co Commercial"))));
		assertEquals("My Co Commercial", lic.get("name"));
		assertNull(lic.get("id"));
	}

	@Test
	void expressionIsUntouched() {
		LicenseChoice lc = CdxLicenseUtil.toLicenseChoice(List.of(Map.of("expression", "MIT OR Apache-2.0")));
		List<Map<String, Object>> out = CdxLicenseUtil.toCdxArray(lc);
		assertEquals("MIT OR Apache-2.0", out.get(0).get("expression"));
	}

	/**
	 * SCORE-41 T-3: the bundled SPDX exception list is loaded and every id on it is refused as a
	 * licence id; reading the resource here too means an emptied or missing file fails the build.
	 */
	@Test
	void everyBundledExceptionIdIsNoLicenceId() throws Exception {
		JsonNode exceptions;
		try (InputStream is = CdxLicenseUtil.class.getResourceAsStream("/spdx-exceptions.json")) {
			assertNotNull(is, "spdx-exceptions.json is not on the classpath");
			exceptions = new ObjectMapper().readTree(is).get("exceptions");
		}
		assertTrue(exceptions.size() >= 80, String.valueOf(exceptions.size()));
		for (JsonNode n : exceptions) {
			String id = n.get("licenseExceptionId").asText();
			assertTrue(CdxLicenseUtil.isSpdxExceptionId(id), id);
			assertEquals(Optional.empty(), CdxLicenseUtil.canonicalSpdxId(id), id);
			assertEquals(Optional.empty(), CdxLicenseUtil.canonicalSpdxId(id.toUpperCase(Locale.ROOT)), id);
		}
		for (String id : List.of("Classpath-exception-2.0", "LLGPL", "SHL-2.0", "SHL-2.1",
				"Linux-syscall-note", "GPL-CC-1.0")) {
			assertTrue(CdxLicenseUtil.isSpdxExceptionId(id), id);
		}
		assertEquals(Optional.of("GPL-2.0-with-classpath-exception"),
				CdxLicenseUtil.canonicalSpdxId("GPL-2.0-with-classpath-exception"));
		assertFalse(CdxLicenseUtil.isSpdxExceptionId("GPL-2.0-with-classpath-exception"));
	}

	/** SCORE-41 ADR-2: uploaded data keeps an exception id in license.id; only the declared licence refuses it. */
	@Test
	void anUploadedExceptionIdIsKept() {
		Map<String, Object> lic = licenseOf(List.of(Map.of("license", Map.of("id", "Classpath-exception-2.0"))));
		assertEquals("Classpath-exception-2.0", lic.get("id"));
		assertNull(lic.get("name"));
	}

	// SCORE-39 T-7: the SPDX list lookup the declared-licence validator uses
	@Test
	void canonicalSpdxIdMatchesTheListCaseInsensitively() {
		assertEquals(Optional.of("Apache-2.0"), CdxLicenseUtil.canonicalSpdxId("apache-2.0"));
		assertEquals(Optional.of("MIT"), CdxLicenseUtil.canonicalSpdxId("MIT"));
		assertEquals(Optional.empty(), CdxLicenseUtil.canonicalSpdxId("Apache-2"));
		assertEquals(Optional.empty(), CdxLicenseUtil.canonicalSpdxId(""));
		assertEquals(Optional.empty(), CdxLicenseUtil.canonicalSpdxId(null));
	}
}
