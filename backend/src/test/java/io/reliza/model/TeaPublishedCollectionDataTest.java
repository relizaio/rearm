/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.TeaPublicationData.TeaAggregateArtifact;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.model.tea.publication.TeaArtifactDocument;
import io.reliza.model.tea.publication.TeaArtifactFormatDocument;
import io.reliza.model.tea.publication.TeaChecksumDocument;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaPublicationService.TeaPublicationState;

/**
 * Task TEA-5, design 4.2 case 21: both TEA publication data classes round-trip through
 * {@code toRecordData} and {@code dataFromRecord} with every field, the entries included, and a
 * record carrying a key this version does not know still reads.
 */
class TeaPublishedCollectionDataTest {

	/** Dates leave the record as epoch numbers and come back in UTC: compare them as instants. */
	private static final ZonedDateTime T1 = ZonedDateTime.of(2026, 10, 1, 9, 15, 30, 123_456_789, ZoneId.of("Europe/Berlin"));
	private static final ZonedDateTime T2 = T1.plusDays(1);
	private static final ZonedDateTime T3 = T1.plusDays(2);

	// ------------------------------------------------------------------ TeaPublicationData

	private static TeaPublicationData fullPublication() {
		TeaPublicationData d = new TeaPublicationData();
		d.setUuid(UUID.randomUUID());
		d.setOrg(UUID.randomUUID());
		d.setRelease(UUID.randomUUID());
		d.setComponent(UUID.randomUUID());
		d.setComponentType(ComponentType.PRODUCT);
		d.setState(TeaPublicationState.HIDDEN);
		d.setLatestVersion(4);
		d.setProfile(UUID.randomUUID());
		d.setProfileRevision(6);
		d.setProfileSource(TeaProfileSource.FOLLOWED_PERSPECTIVE);
		d.setProfileSourceObject(UUID.randomUUID());
		d.setCascadeOf(UUID.randomUUID());
		d.setAggregateArtifacts(List.of(new TeaAggregateArtifact("default", UUID.randomUUID()),
				new TeaAggregateArtifact("top", UUID.randomUUID())));
		d.setTei("tei://example.com/uuid/" + d.getUuid());
		d.setFirstPublishedDate(T1);
		d.setLastPublishedDate(T2);
		d.setLastHiddenDate(T3);
		d.setLastPublishedBy(UUID.randomUUID());
		d.setRevision(9);
		d.setCreatedDate(T1);
		d.setUpdatedDate(T3);
		return d;
	}

	private static TeaPublication rowOf(TeaPublicationData d, Map<String, Object> recordData) {
		TeaPublication p = new TeaPublication();
		p.setUuid(d.getUuid());
		p.setOrg(d.getOrg());
		p.setRelease(d.getRelease());
		p.setComponent(d.getComponent());
		p.setState(d.getState());
		p.setLatestVersion(d.getLatestVersion());
		p.setProfile(d.getProfile());
		p.setProfileRevision(d.getProfileRevision());
		p.setCascadeOf(d.getCascadeOf());
		p.setRevision(d.getRevision());
		p.setCreatedDate(d.getCreatedDate());
		p.setLastUpdatedDate(d.getUpdatedDate());
		p.setRecordData(recordData);
		return p;
	}

	@Test
	void aPublicationRoundTripsEveryField() {
		TeaPublicationData d = fullPublication();
		Map<String, Object> record = d.toRecordData();
		assertTrue(!record.containsKey("revision"), "the revision is the entity's, not the record's");

		TeaPublicationData read = TeaPublicationData.dataFromRecord(rowOf(d, record));

		assertTrue(T1.isEqual(read.getFirstPublishedDate()));
		assertTrue(T2.isEqual(read.getLastPublishedDate()));
		assertTrue(T3.isEqual(read.getLastHiddenDate()));
		assertEquals(9, read.getRevision());
		// with the record dates normalized to the zone the reader produces, every field is equal
		d.setFirstPublishedDate(read.getFirstPublishedDate());
		d.setLastPublishedDate(read.getLastPublishedDate());
		d.setLastHiddenDate(read.getLastHiddenDate());
		d.setCreatedDate(read.getCreatedDate());
		d.setUpdatedDate(read.getUpdatedDate());
		assertEquals(d, read);
		assertEquals(TeaProfileSource.FOLLOWED_PERSPECTIVE, read.getProfileSource());
		assertEquals(d.getTei(), read.getTei());
		assertEquals(d.getCascadeOf(), read.getCascadeOf());
	}

