/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
import io.reliza.model.tea.publication.TeaCollectionDocument;
import io.reliza.model.tea.publication.TeaUpdateReasonDocument;
import io.reliza.service.TeaCollectionAssembler.Changes;
import tools.jackson.databind.JsonNode;

/**
 * Task TEA-5, design 4.2 cases 18 to 20: the content digest that decides whether an aggregate
 * changed, the classification of a collection version and its update-reason precedence, and the
 * canonical collection document.
 */
class TeaCollectionAssemblerTest {

	// ------------------------------------------------------------------ case 18: contentDigest

	private static final String BOM = """
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "serialNumber":"urn:uuid:11111111-1111-1111-1111-111111111111","version":1,
			 "metadata":{"timestamp":"2026-10-01T10:00:00Z","component":{"name":"widget","version":"1.0"}},
			 "components":[{"type":"library","name":"left-pad","version":"1.3.0","purl":"pkg:npm/left-pad@1.3.0"},
			               {"type":"library","name":"lodash","version":"4.17.21","purl":"pkg:npm/lodash@4.17.21"}]}
			""";

	private static JsonNode json(String s) {
		return Utils.OM.readTree(s);
	}

	@Test
	void theDigestIgnoresSerialNumberVersionAndMetadataTimestamp() {
		String rewritten = BOM.replace("urn:uuid:11111111-1111-1111-1111-111111111111",
				"urn:uuid:22222222-2222-2222-2222-222222222222").replace("\"version\":1,", "\"version\":7,")
				.replace("2026-10-01T10:00:00Z", "2026-10-03T23:59:59.123Z");
		assertNotEquals(BOM, rewritten, "the fixture rewrite must have changed the text");
		assertEquals(TeaCollectionAssembler.contentDigest(json(BOM)), TeaCollectionAssembler.contentDigest(json(rewritten)));
	}

	@Test
	void theDigestMovesWhenAComponentChanges() {
		String bumped = BOM.replace("\"4.17.21\"", "\"4.17.22\"");
		assertNotEquals(BOM, bumped);
		assertNotEquals(TeaCollectionAssembler.contentDigest(json(BOM)), TeaCollectionAssembler.contentDigest(json(bumped)));
		JsonNode dropped = json(BOM);
		((tools.jackson.databind.node.ArrayNode) dropped.get("components")).remove(1);
		assertEquals(1, dropped.get("components").size());
		assertNotEquals(TeaCollectionAssembler.contentDigest(json(BOM)), TeaCollectionAssembler.contentDigest(dropped));
	}

	@Test
	void theDigestCountsTheRestOfMetadata() {
		String renamed = BOM.replace("\"name\":\"widget\"", "\"name\":\"gadget\"");
		assertNotEquals(BOM, renamed);
		assertNotEquals(TeaCollectionAssembler.contentDigest(json(BOM)), TeaCollectionAssembler.contentDigest(json(renamed)));
	}

	@Test
	void propertyOrderDoesNotMoveTheDigest() {
		String reordered = """
				{"components":[{"purl":"pkg:npm/left-pad@1.3.0","version":"1.3.0","name":"left-pad","type":"library"},
				               {"version":"4.17.21","purl":"pkg:npm/lodash@4.17.21","type":"library","name":"lodash"}],
				 "metadata":{"component":{"version":"1.0","name":"widget"},"timestamp":"2026-10-01T10:00:00Z"},
				 "version":1,"specVersion":"1.6","bomFormat":"CycloneDX",
				 "serialNumber":"urn:uuid:11111111-1111-1111-1111-111111111111"}
				""";
		assertEquals(TeaCollectionAssembler.contentDigest(json(BOM)), TeaCollectionAssembler.contentDigest(json(reordered)));
	}

	@Test
	void theDigestIsLowercaseSha256AndLeavesItsInputAlone() {
		JsonNode in = json(BOM);
		String before = in.toString();
		String digest = TeaCollectionAssembler.contentDigest(in);
		assertTrue(digest.matches("[0-9a-f]{64}"), digest);
		assertEquals(before, in.toString(), "the digest works on a copy");
	}

