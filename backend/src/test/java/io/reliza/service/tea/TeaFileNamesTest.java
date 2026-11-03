/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ArtifactData.BomFormat;

/** The TEA download file name rule: {@code <teaUuid>-r<revision>} and the media type's extension. */
class TeaFileNamesTest {

	private static final UUID TEA = UUID.fromString("3f2c8a1e-9b4d-4c6e-8f0a-1b2c3d4e5f60");

	@Test
	void aRevisionIsNamedByItsTeaUuidRevisionAndMediaType() {
		assertEquals(TEA + "-r4.cdx.json", TeaFileNames.artifactFileName(TEA, 4, "application/vnd.cyclonedx+json"));
		assertEquals(TEA + "-r1.spdx.json", TeaFileNames.artifactFileName(TEA, 1, "application/spdx+json"));
		assertEquals(TEA + "-r12", TeaFileNames.artifactFileName(TEA, 12, "application/octet-stream"));
		assertEquals(TEA + "-r2", TeaFileNames.artifactFileName(TEA, 2, null));
	}

	@Test
	void knownMediaTypesHaveTheirExtension() {
		assertEquals(".cdx.json", TeaFileNames.extensionOf("application/vnd.cyclonedx+json"));
		assertEquals(".cdx.xml", TeaFileNames.extensionOf("application/vnd.cyclonedx+xml"));
		assertEquals(".spdx.json", TeaFileNames.extensionOf("application/spdx+json"));
		assertEquals(".spdx", TeaFileNames.extensionOf("text/spdx"));
		assertEquals(".json", TeaFileNames.extensionOf("application/json"));
		assertEquals(".xml", TeaFileNames.extensionOf("text/xml"));
		assertEquals(".pdf", TeaFileNames.extensionOf("application/pdf"));
		assertEquals(".txt", TeaFileNames.extensionOf("text/plain"));
		assertEquals(".md", TeaFileNames.extensionOf("text/markdown"));
		assertEquals(".intoto.json", TeaFileNames.extensionOf("application/vnd.in-toto+json"));
	}

	@Test
	void parametersAndCaseDoNotChangeTheExtension() {
		assertEquals(".cdx.json", TeaFileNames.extensionOf("Application/VND.CycloneDX+JSON; version=1.6"));
		assertEquals(".txt", TeaFileNames.extensionOf(" text/plain;charset=utf-8 "));
	}

	@Test
	void anUnknownStructuredSuffixFallsBackAndAnythingElseHasNoExtension() {
		assertEquals(".json", TeaFileNames.extensionOf("application/vnd.openvex+json"));
		assertEquals(".xml", TeaFileNames.extensionOf("application/vnd.example+xml"));
		assertEquals("", TeaFileNames.extensionOf("application/octet-stream"));
		assertEquals("", TeaFileNames.extensionOf("application/x-unknown"));
		assertEquals("", TeaFileNames.extensionOf(""));
		assertEquals("", TeaFileNames.extensionOf(null));
	}

	@Test
	void anSpdxJsonBomIsPublishedAsTheSpdxMediaType() {
		assertEquals("application/spdx+json", TeaTransformerService.resolveMediaType(BomFormat.SPDX, "application/json"));
		assertEquals("vnd.cyclonedx+json", TeaTransformerService.resolveMediaType(BomFormat.CYCLONEDX, "application/json"));
		assertEquals("application/json", TeaTransformerService.resolveMediaType(null, "application/json"));
		assertEquals("text/spdx", TeaTransformerService.resolveMediaType(BomFormat.SPDX, "text/spdx"));
	}
}