	@Test
	void aPublicationRecordWithAnUnknownKeyReads() {
		TeaPublicationData d = fullPublication();
		Map<String, Object> record = new LinkedHashMap<>(d.toRecordData());
		record.put("aFieldOfALaterVersion", Map.of("nested", List.of(1, 2)));
		TeaPublicationData read = TeaPublicationData.dataFromRecord(rowOf(d, record));
		assertEquals(d.getUuid(), read.getUuid());
		assertEquals(TeaPublicationState.HIDDEN, read.getState());
		assertEquals(4, read.getLatestVersion());
	}

	@Test
	void aPublicationOfAnotherSchemaVersionIsRefused() {
		TeaPublicationData d = fullPublication();
		TeaPublication row = rowOf(d, d.toRecordData());
		row.setSchemaVersion(1);
		assertThrows(IllegalStateException.class, () -> TeaPublicationData.dataFromRecord(row));
	}

	// ------------------------------------------------------------------ TeaPublishedCollectionData

	private static TeaPublishedEntry synthetic() {
		UUID tea = UUID.randomUUID();
		List<TeaChecksumDocument> sums = List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "ab".repeat(32)));
		TeaArtifactDocument frozen = new TeaArtifactDocument(tea, 3, "widget-1.0-tea-aggregate.cdx.json", TeaArtifactType.BOM,
				"2026-10-03T10:00:00.123456789Z", List.of(new TeaArtifactFormatDocument("application/vnd.cyclonedx+json", null,
						null, null, sums)));
		return new TeaPublishedEntry(UUID.randomUUID(), tea, 3, TeaArtifactOrigin.SYNTHETIC, TeaArtifactType.BOM,
				frozen.name(), "application/vnd.cyclonedx+json", sums, null, null, null, frozen, "fda-premarket.v2",
				SBOM_PROFILE, 4);
	}

	private static final UUID SBOM_PROFILE = UUID.randomUUID();

	private static TeaPublishedEntry externalWithSignature() {
		UUID tea = UUID.randomUUID();
		List<TeaChecksumDocument> sums = List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "cd".repeat(32)),
				new TeaChecksumDocument(TeaChecksumType.SHA_512, "ef".repeat(64)));
		TeaArtifactDocument frozen = new TeaArtifactDocument(tea, 12, "vex.json", TeaArtifactType.VULNERABILITIES,
				"2026-10-02T08:00:00Z", List.of(new TeaArtifactFormatDocument("application/json", "VEX statements",
						"https://cdn.example.com/vex.json", "https://cdn.example.com/vex.json.sig", sums)));
		return new TeaPublishedEntry(UUID.randomUUID(), tea, 12, TeaArtifactOrigin.REAL, TeaArtifactType.VULNERABILITIES,
				"vex.json", "application/json", sums, "https://cdn.example.com/vex.json", UUID.randomUUID(), 2, frozen);
	}

	private static TeaPublishedCollectionData fullCollection() {
		TeaPublishedCollectionData d = new TeaPublishedCollectionData();
		d.setUuid(UUID.randomUUID());
		d.setVersion(5);
		d.setOrg(UUID.randomUUID());
		d.setRelease(UUID.randomUUID());
		d.setBelongsTo(TeaCollectionBelongsToType.PRODUCT_RELEASE);
		d.setUpdateReasonType(TeaCollectionUpdateReasonType.VEX_UPDATED);
		d.setUpdateReasonComment("revised: vex.json revision 11 -> 12");
		d.setProfileRevision(8);
		d.setEntries(List.of(synthetic(), externalWithSignature()));
		d.setDocument("{\"uuid\":\"" + d.getUuid() + "\"}");
		d.setDocumentSha256("0f".repeat(32));
		d.setCreatedBy(UUID.randomUUID());
		d.setCreatedDate(T2.truncatedTo(ChronoUnit.MICROS));
		return d;
	}

	private static TeaPublishedCollection rowOf(TeaPublishedCollectionData d, Map<String, Object> recordData) {
		TeaPublishedCollection c = new TeaPublishedCollection();
		c.setUuid(d.getUuid());
		c.setVersion(d.getVersion());
		c.setOrg(d.getOrg());
		c.setRelease(d.getRelease());
		c.setCreatedDate(d.getCreatedDate());
		c.setCreatedBy(d.getCreatedBy());
		c.setDocument(d.getDocument());
		c.setDocumentSha256(d.getDocumentSha256());
		c.setRecordData(recordData);
		return c;
	}

	@Test
	void aCollectionRoundTripsEveryFieldEntriesIncluded() {
		TeaPublishedCollectionData d = fullCollection();
		Map<String, Object> record = d.toRecordData();
		assertTrue(!record.containsKey("document") && !record.containsKey("documentSha256") && !record.containsKey("createdBy"),
				"the document, its sha256 and the creator are columns, not record fields: " + record.keySet());

		TeaPublishedCollectionData read = TeaPublishedCollectionData.dataFromRecord(rowOf(d, record));

		assertEquals(d, read);
		assertEquals(2, read.getEntries().size());
		TeaPublishedEntry ext = read.getEntries().get(1);
		assertEquals(d.getEntries().get(1), ext);
		assertEquals(TeaArtifactOrigin.REAL, ext.origin());
		assertEquals(TeaArtifactType.VULNERABILITIES, ext.type());
		assertEquals(Integer.valueOf(2), ext.signatureRevision());
		assertNotNull(ext.signatureArtifact());
		assertEquals("https://cdn.example.com/vex.json", ext.frozen().formats().get(0).url());
		assertEquals("https://cdn.example.com/vex.json.sig", ext.frozen().formats().get(0).signatureUrl());
		assertNull(read.getEntries().get(0).frozen().formats().get(0).signatureUrl());
		assertNull(read.getEntries().get(0).url());
		assertNull(read.getEntries().get(0).signatureRevision());
	}

	@Test
	void anEntryRecordsTeaTypeNotTheGraphqlAlias() {
		Map<String, Object> record = fullCollection().toRecordData();
		@SuppressWarnings("unchecked")
		Map<String, Object> first = (Map<String, Object>) ((List<?>) record.get("entries")).get(0);
		assertEquals("BOM", first.get("teaType"));
		assertTrue(!first.containsKey("type"), "type() is the GraphQL alias of teaType, not stored: " + first.keySet());
	}

	@Test
	@SuppressWarnings("unchecked")
	void aCollectionRecordWithUnknownKeysReads() {
		TeaPublishedCollectionData d = fullCollection();
		Map<String, Object> record = d.toRecordData();
		record.put("aFieldOfALaterVersion", "x");
		Map<String, Object> entry = (Map<String, Object>) ((List<?>) record.get("entries")).get(1);
		entry.put("anEntryFieldOfALaterVersion", 42);
		Map<String, Object> frozen = (Map<String, Object>) entry.get("frozen");
		frozen.put("distributionIds", List.of("eu"));

		TeaPublishedCollectionData read = TeaPublishedCollectionData.dataFromRecord(rowOf(d, record));

		assertEquals(d, read);
	}

	@Test
	void aCollectionOfAnotherSchemaVersionIsRefused() {
		TeaPublishedCollectionData d = fullCollection();
		TeaPublishedCollection row = rowOf(d, d.toRecordData());
		row.setSchemaVersion(1);
		assertThrows(IllegalStateException.class, () -> TeaPublishedCollectionData.dataFromRecord(row));
	}

	// ------------------------------------------------------------------ task TEA-28, design 4.9

	@Test
	void anEntryRoundTripsItsSbomProfileFields() {
		TeaPublishedCollectionData d = fullCollection();
		TeaPublishedEntry e = TeaPublishedCollectionData.dataFromRecord(rowOf(d, d.toRecordData())).getEntries().get(0);
		assertEquals(d.getEntries().get(0), e);
		assertEquals("fda-premarket.v2", e.sbomProfileName());
		assertEquals(SBOM_PROFILE, e.sbomProfileUuid());
		assertEquals(Integer.valueOf(4), e.sbomProfileRevision());
	}

	@Test
	void theTwelveArgumentEntryLeavesTheSbomProfileFieldsNull() {
		TeaPublishedEntry e = externalWithSignature();
		assertNull(e.sbomProfileName());
		assertNull(e.sbomProfileUuid());
		assertNull(e.sbomProfileRevision());
		TeaPublishedCollectionData d = fullCollection();
		TeaPublishedEntry read = TeaPublishedCollectionData.dataFromRecord(rowOf(d, d.toRecordData())).getEntries().get(1);
		assertNull(read.sbomProfileName());
		assertNull(read.sbomProfileUuid());
		assertNull(read.sbomProfileRevision());
	}

	@Test
	void theHeadsAggregateListRoundTripsInOrder() {
		TeaPublicationData d = fullPublication();
		TeaPublicationData read = TeaPublicationData.dataFromRecord(rowOf(d, d.toRecordData()));
		assertEquals(d.getAggregateArtifacts(), read.getAggregateArtifacts());
		assertEquals(List.of("default", "top"), read.getAggregateArtifacts().stream()
				.map(TeaAggregateArtifact::profileName).toList());
	}

	@Test
	void aHeadRecordWithAStaleAggregateArtifactKeyAndNoListReadsAnEmptyList() {
		TeaPublicationData d = fullPublication();
		Map<String, Object> record = new LinkedHashMap<>(d.toRecordData());
		record.remove("aggregateArtifacts");
		record.put("aggregateArtifact", UUID.randomUUID().toString());
		TeaPublicationData read = TeaPublicationData.dataFromRecord(rowOf(d, record));
		assertNotNull(read.getAggregateArtifacts());
		assertTrue(read.getAggregateArtifacts().isEmpty());

		record.put("aggregateArtifacts", null);
		assertTrue(TeaPublicationData.dataFromRecord(rowOf(d, record)).getAggregateArtifacts().isEmpty(),
				"a null list reads empty");
	}

	@Test
	@SuppressWarnings("unchecked")
	void aSyntheticEntryWithoutTheSbomProfileFieldsReadsThemNull() {
		TeaPublishedCollectionData d = fullCollection();
		Map<String, Object> record = d.toRecordData();
		Map<String, Object> entry = (Map<String, Object>) ((List<?>) record.get("entries")).get(0);
		entry.remove("sbomProfileName");
		entry.remove("sbomProfileUuid");
		entry.remove("sbomProfileRevision");
		TeaPublishedEntry read = TeaPublishedCollectionData.dataFromRecord(rowOf(d, record)).getEntries().get(0);
		assertEquals(TeaArtifactOrigin.SYNTHETIC, read.origin());
		assertNull(read.sbomProfileName());
		assertNull(read.sbomProfileUuid());
		assertNull(read.sbomProfileRevision());
		assertEquals(d.getEntries().get(0).frozen(), read.frozen());
	}
}
