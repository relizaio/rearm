/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.model.tea.publication.TeaArtifactDocument;
import io.reliza.model.tea.publication.TeaArtifactFormatDocument;
import io.reliza.model.tea.publication.TeaChecksumDocument;
import io.reliza.service.TeaCollectionAssembler;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A frozen TEA checksum carries the generated {@link TeaChecksumType} instead of a String: the
 * canonical collection document, its documentSha256 and every stored entry must read and write
 * exactly as they did when the field was the spec string.
 */
class TeaChecksumCompatibilityTest {

	private static final UUID PUBLICATION = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID TEA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
	private static final String SHA256 = "0f".repeat(32);
	private static final String SHA512 = "1e".repeat(64);

	/** The canonical document as it was frozen before the change, byte for byte. */
	private static final String GOLDEN_DOCUMENT = "{\"uuid\":\"aaaaaaaa-0000-0000-0000-000000000001\",\"version\":3,"
			+ "\"createdDate\":\"2026-10-03T10:00:00Z\",\"belongsTo\":\"COMPONENT_RELEASE\","
			+ "\"updateReason\":{\"type\":\"ARTIFACT_UPDATED\"},\"artifacts\":[{\"uuid\":\"bbbbbbbb-0000-0000-0000-000000000002\","
			+ "\"version\":2,\"name\":\"widget.cdx.json\",\"type\":\"BOM\",\"createdDate\":\"2026-10-02T08:30:00Z\","
			+ "\"formats\":[{\"mediaType\":\"application/vnd.cyclonedx+json\",\"checksums\":[{\"algType\":\"SHA-256\","
			+ "\"algValue\":\"" + SHA256 + "\"},{\"algType\":\"SHA-512\",\"algValue\":\"" + SHA512 + "\"}]}]}]}";

	/** The sha256 of {@link #GOLDEN_DOCUMENT}, computed outside the code under test. */
	private static final String GOLDEN_DOCUMENT_SHA256 = "d7540eab7ac7cb1dda1e0b2e3a1663fd67ed404374f3c545b75b2f12ef24caed";

	private static List<TeaChecksumDocument> sums() {
		return List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, SHA256),
				new TeaChecksumDocument(TeaChecksumType.SHA_512, SHA512));
	}

	private static TeaPublishedEntry entry() {
		TeaArtifactDocument frozen = new TeaArtifactDocument(TEA, 2, "widget.cdx.json", TeaArtifactType.BOM,
				"2026-10-02T08:30:00Z", List.of(new TeaArtifactFormatDocument("application/vnd.cyclonedx+json", null, null,
						null, sums())));
		return new TeaPublishedEntry(UUID.fromString("cccccccc-0000-0000-0000-000000000003"), TEA, 2,
				TeaArtifactOrigin.SYNTHETIC, TeaArtifactType.BOM, "widget.cdx.json", "application/vnd.cyclonedx+json",
				sums(), null, null, null, frozen);
	}

	@Test
	void theCanonicalDocumentAndItsShaAreByteIdentical() {
		String doc = TeaCollectionAssembler.document(PUBLICATION, 3, "2026-10-03T10:00:00Z",
				TeaCollectionBelongsToType.COMPONENT_RELEASE, TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, null,
				List.of(entry()));
		assertEquals(GOLDEN_DOCUMENT, doc);
		assertEquals(GOLDEN_DOCUMENT_SHA256, TeaCollectionAssembler.sha256(doc));
	}

	@Test
	void aChecksumWritesTheSpecString() {
		assertEquals("{\"algType\":\"SHA-256\",\"algValue\":\"" + SHA256 + "\"}",
				Utils.OM.writeValueAsString(new TeaChecksumDocument(TeaChecksumType.SHA_256, SHA256)));
		assertEquals("{\"algType\":\"BLAKE2b-256\",\"algValue\":\"ab\"}",
				Utils.OM.writeValueAsString(new TeaChecksumDocument(TeaChecksumType.BLAKE2B_256, "ab")));
	}

	/** An entry stored as record data before the change (algType a plain string) still reads back. */
	@Test
	void aStoredEntryWrittenBeforeTheChangeStillDeserializes() {
		String stored = "{\"artifact\":\"cccccccc-0000-0000-0000-000000000003\",\"teaUuid\":\"" + TEA + "\",\"revision\":2,"
				+ "\"origin\":\"SYNTHETIC\",\"teaType\":\"BOM\",\"name\":\"widget.cdx.json\","
				+ "\"mediaType\":\"application/vnd.cyclonedx+json\",\"checksums\":[{\"algType\":\"SHA-256\",\"algValue\":\""
				+ SHA256 + "\"},{\"algType\":\"SHA-512\",\"algValue\":\"" + SHA512 + "\"}],\"frozen\":{\"uuid\":\"" + TEA
				+ "\",\"version\":2,\"name\":\"widget.cdx.json\",\"type\":\"BOM\",\"createdDate\":\"2026-10-02T08:30:00Z\","
				+ "\"formats\":[{\"mediaType\":\"application/vnd.cyclonedx+json\",\"checksums\":[{\"algType\":\"SHA-256\","
				+ "\"algValue\":\"" + SHA256 + "\"},{\"algType\":\"SHA-512\",\"algValue\":\"" + SHA512 + "\"}]}]}}";
		@SuppressWarnings("unchecked")
		Map<String, Object> recordData = Utils.OM.readValue(stored, Map.class);
		TeaPublishedEntry read = Utils.OM.convertValue(recordData, TeaPublishedEntry.class);
		assertEquals(entry(), read);
		assertEquals(TeaChecksumType.SHA_256, read.checksums().get(0).algType());
		assertEquals(TeaChecksumType.SHA_512, read.frozen().formats().get(0).checksums().get(1).algType());
	}

	@Test
	void theDownloadReadsTheFrozenTypeAsTheEnum() {
		JsonNodeFactory f = JsonNodeFactory.instance;
		assertEquals(TeaChecksumType.SHA_256, TeaArtifactSurfaceService.checksumTypeOf(f.stringNode("SHA-256")));
		assertEquals(TeaChecksumType.SHA3_512, TeaArtifactSurfaceService.checksumTypeOf(f.stringNode("SHA3-512")));
		assertNull(TeaArtifactSurfaceService.checksumTypeOf(f.stringNode("MD5")), "TEA 1.0.0 has no MD5");
		assertNull(TeaArtifactSurfaceService.checksumTypeOf(f.stringNode("SHA_256")), "the constant name is not the spec string");
		assertNull(TeaArtifactSurfaceService.checksumTypeOf(f.numberNode(256)));
		assertNull(TeaArtifactSurfaceService.checksumTypeOf(null));
	}
}