	// ------------------------------------------------------------------ case 19: classify and precedence

	private static TeaPublishedEntry entry(UUID teaUuid, int revision, TeaArtifactType type, TeaArtifactOrigin origin,
			String name) {
		TeaArtifactDocument frozen = new TeaArtifactDocument(teaUuid, revision, name, type, "2026-10-03T10:00:00Z",
				List.of(new TeaArtifactFormatDocument("application/octet-stream", null, null, null,
						List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "ab".repeat(32))))));
		return new TeaPublishedEntry(UUID.randomUUID(), teaUuid, revision, origin, type, name, "application/octet-stream",
				frozen.formats().get(0).checksums(), null, null, null, frozen);
	}

	private static TeaPublishedEntry real(UUID teaUuid, int revision, TeaArtifactType type, String name) {
		return entry(teaUuid, revision, type, TeaArtifactOrigin.REAL, name);
	}

	private static TeaPublishedEntry revised(TeaPublishedEntry e, int revision) {
		return entry(e.teaUuid(), revision, e.teaType(), e.origin(), e.name());
	}

	/** The change kinds, in the precedence order of the spec's collection-update-reason-type. */
	enum Kind {
		VEX(TeaCollectionUpdateReasonType.VEX_UPDATED),
		REMOVED(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED),
		ADDED(TeaCollectionUpdateReasonType.ARTIFACT_ADDED),
		UPDATED(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED);

		final TeaCollectionUpdateReasonType reason;

		Kind(TeaCollectionUpdateReasonType reason) {
			this.reason = reason;
		}
	}

	/** A previous and a next entry list that differ by exactly the given kinds of change. */
	private static List<List<TeaPublishedEntry>> scenario(Set<Kind> kinds, boolean vexByRevision) {
		TeaPublishedEntry aggregate = entry(UUID.randomUUID(), 1, TeaArtifactType.BOM, TeaArtifactOrigin.SYNTHETIC,
				"widget-1.0-tea-aggregate.cdx.json");
		TeaPublishedEntry stays = real(UUID.randomUUID(), 3, TeaArtifactType.BOM, "stays.cdx.json");
		List<TeaPublishedEntry> previous = new ArrayList<>(List.of(aggregate, stays));
		List<TeaPublishedEntry> next = new ArrayList<>(List.of(aggregate, stays));
		if (kinds.contains(Kind.VEX)) {
			TeaPublishedEntry vex = real(UUID.randomUUID(), 1, TeaArtifactType.VULNERABILITIES, "vex.json");
			if (vexByRevision) {
				previous.add(vex);
				next.add(revised(vex, 2));
			} else {
				next.add(vex);
			}
		}
		if (kinds.contains(Kind.REMOVED)) previous.add(real(UUID.randomUUID(), 1, TeaArtifactType.LICENSE, "gone.txt"));
		if (kinds.contains(Kind.ADDED)) next.add(real(UUID.randomUUID(), 1, TeaArtifactType.RELEASE_NOTES, "notes.md"));
		if (kinds.contains(Kind.UPDATED)) {
			TeaPublishedEntry upd = real(UUID.randomUUID(), 4, TeaArtifactType.ATTESTATION, "provenance.json");
			previous.add(upd);
			next.add(revised(upd, 5));
		}
		return List.of(previous, next);
	}

	static Stream<Arguments> everySingleAndPairOfKinds() {
		List<Arguments> out = new ArrayList<>();
		Kind[] all = Kind.values();
		for (int i = 0; i < all.length; i++) {
			for (boolean byRevision : new boolean[] { false, true }) {
				if (Kind.VEX != all[i] && byRevision) continue;
				out.add(Arguments.of(EnumSet.of(all[i]), byRevision));
				for (int j = i + 1; j < all.length; j++) out.add(Arguments.of(EnumSet.of(all[i], all[j]), byRevision));
			}
		}
		return out.stream();
	}

	@ParameterizedTest(name = "{0}, vex by revision {1}")
	@MethodSource("everySingleAndPairOfKinds")
	void theFirstApplicableKindWins(EnumSet<Kind> kinds, boolean vexByRevision) {
		List<List<TeaPublishedEntry>> s = scenario(kinds, vexByRevision);
		Changes c = TeaCollectionAssembler.classify(s.get(0), s.get(1));
		assertFalse(c.isEmpty());
		Kind expected = kinds.stream().min(Enum::compareTo).orElseThrow();
		assertEquals(Optional.of(expected.reason), TeaCollectionAssembler.updateReason(c));
	}

	@Test
	void allFourKindsAnswerVexUpdated() {
		List<List<TeaPublishedEntry>> s = scenario(EnumSet.allOf(Kind.class), false);
		Changes c = TeaCollectionAssembler.classify(s.get(0), s.get(1));
		assertEquals(1, c.removed().size());
		assertEquals(2, c.added().size());
		assertEquals(1, c.revised().size());
		assertEquals(Optional.of(TeaCollectionUpdateReasonType.VEX_UPDATED), TeaCollectionAssembler.updateReason(c));
	}

	@Test
	void aRemovedVulnerabilitiesEntryIsARemovalNotAVexUpdate() {
		TeaPublishedEntry aggregate = entry(UUID.randomUUID(), 1, TeaArtifactType.BOM, TeaArtifactOrigin.SYNTHETIC, "agg");
		TeaPublishedEntry vex = real(UUID.randomUUID(), 1, TeaArtifactType.VULNERABILITIES, "vex.json");
		Changes c = TeaCollectionAssembler.classify(List.of(aggregate, vex), List.of(aggregate));
		assertEquals(Optional.of(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED), TeaCollectionAssembler.updateReason(c));
	}

	@Test
	void noChangeAnswersEmpty() {
		List<List<TeaPublishedEntry>> s = scenario(EnumSet.noneOf(Kind.class), false);
		Changes c = TeaCollectionAssembler.classify(s.get(0), s.get(1));
		assertTrue(c.isEmpty());
		assertEquals(Optional.empty(), TeaCollectionAssembler.updateReason(c));
	}

	@Test
	void entriesAreKeyedByTeaIdNotByPosition() {
		TeaPublishedEntry a = real(UUID.randomUUID(), 1, TeaArtifactType.BOM, "a");
		TeaPublishedEntry b = real(UUID.randomUUID(), 2, TeaArtifactType.LICENSE, "b");
		Changes c = TeaCollectionAssembler.classify(List.of(a, b), List.of(b, a));
		assertTrue(c.isEmpty(), "a reordered entry list is no change");
	}

	@Test
	void aFirstVersionAnswersInitialRelease() {
		TeaPublishedEntry aggregate = entry(UUID.randomUUID(), 1, TeaArtifactType.BOM, TeaArtifactOrigin.SYNTHETIC, "agg");
		TeaPublishedEntry vex = real(UUID.randomUUID(), 1, TeaArtifactType.VULNERABILITIES, "vex.json");
		Changes c = TeaCollectionAssembler.classify(null, List.of(aggregate, vex));
		assertTrue(c.first());
		assertFalse(c.isEmpty());
		assertEquals(Optional.of(TeaCollectionUpdateReasonType.INITIAL_RELEASE), TeaCollectionAssembler.updateReason(c),
				"the first version is INITIAL_RELEASE even when it carries a VULNERABILITIES entry");
	}

	@Test
	void theCommentListsEveryChangeThenTheCallersCommentInAscii() {
		TeaPublishedEntry aggregate = entry(UUID.randomUUID(), 1, TeaArtifactType.BOM, TeaArtifactOrigin.SYNTHETIC, "agg");
		TeaPublishedEntry gone = real(UUID.randomUUID(), 1, TeaArtifactType.LICENSE, "gone.txt");
		TeaPublishedEntry added = real(UUID.randomUUID(), 1, TeaArtifactType.RELEASE_NOTES, "notes.md");
		TeaPublishedEntry upd = real(UUID.randomUUID(), 4, TeaArtifactType.ATTESTATION, "prov.json");
		Changes c = TeaCollectionAssembler.classify(List.of(aggregate, gone, upd),
				List.of(revised(aggregate, 2), added, revised(upd, 5)));
		String comment = TeaCollectionAssembler.updateComment(c, "  café release\n ");
		assertEquals("aggregate SBOM agg regenerated: revision 1 -> 2; added: notes.md (" + added.teaUuid() + "); removed: gone.txt ("
				+ gone.teaUuid() + "); revised: prov.json (" + upd.teaUuid() + ") revision 4 -> 5; caf? release", comment);
		assertEquals("initial publication: 1 artifact",
				TeaCollectionAssembler.updateComment(TeaCollectionAssembler.classify(null, List.of(aggregate)), null));
	}

	@Test
	void carryForwardKeepsTheFrozenEntryOfAnUnchangedRevision() {
		TeaPublishedEntry old = real(UUID.randomUUID(), 3, TeaArtifactType.BOM, "x");
		TeaPublishedEntry sameRevisionFresh = real(old.teaUuid(), 3, TeaArtifactType.BOM, "x");
		TeaPublishedEntry newRevision = real(old.teaUuid(), 4, TeaArtifactType.BOM, "x");
		assertSame(old, TeaCollectionAssembler.carryForward(List.of(old), List.of(sameRevisionFresh)).get(0));
		assertSame(newRevision, TeaCollectionAssembler.carryForward(List.of(old), List.of(newRevision)).get(0));
	}

	// ------------------------------------------------------------------ task TEA-28, design 4.8

	private static TeaPublishedEntry aggregate(String profile, int revision) {
		return entry(UUID.randomUUID(), revision, TeaArtifactType.BOM, TeaArtifactOrigin.SYNTHETIC, profile);
	}

	@Test
	void theCommentNamesTheProfileOfEveryRevisedAggregate() {
		TeaPublishedEntry def = aggregate("default", 1);
		TeaPublishedEntry top = aggregate("top", 3);
		Changes c = TeaCollectionAssembler.classify(List.of(def, top), List.of(revised(def, 2), revised(top, 4)));
		assertEquals("aggregate SBOM default regenerated: revision 1 -> 2; aggregate SBOM top regenerated: revision 3 -> 4",
				TeaCollectionAssembler.updateComment(c, null));
	}

	@Test
	void twoSyntheticEntriesClassifyByThePrecedenceRule() {
		TeaPublishedEntry def = aggregate("default", 1);
		TeaPublishedEntry top = aggregate("top", 1);
		TeaPublishedEntry third = aggregate("third", 1);
		Changes bothRevised = TeaCollectionAssembler.classify(List.of(def, top), List.of(revised(def, 2), revised(top, 2)));
		assertEquals(2, bothRevised.revised().size());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, TeaCollectionAssembler.updateReason(bothRevised).orElseThrow());
		Changes removedAndRevised = TeaCollectionAssembler.classify(List.of(def, top), List.of(revised(def, 2)));
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED,
				TeaCollectionAssembler.updateReason(removedAndRevised).orElseThrow());
		Changes addedAndRevised = TeaCollectionAssembler.classify(List.of(def, top),
				List.of(revised(def, 2), top, third));
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED,
				TeaCollectionAssembler.updateReason(addedAndRevised).orElseThrow());
	}

	@Test
	void carryForwardKeepsALegacyNamedSyntheticEntryVerbatimBesideANewNamedOne() {
		TeaPublishedEntry legacy = aggregate("pump-1.2.3-tea-aggregate.cdx.json", 1);
		TeaPublishedEntry freshLegacy = entry(legacy.teaUuid(), 1, TeaArtifactType.BOM, TeaArtifactOrigin.SYNTHETIC, "default");
		TeaPublishedEntry top = aggregate("top", 1);
		List<TeaPublishedEntry> out = TeaCollectionAssembler.carryForward(List.of(legacy), List.of(freshLegacy, top));
		assertSame(legacy, out.get(0), "the legacy revision keeps its frozen file-style name");
		assertSame(top, out.get(1));
	}

	@Test
	void carryForwardTakesTheLatestVersionsEntryAndNeverAsksHistoryForIt() {
		TeaPublishedEntry old = aggregate("default", 2);
		List<TeaPublishedEntry> asked = new ArrayList<>();
		List<TeaPublishedEntry> out = TeaCollectionAssembler.carryForward(List.of(old), List.of(revised(old, 2)), e -> {
			asked.add(e);
			return Optional.empty();
		});
		assertSame(old, out.get(0));
		assertTrue(asked.isEmpty());
	}

	@Test
	void carryForwardAsksHistoryOnceForAnEntryTheLatestVersionLacks() {
		TeaPublishedEntry def = aggregate("default", 1);
		TeaPublishedEntry readded = aggregate("top", 2);
		TeaPublishedEntry frozen = entry(readded.teaUuid(), 2, TeaArtifactType.BOM, TeaArtifactOrigin.SYNTHETIC, "top");
		TeaPublishedEntry missed = aggregate("third", 1);
		List<TeaPublishedEntry> asked = new ArrayList<>();
		List<TeaPublishedEntry> out = TeaCollectionAssembler.carryForward(List.of(def), List.of(def, readded, missed), e -> {
			asked.add(e);
			return e == readded ? Optional.of(frozen) : Optional.empty();
		});
		assertEquals(List.of(readded, missed), asked, "once per entry the latest version lacks");
		assertSame(frozen, out.get(1), "a hit replaces the fresh entry");
		assertSame(missed, out.get(2), "a miss keeps it fresh");
	}

	@Test
	void carryForwardAsksHistoryForAnEntryAtAnotherRevision() {
		TeaPublishedEntry old = aggregate("default", 1);
		TeaPublishedEntry bumped = revised(old, 2);
		List<TeaPublishedEntry> asked = new ArrayList<>();
		List<TeaPublishedEntry> out = TeaCollectionAssembler.carryForward(List.of(old), List.of(bumped), e -> {
			asked.add(e);
			return Optional.empty();
		});
		assertEquals(List.of(bumped), asked);
		assertSame(bumped, out.get(0));
	}

	@Test
	void theTwoArgumentFormAndANullPreviousAskNothing() {
		TeaPublishedEntry e = aggregate("default", 1);
		List<TeaPublishedEntry> asked = new ArrayList<>();
		List<TeaPublishedEntry> entries = List.of(e);
		assertSame(entries, TeaCollectionAssembler.carryForward(null, entries, x -> {
			asked.add(x);
			return Optional.empty();
		}));
		assertTrue(asked.isEmpty());
		TeaPublishedEntry other = aggregate("top", 1);
		assertSame(e, TeaCollectionAssembler.carryForward(List.of(other), entries).get(0),
				"the two-argument form: absent from previous, fresh");
	}

	// ------------------------------------------------------------------ case 20: the document

	@Test
	void theDocumentHasSchemaOrderOmitsNullsAndWritesInstants() {
		UUID publication = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
		UUID tea = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
		String createdDate = ZonedDateTime.of(2026, 10, 3, 19, 0, 0, 0, ZoneId.of("Asia/Tokyo")).toInstant().toString();
		assertEquals("2026-10-03T10:00:00Z", createdDate, "the service writes createdDate as Instant.toString()");
		TeaArtifactDocument frozen = new TeaArtifactDocument(tea, 2, "widget.cdx.json", TeaArtifactType.BOM,
				"2026-10-02T08:30:00Z", List.of(new TeaArtifactFormatDocument("application/vnd.cyclonedx+json", null, null,
						null, List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "0f".repeat(32))))));
		TeaPublishedEntry e = new TeaPublishedEntry(UUID.randomUUID(), tea, 2, TeaArtifactOrigin.SYNTHETIC,
				TeaArtifactType.BOM, "widget.cdx.json", "application/vnd.cyclonedx+json", frozen.formats().get(0).checksums(),
				null, null, null, frozen);

		String doc = TeaCollectionAssembler.document(publication, 3, createdDate, TeaCollectionBelongsToType.COMPONENT_RELEASE,
				TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, null, List.of(e));

		assertEquals("{\"uuid\":\"aaaaaaaa-0000-0000-0000-000000000001\",\"version\":3,\"createdDate\":\"2026-10-03T10:00:00Z\","
				+ "\"belongsTo\":\"COMPONENT_RELEASE\",\"updateReason\":{\"type\":\"ARTIFACT_UPDATED\"},"
				+ "\"artifacts\":[{\"uuid\":\"bbbbbbbb-0000-0000-0000-000000000002\",\"version\":2,\"name\":\"widget.cdx.json\","
				+ "\"type\":\"BOM\",\"createdDate\":\"2026-10-02T08:30:00Z\",\"formats\":[{\"mediaType\":"
				+ "\"application/vnd.cyclonedx+json\",\"checksums\":[{\"algType\":\"SHA-256\",\"algValue\":\""
				+ "0f".repeat(32) + "\"}]}]}]}", doc);
		assertFalse(doc.contains("null"), "absent url, description and comment are absent, not null");

		String withComment = TeaCollectionAssembler.document(publication, 3, createdDate,
				TeaCollectionBelongsToType.PRODUCT_RELEASE, TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, "why", List.of(e));
		assertTrue(withComment.contains("\"belongsTo\":\"PRODUCT_RELEASE\",\"updateReason\":{\"type\":\"ARTIFACT_UPDATED\",\"comment\":\"why\"}"),
				withComment);
		assertFalse(TeaCollectionAssembler.document(publication, 3, createdDate, TeaCollectionBelongsToType.COMPONENT_RELEASE,
				TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, "", List.of(e)).contains("comment"),
				"an empty comment is absent");
	}

	@Test
	void anExternalUrlStandsBetweenDescriptionAndChecksums() {
		TeaArtifactFormatDocument f = new TeaArtifactFormatDocument("application/pdf", "the manual",
				"https://cdn.example.com/m.pdf", null, List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "aa")));
		assertEquals("{\"mediaType\":\"application/pdf\",\"description\":\"the manual\",\"url\":\"https://cdn.example.com/m.pdf\","
				+ "\"checksums\":[{\"algType\":\"SHA-256\",\"algValue\":\"aa\"}]}", Utils.OM.writeValueAsString(f));
	}

	@Test
	void aSignatureUrlStandsBetweenUrlAndChecksums() {
		TeaArtifactFormatDocument f = new TeaArtifactFormatDocument("application/pdf", "the manual",
				"https://cdn.example.com/m.pdf", "https://cdn.example.com/m.pdf.sig",
				List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "aa")));
		assertEquals("{\"mediaType\":\"application/pdf\",\"description\":\"the manual\",\"url\":\"https://cdn.example.com/m.pdf\","
				+ "\"signatureUrl\":\"https://cdn.example.com/m.pdf.sig\","
				+ "\"checksums\":[{\"algType\":\"SHA-256\",\"algValue\":\"aa\"}]}", Utils.OM.writeValueAsString(f));
	}

	@Test
	void theDocumentRoundTripsToEqualRecords() {
		UUID publication = UUID.randomUUID();
		TeaArtifactDocument a = new TeaArtifactDocument(UUID.randomUUID(), 1, "agg.cdx.json", TeaArtifactType.BOM,
				Instant.now().toString(), List.of(new TeaArtifactFormatDocument("application/vnd.cyclonedx+json", null, null,
						null, List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "12".repeat(32))))));
		TeaArtifactDocument b = new TeaArtifactDocument(UUID.randomUUID(), 7, "vex.json", TeaArtifactType.VULNERABILITIES,
				Instant.now().toString(), List.of(new TeaArtifactFormatDocument("application/json", "VEX",
						"https://cdn.example.com/vex.json", "https://cdn.example.com/vex.json.sig",
						List.of(new TeaChecksumDocument(TeaChecksumType.SHA_256, "34".repeat(32)),
								new TeaChecksumDocument(TeaChecksumType.SHA_512, "56".repeat(64))))));
		List<TeaPublishedEntry> entries = List.of(
				new TeaPublishedEntry(UUID.randomUUID(), a.uuid(), 1, TeaArtifactOrigin.SYNTHETIC, a.type(), a.name(),
						"application/vnd.cyclonedx+json", a.formats().get(0).checksums(), null, null, null, a),
				new TeaPublishedEntry(UUID.randomUUID(), b.uuid(), 7, TeaArtifactOrigin.REAL, b.type(), b.name(),
						"application/json", b.formats().get(0).checksums(), "https://cdn.example.com/vex.json", null, null, b));

		String doc = TeaCollectionAssembler.document(publication, 4, Instant.now().toString(),
				TeaCollectionBelongsToType.PRODUCT_RELEASE, TeaCollectionUpdateReasonType.VEX_UPDATED, "vex revised", entries);
		TeaCollectionDocument read = Utils.OM.readValue(doc, TeaCollectionDocument.class);

		assertEquals(new TeaCollectionDocument(publication, 4, read.createdDate(), TeaCollectionBelongsToType.PRODUCT_RELEASE,
				new TeaUpdateReasonDocument(TeaCollectionUpdateReasonType.VEX_UPDATED, "vex revised"), List.of(a, b)), read);
		assertTrue(read.createdDate().endsWith("Z"));
		assertEquals(doc, Utils.OM.writeValueAsString(read), "re-serializing the read document is byte-identical");
	}

	@Test
	void theSha256IsOfTheUtf8DocumentInLowercaseHex() {
		String doc = "{\"uuid\":\"x\",\"name\":\"café\"}";
		String sha = TeaCollectionAssembler.sha256(doc);
		assertEquals(Utils.bytesToHexSha256(doc.getBytes(StandardCharsets.UTF_8)), sha);
		assertTrue(sha.matches("[0-9a-f]{64}"), sha);
	}

	/** Live run 1 on psclaude: rebom's producer BOM-Link names a fresh internal serial on every merge. */
	@Test
	void theRebomProducerLinkIsStrippedSoARemergeOfTheSameInventoryDigestsEqual() throws Exception {
		String base = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,\"components\":[{\"purl\":\"pkg:npm/a@1\"}],"
				+ "\"externalReferences\":[%s{\"type\":\"bom\",\"url\":\"urn:cdx:%s/1\","
				+ "\"comment\":\"Source document as uploaded by the producer\"}]}";
		JsonNode first = Utils.OM.readTree(String.format(base, "", UUID.randomUUID()));
		JsonNode second = Utils.OM.readTree(String.format(base, "", UUID.randomUUID()));
		assertNotEquals(TeaCollectionAssembler.contentDigest(first), TeaCollectionAssembler.contentDigest(second));

		TeaCollectionAssembler.stripRebomProducerLink(first);
		TeaCollectionAssembler.stripRebomProducerLink(second);

		assertFalse(first.has("externalReferences"), first.toString());
		assertEquals(TeaCollectionAssembler.contentDigest(first), TeaCollectionAssembler.contentDigest(second));

		JsonNode kept = TeaCollectionAssembler.stripRebomProducerLink(Utils.OM.readTree(String.format(base,
				"{\"type\":\"website\",\"url\":\"https://example.com\"},", UUID.randomUUID())));
		assertEquals(1, kept.get("externalReferences").size());
		assertEquals("website", kept.get("externalReferences").get(0).get("type").asString());
	}
}
