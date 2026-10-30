/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Organization;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Task TEA-28, design 4.16 (F-5, threat-model mitigation M1), end to end against the database with two
 * organizations: a component WRITE user's tags on a synthetic row of another writer (the four TEA keys, stored
 * {@code Removable.YES} by {@code updateArtifactTags}, naming organization B's publication) are inert: the row
 * is never adopted or touched and B's head and versions stay byte-identical. After a database edit makes them
 * system tags, the publication uuid guard still takes a fresh uuid with one WARN.
 */
class TeaPublicationForeignTagsIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired SyntheticArtifactService syntheticArtifactService;

	private static final List<String> TEA_KEYS = List.of(CommonVariables.TEA_AGGREGATE_TAG_KEY,
			CommonVariables.TEA_PROFILE_NAME_TAG_KEY, CommonVariables.TEA_PUBLICATION_TAG_KEY,
			CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY);

	/** B's head row and every collection row under it, read by JDBC, as text. */
	private List<String> snapshotOf(UUID publication) {
		List<String> out = new ArrayList<>(jdbc.query("SELECT record_data::text, revision::text, last_updated_date::text, "
				+ "org::text, release::text FROM rearm.tea_publications WHERE uuid = ?",
				(rs, i) -> String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5)), publication));
		out.addAll(jdbc.query("SELECT version::text, document, document_sha256, record_data::text FROM "
				+ "rearm.tea_collections WHERE uuid = ? ORDER BY version", (rs, i) -> String.join("|", rs.getString(1),
						rs.getString(2), rs.getString(3), rs.getString(4)), publication));
		return out;
	}

	private String artifactRecord(UUID artifact) {
		return jdbc.queryForObject("SELECT record_data::text FROM rearm.artifacts WHERE uuid = ?", String.class, artifact);
	}

	@Test
	void aRowOfAnotherWriterTaggedByAUserIsNeverAdoptedAndAnotherOrganizationsPublicationIsNeverWritten()
			throws Exception {
		// organization B and its PUBLIC publication H
		Organization b = testInitializer.obtainOrganization();
		teaProfileService.saveProfile(b.getUuid(), TeaProfileScope.ORGANIZATION, null, profileInput(TeaPublishing.ENABLED,
				TeaVisibility.PUBLIC, TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT).build(),
				TeaCaller.ORG_ADMIN, WU);
		World bw = componentReleaseIn(b, "pkg:generic/b-secret@1.0.0");
		UUID held = publish(bw.release(), TeaPublishMode.PUBLISH).publication().getUuid();
		List<String> heldBefore = snapshotOf(held);
		assertEquals(2, heldBefore.size(), "H's head and its version 1");

		// organization A's release with a synthetic row of another writer, then a user's tags on it
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World a = componentRelease("pkg:generic/pump@1.0.0");
		ObjectNode otherDoc = (ObjectNode) mergedDocument(a.release(), List.of("pkg:generic/other@1"));
		UUID foreign = syntheticArtifactService.write(a.release(), ArtifactDto.builder().type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).displayIdentifier("other-writer.cdx.json")
				.tags(new ArrayList<>(List.of(new TagRecord("otherWriter", "true", Removable.NO)))).build(),
				new TeaPublicationService.NamedBytes(Utils.OM.writeValueAsBytes(otherDoc), "other-writer.cdx.json"), null, WU);
		String digest = TeaCollectionAssembler.contentDigest(mergedDocument(a.release(), mergedPurls.get(a.release())));
		artifactService.updateArtifactTags(foreign, List.of(new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true"),
				new TagRecord(CommonVariables.TEA_PROFILE_NAME_TAG_KEY, "default"),
				new TagRecord(CommonVariables.TEA_PUBLICATION_TAG_KEY, held.toString()),
				new TagRecord(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, digest)), WU);
		ArtifactData foreignBefore = artifactService.getArtifactData(foreign).orElseThrow();
		assertTrue(foreignBefore.getTags().stream().filter(t -> TEA_KEYS.contains(t.key()))
				.allMatch(t -> Removable.YES == t.removable()), "stored as a user's tags");
		assertTrue(foreignBefore.getTags().contains(new TagRecord("otherWriter", "true", Removable.NO)));
		String foreignRecord = artifactRecord(foreign);

		assertEquals(TeaPublishOutcome.PUBLISHED, publish(a.release(), TeaPublishMode.PUBLISH).outcome());

		UUID aPub = teaPublicationService.getPublicationOfRelease(a.release()).orElseThrow().getUuid();
		assertNotEquals(held, aPub);
		List<UUID> synthetic = stored(a.release()).getSyntheticArtifacts();
		assertEquals(2, synthetic.size(), synthetic.toString());
		assertEquals(foreign, synthetic.get(0));
		UUID aggregate = TeaAggregates.defaultAggregate(teaPublicationService.getPublicationOfRelease(a.release())
				.orElseThrow());
		assertEquals(synthetic.get(1), aggregate);
		ArtifactData foreignAfter = artifactService.getArtifactData(foreign).orElseThrow();
		assertEquals(foreignRecord, artifactRecord(foreign), "the foreign row byte-identical");
		assertEquals(foreignBefore.getInternalBom().id(), foreignAfter.getInternalBom().id());
		assertEquals(foreignBefore.getVersion(), foreignAfter.getVersion());
		assertNull(foreignAfter.getTeaUuid(), "never a TEA artifact");
		TeaPublishedCollectionData v1 = teaPublicationService.getLatestCollection(aPub).orElseThrow();
		List<TeaPublishedEntry> boms = v1.getEntries().stream().filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin())
				.toList();
		assertEquals(1, boms.size());
		assertEquals(aggregate, boms.get(0).artifact());
		JsonNode served = uploaded.get(artifactService.getArtifactData(aggregate).orElseThrow().getInternalBom().id());
		assertTrue(served.toString().contains("release-" + a.release()), "A's merge");
		assertEquals(heldBefore, snapshotOf(held), "H's head and versions byte-identical");

		// a state only a database edit reaches: the four keys as system tags, and A's head gone
		jdbc.update("UPDATE rearm.artifacts SET record_data = jsonb_set(record_data, '{tags}', (SELECT jsonb_agg(CASE "
				+ "WHEN t->>'key' IN ('teaAggregate', 'teaProfileName', 'teaPublication', 'teaContentDigest') THEN "
				+ "jsonb_set(t, '{removable}', '\"NO\"') ELSE t END ORDER BY ord) FROM jsonb_array_elements("
				+ "record_data->'tags') WITH ORDINALITY x(t, ord))) WHERE uuid = ?", foreign);
		assertEquals(1, jdbc.update("DELETE FROM rearm.tea_publications WHERE uuid = ?", aPub));
		List<String> warnings = Collections.synchronizedList(new ArrayList<>());
		AbstractAppender appender = new AbstractAppender("tea28-" + UUID.randomUUID(), null, null, true,
				Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (Level.WARN == event.getLevel()) warnings.add(event.getMessage().getFormattedMessage());
			}
		};
		appender.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(TeaPublicationService.class);
		logger.addAppender(appender);
		try {
			assertEquals(TeaPublishOutcome.PUBLISHED, publish(a.release(), TeaPublishMode.PUBLISH).outcome());
		} finally {
			logger.removeAppender(appender);
			appender.stop();
		}
		UUID fresh = teaPublicationService.getPublicationOfRelease(a.release()).orElseThrow().getUuid();
		assertNotEquals(held, fresh);
		assertNotEquals(aPub, fresh);
		List<String> guard = warnings.stream().filter(w -> w.contains("held by another release")).toList();
		assertEquals(1, guard.size(), warnings.toString());
		assertTrue(guard.get(0).contains(held.toString()) && guard.get(0).contains(foreign.toString())
				&& guard.get(0).contains(a.release().toString()), guard.get(0));
		assertEquals(heldBefore, snapshotOf(held), "H still byte-identical");
	}
}
